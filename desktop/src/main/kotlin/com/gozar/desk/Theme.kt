package com.gozar.desk

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val surface = Color(0xFF12131A)
    val surfaceLow = Color(0xFF1C1C23)
    val surfaceMid = Color(0xFF23232A)
    val surfaceHigh = Color(0xFF2D2E35)
    val surfaceHighest = Color(0xFF393840)
    val onSurface = Color(0xFFEFECF7)
    val onSurfaceSoft = Color(0xFFC6C5D5)
    val outline = Color(0xFF454653)
    val primary = Color(0xFFBDC2FF)
    val onPrimary = Color(0xFF000981)
    val secondaryContainer = Color(0xFF656997)
    val onSecondaryContainer = Color(0xFFFFFFFF)
    val tertiaryContainer = Color(0xFFB54C0E)
    val onTertiaryContainer = Color(0xFFFFFFFF)
    val error = Color(0xFFFFB4AB)
    val ok = Color(0xFF9BE8A8)
    val onOk = Color(0xFF073A16)
    val foxRed = Color(0xFFC5002E)
    val foxRedDark = Color(0xFF7A0018)
    val foxOrange = Color(0xFFFF7A1A)
    val foxCream = Color(0xFFFFF3E8)
    val foxDim = Color(0xFFFFD9E1)
    val foxPeach = Color(0xFFFFE2C9)
    val tileContainer = Color(0xFF2A2C52)
    val tileOnContainer = Color(0xFFE0E0FF)
    val mapSurface = Color(0xFF12131A)
    val mapLand = Color(0xFFEEEAF7)
    val mapGrid = Color(0xFF868198)
    val mapOrigin = Color(0xFFD2BCFF)
    val mapExit = Color(0xFF9CC7FF)
}

@Composable
fun GozarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.primary,
            onPrimary = Palette.onPrimary,
            surface = Palette.surface,
            onSurface = Palette.onSurface,
            surfaceVariant = Palette.surfaceHigh,
            onSurfaceVariant = Palette.onSurfaceSoft,
            background = Palette.surface,
            onBackground = Palette.onSurface,
            secondaryContainer = Palette.secondaryContainer,
            onSecondaryContainer = Palette.onSecondaryContainer,
            tertiaryContainer = Palette.tertiaryContainer,
            onTertiaryContainer = Palette.onTertiaryContainer,
            error = Palette.error,
            outline = Palette.outline
        ),
        content = content
    )
}
