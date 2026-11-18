package com.bitdroid.keys

import android.graphics.Color

data class KeyboardTheme(
    val id: String,
    val name: String,
    val subtitle: String,
    val boardBg: Int,
    val stripBg: Int,
    val keyBg: Int,
    val keyPressedBg: Int,
    val funcKeyBg: Int,
    val accentBg: Int,
    val accentText: Int,
    val keyText: Int,
    val subText: Int,
    val dividerColor: Int,
    val popupBg: Int,
    val cornerRadiusDp: Float = 8f
)

object Themes {
    val AMOLED_GBOARD = KeyboardTheme(
        id = "amoled_gboard",
        name = "Gboard Pure AMOLED",
        subtitle = "True #000000 black with Material You sky accents",
        boardBg = Color.parseColor("#000000"),
        stripBg = Color.parseColor("#080A0F"),
        keyBg = Color.parseColor("#181B22"),
        keyPressedBg = Color.parseColor("#2B313E"),
        funcKeyBg = Color.parseColor("#101319"),
        accentBg = Color.parseColor("#A8C7FA"),
        accentText = Color.parseColor("#062E6F"),
        keyText = Color.parseColor("#F2F4F8"),
        subText = Color.parseColor("#8E96A8"),
        dividerColor = Color.parseColor("#242936"),
        popupBg = Color.parseColor("#282E3B"),
        cornerRadiusDp = 9f
    )

    val PIXEL_DARK = KeyboardTheme(
        id = "pixel_dark",
        name = "Gboard Material Dark",
        subtitle = "Official Pixel Gboard charcoal surface",
        boardBg = Color.parseColor("#131314"),
        stripBg = Color.parseColor("#1A1B1E"),
        keyBg = Color.parseColor("#282A2F"),
        keyPressedBg = Color.parseColor("#3B3E47"),
        funcKeyBg = Color.parseColor("#1F2126"),
        accentBg = Color.parseColor("#A8C7FA"),
        accentText = Color.parseColor("#062E6F"),
        keyText = Color.parseColor("#E3E3E3"),
        subText = Color.parseColor("#9AA0A6"),
        dividerColor = Color.parseColor("#33363E"),
        popupBg = Color.parseColor("#383B44"),
        cornerRadiusDp = 9f
    )

    val DYNAMIC_VIOLET = KeyboardTheme(
        id = "dynamic_violet",
        name = "Material You Iris",
        subtitle = "Deep obsidian with lavender M3 pills",
        boardBg = Color.parseColor("#0B0912"),
        stripBg = Color.parseColor("#120F1D"),
        keyBg = Color.parseColor("#211C32"),
        keyPressedBg = Color.parseColor("#342C4E"),
        funcKeyBg = Color.parseColor("#171324"),
        accentBg = Color.parseColor("#D0BCFF"),
        accentText = Color.parseColor("#381E72"),
        keyText = Color.parseColor("#F5EEFF"),
        subText = Color.parseColor("#A89CC2"),
        dividerColor = Color.parseColor("#2E2744"),
        popupBg = Color.parseColor("#342C4E"),
        cornerRadiusDp = 11f
    )

    val PIXEL_LIGHT = KeyboardTheme(
        id = "pixel_light",
        name = "Gboard Daylight",
        subtitle = "Crisp white keys with soft Material tonal elevation",
        boardBg = Color.parseColor("#ECEFF5"),
        stripBg = Color.parseColor("#E2E7F0"),
        keyBg = Color.parseColor("#FFFFFF"),
        keyPressedBg = Color.parseColor("#D8E0EE"),
        funcKeyBg = Color.parseColor("#D5DDEA"),
        accentBg = Color.parseColor("#0B57D0"),
        accentText = Color.parseColor("#FFFFFF"),
        keyText = Color.parseColor("#1F1F1F"),
        subText = Color.parseColor("#5F6368"),
        dividerColor = Color.parseColor("#C6CFDEC0"),
        popupBg = Color.parseColor("#FFFFFF"),
        cornerRadiusDp = 9f
    )

    val ALL = listOf(AMOLED_GBOARD, PIXEL_DARK, DYNAMIC_VIOLET, PIXEL_LIGHT)

    fun byId(id: String): KeyboardTheme =
        ALL.firstOrNull { it.id == id } ?: AMOLED_GBOARD
}
