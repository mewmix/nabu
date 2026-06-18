package com.mewmix.nabu.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mewmix.nabu.R

private val Quicksand = FontFamily(
    Font(R.font.quicksand_regular, FontWeight.Normal),
    Font(R.font.quicksand_medium, FontWeight.Medium),
    Font(R.font.quicksand_bold, FontWeight.Bold)
)

private val ModernFont = FontFamily.SansSerif

fun appTypography(brutal: Boolean): Typography {
    val family = if (brutal) Quicksand else ModernFont
    val titleWeight = if (brutal) FontWeight.Bold else FontWeight.SemiBold
    val labelWeight = if (brutal) FontWeight.Medium else FontWeight.SemiBold
    return Typography(
        displaySmall = TextStyle(
            fontFamily = family,
            fontWeight = titleWeight,
            fontSize = 32.sp,
            lineHeight = 38.sp
        ),
        headlineSmall = TextStyle(
            fontFamily = family,
            fontWeight = titleWeight,
            fontSize = 26.sp,
            lineHeight = 32.sp
        ),
        titleLarge = TextStyle(
            fontFamily = family,
            fontWeight = titleWeight,
            fontSize = 22.sp,
            lineHeight = 28.sp
        ),
        titleMedium = TextStyle(
            fontFamily = family,
            fontWeight = titleWeight,
            fontSize = 18.sp,
            lineHeight = 24.sp
        ),
        titleSmall = TextStyle(
            fontFamily = family,
            fontWeight = labelWeight,
            fontSize = 15.sp,
            lineHeight = 20.sp
        ),
        bodyLarge = TextStyle(
            fontFamily = family,
            fontSize = 16.sp,
            lineHeight = 24.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = family,
            fontSize = 14.sp,
            lineHeight = 20.sp
        ),
        bodySmall = TextStyle(
            fontFamily = family,
            fontSize = 12.sp,
            lineHeight = 17.sp
        ),
        labelLarge = TextStyle(
            fontFamily = family,
            fontWeight = labelWeight,
            fontSize = 14.sp,
            lineHeight = 20.sp
        ),
        labelMedium = TextStyle(
            fontFamily = family,
            fontWeight = labelWeight,
            fontSize = 12.sp,
            lineHeight = 16.sp
        ),
        labelSmall = TextStyle(
            fontFamily = family,
            fontWeight = labelWeight,
            fontSize = 11.sp,
            lineHeight = 14.sp
        )
    )
}

val AppTypography = appTypography(brutal = false)

val LegacyTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = Quicksand,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = Quicksand,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Quicksand,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Quicksand,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
)
