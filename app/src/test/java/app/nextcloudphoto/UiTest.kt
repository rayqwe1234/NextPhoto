package app.nextcloudphoto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.nextcloudphoto.account.Account
import app.nextcloudphoto.data.Media
import app.nextcloudphoto.data.Transfer
import app.nextcloudphoto.ui.PhotoApp
import app.nextcloudphoto.ui.PhotoViewModel
import kotlinx.coroutines.runBlocking
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
class UiTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var rootView: android.view.View
    @Test fun `login validates empty server and renders traditional Chinese`() {
        val app=ApplicationProvider.getApplicationContext<TestPhotoApplication>()
        val vm=PhotoViewModel(app)
        compose.setContent { rootView=LocalView.current; PhotoApp(vm) }
        compose.onNodeWithText("連接我的 Nextcloud").assertIsNotEnabled()
        compose.onNodeWithText("Nextcloud 網址").performTextInput("https://cloud.example.com")
        compose.onNodeWithText("連接我的 Nextcloud").assertIsEnabled()
        screenshot("login")
    }
    @Test fun `cloud timeline renders and navigation opens transfers and settings`() {
        val app=ApplicationProvider.getApplicationContext<TestPhotoApplication>()
        val vm=PhotoViewModel(app)
        val account=Account("https://example.invalid","test","test")
        app.accounts.save(account)
        runBlocking { app.database.dao().saveMedia((1..30).map { n ->
            val bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888)
            val colors=listOf(0xFF80B8D1.toInt(),0xFF608E70.toInt(),0xFFE3BCA6.toInt(),0xFF727CB5.toInt())
            Canvas(bitmap).drawPaint(Paint().apply { shader=LinearGradient(0f,0f,320f,320f,colors[n%4],0xFFF1EEE8.toInt(),Shader.TileMode.CLAMP) })
            val file=File(app.filesDir,"test-$n.png");file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            Media("${account.key}:$n","$n","https://example.invalid/remote.php/dav/files/test/$n.jpg","測試照片 $n.jpg","https://example.invalid/remote.php/dav/files/test","image/jpeg",4000,0,1788832800000L-(n/9)*86400000L,"etag",favorite=n%7==0,offline=file.absolutePath,offlineEtag="etag")
        }) }
        vm.account.value=account
        compose.setContent { rootView=LocalView.current; PhotoApp(vm) }
        compose.waitUntil(10000) { compose.onAllNodesWithText("30 個雲端瞬間").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(20000) { compose.onAllNodesWithContentDescription("測試照片 8.jpg").fetchSemanticsNodes().isNotEmpty() }
        screenshot("timeline-light")
        compose.onNodeWithContentDescription("測試照片 8.jpg").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("測試照片 8.jpg").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("viewer-pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("測試照片 7.jpg").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("傳輸").performClick()
        compose.onNodeWithText("所有回憶，妥善安放").assertExists()
        compose.onNodeWithText("設定").performClick()
        compose.onNodeWithText("我的 Nextcloud").assertExists()
        screenshot("settings-light")
        compose.onNodeWithText("深色").performScrollTo().performClick()
        compose.onNodeWithText("照片").performClick()
        screenshot("timeline-dark")
    }
    @Test fun `clear transfer history keeps queued work visible`() {
        val app=ApplicationProvider.getApplicationContext<TestPhotoApplication>()
        val vm=PhotoViewModel(app)
        val account=Account("https://example.invalid","test","test")
        app.accounts.save(account)
        runBlocking {
            listOf("DONE","QUEUED").forEach { state -> app.database.dao().insertTransfer(
                Transfer(state,"source",state,"dest",state+".jpg","image/jpeg",123,100,state=state)
            ) }
        }
        vm.account.value=account
        compose.setContent { rootView=LocalView.current; PhotoApp(vm) }
        compose.onNodeWithText("傳輸").performClick()
        compose.onNodeWithText("僅在 Wi-Fi 等不計量網路傳輸，背景排程可能稍有延遲").assertExists()
        compose.waitUntil(10000) { compose.onAllNodesWithText("DONE.jpg").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("清空歷史記錄").assertIsEnabled().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("DONE.jpg").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("QUEUED.jpg").assertExists()
        compose.onNodeWithText("清空歷史記錄").assertIsNotEnabled()
        screenshot("transfers-history")
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val file=File("build/reports/screenshots/$name.png").apply { parentFile!!.mkdirs() }
        compose.runOnIdle {
            val view=rootView.rootView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
    }
}
