package com.ilha.relogios;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import com.ilha.relogios.xp.XC_MethodHook;
import com.ilha.relogios.xp.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Números com "vidro" (escolhido no editor) nos relógios All in One.
 *
 * O vidro do HyperOS 4 é um efeito do motor gráfico que o HyperOS 3 não tem: pedido aqui, ele deixa
 * a tinta dos números num modo que o HyperOS 3 não desenha e o número some. Por isso o vidro nativo
 * nunca é ligado; no lugar dele, o número fica em cor sólida ou, com o ajuste "vidro fosco", na
 * mistura com o fundo desfocado do próprio sistema (a mesma do material "mistura").
 */
final class Glass {
    private Glass() {}

    static final int TAG_ON = 0x7e1a0e21, TAG_CLEARED = 0x7e1a0e22, TAG_FROST = 0x7e1a0e23;
    static final Integer C1 = Integer.valueOf(0xffababab), C2 = Integer.valueOf(0xffe6e6e6), FULL = Integer.valueOf(255), ZERO = Integer.valueOf(0);

    static Field fNative;
    static Method mStyle, mEffect, mDisplay, mIsAod, mClear, mBlend, mHandle;
    static Class<?> baseCls, tvCls;
    static int logs, frostLogs, wantLogs;
    static boolean installed;

    /** O motor gráfico do sistema tem o vidro do HyperOS 4? (o HyperOS 3 não tem) */
    static final boolean NATIVE = hasNative();

