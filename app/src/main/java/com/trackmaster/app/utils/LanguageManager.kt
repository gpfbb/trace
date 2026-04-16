package com.trackmaster.app.utils

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import com.trackmaster.app.AppLanguage
import java.util.Locale

private const val PREFS_NAME = "trackmaster_prefs"
private const val KEY_APP_LANGUAGE = "app_language"

/**
 * Manages app-wide locale by directly patching Activity Resources.
 *
 * Does NOT call recreate() — instead directly sets the locale on the
 * Activity's Resources and triggers a Compose recomposition via state update.
 *
 * Compose's stringResource() reads from context.resources — we update that
 * in-place so the next recomposition picks up the new locale strings.
 */
object LanguageManager {

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLocale(language: AppLanguage): Locale = when (language) {
        AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
        AppLanguage.ENGLISH -> Locale.ENGLISH
        AppLanguage.MALAY -> Locale("ms", "MY")
    }

    private fun savedLanguage(context: Context): AppLanguage {
        val name = getPrefs(context).getString(KEY_APP_LANGUAGE, null)
        return if (name != null) AppLanguage.valueOf(name) else AppLanguage.ENGLISH
    }

    private fun saveLanguage(context: Context, language: AppLanguage) {
        getPrefs(context).edit().putString(KEY_APP_LANGUAGE, language.name).apply()
    }

    /**
     * Apply locale directly to the Activity's Resources (no recreate).
     * Updates locale in-place so Compose's next recomposition picks up new strings.
     *
     * @param context must be the Activity
     * @param newLanguage language to switch to
     * @param previousLanguage the language before user changed it (for change detection)
     * @param onRecompose callback to trigger Compose recomposition (call setState {})
     */
    fun applyLocale(
        context: Context,
        newLanguage: AppLanguage,
        previousLanguage: AppLanguage? = null,
        onRecompose: (() -> Unit)? = null
    ) {
        val currentSaved = savedLanguage(context)
        val actuallyChanged = previousLanguage?.let { it != newLanguage } ?: (currentSaved != newLanguage)

        android.util.Log.d("LanguageManager", "applyLocale: new=$newLanguage, saved=$currentSaved, prev=$previousLanguage, changed=$actuallyChanged")

        if (!actuallyChanged) {
            android.util.Log.d("LanguageManager", "applyLocale: skip (same)")
            return
        }

        saveLanguage(context, newLanguage)

        if (context is Activity) {
            val locale = getLocale(newLanguage)
            setLocale(context, locale)
            android.util.Log.d("LanguageManager", "applyLocale: locale set to $locale, triggering recompose")
            onRecompose?.invoke()
        } else {
            android.util.Log.w("LanguageManager", "applyLocale: non-Activity context")
        }
    }

    /**
     * Directly patch the Activity's Resources with the new locale.
     * No recreate() needed — Compose re-reads from the updated Resources on recomposition.
     */
    private fun setLocale(activity: Activity, locale: Locale) {
        Locale.setDefault(locale)

        val config = Configuration(activity.resources.configuration)
        config.setLocale(locale)
        config.setLocales(android.os.LocaleList(locale))

        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
        activity.window.decorView.postInvalidate()
    }

    /**
     * Call on Activity cold start to ensure locale is correct.
     * Safe to call every onCreate — skips if already at target.
     */
    fun ensureLocale(activity: Activity) {
        val language = savedLanguage(activity)
        val locale = getLocale(language)
        val currentLocale = activity.resources.configuration.locales[0]

        if (currentLocale != locale) {
            android.util.Log.d("LanguageManager", "ensureLocale: setting ${locale} (current=$currentLocale)")
            setLocale(activity, locale)
        } else {
            android.util.Log.d("LanguageManager", "ensureLocale: already $locale")
        }
    }

    fun restartApp(context: Context) {
        if (context is Activity) {
            context.recreate()
        }
    }
}
