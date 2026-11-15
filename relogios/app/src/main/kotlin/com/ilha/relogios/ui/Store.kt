package com.ilha.relogios.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.ilha.relogios.Keys
import com.ilha.relogios.LibPatch
import com.ilha.relogios.RemoteSync
import com.ilha.relogios.SafeReceiver

/**
 * Ajustes da extensão. Os de comportamento ficam no arquivo "ajustes", que o RemoteSync copia para
 * as preferências remotas do LSPosed; a escolha dos patches fica num arquivo à parte (só do app).
 */
class Store(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(Keys.FILE, Context.MODE_PRIVATE)
    private val local: SharedPreferences = context.getSharedPreferences("patcher", Context.MODE_PRIVATE)

    inner class Opt(val key: String) {
        val state = mutableStateOf(prefs.getBoolean(key, false))

        var value: Boolean
            get() = state.value
            set(v) {
                state.value = v
                val e = prefs.edit().putBoolean(key, v)
                // a proteção contra quedas em sequência desliga a última opção ligada
                if (v) {
                    e.putString(Keys.SAFE_LAST, key).putLong(Keys.SAFE_AT, System.currentTimeMillis())
                } else if (key == prefs.getString(Keys.SAFE_LAST, "")) {
                    e.remove(Keys.SAFE_LAST).remove(Keys.SAFE_AT)
                }
                e.apply()
            }

        fun reload() {
            state.value = prefs.getBoolean(key, false)
        }
    }

    inner class IntOpt(val key: String, val def: Int = 0) {
        val state = mutableIntStateOf(prefs.getInt(key, def))

        var value: Int
            get() = state.intValue
            set(v) {
                state.intValue = v
                prefs.edit().putInt(key, v).apply()
            }

        fun reload() {
            state.intValue = prefs.getInt(key, def)
        }
    }

    val wob = Opt(Keys.WOB)
    val wobArea = IntOpt(Keys.WOB_AREA)
    val wobForce = IntOpt(Keys.WOB_FORCE, 50)
    var wobMask: String
        get() = prefs.getString(Keys.WOB_MASK, "") ?: ""
        set(v) {
            prefs.edit().putString(Keys.WOB_MASK, v).apply()
        }

    val fx = IntOpt(Keys.FX)
    val fxBlend = IntOpt(Keys.FX_BLEND)

    val editor = Opt(Keys.EDITOR)
    val lock = Opt(Keys.LOCK)
    val follow = Opt(Keys.FOLLOW)
    val curtain = Opt(Keys.CURTAIN)
    val frost = Opt(Keys.FROST)
    private val all = listOf(editor, lock, follow, curtain, frost, wob)

    val synced = mutableStateOf(false)
    val connected = mutableStateOf(false)
    val safeMsg = mutableStateOf("")

    /** Patches escolhidos para o módulo (padrão: nenhum). */
    val picked = mutableStateOf(LibPatch.ALL.filter { local.getBoolean("patch_" + it.id, false) }.map { it.id }.toSet())

    fun pick(id: String, on: Boolean) {
        local.edit().putBoolean("patch_$id", on).apply()
        picked.value = if (on) picked.value + id else picked.value - id
    }

    private val watcher = Runnable {
        synced.value = RemoteSync.synced()
        connected.value = RemoteSync.connected()
    }

    fun attach() {
        RemoteSync.watch(watcher)
        RemoteSync.attach(context)
        watcher.run()
    }

    fun detach() {
        RemoteSync.unwatch(watcher)
    }

    fun reload() {
        all.forEach { it.reload() }
        fx.reload()
        fxBlend.reload()
        wobArea.reload()
        wobForce.reload()
        val msg = prefs.getString(SafeReceiver.SAFE_MSG, "") ?: ""
        val at = prefs.getLong(SafeReceiver.SAFE_MSG_AT, 0L)
        safeMsg.value = if (msg.isNotEmpty() && System.currentTimeMillis() - at < 3L * 24 * 3600 * 1000) msg else ""
        watcher.run()
    }

    fun dismissSafe() {
        prefs.edit().remove(SafeReceiver.SAFE_MSG).remove(SafeReceiver.SAFE_MSG_AT).apply()
        safeMsg.value = ""
    }
}
