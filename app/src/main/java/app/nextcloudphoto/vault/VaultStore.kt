package app.nextcloudphoto.vault

import app.nextcloudphoto.i18n.tr
import app.nextcloudphoto.R

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import app.nextcloudphoto.network.NextcloudClient
import app.nextcloudphoto.network.CloudException
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

/** Owns all decrypted bitmaps and the album key; no plaintext file or shared image cache. */
class VaultSession(val id: String, val header: ByteArray, private val key: ByteArray) : AutoCloseable {
    private val bitmaps=mutableSetOf<Bitmap>()
    private val listeners=mutableSetOf<()->Unit>()
    fun onLock(action: ()->Unit): AutoCloseable {
        synchronized(this) { if(!closed) { listeners.add(action);return AutoCloseable { synchronized(this) { listeners.remove(action) } } } }
        action();return AutoCloseable { }
    }
    @Volatile var closed=false; private set
    @Synchronized fun <T> withKey(block: (ByteArray)->T): T { check(!closed) { tr(R.string.msg_290_album_locked) };return block(key) }
    fun title(): String = withKey { k ->
        val plain=VaultCrypto.open(k,VaultCrypto.decode(VaultCrypto.parse(header,id).getString("title")),"${id}/title")
        try { plain.toString(Charsets.UTF_8) } finally { plain.fill(0) }
    }
    @Synchronized fun own(bitmap: Bitmap): Bitmap {
        if(closed) { bitmap.recycle();error(tr(R.string.msg_290_album_locked)) }
        bitmaps.add(bitmap);return bitmap
    }
    @Synchronized fun release(bitmap: Bitmap) { bitmaps.remove(bitmap);if(!bitmap.isRecycled) bitmap.recycle() }
    override fun close() {
        val actions=synchronized(this) {
            closed=true;key.fill(0)
            bitmaps.forEach { if(!it.isRecycled) it.recycle() };bitmaps.clear()
            listeners.toList().also { listeners.clear() }
        }
        actions.forEach { runCatching { it() } }
    }
}

