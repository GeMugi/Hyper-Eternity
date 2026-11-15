package com.ilha.headsup;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Baixa a extensão Hyper Eternity Relógios das releases do Hyper Eternity no GitHub e abre o
 * instalador do sistema. O APK vai em cada release como Hyper-Eternity-Relogios-&lt;versão&gt;.apk.
 */
final class ExtInstall {
    private ExtInstall() {}

    static final String PKG = "com.ilha.headsup.relogios";
    static final String API = "https://api.github.com/repos/GeMugi/hyper-eternity/releases?per_page=15";
    static final String PREFIX = "Hyper-Eternity-Relogios-";
    static final String ACTION = Keys.PKG + ".EXT_INSTALL";

    /** Quem mostra o andamento (a tela de ajustes). */
    interface Listener {
        void status(String text, boolean busy);
    }

    static volatile Listener listener;
    /** Versão mais nova publicada (null enquanto não se sabe). */
    static volatile String latest;
    static volatile long peeked;

    /** Procura a versão publicada sem baixar (para mostrar no cartão). */
    static void peek() {
        long now = System.currentTimeMillis();
        if (now - peeked < 60000L) return;
        peeked = now;
        new Thread(new Runnable() {
            public void run() {
                try {
                    String[] r = find();
                    if (r != null) {
                        latest = r[0];
                        main.post(new Runnable() {
                            public void run() {
                                Listener l = listener;
                                if (l != null) l.status("", false);
                            }
                        });
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "versao-relogios").start();
    }
    static volatile boolean busy;
    static volatile String last = "";
    static final Handler main = new Handler(Looper.getMainLooper());
    static boolean receiverOn;

    static void say(final String text, final boolean b) {
        busy = b;
        last = text;
        main.post(new Runnable() {
            public void run() {
                Listener l = listener;
                if (l != null) l.status(text, b);
            }
        });
    }

    static String installed(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(PKG, 0).versionName;
            return v == null ? "?" : v;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean open(Context c) {
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(PKG);
            if (i == null) return false;
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Procura, baixa e instala (ou avisa que já está na versão mais nova). */
    static void run(final Context ctx) {
        if (busy) return;
        final Context app = ctx.getApplicationContext();
        if (!app.getPackageManager().canRequestPackageInstalls()) {
            say(Tr.t("Permita instalar apps do Hyper Eternity e toque de novo"), false);
            try {
                ctx.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) {
            }
            return;
        }
        say(Tr.t("Procurando a versão mais nova…"), true);
        new Thread(new Runnable() {
            public void run() {
                try {
                    String[] rel = find();
                    if (rel == null) throw new IllegalStateException(Tr.t("nenhuma versão publicada ainda"));
                    latest = rel[0];
                    String inst = installed(app);
                    if (inst != null && !newer(rel[0], inst)) {
                        say(Tr.t("Já está na versão mais nova") + " (" + rel[0] + ")", false);
                        return;
                    }
                    File f = download(app, rel);
                    say(Tr.t("Abrindo o instalador…"), true);
                    install(app, f);
                } catch (Throwable t) {
                    say(Tr.t("Não consegui baixar") + ": " + (t.getMessage() != null ? t.getMessage() : t.toString()), false);
                }
            }
        }, "baixar-relogios").start();
    }

    /** {versão, url, tamanho} da extensão mais nova publicada. */
    static String[] find() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "Hyper-Eternity");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("GitHub " + c.getResponseCode());
            JSONArray arr = new JSONArray(readAll(c.getInputStream()));
            // a ordem da lista do GitHub não é confiável (releases do mesmo commit empatam): fica a maior versão
            String[] best = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.getJSONObject(i);
                if (r.optBoolean("draft")) continue;
                JSONArray assets = r.optJSONArray("assets");
                if (assets == null) continue;
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject a = assets.getJSONObject(j);
                    String name = a.optString("name");
                    if (isExt(name)) {
                        String base = name.substring(0, name.length() - 4);
                        String ver = base.substring(base.lastIndexOf('-') + 1);
                        if (best == null || newer(ver, best[0]))
                            best = new String[]{ver, a.optString("browser_download_url"), String.valueOf(a.optLong("size"))};
                    }
                }
            }
            return best;
        } finally {
            c.disconnect();
        }
    }

    /** a é mais nova que b? (compara número a número: 1.10 > 1.9 > 1.2) */
    static boolean newer(String a, String b) {
        String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length && x[i].length() > 0 ? Long.parseLong(x[i]) : 0;
            long q = i < y.length && y[i].length() > 0 ? Long.parseLong(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    /** APK da extensão numa release: Hyper-Eternity-Relogios-1.0.apk, Hyper-Eternity-Hos4Clocks-1.0.apk… */
    static boolean isExt(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (!n.endsWith(".apk")) return false;
        return n.contains("relogios") || n.contains("relógios") || n.contains("hos4clocks") || n.contains("hos4-clocks") || n.contains("hos4_clocks");
    }

    static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    static File download(Context c, String[] rel) throws Exception {
        File out = new File(c.getCacheDir(), "relogios.apk");
        String url = rel[1];
        HttpURLConnection h = null;
        for (int hops = 0; hops < 6; hops++) {
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(15000);
            h.setReadTimeout(30000);
            h.setRequestProperty("User-Agent", "Hyper-Eternity");
            int code = h.getResponseCode();
            if (code >= 300 && code < 400 && h.getHeaderField("Location") != null) {
                url = h.getHeaderField("Location");
                h.disconnect();
                continue;
            }
            if (code != 200) throw new IllegalStateException("download " + code);
            break;
        }
        long total = h.getContentLengthLong() > 0 ? h.getContentLengthLong() : Long.parseLong(rel[2]);
        InputStream in = h.getInputStream();
        OutputStream o = new FileOutputStream(out);
        try {
            byte[] buf = new byte[65536];
            long done = 0;
            int n, lastPct = -1;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                done += n;
                int pct = total > 0 ? (int) (done * 100 / total) : -1;
                if (pct != lastPct && pct >= 0) {
                    lastPct = pct;
                    say(Tr.t("Baixando") + " " + rel[0] + "… " + pct + "%", true);
                }
            }
        } finally {
            o.close();
            in.close();
            h.disconnect();
        }
        return out;
    }

    static void install(Context c, File apk) throws Exception {
        listen(c);
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(PKG);
        int id = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            OutputStream o = s.openWrite("base.apk", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            in.close();
            s.fsync(o);
            o.close();
            Intent i = new Intent(ACTION).setPackage(c.getPackageName());
            PendingIntent p = PendingIntent.getBroadcast(c, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(p.getIntentSender());
        } finally {
            s.close();
        }
        apk.delete();
    }

    static synchronized void listen(Context c) {
        if (receiverOn) return;
        receiverOn = true;
        c.registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context cx, Intent i) {
                int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    @SuppressWarnings("deprecation") Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        try {
                            cx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Throwable ignored) {
                        }
                    }
                } else if (st == PackageInstaller.STATUS_SUCCESS) {
                    say(Tr.t("Instalada. Ative a extensão no LSPosed (Interface do Sistema e Tela sempre ativa) e reinicie a Interface do Sistema."), false);
                } else {
                    String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    say(Tr.t("Instalação cancelada ou recusada") + (m != null && m.length() > 0 ? ": " + m : ""), false);
                }
            }
        }, new IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED);
    }
}
