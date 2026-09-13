package app.nextcloudphoto

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import app.nextcloudphoto.account.Account
import app.nextcloudphoto.network.NextcloudClient
import app.nextcloudphoto.vault.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class VaultVideoTest {
    private val album="12345678-1234-1234-1234-123456789abc"
    private val id="aaaaaaaa-1234-1234-1234-123456789abc"
    private fun store(server: MockWebServer)=VaultStore(ApplicationProvider.getApplicationContext(),NextcloudClient(Account(server.url("/").toString(),"ray","secret")))
    private fun spec(position: Long=0,length: Long=C.LENGTH_UNSET.toLong())=DataSpec.Builder().setUri(Uri.parse("vault://test/video")).setPosition(position).setLength(length).build()

    @Test fun `video uploads bounded encrypted chunks and commits encrypted manifest last`() = runBlocking {
        MockWebServer().use { server ->
            server.start();val store=store(server);val key=VaultCrypto.random(32)
            val plain=ByteArray(2*VIDEO_CHUNK+123) { (it%251).toByte() }
            repeat(4) { server.enqueue(MockResponse().setResponseCode(201)) }
            val progress=mutableListOf<Long>()
            VaultSession(album,ByteArray(0),key).use { session ->
                val videoId=store.importVideo(session,"私密影片.mp4","video/mp4",ByteArrayInputStream(plain),plain.size.toLong()) { progress.add(it) }
                repeat(3) { index ->
                    val request=server.takeRequest()
                    assertEquals("PUT",request.method);assertEquals("*",request.getHeader("If-None-Match"))
                    assertTrue(request.path!!.endsWith("$videoId-$index.nvc"));assertFalse(request.path!!.contains("mp4"))
                    val restored=VaultCrypto.open(key,request.body.readByteArray(),"$album/$videoId/video/$index")
                    assertArrayEquals(plain.copyOfRange(index*VIDEO_CHUNK,minOf(plain.size,(index+1)*VIDEO_CHUNK)),restored);restored.fill(0)
                }
                val request=server.takeRequest();assertTrue(request.path!!.endsWith(".ncv"))
                val bytes=request.body.readByteArray();assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("mp4"))
                val decoded=VaultCrypto.open(key,bytes,"$album/$videoId/video-manifest")
                val manifest=VaultVideo.parse(decoded,videoId);decoded.fill(0)
                assertEquals("私密影片.mp4",manifest.name);assertEquals(plain.size.toLong(),manifest.size);assertEquals(3,manifest.chunks)
                assertEquals(listOf(VIDEO_CHUNK.toLong(),2L*VIDEO_CHUNK,plain.size.toLong()),progress)
            }
        }
    }
    @Test fun `seek and reads cross chunk boundaries with no plaintext disk files`() {
        MockWebServer().use { server ->
            server.start();val key=VaultCrypto.random(32);val plain=ByteArray(VIDEO_CHUNK+99) { (it%251).toByte() }
            val context=ApplicationProvider.getApplicationContext<Context>()
            val before=context.cacheDir.walkTopDown().filter { it.isFile }.map { it.path }.toSet()
            server.dispatcher=object: Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
                val index=request.path!!.substringAfterLast('-').removeSuffix(".nvc").toInt()
                val part=plain.copyOfRange(index*VIDEO_CHUNK,minOf(plain.size,(index+1)*VIDEO_CHUNK))
                return MockResponse().setBody(Buffer().write(VaultCrypto.seal(key,part,"$album/$id/video/$index")))
            } }
            VaultSession(album,ByteArray(0),key).use { session ->
                val source=VaultVideoDataSource(store(server),session,VaultVideo(id,"clip.mp4","video/mp4",plain.size.toLong(),2))
                assertEquals(106L,source.open(spec(VIDEO_CHUNK-7L)))
                val buffer=ByteArray(200)
                assertEquals(7,source.read(buffer,0,200));assertArrayEquals(plain.copyOfRange(VIDEO_CHUNK-7,VIDEO_CHUNK),buffer.copyOf(7))
                assertEquals(99,source.read(buffer,7,193));assertEquals(C.RESULT_END_OF_INPUT,source.read(buffer,106,94))
                assertEquals(0,source.read(buffer,0,0));source.close()
                assertEquals(5L,source.open(spec(12,5)));assertEquals(5,source.read(buffer,0,20));assertArrayEquals(plain.copyOfRange(12,17),buffer.copyOf(5))
                source.close()
                assertEquals(0L,source.open(spec(plain.size.toLong())));assertEquals(C.RESULT_END_OF_INPUT,source.read(buffer,0,1));source.close()
                assertThrows(DataSourceException::class.java) { source.open(spec(plain.size+1L)) }
                assertEquals(3,server.requestCount)
            }
            assertEquals(before,context.cacheDir.walkTopDown().filter { it.isFile }.map { it.path }.toSet())
        }
    }
    @Test fun `tampered and substituted chunks never return unauthenticated bytes`() {
        MockWebServer().use { server ->
            server.start();val key=VaultCrypto.random(32)
            val encrypted=VaultCrypto.seal(key,ByteArray(32) { 7 },"$album/$id/video/1")
            server.enqueue(MockResponse().setBody(Buffer().write(encrypted)))
            VaultSession(album,ByteArray(0),key).use { session ->
                val source=VaultVideoDataSource(store(server),session,VaultVideo(id,"clip.mp4","video/mp4",32,1))
                source.open(spec());val buffer=ByteArray(32) { 42 }
                assertThrows(IOException::class.java) { source.read(buffer,0,32) };assertTrue(buffer.all { it==42.toByte() });source.close()
            }
        }
    }
    @Test fun `locking wipes cached video bytes and forbids reopening`() {
        MockWebServer().use { server ->
            server.start();val key=VaultCrypto.random(32)
            server.enqueue(MockResponse().setBody(Buffer().write(VaultCrypto.seal(key,ByteArray(32) { 7 },"$album/$id/video/0"))))
            val session=VaultSession(album,ByteArray(0),key)
            val source=VaultVideoDataSource(store(server),session,VaultVideo(id,"clip.mp4","video/mp4",32,1))
            source.open(spec());assertEquals(1,source.read(ByteArray(1),0,1))
            val field=source.javaClass.getDeclaredField("chunk").apply { isAccessible=true }
            val buffer=field.get(source) as ByteArray
            session.close();assertTrue(buffer.all { it==0.toByte() });assertNull(source.uri)
            assertThrows(IOException::class.java) { source.read(ByteArray(1),0,1) }
            assertThrows(IOException::class.java) { source.open(spec()) };source.close()
        }
    }
    @Test fun `quota failure never publishes video manifest`() = runBlocking {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setResponseCode(201));server.enqueue(MockResponse().setResponseCode(507))
            VaultSession(album,ByteArray(0),VaultCrypto.random(32)).use { session ->
                try { store(server).importVideo(session,"clip.mp4","video/mp4",ByteArrayInputStream(ByteArray(VIDEO_CHUNK+1)));fail("expected failure") }
                catch(e: app.nextcloudphoto.network.CloudException) { assertEquals(507,e.code) }
            }
            assertEquals(2,server.requestCount);repeat(2) { assertTrue(server.takeRequest().path!!.endsWith(".nvc")) }
        }
    }
    @Test fun `short source and empty videos never publish manifest`() = runBlocking {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setResponseCode(201))
            VaultSession(album,ByteArray(0),VaultCrypto.random(32)).use { session ->
                try { store(server).importVideo(session,"clip.mp4","video/mp4",ByteArrayInputStream(ByteArray(3)),10);fail("expected short read") } catch(_: IllegalArgumentException) { }
                try { store(server).importVideo(session,"clip.mp4","video/mp4",ByteArrayInputStream(ByteArray(0)));fail("expected empty rejection") } catch(_: IllegalArgumentException) { }
            }
            assertEquals(1,server.requestCount);assertTrue(server.takeRequest().path!!.endsWith(".nvc"))
        }
    }
    @Test fun `cancellation after a chunk leaves no visible video`() = runBlocking {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setResponseCode(201))
            VaultSession(album,ByteArray(0),VaultCrypto.random(32)).use { session ->
                val job=launch(Dispatchers.IO) {
                    store(server).importVideo(session,"clip.mp4","video/mp4",ByteArrayInputStream(ByteArray(VIDEO_CHUNK+2))) { cancel() }
                }
                job.join();assertTrue(job.isCancelled)
            }
            assertEquals(1,server.requestCount);assertTrue(server.takeRequest().path!!.endsWith(".nvc"))
        }
    }
    @Test fun `manifest rejects inconsistent lengths and unsupported versions`() {
        val json=org.json.JSONObject(VaultVideo(id,"clip.mp4","video/mp4",VIDEO_CHUNK+1L,2).encode().toString(Charsets.UTF_8))
        json.put("chunks",1)
        assertThrows(IllegalArgumentException::class.java) { VaultVideo.parse(json.toString().toByteArray(),id) }
        json.put("chunks",2).put("version",2)
        assertThrows(IllegalArgumentException::class.java) { VaultVideo.parse(json.toString().toByteArray(),id) }
    }
    @Test fun `seeks beyond two GiB retain long positions and download only requested chunk`() {
        MockWebServer().use { server ->
            server.start();val key=VaultCrypto.random(32)
            val size=3L*1024*1024*1024+5;val index=3072
            server.enqueue(MockResponse().setBody(Buffer().write(VaultCrypto.seal(key,byteArrayOf(1,2,3,4,5),"$album/$id/video/$index"))))
            VaultSession(album,ByteArray(0),key).use { session ->
                val source=VaultVideoDataSource(store(server),session,VaultVideo(id,"large.mp4","video/mp4",size,index+1))
                assertEquals(3L,source.open(spec(size-3)))
                val bytes=ByteArray(3);assertEquals(3,source.read(bytes,0,3));assertArrayEquals(byteArrayOf(3,4,5),bytes)
                source.close();assertTrue(server.takeRequest().path!!.endsWith("-$index.nvc"));assertEquals(1,server.requestCount)
            }
        }
    }
    @Test fun `Media3 extracts H264 video and AAC audio from encrypted MP4 without a plaintext file`() {
        val original=javaClass.getResourceAsStream("/vault-sample.mp4")!!.use { it.readBytes() }
        MockWebServer().use { server ->
            server.start();val key=VaultCrypto.random(32)
            val encrypted=VaultCrypto.seal(key,original,"$album/$id/video/0")
            server.dispatcher=object: Dispatcher() { override fun dispatch(request: RecordedRequest)=MockResponse().setBody(Buffer().write(encrypted)) }
            VaultSession(album,ByteArray(0),key).use { session ->
                val source=VaultVideoDataSource(store(server),session,VaultVideo(id,"sample.mp4","video/mp4",original.size.toLong(),1))
                val extractor=androidx.media3.extractor.mp4.Mp4Extractor()
                val formats=mutableSetOf<String>()
                var samples=0;var seekable=false
                extractor.init(object: androidx.media3.extractor.ExtractorOutput {
                    override fun track(id: Int,type: Int): androidx.media3.extractor.TrackOutput = object: androidx.media3.extractor.TrackOutput by androidx.media3.extractor.DummyTrackOutput() {
                        override fun format(format: androidx.media3.common.Format) { format.sampleMimeType?.let(formats::add) }
                        override fun sampleMetadata(timeUs: Long,flags: Int,size: Int,offset: Int,cryptoData: androidx.media3.extractor.TrackOutput.CryptoData?) { samples++ }
                    }
                    override fun endTracks() { }
                    override fun seekMap(map: androidx.media3.extractor.SeekMap) { seekable=map.isSeekable }
                })
                source.open(spec())
                var input=androidx.media3.extractor.DefaultExtractorInput(source,0,original.size.toLong())
                val position=androidx.media3.extractor.PositionHolder()
                var ended=false
                repeat(500) {
                    if(!ended) when(extractor.read(input,position)) {
                        androidx.media3.extractor.Extractor.RESULT_END_OF_INPUT -> ended=true
                        androidx.media3.extractor.Extractor.RESULT_SEEK -> {
                            source.close();source.open(spec(position.position))
                            input=androidx.media3.extractor.DefaultExtractorInput(source,position.position,original.size.toLong())
                        }
                    }
                }
                source.close();extractor.release()
                assertTrue(ended);assertTrue(seekable);assertTrue(samples>24)
                assertTrue(formats.contains("video/avc"));assertTrue(formats.contains("audio/mp4a-latm"))
            }
        }
    }
}
