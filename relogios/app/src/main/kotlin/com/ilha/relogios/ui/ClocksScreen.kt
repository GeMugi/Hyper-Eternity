package com.ilha.relogios.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilha.relogios.LibModule
import com.ilha.relogios.LibPatch
import com.ilha.relogios.ui.effect.BgEffectBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private const val AOD = "com.miui.aod"

private val FxNames = listOf("Nenhum", "Papel de parede nos números", "Duas cores do papel de parede", "Holográfico (mexe ao inclinar)", "Granulado", "Separação RGB", "Pôster (foto em duas tintas e granulado)", "Rastro (rastro colorido atrás dos números)", "Vidro líquido (reflexo e refração)")
private val BlendModes = listOf(0, 15, 21, 14, 18, 24, 22, 23, 12, 28)
private val BlendNames = listOf("A do editor", "Sobrepor", "Luz suave", "Tela", "Subexposição de cor", "Multiplicar", "Diferença", "Exclusão", "Adicionar", "Luminosidade")
private const val MAIN = "com.ilha.headsup"

/** O que a tela sabe do aparelho (lido fora da linha principal). */
private class SysInfo(
    val aodVersion: String?,
    val allInOne: Boolean,
    val mainVersion: String?,
)

/** Estado do patcher. */
private class PatchUi {
    val rows = mutableStateOf<List<Pair<LibPatch, LibPatch.Scan>>>(emptyList())
    val mod = mutableStateOf<LibModule.State?>(null)
    val inModule = mutableStateOf<Set<String>>(emptySet())
    val busy = mutableStateOf(false)
    val msg = mutableStateOf("")
}

