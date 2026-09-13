package app.nextcloudphoto.ui

import app.nextcloudphoto.i18n.tr
import app.nextcloudphoto.R

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.exifinterface.media.ExifInterface
import app.nextcloudphoto.data.Media
import app.nextcloudphoto.data.PhotoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class EditOptions(val rotation: Float=0f,val mirror: Boolean=false,val brightness: Float=0f,val left: Float=0f,val top: Float=0f,val right: Float=1f,val bottom: Float=1f)
fun transformBitmap(source: Bitmap,options: EditOptions): Bitmap {
    val matrix=Matrix().apply { postRotate(options.rotation); if(options.mirror) postScale(-1f,1f) }
    val rotated=Bitmap.createBitmap(source,0,0,source.width,source.height,matrix,true)
    val x=(rotated.width*options.left).toInt().coerceIn(0,rotated.width-1)
    val y=(rotated.height*options.top).toInt().coerceIn(0,rotated.height-1)
    val right=(rotated.width*options.right).toInt().coerceIn(x+1,rotated.width)
    val bottom=(rotated.height*options.bottom).toInt().coerceIn(y+1,rotated.height)
    val cropped=Bitmap.createBitmap(rotated,x,y,right-x,bottom-y)
    val result=Bitmap.createBitmap(cropped.width,cropped.height,Bitmap.Config.ARGB_8888)
    val b=options.brightness*255
    val color=ColorMatrix(floatArrayOf(1f,0f,0f,0f,b,0f,1f,0f,0f,b,0f,0f,1f,0f,b,0f,0f,0f,1f,0f))
    Canvas(result).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(cropped,0f,0f,Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter=ColorMatrixColorFilter(color) }) }
    if(cropped!==source && cropped!==rotated) cropped.recycle()
    if(rotated!==source) rotated.recycle()
    return result
}

