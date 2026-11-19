package com.nova.assistant.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.nova.assistant.core.AliasDictionary
import com.nova.assistant.core.FuzzyMatcher
import com.nova.assistant.permission.PermissionManager

/**
 * Запуск приложений и системных экранов. Использует только штатные
 * механизмы Android (PackageManager + Intent).
 */
class AppLauncher(private val context: Context) {

    private val pm = context.packageManager

    fun listApps(): List<PermissionManager.AppInfo> = PermissionManager.loadInstalledApps(context)

    /**
     * Находит приложение по названию: сначала локальный словарь псевдонимов,
     * затем нечёткое совпадение с ярлыками установленных приложений.
     */
    fun resolve(name: String): PermissionManager.AppInfo? {
        val apps = listApps()
        val norm = FuzzyMatcher.normalize(name)
        if (norm.isEmpty()) return null

        // 1. Словарь псевдонимов → ищем ярлык, подходящий под любой псевдоним канона.
        var bestCanon: String? = null
        var bestScore = 0f
        for ((canon, aliases) in AliasDictionary.apps) {
            for (a in aliases) {
                val s = FuzzyMatcher.scoreTranslit(norm, a)
                if (s > bestScore) { bestScore = s; bestCanon = canon }
            }
        }
        if (bestCanon != null && bestScore >= 0.66f) {
            val aliases = AliasDictionary.apps[bestCanon].orEmpty()
            var bestApp: PermissionManager.AppInfo? = null
            var bestAppScore = 0f
            for (app in apps) {
                for (a in aliases) {
                    val s = FuzzyMatcher.scoreTranslit(FuzzyMatcher.normalize(app.label), a)
                    if (s > bestAppScore) { bestAppScore = s; bestApp = app }
                }
            }
            if (bestApp != null && bestAppScore >= 0.62f) return bestApp
        }

        // 2. Прямое нечёткое совпадение с ярлыком.
        val m = FuzzyMatcher.bestMatch(name, apps.map { it.label }, 0.62f) ?: return null
        return apps.firstOrNull { it.label == m.value }
    }

    fun launch(app: PermissionManager.AppInfo): Boolean {
        val intent = pm.getLaunchIntentForPackage(app.packageName) ?: return false
        return launch(intent)
    }

    fun launch(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    fun launchByName(name: String): PermissionManager.AppInfo? {
        val app = resolve(name) ?: return null
        return if (launch(app)) app else null
    }

    fun openSettings(section: String?): Boolean {
        val intent = when (section?.lowercase()) {
            null, "настройки", "параметры" -> Intent(Settings.ACTION_SETTINGS)
            "wi-fi", "wifi", "вайфай", "сеть" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth", "блютуз" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "экран", "дисплей" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "звук", "громкость" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "батарея" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "память" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "уведомления" -> if (android.os.Build.VERSION.SDK_INT >= 26) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            } else {
                Intent(Settings.ACTION_SETTINGS)
            }
            "приложения" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            "обои" -> Intent(Intent.ACTION_SET_WALLPAPER)
            "блокировка", "безопасность" -> Intent(Settings.ACTION_SECURITY_SETTINGS)
            "местоположение", "геоданные" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "язык" -> Intent(Settings.ACTION_LOCALE_SETTINGS)
            "дата и время" -> Intent(Settings.ACTION_DATE_SETTINGS)
            "режим полета", "модем" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            "разработчик" -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        return launch(intent)
    }

    fun openUrl(url: String): Boolean {
        val u = if (url.startsWith("http")) url else "https://$url"
        return launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
    }

    fun home(): Boolean = launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun back() {
        AccessibilityController.back { }
    }

    fun recents() {
        AccessibilityController.recents { }
    }

    fun recordUsage(pkg: String, label: String) {
        // Используется для подсказок «частые приложения». Не критично при сбое.
    }
}
