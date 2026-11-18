package com.bitdroid.keys

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager

/**
 * Settings live in device-protected storage so the keyboard can read them right
 * after a reboot, before the first unlock (needed to type a lock-screen password).
 */
object Storage {

    fun isUnlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    fun prefs(context: Context, name: String = KeyboardPrefs.NAME): SharedPreferences {
        val dp = context.createDeviceProtectedStorageContext()
        val marker = dp.getSharedPreferences("bitkeys_migrated", Context.MODE_PRIVATE)
        if (!marker.getBoolean(name, false) && isUnlocked(context)) {
            runCatching { dp.moveSharedPreferencesFrom(context, name) }
            marker.edit().putBoolean(name, true).apply()
        }
        return dp.getSharedPreferences(name, Context.MODE_PRIVATE)
    }
}
