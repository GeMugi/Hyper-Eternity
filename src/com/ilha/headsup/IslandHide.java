package com.ilha.headsup;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Esconder a ilha ao puxar a central (teste). Com a ilha de música ativa, o HyperOS anima a ilha
 * e a central ao mesmo tempo e a puxada engasga; enquanto a central está aberta, a janela da ilha
 * fica invisível (não é desenhada) e volta quando a central fecha.
 */
final class IslandHide {
    private IslandHide() {}

    static final ArrayList<WeakReference<View>> islands = new ArrayList<>();
    static final Handler H = new Handler(Looper.getMainLooper());
    static boolean hidden, running;
    static int logs;

    static void install() {
        try {
            XposedBridge.hookMethod(Class.forName("android.view.WindowManagerGlobal").getDeclaredMethod("addView", View.class, ViewGroup.LayoutParams.class,
                    android.view.Display.class, android.view.Window.class, int.class), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        View v = (View) p.args[0];
                        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) p.args[1];
                        String t = String.valueOf(lp.getTitle());
                        if (logs < 40) log("janela '" + t + "' tipo=" + lp.type + " " + lp.width + "x" + lp.height + " " + v.getClass().getName());
                        String lo = (t + " " + v.getClass().getName()).toLowerCase();
                        if (lo.contains("island")) {
                            islands.add(new WeakReference<>(v));
                            log("ilha encontrada: '" + t + "'");
                            start();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            log("indisponível: " + t);
        }
    }

    static void start() {
        if (running) return;
        running = true;
        H.post(TICK);
    }

    static final Runnable TICK = new Runnable() {
        public void run() {
            try {
                boolean want = Prefs.islandHide && ShadeSheet.expanded();
                if (want != hidden) {
                    hidden = want;
                    for (WeakReference<View> r : islands) {
                        View v = r.get();
                        if (v != null) v.setVisibility(want ? View.INVISIBLE : View.VISIBLE);
                    }
                    if (logs < 60) log(want ? "central aberta: ilha escondida" : "central fechada: ilha de volta");
                }
            } catch (Throwable ignored) {
            }
            H.postDelayed(this, hidden ? 40 : 60);
        }
    };

    static void log(String s) {
        if (logs++ < 70) ElasticCard.log("ilha: " + s);
    }
}
