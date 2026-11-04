package com.ilha.headsup;

import android.view.View;
import android.view.ViewGroup;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;

/**
 * Mapa (teste) do relógio do bloqueio quando o HyperMusicCover muda o tamanho dele: anota as
 * peças (números e os dois pontos), tamanho, posição e escala, para os pontos acompanharem.
 */
final class ClockDots {
    private ClockDots() {}

    static int dumps;
    static long last;
    static String lastSig = "";

    static void install(ClassLoader cl) {
        try {
            Class<?> c = Class.forName("com.android.keyguard.clock.KeyguardClockContainer", false, cl);
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("onLayout")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            map((View) p.thisObject);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        } catch (Throwable t) {
            ElasticCard.log("relógio (mapa): " + t);
        }
    }

    static void map(View root) {
        if (dumps >= 4) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - last < 1500) return;
        StringBuilder sig = new StringBuilder();
        sig(root, sig, 0);
        if (sig.toString().equals(lastSig)) return;
        lastSig = sig.toString();
        last = now;
        dumps++;
        ElasticCard.log("relógio (mapa " + dumps + "):");
        dump(root, 0);
    }

    private static void sig(View v, StringBuilder b, int d) {
        if (d > 8) return;
        b.append(v.getWidth()).append('x').append(v.getHeight()).append(',').append(v.getScaleX()).append(';');
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) sig(((ViewGroup) v).getChildAt(i), b, d + 1);
    }

    private static void dump(View v, int d) {
        if (d > 8 || v.getVisibility() == View.GONE) return;
        String id = "";
        try {
            if (v.getId() > 0) id = "#" + v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable ignored) {
        }
        String extra = "";
        if (v instanceof android.widget.TextView) {
            android.widget.TextView t = (android.widget.TextView) v;
            extra = " texto='" + t.getText() + "' tam=" + t.getTextSize();
        }
        ElasticCard.log(new String(new char[d * 2]).replace('\0', ' ') + v.getClass().getName() + id + " " + v.getWidth() + "x" + v.getHeight()
                + " em " + v.getLeft() + "," + v.getTop() + " esc=" + v.getScaleX() + "/" + v.getScaleY() + " trans=" + (int) v.getTranslationX() + "," + (int) v.getTranslationY()
                + " piv=" + (int) v.getPivotX() + "," + (int) v.getPivotY() + (v.getAlpha() != 1f ? " alfa=" + v.getAlpha() : "") + extra);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) dump(((ViewGroup) v).getChildAt(i), d + 1);
    }
}
