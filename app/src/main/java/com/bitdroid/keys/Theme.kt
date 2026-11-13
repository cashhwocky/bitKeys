package com.bitdroid.keys

import android.content.Context

/** User-facing keyboard settings, stored in SharedPreferences. */
data class KeyboardPrefs(
    val theme: String = "amoled",
    val accent: String = "lilac",
    val shape: String = "square",
    val borders: Boolean = false,
    val numberRow: Boolean = false,
    val haptics: Boolean = true,
    val sound: Boolean = false,
    val autoCaps: Boolean = true,
    val numpad: Boolean = true,
    val autocorrect: Boolean = true,
) {
    fun save(context: Context) {
        Storage.prefs(context, NAME).edit()
            .putString("theme", theme)
            .putString("accent_v3", accent)
            .putString("shape_v2", shape)
            .putBoolean("borders", borders)
            .putBoolean("number_row", numberRow)
            .putBoolean("haptics", haptics)
            .putBoolean("sound", sound)
            .putBoolean("auto_caps", autoCaps)
            .putBoolean("numpad", numpad)
            .putBoolean("autocorrect", autocorrect)
            .apply()
    }

    companion object {
        const val NAME = "bitkeys"

        fun load(context: Context): KeyboardPrefs {
            val sp = Storage.prefs(context, NAME)
            val d = KeyboardPrefs()
            return KeyboardPrefs(
                theme = sp.getString("theme", d.theme) ?: d.theme,
                accent = sp.getString("accent_v3", d.accent) ?: d.accent,
                shape = sp.getString("shape_v2", d.shape) ?: d.shape,
                borders = sp.getBoolean("borders", d.borders),
                numberRow = sp.getBoolean("number_row", d.numberRow),
                haptics = sp.getBoolean("haptics", d.haptics),
                sound = sp.getBoolean("sound", d.sound),
                autoCaps = sp.getBoolean("auto_caps", d.autoCaps),
                numpad = sp.getBoolean("numpad", d.numpad),
                autocorrect = sp.getBoolean("autocorrect", d.autocorrect),
            )
        }
    }
}

/** Resolved colors for drawing the keyboard. */
data class Palette(
    val bg: Int,
    val key: Int,
    val mod: Int,
    val text: Int,
    val sub: Int,
    val pressed: Int,
    val border: Int,
    val accent: Int,
    val onAccent: Int,
    val isLight: Boolean,
)

object Themes {
    val THEMES = listOf(
        "amoled" to "AMOLED",
        "graphite" to "Graphite",
        "light" to "Light",
        "dynamic" to "Wallpaper colors",
    )

    val ACCENTS = listOf(
        "lilac" to "Lilac",
        "blue" to "Blue",
        "mint" to "Mint",
        "coral" to "Coral",
        "dynamic" to "Wallpaper",
    )

    val SHAPES = listOf(
        "square" to "Square",
        "round" to "Rounded",
        "pill" to "Pill",
    )

    private fun c(v: Long): Int = v.toInt()

    fun accentColor(context: Context, id: String): Int = when (id) {
        "blue" -> c(0xFFA8C7FA)
        "coral" -> c(0xFFFFB4A8)
        "lilac", "lavender" -> c(0xFFD7BAFF)
        "dynamic" -> context.getColor(android.R.color.system_accent1_200)
        else -> c(0xFF7FE0C4)
    }

    fun palette(context: Context, prefs: KeyboardPrefs): Palette =
        palette(context, prefs.theme, prefs.accent)

    fun palette(context: Context, themeId: String, accentId: String): Palette {
        val accent = accentColor(context, accentId)
        val onAccent = c(0xFF000000)
        return when (themeId) {
            "graphite" -> Palette(
                bg = c(0xFF1A1A1D), key = c(0xFF323238), mod = c(0xFF45454C),
                text = c(0xFFF2F2F5), sub = c(0xFFB0B0B8), pressed = c(0xFF5A5A62),
                border = c(0xFF55555C), accent = accent, onAccent = onAccent, isLight = false,
            )
            "light" -> Palette(
                bg = c(0xFFE9E9EE), key = c(0xFFFFFFFF), mod = c(0xFFC9C9D1),
                text = c(0xFF1B1B1F), sub = c(0xFF55555D), pressed = c(0xFFD2D2DA),
                border = c(0xFFB4B4BD), accent = accent, onAccent = onAccent, isLight = true,
            )
            "dynamic" -> Palette(
                bg = context.getColor(android.R.color.system_neutral1_900),
                key = context.getColor(android.R.color.system_neutral2_800),
                mod = context.getColor(android.R.color.system_neutral2_700),
                text = context.getColor(android.R.color.system_neutral1_50),
                sub = context.getColor(android.R.color.system_neutral2_300),
                pressed = context.getColor(android.R.color.system_neutral2_600),
                border = context.getColor(android.R.color.system_neutral2_600),
                accent = accent, onAccent = onAccent, isLight = false,
            )
            else -> Palette( // AMOLED (default)
                bg = c(0xFF000000), key = c(0xFF1C1C1E), mod = c(0xFF2C2C31),
                text = c(0xFFF2F2F5), sub = c(0xFFA0A0A8), pressed = c(0xFF3A3A40),
                border = c(0xFF3A3A40), accent = accent, onAccent = onAccent, isLight = false,
            )
        }
    }

    /** Corner radius in dp for normal keys. Wide keys (space, enter, mode) are always pills except in square mode. */
    fun keyRadiusDp(shape: String): Float = when (shape) {
        "pill" -> 100f
        "square" -> 6f
        else -> 16f
    }
}
