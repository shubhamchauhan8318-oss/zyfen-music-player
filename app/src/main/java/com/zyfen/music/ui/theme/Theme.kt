package com.zyfen.music.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

// ViMusic-inspired dark palette
val ZyfenBg = Color(0xff16171d)        // background0 — app background
val ZyfenSurface = Color(0xff1f2029)   // background1 — player / mini player
val ZyfenCard = Color(0xff2b2d3b)      // background2 — buttons, seek track
val ZyfenPurple = Color(0xff5055c0)    // accent
val ZyfenNeon = Color(0xffbf4040)      // favorites red
val ZyfenPink = Color(0xff5055c0)
val ZyfenText = Color(0xffe1e1e2)
val ZyfenTextSecondary = Color(0xffa3a4a6)
val ZyfenTextDisabled = Color(0xff6f6f73)

private val DarkScheme = darkColorScheme(
    primary = ZyfenPurple,
    onPrimary = Color.White,
    secondary = ZyfenPurple,
    onSecondary = Color.White,
    tertiary = ZyfenPurple,
    background = ZyfenBg,
    onBackground = ZyfenText,
    surface = ZyfenBg,
    onSurface = ZyfenText,
    surfaceVariant = ZyfenCard,
    onSurfaceVariant = ZyfenTextSecondary,
    outline = ZyfenCard,
    error = ZyfenNeon
)

val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold)
)

private val ZyfenTypography = Typography(
    displayLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 56.sp),
    displayMedium = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 44.sp),
    displaySmall = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 36.sp),
    headlineLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 32.sp),
    headlineMedium = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 32.sp),
    headlineSmall = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    titleLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleSmall = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 11.sp)
)

@Composable
fun ZyfenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = ZyfenTypography,
        shapes = Shapes(
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp)
        )
    ) {
        Surface(color = DarkScheme.background, content = content)
    }
}
