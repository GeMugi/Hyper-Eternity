package com.ilha.headsup;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * O que funciona neste aparelho: cada função que não conseguiu se instalar (o HyperOS não tem a
 * parte que ela usa) ou que fica desligada de propósito nesta versão do HyperOS entra aqui. A lista
 * vai para uma configuração do sistema que o app de ajustes lê (sem precisar abrir o app).
 */
final class Caps {
    private Caps() {}

    static final String SETTING = "hyper_eternity_caps";
    static final LinkedHashMap<String, String> fail = new LinkedHashMap<>(), off = new LinkedHashMap<>();
    static boolean scheduled;

    static synchronized void fail(String key, Throwable t) {
        String r = t == null ? "" : t.getClass().getSimpleName();
        if (t instanceof ClassNotFoundException || t instanceof NoSuchMethodException || t instanceof NoSuchFieldException) r = "falta no sistema";
        fail.put(key, clean(r));
        schedule();
    }

    static synchronized void off(String key, String why) {
        off.put(key, clean(why));
        schedule();
    }

    static String clean(String s) {
        return s == null ? "" : s.replace(';', ',').replace('|', '/').replace('~', '-');
    }

    static synchronized void schedule() {
        if (scheduled) return;
        scheduled = true;
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                public void run() {
                    publish();
                }
            }, 12000);
        } catch (Throwable t) {
            scheduled = false;
        }
    }

    static synchronized void publish() {
        scheduled = false;
        try {
            Class at = Class.forName("android.app.ActivityThread");
            Context c = (Context) at.getMethod("currentApplication").invoke(null);
            if (c == null) return;
            StringBuilder sb = new StringBuilder();
            sb.append("v=").append(Keys.VERSION).append(";os=").append(Main.prop("ro.mi.os.version.code"));
            sb.append(";fail=");
            int i = 0;
            for (Map.Entry<String, String> e : fail.entrySet()) sb.append(i++ > 0 ? "|" : "").append(e.getKey()).append('~').append(e.getValue());
            sb.append(";off=");
            i = 0;
            for (Map.Entry<String, String> e : off.entrySet()) sb.append(i++ > 0 ? "|" : "").append(e.getKey()).append('~').append(e.getValue());
            android.provider.Settings.Global.putString(c.getContentResolver(), SETTING, sb.toString());
            ElasticCard.log("funções neste aparelho: " + fail.size() + " indisponíveis, " + off.size() + " desligadas nesta versão do HyperOS");
        } catch (Throwable t) {
            ElasticCard.log("funções neste aparelho: não deu para avisar o app (" + t + ")");
        }
    }

    /** Lado do app: lê a lista. Mapa chave -> motivo ("!" na frente = desligada de propósito). */
    static Map<String, String> read(Context c) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        try {
            String s = android.provider.Settings.Global.getString(c.getContentResolver(), SETTING);
            if (s == null) return out;
            String v = null, f = "", o = "";
            for (String part : s.split(";")) {
                if (part.startsWith("v=")) v = part.substring(2);
                else if (part.startsWith("fail=")) f = part.substring(5);
                else if (part.startsWith("off=")) o = part.substring(4);
            }
            if (v == null || !v.equals(Keys.VERSION)) return out;   // lista de outra versão do módulo: ignora
            for (String e : f.split("\\|")) { int k = e.indexOf('~'); if (k > 0) out.put(e.substring(0, k), e.substring(k + 1)); }
            for (String e : o.split("\\|")) { int k = e.indexOf('~'); if (k > 0) out.put(e.substring(0, k), "!" + e.substring(k + 1)); }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
