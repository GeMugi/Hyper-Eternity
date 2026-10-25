package com.ilha.headsup.miuix

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Ponte com o lado Java do módulo (BridgeProvider, no mesmo app): grava os ajustes e o RemoteSync
 * leva tudo ao LSPosed, como a tela de ajustes clássica.
 */
object Bridge {
    private val URI: Uri = Uri.parse("content://com.ilha.headsup.bridge")

    fun call(context: Context, method: String, arg: String? = null, extras: Bundle? = null): Bundle? =
        try {
            context.contentResolver.call(URI, method, arg, extras)
        } catch (t: Throwable) {
            null
        }

    fun put(context: Context, key: String, value: Any?) {
        val b = Bundle()
        when (value) {
            is Boolean -> b.putBoolean("v", value)
            is Int -> b.putInt("v", value)
            is String -> b.putString("v", value)
        }
        call(context, "put", key, b)
    }
}

/** Um ajuste que pode voltar ao padrão. */
interface Resettable {
    fun reset()
    fun load(all: Bundle)
}

/** Ajuste ligado/desligado do módulo. */
class BoolPref(
    private val put: (String, Any?) -> Unit,
    val key: String,
    val def: Boolean,
) : Resettable {
    private val state = mutableStateOf(def)

    var value: Boolean
        get() = state.value
        set(v) {
            state.value = v
            put(key, v)
        }

    override fun reset() {
        state.value = def
    }

    override fun load(all: Bundle) {
        state.value = if (all.containsKey(key)) all.getBoolean(key, def) else def
    }
}

/** Ajuste numérico do módulo. Enquanto a barra é arrastada só a tela muda; ao soltar, salva. */
class IntPref(
    private val put: (String, Any?) -> Unit,
    val key: String,
    val def: Int,
) : Resettable {
    private val state = mutableIntStateOf(def)

    var live: Int
        get() = state.intValue
        set(v) {
            state.intValue = v
        }

    var value: Int
        get() = state.intValue
        set(v) {
            state.intValue = v
            save()
        }

    fun save() {
        put(key, state.intValue)
    }

    override fun reset() {
        state.intValue = def
    }

    override fun load(all: Bundle) {
        state.intValue = if (all.containsKey(key)) all.getInt(key, def) else def
    }
}

/** Lista guardada como texto, um item por linha (apps, conversas e contatos da privacidade). */
class LinesPref(
    private val put: (String, Any?) -> Unit,
    val key: String,
) : Resettable {
    private val state = mutableStateOf<Set<String>>(emptySet())

    val value: Set<String> get() = state.value

    fun has(item: String) = item in state.value

    fun set(item: String, on: Boolean) {
        val n = LinkedHashSet(state.value)
        if (on) n.add(item) else n.remove(item)
        state.value = n
        put(key, n.joinToString("\n"))
    }

    override fun reset() {
        state.value = emptySet()
    }

    override fun load(all: Bundle) {
        val t = all.getString(key) ?: ""
        state.value = t.split("\n").filter { it.isNotEmpty() }.toCollection(LinkedHashSet())
    }
}

/** Ajuste só deste app (aparência). */
class LocalBool(private val sp: SharedPreferences, private val key: String, def: Boolean) {
    private val state = mutableStateOf(sp.getBoolean(key, def))
    var value: Boolean
        get() = state.value
        set(v) {
            state.value = v
            sp.edit().putBoolean(key, v).apply()
        }
}

class LocalInt(private val sp: SharedPreferences, private val key: String, def: Int) {
    private val state = mutableIntStateOf(sp.getInt(key, def))
    var value: Int
        get() = state.intValue
        set(v) {
            state.intValue = v
            sp.edit().putInt(key, v).apply()
        }
}

