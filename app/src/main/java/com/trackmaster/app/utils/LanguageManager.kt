package com.trackmaster.app.utils

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
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

        AppLog.d("LanguageManager", "applyLocale: new=$newLanguage, saved=$currentSaved, prev=$previousLanguage, changed=$actuallyChanged")

        if (!actuallyChanged) {
            AppLog.d("LanguageManager", "applyLocale: skip (same)")
            return
        }

        saveLanguage(context, newLanguage)

        if (context is Activity) {
            val locale = getLocale(newLanguage)
            setLocale(context, locale)
            AppLog.d("LanguageManager", "applyLocale: locale set to $locale, triggering recompose")
            onRecompose?.invoke()
        } else {
            AppLog.w("LanguageManager", "applyLocale: non-Activity context")
        }
    }

    /**
     * Directly patch the Activity's Resources with the new locale.
     * Also persists to LocaleManager (Android 13+) so system doesn't override it.
     * No recreate() needed — Compose re-reads from the updated Resources on recomposition.
     */
    private fun setLocale(activity: Activity, locale: Locale) {
        Locale.setDefault(locale)

        // 【Bug修复】同时使用 Android 13+ per-app language API 持久化语言设置
        // 防止 App 在后台被系统 killed 后，语言设置被系统 LocaleManager 覆盖回马来语
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val localeManager = activity.getSystemService(LocaleManager::class.java)
                localeManager?.applicationLocales = LocaleList(locale)
                AppLog.d("LanguageManager", "setLocale: persisted to LocaleManager API (Android 13+)")
            } catch (e: Exception) {
                AppLog.e("LanguageManager", "setLocale: LocaleManager API failed", e)
            }
        }

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
            AppLog.d("LanguageManager", "ensureLocale: setting ${locale} (current=$currentLocale)")
            setLocale(activity, locale)
        } else {
            AppLog.d("LanguageManager", "ensureLocale: already $locale")
        }
    }

    fun restartApp(context: Context) {
        if (context is Activity) {
            context.recreate()
        }
    }
}
