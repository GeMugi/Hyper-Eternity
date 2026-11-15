package com.ilha.relogios.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ilha.relogios.Keys
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max
import kotlin.math.min

private const val MW = 72
private const val MH = 160
private val REQ = Keys.PKG + ".WALL_REQ"
private val RES = Keys.PKG + ".WALL_RES"

/** Pinta sobre a miniatura do papel de parede a área que balança. */
@Composable
fun PaintArea(store: Store, onClose: () -> Unit) {
    val context = LocalContext.current
    val grid = remember { decodeMask(store.wobMask) }
    var tick by remember { mutableIntStateOf(0) }
    var erase by remember { mutableStateOf(false) }
    var brush by remember { mutableIntStateOf(40) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    val dm = context.resources.displayMetrics
    val ratio = dm.widthPixels.toFloat() / dm.heightPixels.toFloat()

    BackHandler { onClose() }

    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = i.getByteArrayExtra("jpg") ?: return
                image = BitmapFactory.decodeByteArray(d, 0, d.size)?.asImageBitmap()
            }
        }
        context.registerReceiver(r, IntentFilter(RES), Context.RECEIVER_EXPORTED)
        context.sendBroadcast(Intent(REQ).setPackage("com.android.systemui"))
        onDispose { context.unregisterReceiver(r) }
    }

    fun paint(p: Offset, size: IntSize) {
        if (size.width <= 0 || size.height <= 0) return
        val cx = p.x / size.width * MW
        val cy = p.y / size.height * MH
        // raio em células: de um traço fino a um bem largo
        val rad = 1.5f + brush / 100f * 13f
        for (y in max(0, (cy - rad).toInt())..min(MH - 1, (cy + rad).toInt())) {
            for (x in max(0, (cx - rad).toInt())..min(MW - 1, (cx + rad).toInt())) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val d = kotlin.math.sqrt(dx * dx + dy * dy)
                if (d > rad) continue
                // miolo cheio e borda macia
                val v = ((1f - d / rad) * 2.2f).coerceIn(0f, 1f)
                val i = y * MW + x
                grid[i] = if (erase) (grid[i] - v).coerceAtLeast(0f) else max(grid[i], v)
            }
        }
        tick++
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .systemBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = tr(if (image == null) "Pedindo o papel de parede à Interface do Sistema… (a extensão precisa estar ativa no LSPosed)" else "Pinte a área que deve balançar"),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF202024)),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { paint(it, size) },
                            onDrag = { change, _ -> paint(change.position, size) },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { paint(it, size) })
                    },
            ) {
                val t = tick
                val img = image
                if (img != null) {
                    drawImage(img, dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                }
                if (t >= 0) {
                    // a área pintada vira uma imagem só, esticada com suavização: um véu claro contínuo
                    val px = IntArray(MW * MH)
                    for (i in px.indices) {
                        val a = (grid[i].coerceIn(0f, 1f) * 150f).toInt()
                        px[i] = (a shl 24) or 0x00FFFFFF
                    }
                    val veil = android.graphics.Bitmap.createBitmap(px, MW, MH, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
                    drawImage(
                        veil,
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                    )
                }
            }
        }
        SliderPreference(
            value = brush.toFloat(),
            onValueChange = { brush = it.toInt().coerceIn(0, 100) },
            title = tr("Tamanho do pincel"),
            valueText = "$brush%",
            valueRange = 0f..100f,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = tr(if (erase) "Borracha" else "Pincel"),
                onClick = { erase = !erase },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            TextButton(
                text = tr("Limpar"),
                onClick = {
                    grid.fill(0f)
                    tick++
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            TextButton(
                text = tr("Salvar"),
                onClick = {
                    store.wobMask = encodeMask(grid)
                    onClose()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

private fun decodeMask(s: String): FloatArray {
    val g = FloatArray(MW * MH)
    try {
        val d = Base64.decode(s, Base64.DEFAULT)
        if (d.size == g.size) for (i in g.indices) g[i] = (d[i].toInt() and 0xff) / 255f
    } catch (t: Throwable) {
    }
    return g
}

private fun encodeMask(g: FloatArray): String {
    val d = ByteArray(g.size)
    for (i in g.indices) d[i] = (g[i].coerceIn(0f, 1f) * 255f).toInt().toByte()
    return Base64.encodeToString(d, Base64.NO_WRAP)
}
