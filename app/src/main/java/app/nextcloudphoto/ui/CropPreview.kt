package app.nextcloudphoto.ui

import app.nextcloudphoto.i18n.tr
import app.nextcloudphoto.R

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.abs

enum class CropHandle { MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT, TOP, RIGHT, BOTTOM }

fun fittedImageRect(width: Float, height: Float, imageWidth: Int, imageHeight: Int): Rect {
    val scale=minOf(width/imageWidth,height/imageHeight)
    val w=imageWidth*scale; val h=imageHeight*scale
    return Rect((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
}

fun cropHandle(point: Offset, crop: Rect, radius: Float): CropHandle? {
    val corners=listOf(crop.topLeft to CropHandle.TOP_LEFT,crop.topRight to CropHandle.TOP_RIGHT,
        crop.bottomLeft to CropHandle.BOTTOM_LEFT,crop.bottomRight to CropHandle.BOTTOM_RIGHT)
    corners.firstOrNull { (p,_) -> (point-p).getDistance()<=radius }?.let { return it.second }
    if(point.y in crop.top-radius..crop.bottom+radius) {
        if(abs(point.x-crop.left)<=radius) return CropHandle.LEFT
        if(abs(point.x-crop.right)<=radius) return CropHandle.RIGHT
    }
    if(point.x in crop.left-radius..crop.right+radius) {
        if(abs(point.y-crop.top)<=radius) return CropHandle.TOP
        if(abs(point.y-crop.bottom)<=radius) return CropHandle.BOTTOM
    }
    return if(crop.contains(point)) CropHandle.MOVE else null
}

/** Crop coordinates refer to the rotated image, so on-screen framing matches exported pixels. */
fun dragCrop(start: EditOptions, handle: CropHandle, dx: Float, dy: Float): EditOptions {
    if(handle==CropHandle.MOVE) {
        val x=dx.coerceIn(-start.left,1f-start.right)
        val y=dy.coerceIn(-start.top,1f-start.bottom)
        return start.copy(left=start.left+x,right=start.right+x,top=start.top+y,bottom=start.bottom+y)
    }
    val left=handle in listOf(CropHandle.LEFT,CropHandle.TOP_LEFT,CropHandle.BOTTOM_LEFT)
    val right=handle in listOf(CropHandle.RIGHT,CropHandle.TOP_RIGHT,CropHandle.BOTTOM_RIGHT)
    val top=handle in listOf(CropHandle.TOP,CropHandle.TOP_LEFT,CropHandle.TOP_RIGHT)
    val bottom=handle in listOf(CropHandle.BOTTOM,CropHandle.BOTTOM_LEFT,CropHandle.BOTTOM_RIGHT)
    return start.copy(
        left=if(left) (start.left+dx).coerceIn(0f,start.right-.05f) else start.left,
        right=if(right) (start.right+dx).coerceIn(start.left+.05f,1f) else start.right,
        top=if(top) (start.top+dy).coerceIn(0f,start.bottom-.05f) else start.top,
        bottom=if(bottom) (start.bottom+dy).coerceIn(start.top+.05f,1f) else start.bottom)
}

private fun EditOptions.pixelCrop(image: Rect)=Rect(image.left+left*image.width,image.top+top*image.height,
    image.left+right*image.width,image.top+bottom*image.height)

@Composable fun CropPreview(bitmap: Bitmap, options: EditOptions, onChange: (EditOptions)->Unit, modifier: Modifier=Modifier, enabled: Boolean=true) {
    val current by rememberUpdatedState(options)
    val change by rememberUpdatedState(onChange)
    Box(modifier) {
        Image(bitmap.asImageBitmap(),tr(R.string.msg_54_edit_preview),Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
        Canvas(Modifier.fillMaxSize().testTag("crop-frame").semantics {
            contentDescription=tr(R.string.msg_55_crop_frame_drag_inside_to_move_or_drag_corners_and_edge)
            stateDescription="${(options.left*100).toInt()},${(options.top*100).toInt()},${(options.right*100).toInt()},${(options.bottom*100).toInt()}"
        }.pointerInput(bitmap.width,bitmap.height,enabled) {
            if(!enabled) return@pointerInput
            var handle: CropHandle?=null
            var start=current
            var delta=Offset.Zero
            var image=Rect.Zero
            detectDragGestures(onDragStart={ point ->
                image=fittedImageRect(size.width.toFloat(),size.height.toFloat(),bitmap.width,bitmap.height)
                start=current;delta=Offset.Zero
                handle=cropHandle(point,start.pixelCrop(image),24.dp.toPx())
            },onDragEnd={handle=null},onDragCancel={handle=null}) { event, distance ->
                handle?.let {
                    event.consume();delta+=distance
                    change(dragCrop(start,it,delta.x/image.width,delta.y/image.height))
                }
            }
        }) {
            val image=fittedImageRect(size.width,size.height,bitmap.width,bitmap.height)
            val crop=options.pixelCrop(image)
            val shade=Color.Black.copy(alpha=.58f)
            fun band(left: Float,top: Float,right: Float,bottom: Float) {
                if(right>left && bottom>top) drawRect(shade,Offset(left,top),Size(right-left,bottom-top))
            }
            band(image.left,image.top,image.right,crop.top)
            band(image.left,crop.bottom,image.right,image.bottom)
            band(image.left,crop.top,crop.left,crop.bottom)
            band(crop.right,crop.top,image.right,crop.bottom)
            drawRect(Color.White,crop.topLeft,crop.size,style=Stroke(1.5.dp.toPx()))
            for(i in 1..2) {
                val x=crop.left+crop.width*i/3;val y=crop.top+crop.height*i/3
                drawLine(Color.White.copy(alpha=.5f),Offset(x,crop.top),Offset(x,crop.bottom),1.dp.toPx())
                drawLine(Color.White.copy(alpha=.5f),Offset(crop.left,y),Offset(crop.right,y),1.dp.toPx())
            }
            val length=minOf(20.dp.toPx(),crop.width/4,crop.height/4)
            val stroke=3.dp.toPx()
            listOf(crop.topLeft to Offset(1f,1f),crop.topRight to Offset(-1f,1f),
                crop.bottomLeft to Offset(1f,-1f),crop.bottomRight to Offset(-1f,-1f)).forEach { (p,d) ->
                drawLine(Color.White,p,p+Offset(d.x*length,0f),stroke)
                drawLine(Color.White,p,p+Offset(0f,d.y*length),stroke)
            }
            val half=8.dp.toPx()
            listOf(crop.topCenter,crop.bottomCenter).forEach { p -> drawLine(Color.White,p-Offset(half,0f),p+Offset(half,0f),stroke) }
            listOf(crop.centerLeft,crop.centerRight).forEach { p -> drawLine(Color.White,p-Offset(0f,half),p+Offset(0f,half),stroke) }
        }
    }
}
