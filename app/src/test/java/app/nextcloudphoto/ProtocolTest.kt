package app.nextcloudphoto

import app.nextcloudphoto.account.Account
import app.nextcloudphoto.network.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class ProtocolTest {
    private fun withServer(block: (MockWebServer,NextcloudClient)->Unit) {
        MockWebServer().use { server -> server.start(); block(server,NextcloudClient(Account(server.url("/nextcloud").toString(),"ray+測試","test-app-password"))) }
    }
    @Test fun `server normalization retains port and subpath`() {
        assertEquals("https://example.com:8443/nextcloud",normalizeServer(" https://example.com:8443/nextcloud/ "))
        assertThrows(IllegalArgumentException::class.java) { normalizeServer("http://example.com") }
        assertThrows(IllegalArgumentException::class.java) { normalizeServer("https://user:pass@example.com") }
    }
    @Test fun `path names encode reserved and unicode characters without changing hierarchy`() = withServer { _,client ->
        val target=client.child(client.filesRoot,"旅行 + #?.jpg")
        assertTrue(target.contains("%23%3F.jpg"))
        assertEquals("旅行 + #?.jpg",client.trusted(target).pathSegments.last())
        assertTrue(target.contains("/nextcloud/remote.php/dav/files/"))
        assertThrows(IllegalArgumentException::class.java) { client.child(client.filesRoot,"../secret") }
    }
    @Test fun `cross origin href never receives credentials`() = withServer { server,client ->
        assertThrows(IllegalArgumentException::class.java) { client.response("GET","https://evil.invalid/photo.jpg") }
        assertEquals(0,server.requestCount)
    }
    @Test fun `album removal deletes membership endpoint only`() = withServer { server,client ->
        server.enqueue(MockResponse().setResponseCode(204))
        val album=client.child(client.albumsRoot,"旅行")
        client.removeFromAlbum(client.child(album,"123-photo.jpg"))
        val request=server.takeRequest()
        assertEquals("DELETE",request.method)
        assertTrue(request.path!!.contains("/photos/"))
        assertFalse(request.path!!.contains("/dav/files/"))
        assertThrows(IllegalArgumentException::class.java) { client.removeFromAlbum(client.child(client.filesRoot,"photo.jpg")) }
        assertThrows(IllegalArgumentException::class.java) { client.removeFromAlbum(album) }
    }
    @Test fun `move refuses overwrite and copy uses Photos virtual album`() = withServer { server,client ->
        repeat(2) { server.enqueue(MockResponse().setResponseCode(201)) }
        val file=client.child(client.filesRoot,"original.jpg")
        client.move(file,client.child(client.filesRoot,"renamed.jpg"))
        assertEquals("F",server.takeRequest().getHeader("Overwrite"))
        client.addToAlbum(file,client.child(client.albumsRoot,"Trip"),"original.jpg")
        val copy=server.takeRequest()
        assertEquals("COPY",copy.method)
        assertTrue(copy.getHeader("Destination")!!.contains("/albums/Trip/"))
        assertEquals("F",copy.getHeader("Overwrite"))
    }
    @Test fun `failed property patch is never treated as success`() = withServer { server,client ->
        server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"><d:response><d:propstat><d:status>HTTP/1.1 403 Forbidden</d:status></d:propstat></d:response></d:multistatus>"))
        val error=assertThrows(CloudException::class.java) { client.favorite(client.child(client.filesRoot,"x.jpg"),true) }
        assertEquals(403,error.code)
    }
    @Test fun `parser ignores failed property blocks and preserves successful properties`() {
        val xml="""<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:response><d:href>/nextcloud/remote.php/dav/files/ray/test.jpg</d:href><d:propstat><d:prop><oc:fileid>42</oc:fileid><d:getcontenttype>image/jpeg</d:getcontenttype><oc:favorite>1</oc:favorite></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat><d:propstat><d:prop><oc:favorite>0</oc:favorite></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response></d:multistatus>"""
        val rows=mutableListOf<DavEntry>();readDav(xml.reader()) { rows.add(it) }
        assertEquals(1,rows.size);assertEquals("42",rows.single().id);assertEquals("1",rows.single().props["favorite"])
    }
    @Test fun `metadata dates support seconds milliseconds and fallback`() {
        assertEquals(1700000000000L,parseTaken("1700000000",0))
        assertEquals(1700000000000L,parseTaken("1700000000000",0))
        assertEquals(123L,parseTaken("invalid",123))
    }
    @Test fun `truncated XML cannot complete an index scan`() {
        assertThrows(Exception::class.java) { readDav("<d:multistatus xmlns:d=\"DAV:\"><d:response>".reader()) {} }
    }
    @Test fun `quota and authorization failures stay distinct`() = withServer { server,client ->
        server.enqueue(MockResponse().setResponseCode(507));server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(507,assertThrows(CloudException::class.java) { client.stat(client.filesRoot) }.code)
        assertEquals(401,assertThrows(CloudException::class.java) { client.stat(client.filesRoot) }.code)
    }
}
