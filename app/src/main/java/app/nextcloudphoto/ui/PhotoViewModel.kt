package app.nextcloudphoto.ui

import app.nextcloudphoto.i18n.tr
import app.nextcloudphoto.R

import app.nextcloudphoto.vault.VaultBiometrics

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import androidx.work.WorkManager
import app.nextcloudphoto.PhotoApplication
import app.nextcloudphoto.account.Account
import app.nextcloudphoto.data.*
import app.nextcloudphoto.network.*
import app.nextcloudphoto.transfer.TransferQueue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import java.io.File

data class Filters(val query: String="",val kind: String="",val favorite: Boolean=false,val offline: Boolean=false,val folder: String="",val album: String="",val before: Long=Long.MAX_VALUE)
sealed interface TimelineItem {
    data class Photo(val media: Media): TimelineItem
    data class Day(val title: String): TimelineItem
}
fun dayLabel(time: Long): String = java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
class PhotoViewModel(application: Application): AndroidViewModel(application) {
    val app=application as PhotoApplication
    val repo=app.repository
    val queue=TransferQueue(app)
    val account=MutableStateFlow(app.accounts.read())
    val filters=MutableStateFlow(Filters())
    val message=MutableStateFlow("")
    val busy=MutableStateFlow(false)
    val loginPending=MutableStateFlow(false)
    val album=MutableStateFlow<Album?>(null)
    val count=repo.dao.count().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0)
    val albums=repo.dao.albums().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val transfers=repo.dao.transfers().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val transferHistoryCount=repo.dao.transferHistoryCount().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0)
    val pending=repo.dao.pending().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0)
    @OptIn(ExperimentalCoroutinesApi::class)
    val photos=filters.flatMapLatest { f -> Pager(PagingConfig(pageSize=90,prefetchDistance=30,maxSize=450,enablePlaceholders=true)) {
        repo.dao.page(f.query,f.kind,f.favorite,f.offline,f.folder,f.album,f.before,if(account.value==null || f.folder.isNotBlank()) "" else repo.root())
    }.flow }.cachedIn(viewModelScope)
    val timeline=photos.map { data -> data.map<Media,TimelineItem> { TimelineItem.Photo(it) }.insertSeparators { before, after ->
        val next=(after as? TimelineItem.Photo)?.media
        val prev=(before as? TimelineItem.Photo)?.media
        if(next!=null && (prev==null || dayLabel(prev.taken)!=dayLabel(next.taken))) TimelineItem.Day(dayLabel(next.taken)) else null
    } }.cachedIn(viewModelScope)
    @OptIn(ExperimentalCoroutinesApi::class)
    val folders=filters.map { it.folder }.distinctUntilChanged().flatMapLatest { repo.dao.folders(it) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private var loginJob: Job?=null
    private val operations=mutableSetOf<Job>()
    init { if(account.value != null) { refresh(); queue.schedule(); queue.backupSchedule(repo.prefs.getBoolean("backup",false)) } }
    fun action(block: suspend () -> Unit): Job {
        val job=viewModelScope.launch(start=CoroutineStart.LAZY) {
            try { withContext(Dispatchers.IO) { block() } } catch(e: CancellationException) { throw e }
            catch(e: Exception) { message.value=e.message ?: tr(R.string.msg_140_operation_failed_please_try_again) }
            finally { operations.remove(currentCoroutineContext().job);busy.value=operations.isNotEmpty() }
        }
        operations.add(job);busy.value=true;job.start();return job
    }
    fun refresh(force: Boolean=false) { if(!repo.sync.value.running) action { repo.refresh(force) } }
    fun login(server: String, open: (String) -> Unit) {
        loginJob?.cancel()
        loginJob=viewModelScope.launch {
            loginPending.value=true
            try {
                val api=NextcloudClient(null,normalizeServer(server))
                val flow=withContext(Dispatchers.IO) { api.beginLogin() }
                open(flow.login)
                val result=withTimeout(15*60*1000L) {
                    var found: Account?=null
                    while(found==null) { delay(2000); found=withContext(Dispatchers.IO) { api.poll(flow) } }
                    found
                }
                val resolved=withContext(Dispatchers.IO) {
                    val uid=NextcloudClient(result).ocs("GET",listOf("ocs","v2.php","cloud","user")).getJSONObject("data").getString("id")
                    result.copy(user=uid).also { app.accounts.save(it) }
                }
                account.value=resolved
                refresh()
            } catch(e: CancellationException) { if(e is TimeoutCancellationException) message.value=tr(R.string.msg_141_authorization_timed_out_please_sign_in_again) }
            catch(e: Exception) { message.value=e.message ?: tr(R.string.msg_142_sign_in_failed) }
            finally { loginPending.value=false }
        }
    }
    fun cancelLogin() { loginJob?.cancel() }
    fun openAlbum(value: Album) { album.value=value; filters.value=Filters(album=value.href); action { repo.refreshAlbum(value) } }
    fun browse(path: String) { album.value=null; filters.value=Filters(folder=path); action { repo.browse(path) } }
    fun reset() { album.value=null; filters.value=Filters() }
    suspend fun position(media: Media): Int {
        val f=filters.value
        return repo.dao.position(f.query,f.kind,f.favorite,f.offline,f.folder,f.album,f.before,if(f.folder.isNotBlank()) "" else repo.root(),media.taken,media.id)
    }
    fun newAlbum(name: String) = action { val c=repo.client(); c.command("MKCOL",c.child(c.albumsRoot,name)); repo.refreshAlbums() }
    fun renameAlbum(value: Album, name: String) = action { val c=repo.client(); c.move(value.href,c.child(c.albumsRoot,name)); repo.refreshAlbums(); reset() }
    fun addToAlbum(value: Album, items: List<Media>) = action {
        val c=repo.client(); items.forEach { c.addToAlbum(it.href,value.href,it.name) }; repo.refreshAlbum(value); repo.refreshAlbums(); message.value=tr(R.string.msg_143_added_to_album)
    }
    fun offline(items: List<Media>) = action { items.forEach { queue.offline(it) }; message.value=tr(R.string.msg_144_added_to_the_offline_download_queue) }
    fun offlineAlbum(value: Album) = action {
        repo.refreshAlbum(value)
        var offset=0
        while(true) { val batch=repo.dao.albumBatch(value.href,100,offset); if(batch.isEmpty()) break; batch.forEach { queue.offline(it,false) }; offset+=batch.size }
        queue.schedule()
        message.value=tr(R.string.msg_145_album_added_to_the_offline_download_queue)
    }
    fun upload(uris: List<Uri>) = action {
        uris.forEach { uri ->
            runCatching { app.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            queue.upload(uri,repo.prefs.getString("uploadRoot","Photos/手機備份")!!)
        }
        message.value=tr(R.string.msg_146_added_to_the_upload_queue)
    }
    fun move(media: Media, relative: String) = action {
        val c=repo.client(); require(c.inFiles(media.href)) { tr(R.string.msg_147_this_photo_is_only_accessible_through_its_album) }
        var dest=c.filesRoot; relative.split('/').filter { it.isNotBlank() }.forEach { dest=c.child(dest,it) }
        c.move(media.href,dest,media.etag)
        repo.merge(listOf(repo.toMedia(c,c.stat(dest))))
        message.value=tr(R.string.msg_148_file_location_updated)
    }
    fun shareFile(media: Media, ready: (Intent)->Unit) = action {
        val local=File(app.cacheDir,"share/${media.fileId}/${validName(media.name)}")
        repo.client().download(media.href,local)
        val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",local)
        withContext(Dispatchers.Main) { ready(Intent(Intent.ACTION_SEND).setType(media.mime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
    }
    fun export(media: Media) = action {
        val resolver=app.contentResolver
        val values=ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME,media.name); put(MediaStore.Downloads.MIME_TYPE,media.mime); put(MediaStore.Downloads.IS_PENDING,1); put(MediaStore.Downloads.RELATIVE_PATH,"Download/Nextcloud Photo") }
        val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: error(tr(R.string.msg_149_cannot_create_the_download_file))
        try {
            repo.client().response("GET",media.href).use { response -> resolver.openOutputStream(uri)!!.use { output -> response.body!!.byteStream().use {
                val copied=it.copyTo(output)
                if(response.body!!.contentLength()>=0 && copied!=response.body!!.contentLength()) throw java.io.IOException(tr(R.string.msg_36_download_incomplete))
            } } }
            resolver.update(uri,ContentValues().apply { put(MediaStore.Downloads.IS_PENDING,0) },null,null)
            message.value=tr(R.string.msg_150_saved_to_download_nextcloud_photo)
        } catch(e: Exception) { resolver.delete(uri,null,null); throw e }
    }
    fun logout() = viewModelScope.launch {
        val active=operations.toList()
        active.forEach { it.cancel() };active.joinAll()
        busy.value=true
        try { withContext(Dispatchers.IO) {
        WorkManager.getInstance(app).cancelAllWork().result.get()
        repo.transferLock.withLock { repo.lock.withLock {
        // Cancel active UI work before switching identity; this app supports one account.
        app.accounts.clear()
        VaultBiometrics.clearAll(app)
        repo.clearClient()
        app.database.clearAllTables()
        File(app.filesDir,"offline").deleteRecursively()
        File(app.filesDir,"uploads").deleteRecursively()
        File(app.filesDir,"edits").deleteRecursively()
        app.cacheDir.deleteRecursively()
        repo.prefs.edit().clear().commit()
        withContext(Dispatchers.Main) { account.value=null; reset(); repo.server.value=null; repo.sync.value=SyncStatus() }
        } }
        } } catch(e: Exception) { message.value=e.message ?: tr(R.string.msg_151_sign_out_did_not_finish) } finally {busy.value=false}
    }
}
