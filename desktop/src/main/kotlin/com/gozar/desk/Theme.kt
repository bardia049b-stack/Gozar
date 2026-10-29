package com.gozar.desk

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Palette {
    val surface = Color(0xFF131011)
    val surfaceLow = Color(0xFF1D1819)
    val surfaceMid = Color(0xFF211C1D)
    val surfaceHigh = Color(0xFF2C2627)
    val surfaceHighest = Color(0xFF372F30)
    val onSurface = Color(0xFFF2E9E9)
    val onSurfaceSoft = Color(0xFFD6C2C3)
    val outline = Color(0xFF574143)
    val primary = Color(0xFFFF5165)
    val onPrimary = Color(0xFF4A0010)
    val secondaryContainer = Color(0xFF6F3438)
    val onSecondaryContainer = Color(0xFFFFDADD)
    val tertiaryContainer = Color(0xFF7A4300)
    val onTertiaryContainer = Color(0xFFFFDCBE)
    val error = Color(0xFFFFB4AB)
    val ok = Color(0xFF9BE8A8)
    val onOk = Color(0xFF073A16)
    val foxRed = Color(0xFFE4002B)
    val foxRedDark = Color(0xFF8A0018)
    val foxOrange = Color(0xFFFF7A1A)
    val foxCream = Color(0xFFFFF3E8)
    val foxDim = Color(0xFFFFD9E1)
    val foxPeach = Color(0xFFFFE2C9)
    val tileContainer = Color(0xFF7A0018)
    val tileOnContainer = Color(0xFFFFD9DE)
    val mapSurface = Color(0xFF131011)
    val mapLand = Color(0xFFF2E7E7)
    val mapGrid = Color(0xFF9C8A8C)
    val mapOrigin = Color(0xFFFFB1BC)
    val mapExit = Color(0xFFFFD08A)
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
