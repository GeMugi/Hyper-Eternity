package com.ilha.headsup.miuix

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Baixa a extensão Hyper Eternity Relógios do GitHub (releases do Hyper Eternity) e abre o
 * instalador do sistema. O APK da extensão vai em cada release com o nome
 * Hyper-Eternity-Relogios-<versão>.apk.
 */
object ExtInstall {
    const val PKG = "com.ilha.headsup.relogios"
    private const val API = "https://api.github.com/repos/GeMugi/hyper-eternity/releases?per_page=15"
    private const val PREFIX = "Hyper-Eternity-Relogios-"
    private const val ACTION = "com.ilha.headsup.EXT_INSTALL"

    /** Versão instalada (null = não instalada). */
    val installed = mutableStateOf<String?>(null)
    /** Última versão publicada, quando já procurada. */
    val latest = mutableStateOf<String?>(null)
    val busy = mutableStateOf(false)
    val progress = mutableFloatStateOf(-1f)
    val status = mutableStateOf("")

    private val main = Handler(Looper.getMainLooper())
    private var receiverOn = false

    private fun ui(r: () -> Unit) {
        main.post(r)
    }

    fun refresh(context: Context) {
        installed.value = try {
            context.packageManager.getPackageInfo(PKG, 0).versionName ?: "?"
        } catch (t: Throwable) {
            null
        }
    }

    fun open(context: Context): Boolean = try {
        val i = context.packageManager.getLaunchIntentForPackage(PKG) ?: throw IllegalStateException()
        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (t: Throwable) {
        false
    }

    private class Release(val version: String, val url: String, val size: Long)

    /** Procura a versão mais nova da extensão nas releases. */
    private fun find(): Release? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Hyper-Eternity")
        try {
            if (c.responseCode != 200) throw IllegalStateException("GitHub respondeu ${c.responseCode}")
            val arr = JSONArray(c.inputStream.bufferedReader().readText())
            // a ordem do GitHub não é confiável: fica a maior versão publicada
            var best: Release? = null
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                if (r.optBoolean("draft")) continue
                val assets = r.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    val name = a.optString("name")
                    val n = name.lowercase()
                    if (n.endsWith(".apk") && (n.contains("relogios") || n.contains("relógios") || n.contains("hos4clocks") || n.contains("hos4-clocks") || n.contains("hos4_clocks"))) {
                        val rel = Release(name.removeSuffix(".apk").substringAfterLast('-'), a.optString("browser_download_url"), a.optLong("size"))
                        if (best == null || newer(rel.version, best.version)) best = rel
                    }
                }
            }
            return best
        } finally {
            c.disconnect()
        }
    }

    /** a é mais nova que b? (número a número: 1.10 > 1.9 > 1.2) */
    fun newer(a: String, b: String): Boolean {
        val x = a.split(Regex("[^0-9]+")).map { it.toLongOrNull() ?: 0L }
        val y = b.split(Regex("[^0-9]+")).map { it.toLongOrNull() ?: 0L }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0L }
            val q = y.getOrElse(i) { 0L }
            if (p != q) return p > q
        }
        return false
    }

    /** Procura, baixa e instala (ou só avisa que já está na versão mais nova). */
    fun run(context: Context) {
        if (busy.value) return
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            status.value = tr("Permita instalar apps do Hyper Eternity e toque de novo")
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (t: Throwable) {
            }
            return
        }
        busy.value = true
        progress.floatValue = -1f
        status.value = tr("Procurando a versão mais nova…")
        Thread {
            try {
                val rel = find() ?: throw IllegalStateException(tr("nenhuma versão publicada ainda"))
                ui { latest.value = rel.version }
                val inst = installed.value
                if (inst != null && !newer(rel.version, inst)) {
                    ui {
                        status.value = tr("Já está na versão mais nova") + " (" + rel.version + ")"
                        busy.value = false
                    }
                    return@Thread
                }
                ui { status.value = tr("Baixando") + " " + rel.version + "…" }
                val f = download(app, rel)
                ui {
                    progress.floatValue = -1f
                    status.value = tr("Abrindo o instalador…")
                }
                install(app, f)
            } catch (t: Throwable) {
                ui {
                    status.value = tr("Não consegui baixar") + ": " + (t.message ?: t.toString())
                    busy.value = false
                    progress.floatValue = -1f
                }
            }
        }.start()
    }

    private fun download(context: Context, rel: Release): File {
        val out = File(context.cacheDir, "relogios.apk")
        var url = rel.url
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "Hyper-Eternity")
            val code = c.responseCode
            if (code in 300..399 && hops++ < 5) {
                url = c.getHeaderField("Location") ?: break
                c.disconnect()
                continue
            }
            if (code != 200) throw IllegalStateException("download $code")
            break
        }
        try {
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else rel.size
            c.inputStream.use { inp ->
                out.outputStream().use { o ->
                    val buf = ByteArray(65536)
                    var done = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = done.toFloat() / total
                            ui { progress.floatValue = p }
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return out
    }

    private fun install(context: Context, apk: File) {
        listen(context)
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(PKG)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { o ->
                apk.inputStream().use { it.copyTo(o) }
                s.fsync(o)
            }
            val intent = Intent(ACTION).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(pending.intentSender)
        }
        apk.delete()
    }

    private fun listen(context: Context) {
        if (receiverOn) return
        receiverOn = true
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        if (confirm != null) {
                            try {
                                c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (t: Throwable) {
                            }
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        refresh(c)
                        status.value = tr("Instalada. Ative a extensão no LSPosed (Interface do Sistema e Tela sempre ativa) e reinicie a Interface do Sistema.")
                        busy.value = false
                    }
                    else -> {
                        val m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                        status.value = tr("Instalação cancelada ou recusada") + if (m.isNotEmpty()) ": $m" else ""
                        busy.value = false
                    }
                }
            }
        }, IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED)
    }
}
