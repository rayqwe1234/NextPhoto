package app.nextcloudphoto

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import app.nextcloudphoto.account.*
import app.nextcloudphoto.data.Transfer
import app.nextcloudphoto.transfer.TransferWorker
import app.nextcloudphoto.transfer.digest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class TestPhotoApplication: PhotoApplication() {
    override val accounts=object: CredentialStore {
        private var value: Account?=null
        override fun save(account: Account) { value=account }
        override fun read()=value
        override fun clear() { value=null }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36],application=TestPhotoApplication::class,qualifiers="zh-rTW")
class TransferTest {
    private lateinit var app: TestPhotoApplication
    private lateinit var server: MockWebServer
    private val files=ConcurrentHashMap<String,ByteArray>()
    private var putCount=0
    private var sawProtectedWrite=false
    private var quotaFailure=false
    private val directories=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var finalMoveProtected=false
    @Before fun setup() {
        app=ApplicationProvider.getApplicationContext()
        server=MockWebServer()
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path=request.requestUrl!!.encodedPath
                when(request.method) {
                    "PROPFIND" -> {
                        val bytes=files[path]
                        if(bytes==null && !path.endsWith("/ray") && path !in directories) return MockResponse().setResponseCode(404)
                        return MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:"><d:response><d:href>$path</d:href><d:propstat><d:prop><d:getcontentlength>${bytes?.size ?: 0}</d:getcontentlength><d:getetag>etag</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""")
                    }
                    "PUT" -> {
                        putCount++;sawProtectedWrite=request.getHeader("If-None-Match")=="*"
                        if(quotaFailure) return MockResponse().setResponseCode(507)
                        if(files.containsKey(path)) return MockResponse().setResponseCode(412)
                        files[path]=request.body.readByteArray()
                        return MockResponse().setResponseCode(201)
                    }
                    "GET" -> return files[path]?.let { MockResponse().setBody(okio.Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
                    "MKCOL" -> { directories.add(path); return MockResponse().setResponseCode(201) }
                    "MOVE" -> {
                        finalMoveProtected=request.getHeader("Overwrite")=="F"
                        val destination=java.net.URI(request.getHeader("Destination")!!).rawPath
                        if(files.containsKey(destination)) return MockResponse().setResponseCode(412)
                        val prefix=path.substringBeforeLast('/')+"/"
                        val chunks=files.keys.filter { it.startsWith(prefix) }.sorted()
                        val output=java.io.ByteArrayOutputStream()
                        chunks.forEach { output.write(files[it]!!) }
                        files[destination]=output.toByteArray()
                        return MockResponse().setResponseCode(201)
                    }
                    else -> return MockResponse().setResponseCode(404)
                }
            }
        }
        server.start();app.accounts.save(Account(server.url("/cloud").toString(),"ray","test-password"))
    }
    @After fun cleanup() { server.shutdown();app.database.close() }
    private suspend fun row(id: String="job-one",data: ByteArray="photo-bytes".toByteArray()): Transfer {
        val source=File(app.filesDir,"$id.jpg").apply { writeBytes(data) }
        return Transfer(id,source.toURI().toString(),"source-$id",server.url("/cloud/remote.php/dav/files/ray/photo.jpg").toString(),"photo.jpg","image/jpeg",data.size.toLong(),1000).also { app.database.dao().insertTransfer(it) }
    }
    private suspend fun work()=TestListenableWorkerBuilder<TransferWorker>(app).build().doWork()
    @Test fun `upload succeeds only after protected write and server size verification`()=runBlocking {
        val item=row();assertEquals(ListenableWorker.Result.success(),work())
        assertEquals("DONE",app.database.dao().transfer(item.id)!!.state)
        assertEquals(1,putCount);assertTrue(sawProtectedWrite)
    }
    @Test fun `name collision preserves existing bytes and uploads separate file`()=runBlocking {
        files["/cloud/remote.php/dav/files/ray/photo.jpg"]="existing".toByteArray()
        val item=row();work()
        assertEquals("existing",String(files["/cloud/remote.php/dav/files/ray/photo.jpg"]!!))
        assertEquals("DONE",app.database.dao().transfer(item.id)!!.state)
        assertTrue(app.database.dao().transfer(item.id)!!.destination.contains("photo-job-one.jpg"))
    }
    @Test fun `commit before process death is recovered without source file or duplicate upload`()=runBlocking {
        val item=row();val bytes="photo-bytes".toByteArray()
        files["/cloud/remote.php/dav/files/ray/photo.jpg"]=bytes
        val checksum=digest(bytes.inputStream())
        File(java.net.URI(item.source)).delete()
        app.database.dao().transfer(item.copy(state="RUNNING",checksum=checksum))
        work()
        assertEquals("DONE",app.database.dao().transfer(item.id)!!.state)
        assertEquals(0,putCount)
    }
    @Test fun `quota exhausted never marks upload complete`()=runBlocking {
        quotaFailure=true
        val item=row();work()
        assertEquals("ERROR",app.database.dao().transfer(item.id)!!.state)
        assertTrue(app.database.dao().transfer(item.id)!!.error.contains("空間不足"))
    }
    @Test fun `large file uses numbered chunks and protected final assembly`()=runBlocking {
        val data=ByteArray(21*1024*1024) { (it%251).toByte() }
        val item=row("large",data);work()
        assertEquals("DONE",app.database.dao().transfer(item.id)!!.state)
        assertEquals(3,putCount);assertTrue(finalMoveProtected)
        assertArrayEquals(data,files["/cloud/remote.php/dav/files/ray/photo.jpg"])
    }
    @Test fun `cancelled job never starts network upload`()=runBlocking {
        val item=row();app.database.dao().transfer(item.copy(state="CANCELLED"));work()
        assertEquals(0,putCount);assertEquals("CANCELLED",app.database.dao().transfer(item.id)!!.state)
    }
}
