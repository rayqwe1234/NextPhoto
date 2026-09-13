package app.nextcloudphoto.transfer

import app.nextcloudphoto.i18n.tr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import app.nextcloudphoto.PhotoApplication
import app.nextcloudphoto.R
import app.nextcloudphoto.data.*
import app.nextcloudphoto.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.*
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

fun digest(file: File): String = file.inputStream().use { digest(it) }
fun digest(input: InputStream): String {
    val md = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64*1024)
    while(true) { val n = input.read(buffer); if(n<0) break; md.update(buffer,0,n) }
    return md.digest().joinToString("") { "%02x".format(it) }
}

class TransferQueue(private val context: Context) {
    private val repo = (context.applicationContext as PhotoApplication).repository
    private val dao get() = repo.dao
    suspend fun upload(uri: Uri, destination: String, backup: Boolean = false, modified: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        require(destination.split('/').firstOrNull { it.isNotBlank() } != app.nextcloudphoto.vault.VaultStore.ROOT) { tr(R.string.msg_37_encrypted_albums_require_encrypted_uploads_use_the_encr) }
        var name = tr(R.string.msg_38_photo_1_s_jpg, System.currentTimeMillis())
        var size = -1L
        if(uri.scheme == "file") { val file=File(uri.path!!); name=file.name; size=file.length() }
        else context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { c ->
            if(c.moveToFirst()) { name = c.getString(0); if(!c.isNull(1)) size=c.getLong(1) }
        }
        val mime = if(uri.scheme=="file") "image/jpeg" else context.contentResolver.getType(uri) ?: "application/octet-stream"
        require(mime.startsWith("image/") || mime.startsWith("video/")) { tr(R.string.msg_39_only_photos_and_videos_are_accepted) }
        validName(name)
        val client = repo.client()
        var folder = client.filesRoot
        destination.split('/').filter { it.isNotBlank() }.forEach { folder=client.child(folder,it) }
        if(backup) DateTimeFormatter.ofPattern("yyyy/MM").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(modified)).split('/').forEach { folder=client.child(folder,it) }
        val id=UUID.randomUUID().toString()
        val sourceKey = "${client.account!!.key}|${uri}|${size}|${if(backup) modified else 0}|${folder}"
        val row = Transfer(id,uri.toString(),sourceKey,client.child(folder,name),name,mime,size,modified)
        if(dao.insertTransfer(row) != -1L && !backup) schedule()
    }
    suspend fun offline(media: Media, start: Boolean=true) {
        if(media.offline.isNotBlank() && media.offlineEtag == media.etag && File(media.offline).exists()) return
        val row = Transfer(UUID.randomUUID().toString(),media.href,"offline|${media.id}|${media.etag}","",media.name,media.mime,media.size,media.modified,kind="DOWNLOAD",mediaId=media.id)
        val previous=dao.transferForSource(row.sourceKey)
        if(previous!=null && previous.state !in listOf("QUEUED","RUNNING")) dao.transfer(previous.copy(state="QUEUED",error="",progress=0,hidden=false))
        else dao.insertTransfer(row)
        if(start) schedule()
    }
    fun schedule(replace: Boolean = false) {
        val wifi = repo.prefs.getBoolean("wifi",true)
        val request = OneTimeWorkRequestBuilder<TransferWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(if(wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("transfers",if(replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,request)
    }
    suspend fun retry(item: Transfer) { dao.transfer(item.copy(state="QUEUED",error="",hidden=false)); schedule() }
    suspend fun cancel(item: Transfer) { dao.transfer(item.copy(state="CANCELLED",error=tr(R.string.msg_40_cancelled))) }
    fun backupSchedule(enabled: Boolean) {
        val work=WorkManager.getInstance(context)
        if(!enabled) work.cancelUniqueWork("backup")
        else work.enqueueUniquePeriodicWork("backup",ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BackupWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder()
                .setRequiredNetworkType(if(repo.prefs.getBoolean("wifi",true)) NetworkType.UNMETERED else NetworkType.CONNECTED).build()).build())
    }
}

class TransferWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    private val repo = (context.applicationContext as PhotoApplication).repository
    private val dao get() = repo.dao
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        repo.transferLock.withLock {
        if(repo.accounts.read() == null) return@withContext Result.failure()
        try { setForeground(foreground()) } catch(_: Exception) { /* Short work may proceed when foreground start is restricted. */ }
        while(true) {
            ensureActive()
            val item=dao.nextTransfer() ?: break
            try {
                dao.status(item.id,"RUNNING",item.progress)
                if(item.kind == "DOWNLOAD") download(item) else upload(item)
                dao.status(item.id,"DONE",if(item.size>0) item.size else dao.transfer(item.id)?.progress ?: 0)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                if(dao.transfer(item.id)?.state != "CANCELLED") {
                    val retryable = (e is IOException && (e !is CloudException || e.code in listOf(408,423,429) || e.code>=500 && e.code!=507))
                    if(retryable && runAttemptCount < 4) { dao.status(item.id,"QUEUED",dao.transfer(item.id)?.progress ?: 0,e.message ?: tr(R.string.msg_41_network_disconnected)); return@withContext Result.retry() }
                    dao.status(item.id,"ERROR",dao.transfer(item.id)?.progress ?: 0,e.message ?: tr(R.string.msg_42_transfer_failed))
                }
            }
        }
        runCatching { repo.refresh() }
        Result.success()
        }
    }
    private fun foreground(): ForegroundInfo {
        val manager=applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("transfers",tr(R.string.msg_43_photo_transfers),NotificationManager.IMPORTANCE_LOW))
        val notification=NotificationCompat.Builder(applicationContext,"transfers").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("NextPhoto").setContentText(tr(R.string.msg_44_transferring_photos_and_videos)).setOngoing(true).build()
        return ForegroundInfo(10,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    private suspend fun check(item: Transfer) {
        currentCoroutineContext().ensureActive()
        if(isStopped) throw CancellationException()
        if(dao.transfer(item.id)?.state == "CANCELLED") throw IOException(tr(R.string.msg_40_cancelled))
    }
    private suspend fun download(item: Transfer) {
        val media=dao.media(item.mediaId) ?: error(tr(R.string.msg_45_this_photo_is_no_longer_in_the_index))
        val folder=File(applicationContext.filesDir,"offline").apply { mkdirs() }
        val target=File(folder,"${item.id}.${media.name.substringAfterLast('.',"bin")}")
        val partial=File(folder,"${item.id}.part")
        try {
            val client=repo.client()
            val before=client.stat(media.href)
            var last=0L
            client.download(media.href,partial) { bytes -> if(bytes-last>=512*1024) { last=bytes; runBlocking { check(item); dao.status(item.id,"RUNNING",bytes) } } }
            check(item)
            val after=client.stat(media.href)
            require(before.props["getetag"] == after.props["getetag"]) { tr(R.string.msg_46_the_cloud_file_changed_during_download_please_try_again) }
            if(after.props["getcontentlength"]?.toLongOrNull()?.let { it != partial.length() } == true) throw IOException(tr(R.string.msg_36_download_incomplete))
            check(partial.renameTo(target)) { tr(R.string.msg_47_cannot_save_the_offline_photo) }
            dao.offline(media.id,target.absolutePath,after.props["getetag"].orEmpty())
            if(media.offline.isNotBlank() && media.offline != target.absolutePath) File(media.offline).delete()
        } finally { partial.delete() }
    }
    private suspend fun upload(original: Transfer) {
        var item=original
        check(item)
        val client=repo.client()
        if(item.checksum.isNotEmpty() && client.exists(item.destination)) {
            val remote=client.response("GET",item.destination).use { digest(it.body!!.byteStream()) }
            if(remote != item.checksum) throw CloudException(412,errorMessage(412))
            File(applicationContext.filesDir,"uploads/${item.id}.data").delete()
            return
        }
        val stage=File(applicationContext.filesDir,"uploads/${item.id}.data").apply { parentFile!!.mkdirs() }
        // The staging file is atomically renamed so interrupted copies never become valid uploads.
        if(!stage.exists()) {
            val partial=File(stage.parentFile,"${item.id}.part")
            try {
                applicationContext.contentResolver.openInputStream(Uri.parse(item.source))!!.use { input -> partial.outputStream().use { output ->
                    val buffer=ByteArray(64*1024)
                    while(true) { check(item); val n=input.read(buffer); if(n<0) break; output.write(buffer,0,n) }
                } }
                if(item.size>=0 && item.size!=partial.length()) throw IOException(tr(R.string.msg_48_the_source_size_changed_please_add_it_to_uploads_again))
                check(partial.renameTo(stage)) { tr(R.string.msg_49_cannot_create_the_upload_staging_file) }
            } finally { partial.delete() }
        }
        val checksum=if(item.checksum.isNotEmpty()) item.checksum else digest(stage)
        if(item.checksum.isEmpty()) {
            if(client.exists(item.destination)) {
                val extension=item.name.substringAfterLast('.',"")
                val stem=if(extension.isEmpty()) item.name else item.name.substringBeforeLast('.')
                val renamed="${stem}-${item.id.take(8)}${if(extension.isEmpty()) "" else ".${extension}"}"
                item=item.copy(destination=client.child(PhotoRepository.parent(item.destination),renamed),name=renamed)
            }
            item=item.copy(checksum=checksum,size=stage.length())
            dao.transfer(item.copy(state="RUNNING"))
        }
        // If the server committed before the process died, verify bytes before acknowledging success.
        if(client.exists(item.destination)) {
            val remote=client.response("GET",item.destination).use { digest(it.body!!.byteStream()) }
            if(remote != checksum) throw CloudException(412,errorMessage(412))
            stage.delete(); return
        }
        ensureFolders(client,PhotoRepository.parent(item.destination))
        val headers=mapOf("Destination" to item.destination,"OC-Total-Length" to "${stage.length()}")
        val block=10L*1024*1024
        fun body(offset: Long, length: Long) = object: RequestBody() {
            override fun contentType() = item.mime.toMediaType()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                RandomAccessFile(stage,"r").use { input ->
                    input.seek(offset); var sent=0L; var last=0L; val buffer=ByteArray(64*1024)
                    while(sent<length) {
                        val n=input.read(buffer,0,minOf(buffer.size.toLong(),length-sent).toInt()); if(n<0) throw EOFException()
                        sink.write(buffer,0,n); sent+=n
                        if(sent-last>=512*1024 || sent==length) { last=sent; runBlocking { check(item); dao.status(item.id,"RUNNING",offset+sent) } }
                    }
                }
            }
        }
        if(stage.length() <= block) {
            client.response("PUT",item.destination,body(0,stage.length()),mapOf("If-None-Match" to "*","X-OC-Mtime" to "${item.modified/1000}","OC-Checksum" to "SHA256:${checksum}")).close()
        } else {
            val chunks=(stage.length()+block-1)/block
            require(chunks<=10000) { tr(R.string.msg_50_the_file_exceeds_the_chunked_upload_limit) }
            val uploadRoot=client.url("remote.php","dav","uploads",client.account!!.user,item.id)
            if(!client.exists(uploadRoot)) client.command("MKCOL",uploadRoot,headers=headers)
            for(i in 0 until chunks) {
                check(item)
                val target=client.child(uploadRoot,"${i+1}".padStart(5,'0'))
                val length=minOf(block,stage.length()-i*block)
                val present=try { client.stat(target).props["getcontentlength"]?.toLongOrNull() == length } catch(e: CloudException) { if(e.code==404) false else throw e }
                if(!present) client.response("PUT",target,body(i*block,length),headers).close()
            }
            check(item)
            client.command("MOVE",client.child(uploadRoot,".file"),headers=headers + mapOf("Overwrite" to "F","X-OC-Mtime" to "${item.modified/1000}","OC-Checksum" to "SHA256:${checksum}"))
        }
        val final=client.stat(item.destination)
        if(final.props["getcontentlength"]?.toLongOrNull()!=stage.length()) throw IOException(tr(R.string.msg_51_the_server_file_size_was_not_confirmed_please_try_again))
        stage.delete()
        if(Uri.parse(item.source).scheme=="file") {
            val source=File(Uri.parse(item.source).path!!)
            if(source.canonicalPath.startsWith(File(applicationContext.filesDir,"edits").canonicalPath+File.separator)) source.delete()
        }
    }
    private fun ensureFolders(client: NextcloudClient, destination: String) {
        val root=client.trusted(client.filesRoot).pathSegments
        var path=client.filesRoot
        client.trusted(destination).pathSegments.drop(root.size).forEach { segment ->
            path=client.child(path,segment)
            if(!client.exists(path)) try { client.command("MKCOL",path) } catch(e: CloudException) { if(e.code != 405) throw e }
        }
    }
}

class BackupWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo=(applicationContext as PhotoApplication).repository
        repo.transferLock.withLock {
        if(!repo.prefs.getBoolean("backup",false) || repo.accounts.read()==null) return@withContext Result.success()
        try {
            val tree=Uri.parse(repo.prefs.getString("backupTree","")!!)
            val root=DocumentFile.fromTreeUri(applicationContext,tree) ?: error(tr(R.string.msg_52_please_select_the_backup_folder_again))
            require(root.canRead()) { tr(R.string.msg_53_backup_folder_access_has_expired_please_select_it_again) }
            val queue=ArrayDeque<DocumentFile>().apply { add(root) }
            val transfers=TransferQueue(applicationContext)
            while(queue.isNotEmpty()) {
                ensureActive()
                queue.removeFirst().listFiles().forEach { file ->
                    if(file.isDirectory) queue.add(file)
                    else if(file.type?.let { it.startsWith("image/") || it.startsWith("video/") } == true)
                        transfers.upload(file.uri,repo.prefs.getString("uploadRoot","Photos/手機備份")!!,true,file.lastModified().takeIf { it>0 } ?: 0)
                }
            }
            repo.prefs.edit().putLong("lastBackup",System.currentTimeMillis()).putString("backupError","").apply()
            transfers.schedule()
            Result.success()
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { repo.prefs.edit().putString("backupError",e.message).apply(); Result.failure() }
        }
    }
}
