package com.ilha.headsup.miuix

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

val LocalIsDark = compositionLocalOf { false }

/** Usado pelo fundo animado para escolher as cores claras ou escuras. */
@Composable
fun isInDarkTheme(): Boolean = LocalIsDark.current

/** 0 = sistema, 1 = claro, 2 = escuro, 3 = cores do papel de parede (Monet). */
@Composable
fun resolveDark(colorMode: Int): Boolean {
    val system = isSystemInDarkTheme()
    return when (colorMode) {
        1 -> false
        2 -> true
        else -> system
    }
}

@Composable
fun IlhaTheme(
    colorMode: Int,
    content: @Composable () -> Unit,
) {
    val dark = resolveDark(colorMode)
    val controller = remember(colorMode, dark) {
        when (colorMode) {
            1 -> ThemeController(ColorSchemeMode.Light)
            2 -> ThemeController(ColorSchemeMode.Dark)
            3 -> ThemeController(ColorSchemeMode.MonetSystem, isDark = dark)
            else -> ThemeController(ColorSchemeMode.System, isDark = dark)
        }
    }
    CompositionLocalProvider(LocalIsDark provides dark) {
        MiuixTheme(controller = controller, content = content)
    }
}
