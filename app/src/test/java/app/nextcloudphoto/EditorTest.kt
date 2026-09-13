package app.nextcloudphoto

import android.graphics.Bitmap
import android.graphics.Color
import app.nextcloudphoto.ui.*
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorTest {
    @Test fun `moving crop preserves its size and clamps at image boundaries`() {
        val initial=EditOptions(left=.2f,top=.3f,right=.7f,bottom=.8f)
        val moved=dragCrop(initial,CropHandle.MOVE,3f,-3f)
        assertEquals(.5f,moved.left,.0001f);assertEquals(1f,moved.right,.0001f)
        assertEquals(0f,moved.top,.0001f);assertEquals(.5f,moved.bottom,.0001f)
    }
    @Test fun `corner resize cannot invert crop or cross bounds`() {
        val initial=EditOptions(left=.2f,top=.2f,right=.8f,bottom=.8f)
        val smaller=dragCrop(initial,CropHandle.TOP_LEFT,3f,3f)
        assertEquals(.05f,smaller.right-smaller.left,.0001f)
        assertEquals(.05f,smaller.bottom-smaller.top,.0001f)
        val larger=dragCrop(initial,CropHandle.BOTTOM_RIGHT,3f,3f)
        assertEquals(1f,larger.right,.0001f);assertEquals(1f,larger.bottom,.0001f)
    }
    @Test fun `letterbox hit testing aligns handles with image rather than container`() {
        val image=fittedImageRect(400f,400f,400,200)
        assertEquals(100f,image.top,0f);assertEquals(300f,image.bottom,0f)
        assertNull(cropHandle(Offset(200f,20f),image,24f))
        assertEquals(CropHandle.TOP_LEFT,cropHandle(Offset(5f,105f),image,24f))
        assertEquals(CropHandle.MOVE,cropHandle(Offset(200f,200f),image,24f))
    }
    @Test fun `export crops the rotated image using same normalized bounds as preview`() {
        val source=Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        for(x in 60 until 120) for(y in 0 until 80) source.setPixel(x,y,Color.BLUE)
        val exported=transformBitmap(source,EditOptions(rotation=90f,top=.5f))
        assertEquals(80,exported.width);assertEquals(60,exported.height)
        assertEquals(Color.BLUE,exported.getPixel(40,30))
        assertFalse(source.isRecycled)
        exported.recycle();source.recycle()
    }
    @Test fun `fractional rotation keeps precision and matches full preview crop`() {
        val source=Bitmap.createBitmap(160,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val options=EditOptions(rotation=23.5f,left=.2f,top=.2f,right=.8f,bottom=.8f)
        val preview=transformBitmap(source,options.copy(left=0f,top=0f,right=1f,bottom=1f))
        val exported=transformBitmap(source,options)
        val x=(preview.width*.2f).toInt();val y=(preview.height*.2f).toInt()
        assertEquals((preview.width*.8f).toInt()-x,exported.width)
        assertEquals((preview.height*.8f).toInt()-y,exported.height)
        for(px in 0 until exported.width step 7) for(py in 0 until exported.height step 7)
            assertEquals(preview.getPixel(x+px,y+py),exported.getPixel(px,py))
        source.recycle();preview.recycle();exported.recycle()
    }
}
