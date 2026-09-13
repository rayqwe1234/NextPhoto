package app.nextcloudphoto.vault

import app.nextcloudphoto.i18n.tr
import app.nextcloudphoto.R

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.UUID

const val VIDEO_CHUNK=1024*1024
const val MAX_VIDEO=32L*1024*1024*1024

data class VaultVideo(val id: String, val name: String, val mime: String, val size: Long, val chunks: Int) {
    fun encode(): ByteArray = JSONObject().put("version",1).put("id",id).put("name",name).put("mime",mime)
        .put("size",size).put("chunkSize",VIDEO_CHUNK).put("chunks",chunks).toString().toByteArray()
    companion object {
        fun parse(bytes: ByteArray, id: String): VaultVideo {
            val json=JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.getInt("version")==1 && json.getString("id")==id && json.getInt("chunkSize")==VIDEO_CHUNK) { tr(R.string.msg_302_unsupported_encrypted_video_format) }
            val size=json.getLong("size");val count=json.getInt("chunks")
            require(size in 1..MAX_VIDEO && count.toLong()==(size+VIDEO_CHUNK-1)/VIDEO_CHUNK) { tr(R.string.msg_303_encrypted_video_length_is_incomplete) }
            val mime=json.getString("mime");val name=json.getString("name")
            require(mime.startsWith("video/") && mime.length<=128 && name.length in 1..512)
            return VaultVideo(id,name,mime,size,count)
        }
    }
}

/** Publishes the authenticated manifest last; incomplete chunks never appear as a playable video. */
suspend fun VaultStore.importVideo(session: VaultSession, name: String, mime: String, input: InputStream,
    expectedSize: Long=-1, progress: (Long)->Unit = {}): String {
    require(mime.startsWith("video/") && mime.length<=128)
    require(expectedSize<=MAX_VIDEO) { tr(R.string.msg_304_video_limit_32_gib) }
    val id=UUID.randomUUID().toString()
    val buffer=ByteArray(VIDEO_CHUNK)
    var total=0L;var index=0
    try {
        while(true) {
            currentCoroutineContext().ensureActive();check(!session.closed) { tr(R.string.msg_290_album_locked) }
            var count=0
            while(count<buffer.size) {
                currentCoroutineContext().ensureActive();check(!session.closed) { tr(R.string.msg_290_album_locked) }
                val read=input.read(buffer,count,buffer.size-count)
                if(read<0) break
                if(read==0) { val one=input.read();if(one<0) break;buffer[count++]=one.toByte() } else count+=read
            }
            if(count==0) break
            require(total+count<=MAX_VIDEO && (expectedSize<0 || total+count<=expectedSize)) { tr(R.string.msg_305_video_length_is_invalid_or_exceeds_32_gib_upload_did_no) }
            val plain=if(count==buffer.size) buffer else buffer.copyOf(count)
            val encrypted=try { session.withKey { VaultCrypto.seal(it,plain,"${session.id}/${id}/video/${index}") } }
                finally { plain.fill(0);buffer.fill(0) }
            currentCoroutineContext().ensureActive();check(!session.closed) { tr(R.string.msg_290_album_locked) }
            put(path(session.id,"${id}-${index}.nvc"),encrypted)
            total+=count;index++;progress(total)
        }
        require(total>0 && (expectedSize<0 || total==expectedSize)) { tr(R.string.msg_306_video_read_was_incomplete_upload_did_not_finish) }
        val descriptor=VaultVideo(id,name.take(512).ifBlank { tr(R.string.msg_95_videos) },mime,total,index).encode()
        val encrypted=try { session.withKey { VaultCrypto.seal(it,descriptor,"${session.id}/${id}/video-manifest") } }
            finally { descriptor.fill(0) }
        currentCoroutineContext().ensureActive();check(!session.closed) { tr(R.string.msg_290_album_locked) }
        put(path(session.id,"${id}.ncv"),encrypted)
        return id
    } finally { buffer.fill(0) }
}

