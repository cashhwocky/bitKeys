package com.bitdroid.keys

import android.content.Context

/** User-facing keyboard settings, stored in SharedPreferences. */
data class KeyboardPrefs(
    val theme: String = "glass",
    val accent: String = "theme",
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
            .putString("theme_v3", theme)
            .putString("accent_v5", accent)
            .putString("shape_v3", shape)
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
                theme = sp.getString("theme_v3", d.theme) ?: d.theme,
                accent = sp.getString("accent_v5", d.accent) ?: d.accent,
                shape = sp.getString("shape_v3", "square") ?: "square",
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
        "glass" to "Glass",
        "tonal" to "Tonal",
        "lavender" to "Lavender",
        "dynamic" to "Wallpaper colors",
        "amoled" to "AMOLED",
        "graphite" to "Graphite",
        "light" to "Light",
        "gboard" to "Gboard Dark",
        "ocean" to "Midnight Ocean",
        "emerald" to "Emerald Forest",
        "crimson" to "Crimson Velvet",
        "cyber" to "Cyber Neon",
        "rosequartz" to "Rose Quartz",
    )

    /** "lilac" means each theme's own native accent. */
    val ACCENTS = listOf(
        "theme" to "Theme",
        "mint" to "Mint",
        "lilac" to "Lilac",
        "blue" to "Blue",
        "coral" to "Coral",
        "rose" to "Rose",
        "gold" to "Gold",
        "cyan" to "Cyan",
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
        "rose" -> c(0xFFFFB1C8)
        "gold" -> c(0xFFF5C96B)
        "cyan" -> c(0xFF6EE7F9)
        "dynamic" -> context.getColor(android.R.color.system_accent1_200)
        "theme" -> c(0xFF0A84FF) // swatch for "theme default" (Glass blue)
        else -> c(0xFFB9A7F0) // lilac
    }

    fun palette(context: Context, prefs: KeyboardPrefs): Palette =
        palette(context, prefs.theme, prefs.accent)

    fun palette(context: Context, themeId: String, accentId: String): Palette {
        fun sys(id: Int) = context.getColor(id)
        val base = when (themeId) {
            "glass" -> Palette( // Glass (default): smoked panel, flat gray keys, blue action key
                bg = c(0xFF1E1E20), key = c(0xFF5C5C60), mod = c(0xFF3C3C3F), onMod = WHITE,
                text = WHITE, sub = c(0xFF9A9AA0), pressed = c(0xFF7C7C81), border = c(0xFF4A4A4E),
                accent = c(0xFF0A84FF), onAccent = WHITE, strip = c(0xFF1E1E20),
                tool = c(0xFF3C3C3F), onTool = WHITE, micBg = c(0x00000000), micFg = WHITE,
                wave = c(0xFF0A84FF), isLight = false,
            )
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
            "gboard" -> Palette(
                bg = c(0xFF131314), key = c(0xFF282A2F), mod = c(0xFF1F2126), onMod = c(0xFFE3E3E3),
                text = c(0xFFE3E3E3), sub = c(0xFF9AA0A6), pressed = c(0xFF3B3E47), border = c(0xFF33363E),
                accent = c(0xFFA8C7FA), onAccent = c(0xFF062E6F), strip = c(0xFF1A1B1E),
                tool = c(0xFF282A2F), onTool = c(0xFFE3E3E3), micBg = c(0xFFA8C7FA), micFg = c(0xFF062E6F),
                wave = c(0xFFA8C7FA), isLight = false,
            )
            "ocean" -> Palette(
                bg = c(0xFF0D1520), key = c(0xFF182638), mod = c(0xFF233852), onMod = c(0xFFD7ECFF),
                text = c(0xFFEAF4FF), sub = c(0xFF8AA8C7), pressed = c(0xFF2E496B), border = c(0xFF2B425E),
                accent = c(0xFF6EE7F9), onAccent = c(0xFF052630), strip = c(0xFF131F2F),
                tool = c(0xFF1E324A), onTool = c(0xFFBDE6FF), micBg = c(0xFF6EE7F9), micFg = c(0xFF052630),
                wave = c(0xFF6EE7F9), isLight = false,
            )
            "emerald" -> Palette(
                bg = c(0xFF0E1B16), key = c(0xFF193026), mod = c(0xFF244738), onMod = c(0xFFD6F7E7),
                text = c(0xFFE8FBF0), sub = c(0xFF8AB8A2), pressed = c(0xFF315E4B), border = c(0xFF2B5241),
                accent = c(0xFF7FE0C4), onAccent = c(0xFF08291E), strip = c(0xFF13241D),
                tool = c(0xFF1F3D31), onTool = c(0xFFBDF5E1), micBg = c(0xFF7FE0C4), micFg = c(0xFF08291E),
                wave = c(0xFF7FE0C4), isLight = false,
            )
            "crimson" -> Palette(
                bg = c(0xFF1E1014), key = c(0xFF361C24), mod = c(0xFF522936), onMod = c(0xFFFFD9E2),
                text = c(0xFFFFECF0), sub = c(0xFFC997A4), pressed = c(0xFF693545), border = c(0xFF592D3B),
                accent = c(0xFFFFB1C8), onAccent = c(0xFF3B0918), strip = c(0xFF29161C),
                tool = c(0xFF47232E), onTool = c(0xFFFFD1DF), micBg = c(0xFFFFB1C8), micFg = c(0xFF3B0918),
                wave = c(0xFFFFB1C8), isLight = false,
            )
            "cyber" -> Palette(
                bg = c(0xFF0B0A14), key = c(0xFF1A1630), mod = c(0xFF2B224D), onMod = c(0xFF6EE7F9),
                text = c(0xFFF3EEFF), sub = c(0xFFA594D9), pressed = c(0xFF3B2E6B), border = c(0xFF382C66),
                accent = c(0xFFFF6AC1), onAccent = c(0xFF1F0415), strip = c(0xFF131024),
                tool = c(0xFF261E45), onTool = c(0xFF6EE7F9), micBg = c(0xFFFF6AC1), micFg = c(0xFF1F0415),
                wave = c(0xFF6EE7F9), isLight = false,
            )
            "rosequartz" -> Palette(
                bg = c(0xFFF7E6EC), key = c(0xFFFFF9FB), mod = c(0xFFEBC5D3), onMod = c(0xFF3D1E29),
                text = c(0xFF3D1E29), sub = c(0xFF875C6B), pressed = c(0xFFF0D0DC), border = c(0xFFDFB6C5),
                accent = c(0xFFA83860), onAccent = WHITE, strip = c(0xFFFFF2F6),
                tool = c(0xFFA83860), onTool = c(0xFFFFE8F0), micBg = c(0xFFA83860), micFg = WHITE,
                wave = c(0xFFD46A8E), isLight = true,
            )
            else -> Palette( // Tonal (default) — plum keys, lavender modifiers
                bg = c(0xFF261F30), key = c(0xFF3D3550), mod = c(0xFFD2C4F0), onMod = c(0xFF2C2442),
                text = c(0xFFEFE7FB), sub = c(0xFFB8AECB), pressed = c(0xFF544A69), border = c(0xFF544A69),
                accent = c(0xFFB9A7F0), onAccent = c(0xFF221A33), strip = c(0xFF322A3F),
                tool = c(0xFF4D3F96), onTool = c(0xFFD9CCFF), micBg = c(0xFFD2C4F0), micFg = c(0xFF2C2442),
                wave = c(0xFFF2C6EA), isLight = false,
            )
        }
        // "theme" keeps each theme's own accent; any other choice recolors the action key, mic and wave.
        if (accentId == "theme" || (accentId == "lilac" && themeId != "glass")) return base
        val a = accentColor(context, accentId)
        if (themeId == "glass") return base.copy(accent = a, onAccent = BLACK, wave = a)
        return base.copy(accent = a, onAccent = BLACK, micBg = a, micFg = BLACK, wave = a)
    }

    /** Corner radius in dp for keys. */
    fun keyRadiusDp(shape: String): Float = when (shape) {
        "pill" -> 100f
        "square" -> 6f
        else -> 16f
    }
}