@Composable fun Editor(vm: PhotoViewModel,media: Media,dismiss: ()->Unit) {
    var options by remember { mutableStateOf(EditOptions()) }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var original by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var dimensions by remember { mutableStateOf("") }
    LaunchedEffect(media.id) {
        try { withContext(Dispatchers.IO) {
            val file=File(vm.app.cacheDir,"edit-source/${UUID.randomUUID()}")
            vm.repo.client().download(media.href,file)
            original=file
            source=ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder,info,_ ->
                dimensions="${info.size.width} × ${info.size.height}"
                val scale=minOf(1.0,1400.0/maxOf(info.size.width,info.size.height))
                decoder.setTargetSize((info.size.width*scale).toInt(),(info.size.height*scale).toInt())
                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } } catch(e: CancellationException) { throw e } catch(e: Exception) { error=e.message ?: tr(R.string.msg_56_this_image_cannot_be_edited) }
    }
    // Cropping only changes the overlay; keep the image still while dragging the frame.
    LaunchedEffect(source,options.rotation,options.mirror,options.brightness) { source?.let { bitmap ->
        preview=withContext(Dispatchers.Default) { transformBitmap(bitmap,options.copy(left=0f,top=0f,right=1f,bottom=1f)) }
    } }
    DisposableEffect(Unit) { onDispose { original?.delete() } }
    Dialog(onDismissRequest={if(!saving)dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(tr(R.string.msg_57_edit_photo),style=MaterialTheme.typography.headlineMedium)
                Text(tr(R.string.msg_58_save_a_jpeg_copy_original_kept_1_s, dimensions),style=MaterialTheme.typography.bodySmall)
                Box(Modifier.fillMaxWidth().height(340.dp),contentAlignment=Alignment.Center) {
                    preview?.let { CropPreview(it,options,{options=it},Modifier.fillMaxSize(),enabled=!saving) } ?: if(error.isBlank()) CircularProgressIndicator() else Text(error)
                }
                Text(tr(R.string.msg_59_drag_inside_the_crop_frame_to_move_it_or_drag_corners_a),style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick={options=options.copy(mirror=!options.mirror)},enabled=!saving) { Text(tr(R.string.msg_60_mirror)) }
                    TextButton(onClick={options=EditOptions()},enabled=!saving) { Text(tr(R.string.msg_61_reset)) }
                }
                RotationControl(options.rotation,{options=options.copy(rotation=it)},enabled=!saving)
                Text(tr(R.string.msg_62_brightness))
                Slider(options.brightness,{options=options.copy(brightness=it)},valueRange=-.5f.. .5f,enabled=!saving)
                if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
                Button(onClick={
                    saving=true
                    vm.action {
                        try {
                            val file=original ?: error(tr(R.string.msg_63_the_original_has_not_been_downloaded))
                            val full=ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder,info,_ ->
                                require(info.size.width.toLong()*info.size.height<=40_000_000L) { tr(R.string.msg_64_this_image_exceeds_40_megapixels_use_an_external_editor) }
                                val bounds=android.graphics.RectF(0f,0f,info.size.width.toFloat(),info.size.height.toFloat())
                                Matrix().apply { postRotate(options.rotation) }.mapRect(bounds)
                                val required=info.size.width.toLong()*info.size.height*4+ bounds.width().toDouble()*bounds.height()*12
                                require(required < Runtime.getRuntime().maxMemory()*0.65) { tr(R.string.msg_65_not_enough_memory_to_edit_this_photo_at_its_original_re) }
                                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                            }
                            val edited=try { transformBitmap(full,options) } finally { full.recycle() }
                            val output=File(vm.app.filesDir,"edits/${media.name.substringBeforeLast('.')}-edited-${UUID.randomUUID().toString().take(8)}.jpg").apply { parentFile!!.mkdirs() }
                            try { output.outputStream().use { check(edited.compress(Bitmap.CompressFormat.JPEG,95,it)) { tr(R.string.msg_66_cannot_save_the_edited_photo) } } } finally { edited.recycle() }
                            runCatching {
                                val from=ExifInterface(file); val to=ExifInterface(output)
                                listOf(ExifInterface.TAG_DATETIME_ORIGINAL,ExifInterface.TAG_DATETIME,ExifInterface.TAG_MAKE,ExifInterface.TAG_MODEL,
                                    ExifInterface.TAG_GPS_LATITUDE,ExifInterface.TAG_GPS_LATITUDE_REF,ExifInterface.TAG_GPS_LONGITUDE,ExifInterface.TAG_GPS_LONGITUDE_REF).forEach { tag -> from.getAttribute(tag)?.let { to.setAttribute(tag,it) } }
                                to.setAttribute(ExifInterface.TAG_ORIENTATION,"1");to.saveAttributes()
                            }
                            val c=vm.repo.client()
                            val relative=c.trusted(PhotoRepository.parent(media.href)).pathSegments.drop(c.trusted(c.filesRoot).pathSegments.size).joinToString("/")
                            vm.queue.upload(Uri.fromFile(output),relative)
                            withContext(Dispatchers.Main) { saving=false;dismiss();vm.message.value=tr(R.string.msg_67_edited_copy_added_to_the_upload_queue) }
                        } catch(e: Exception) { saving=false;error=e.message ?: tr(R.string.msg_68_editing_failed) }
                    }
                },enabled=source!=null && !saving,modifier=Modifier.fillMaxWidth()) { Text(if(saving) tr(R.string.msg_69_saving_a_copy) else tr(R.string.msg_70_save_a_copy_to_nextcloud)) }
                TextButton(onClick=dismiss,enabled=!saving,modifier=Modifier.fillMaxWidth()) { Text(tr(R.string.msg_71_cancel)) }
            }
        }
    }
}

@Composable fun RotationControl(rotation: Float,onChange: (Float)->Unit,enabled: Boolean=true) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text(tr(R.string.msg_72_rotation_1_s, "%.1f".format(java.util.Locale.ROOT,rotation)))
        TextButton(onClick={onChange(0f)},enabled=enabled && rotation!=0f) { Text(tr(R.string.msg_73_reset_angle)) }
    }
    Slider(rotation,onChange,valueRange=-180f..180f,enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("rotation-slider"))
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text("−180°",style=MaterialTheme.typography.labelSmall)
        Text("0°",style=MaterialTheme.typography.labelSmall)
        Text("+180°",style=MaterialTheme.typography.labelSmall)
    }
}
