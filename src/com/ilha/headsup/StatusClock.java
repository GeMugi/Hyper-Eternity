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

    /**
     * Centro do ícone deste app (desta notificação) na barra de status: {x, y, tamanho}, ou null
     * se ele não estiver visível lá.
     */
    static float[] iconAnchor(View row, int statusBar) {
        try {
            Object entry = Refl.call(row, "getEntry");
            if (entry == null) return null;
            View best = null;
            Object pack = Refl.get(entry, "mIcons");
            Object own = Refl.get(pack, "mStatusBarIcon");
            if (own instanceof View && usable((View) own, statusBar)) best = (View) own;
            if (best == null && !iconAreas.isEmpty()) {
                Object key = Refl.get(entry, "mKey");
                ArrayList<ViewGroup> list = new ArrayList<ViewGroup>(iconAreas.keySet());
                for (int i = 0; i < list.size() && best == null && key != null; i++) {
                    ViewGroup g = list.get(i);
                    if (!g.isShown()) continue;
                    for (int j = 0; j < g.getChildCount(); j++) {
                        View c = g.getChildAt(j);
                        if (!usable(c, statusBar)) continue;
                        Object sbn = Refl.get(c, "mNotification");
                        if (sbn != null && key.equals(Refl.call(sbn, "getKey"))) {
                            best = c;
                            break;
                        }
                    }
                }
            }
            if (best == null) return null;
            best.getLocationOnScreen(tmp);
            float sw = best.getWidth() * Math.abs(best.getScaleX());
            float sh = best.getHeight() * Math.abs(best.getScaleY());
            float size = Math.min(sw, sh);
            return new float[] {tmp[0] + sw * 0.5f, tmp[1] + sh * 0.5f, size};
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean usable(View v, int statusBar) {
        if (!v.isShown() || v.getWidth() <= 0 || v.getHeight() <= 0 || v.getAlpha() < 0.05f) return false;
        v.getLocationOnScreen(tmp);
        return tmp[1] >= -2 && tmp[1] < statusBar && v.getHeight() <= statusBar * 1.4f;
    }

    /** Ponto abaixo do relógio visível na barra de status (x central, y da base), ou null. */
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