    static boolean hasNative() {
        try {
            View.class.getMethod("setMiGlass", float[].class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static void log(String s) {
        XposedBridge.log("[Relógios HOS4] " + s);
    }

    static void install(ClassLoader cl) {
        if (installed) return;
        installed = true;
        if (NATIVE) {
            log("vidro: o sistema tem o vidro do HyperOS 4, nada a fazer");
            return;
        }
        if (Safe.blocked("vidro")) {
            log("vidro desligado pela proteção (o SystemUI caiu em sequência com ele); volta na próxima versão");
            return;
        }
        try {
            Class<?> tv = tvCls = cl.loadClass("com.miui.clock.allInOne.TimeView");
            fNative = tv.getDeclaredField("isMiGlassEffectEnable");
            fNative.setAccessible(true);
            for (Class<?> k = tv; k != null && mHandle == null; k = k.getSuperclass()) {
                try {
                    mHandle = k.getDeclaredMethod("handleParams");
                    mHandle.setAccessible(true);
                } catch (Throwable ignored) {
                }
            }
            baseCls = cl.loadClass("com.miui.clock.allInOne.AllInOneBase");
            Class<?> info = cl.loadClass("com.miui.clock.module.ClockStyleInfo");
            mStyle = baseCls.getMethod("getClockStyleInfo");
            mEffect = info.getMethod("getClockEffect");
            mDisplay = info.getMethod("getDisplayType");
            mIsAod = info.getMethod("isAODType", int.class);
            Class<?> mbu = cl.loadClass("com.miui.clock.utils.MiuiBlurUtils");
            mClear = mbu.getMethod("clearMemberBlendColor", View.class);
            mBlend = mbu.getMethod("setMemberBlendColors", View.class, boolean.class, int.class, int.class, int.class, int.class, boolean.class, int.class);

            // efeito do material aplicado em cada número
            XC_MethodHook eff = new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (Fx.blending() || Fx.shading() || Fx.glass()) return;   // os efeitos da extensão mandam
                        if (!(p.args[0] instanceof View) || !tvCls.isInstance(p.args[0]) || p.args[1] == null) return;
                        View v = (View) p.args[0];
                        int effect = ((Integer) mEffect.invoke(p.args[1])).intValue();
                        int type = ((Integer) mDisplay.invoke(p.args[1])).intValue();
                        boolean on = effect == 5 && !((Boolean) mIsAod.invoke(null, type)).booleanValue();
                        Object was = v.getTag(TAG_ON);
                        v.setTag(TAG_ON, on ? Boolean.TRUE : Boolean.FALSE);
                        if (on && !Cfg.frost) {
                            p.setResult(null);
                            mClear.invoke(null, v);
                        }
                        if (!Boolean.valueOf(on).equals(was)) v.invalidate();
                    } catch (Throwable t) {
                        if (logs++ < 10) log("vidro (efeito): " + t);
                    }
                }
            };
            for (Method m : cl.loadClass("com.miui.clock.utils.ClockEffectUtils").getDeclaredMethods())
                if (m.getName().equals("setClockEffectsView")) XposedBridge.hookMethod(m, eff);

            // o vidro nativo fica sempre desligado (sem ele no sistema, o número sumia)
            XC_MethodHook off = new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) { p.args[0] = Boolean.FALSE; }
            };
            XposedBridge.hookMethod(tv.getDeclaredMethod("setIsMiGlassEffectEnable", boolean.class), off);
            XposedBridge.hookMethod(tv.getDeclaredMethod("setMiGlassEffectEnable", boolean.class), off);
            // aqui o liga/desliga é o 2º argumento (o 1º é a tinta)
            XposedBridge.hookMethod(mbu.getMethod("setPaintGlassEffect", Paint.class, boolean.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) { p.args[1] = Boolean.FALSE; }
            });
            // vidro nativo: vira o fosco (mistura com o fundo desfocado) ou nada (cor sólida)
            XposedBridge.hookMethod(mbu.getMethod("setGlassEffectMethod", View.class, float[].class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    p.setResult(null);
                    if (!Cfg.frost || Fx.glass()) return;
                    try {
                        View v = (View) p.args[0];
                        if (v == null) return;
                        v.setTag(TAG_FROST, Boolean.TRUE);
                        frost(v);
                        if (frostLogs++ < 6) log("vidro fosco em " + name(v));
                    } catch (Throwable t) {
                        if (logs++ < 10) log("vidro fosco: " + t);
                    }
                }
            });
            // sem o fosco: nenhum caminho põe um número com vidro no modo de mistura
            XposedBridge.hookMethod(mbu.getMethod("setMiViewBlurMode", View.class, int.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (Cfg.frost || Fx.glass() || !(p.args[0] instanceof View) || !tvCls.isInstance(p.args[0])) return;
                    if (((Integer) p.args[1]).intValue() != 0 && wanted((View) p.args[0])) p.args[1] = ZERO;
                }
            });
            XC_MethodHook noBlend = new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (Cfg.frost || Fx.glass() || p.args.length == 0 || !(p.args[0] instanceof View) || !tvCls.isInstance(p.args[0])) return;
                    if (wanted((View) p.args[0])) p.setResult(null);
                }
            };
            for (Method m : mbu.getDeclaredMethods()) {
                String n = m.getName();
                if (m != mBlend && n.equals("setMiBackgroundBlendColors")) XposedBridge.hookMethod(m, noBlend);
            }
            XposedBridge.hookMethod(mBlend, noBlend);
            XposedBridge.hookMethod(tv.getDeclaredMethod("onDraw", Canvas.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    before((View) p.thisObject);
                }
            });
            log("vidro: sem o vidro nativo (cor sólida, ou fosco com o ajuste ligado)");
        } catch (Throwable t) {
            log("vidro indisponível: " + t);
        }
    }

    static void frost(View v) throws Exception {
        // chamada direta: o gancho acima deixa passar quando o fosco está ligado
        mBlend.invoke(null, v, Boolean.FALSE, C1, FULL, FULL, C2, Boolean.TRUE, ZERO);
    }

    /** O número pertence a um relógio com o vidro escolhido (e fora do AOD)? */
    static boolean wanted(View tv) {
        try {
            for (android.view.ViewParent pp = tv.getParent(); pp instanceof View; pp = pp.getParent()) {
                if (!baseCls.isInstance(pp)) continue;
                Object info = mStyle.invoke(pp);
                if (info == null) return false;
                int effect = ((Integer) mEffect.invoke(info)).intValue();
                int type = ((Integer) mDisplay.invoke(info)).intValue();
                boolean on = effect == 5 && !((Boolean) mIsAod.invoke(null, type)).booleanValue();
                Object was = tv.getTag(TAG_ON);
                if (!Boolean.valueOf(on).equals(was)) {
                    tv.setTag(TAG_ON, on ? Boolean.TRUE : Boolean.FALSE);
                    tv.invalidate();
                    if (on) Safe.touch("vidro");
                    if (wantLogs++ < 12) log("vidro: número " + name(tv) + " efeito " + effect + " tipo " + type);
                }
                return on;
            }
        } catch (Throwable t) {
            if (logs++ < 10) log("vidro (relógio): " + t);
        }
        return false;
    }

    static String name(View v) {
        try { return v.getResources().getResourceEntryName(v.getId()); } catch (Throwable t) { return "?"; }
    }

    static void before(View v) {
        if (Fx.blending()) return;
        if (Fx.glass()) {
            // vidro da lib: o vidro nativo do HOS4 continua desligado; a mistura e o material são os dos cartões
            try {
                if (fNative.getBoolean(v)) {
                    fNative.setBoolean(v, false);
                    if (mHandle != null) try { mHandle.invoke(v); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            return;
        }
        try {
            if (fNative.getBoolean(v)) {
                fNative.setBoolean(v, false);
                if (mHandle != null) try { mHandle.invoke(v); } catch (Throwable ignored) {}
            }
            boolean want = wanted(v);
            if (Cfg.frost && want && v.getTag(TAG_FROST) == null) {
                // a biblioteca não chamou o vidro neste número: aplica a mistura aqui mesmo
                v.setTag(TAG_FROST, Boolean.TRUE);
                frost(v);
                if (frostLogs++ < 6) log("vidro fosco no desenho de " + name(v));
            } else if (!want && v.getTag(TAG_FROST) != null) v.setTag(TAG_FROST, null);
            if (!Cfg.frost && want && v.getTag(TAG_CLEARED) == null) {
                // já estava no modo de mistura antes de entrar no relógio: volta ao normal
                v.setTag(TAG_CLEARED, Boolean.TRUE);
                v.setTag(TAG_FROST, null);
                try { mClear.invoke(null, v); } catch (Throwable ignored) {}
            } else if ((!want || Cfg.frost) && v.getTag(TAG_CLEARED) != null) v.setTag(TAG_CLEARED, null);
        } catch (Throwable t) {
            if (logs++ < 10) log("vidro (desenho): " + t);
        }
    }
}
