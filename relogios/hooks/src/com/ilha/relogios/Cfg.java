package com.ilha.relogios;

import android.content.SharedPreferences;
import com.ilha.relogios.xp.XposedBridge;

/** Ajustes da extensão dentro dos processos do sistema (preferências remotas do LSPosed). */
final class Cfg {
    private Cfg() {}

    static volatile boolean editor, lock, curtain, follow, frost, loaded;
    static volatile int fx, fxBlend, wobArea, wobForce;
    static volatile boolean wob, glow;
    static volatile int glowForce = 50, glowArea;
    static volatile String subjMask = "";
    static volatile String wobMask = "";
    private static volatile SharedPreferences prefs;
    private static final java.util.ArrayList<Runnable> watchers = new java.util.ArrayList<Runnable>();
    private static final SharedPreferences.OnSharedPreferenceChangeListener LISTENER =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                    read(sp);
                }
            };

    /** Carrega na hora (bloqueia até o LSPosed responder). */
    static synchronized void loadNow() {
        if (prefs != null) return;
        try {
            SharedPreferences p = XposedBridge.remotePrefs(Keys.GROUP);
            if (p == null) return;
            prefs = p;
            p.registerOnSharedPreferenceChangeListener(LISTENER);
            read(p);
        } catch (Throwable t) {
            XposedBridge.log("[Relógios HOS4] ajustes indisponíveis: " + t);
        }
    }

    /** Carrega numa linha separada (para não atrasar a partida da Interface do Sistema). */
    static void loadLater() {
        Thread t = new Thread(new Runnable() {
            public void run() { loadNow(); }
        }, "relogios-ajustes");
        t.setDaemon(true);
        t.start();
    }

    static void watch(Runnable r) {
        synchronized (watchers) { watchers.add(r); }
    }

    static void read(SharedPreferences p) {
        boolean was = loaded;
        editor = p.getBoolean(Keys.EDITOR, false);
        lock = Safe.apply(Keys.LOCK, p.getBoolean(Keys.LOCK, false));
        curtain = Safe.apply(Keys.CURTAIN, p.getBoolean(Keys.CURTAIN, false));
        follow = Safe.apply(Keys.FOLLOW, p.getBoolean(Keys.FOLLOW, false));
        frost = Safe.apply(Keys.FROST, p.getBoolean(Keys.FROST, false));
        try {
            fx = p.getInt(Keys.FX, 0);
            fxBlend = p.getInt(Keys.FX_BLEND, 0);
            wob = Safe.apply(Keys.WOB, p.getBoolean(Keys.WOB, false));
            wobArea = p.getInt(Keys.WOB_AREA, 0);
            wobForce = p.getInt(Keys.WOB_FORCE, 50);
            wobMask = p.getString(Keys.WOB_MASK, "");
            glow = Safe.apply(Keys.GLOW, p.getBoolean(Keys.GLOW, false));
            glowForce = p.getInt(Keys.GLOW_FORCE, 50);
            glowArea = p.getInt(Keys.GLOW_AREA, 0);
            subjMask = p.getString(Keys.SUBJ_MASK, "");
        } catch (Throwable ignored) {
        }
        try {
            Safe.noteLast(p.getString(Keys.SAFE_LAST, ""), p.getLong(Keys.SAFE_AT, 0L));
        } catch (Throwable ignored) {
        }
        loaded = true;
        if (!was) XposedBridge.log("[Relógios HOS4] ajustes: editor " + editor + ", bloqueio " + lock + ", cortina " + curtain + ", notificações " + follow + ", vidro fosco " + frost + ", efeito " + fx + ", mistura " + fxBlend);
        final java.util.ArrayList<Runnable> copy;
        synchronized (watchers) { copy = new java.util.ArrayList<Runnable>(watchers); }
        if (copy.isEmpty()) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                for (Runnable r : copy) {
                    try { r.run(); } catch (Throwable ignored) {}
                }
            }
        });
    }
}
