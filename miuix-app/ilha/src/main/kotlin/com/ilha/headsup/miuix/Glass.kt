package com.ilha.headsup.miuix

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.ilha.headsup.miuix.blend.ColorBlendToken
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Barra do topo: quando [active], desfoca o que passa por baixo dela. */
@Composable
fun BlurredBar(
    backdrop: LayerBackdrop,
    active: Boolean,
    content: @Composable () -> Unit,
) {
    val surface = MiuixTheme.colorScheme.surface
    val colors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(color = surface.copy(alpha = 0.8f))),
    )
    Box(
        modifier = if (active) {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = 25f,
                colors = colors,
            )
        } else {
            Modifier
        },
    ) {
        content()
    }
}

/**
 * Cartão de vidro fosco: desfoca o fundo colorido que está atrás dele.
 * Sem [backdrop] (vidro desligado ou aparelho sem suporte) vira um cartão comum.
 */
@Composable
fun GlassCard(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val blend = if (isInDarkTheme()) ColorBlendToken.Overlay_Thin_Light else ColorBlendToken.Pured_Regular_Light
    val colors = BlurDefaults.blurColors(blendColors = blend)
    Card(
        modifier = modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .then(
                if (backdrop != null) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(CardDefaults.CornerRadius),
                        blurRadius = 60f,
                        noiseCoefficient = BlurDefaults.NoiseCoefficient,
                        colors = colors,
                    )
                } else {
                    Modifier
                },
            ),
        colors = CardDefaults.defaultColors(
            color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
        ),
        content = content,
    )
}
