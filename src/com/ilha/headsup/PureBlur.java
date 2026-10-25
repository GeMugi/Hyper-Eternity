package com.ilha.headsup;

import android.view.View;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.reflect.Method;

/**
 * Fundo do player igual ao das notificações. O HyperOS desfoca o que está atrás do cartão do player e
 * depois mistura cores por cima (MiBlurCompat.setMiBackgroundBlendColors no media_bg; no
 * modo escuro uma delas é preto a 60%), por isso o cartão fica escuro. Aqui essa mistura é
 * pulada só no fundo do player (gaveta e bloqueio): fica o vidro puro, sem cor.
 */
final class PureBlur {
    private PureBlur() {}

    static Method clear;
    static int idBg, idPlayer;
    static int logs;

    static void install(ClassLoader cl) {
        try {
            Class mb = Class.forName("com.miui.systemui.util.MiBlurCompat", false, cl);
            clear = mb.getDeclaredMethod("clearMiBackgroundBlendColorCompat", View.class);
            clear.setAccessible(true);
            XC_MethodHook skip = new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Object v = p.args[0];
                        if (!(v instanceof View) || !isPlayerBg((View) v) || !(p.args[1] instanceof int[])) return;
                        Prefs.refresh();
                        if (!Prefs.mediaPure) return;
                        int[] like = notifBlend((View) v, (int[]) p.args[1]);
                        if (like == null) return;
                        p.args[1] = like;
                        if (logs++ < 2) ElasticCard.log("fundo do player: mesma mistura de cor das notificações");
                    } catch (Throwable t) {
                        if (logs++ < 6) ElasticCard.log("fundo do player: erro " + t);
                    }
                }
            };
            int n = 0;
            for (Method m : mb.getDeclaredMethods()) {
                String nm = m.getName();
                Class[] pt = m.getParameterTypes();
                if ((nm.equals("setMiBackgroundBlendColors") || nm.startsWith("setMiBackgroundBlendColorsNew"))
                        && pt.length >= 2 && pt[0] == View.class) {
                    XposedBridge.hookMethod(m, skip);
                    n++;
                }
            }
            ElasticCard.log("fundo do player igual às notificações: " + (n > 0 ? "instalado" : "mistura de cor não encontrada"));
        } catch (Throwable t) {
            ElasticCard.log("fundo do player só com desfoque: indisponível " + t);
        }
    }

    /**
     * Troca a mistura de cor do player pela das notificações comuns (a mesma cor e modo que
     * os outros cartões usam na gaveta ou no bloqueio), para o player ficar igual a eles.
     */
    static int[] notifBlend(View v, int[] media) {
        android.content.res.Resources r = v.getResources();
        String pkg = "com.android.systemui";
        int kgId = r.getIdentifier("media_notification_element_blend_keyguard_color_1", "color", pkg);
        boolean kg = kgId != 0 && media.length > 0 && media[0] == v.getContext().getColor(kgId);
        String where = kg ? "keyguard" : "shade";
        int c1 = r.getIdentifier("notification_element_blend_" + where + "_color_1", "color", pkg);
        int c2 = r.getIdentifier("notification_element_blend_" + where + "_color_2", "color", pkg);
        int m1 = r.getIdentifier("notification_element_blend_" + where + "_mode_1", "integer", pkg);
        int m2 = r.getIdentifier("notification_element_blend_" + where + "_mode_2", "integer", pkg);
        if (c1 == 0 || c2 == 0 || m1 == 0 || m2 == 0) return null;
        android.content.Context c = v.getContext();
        return new int[]{c.getColor(c1), r.getInteger(m1), c.getColor(c2), r.getInteger(m2)};
    }

    static boolean isPlayerBg(View v) {
        if (idBg == 0) {
            idBg = v.getResources().getIdentifier("media_bg", "id", "com.android.systemui");
            idPlayer = v.getResources().getIdentifier("mi_media_controls", "id", "com.android.systemui");
        }
        if (idBg == 0 || v.getId() != idBg) return false;
        Object p = v.getParent();
        return p instanceof View && (idPlayer == 0 || ((View) p).getId() == idPlayer);
    }
}
