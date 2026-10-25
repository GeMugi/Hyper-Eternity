package com.ilha.headsup;

import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Properties;

/**
 * Modo de segurança. A tela de ajustes anota qual opção foi ligada por último (safe_last e a
 * hora, safe_at). Se a Interface do Sistema reiniciar mais de uma vez em poucos minutos logo
 * depois disso (3 partidas em 3 minutos), essa opção é desligada aqui mesmo, antes de qualquer
 * gancho agir, e o app é avisado para desligar a chave nos ajustes também.
 *
 * O registro fica num arquivo da própria Interface do Sistema, lido na partida sem esperar
 * pelas preferências do LSPosed (que chegam depois, numa linha separada).
 */
final class SafeMode {
    private SafeMode() {}

    static final String ACTION = "com.ilha.headsup.SAFE_OFF";
    static final long WINDOW = 3L * 60L * 1000L;      // partidas que contam
    static final long RECENT = 30L * 60L * 1000L;     // a opção precisa ter sido ligada há pouco
    static final File FILE = new File("/data/user_de/0/com.android.systemui/files/heads-up-ilha-seguranca");

    static final HashSet off = new HashSet();
    static String last = "";
    static long lastAt;
    static String pending = "";

    static synchronized void boot() {
        try {
            Properties p = load();
            last = p.getProperty("last", "");
            lastAt = parse(p.getProperty("lastAt", "0"));
            for (String k : p.getProperty("off", "").split(",")) if (k.length() > 0) off.add(k);
            pending = p.getProperty("pending", "");
            long now = System.currentTimeMillis();
            ArrayList starts = new ArrayList();
            for (String s : p.getProperty("starts", "").split(",")) {
                long t = parse(s);
                if (t > 0 && now - t < WINDOW) starts.add(Long.valueOf(t));
            }
            starts.add(Long.valueOf(now));
            if (starts.size() >= 3 && last.length() > 0 && now - lastAt < RECENT) {
                off.add(last);
                pending = last;
                ElasticCard.log("SEGURANÇA: a Interface do Sistema reiniciou " + (starts.size() - 1)
                        + " vezes em poucos minutos; desliguei a última opção ligada (" + last + ")");
                last = "";
                starts.clear();
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < starts.size(); i++) sb.append(i > 0 ? "," : "").append(starts.get(i));
            p.setProperty("starts", sb.toString());
            save(p);
        } catch (Throwable t) {
            ElasticCard.log("segurança: registro indisponível " + t);
        }
    }

    /** Valor da opção depois do modo de segurança. */
    static boolean apply(String key, boolean v) {
        if (!v) {
            if (off.contains(key)) clear(key);      // o app já desligou: não precisa mais forçar
            return false;
        }
        return !off.contains(key);
    }

    /** Lido das preferências: guarda qual foi a última opção ligada. */
    static synchronized void noteLast(String key, long at) {
        if (key == null) key = "";
        if (key.equals(last) && at == lastAt) return;
        last = key;
        lastAt = at;
        try {
            Properties p = load();
            p.setProperty("last", key);
            p.setProperty("lastAt", String.valueOf(at));
            save(p);
        } catch (Throwable ignored) {
        }
    }

    static synchronized void clear(String key) {
        if (!off.remove(key)) return;
        try {
            Properties p = load();
            p.setProperty("off", join(off));
            save(p);
        } catch (Throwable ignored) {
        }
    }

    /** Avisa o app (quando o contexto já existe) para desligar a chave nos ajustes. */
    static synchronized void notifyApp() {
        if (pending.length() == 0) return;
        try {
            Class at = Class.forName("android.app.ActivityThread");
            Context c = (Context) at.getMethod("currentApplication").invoke(null);
            if (c == null) return;
            Intent i = new Intent(ACTION);
            i.setPackage(Keys.PKG);
            i.putExtra("key", pending);
            c.sendBroadcast(i);
            ElasticCard.log("segurança: pedi ao app para desligar " + pending);
            pending = "";
            Properties p = load();
            p.setProperty("pending", "");
            p.setProperty("off", join(off));
            save(p);
        } catch (Throwable t) {
            ElasticCard.log("segurança: aviso ao app falhou " + t);
        }
    }

    static String join(HashSet s) {
        StringBuilder sb = new StringBuilder();
        for (Object o : s) sb.append(sb.length() > 0 ? "," : "").append(o);
        return sb.toString();
    }

    static long parse(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    static Properties load() {
        Properties p = new Properties();
        if (FILE.exists()) {
            FileInputStream in = null;
            try {
                in = new FileInputStream(FILE);
                p.load(in);
            } catch (Throwable ignored) {
            } finally {
                try { if (in != null) in.close(); } catch (Throwable ignored) { }
            }
        }
        return p;
    }

    static void save(Properties p) throws Exception {
        File d = FILE.getParentFile();
        if (!d.exists()) d.mkdirs();
        p.setProperty("off", join(off));
        p.setProperty("pending", pending);
        FileOutputStream out = new FileOutputStream(FILE);
        try {
            p.store(out, "Heads-up Ilha");
        } finally {
            out.close();
        }
    }
}