@Composable
fun ClocksScreen(store: Store) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val barBehavior = MiuixScrollBehavior()
    val glassOn = remember { isRuntimeShaderSupported() }
    val surface = MiuixTheme.colorScheme.surface
    val bgBackdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
    val screenBackdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
    val cardBackdrop: LayerBackdrop? = if (glassOn) bgBackdrop else null
    val info = remember { mutableStateOf<SysInfo?>(null) }
    val patch = remember { PatchUi() }
    val hos4 = remember { mutableStateOf(false) }
    var askReboot by remember { mutableStateOf(false) }
    var painting by remember { mutableStateOf(false) }
    if (painting) {
        PaintArea(store) { painting = false }
        return
    }

    LaunchedEffect(Unit) {
        hos4.value = withContext(Dispatchers.IO) { com.ilha.relogios.Hos.isHos4() }
        info.value = withContext(Dispatchers.IO) { sysInfo(context) }
        patch.rows.value = withContext(Dispatchers.IO) { scanAll() }
    }

    val barBlur = BlurDefaults.blurColors(blendColors = listOf(BlendColorEntry(color = surface.copy(alpha = 0.8f))))
    val title = tr("Relógios do HyperOS 4")

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
                    title = title,
                    largeTitle = title,
                    scrollBehavior = barBehavior,
                    color = if (glassOn) Color.Transparent else surface,
                )
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .then(if (glassOn) Modifier.layerBackdrop(screenBackdrop) else Modifier),
        ) {
            BgEffectBackground(
                dynamicBackground = true,
                effectBackground = true,
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
                        bottom = innerPadding.calculateBottomPadding() + 24.dp,
                    ),
                ) {
                    statusCard(store)
                    if (store.safeMsg.value.isNotEmpty()) {
                        section("safe", "Proteção", cardBackdrop) {
                            CardText(tr("A Interface do Sistema reiniciou em sequência logo depois desta opção ser ligada, então ela foi desligada:") + " " + optName(store.safeMsg.value))
                            TextButton(
                                text = "OK",
                                onClick = { store.dismissSafe() },
                                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                            )
                        }
                    }
                    if (hos4.value) {
                        section("hos4", "HyperOS 4", cardBackdrop) {
                            CardText(tr("Este aparelho já está no HyperOS 4: os relógios, o editor, o vidro e a fonte variável são nativos. A extensão fica parada para não causar conflito; pode desinstalar."))
                        }
                    } else {
                        systemSection(info.value, cardBackdrop)
                        clocksSection(store, cardBackdrop, context, scope)
                        section("wob", "Papel de parede (teste)", cardBackdrop) {
                            Toggle(store.wob, "Balanço ao sacudir", "No bloqueio, o papel de parede balança como gelatina ao sacudir o aparelho ou acender a tela.")
                            OverlayDropdownPreference(
                                title = tr("Área do efeito"),
                                items = listOf(tr("Imagem completa"), tr("Área pintada")),
                                selectedIndex = store.wobArea.value.coerceIn(0, 1),
                                onSelectedIndexChange = { store.wobArea.value = it },
                            )
                            ArrowPreference(
                                title = tr("Pintar a área"),
                                summary = tr("Escolha com o dedo a parte da imagem que balança."),
                                onClick = { painting = true },
                            )
                            SliderPreference(
                                value = store.wobForce.value.toFloat(),
                                onValueChange = { store.wobForce.value = ((it / 5f).toInt() * 5).coerceIn(10, 100) },
                                title = tr("Força"),
                                valueText = "${store.wobForce.value}%",
                                valueRange = 10f..100f,
                            )
                        }
                        patcherSection(store, patch, cardBackdrop, context, scope, onReboot = { askReboot = true })
                    }
                    aboutSection(cardBackdrop, context)
                }
            }
        }

        OverlayDialog(
            show = askReboot,
            title = tr("Reiniciar agora?"),
            summary = tr("O módulo só vale depois de reiniciar."),
            onDismissRequest = { askReboot = false },
        ) {
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(text = tr("Cancelar"), onClick = { askReboot = false }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = tr("Reiniciar"),
                    onClick = {
                        askReboot = false
                        scope.launch(Dispatchers.IO) { LibModule.reboot() }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ seções

private fun LazyListScope.statusCard(store: Store) {
    item(key = "status") {
        val ok = store.synced.value || store.connected.value
        val dark = isInDarkTheme()
        val bg = if (ok) {
            if (dark) Color(0xFF1B3324) else Color(0xFFE2F4E7)
        } else {
            if (dark) Color(0xFF3A1F1F) else Color(0xFFFBE6E4)
        }
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp, bottom = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(bg),
        ) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.relogios_logo),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)),
                )
                Column(Modifier.padding(start = 16.dp)) {
                    Text(
                        text = tr(if (ok) "Extensão ativa" else "Extensão não ativa"),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = tr(if (ok) "Os ajustes valem na hora" else "Ative no LSPosed e reinicie a Interface do Sistema"),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private fun LazyListScope.systemSection(info: SysInfo?, backdrop: LayerBackdrop?) {
    section("sys", "Sistema", backdrop) {
        val aod = when {
            info == null -> "…"
            info.aodVersion == null -> tr("Não instalado")
            else -> info.aodVersion + " · " + tr(if (info.allInOne) "com os relógios All in One" else "sem os relógios All in One (precisa da versão do HyperOS 4)")
        }
        BasicComponent(title = tr("App da tela sempre ativa"), summary = aod)
        val main = when {
            info == null -> "…"
            info.mainVersion == null -> tr("Não instalado: o relógio fica no bloqueio, mas não acompanha a lista de notificações do Hyper Eternity nem aparece na cortina.")
            else -> tr("Instalado") + " · " + info.mainVersion
        }
        BasicComponent(title = tr("Hyper Eternity"), summary = main)
    }
}

private fun LazyListScope.clocksSection(store: Store, backdrop: LayerBackdrop?, context: Context, scope: kotlinx.coroutines.CoroutineScope) {
    section("clk", "Relógios", backdrop) {
        Toggle(store.editor, "Modelos do HyperOS 4 no editor", "O editor da tela de bloqueio passa a oferecer os relógios All in One. Feche e abra o editor depois de mudar.")
        Toggle(store.lock, "No bloqueio", "Quando o modelo escolhido é All in One, o bloqueio mostra o relógio do HyperOS 4.")
        Toggle(store.follow, "Acompanha as notificações", "Encolhe e sobe quando as notificações chegam perto, como no HyperOS 4.")
        Toggle(store.curtain, "Na cortina do Hyper Eternity", "O mesmo relógio na central estilo iOS, com o relógio do bloqueio.")
        Toggle(store.frost, "Vidro fosco", "Números com vidro (escolhido no editor) ficam foscos, misturados com o fundo. Desligado: cor sólida.")
    }
    section("fx", "Efeitos dos números (teste)", backdrop) {
        CardText(tr("Valem por cima do material escolhido no editor. A mistura usa o fundo desfocado de verdade; os outros pintam os números."))
        OverlayDropdownPreference(
            title = tr("Efeito"),
            items = FxNames.map { tr(it) },
            selectedIndex = store.fx.value.coerceIn(0, FxNames.size - 1),
            onSelectedIndexChange = { store.fx.value = it },
        )
        val bi = BlendModes.indexOf(store.fxBlend.value).coerceAtLeast(0)
        OverlayDropdownPreference(
            title = tr("Mistura com o fundo"),
            items = BlendNames.map { tr(it) },
            selectedIndex = bi,
            onSelectedIndexChange = { store.fxBlend.value = BlendModes[it] },
        )
    }
    section("clk2", null, backdrop) {
        ArrowPreference(
            title = tr("Abrir o editor do bloqueio"),
            onClick = { if (!openEditor(context)) toast(context, "Não consegui abrir o editor") },
        )
        ArrowPreference(
            title = tr("Reiniciar a Interface do Sistema"),
            summary = tr("Precisa de root"),
            onClick = {
                scope.launch(Dispatchers.IO) {
                    val r = LibModule.su("killall com.android.systemui 2>/dev/null || pkill -f com.android.systemui")
                    if (!r.ok()) withContext(Dispatchers.Main) { toast(context, "Sem root") }
                }
            },
        )
    }
}

private fun LazyListScope.patcherSection(
    store: Store,
    ui: PatchUi,
    backdrop: LayerBackdrop?,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onReboot: () -> Unit,
) {
    section("libs", "Bibliotecas do sistema", backdrop) {
        CardText(tr("Algumas funções do HyperOS 4 existem no HyperOS 3, mas vêm desligadas dentro das bibliotecas do sistema. O patcher lê as bibliotecas deste aparelho, confere se a função é exatamente a esperada e gera um módulo (Magisk, KernelSU ou APatch) com a cópia corrigida. Se o sistema for atualizado, o módulo se desliga sozinho."))
    }
    section("libs2", null, backdrop) {
        val rows = ui.rows.value
        if (rows.isEmpty()) BasicComponent(title = "…")
        rows.forEach { (p, s) ->
            val st = when (s.state) {
                LibPatch.Scan.ON -> tr("Ligada no sistema") + if (p.id in ui.inModule.value) " · " + tr("no módulo") else ""
                LibPatch.Scan.OFF -> tr("Desligada no sistema")
                LibPatch.Scan.MISSING -> tr("Biblioteca não encontrada")
                else -> tr("Não compatível") + ": " + s.detail
            }
            SwitchPreference(
                title = tr(p.title),
                summary = tr(p.summary) + "\n" + st,
                checked = p.id in store.picked.value,
                onCheckedChange = { store.pick(p.id, it) },
            )
        }
    }
    section("libs3", "Módulo", backdrop) {
        val m = ui.mod.value
        val state = when {
            ui.busy.value -> tr("Gerando…")
            m == null -> "—"
            !m.root -> tr("Sem root: o patcher precisa de root para instalar o módulo.")
            m.removing -> tr("Sai ao reiniciar")
            m.pending -> tr("Instalado, vale ao reiniciar")
            m.disabled -> tr("Desligado") + if (m.status.isNotEmpty()) " · " + m.status else ""
            m.installed -> tr("Instalado e ativo") + if (m.manager.isNotEmpty()) " · " + m.manager else ""
            else -> tr("Não instalado") + if (m.manager.isNotEmpty()) " · " + m.manager else ""
        }
        BasicComponent(title = tr("Módulo"), summary = if (ui.msg.value.isNotEmpty()) state + "\n" + ui.msg.value else state)
        ArrowPreference(
            title = tr("Conferir com root"),
            onClick = { if (!ui.busy.value) scope.launch { refresh(ui) } },
        )
        ArrowPreference(
            title = tr("Gerar e instalar o módulo"),
            onClick = {
                if (ui.busy.value) return@ArrowPreference
                val wanted = LibPatch.ALL.filter { it.id in store.picked.value }
                if (wanted.isEmpty()) {
                    toast(context, "Escolha ao menos um patch compatível")
                    return@ArrowPreference
                }
                scope.launch {
                    ui.busy.value = true
                    ui.msg.value = ""
                    val done = withContext(Dispatchers.IO) { build(context, wanted) }
                    ui.msg.value = done.second
                    ui.busy.value = false
                    refresh(ui)
                    if (done.first) onReboot()
                }
            },
        )
        ArrowPreference(
            title = tr("Remover o módulo"),
            onClick = {
                if (ui.busy.value) return@ArrowPreference
                scope.launch {
                    val r = withContext(Dispatchers.IO) { LibModule.remove() }
                    ui.msg.value = if (r.ok()) tr("Marcado para sair. Reinicie o aparelho.") else tr("Falhou") + ": " + r.out.takeLast(200)
                    refresh(ui)
                }
            },
        )
        ArrowPreference(title = tr("Reiniciar o aparelho"), onClick = onReboot)
    }
}

private fun LazyListScope.aboutSection(backdrop: LayerBackdrop?, context: Context) {
    section("about", "Sobre", backdrop) {
        CardText(tr("Extensão do Hyper Eternity para HyperOS 3. O código dos relógios é o do próprio app da tela sempre ativa instalado no aparelho; nada da Xiaomi vem dentro deste app."))
        BasicComponent(title = tr("Versão"), summary = appVersion(context))
        ArrowPreference(
            title = tr("Código-fonte e licença (AGPL v3.0)"),
            summary = "github.com/GeMugi/hyper-eternity",
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/GeMugi/hyper-eternity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (t: Throwable) {
                }
            },
        )
    }
}

// ------------------------------------------------------------------ peças

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

@Composable
private fun CardText(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun Toggle(opt: Store.Opt, title: String, summary: String?) {
    SwitchPreference(
        title = tr(title),
        summary = tr(summary),
        checked = opt.state.value,
        onCheckedChange = { opt.value = it },
    )
}

private fun toast(context: Context, text: String) {
    Toast.makeText(context, tr(text), Toast.LENGTH_SHORT).show()
}

private fun optName(key: String): String = tr(
    when (key) {
        com.ilha.relogios.Keys.EDITOR -> "Modelos do HyperOS 4 no editor"
        com.ilha.relogios.Keys.LOCK -> "No bloqueio"
        com.ilha.relogios.Keys.FOLLOW -> "Acompanha as notificações"
        com.ilha.relogios.Keys.CURTAIN -> "Na cortina do Hyper Eternity"
        com.ilha.relogios.Keys.FROST -> "Vidro fosco"
        else -> key
    },
)

private fun appVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
} catch (t: Throwable) {
    "?"
}

private fun sysInfo(context: Context): SysInfo {
    val pm = context.packageManager
    val aodVer = try { pm.getPackageInfo(AOD, 0).versionName ?: "?" } catch (t: Throwable) { null }
    val allInOne = aodVer != null && try {
        val c = context.createPackageContext(AOD, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        c.classLoader.loadClass("com.miui.clock.allInOne.AllInOneBase")
        true
    } catch (t: Throwable) {
        false
    }
    val mainVer = try { pm.getPackageInfo(MAIN, 0).versionName ?: "?" } catch (t: Throwable) { null }
    return SysInfo(aodVer, allInOne, mainVer)
}

private fun openEditor(context: Context): Boolean {
    for (action in listOf("miui.keyguard.editor.settings", "miui.keyguard.editor")) {
        try {
            context.startActivity(Intent(action).setPackage(AOD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (t: Throwable) {
        }
    }
    return false
}

private fun scanAll(): List<Pair<LibPatch, LibPatch.Scan>> =
    LibPatch.ALL.map { it to it.scan(LibModule.read(it.path)) }

private suspend fun refresh(ui: PatchUi) {
    ui.busy.value = true
    val st = withContext(Dispatchers.IO) { LibModule.state() }
    val inMod = if (st.root && (st.installed || st.pending)) withContext(Dispatchers.IO) { LibModule.inModule() } else emptySet()
    ui.rows.value = withContext(Dispatchers.IO) { scanAll() }
    ui.mod.value = st
    ui.inModule.value = inMod
    ui.busy.value = false
}

/** Gera e instala o módulo. Devolve (deu certo, mensagem). */
private fun build(context: Context, wanted: List<LibPatch>): Pair<Boolean, String> {
    val st = LibModule.state()
    if (!st.root) return false to tr("Sem root: o patcher precisa de root para instalar o módulo.")
    val why = StringBuilder()
    val items = LibModule.prepare(wanted, why, true)
    if (items.isEmpty()) return false to why.toString().trim()
    return try {
        val zip = LibModule.writeZip(context.cacheDir, items, Build.FINGERPRINT, (System.currentTimeMillis() / 60000L).toInt())
        val r = LibModule.install(zip)
        zip.delete()
        if (r.ok()) {
            true to (tr("Instalado. Reinicie o aparelho para valer.") + if (why.isNotEmpty()) "\n" + why.toString().trim() else "")
        } else {
            false to tr("Falhou") + ": " + r.out.takeLast(300)
        }
    } catch (t: Throwable) {
        false to tr("Falhou") + ": " + t
    }
}
