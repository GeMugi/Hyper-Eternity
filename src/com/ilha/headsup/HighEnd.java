package com.ilha.headsup;

import android.content.ContentResolver;

import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * "Tratar como topo de linha": em aparelhos que a Xiaomi classifica como de entrada (HyperOS Lite,
 * por exemplo o REDMI 15C), a Interface do Sistema corta vários efeitos - o vidro dos atalhos do
 * bloqueio, a ilha desfocada, animações do relógio... Ela pergunta isso em MiuiConfigs
 * (isLowEndDevice / isLiteOrLowDevice / isLite2Phone / isCupAndGupHighLevel); com a opção, as
 * respostas viram as de um topo de linha. A ilha desfocada é decidida pela configuração
 * support_dynamic_island_blur (que a tela inicial da Xiaomi liga só nos aparelhos fortes): com a
 * opção, ela é lida como ligada.
 *
 * Métodos tão curtos costumam vir "embutidos" em quem os chama (código pré-compilado): os que
 * chamam são desotimizados para passarem pelo gancho. Vale depois de reiniciar a Interface do Sistema.
 */
final class HighEnd {
    private HighEnd() {}

    static final String[] CALLERS = {
            "com.android.keyguard.BaseMiuiKeyguardCameraViewInternal",
            "com.android.keyguard.clock.animation.oversize.OverSizeAAnimation",
            "com.android.keyguard.clock.animation.oversize.OverSizeAnimation",
            "com.android.keyguard.clock.animation.oversize.OverSizeBAnimation",
            "com.android.keyguard.clock.animation.rhombus.RhombusDefaultClockAnimation",
            "com.android.keyguard.clock.animation.rhombus.RhombusSKPupokClockAnimation",
            "com.android.keyguard.clock.animation.rhombus.RhombusSilkSerifClockAnimation",
            "com.android.keyguard.injector.DozeServiceHostInjector",
            "com.android.keyguard.injector.KeyguardPanelViewInjector",
            "com.android.keyguard.injector.MiuiKeyguardSecurityContainerControllerInjector",
            "com.android.keyguard.negative.KeyguardMoveLeftController",
            "com.android.keyguard.panel.KeyguardMoveHelper",
            "com.android.keyguard.panel.KeyguardPanelViewController",
            "com.android.keyguard.shortcut.MiuiShortcutController",
            "com.android.keyguard.utils.AnimUtils",
            "com.android.keyguard.wallpaper.MiuiKeyguardWallPaperManager",
            "com.android.keyguard.widget.MiuiKeyguardMoveLeftViewContainer",
            "com.android.systemui.statusbar.animation.MiuiStatusBarLaunchAnimator",
            "com.android.systemui.statusbar.animation.RectFSpringAnim",
            "com.android.systemui.statusbar.notification.modal.ModalWindowManager",
            "com.android.systemui.statusbar.phone.ScrimState",
            "com.miui.clock.oversize.OversizeSvgView",
            "com.miui.keyguard.screenfade.ShaderAnimation",
            "com.miui.systemui.statusbar.policy.BlurUtilsExt",
            "com.android.systemui.statusbar.notification.DynamicIslandController",
            "com.android.systemui.statusbar.notification.DynamicIslandWindowAnimController",
            "com.android.systemui.statusbar.notification.modal.ModalQSControlDetail",
            "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutInjector",
            "com.android.systemui.statusbar.notification.NotificationAnimationRunner",
            "com.android.keyguard.TinyKeyguardCameraViewController",
    };

    static Object unsafe;
    static Method mPutBool, mPutInt, mGetOff;

