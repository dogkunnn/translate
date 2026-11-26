package com.ict.livetranslate

import android.graphics.Color
import android.graphics.drawable.GradientDrawable

data class NexsusTheme(
    val id: String,
    val label: String,
    val isDark: Boolean,
    val bg: Int,
    val card: Int,
    val cardBorder: Int,
    val inputBg: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
    val accentSoft: Int,
    val statusGood: Int,
    val danger: Int,
    val navBg: Int
)

object ThemeManager {
    val themes = listOf(
        NexsusTheme(
            id = "pastel_violet",
            label = "Pastel Violet (มืด)",
            isDark = true,
            bg = Color.parseColor("#0F0C1D"),
            card = Color.parseColor("#1A1530"),
            cardBorder = Color.parseColor("#352B5C"),
            inputBg = Color.parseColor("#120E24"),
            textPrimary = Color.parseColor("#F5F3FF"),
            textSecondary = Color.parseColor("#B4A5E8"),
            accent = Color.parseColor("#A78BFA"),
            accentSoft = Color.parseColor("#2A214A"),
            statusGood = Color.parseColor("#6EE7B7"),
            danger = Color.parseColor("#F472B6"),
            navBg = Color.parseColor("#161129")
        ),
        NexsusTheme(
            id = "midnight_mint",
            label = "Midnight Mint (มืด)",
            isDark = true,
            bg = Color.parseColor("#0B1418"),
            card = Color.parseColor("#132026"),
            cardBorder = Color.parseColor("#243B45"),
            inputBg = Color.parseColor("#0D181D"),
            textPrimary = Color.parseColor("#ECFDF5"),
            textSecondary = Color.parseColor("#99D5C9"),
            accent = Color.parseColor("#5EEAD4"),
            accentSoft = Color.parseColor("#1A3338"),
            statusGood = Color.parseColor("#6EE7B7"),
            danger = Color.parseColor("#FB7185"),
            navBg = Color.parseColor("#101B20")
        ),
        NexsusTheme(
            id = "lavender_light",
            label = "Soft Lavender (สว่าง)",
            isDark = false,
            bg = Color.parseColor("#F5F3FF"),
            card = Color.parseColor("#FFFFFF"),
            cardBorder = Color.parseColor("#DDD6FE"),
            inputBg = Color.parseColor("#FAF8FF"),
            textPrimary = Color.parseColor("#1E1B4B"),
            textSecondary = Color.parseColor("#6D5BA3"),
            accent = Color.parseColor("#8B5CF6"),
            accentSoft = Color.parseColor("#EDE9FE"),
            statusGood = Color.parseColor("#059669"),
            danger = Color.parseColor("#DB2777"),
            navBg = Color.parseColor("#FFFFFF")
        ),
        NexsusTheme(
            id = "cloud_cream",
            label = "Cloud Cream (สว่าง)",
            isDark = false,
            bg = Color.parseColor("#FAF7F2"),
            card = Color.parseColor("#FFFFFF"),
            cardBorder = Color.parseColor("#E7DFD3"),
            inputBg = Color.parseColor("#FDFBF7"),
            textPrimary = Color.parseColor("#292524"),
            textSecondary = Color.parseColor("#78716C"),
            accent = Color.parseColor("#A855F7"),
            accentSoft = Color.parseColor("#F3E8FF"),
            statusGood = Color.parseColor("#0D9488"),
            danger = Color.parseColor("#E11D48"),
            navBg = Color.parseColor("#FFFFFF")
        )
    )

    fun byId(id: String?): NexsusTheme = themes.firstOrNull { it.id == id } ?: themes.first()

    fun roundedBox(fillColor: Int, strokeColor: Int, radiusDp: Float, density: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(fillColor)
            setStroke((1f * density).toInt().coerceAtLeast(1), strokeColor)
        }
    }
}
