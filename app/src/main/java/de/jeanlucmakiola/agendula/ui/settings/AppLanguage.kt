package de.jeanlucmakiola.agendula.ui.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import de.jeanlucmakiola.agendula.R
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/**
 * Per-app language via AppCompatDelegate, driven by res/xml/locales_config.xml.
 *
 * That file is the single source of truth for which languages we ship: dropping
 * in a values-<tag> translation and adding a matching `<locale>` entry makes the
 * language show up here and in the system per-app-language settings, with no
 * other code change. The system-default choice is represented as `null`.
 *
 * On API 33+ this delegates to the platform per-app-languages API; below that
 * the appcompat backport persists the choice itself (manifest `autoStoreLocales`
 * service), so we don't mirror it in DataStore. Setting a locale recreates the
 * activity, which re-reads the current value for the picker.
 */
object AppLanguage {

    /**
     * The BCP-47 tags the app ships translations for, in declaration order, as
     * listed in locales_config.xml. Returns whatever could be parsed; a missing
     * or malformed config yields an empty list (the picker then offers only the
     * system-default entry rather than crashing).
     */
    fun supportedTags(context: Context): List<String> {
        val tags = mutableListOf<String>()
        val parser = context.resources.getXml(R.xml.locales_config)
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "locale") {
                    parser.getAttributeValue(ANDROID_NS, "name")?.let(tags::add)
                }
                event = parser.next()
            }
        } catch (_: Exception) {
            // Fall back to whatever was parsed before the failure.
        } finally {
            parser.close()
        }
        return tags
    }

    /** The applied app language as a BCP-47 tag, or `null` when following the system. */
    fun currentTag(): String? {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) null else locales[0]?.toLanguageTag()
    }

    /** Apply a BCP-47 tag, or `null` to follow the system languages. */
    fun apply(tag: String?) {
        val locales = if (tag == null) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /**
     * The autonym for a tag — the language's own name in its own script, e.g.
     * "Deutsch", "English", "Français" — so users find their language regardless
     * of the current UI language. Capitalised per the language's own rules.
     */
    fun displayName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}