    /** Troca o valor de um campo estático (mesmo final) só neste processo. */
    static void flip(StringBuilder out, String cls, ClassLoader cl, String name, Object val) {
        try {
            Class<?> k = cl == null ? Class.forName(cls) : Class.forName(cls, true, cl);
            Field f = k.getDeclaredField(name);
            f.setAccessible(true);
            Object before = f.get(null);
            try {
                f.set(null, val);
            } catch (Throwable t) {
                if (unsafe == null) {
                    Class<?> u = Class.forName("sun.misc.Unsafe");
                    Field tu = u.getDeclaredField("theUnsafe");
                    tu.setAccessible(true);
                    unsafe = tu.get(null);
                    mPutBool = u.getMethod("putBoolean", Object.class, long.class, boolean.class);
                    mPutInt = u.getMethod("putInt", Object.class, long.class, int.class);
                    mGetOff = Field.class.getDeclaredMethod("getOffset");
                    mGetOff.setAccessible(true);
                }
                long off = ((Number) mGetOff.invoke(f)).longValue();
                if (val instanceof Boolean) mPutBool.invoke(unsafe, k, off, val);
                else mPutInt.invoke(unsafe, k, off, ((Number) val).intValue());
            }
            out.append(k.getSimpleName()).append('.').append(name).append(' ').append(before).append("->").append(f.get(null)).append("; ");
        } catch (Throwable t) {
            out.append(name).append(" (").append(t.getClass().getSimpleName()).append("); ");
        }
    }

    static void log(String m) {
        ElasticCard.log("topo de linha: " + m);
    }

    static void install(ClassLoader cl) {
        Prefs.load();
        if (!Prefs.highEnd) return;   // só pesa (desotimiza) quem ligou a opção
        int hooks = 0;
        // as próprias marcas de "aparelho de entrada", neste processo: quem lê direto (sem passar pelo
        // MiuiConfigs, como a troca de cores do modo claro/escuro em alguns pontos) também vê topo de linha
        StringBuilder fl = new StringBuilder();
        flip(fl, "miui.util.DeviceLevel", null, "IS_MIUI_LITE_VERSION", false);
        flip(fl, "miui.util.DeviceLevel", null, "IS_MIUI_MIDDLE_VERSION", false);
        flip(fl, "miui.util.DeviceLevel", null, "IS_MIUI_GO_VERSION", false);
        flip(fl, "miui.os.Build", null, "IS_MIUI_LITE_VERSION", false);
        flip(fl, "com.miui.utils.configs.MiuiConfigs", cl, "MIUI_LITE_V2", false);
        flip(fl, "com.miui.utils.configs.MiuiConfigs", cl, "sForceMiddleDevice", 0);
        try {
            Object high = Class.forName("miui.util.DeviceLevel").getField("HIGH").get(null);
            flip(fl, "com.miui.utils.configs.MiuiConfigs", cl, "cpuLevel", high);
            flip(fl, "com.miui.utils.configs.MiuiConfigs", cl, "gpuLevel", high);
        } catch (Throwable ignored) {
        }
        log("marcas: " + fl);
        try {
            Class<?> mc = Class.forName("com.miui.utils.configs.MiuiConfigs", false, cl);
            for (String n : new String[]{"isLowEndDevice", "isLiteOrLowDevice", "isLite2Phone", "isCupAndGupHighLevel"}) {
                try {
                    final boolean val = n.equals("isCupAndGupHighLevel");
                    Method m = mc.getDeclaredMethod(n);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (Prefs.highEnd) p.setResult(val);
                        }
                    });
                    hooks++;
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable t) {
            log("MiuiConfigs não encontrado: " + t);
        }
        // a ilha desfocada: configuração que a tela inicial só liga nos aparelhos fortes
        try {
            Class<?> g = Class.forName("android.provider.MiuiSettings$Global");
            Method gb = g.getDeclaredMethod("getBoolean", ContentResolver.class, String.class);
            XposedBridge.hookMethod(gb, new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if (Prefs.highEnd && "support_dynamic_island_blur".equals(p.args[1])) p.setResult(Boolean.TRUE);
                }
            });
            hooks++;
        } catch (Throwable t) {
            log("configuração da ilha: " + t);
        }
        try {
            Field f = Class.forName("com.android.systemui.statusbar.notification.DynamicFeatureConfig", true, cl).getDeclaredField("sHomeSupportedDynamicIslandBlur");
            f.setAccessible(true);
            f.setBoolean(null, true);
        } catch (Throwable ignored) {
        }
        int deopt = 0;
        for (String n : CALLERS) {
            try {
                Class<?> k = Class.forName(n, false, cl);
                for (Method m : k.getDeclaredMethods()) if (XposedBridge.deoptimize(m)) deopt++;
                for (Constructor<?> m : k.getDeclaredConstructors()) if (XposedBridge.deoptimize(m)) deopt++;
            } catch (Throwable ignored) {
            }
        }
        log(hooks + " ganchos, " + deopt + " métodos desotimizados");
    }
}
