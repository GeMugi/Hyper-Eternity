package com.ilha.headsup.miuix

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilha.headsup.miuix.effect.BgEffectBackground
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.extended.Tune
import kotlin.math.roundToInt

private const val MODULE = "com.ilha.headsup"

/** Abas da barra de baixo, como no app de ajustes do módulo. */
private val Navs = listOf("Início", "Recursos", "Extensões", "Ajustes", "Sobre")

/** As mesmas categorias de Recursos do módulo. */
private val Categories = listOf(
    "Heads-up" to "Estilo, velocidade e conteúdo",
    "Notificações" to "Conversas, linhas de texto e visual dos cartões",
    "Gaveta e pilhas" to "Empilhamento e grupos em pilha",
    "Tela de bloqueio" to "Lista, relógio e rolagem",
    "Central estilo iOS" to "Cortina, relógio do bloqueio e fundo",
    "Barra de status" to "Pílulas do relógio, Instagram e Gboard",
    "Música" to "Player, capa e letras",
    "Privacidade" to "Esconder apps e conversas",
)

private val ColorModes = listOf("Sistema", "Claro", "Escuro", "Cores do papel de parede")

private val PillColors = listOf(
    "Vermelho" to 0xFFFF3B30.toInt(),
    "Laranja" to 0xFFFF9500.toInt(),
    "Amarelo" to 0xFFFFCC00.toInt(),
    "Verde" to 0xFF34C759.toInt(),
    "Azul" to 0xFF0A84FF.toInt(),
    "Roxo" to 0xFFAF52DE.toInt(),
    "Rosa" to 0xFFFF2D55.toInt(),
    "Cinza" to 0xFF8E8E93.toInt(),
)