/** Os ajustes do Hyper Eternity (mesmos nomes e padrões do Keys.java). */
class Store(
    private val context: Context,
    ui: SharedPreferences,
) {
    private val put: (String, Any?) -> Unit = { k, v -> Bridge.put(context, k, v) }
    private val all = ArrayList<Resettable>()

    private fun b(key: String, def: Boolean) = BoolPref(put, key, def).also { all.add(it) }
    private fun i(key: String, def: Int) = IntPref(put, key, def).also { all.add(it) }
    private fun l(key: String) = LinesPref(put, key).also { all.add(it) }

    // estado do módulo
    val connected = mutableStateOf(false)
    val reached = mutableStateOf(false)
    val moduleVersion = mutableStateOf("")
    val musicInstalled = mutableStateOf(false)
    val enabledCount = mutableIntStateOf(0)
    val otherInstalled = mutableStateOf(false)

    // Heads-up
    val huEnabled = b("hu_enabled", true)
    val huStyle = i("hu_style", 0)
    val huSpeed = i("hu_speed", 100)
    val huBounce = i("hu_bounce", 100)
    val huIosSpeed = i("hu_ios_speed", 100)
    val huIosBounce = i("hu_ios_bounce", 100)
    val huNoActions = b("hu_no_actions", true)
    val huSeparate = b("hu_separate", true)

    // Notificações
    val modalFull = b("modal_full", false)
    val lines4 = b("lines4", false)
    val linesN = i("lines_n", 4)
    val convTime = b("conv_time", false)
    val convNoExpand = b("conv_no_expand", false)
    val convMiui = b("conv_miui", false)
    val convSplit = b("conv_split", false)
    val convSender = b("conv_sender", false)
    val bodyVib = b("body_vib", false)
    val bodyVibN = i("body_vib_n", 70)
    val notifVib = b("notif_vib", false)

    // Gaveta e pilhas
    val stEnabled = b("st_enabled", true)
    val stOffset = i("st_offset", 72)
    val stGroups = b("st_groups", true)
    val groupStack = b("group_stack", false)
    val gsSpeed = i("gs_speed", 100)
    val gsBounce = i("gs_bounce", 100)
    val gsDepth = i("gs_depth", 12)
    val gsShadow = i("gs_shadow", 40)

    // Tela de bloqueio
    val kgHmc = b("kg_hmc", true)
    val kgKeep = b("kg_keep", true)
    val kgEnabled = b("kg_enabled", true)
    val kgScroll = b("kg_scroll", true)
    val kgClockFixed = b("kg_clock_fixed", true)
    val kgClock = b("kg_clock", true)
    val kgBlur = b("kg_blur", true)
    val kgIos = b("kg_ios", true)
    val kgStart = i("kg_start", 58)
    val kgOffset = i("kg_offset", 150)
    val kgHide = b("kg_hide", false)

    // Central estilo iOS
    val shadeSheet = b("shade_sheet", false)
    val sheetRound = b("sheet_round", true)
    val sheetBlur = b("sheet_blur", true)
    val sheetLockClock = b("sheet_lockclock", false)
    val sheetClock = i("sheet_clock", 60)

    // Barra de status
    val recPill = b("rec_pill", false)
    val locPill = b("loc_pill", false)
    val hotPill = b("hot_pill", false)
    val pillVib = b("pill_vib", false)
    val recColor = i("rec_color", 0xFFFF3B30.toInt())
    val locColor = i("loc_color", 0xFF0A84FF.toInt())
    val hotColor = i("hot_color", 0xFF34C759.toInt())

    // Música
    val mediaPure = b("media_pure_blur", false)
    val albumGlow = b("album_glow", false)
    val glowPower = i("glow_power", 80)
    val glowSize = i("glow_size", 100)
    val glowDist = i("glow_dist", 100)
    val glowX = i("glow_x", 0)
    val glowY = i("glow_y", 0)
    val glowTime = i("glow_time", 100)
    val mediaEdge = b("media_edge", false)
    val mediaCut = b("media_cut", false)
    val mvText = i("mv_text", 45)
    val mvTrack = i("mv_track", 22)
    val mvPlayed = i("mv_played", 85)
    val lyricShift = i("lyric_shift", 0)

    // Privacidade
    val privOn = b("priv_on", false)
    val privUnlocked = b("priv_unlocked", false)
    val privApps = l("priv_apps")
    val privConvs = l("priv_convs")
    val privContacts = l("priv_contacts")
    val privSeen = l("priv_seen")   // só leitura: o módulo anota as conversas vistas

    // Aparência deste app
    val colorMode = LocalInt(ui, "v_color", 0)
    val animatedBg = LocalBool(ui, "v_bg", true)
    val glass = LocalBool(ui, "v_glass", true)
    val floatingNav = LocalBool(ui, "v_navbar", true)

    /** Lê tudo do módulo de novo (ao abrir e ao voltar para o app). */
    fun reload() {
        val res = Bridge.call(context, "getAll")
        reached.value = res != null
        if (res == null) {
            connected.value = false
            return
        }
        connected.value = res.getBoolean("__connected", false)
        moduleVersion.value = res.getString("__version") ?: ""
        musicInstalled.value = res.getBoolean("__music", false)
        all.forEach { it.load(res) }
        enabledCount.intValue = all.count { it is BoolPref && it.value }
    }

    fun resetAll() {
        Bridge.call(context, "reset")
        all.forEach { it.reset() }
    }

    /** Mesmo formato do Hyper Eternity normal, para os ajustes irem e virem entre os dois. */
    fun exportJson(): String {
        val res = Bridge.call(context, "getAll") ?: return "{}"
        val o = org.json.JSONObject()
        for (k in res.keySet()) {
            if (k.startsWith("__") || k.startsWith("safe_")) continue
            @Suppress("DEPRECATION")
            o.put(k, res.get(k))
        }
        val root = org.json.JSONObject()
        root.put("modulo", "heads-up-ilha")
        root.put("versao", moduleVersion.value)
        root.put("ajustes", o)
        return root.toString(2)
    }

    fun importJson(text: String): Boolean = try {
        val root = org.json.JSONObject(text)
        val o = if (root.has("ajustes")) root.getJSONObject("ajustes") else root
        for (k in o.keys()) {
            when (val v = o.get(k)) {
                is Boolean, is Int, is String -> put(k, v)
                is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) put(k, v.toInt())
            }
        }
        reload()
        true
    } catch (t: Throwable) {
        false
    }
}
