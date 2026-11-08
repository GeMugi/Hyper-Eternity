package com.ilha.headsup;

import android.view.View;
import android.view.ViewGroup;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Pontos de origem da heads-up estilo iOS: o ícone do app na barra de status (preferido) ou,
 * sem ele, o relógio da barra (MiuiClock / Clock).
 */
final class StatusClock {
    private StatusClock() {}

    static final Map<View, Boolean> clocks = new WeakHashMap<View, Boolean>();
    static final Map<ViewGroup, Boolean> iconAreas = new WeakHashMap<ViewGroup, Boolean>();
    static final int[] tmp = new int[2];

    static void install(ClassLoader cl) {
        int n = 0;
        String[] names = {"com.android.systemui.statusbar.views.MiuiClock", "com.android.systemui.statusbar.policy.Clock"};
        for (int i = 0; i < names.length; i++) {
            try {
                Class c = Class.forName(names[i], false, cl);
                Method at = c.getDeclaredMethod("onAttachedToWindow");
                XposedBridge.hookMethod(at, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        clocks.put((View) p.thisObject, Boolean.TRUE);
                    }
                });
                Method de = c.getDeclaredMethod("onDetachedFromWindow");
                XposedBridge.hookMethod(de, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        clocks.remove(p.thisObject);
                    }
                });
                n++;
            } catch (Throwable ignored) {
            }
        }
        ElasticCard.log("relógio da barra de status: " + n + " tipo(s) acompanhados");
        try {
            Class c = Class.forName("com.android.systemui.statusbar.phone.NotificationIconContainer", false, cl);
            Method lay = c.getDeclaredMethod("onLayout", boolean.class, int.class, int.class, int.class, int.class);
            XposedBridge.hookMethod(lay, new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    iconAreas.put((ViewGroup) p.thisObject, Boolean.TRUE);
                }
            });
            ElasticCard.log("ícones da barra de status: acompanhados");
        } catch (Throwable t) {
            ElasticCard.log("ícones da barra de status indisponíveis: " + t);
        }
    }

    /** Ponto abaixo do relógio visível na barra de status (x central, y da base), ou null. */
    /** O relógio mais à esquerda que está à vista na barra de status. */
    static View leftClock() {
        View best = null;
        int bestX = Integer.MAX_VALUE;
        for (View v : new ArrayList<View>(clocks.keySet())) {
            if (!v.isShown() || v.getWidth() <= 0 || v.getAlpha() < 0.05f) continue;
            v.getLocationOnScreen(tmp);
            if (tmp[1] > v.getHeight() * 3) continue;
            if (tmp[0] < bestX) { bestX = tmp[0]; best = v; }
        }
        return best;
    }

    static float[] anchor(int statusBar) {
        try {
            if (clocks.isEmpty()) return null;
            ArrayList<View> list = new ArrayList<View>(clocks.keySet());
            View best = null;
            int bestX = Integer.MAX_VALUE;
            for (int i = 0; i < list.size(); i++) {
                View v = list.get(i);
                if (!v.isShown() || v.getWidth() <= 0 || v.getHeight() <= 0 || v.getAlpha() < 0.05f) continue;
                v.getLocationOnScreen(tmp);
                if (tmp[1] < -2 || tmp[1] > statusBar || v.getHeight() > statusBar * 1.4f) continue;
                if (tmp[0] < bestX) {
                    bestX = tmp[0];
                    best = v;
                }
            }
            if (best == null) return null;
            best.getLocationOnScreen(tmp);
            float d = best.getResources().getDisplayMetrics().density;
            return new float[] {tmp[0] + best.getWidth() * 0.5f, tmp[1] + best.getHeight() + 3f * d};
        } catch (Throwable t) {
            return null;
        }
    }
}