@Composable
fun IlhaScreen(store: Store) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val listState = rememberLazyListState()
    val barBehavior = MiuixScrollBehavior()
    val shaderOk = remember { isRuntimeShaderSupported() }
    val glassOn = shaderOk && store.glass.value
    val surface = MiuixTheme.colorScheme.surface
    // fundo colorido (para os cartões de vidro)
    val bgBackdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
    // tela inteira (para as barras do topo e de baixo desfocarem o que passa por trás)
    val screenBackdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
    val cardBackdrop: LayerBackdrop? = if (glassOn) bgBackdrop else null

    var nav by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(-1) }
    // Privacidade: 0 = página, 1 = apps, 2 = apps de conversa, 3 = conversas de um app, 4 = contatos
    var priv by remember { mutableIntStateOf(0) }
    var privPkg by remember { mutableStateOf("") }
    var showReset by remember { mutableStateOf(false) }
    BackHandler(enabled = page >= 0 || nav != 0) {
        when {
            page == 7 && priv == 3 -> priv = 2
            page == 7 && priv > 0 -> priv = 0
            page >= 0 -> page = -1
            else -> nav = 0
        }
    }
    LaunchedEffect(nav, page, priv) { listState.scrollToItem(0) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val ok = try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(store.exportJson().toByteArray()) }
                true
            } catch (t: Throwable) {
                false
            }
            toast(context, if (ok) "Ajustes exportados" else "Não consegui salvar o arquivo")
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (t: Throwable) {
                null
            }
            toast(context, if (text != null && store.importJson(text)) "Ajustes importados" else "Arquivo inválido")
        }
    }
    val calendarPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) toast(context, "Sem acesso à agenda")
    }
    val askCalendar = {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast(context, "Acesso à agenda já permitido")
        } else {
            calendarPerm.launch(android.Manifest.permission.READ_CALENDAR)
        }
    }
    val contactsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) priv = 4 else toast(context, "Sem acesso aos contatos")
    }
    val openContacts = {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            priv = 4
        } else {
            contactsPerm.launch(android.Manifest.permission.READ_CONTACTS)
        }
    }

    val title = when {
        nav == 1 && page == 7 && priv == 1 -> "Apps"
        nav == 1 && page == 7 && priv == 2 -> "Conversas"
        nav == 1 && page == 7 && priv == 3 -> appLabel(context, privPkg)
        nav == 1 && page == 7 && priv == 4 -> "Contatos"
        nav == 1 && page >= 0 -> Categories[page].first
        else -> Navs[nav]
    }
    val dark = isInDarkTheme()
    val barBlur = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(color = surface.copy(alpha = 0.8f))),
    )

    Scaffold(
        topBar = {
            Box(
                modifier = if (glassOn) {
                    Modifier.textureBlur(backdrop = screenBackdrop, shape = RectangleShape, blurRadius = 25f, colors = barBlur)
                } else {
                    Modifier
                },
            ) {
                TopAppBar(
                    title = tr(title),
                    largeTitle = tr(title),
                    scrollBehavior = barBehavior,
                    color = if (glassOn) Color.Transparent else surface,
                )
            }
        },
        bottomBar = {
            val icons = listOf(MiuixIcons.Home, MiuixIcons.Tune, MiuixIcons.GridView, MiuixIcons.Settings, MiuixIcons.Info)
            val pick = { i: Int ->
                nav = i
                page = -1
                priv = 0
            }
            if (store.floatingNav.value) {
                // flutuante, só com os ícones, como a do app do Hyper Eternity
                val shape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius)
                FloatingNavigationBar(
                    modifier = if (glassOn) {
                        Modifier.textureBlur(
                            backdrop = screenBackdrop,
                            shape = shape,
                            blurRadius = 25f,
                            colors = BlurDefaults.blurColors(
                                blendColors = listOf(BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f))),
                            ),
                            highlight = if (dark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight,
                        )
                    } else {
                        Modifier
                    },
                    color = if (glassOn) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
                ) {
                    Navs.forEachIndexed { i, label ->
                        FloatingNavigationBarItem(selected = nav == i, onClick = { pick(i) }, icon = icons[i], label = tr(label))
                    }
                }
            } else {
                Box(
                    modifier = if (glassOn) {
                        Modifier.textureBlur(backdrop = screenBackdrop, shape = RectangleShape, blurRadius = 25f, colors = barBlur)
                    } else {
                        Modifier
                    },
                ) {
                    NavigationBar(color = if (glassOn) Color.Transparent else surface) {
                        Navs.forEachIndexed { i, label ->
                            NavigationBarItem(selected = nav == i, onClick = { pick(i) }, icon = icons[i], label = tr(label))
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .then(if (glassOn) Modifier.layerBackdrop(screenBackdrop) else Modifier),
        ) {
            BgEffectBackground(
                dynamicBackground = store.animatedBg.value,
                effectBackground = store.animatedBg.value,
                isFullSize = true,
                modifier = Modifier.fillMaxSize(),
                bgModifier = if (glassOn) Modifier.layerBackdrop(bgBackdrop) else Modifier,
                alpha = { 0.55f },
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(barBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding() + 12.dp,
                    ),
                ) {
                    when (nav) {
                        0 -> homeTab(store, cardBackdrop, context, onFeatures = { nav = 1 })
                        1 -> if (page < 0) {
                            item(key = "cats") {
                                Spacer(Modifier.height(12.dp))
                                GlassCard(cardBackdrop) {
                                    Categories.forEachIndexed { i, c ->
                                        ArrowPreference(title = tr(c.first), summary = tr(c.second), onClick = {
                                            page = i
                                            priv = 0
                                        })
                                    }
                                }
                            }
                        } else {
                            when (page) {
                                0 -> headsUpPage(store, cardBackdrop, context)
                                1 -> notificationsPage(store, cardBackdrop)
                                2 -> stacksPage(store, cardBackdrop)
                                3 -> lockPage(store, cardBackdrop, askCalendar)
                                4 -> curtainPage(store, cardBackdrop)
                                5 -> statusBarPage(store, cardBackdrop)
                                6 -> musicPage(store, cardBackdrop)
                                else -> when (priv) {
                                    1 -> privacyApps(store, cardBackdrop)
                                    2 -> privacyConvApps(store, cardBackdrop, onPick = {
                                        privPkg = it
                                        priv = 3
                                    })
                                    3 -> privacyConvs(store, cardBackdrop, privPkg)
                                    4 -> privacyContacts(store, cardBackdrop)
                                    else -> privacyPage(store, cardBackdrop, onApps = { priv = 1 }, onConvs = { priv = 2 }, onContacts = openContacts)
                                }
                            }
                        }
                        2 -> extensionsTab(store, cardBackdrop, context)
                        3 -> settingsTab(
                            store,
                            cardBackdrop,
                            shaderOk,
                            context,
                            onExport = { exporter.launch("hyper-eternity-ajustes.json") },
                            onImport = { importer.launch(arrayOf("*/*")) },
                            onReset = { showReset = true },
                        )
                        else -> aboutTab(cardBackdrop, uriHandler)
                    }
                }
            }
        }

        OverlayDialog(
            show = showReset,
            title = tr("Restaurar tudo?"),
            summary = tr("Todos os ajustes do Hyper Eternity voltam ao padrão."),
            onDismissRequest = { showReset = false },
        ) {
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(text = tr("Cancelar"), onClick = { showReset = false }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = tr("Restaurar"),
                    onClick = {
                        store.resetAll()
                        showReset = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ peças

@Composable
private fun CardText(text: String) {
    Text(
        text = tr(text),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun IntSlider(
    pref: IntPref,
    title: String,
    summary: String?,
    min: Int,
    max: Int,
    unit: String,
    step: Int = 5,
) {
    SliderPreference(
        value = pref.live.toFloat(),
        onValueChange = { v -> pref.live = ((v / step).roundToInt() * step).coerceIn(min, max) },
        onValueChangeFinished = { pref.save() },
        title = tr(title),
        summary = tr(summary),
        valueText = "${pref.live}$unit",
        valueRange = min.toFloat()..max.toFloat(),
    )
}

@Composable
private fun Toggle(pref: BoolPref, title: String, summary: String?) {
    SwitchPreference(
        title = tr(title),
        summary = tr(summary),
        checked = pref.value,
        onCheckedChange = { pref.value = it },
    )
}

@Composable
private fun ColorChoice(pref: IntPref, title: String, withWhite: Boolean = false) {
    val list = if (withWhite) listOf("Branco" to 0xFFFFFFFF.toInt()) + PillColors else PillColors
    val idx = list.indexOfFirst { it.second == pref.value }.let { if (it < 0) 0 else it }
    OverlayDropdownPreference(
        title = tr(title),
        items = list.map { tr(it.first) },
        selectedIndex = idx,
        onSelectedIndexChange = { pref.value = list[it].second },
    )
}

private fun LazyListScope.section(
    key: String,
    title: String?,
    backdrop: LayerBackdrop?,
    content: @Composable ColumnScope.() -> Unit,
) {
    item(key = key) {
        if (title != null) SmallTitle(text = tr(title)) else Spacer(Modifier.height(12.dp))
        GlassCard(backdrop, content = content)
    }
}

private fun toast(context: Context, text: String) {
    Toast.makeText(context, tr(text), Toast.LENGTH_SHORT).show()
}

private fun prop(name: String, def: String): String = try {
    val c = Class.forName("android.os.SystemProperties")
    val v = c.getMethod("get", String::class.java).invoke(null, name) as String
    if (v.isEmpty()) def else v
} catch (t: Throwable) {
    def
}

private fun appLabel(context: Context, pkg: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (t: Throwable) {
    pkg
}

private fun deviceName(context: Context): String {
    try {
        val n = android.provider.Settings.Global.getString(context.contentResolver, "device_name")
        if (!n.isNullOrEmpty()) return n
    } catch (t: Throwable) {
    }
    return prop("ro.product.marketname", Build.MANUFACTURER + " " + Build.MODEL)
}

private fun drawableBitmap(d: android.graphics.drawable.Drawable): ImageBitmap {
    val w = d.intrinsicWidth.coerceIn(1, 192)
    val h = d.intrinsicHeight.coerceIn(1, 192)
    val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    d.setBounds(0, 0, w, h)
    d.draw(c)
    return b.asImageBitmap()
}

/** Anel grande do estado: ✓ quando ativo, ! quando não. */
@Composable
private fun StatusRing(ok: Boolean, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val st = Stroke(width = 2.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(color, radius = 10f * u, center = Offset(12f * u, 12f * u), style = st)
        if (ok) {
            val path = Path().apply {
                moveTo(7.5f * u, 12.2f * u)
                lineTo(10.8f * u, 15.4f * u)
                lineTo(16.8f * u, 8.8f * u)
            }
            drawPath(path, color, style = st)
        } else {
            drawLine(color, Offset(12f * u, 6.5f * u), Offset(12f * u, 13f * u), strokeWidth = 2.4f * u, cap = StrokeCap.Round)
            drawCircle(color, radius = 1.4f * u, center = Offset(12f * u, 17f * u))
        }
    }
}

/** Foto do perfil animada (o webp animado), recortada em círculo. */
@Composable
private fun AnimatedAvatar(modifier: Modifier) {
    AndroidView(
        modifier = modifier.clip(CircleShape),
        factory = { ctx ->
            android.widget.ImageView(ctx).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: android.view.View, o: android.graphics.Outline) {
                        o.setOval(0, 0, v.width, v.height)
                    }
                }
                try {
                    val d = android.graphics.ImageDecoder.decodeDrawable(
                        android.graphics.ImageDecoder.createSource(ctx.assets, "eternity_avatar.webp"),
                    )
                    setImageDrawable(d)
                    if (d is android.graphics.drawable.AnimatedImageDrawable) {
                        d.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
                        d.start()
                    }
                } catch (t: Throwable) {
                }
            }
        },
    )
}

// ------------------------------------------------------------------ Início

private fun LazyListScope.homeTab(store: Store, backdrop: LayerBackdrop?, context: Context, onFeatures: () -> Unit) {
    item(key = "status") {
        val ok = store.connected.value
        val dark = isInDarkTheme()
        val bg = if (ok) {
            if (dark) Color(0xFF1B3324) else Color(0xFFE2F4E7)
        } else {
            if (dark) Color(0xFF3A1F1F) else Color(0xFFFBE6E4)
        }
        val ic = if (ok) {
            if (dark) Color(0xFF3FBF6A) else Color(0xFF2EA65A)
        } else {
            if (dark) Color(0xFFE0434F) else Color(0xFFD8323F)
        }
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp, bottom = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(bg),
        ) {
            Column(Modifier.padding(start = 18.dp, top = 20.dp, end = 78.dp, bottom = 40.dp)) {
                Text(
                    text = tr(if (ok) "Módulo ativo" else "Módulo não ativo"),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = tr(if (ok) "Versão ${store.moduleVersion.value} · os ajustes valem na hora" else "Ative no LSPosed e reinicie a Interface do Sistema"),
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            StatusRing(
                ok = ok,
                color = ic,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 22.dp, y = 26.dp)
                    .size(120.dp),
            )
        }
    }
    section("dev", null, backdrop) {
        BasicComponent(title = tr("Nome do aparelho"), summary = deviceName(context))
        BasicComponent(title = tr("Modelo"), summary = Build.MODEL)
        BasicComponent(title = tr("HyperOS"), summary = prop("ro.mi.os.version.incremental", Build.VERSION.INCREMENTAL))
        BasicComponent(title = tr("Android"), summary = tr("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"))
    }
    section("on", null, backdrop) {
        val n = store.enabledCount.intValue
        ArrowPreference(
            title = tr("Recursos"),
            summary = tr(if (n == 0) "Nenhum ligado — tudo vem desligado" else if (n == 1) "1 recurso ligado" else "$n recursos ligados"),
            onClick = onFeatures,
        )
    }
}

// ------------------------------------------------------------------ Recursos

private fun LazyListScope.headsUpPage(store: Store, backdrop: LayerBackdrop?, context: Context) {
    section("hu", "Heads-up", backdrop) {
        Toggle(store.huEnabled, "Efeito ativado", "Anima a notificação no estilo escolhido.")
        AnimatedVisibility(visible = store.huEnabled.value) {
            Column {
                val ios = store.huStyle.value == 1
                TabRow(
                    tabs = listOf("OriginOS", "iOS"),
                    selectedTabIndex = store.huStyle.value,
                    onTabSelected = { store.huStyle.value = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = TabRowDefaults.tabRowColors(
                        backgroundColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        selectedBackgroundColor = MiuixTheme.colorScheme.surface,
                    ),
                )
                CardText(if (ios) "Nasce do ícone na barra de status e volta para ele." else "Nasce da ilha com a mola do OriginOS.")
                val speed = if (ios) store.huIosSpeed else store.huSpeed
                val bounce = if (ios) store.huIosBounce else store.huBounce
                IntSlider(speed, "Velocidade", "Mais alto é mais rápido.", 50, 200, "%")
                IntSlider(bounce, "Bounce", "Quanto a animação passa do ponto.", 0, 200, "%")
                ArrowPreference(
                    title = tr("Testar heads-up"),
                    summary = tr("Teste ${if (ios) "iOS" else "OriginOS"}: velocidade ${speed.live}% · bounce ${bounce.live}%"),
                    onClick = {
                        val r = Bridge.call(context, "test")
                        toast(
                            context,
                            if (r?.getBoolean("ok") == true) "O teste aparece em 1,5 s" else "Ligue as notificações do Hyper Eternity para testar",
                        )
                    },
                )
                ArrowPreference(
                    title = tr("Permitir flutuante"),
                    summary = tr("Precisa de \"Notificações flutuantes\" ligado para o Hyper Eternity."),
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (t: Throwable) {
                            toast(context, "Não consegui abrir os ajustes de notificação")
                        }
                    },
                )
            }
        }
    }
    section("hc", "Conteúdo da heads-up", backdrop) {
        if (store.huStyle.value == 1) {
            Toggle(store.barOn, "Barrinha de notificações", "Uma barrinha abaixo do relógio avisa que há notificações.")
            if (store.barOn.value) {
                IntSlider(store.barDx, "Barrinha (iOS): para o lado", "Posição lateral.", -80, 80, " dp", step = 1)
                IntSlider(store.barDy, "Barrinha (iOS): altura na tela", "Posição vertical.", -30, 60, " dp", step = 1)
                IntSlider(store.barW, "Barrinha (iOS): largura", "Comprimento da barrinha.", 10, 90, " dp", step = 1)
                IntSlider(store.barH, "Barrinha (iOS): espessura", "Altura da barrinha.", 2, 14, " dp", step = 1)
                IntSlider(store.barA, "Barrinha (iOS): opacidade", "Quanto a barrinha aparece.", 10, 100, "%", step = 1)
            }
        }
        Toggle(store.huNoActions, "Sem botões", "Recolhida, mostra só título e texto.")
        Toggle(store.huSeparate, "Uma por notificação", "Mostra só a notificação nova, sem as anteriores.")
    }
}

private fun LazyListScope.notificationsPage(store: Store, backdrop: LayerBackdrop?) {
    section("tx", "Texto", backdrop) {
        Toggle(store.modalFull, "Mensagem inteira ao segurar", "Segure a notificação para ler a mensagem completa.")
        Toggle(store.lines4, "Mais linhas de texto", "Os cartões da central mostram mais linhas.")
        if (store.lines4.value) IntSlider(store.linesN, "Linhas de texto", "Quantas linhas os cartões da central mostram.", 2, 10, "", step = 1)
    }
    section("cc", "Notificações de conversa", backdrop) {
        Toggle(store.convTime, "Hora das conversas no canto", "A hora vai para o canto direito.")
        Toggle(store.convNoExpand, "Conversas sem a seta", "Esconde o botão de expandir; puxe para abrir.")
        Toggle(store.convMiui, "Conversas no visual do HyperOS (teste)", "Conversas no mesmo visual dos outros cartões.")
        Toggle(store.convSplit, "Cada mensagem num cartão novo (teste)", "Cada mensagem vira um cartão (precisa do Framework do sistema no escopo).")
        Toggle(store.convSender, "Grupos só com o nome de quem mandou (teste)", "O título mostra só quem mandou (precisa do Framework do sistema no escopo).")
        CardText("Valem para as notificações novas ou atualizadas.")
    }
    section("vi", "Visual dos cartões", backdrop) {
        Toggle(store.bodyVib, "Texto das notificações com vibrance", "O texto ganha o efeito vibrancy.")
        if (store.bodyVib.value) IntSlider(store.bodyVibN, "Intensidade do texto", "Força do efeito.", 0, 100, "%")
        Toggle(store.notifVib, "Hora das notificações como no iOS (teste)", "A hora ganha o efeito vibrancy.")
    }
}

private fun LazyListScope.stacksPage(store: Store, backdrop: LayerBackdrop?) {
    section("st", "Empilhamento na gaveta", backdrop) {
        Toggle(store.stEnabled, "Empilhar notificações", "As que passam do limite encolhem embaixo, como no HyperOS 4.")
        IntSlider(store.stOffset, "Limite acima do fim da tela", "Maior começa a pilha mais alto.", 0, 400, " dp", step = 2)
        Toggle(store.stGroups, "Grupos expandidos por cartão", "Cada notificação de um grupo aberto empilha separada.")
        Toggle(store.landSplit, "Player ao lado em paisagem", "Em paisagem, o player fica à esquerda e as notificações à direita.")
        Toggle(store.groupStack, "Grupos em pilha (teste)", "Notificações do mesmo app viram uma pilha.")
        if (store.groupStack.value) {
            IntSlider(store.gsSpeed, "Pilha: velocidade", "Mais alto é mais rápido.", 50, 200, "%")
            IntSlider(store.gsBounce, "Pilha: bounce", "Quanto a pilha pula.", 0, 300, "%")
            IntSlider(store.gsDepth, "Pilha: profundidade", "Quanto os cartões de trás aparecem.", 4, 30, " dp", step = 1)
            IntSlider(store.gsShadow, "Pilha: sombra entre os cartões", "Sombra entre os cartões.", 0, 100, "%")
        }
    }
}

private fun LazyListScope.lockPage(store: Store, backdrop: LayerBackdrop?, askCalendar: () -> Unit) {
    section("kg", "Tela de bloqueio", backdrop) {
        Toggle(store.kgHmc, "Bloqueio com o HyperMusicCover", "O bloqueio usa o relógio, a capa e o player do HyperMusicCover.")
        Toggle(store.kgKeep, "Manter notificações após desbloquear", "As notificações vistas continuam no bloqueio até serem apagadas.")
        Toggle(store.kgEnabled, "Empilhar no bloqueio", "Mostra todas as notificações e empilha as que passam do limite.")
        Toggle(store.kgScroll, "Rolar sem desbloquear", "Deslizar sobre as notificações rola a lista; fora delas, desbloqueia.")
        Toggle(store.kgClockFixed, "Relógio na posição inicial", "Com notificações, o relógio fica grande e no mesmo lugar.")
        Toggle(store.kgClock, "Relógio acompanha a rolagem", "Ao rolar, as notificações empurram o relógio para cima.")
        Toggle(store.kgBlur, "Desfoque no topo", "As notificações se desfocam ao passar sob a barra de status.")
        Toggle(store.kgIos, "Lista baixa (estilo iOS)", "A lista começa mais embaixo, perto do polegar.")
        if (store.kgIos.value) IntSlider(store.kgStart, "Início da lista", "Onde o primeiro cartão começa com muitas notificações.", 25, 90, "%", step = 1)
        IntSlider(store.kgOffset, "Limite acima do fim da tela", "Deixe espaço para o cadeado e os atalhos de baixo.", 40, 500, " dp")
        Toggle(store.kgHide, "Puxar para empilhar (teste)", "Role além do topo para empilhar as notificações.")
        CardText("Vale na próxima vez que a tela bloquear.")
    }
    section("lw", "Widgets do bloqueio", backdrop) {
        Toggle(store.lockWidgets, "Widgets no bloqueio", "Uma fileira de widgets no estilo iOS acima da lanterna e da câmera.")
        if (store.lockWidgets.value) {
            Toggle(store.lwAgenda, "Agenda e alarme", "Próximo evento da agenda e próximo alarme.")
            Toggle(store.lwBattery, "Bateria", "Nível da bateria, verde carregando.")
            Toggle(store.lwWeather, "Clima", "Temperatura do app de clima da Xiaomi.")
            Toggle(store.lwSteps, "Passos", "Passos do dia (app Mi Fitness).")
            ArrowPreference(title = tr("Permitir acesso à agenda"), onClick = askCalendar)
        }
    }
}

private fun LazyListScope.curtainPage(store: Store, backdrop: LayerBackdrop?) {
    section("ios", "Cortina", backdrop) {
        Toggle(store.shadeSheet, "Central em cortina (teste)", "A central desce do topo como uma cortina.")
        Toggle(store.islandHide, "Esconder a ilha ao puxar a central (teste)", "Esconde a ilha de música enquanto a central está aberta.")
        Toggle(store.sheetPlayerFree, "Player rola com a lista", "O player rola junto com as notificações.")
        Toggle(store.sheetRound, "Cantos arredondados na cortina", "Arredonda a borda de baixo da cortina.")
        Toggle(store.sheetBlur, "Fundo desfocado", "Desfoca a tela de trás; desligado usa o papel de parede.")
    }
    section("ck", "Relógio da cortina", backdrop) {
        Toggle(store.sheetLockClock, "Relógio do bloqueio na cortina (teste)", "Usa o mesmo relógio da tela de bloqueio.")
        if (!store.sheetLockClock.value) IntSlider(store.sheetClock, "Posição do relógio", "Quanto o relógio e a lista descem.", 0, 240, " dp")
    }
}

private fun LazyListScope.statusBarPage(store: Store, backdrop: LayerBackdrop?) {
    section("pl", "Pílulas do relógio", backdrop) {
        Toggle(store.recPill, "Relógio vermelho ao gravar a tela", "Pílula vermelha no relógio durante a gravação.")
        Toggle(store.locPill, "Relógio azul com a localização em uso", "Pílula azul no relógio com a localização em uso.")
        Toggle(store.hotPill, "Relógio verde com o roteador ligado", "Pílula verde no relógio com o roteador ligado.")
        Toggle(store.pillVib, "Vibrance na pílula do relógio", "A hora vira um recorte na pílula.")
    }
    section("co", "Cores das pílulas", backdrop) {
        ColorChoice(store.recColor, "Cor da gravação")
        ColorChoice(store.locColor, "Cor da localização")
        ColorChoice(store.hotColor, "Cor do roteador")
    }
    section("ig", "Instagram", backdrop) {
        Toggle(store.igGlass, "Barra de abas de vidro no Instagram (teste)", "A barra de baixo vira uma pílula de vidro (marque o Instagram no escopo).")
        if (store.igGlass.value) {
            OverlayDropdownPreference(
                title = tr("Estilo do vidro"),
                items = listOf(tr("Líquido"), tr("Fosco")),
                selectedIndex = store.igGlassStyle.value,
                onSelectedIndexChange = { store.igGlassStyle.value = it },
            )
        }
    }
    section("kb", "Teclado", backdrop) {
        Toggle(store.kbBlur, "Gboard desfocado (teste)", "O fundo do Gboard fica de vidro (marque o Gboard no escopo).")
        if (store.kbBlur.value) {
            IntSlider(store.kbBlurR, "Intensidade do desfoque", "Quanto o fundo fica borrado.", 0, 150, "")
            IntSlider(store.kbBlurA, "Cor do fundo", "Quanto da cor do teclado fica por cima.", 0, 100, "%")
            IntSlider(store.kbKeysA, "Vidro das teclas", "Mais baixo deixa as teclas mais transparentes.", 10, 100, "%")
            val kbCtx = LocalContext.current
            ArrowPreference(
                title = tr("Reiniciar o Gboard"),
                summary = tr("Aplica as cores novas das teclas."),
                onClick = { Bridge.call(kbCtx, "kbrestart"); toast(kbCtx, "O Gboard vai abrir de novo com os ajustes novos.") },
            )
        }
    }
}

/** As 8 fontes do Neon (assets do módulo) e os nomes delas. */
private val NeonFiles = listOf("lyric_neon.ttf", "ln_tilt.ttf", "ln_sacra.ttf", "ln_dafoe.ttf", "ln_monoton.ttf", "lyric_neon_b.ttf", "ln_hair.ttf", "ln_audio.ttf")
private val NeonNames = listOf("Pirata One", "Tilt Neon", "Sacramento", "Mr Dafoe", "Monoton", "Anton", "Bungee Hairline", "Audiowide")

private fun LazyListScope.musicPage(store: Store, backdrop: LayerBackdrop?) {
    section("pg", "Player", backdrop) {
        Toggle(store.playerOrigin, "Player estilo OriginOS", "Capa grande à esquerda e controles embaixo, como no OriginOS.")
        Toggle(store.playerIos, "Player da central no estilo ColorOS (teste)", "Capa de ponta a ponta que some num degradê.")
        Toggle(store.mediaPure, "Fundo igual às notificações", "O player usa o mesmo vidro das notificações.")
        Toggle(store.explicitE, "Selo E de conteúdo explícito", "Selo \"E\" antes do título das músicas explícitas.")
        Toggle(store.mediaEdge, "Título com as pontas desfocadas", "Nomes longos rolam com as pontas desfocadas.")
    }
    section("cv", "Capa na troca de música", backdrop) {
        Toggle(store.albumGlow, "Capa gira e brilha na troca", "Ao trocar de música, a capa gira e solta um brilho.")
        if (store.albumGlow.value) {
            Toggle(store.glowSweep, "Brilho em onda de luz", "O brilho se espalha como uma onda; desligado, é uma mancha.")
            IntSlider(store.glowPower, "Brilho: intensidade", "Quão forte o brilho aparece.", 10, 300, "%")
            IntSlider(store.glowSize, "Brilho: tamanho", "Até onde ele se espalha em volta da capa.", 40, 200, "%")
            IntSlider(store.glowDist, "Brilho: distância", "Quanto ele desce pelo cartão enquanto some.", 0, 250, "%")
            IntSlider(store.glowX, "Brilho: posição lateral", "Negativo vai para a esquerda.", -150, 150, "%")
            IntSlider(store.glowY, "Brilho: posição vertical", "Negativo vai para cima.", -100, 150, "%")
            IntSlider(store.glowTime, "Brilho: duração", "Mais alto é mais lento.", 50, 250, "%")
        }
    }
    section("br", "Barra de progresso", backdrop) {
        Toggle(store.cometBar, "Cometa na barra (teste)", "A parte tocada vira um cometa que brilha na ponta.")
        if (store.cometBar.value) {
            IntSlider(store.cometThick, "Cometa: espessura da barra", "0 = igual à barra do sistema.", 0, 10, " dp", step = 1)
            IntSlider(store.cometTail, "Cometa: tamanho da cauda", "Comprimento máximo da cauda.", 20, 200, " dp")
            IntSlider(store.cometFade, "Cometa: começo apagado", "Quanto o começo fica transparente.", 0, 100, "%")
            IntSlider(store.cometGlow, "Cometa: brilho", "Luz em volta da ponta; 0 tira.", 0, 100, "%")
            IntSlider(store.cometSize, "Cometa: tamanho do brilho", "Até onde a luz da ponta se espalha.", 50, 250, "%")
            IntSlider(store.cometPulse, "Cometa: pulsar", "O brilho pulsa; 0 deixa fixo.", 0, 100, "%")
            IntSlider(store.cometSparks, "Cometa: faíscas", "Pontinhos que ficam para trás; 0 tira.", 0, 10, "", step = 1)
            Toggle(store.cometAuto, "Começo na cor da capa", "O começo da barra pega a cor da capa.")
            ColorChoice(store.cometColor, "Cor da ponta", withWhite = true)
        }
        Toggle(store.mediaCut, "Barra e tempos como no iOS (teste)", "A barra e os tempos ganham o efeito vibrancy.")
        if (store.mediaCut.value) {
            IntSlider(store.mvText, "iOS: intensidade dos números", "Força do efeito nos tempos.", 0, 100, "%")
            IntSlider(store.mvTrack, "iOS: intensidade do trilho", "A parte que falta tocar.", 0, 100, "%")
            IntSlider(store.mvPlayed, "iOS: intensidade da parte tocada", "A parte que já tocou.", 0, 100, "%")
        }
        Toggle(store.mediaVibPlus, "Vibrance no artista e nos botões (teste)", "Artista, botões e selo E ganham o efeito vibrancy.")
        if (store.mediaVibPlus.value) IntSlider(store.mvExtra, "Artista e botões: intensidade", "Força do efeito.", 0, 100, "%")
    }
    section("ly", "Letras na tela de bloqueio", backdrop) {
        IntSlider(store.lyricShift, "Adiantar a letra", "Atrasada, aumente; adiantada, diminua.", -20, 30, " ds", step = 1)
        Toggle(store.lyricDim, "Fundo escuro com as letras", "Escurece o fundo enquanto as letras estão abertas.")
        if (store.lyricDim.value) IntSlider(store.lyricDimPct, "Fundo escuro: intensidade", "100% fica quase preto.", 10, 100, "%")
        Toggle(store.lyricVideo, "Letras estilo vídeo lírico", "Uma frase por vez, centralizada, no estilo escolhido.")
        if (store.lyricVideo.value) {
            OverlayDropdownPreference(
                title = tr("Estilo das letras"),
                summary = tr("Pincel aparece como tinta; Neon acende palavra por palavra na cor da capa."),
                items = listOf(tr("Pincel"), tr("Neon")),
                selectedIndex = store.lyricStyle.value,
                onSelectedIndexChange = { store.lyricStyle.value = it },
            )
            Toggle(store.lyricRain, "Chuva no vidro (Neon)", "Gotas escorrem pelo vidro em volta do neon.")
            if (store.lyricRain.value) {
                IntSlider(store.lyricRainAmt, "Chuva: quantidade", "Quantas gotas batem no vidro.", 10, 300, "%")
                IntSlider(store.lyricRainSlide, "Chuva: gotas escorrendo", "Com que facilidade as gotas descem; 0 deixa paradas.", 0, 200, "%")
            }
            Toggle(store.lyricStandby, "Também no StandBy", "Mesmo estilo no StandBy do HyperImmersive (marque-o no escopo).")
        }
    }
    if (store.lyricVideo.value && store.lyricStyle.value == 1) neonFonts(store, backdrop)
}

/** Fontes do Neon: cada uma pode ser trocada por um arquivo .ttf/.otf do aparelho. */
private fun LazyListScope.neonFonts(store: Store, backdrop: LayerBackdrop?) {
    item(key = "fonts") {
        val context = LocalContext.current
        var slot by remember { mutableIntStateOf(-1) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && slot >= 0) importFont(context, store, slot, uri)
        }
        SmallTitle(text = tr("Fontes do Neon"))
        GlassCard(backdrop) {
            CardText("Fontes 1 a 5 são do neon colorido; 6 a 8, do contorno branco.")
            for (i in 0 until 8) {
                val cur = store.lyricFonts[i].value
                val name = if (cur.isEmpty()) NeonNames[i] else cur.substringBeforeLast('|')
                val face = remember(cur) {
                    try {
                        if (cur.isEmpty()) android.graphics.Typeface.createFromAsset(context.assets, NeonFiles[i])
                        else android.graphics.Typeface.createFromFile(com.ilha.headsup.FontProvider.file(context, i))
                    } catch (t: Throwable) {
                        null
                    }
                }
                BasicComponent(
                    endActions = {
                        TextButton(
                            text = tr("Trocar"),
                            onClick = {
                                slot = i
                                try {
                                    picker.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt", "application/octet-stream"))
                                } catch (t: Throwable) {
                                    toast(context, "Sem app de arquivos para abrir")
                                }
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                        if (cur.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                text = tr("Padrão"),
                                onClick = {
                                    com.ilha.headsup.FontProvider.file(context, i).delete()
                                    store.lyricFonts[i].value = ""
                                },
                            )
                        }
                    },
                ) {
                    Text(
                        text = tr(if (i < 5) "Neon colorido" else "Contorno branco") + " " + (i + 1),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = name,
                        fontSize = 18.sp,
                        fontFamily = face?.let { androidx.compose.ui.text.font.FontFamily(it) },
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Copia a fonte escolhida para a posição e confere se o Android consegue abrir. */
private fun importFont(context: Context, store: Store, slot: Int, uri: Uri) {
    val max = 25L * 1024 * 1024
    val ok = try {
        val tmp = java.io.File(context.cacheDir, "font_in.ttf")
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { inp ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(16384)
                while (true) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (total > max) break
                }
            }
        }
        val tf = try {
            android.graphics.Typeface.Builder(tmp).build()
        } catch (t: Throwable) {
            null
        }
        if (tf == null || total > max) {
            tmp.delete()
            false
        } else {
            val dst = com.ilha.headsup.FontProvider.file(context, slot)
            dst.delete()
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
            var name = "fonte"
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && c.getString(0) != null) name = c.getString(0)
            }
            name = name.replace("|", " ").replace(Regex("\\.(ttf|otf|TTF|OTF)$"), "")
            store.lyricFonts[slot].value = name + "|" + System.currentTimeMillis()
            true
        }
    } catch (t: Throwable) {
        false
    }
    toast(context, if (ok) "Fonte trocada" else "Esse arquivo não é uma fonte que o Android consiga abrir")
}

private fun LazyListScope.privacyPage(
    store: Store,
    backdrop: LayerBackdrop?,
    onApps: () -> Unit,
    onConvs: () -> Unit,
    onContacts: () -> Unit,
) {
    section("pv", "Privacidade", backdrop) {
        Toggle(store.privOn, "Esconder conteúdo (teste)", "Desfoca as notificações escolhidas; toque para ver.")
        Toggle(store.privUnlocked, "Esconder também desbloqueado", "Mesmo desbloqueado, só aparece tocando.")
    }
    section("pq", "O que esconder", backdrop) {
        val na = store.privApps.value.size
        val nc = store.privConvs.value.size
        val nk = store.privContacts.value.size
        ArrowPreference(title = tr("Apps"), summary = tr(if (na == 0) "Nenhum escolhido" else "$na ${if (na == 1) "app" else "apps"} com tudo escondido"), onClick = onApps)
        ArrowPreference(title = tr("Conversas"), summary = tr(if (nc == 0) "Nenhuma escolhida · só as de apps de mensagem" else "$nc ${if (nc == 1) "conversa" else "conversas"}"), onClick = onConvs)
        ArrowPreference(title = tr("Contatos"), summary = tr(if (nk == 0) "Nenhum escolhido · vale em qualquer app de mensagem" else "$nk ${if (nk == 1) "contato" else "contatos"}"), onClick = onContacts)
        CardText("Conversas aparecem depois da primeira notificação.")
    }
}

private class AppRow(val pkg: String, val label: String, val icon: ImageBitmap?)

private fun LazyListScope.privacyApps(store: Store, backdrop: LayerBackdrop?) {
    item(key = "apps") {
        val context = LocalContext.current
        val apps by produceState<List<AppRow>?>(null) {
            value = withContext(Dispatchers.IO) {
                try {
                    val pm = context.packageManager
                    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    pm.queryIntentActivities(i, 0)
                        .distinctBy { it.activityInfo.packageName }
                        .map { AppRow(it.activityInfo.packageName, it.loadLabel(pm).toString(), try { drawableBitmap(it.loadIcon(pm)) } catch (t: Throwable) { null }) }
                        .sortedBy { it.label.lowercase() }
                } catch (t: Throwable) {
                    emptyList()
                }
            }
        }
        SmallTitle(text = tr("Toque para esconder o conteúdo do app"))
        GlassCard(backdrop) {
            val list = apps
            if (list == null) {
                CardText("Carregando os apps…")
            } else {
                list.forEach { a ->
                    SwitchPreference(
                        title = tr(a.label),
                        summary = tr(a.pkg),
                        checked = store.privApps.has(a.pkg),
                        onCheckedChange = { store.privApps.set(a.pkg, it) },
                        startAction = {
                            if (a.icon != null) {
                                Image(bitmap = a.icon, contentDescription = null, modifier = Modifier.padding(end = 14.dp).size(36.dp))
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun LazyListScope.privacyConvApps(store: Store, backdrop: LayerBackdrop?, onPick: (String) -> Unit) {
    item(key = "capps") {
        val context = LocalContext.current
        val counts = LinkedHashMap<String, Int>()
        for (line in store.privSeen.value) {
            val f = line.split("|", limit = 3)
            if (f.size < 3) continue
            counts[f[0]] = (counts[f[0]] ?: 0) + 1
        }
        SmallTitle(text = tr("Escolha o app"))
        GlassCard(backdrop) {
            if (counts.isEmpty()) {
                CardText("Nenhuma conversa ainda. Elas aparecem aqui depois da primeira notificação.")
            } else {
                counts.forEach { (pkg, n) ->
                    ArrowPreference(title = appLabel(context, pkg), summary = tr("$n ${if (n == 1) "conversa" else "conversas"}"), onClick = { onPick(pkg) })
                }
            }
        }
    }
}

private fun LazyListScope.privacyConvs(store: Store, backdrop: LayerBackdrop?, pkg: String) {
    item(key = "convs") {
        val list = store.privSeen.value
            .map { it.split("|", limit = 3) }
            .filter { it.size == 3 && it[0] == pkg }
            .sortedBy { it[2].lowercase() }
        SmallTitle(text = tr("Toque para esconder a conversa"))
        GlassCard(backdrop) {
            list.forEach { f ->
                val id = f[0] + "|" + f[1]
                SwitchPreference(
                    title = f[2],
                    checked = store.privConvs.has(id),
                    onCheckedChange = { store.privConvs.set(id, it) },
                )
            }
        }
    }
}

private fun LazyListScope.privacyContacts(store: Store, backdrop: LayerBackdrop?) {
    item(key = "contacts") {
        val context = LocalContext.current
        val names by produceState<List<String>?>(null) {
            value = withContext(Dispatchers.IO) {
                val set = java.util.TreeSet<String>(String.CASE_INSENSITIVE_ORDER)
                try {
                    context.contentResolver.query(
                        android.provider.ContactsContract.Contacts.CONTENT_URI,
                        arrayOf(android.provider.ContactsContract.Contacts.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val s = c.getString(0)
                            if (!s.isNullOrBlank()) set.add(s.trim())
                        }
                    }
                } catch (t: Throwable) {
                }
                set.toList()
            }
        }
        SmallTitle(text = tr("Toque para esconder as mensagens do contato"))
        GlassCard(backdrop) {
            val list = names
            when {
                list == null -> CardText("Carregando os contatos…")
                list.isEmpty() -> CardText("Nenhum contato encontrado.")
                else -> list.forEach { nm ->
                    val k = nm.lowercase()
                    SwitchPreference(
                        title = tr(nm),
                        checked = store.privContacts.has(k),
                        onCheckedChange = { store.privContacts.set(k, it) },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Extensões

/** O HyperMusicCover-Enhanced oficial e a versão antiga adaptada (pacote próprio). */
private val MusicPkgs = listOf("com.yzc26623.HyperMusicCoverEnhanced", "$MODULE.music")

private fun LazyListScope.extensionsTab(store: Store, backdrop: LayerBackdrop?, context: Context) {
    if (!store.musicInstalled.value) {
        section("ext", "HyperMusicCover-Enhanced", backdrop) {
            CardText("Capa e letras no bloqueio. Instale o HyperMusicCover-Enhanced e ative no LSPosed.")
            ArrowPreference(
                title = tr("Baixar o HyperMusicCover-Enhanced"),
                summary = tr("github.com/Lewewe/HyperMusicCover-Enhanced"),
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Lewewe/HyperMusicCover-Enhanced/releases")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (t: Throwable) {
                    }
                },
            )
        }
        return
    }
    section("mu", "Capa e letras no bloqueio", backdrop) {
        CardText("No HyperOS 3, o Hyper Eternity corrige o desfoque da ilha e a sincronia das letras.")
        ArrowPreference(
            title = tr("Abrir ajustes da música"),
            summary = tr("A tela da música é a original, em inglês."),
            onClick = {
                val ok = MusicPkgs.any { pkg ->
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_MAIN)
                                .setClassName(pkg, "com.os4.musiccover.MainActivity")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        true
                    } catch (t: Throwable) {
                        false
                    }
                }
                if (!ok) toast(context, "Não consegui abrir os ajustes da música")
            },
        )
    }
    section("il", "Ilha no bloqueio", backdrop) {
        CardText("Ligue \"Lock screen super island\" e \"Blur background\" nos ajustes da música.")
    }
}

// ------------------------------------------------------------------ Ajustes

private fun LazyListScope.settingsTab(
    store: Store,
    backdrop: LayerBackdrop?,
    shaderOk: Boolean,
    context: Context,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onReset: () -> Unit,
) {
    section("ap", "Aparência", backdrop) {
        OverlayDropdownPreference(
            title = tr("Tema do app"),
            items = ColorModes.map { tr(it) },
            selectedIndex = store.colorMode.value,
            onSelectedIndexChange = { store.colorMode.value = it },
        )
        OverlayDropdownPreference(
            title = tr("Idioma do app"),
            items = listOf(tr("Automático"), "Português", "English", "Español"),
            selectedIndex = store.lang.value,
            onSelectedIndexChange = {
                store.lang.value = it
                I18n.setup(it)
            },
        )
        if (I18n.en) CardText("Os ajustes da música (HyperMusicCover) seguem em inglês.")
        SwitchPreference(
            title = tr("Barra de navegação flutuante"),
            summary = tr("Desligada, a barra fica presa embaixo, de ponta a ponta."),
            checked = store.floatingNav.value,
            onCheckedChange = { store.floatingNav.value = it },
        )
        SwitchPreference(
            title = tr("Fundo colorido animado"),
            summary = tr("O degradê do HyperOS 3 atrás das telas."),
            checked = store.animatedBg.value,
            onCheckedChange = { store.animatedBg.value = it },
        )
        SwitchPreference(
            title = tr("Vidro fosco"),
            summary = tr(if (shaderOk) "Os cartões desfocam o que está atrás deles." else "Este aparelho não tem o recurso de desfoque que o miuix usa."),
            checked = store.glass.value,
            enabled = shaderOk,
            onCheckedChange = { store.glass.value = it },
        )
    }
    section("lo", "Registros", backdrop) {
        CardText("Os registros ficam no LSPosed; copie o resumo para mandar junto.")
        ArrowPreference(
            title = tr("Copiar resumo dos ajustes"),
            onClick = {
                try {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ajustes", "Hyper Eternity ${store.moduleVersion.value} · ${Build.DISPLAY}\n${store.exportJson()}"))
                    toast(context, "Resumo copiado")
                } catch (t: Throwable) {
                    toast(context, "Não consegui copiar")
                }
            },
        )
    }
    section("ie", "Importar e exportar", backdrop) {
        ArrowPreference(title = tr("Exportar"), summary = tr("Salva todos os ajustes num arquivo."), onClick = onExport)
        ArrowPreference(title = tr("Importar"), summary = tr("Carrega os ajustes de um arquivo."), onClick = onImport)
    }
    section("sys", "Sistema", backdrop) {
        ArrowPreference(
            title = tr("Reiniciar Interface do Sistema"),
            summary = tr("Precisa de root."),
            onClick = { Bridge.call(context, "restart") },
        )
        ArrowPreference(title = tr("Restaurar tudo"), summary = tr("Volta todos os ajustes ao padrão."), onClick = onReset)
    }
}

// ------------------------------------------------------------------ Sobre

private fun LazyListScope.aboutTab(backdrop: LayerBackdrop?, uriHandler: UriHandler) {
    item(key = "who") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedAvatar(Modifier.size(128.dp))
            Text(
                text = tr("Fabulous Lightning McQ"),
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(text = tr("@Gmugi"), fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
    section("tg", "Telegram", backdrop) {
        ArrowPreference(
            title = tr("Gmugi 愛"),
            summary = tr("t.me/GmugiUwu"),
            startAction = {
                Image(
                    painter = painterResource(R.drawable.eternity_channel),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 14.dp)
                        .size(48.dp)
                        .clip(CircleShape),
                )
            },
            onClick = { uriHandler.openUri("https://t.me/GmugiUwu") },
        )
    }
    section("gh", "GitHub", backdrop) {
        ArrowPreference(
            title = tr("GeMugi/Hyper-Eternity"),
            summary = tr("github.com/GeMugi/Hyper-Eternity"),
            startAction = {
                Image(
                    painter = painterResource(R.drawable.ilha_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 14.dp)
                        .size(48.dp)
                        .clip(CircleShape),
                )
            },
            onClick = { uriHandler.openUri("https://github.com/GeMugi/Hyper-Eternity") },
        )
    }
    section("cr", "Créditos e licença", backdrop) {
        ArrowPreference(title = tr("HyperMusicCover"), summary = tr("Autor original: zyl6932"), onClick = { uriHandler.openUri("https://github.com/zyl6932/HyperMusicCover") })
        ArrowPreference(title = tr("HyperMusicCover-Enhanced"), summary = tr("Versão melhorada: Lewewe"), onClick = { uriHandler.openUri("https://github.com/Lewewe/HyperMusicCover-Enhanced") })
        ArrowPreference(title = tr("miuix"), summary = tr("Componentes e desfoque desta tela (Apache 2.0)"), onClick = { uriHandler.openUri("https://github.com/compose-miuix-ui/miuix") })
        CardText("O HyperMusicCover e o HyperMusicCover-Enhanced são AGPL v3.0, e o Hyper Eternity também.")
    }
}
