package app.nextcloudphoto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.nextcloudphoto.account.Account
import app.nextcloudphoto.i18n.*
import app.nextcloudphoto.ui.PhotoApp
import app.nextcloudphoto.ui.PhotoViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36],application=TestPhotoApplication::class,qualifiers="en-rUS-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalizationTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var view: android.view.View

    @Test fun `Chinese script and region selection and unsupported fallback`() {
        mapOf("zh-TW" to "zh-Hant", "zh-HK" to "zh-Hant", "zh-MO" to "zh-Hant",
            "zh-CN" to "zh-Hans", "zh-SG" to "zh-Hans", "zh" to "zh-Hans",
            "zh-Hans-TW" to "zh-Hans", "zh-Hant-CN" to "zh-Hant",
            "en-GB" to "en", "ja-JP" to "en", "fr-FR" to "en", "ar" to "en", "ko" to "en"
        ).forEach { (input, expected) -> assertEquals(input,expected,supportedLocale(Locale.forLanguageTag(input)).toLanguageTag()) }
    }

    @Test fun `unsupported primary language ignores secondary Chinese`() {
        val app=ApplicationProvider.getApplicationContext<TestPhotoApplication>()
        val config=Configuration(app.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("fr-FR,zh-TW")) }
        val context=localizedContext(app.createConfigurationContext(config))
        assertEquals("Photos",context.getString(R.string.msg_82_photos))
        assertEquals(Locale.ENGLISH,context.resources.configuration.locales[0])
    }

    @Test fun `cached background resources follow language changes`() {
        assertEquals("Transfers",tr(R.string.msg_84_transfers))
        org.robolectric.RuntimeEnvironment.setQualifiers("zh-rCN-w411dp-h891dp-420dpi")
        assertEquals("传输",tr(R.string.msg_84_transfers))
        org.robolectric.RuntimeEnvironment.setQualifiers("zh-rTW-w411dp-h891dp-420dpi")
        assertEquals("傳輸",tr(R.string.msg_84_transfers))
        org.robolectric.RuntimeEnvironment.setQualifiers("ja-rJP-w411dp-h891dp-420dpi")
        assertEquals("Transfers",tr(R.string.msg_84_transfers))
    }

    @Test fun `English navigation and transfer screen`() { navigation("Photos","Transfers","Settings","Every memory, safely stored","My Nextcloud","locale-en") }

    @Test @Config(qualifiers="zh-rCN-w411dp-h891dp-420dpi")
    fun `Simplified Chinese navigation and transfer screen`() { navigation("照片","传输","设置","每一份回忆，都有着落","我的 Nextcloud","locale-zh-cn") }

    @Test @Config(qualifiers="fr-rFR-w411dp-h891dp-420dpi")
    fun `French system displays English UI`() { navigation("Photos","Transfers","Settings","Every memory, safely stored","My Nextcloud","locale-fr-fallback") }

    @Test @Config(qualifiers="zh-rHK-w411dp-h891dp-420dpi")
    fun `Hong Kong uses Traditional Chinese resources`() {
        assertEquals("傳輸",tr(R.string.msg_84_transfers))
        assertEquals("設定",tr(R.string.msg_85_settings))
    }

    private fun navigation(photos: String,transfers: String,settings: String,empty: String,heading: String,name: String) {
        val app=ApplicationProvider.getApplicationContext<TestPhotoApplication>()
        val vm=PhotoViewModel(app)
        val account=Account("https://example.invalid","test","test")
        app.accounts.save(account);vm.account.value=account
        compose.setContent { view=LocalView.current;PhotoApp(vm) }
        compose.onNode(hasText(photos) and hasClickAction()).assertExists()
        compose.onNodeWithText(transfers).performClick()
        compose.onNodeWithText(empty).assertExists()
        screenshot(name+"-transfers")
        compose.onNodeWithText(settings).performClick()
        compose.onNodeWithText(heading).assertExists()
        screenshot(name+"-settings")
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val root=view.rootView
            val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File("build/reports/screenshots/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
    }
}
