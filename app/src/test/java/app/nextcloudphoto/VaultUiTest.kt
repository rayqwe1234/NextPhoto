package app.nextcloudphoto

import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import app.nextcloudphoto.account.Account
import app.nextcloudphoto.ui.PhotoApp
import app.nextcloudphoto.ui.PhotoViewModel
import app.nextcloudphoto.vault.VaultCrypto
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36],application=TestPhotoApplication::class,qualifiers="zh-rTW-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VaultUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun `password unlock is protected from screenshots and stop locks before resume`() {
        MockWebServer().use { server ->
            server.start()
            val id="12345678-1234-1234-1234-123456789abc"
            val key=VaultCrypto.random(32)
            val header=VaultCrypto.header(id,"私人相簿","correct password!".toCharArray(),key)
            val photoId="aaaaaaaa-1234-1234-1234-123456789abc"
            val sample=android.graphics.Bitmap.createBitmap(300,200,android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(sample).apply {
                drawColor(android.graphics.Color.rgb(53,114,159))
                drawCircle(210f,65f,38f,android.graphics.Paint().apply { color=android.graphics.Color.rgb(255,205,150) })
            }
            val png=java.io.ByteArrayOutputStream().also { sample.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
            sample.recycle()
            val filename="秘密照片.png".toByteArray()
            val payload=java.nio.ByteBuffer.allocate(4+filename.size+png.size).putInt(filename.size).put(filename).put(png).array()
            val sealedPhoto=VaultCrypto.seal(key,payload,"$id/$photoId/photo")
            val sealedThumb=VaultCrypto.seal(key,png,"$id/$photoId/thumbnail")
            payload.fill(0);png.fill(0);key.fill(0)
            val root="/remote.php/dav/files/test/Nextcloud%20Photo%20Vault"
            server.dispatcher=object: Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method=="PROPFIND" && request.path==root -> MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:"><d:response><d:href>$root/$id</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""")
                    request.method=="PROPFIND" -> MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:"><d:response><d:href>$root/$id/$photoId.ncp</d:href><d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""")
                    request.path?.endsWith(".ncp")==true -> MockResponse().setBody(okio.Buffer().write(sealedPhoto))
                    request.path?.endsWith(".nct")==true -> MockResponse().setBody(okio.Buffer().write(sealedThumb))
                    request.path?.endsWith("vault.json")==true -> MockResponse().setBody(okio.Buffer().write(header))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val app=ApplicationProvider.getApplicationContext<TestPhotoApplication>()
            val vm=PhotoViewModel(app)
            val account=Account(server.url("/").toString(),"test","test")
            app.accounts.save(account);vm.account.value=account
            lateinit var lifecycle: LifecycleRegistry
            lateinit var activity: Activity
            lateinit var view: android.view.View
            compose.setContent {
                lifecycle=LocalLifecycleOwner.current.lifecycle as LifecycleRegistry
                view=LocalView.current
                var c=view.context
                while(c !is Activity && c is ContextWrapper) c=c.baseContext
                activity=c as Activity
                PhotoApp(vm)
            }
            compose.onNodeWithText("相簿").performClick()
            compose.onNodeWithText("加密相簿").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("已鎖定的相簿 · 12345678").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            compose.onNodeWithText("已鎖定的相簿 · 12345678").performClick()
            compose.onNodeWithText("指紋解鎖").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("首次設定：先用相簿密碼解鎖，再點選「啟用指紋解鎖」").assertIsDisplayed()
            val requestsBefore=server.requestCount
            compose.onNodeWithText("指紋解鎖").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("私人相簿").assertDoesNotExist()
            assertEquals(requestsBefore,server.requestCount)
            compose.runOnIdle {
                val bitmap=android.graphics.Bitmap.createBitmap(view.rootView.width,view.rootView.height,android.graphics.Bitmap.Config.ARGB_8888)
                view.rootView.draw(android.graphics.Canvas(bitmap))
                val file=java.io.File("build/reports/screenshots/vault-fingerprint-entry.png").apply { parentFile!!.mkdirs() }
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
            compose.onNodeWithText("相簿密碼").performScrollTo().performTextInput("correct password!")
            compose.onNodeWithText("密碼解鎖").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText("私人相簿").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("選擇手機照片／影片").assertExists()
            compose.onNodeWithText("啟用指紋解鎖").assertIsDisplayed()
            compose.onNodeWithText("下次使用指紋解鎖").assertIsDisplayed()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("加密照片").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("加密照片").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("秘密照片.png").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle {
                val bitmap=android.graphics.Bitmap.createBitmap(view.rootView.width,view.rootView.height,android.graphics.Bitmap.Config.ARGB_8888)
                view.rootView.draw(android.graphics.Canvas(bitmap))
                val file=java.io.File("build/reports/screenshots/vault-unlocked.png").apply { parentFile!!.mkdirs() }
                file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            compose.onNodeWithText("私人相簿").assertDoesNotExist()
            compose.onNodeWithText("秘密照片.png").assertDoesNotExist()
            compose.onNodeWithContentDescription("加密照片").assertDoesNotExist()
            compose.onNodeWithText("密碼解鎖").assertExists()
            compose.onNodeWithContentDescription("返回並鎖定").performClick()
            compose.onNodeWithContentDescription("返回並鎖定").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("雲端資料夾").assertExists()
            assertEquals(0,activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