class VaultStore(private val context: Context, sourceClient: NextcloudClient) {
    // Independent calls can be cancelled immediately on lock without stopping ordinary photo sync.
    private val client=NextcloudClient(sourceClient.account,sourceClient.base.toString(),
        sourceClient.http.newBuilder().dispatcher(okhttp3.Dispatcher()).cache(null).build())
    fun cancelRequests() { client.http.dispatcher.cancelAll() }
    companion object {
        const val ROOT="Nextcloud Photo Vault"
        const val MAX_PHOTO=32*1024*1024
        val ID=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
    private val root=client.child(client.filesRoot,ROOT)
    private fun album(id: String): String { require(ID.matches(id));return client.child(root,id) }
    internal fun path(id: String, name: String)=client.child(album(id),name)
    private fun mkdir(path: String) {
        try { client.command("MKCOL",path) }
        catch(e: CloudException) { if(e.code!=405 || !client.stat(path).directory) throw e }
    }
    suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        val ids=mutableListOf<String>()
        try { client.list(root) { entry ->
            ensureActive()
            val href=client.canonical(entry.href)
            val id=client.trusted(href).pathSegments.last()
            if(entry.directory && ID.matches(id) && href==album(id)) ids.add(id)
        } } catch(e: CloudException) { if(e.code!=404) throw e }
        ids.sorted()
    }
    internal fun get(path: String, limit: Int): ByteArray = client.response("GET",path,headers=mapOf("Cache-Control" to "no-store")).use {
        require(it.body!!.contentLength() <= limit) { tr(R.string.msg_291_this_file_exceeds_the_encrypted_album_memory_limit) }
        it.body!!.byteStream().use { input -> readBounded(input,limit) }
    }
    internal fun put(path: String, bytes: ByteArray) {
        client.response("PUT",path,bytes.toRequestBody("application/octet-stream".toMediaType()),mapOf("If-None-Match" to "*","Cache-Control" to "no-store")).use {
            check(it.code==201 || it.code==204) { tr(R.string.msg_292_the_server_did_not_confirm_the_save) }
        }
    }
    suspend fun header(id: String): ByteArray = withContext(Dispatchers.IO) { get(path(id,"vault.json"),16*1024).also { VaultCrypto.parse(it,id) } }
    suspend fun create(name: String, password: CharArray): String = withContext(Dispatchers.IO) {
        val id=UUID.randomUUID().toString();val key=VaultCrypto.random(32)
        try {
            val header=VaultCrypto.header(id,name,password,key)
            ensureActive();mkdir(root);ensureActive();mkdir(album(id));ensureActive();put(path(id,"vault.json"),header)
            id
        } finally { key.fill(0);password.fill('\u0000') }
    }
    suspend fun entries(session: VaultSession): List<String> = withContext(Dispatchers.IO) {
        check(!session.closed)
        val result=mutableListOf<String>()
        client.list(album(session.id)) { entry ->
            ensureActive();check(!session.closed)
            val name=client.trusted(entry.href).pathSegments.last()
            if(!entry.directory && name.endsWith(".ncp") && ID.matches(name.removeSuffix(".ncp")) && client.canonical(entry.href)==path(session.id,name)) result.add(name.removeSuffix(".ncp"))
            if(!entry.directory && name.endsWith(".ncv") && ID.matches(name.removeSuffix(".ncv")) && client.canonical(entry.href)==path(session.id,name)) result.add(name)
        }
        result.sorted()
    }
    suspend fun importPhoto(session: VaultSession, uri: Uri, progress: (Long)->Unit = {}) = withContext(Dispatchers.IO) {
        val resolver=context.contentResolver
        val mime=resolver.getType(uri).orEmpty()
        require(mime.startsWith("image/") || mime.startsWith("video/")) { tr(R.string.msg_293_select_a_photo_or_video) }
        val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: tr(R.string.msg_82_photos)
        if(mime.startsWith("video/")) {
            resolver.openInputStream(uri)?.use { importVideo(session,name,mime,it,progress=progress) } ?: error(tr(R.string.msg_294_cannot_read_the_video))
            return@withContext
        }
        val plain=resolver.openInputStream(uri)?.use { readBounded(it,MAX_PHOTO) } ?: error(tr(R.string.msg_295_cannot_read_the_photo))
        importBytes(session,name,plain)
    }
    suspend fun importCloudPhoto(session: VaultSession, media: app.nextcloudphoto.data.Media, progress: (Long)->Unit = {}) = withContext(Dispatchers.IO) {
        require((media.mime.startsWith("image/") || media.mime.startsWith("video/")) && client.inFiles(media.href)) { tr(R.string.msg_296_select_a_cloud_photo_or_video) }
        if(media.mime.startsWith("video/")) {
            client.response("GET",media.href,headers=mapOf("Cache-Control" to "no-store")).use { response ->
                val body=response.body!!
                body.byteStream().use { importVideo(session,media.name,media.mime,it,body.contentLength(),progress) }
            }
            return@withContext
        }
        require(media.size<=MAX_PHOTO) { tr(R.string.msg_297_photo_limit_32_mb) }
        val plain=get(media.href,MAX_PHOTO)
        importBytes(session,media.name,plain)
    }
    private suspend fun importBytes(session: VaultSession, name: String, plain: ByteArray) {
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive();check(!session.closed)
            // Validate decoding before committing ciphertext. Original bytes (including EXIF) remain intact.
            val id=UUID.randomUUID().toString()
            val preview=decodeBitmap(plain,320)
            val thumbOutput=object: ByteArrayOutputStream() { fun wipe() { buf.fill(0);reset() } }
            val thumb=try { preview.compress(Bitmap.CompressFormat.JPEG,75,thumbOutput);thumbOutput.toByteArray() }
                finally { preview.recycle();thumbOutput.wipe() }
            try {
                val sealed=session.withKey { VaultCrypto.seal(it,thumb,"${session.id}/${id}/thumbnail") }
                kotlinx.coroutines.currentCoroutineContext().ensureActive();check(!session.closed);put(path(session.id,"${id}.nct"),sealed)
            } finally { thumb.fill(0) }
            val nameBytes=name.take(512).toByteArray()
            val payload=java.nio.ByteBuffer.allocate(4+nameBytes.size+plain.size).putInt(nameBytes.size).put(nameBytes).put(plain).array()
            try {
                val sealed=session.withKey { VaultCrypto.seal(it,payload,"${session.id}/${id}/photo") }
                kotlinx.coroutines.currentCoroutineContext().ensureActive();check(!session.closed)
                put(path(session.id,"${id}.ncp"),sealed)
            } finally { payload.fill(0);nameBytes.fill(0) }
        } finally { plain.fill(0) }
    }
    suspend fun thumbnail(session: VaultSession, id: String): Bitmap {
        var owned: Bitmap?=null
        try { return withContext(Dispatchers.IO) {
        require(ID.matches(id))
        val sealed=get(path(session.id,"${id}.nct"),512*1024)
        ensureActive()
        val plain=session.withKey { VaultCrypto.open(it,sealed,"${session.id}/${id}/thumbnail") }
        try { ensureActive();session.own(decodeBitmap(plain,320)).also { owned=it } } finally { plain.fill(0) }
        } } catch(e: Exception) { owned?.let(session::release);throw e }
    }
    suspend fun photo(session: VaultSession, id: String): Pair<String,Bitmap> {
        var owned: Bitmap?=null
        try { return withContext(Dispatchers.IO) {
        require(ID.matches(id))
        val sealed=get(path(session.id,"${id}.ncp"),MAX_PHOTO+4096)
        ensureActive()
        val plain=session.withKey { VaultCrypto.open(it,sealed,"${session.id}/${id}/photo") }
        try {
            ensureActive();check(!session.closed)
            val buffer=java.nio.ByteBuffer.wrap(plain)
            val length=buffer.int
            require(length in 1..2048 && length < buffer.remaining()) { tr(R.string.msg_298_photo_data_is_damaged) }
            val name=String(plain,4,length,Charsets.UTF_8)
            val bitmap=decodeBitmap(plain,2048,4+length,plain.size-4-length)
            name to session.own(bitmap).also { owned=it }
        } finally { plain.fill(0) }
        } } catch(e: Exception) { owned?.let(session::release);throw e }
    }
}

