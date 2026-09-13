package app.nextcloudphoto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.nextcloudphoto.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36],application=TestPhotoApplication::class,qualifiers="zh-rTW-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun `drag handles move and resize crop and slider accepts arbitrary rotation`() {
        val source=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(0xFF8EC5E0.toInt())
            drawCircle(460f,90f,44f,Paint().apply { color=0xFFFFD587.toInt() })
            drawRect(0f,260f,600f,400f,Paint().apply {color=0xFF507C67.toInt()})
        }
        var options by mutableStateOf(EditOptions(left=.2f,top=.2f,right=.8f,bottom=.8f))
        lateinit var view: android.view.View
        compose.setContent {
            view=LocalView.current
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(20.dp)) {
                    Text("編輯照片",style=MaterialTheme.typography.headlineMedium)
                    val preview=remember(options.rotation) { transformBitmap(source,options.copy(left=0f,top=0f,right=1f,bottom=1f)) }
                    CropPreview(preview,options,{options=it},Modifier.fillMaxWidth().height(340.dp))
                    Text("拖曳框內移動，拖曳四角或邊緣縮放。")
                    RotationControl(options.rotation,{options=options.copy(rotation=it)})
                } }
            }
        }
        compose.onNodeWithTag("crop-frame").performTouchInput {
            val image=fittedImageRect(width.toFloat(),height.toFloat(),600,400)
            val start=Offset(image.left+image.width*.2f,image.top+image.height*.2f)
            swipe(start,start+Offset(image.width*.12f,image.height*.12f),500)
        }
        compose.runOnIdle { assertTrue(options.left>.25f);assertTrue(options.top>.25f) }
        val before=options
        compose.onNodeWithTag("crop-frame").performTouchInput {
            val image=fittedImageRect(width.toFloat(),height.toFloat(),600,400)
            val start=Offset(image.left+image.width*(before.left+before.right)/2,image.top+image.height*(before.top+before.bottom)/2)
            swipe(start,start-Offset(image.width*.1f,image.height*.1f),500)
        }
        compose.runOnIdle { assertTrue(options.left<before.left);assertEquals(before.right-before.left,options.right-options.left,.001f) }
        compose.onNodeWithTag("rotation-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(23.5f) }
        compose.onNodeWithText("旋轉 23.5°").assertExists()
        compose.runOnIdle { assertEquals(23.5f,options.rotation,0f) }
        compose.waitForIdle()
        compose.runOnIdle {
            val bitmap=Bitmap.createBitmap(view.rootView.width,view.rootView.height,Bitmap.Config.ARGB_8888)
            view.rootView.draw(Canvas(bitmap))
            val file=File("build/reports/screenshots/editor-crop-rotation.png").apply {parentFile!!.mkdirs()}
            file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
        compose.onNodeWithTag("rotation-slider").performTouchInput { swipe(center,Offset(width*.8f,center.y),500) }
        compose.runOnIdle {assertTrue(options.rotation>23.5f && options.rotation<180f)}
        compose.onNodeWithText("歸零").performClick()
        compose.runOnIdle { assertEquals(0f,options.rotation,0f) }
    }
}
