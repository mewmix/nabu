package com.mewmix.nabu.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class NabuUiMode {
    Modern,
    Brutal
}

@Immutable
data class NabuChrome(
    val screenPadding: Dp,
    val panelPadding: Dp,
    val panelRadius: Dp,
    val controlRadius: Dp,
    val chipRadius: Dp,
    val borderWidth: Dp,
    val buttonHeight: Dp,
    val navItemRadius: Dp,
    val navIconOnly: Boolean
) {
    companion object {
        val Modern = NabuChrome(
            screenPadding = 24.dp,
            panelPadding = 20.dp,
            panelRadius = 24.dp,
            controlRadius = 18.dp,
            chipRadius = 999.dp,
            borderWidth = 1.dp,
            buttonHeight = 56.dp,
            navItemRadius = 24.dp,
            navIconOnly = true
        )

        val Brutal = NabuChrome(
            screenPadding = 16.dp,
            panelPadding = 8.dp,
            panelRadius = 8.dp,
            controlRadius = 6.dp,
            chipRadius = 4.dp,
            borderWidth = 1.dp,
            buttonHeight = 42.dp,
            navItemRadius = 18.dp,
            navIconOnly = false
        )
    }
}

val LocalNabuUiMode = staticCompositionLocalOf { NabuUiMode.Modern }
val LocalNabuChrome = staticCompositionLocalOf { NabuChrome.Modern }