/** Wipes the bounded read buffer even on oversize/failure; callers own and wipe the result. */
internal fun readBounded(input: InputStream, limit: Int): ByteArray {
    val buffer=ByteArray(limit)
    var total=0
    try {
        while(total<limit) { val n=input.read(buffer,total,minOf(64*1024,limit-total));if(n<0) return buffer.copyOf(total);total+=n }
        require(input.read()<0) { tr(R.string.msg_299_photo_limit_32_mb_this_file_was_not_uploaded) }
        return buffer.copyOf(total)
    } finally { buffer.fill(0) }
}

internal fun decodeBitmap(bytes: ByteArray, maxSide: Int, offset: Int=0, length: Int=bytes.size): Bitmap {
    val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
    BitmapFactory.decodeByteArray(bytes,offset,length,options)
    require(options.outWidth>0 && options.outHeight>0) { tr(R.string.msg_300_this_device_cannot_decode_the_image) }
    options.inSampleSize=1
    while(maxOf(options.outWidth,options.outHeight)/options.inSampleSize>maxSide) options.inSampleSize*=2
    options.inJustDecodeBounds=false
    val bitmap=BitmapFactory.decodeByteArray(bytes,offset,length,options) ?: error(tr(R.string.msg_301_cannot_decode_this_image))
    return try {
        val exif=ExifInterface(ByteArrayInputStream(bytes,offset,length))
        val matrix=Matrix().apply { if(exif.isFlipped) postScale(-1f,1f);postRotate(exif.rotationDegrees.toFloat()) }
        if(matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true).also { if(it!==bitmap) bitmap.recycle() }
    } catch(_: Exception) { bitmap }
}
