package app.nextcloudphoto.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

/** Resolve only the primary language, so unsupported languages always use English. */
fun supportedLocale(locale: Locale): Locale = when (locale.language) {
    "zh" -> Locale.forLanguageTag(if (locale.script == "Hant" ||
        (locale.script != "Hans" && locale.country in setOf("TW", "HK", "MO"))) "zh-Hant" else "zh-Hans")
    else -> Locale.ENGLISH
}

fun localizedContext(context: Context): Context {
    val configuration = Configuration(context.resources.configuration)
    val primary = configuration.locales.let { if (it.isEmpty) Locale.ENGLISH else it[0] }
    configuration.setLocales(LocaleList(supportedLocale(primary)))
    return context.createConfigurationContext(configuration)
}

object Localization {
    private lateinit var application: Context
    private var cachedConfiguration: Configuration? = null
    private var cachedResources: android.content.res.Resources? = null

    @Synchronized fun initialize(context: Context) {
        application = context.applicationContext
        cachedConfiguration = null
        cachedResources = null
    }

    // Reuse resources while scrolling; refresh when the system configuration changes.
    @Synchronized private fun resources(): android.content.res.Resources {
        val current = application.resources.configuration
        if (cachedConfiguration != current) {
            cachedConfiguration = Configuration(current)
            cachedResources = localizedContext(application).resources
        }
        return checkNotNull(cachedResources)
    }

    fun text(@StringRes id: Int, vararg args: Any?): String = resources().getString(id, *args)
}

fun tr(@StringRes id: Int, vararg args: Any?): String = Localization.text(id, *args)
