package app.nextcloudphoto

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import app.nextcloudphoto.account.Account
import app.nextcloudphoto.data.Media
import app.nextcloudphoto.network.NextcloudClient
import app.nextcloudphoto.vault.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.AEADBadTagException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VaultTest {
    private val id="12345678-1234-1234-1234-123456789abc"
    @Test fun `password protected header hides title and round trips across devices`() {
        val key=VaultCrypto.random(32);val password="a strong password!".toCharArray()
        val header=VaultCrypto.header(id,"私人旅行",password,key)
        assertTrue(password.all { it=='\u0000' })
        assertFalse(header.toString(Charsets.UTF_8).contains("私人旅行"))
        assertFalse(header.toString(Charsets.UTF_8).contains("a strong password!"))
        val restored=VaultCrypto.unlock(header,id,"a strong password!".toCharArray())
        assertArrayEquals(key,restored)
        VaultSession(id,header,restored).use { assertEquals("私人旅行",it.title()) }
    }
    @Test fun `incorrect password and substituted album cannot unwrap key`() {
        val header=VaultCrypto.header(id,"private","correct password!".toCharArray(),VaultCrypto.random(32))
        assertThrows(AEADBadTagException::class.java) { VaultCrypto.unlock(header,id,"wrong password!!".toCharArray()) }
        assertThrows(IllegalArgumentException::class.java) { VaultCrypto.unlock(header,"other","correct password!".toCharArray()) }
    }
    @Test fun `tampering truncation and cross object substitution fail authentication`() {
        val key=VaultCrypto.random(32);val plain="original bytes".toByteArray()
        val sealed=VaultCrypto.seal(key,plain,"album/photo")
        assertThrows(AEADBadTagException::class.java) { VaultCrypto.open(key,sealed.copyOf().apply { this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte() },"album/photo") }
        assertThrows(AEADBadTagException::class.java) { VaultCrypto.open(key,sealed.copyOf(sealed.size-1),"album/photo") }
        assertThrows(AEADBadTagException::class.java) { VaultCrypto.open(key,sealed,"album/thumbnail") }
        assertThrows(AEADBadTagException::class.java) { VaultCrypto.open(key,sealed,"other/photo") }
    }
    @Test fun `identical plaintext gets fresh random nonces`() {
        val key=VaultCrypto.random(32);val plain=ByteArray(200) { 42 }
        val one=VaultCrypto.seal(key,plain,"context");val two=VaultCrypto.seal(key,plain,"context")
        assertFalse(one.contentEquals(two));assertArrayEquals(plain,VaultCrypto.open(key,one,"context"))
    }
    @Test fun `locking wipes session key and recycles every owned bitmap including late arrivals`() {
        val key=VaultCrypto.random(32)
        val session=VaultSession(id,ByteArray(0),key)
        val bitmap=session.own(Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888))
        session.close();session.close()
        assertTrue(key.all { it==0.toByte() });assertTrue(bitmap.isRecycled)
        assertThrows(IllegalStateException::class.java) { session.withKey { it.copyOf() } }
        val late=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
        assertThrows(IllegalStateException::class.java) { session.own(late) };assertTrue(late.isRecycled)
    }
    @Test fun `bounded reads reject oversized input and preserve exact limit`() {
        assertArrayEquals(byteArrayOf(1,2,3),readBounded(ByteArrayInputStream(byteArrayOf(1,2,3)),3))
        assertThrows(IllegalArgumentException::class.java) { readBounded(ByteArrayInputStream(ByteArray(4)),3) }
    }
    @Test fun `ordinary cloud move cannot place plaintext inside the vault`() {
        MockWebServer().use { server ->
            server.start();val client=NextcloudClient(Account(server.url("/").toString(),"ray","secret"))
            val vault=client.child(client.filesRoot,VaultStore.ROOT)
            assertThrows(IllegalArgumentException::class.java) { client.move(client.child(client.filesRoot,"photo.jpg"),client.child(client.child(vault,id),"photo.jpg")) }
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun `cloud import sends only encrypted names photos and thumbnails without local files`() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val before=context.cacheDir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet()
        MockWebServer().use { server ->
            server.start()
            val client=NextcloudClient(Account(server.url("/nextcloud").toString(),"ray","secret"))
            val store=VaultStore(context,client)
            val key=VaultCrypto.random(32);val session=VaultSession(id,ByteArray(0),key)
            val bitmap=Bitmap.createBitmap(12,8,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
            val bytes=ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray();bitmap.recycle()
            val name="secret-location.jpg"
            val media=Media("1","1",client.child(client.filesRoot,name),name,client.filesRoot,"image/png",bytes.size.toLong(),0,0,"etag")
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setResponseCode(201))
            store.importCloudPhoto(session,media)
            assertEquals("GET",server.takeRequest().method)
            val thumb=server.takeRequest();val photo=server.takeRequest()
            assertEquals("PUT",photo.method);assertEquals("*",photo.getHeader("If-None-Match"))
            assertEquals("application/octet-stream",photo.getHeader("Content-Type"))
            assertFalse(photo.path!!.contains(name));assertTrue(thumb.path!!.endsWith(".nct"))
            val photoId=photo.path!!.substringAfterLast('/').removeSuffix(".ncp")
            val encrypted=photo.body.readByteArray()
            assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains(name))
            val restored=VaultCrypto.open(key,encrypted,"$id/$photoId/photo")
            val n=java.nio.ByteBuffer.wrap(restored).int
            assertEquals(name,String(restored,4,n,Charsets.UTF_8))
            assertArrayEquals(bytes,restored.copyOfRange(4+n,restored.size));restored.fill(0)
            server.enqueue(MockResponse().setBody(Buffer().write(encrypted)))
            val loaded=store.photo(session,photoId)
            assertEquals(name,loaded.first);assertEquals(12,loaded.second.width)
            session.close();assertTrue(loaded.second.isRecycled)
            assertEquals(before,context.cacheDir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet())
        }
    }
    @Test fun `quota failure does not publish a photo entry or delete source`() = runBlocking {
        MockWebServer().use { server ->
            server.start();val client=NextcloudClient(Account(server.url("/").toString(),"ray","secret"))
            val store=VaultStore(ApplicationProvider.getApplicationContext(),client)
            val bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
            val bytes=ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray();bitmap.recycle()
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)));server.enqueue(MockResponse().setResponseCode(507))
            val media=Media("1","1",client.child(client.filesRoot,"original.png"),"original.png",client.filesRoot,"image/png",bytes.size.toLong(),0,0,"etag")
            VaultSession(id,ByteArray(0),VaultCrypto.random(32)).use { session ->
                try { store.importCloudPhoto(session,media);fail("must report upload failure") }
                catch(e: app.nextcloudphoto.network.CloudException) { assertEquals(507,e.code) }
            }
            assertEquals(2,server.requestCount);assertEquals("GET",server.takeRequest().method)
            assertTrue(server.takeRequest().path!!.endsWith(".nct"))
        }
    }
}