suspend fun VaultStore.video(session: VaultSession, entry: String): VaultVideo = withContext(Dispatchers.IO) {
    require(entry.endsWith(".ncv"))
    val id=entry.removeSuffix(".ncv");require(VaultStore.ID.matches(id))
    val encrypted=get(path(session.id,entry),16*1024)
    ensureActive()
    val plain=session.withKey { VaultCrypto.open(it,encrypted,"${session.id}/${id}/video-manifest") }
    try { VaultVideo.parse(plain,id) } finally { plain.fill(0) }
}

/** A single authenticated chunk in RAM; supports Media3 seeks without a plaintext file or disk cache. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VaultVideoDataSource(private val store: VaultStore, private val session: VaultSession, private val video: VaultVideo): BaseDataSource(true) {
    private var uri: Uri?=null
    private var position=0L
    private var remaining=0L
    private var chunkIndex=-1
    private var chunk: ByteArray?=null
    private var started=false
    private var registration: AutoCloseable?=null
    private var generation=0

    override fun open(dataSpec: DataSpec): Long {
        close();transferInitializing(dataSpec)
        if(session.closed) throw IOException(tr(R.string.msg_290_album_locked))
        if(dataSpec.position>video.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        synchronized(this) {
            uri=dataSpec.uri;position=dataSpec.position
            remaining=if(dataSpec.length==C.LENGTH_UNSET.toLong()) video.size-position else minOf(dataSpec.length,video.size-position)
            started=true
        }
        registration=session.onLock { store.cancelRequests();clearBuffer() }
        transferStarted(dataSpec)
        return if(dataSpec.length==C.LENGTH_UNSET.toLong()) video.size-dataSpec.position else dataSpec.length
    }
    private fun checkOpen() { if(session.closed || uri==null) throw IOException(tr(R.string.msg_307_the_album_is_locked_or_playback_has_stopped)) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if(length==0) return 0
        val index: Int;val epoch: Int
        synchronized(this) {
            checkOpen();if(remaining==0L) return C.RESULT_END_OF_INPUT
            index=(position/VIDEO_CHUNK).toInt();epoch=generation
        }
        if(synchronized(this) { chunkIndex!=index }) {
            val plain=try {
                val encrypted=store.get(store.path(session.id,"${video.id}-${index}.nvc"),VIDEO_CHUNK+28)
                session.withKey { VaultCrypto.open(it,encrypted,"${session.id}/${video.id}/video/${index}") }
            } catch(e: Exception) { throw IOException(tr(R.string.msg_308_cannot_read_the_video_check_your_connection_or_encrypte),e) }
            synchronized(this) {
                val expected=minOf(VIDEO_CHUNK.toLong(),video.size-index.toLong()*VIDEO_CHUNK).toInt()
                if(session.closed || generation!=epoch || plain.size!=expected) {
                    plain.fill(0);throw IOException(tr(R.string.msg_309_the_album_is_locked_or_an_encrypted_video_chunk_is_inco))
                }
                chunk?.fill(0);chunk=plain;chunkIndex=index
            }
        }
        val count=synchronized(this) {
            checkOpen()
            val current=chunk ?: throw IOException(tr(R.string.msg_310_playback_stopped))
            val start=(position%VIDEO_CHUNK).toInt()
            val count=minOf(length,current.size-start,minOf(remaining,Int.MAX_VALUE.toLong()).toInt())
            current.copyInto(buffer,offset,start,start+count)
            position+=count;remaining-=count;count
        }
        bytesTransferred(count);return count
    }
    override fun getUri(): Uri? = synchronized(this) { uri }
    @Synchronized private fun clearBuffer() { generation++;chunk?.fill(0);chunk=null;chunkIndex=-1;uri=null;remaining=0 }
    override fun close() {
        clearBuffer();registration?.close();registration=null
        if(started) { started=false;transferEnded() }
    }
}
