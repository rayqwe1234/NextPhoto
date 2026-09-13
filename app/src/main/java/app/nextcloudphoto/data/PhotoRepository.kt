package app.nextcloudphoto.data

import app.nextcloudphoto.i18n.tr
import app.nextcloudphoto.R

import android.content.Context
import app.nextcloudphoto.account.CredentialStore
import app.nextcloudphoto.network.*
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File

data class SyncStatus(val running: Boolean = false, val indexed: Int = 0, val message: String = tr(R.string.msg_0_not_synced_yet), val last: Long = 0)
class PhotoRepository(val context: Context, val db: PhotoDatabase, val accounts: CredentialStore) {
    val dao = db.dao()
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val sync = MutableStateFlow(SyncStatus(last = prefs.getLong("lastSync",0)))
    val server = MutableStateFlow<ServerInfo?>(null)
    val albumError = MutableStateFlow("")
    val lock = Mutex()
    val transferLock = Mutex()
    @Volatile private var cachedClient: NextcloudClient? = null
    fun client(): NextcloudClient = cachedClient ?: synchronized(this) {
        cachedClient ?: NextcloudClient(accounts.read() ?: error(tr(R.string.msg_1_please_sign_in_first))).also { cachedClient=it }
    }
    fun clearClient() { cachedClient?.http?.dispatcher?.cancelAll(); cachedClient=null }
    fun root(client: NextcloudClient = client()): String {
        var root = client.filesRoot
        prefs.getString("scanRoot", "")!!.split('/').filter { it.isNotBlank() }.forEach { root = client.child(root,it) }
        return root
    }
    suspend fun refresh(force: Boolean = false) = lock.withLock {
        withContext(Dispatchers.IO) {
            val client = client()
            val previous = sync.value.last
            sync.value = SyncStatus(true, message = tr(R.string.msg_2_connecting), last = previous)
            try {
                server.value = client.info()
                var count = 0
                val completedFolders = mutableListOf<Folder>()
                val queue = ArrayDeque<String>().apply { add(root(client)) }
                while(queue.isNotEmpty()) {
                    ensureActive()
                    val path = queue.removeFirst()
                    val stat = client.stat(path)
                    val old = dao.folder(path)
                    if (!force && old != null && stat.props["getetag"].orEmpty().isNotEmpty() && old.etag == stat.props["getetag"]) continue
                    val seen = System.currentTimeMillis()
                    val buffer = mutableListOf<Media>()
                    val childFolders = mutableSetOf<String>()
                    fun flush() { if(buffer.isNotEmpty()) { runBlocking { merge(buffer.toList()) }; buffer.clear() } }
                    client.list(path) { entry ->
                        ensureActive()
                        val href = client.canonical(entry.href)
                        val vaultRoot=client.child(client.filesRoot,app.nextcloudphoto.vault.VaultStore.ROOT)
                        if(href==vaultRoot || href.startsWith("${vaultRoot}/")) return@list
                        if (href != path) {
                            if (entry.directory) { queue.add(href); childFolders.add(href) }
                            else if (entry.id.isNotBlank() && (entry.mime.startsWith("image/") || entry.mime.startsWith("video/"))) {
                                buffer.add(toMedia(client,entry,seen)); count++
                                if(buffer.size >= 150) flush()
                                sync.value = SyncStatus(true,count,tr(R.string.msg_3_indexing_1_s, name(path)),previous)
                            }
                        }
                    }
                    flush()
                    // Only reconcile after this directory was read completely.
                    dao.missing(path,seen).forEach { missing ->
                        if (!client.exists(missing.href)) { dao.delete(missing.id); dao.removeMemberships(missing.id) }
                    }
                    dao.childFolders(path).filter { it.href !in childFolders }.forEach { missing ->
                        if(!client.exists(missing.href)) { dao.removeFolderMedia(missing.href); dao.removeFolder(missing.href) }
                    }
                    completedFolders.add(Folder(path,name(path),parent(path),stat.props["getetag"].orEmpty()))
                }
                // Commit ETags only after the traversal succeeds, so interrupted subtrees resume.
                db.withTransaction { completedFolders.forEach { dao.folder(it) } }
                refreshAlbums(client)
                if(albumError.value.isEmpty()) dao.allAlbums().forEach { album ->
                    sync.value=sync.value.copy(message=tr(R.string.msg_4_syncing_album_1_s, album.name))
                    try { refreshAlbum(album) } catch(e: CancellationException) { throw e }
                    catch(e: Exception) { albumError.value=tr(R.string.msg_5_some_albums_could_not_be_synced_1_s, e.message) }
                }
                val now = System.currentTimeMillis()
                prefs.edit().putLong("lastSync",now).apply()
                sync.value = SyncStatus(false,count,tr(R.string.msg_6_synced_with_nextcloud),now)
            } catch(e: CancellationException) { sync.value = sync.value.copy(running=false,message=tr(R.string.msg_7_sync_stopped)); throw e }
            catch(e: Exception) { sync.value = sync.value.copy(running=false,message=e.message ?: tr(R.string.msg_8_connection_failed)); throw e }
        }
    }
    suspend fun merge(items: List<Media>) {
        dao.saveMedia(items.map { item ->
            val old = dao.media(item.id)
            item.copy(offline=old?.offline.orEmpty(),offlineEtag=old?.offlineEtag.orEmpty())
        })
    }
    fun toMedia(client: NextcloudClient, entry: DavEntry, seen: Long = System.currentTimeMillis()): Media {
        require(entry.id.toLongOrNull()!=null) { tr(R.string.msg_9_the_server_returned_an_invalid_photo_id) }
        val href = client.canonical(entry.href)
        val account = client.account!!
        return Media("${account.key}:${entry.id}",entry.id,href,
            entry.props["photos-collection-file-original-filename"]?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: name(href),
            parent(href),entry.mime,entry.props["getcontentlength"]?.toLongOrNull() ?: 0,entry.modified,entry.taken,
            entry.props["getetag"].orEmpty(),entry.props["favorite"] == "1",entry.props["permissions"].orEmpty(),entry.props["has-preview"] != "false",seen=seen)
    }
    suspend fun refreshAlbums(client: NextcloudClient = client()) = withContext(Dispatchers.IO) {
        try {
            val albums = mutableListOf<Album>()
            client.list(client.albumsRoot) { entry ->
                val href = client.canonical(entry.href)
                if(href != client.albumsRoot) albums.add(Album(href,name(href),entry.props["nbItems"]?.toIntOrNull() ?: 0,entry.props["last-photo"].orEmpty()))
            }
            db.withTransaction { dao.clearAlbums(); dao.albums(albums) }
            albumError.value = ""
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { albumError.value = tr(R.string.msg_10_photos_albums_are_temporarily_unavailable_1_s, e.message) }
    }
    suspend fun refreshAlbum(album: Album) = withContext(Dispatchers.IO) {
        val client = client()
        val members = mutableListOf<Membership>()
        val entries = mutableListOf<Media>()
        val seen=System.currentTimeMillis()
        suspend fun flush() {
            db.withTransaction {
                merge(entries.filter { dao.media(it.id)==null })
                dao.members(members.toList())
            }
            entries.clear();members.clear()
        }
        client.list(album.href) { entry ->
            if(entry.id.isNotBlank() && !entry.directory) {
                val item = toMedia(client,entry)
                members.add(Membership(album.href,item.id,client.canonical(entry.href),seen))
                entries.add(item)
                if(entries.size>=150) runBlocking { flush() }
            }
        }
        flush()
        dao.pruneMembers(album.href,seen)
    }
    suspend fun browse(path: String) = withContext(Dispatchers.IO) {
        val client = client()
        val media = mutableListOf<Media>()
        client.list(path) { entry ->
            val href = client.canonical(entry.href)
            if(href != path) runBlocking {
                if(entry.directory) dao.folder(Folder(href,name(href),path,""))
                else if(entry.id.isNotBlank() && (entry.mime.startsWith("image/") || entry.mime.startsWith("video/"))) {
                    media.add(toMedia(client,entry)); if(media.size >= 150) { merge(media.toList()); media.clear() }
                }
            }
        }
        merge(media)
    }
    suspend fun favorite(media: Media) = withContext(Dispatchers.IO) { client().favorite(media.href,!media.favorite); dao.favorite(media.id,!media.favorite) }
    suspend fun removeFile(media: Media) = withContext(Dispatchers.IO) {
        client().deleteFile(media.href,media.etag); dao.delete(media.id); dao.removeMemberships(media.id)
        if(media.offline.isNotEmpty()) File(media.offline).delete()
    }
    suspend fun removeMember(album: Album, media: Media) = withContext(Dispatchers.IO) {
        val member = dao.member(album.href,media.id) ?: error(tr(R.string.msg_11_please_refresh_the_album_first))
        client().removeFromAlbum(member.href); refreshAlbum(album)
    }
    suspend fun clearOffline(media: Media) = withContext(Dispatchers.IO) { if(media.offline.isNotBlank()) File(media.offline).delete(); dao.offline(media.id,"","") }
    companion object {
        fun name(href: String) = href.toHttpUrl().pathSegments.lastOrNull { it.isNotEmpty() }.orEmpty()
        fun parent(href: String): String = href.toHttpUrl().newBuilder().removePathSegment(href.toHttpUrl().pathSegments.lastIndex).build().toString().trimEnd('/')
    }
}
