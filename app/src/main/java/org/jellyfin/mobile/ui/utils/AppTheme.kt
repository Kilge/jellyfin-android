package org.jellyfin.mobile.ui.utils

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colors = remember(darkTheme) {
        @Suppress("MagicNumber")
        if (darkTheme) {
            darkColors(
                primary = Color(0xFF00A4DC),
                primaryVariant = Color(0xFF202020),
                background = Color(0xFF101010),
                surface = Color(0xFF363636),
                error = Color(0xFFCF6679),
                onPrimary = Color.White,
                onSecondary = Color.White,
                onBackground = Color.White,
                onSurface = Color.White,
                onError = Color.White,
            )
        } else {
            lightColors(
                primary = Color(0xFF00A4DC),
                primaryVariant = Color(0xFF0085AE),
                secondary = Color(0xFF0085AE),
                background = Color(0xFFFAFAFA),
                surface = Color.White,
                error = Color(0xFFB3261E),
                onPrimary = Color.White,
                onSecondary = Color.White,
                onBackground = Color(0xFF101010),
                onSurface = Color(0xFF101010),
                onError = Color.White,
            )
        }
    }
    MaterialTheme(
        colors = colors,
        shapes = Shapes(
            small = RoundedCornerShape(4.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(0.dp),
        ),
        content = content,
    )
}
