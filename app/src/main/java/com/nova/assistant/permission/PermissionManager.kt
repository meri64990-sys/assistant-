package com.nova.assistant.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.content.ComponentName
import androidx.core.content.ContextCompat
import com.nova.assistant.service.NovaAccessibilityService

/**
 * Проверка и получение разрешений. Ничего не делает без согласия пользователя.
 */
object PermissionManager {

    data class AppInfo(val label: String, val packageName: String)

    fun canDrawOverlays(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun hasMic(ctx: Context): Boolean = granted(ctx, Manifest.permission.RECORD_AUDIO)
    fun hasCamera(ctx: Context): Boolean = granted(ctx, Manifest.permission.CAMERA)
    fun hasContacts(ctx: Context): Boolean = granted(ctx, Manifest.permission.READ_CONTACTS)
    fun hasNotifications(ctx: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)

    private fun granted(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    /** Включена ли служба спец. возможностей NOVA. */
    fun isAccessibilityEnabled(ctx: Context): Boolean {
        val expected = ComponentName(ctx, NovaAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun overlayIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))

    fun accessibilityIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /** Имена контактов для команд «напиши Саше». Только с разрешением. */
    fun loadContacts(ctx: Context): List<String> {
        if (!hasContacts(ctx)) return emptyList()
        val out = linkedSetOf<String>()
        runCatching {
            val cr = ctx.contentResolver
            val cursor = cr.query(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.Contacts.DISPLAY_NAME),
                null, null,
                android.provider.ContactsContract.Contacts.DISPLAY_NAME + " ASC"
            ) ?: return emptyList()
            cursor.use {
                val idx = it.getColumnIndex(android.provider.ContactsContract.Contacts.DISPLAY_NAME)
                while (it.moveToNext() && out.size < 500) {
                    val name = it.getString(idx) ?: continue
                    if (name.isNotBlank()) out += name
                }
            }
        }
        return out.toList()
    }

    /** Имя → номер телефона (нужно разрешение на контакты). */
    fun loadContactPhones(ctx: Context): Map<String, String> {
        if (!hasContacts(ctx)) return emptyMap()
        val out = linkedMapOf<String, String>()
        runCatching {
            val cr = ctx.contentResolver
            val cursor = cr.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            ) ?: return emptyMap()
            cursor.use {
                val nameIdx = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext() && out.size < 500) {
                    val name = it.getString(nameIdx) ?: continue
                    val number = it.getString(numIdx) ?: continue
                    if (name.isNotBlank() && number.isNotBlank()) out[name] = number
                }
            }
        }
        return out
    }

    /** Установленные приложения (только те, у которых есть ярлык запуска). */
    fun loadInstalledApps(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val result = mutableListOf<AppInfo>()
        runCatching {
            val infos = pm.queryIntentActivities(intent, 0)
            for (ri in infos) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == ctx.packageName) continue
                val label = runCatching { ri.loadLabel(pm).toString() }.getOrNull() ?: continue
                if (label.isNotBlank()) result += AppInfo(label, pkg)
            }
        }
        return result.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }
}
