package com.bitdroid.keys

import android.content.Context
import android.content.SharedPreferences

object Storage {
    private const val PREFS_NAME = "bitkeys_prefs"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var themeId: String = "amoled_gboard"
    var keyBorders: Boolean = true
    var hapticEnabled: Boolean = true
    var hapticStrength: Int = 22
    var autoCorrect: Boolean = true
    var autoCapitalize: Boolean = true
    var doubleSpacePeriod: Boolean = true
    var showNumberRow: Boolean = true
    var keyHeightScale: Float = 1.0f
    var aggressiveAutocorrect: Boolean = true

    fun load(ctx: Context) {
        val p = prefs(ctx)
        themeId = p.getString("theme_id", "amoled_gboard") ?: "amoled_gboard"
        keyBorders = p.getBoolean("key_borders", true)
        hapticEnabled = p.getBoolean("haptic_enabled", true)
        hapticStrength = p.getInt("haptic_strength", 22)
        autoCorrect = p.getBoolean("auto_correct", true)
        autoCapitalize = p.getBoolean("auto_capitalize", true)
        doubleSpacePeriod = p.getBoolean("double_space_period", true)
        showNumberRow = p.getBoolean("show_number_row", true)
        keyHeightScale = p.getFloat("key_height_scale", 1.0f)
        aggressiveAutocorrect = p.getBoolean("aggressive_autocorrect", true)
    }

    fun save(ctx: Context) {
        prefs(ctx).edit().apply {
            putString("theme_id", themeId)
            putBoolean("key_borders", keyBorders)
            putBoolean("haptic_enabled", hapticEnabled)
            putInt("haptic_strength", hapticStrength)
            putBoolean("auto_correct", autoCorrect)
            putBoolean("auto_capitalize", autoCapitalize)
            putBoolean("double_space_period", doubleSpacePeriod)
            putBoolean("show_number_row", showNumberRow)
            putFloat("key_height_scale", keyHeightScale)
            putBoolean("aggressive_autocorrect", aggressiveAutocorrect)
            apply()
        }
    }

    fun getUserWords(ctx: Context): Map<String, Int> {
        val raw = prefs(ctx).getString("user_dict_v2", "") ?: ""
        if (raw.isBlank()) return emptyMap()
        val map = mutableMapOf<String, Int>()
        raw.split(";").forEach { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                val w = parts[0].trim()
                val c = parts[1].toIntOrNull() ?: 1
                if (w.isNotEmpty()) map[w] = c
            }
        }
        return map
    }

    fun recordUserWord(ctx: Context, word: String) {
        val clean = word.trim().lowercase()
        if (clean.length < 2) return
        val current = getUserWords(ctx).toMutableMap()
        current[clean] = (current[clean] ?: 0) + 5
        val serialized = current.entries
            .sortedByDescending { it.value }
            .take(400)
            .joinToString(";") { "${it.key}:${it.value}" }
        prefs(ctx).edit().putString("user_dict_v2", serialized).apply()
    }
}
