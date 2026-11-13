package com.bitdroid.keys

import android.content.Context

/** User-facing keyboard settings, stored in SharedPreferences. */
data class KeyboardPrefs(
    val theme: String = "tonal",
    val accent: String = "mint",
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
            .putString("theme_v2", theme)
            .putString("accent_v4", accent)
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
                theme = sp.getString("theme_v2", d.theme) ?: d.theme,
                accent = sp.getString("accent_v4", d.accent) ?: d.accent,
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
    val bg: Int,        // keyboard sheet
    val key: Int,       // letter keys
    val mod: Int,       // modifier keys (shift, delete, ?123, , .)
    val onMod: Int,     // text/icons on modifier keys
    val text: Int,      // text on letter keys
    val sub: Int,       // secondary text
    val pressed: Int,
    val border: Int,
    val accent: Int,    // Enter "cookie"
    val onAccent: Int,
    val strip: Int,     // suggestion-bar pill
    val tool: Int,      // toolbar squircles
    val onTool: Int,
    val micBg: Int,
    val micFg: Int,
    val wave: Int,      // wavy line on the space bar / voice ribbon
    val isLight: Boolean,
)

object Themes {
    val THEMES = listOf(
        "tonal" to "Tonal",
        "lavender" to "Lavender",
        "dynamic" to "Wallpaper colors",
        "amoled" to "AMOLED",
        "graphite" to "Graphite",
        "light" to "Light",
    )

    /** "lilac" means each theme's own native lilac. */
    val ACCENTS = listOf(
        "mint" to "Mint",
        "lilac" to "Lilac",
        "blue" to "Blue",
        "coral" to "Coral",
        "dynamic" to "Wallpaper",
    )

    val SHAPES = listOf(
        "square" to "Square",
        "round" to "Rounded",
        "pill" to "Pill",
    )

    private fun c(v: Long): Int = v.toInt()
    private val BLACK = 0xFF000000.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()

    fun accentColor(context: Context, id: String): Int = when (id) {
        "blue" -> c(0xFFA8C7FA)
        "coral" -> c(0xFFFFB4A8)
        "mint" -> c(0xFF7FE0C4)
        "dynamic" -> context.getColor(android.R.color.system_accent1_200)
        else -> c(0xFFB9A7F0) // lilac
    }

    fun palette(context: Context, prefs: KeyboardPrefs): Palette =
        palette(context, prefs.theme, prefs.accent)

    fun palette(context: Context, themeId: String, accentId: String): Palette {
        fun sys(id: Int) = context.getColor(id)
        val base = when (themeId) {
            "lavender" -> Palette(
                bg = c(0xFFE7DDF8), key = c(0xFFFBF8FF), mod = c(0xFFCDBDF2), onMod = c(0xFF2B2342),
                text = c(0xFF2B2342), sub = c(0xFF6C6283), pressed = c(0xFFD9CCF5), border = c(0xFFC2B5DD),
                accent = c(0xFF5B4BA8), onAccent = WHITE, strip = c(0xFFF4EEFF),
                tool = c(0xFF5B4BA8), onTool = c(0xFFECE4FF), micBg = c(0xFF5B4BA8), micFg = WHITE,
                wave = c(0xFF8B78D6), isLight = true,
            )
            "dynamic" -> Palette(
                bg = sys(android.R.color.system_neutral1_900),
                key = sys(android.R.color.system_neutral2_800),
                mod = sys(android.R.color.system_accent1_200),
                onMod = sys(android.R.color.system_accent1_900),
                text = sys(android.R.color.system_neutral1_50),
                sub = sys(android.R.color.system_neutral2_300),
                pressed = sys(android.R.color.system_neutral2_600),
                border = sys(android.R.color.system_neutral2_600),
                accent = sys(android.R.color.system_accent1_300),
                onAccent = sys(android.R.color.system_accent1_900),
                strip = sys(android.R.color.system_neutral2_800),
                tool = sys(android.R.color.system_accent1_700),
                onTool = sys(android.R.color.system_accent1_100),
                micBg = sys(android.R.color.system_accent1_200),
                micFg = sys(android.R.color.system_accent1_900),
                wave = sys(android.R.color.system_accent3_200),
                isLight = false,
            )
            "amoled" -> Palette(
                bg = BLACK, key = c(0xFF1C1C1E), mod = c(0xFF2C2C31), onMod = c(0xFFF2F2F5),
                text = c(0xFFF2F2F5), sub = c(0xFFA0A0A8), pressed = c(0xFF3A3A40), border = c(0xFF3A3A40),
                accent = c(0xFFD7BAFF), onAccent = BLACK, strip = c(0xFF121214),
                tool = c(0xFF1C1C1E), onTool = c(0xFFC8C8CE), micBg = c(0xFF1C1C1E), micFg = c(0xFFD7BAFF),
                wave = c(0xFFD7BAFF), isLight = false,
            )
            "graphite" -> Palette(
                bg = c(0xFF1A1A1D), key = c(0xFF323238), mod = c(0xFF45454C), onMod = c(0xFFF2F2F5),
                text = c(0xFFF2F2F5), sub = c(0xFFB0B0B8), pressed = c(0xFF5A5A62), border = c(0xFF55555C),
                accent = c(0xFFD7BAFF), onAccent = BLACK, strip = c(0xFF26262A),
                tool = c(0xFF323238), onTool = c(0xFFD0D0D6), micBg = c(0xFF323238), micFg = c(0xFFD7BAFF),
                wave = c(0xFFD7BAFF), isLight = false,
            )
            "light" -> Palette(
                bg = c(0xFFE9E9EE), key = WHITE, mod = c(0xFFC9C9D1), onMod = c(0xFF1B1B1F),
                text = c(0xFF1B1B1F), sub = c(0xFF55555D), pressed = c(0xFFD2D2DA), border = c(0xFFB4B4BD),
                accent = c(0xFF5B4BA8), onAccent = WHITE, strip = c(0xFFF6F6F9),
                tool = c(0xFFDCDCE2), onTool = c(0xFF2A2A30), micBg = c(0xFF5B4BA8), micFg = WHITE,
                wave = c(0xFF8B78D6), isLight = true,
            )
            else -> Palette( // Tonal (default) — plum keys, lavender modifiers
                bg = c(0xFF261F30), key = c(0xFF3D3550), mod = c(0xFFD2C4F0), onMod = c(0xFF2C2442),
                text = c(0xFFEFE7FB), sub = c(0xFFB8AECB), pressed = c(0xFF544A69), border = c(0xFF544A69),
                accent = c(0xFFB9A7F0), onAccent = c(0xFF221A33), strip = c(0xFF322A3F),
                tool = c(0xFF4D3F96), onTool = c(0xFFD9CCFF), micBg = c(0xFFD2C4F0), micFg = c(0xFF2C2442),
                wave = c(0xFFF2C6EA), isLight = false,
            )
        }
        // A non-lilac accent recolors the Enter key, mic and wavy line.
        if (accentId == "lilac") return base
        val a = accentColor(context, accentId)
        return base.copy(accent = a, onAccent = BLACK, micBg = a, micFg = BLACK, wave = a)
    }

    /** Corner radius in dp for keys. */
    fun keyRadiusDp(shape: String): Float = when (shape) {
        "pill" -> 100f
        "square" -> 6f
        else -> 16f
    }
}
