package com.ilha.headsup;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;

/**
 * Linhas de texto dos cartões da central (o HyperOS mostra 2): o usuário escolhe quantas.
 * Dentro de um grupo em pilha os cartões ficam com 3 linhas; ao abrir a pilha voltam ao escolhido.
 * A heads-up não muda.
 */
final class Lines4 {
    private Lines4() {}

    static final int TAG = 0x7e1a7041, TAG_H = 0x7e1a7042, TAG_N = 0x7e1a7043;
    static final int STACKED = 3;
    static int logs;
    static View root;

    static int want() {
        return Prefs.lines4 ? Math.max(2, Prefs.linesN) : 2;
    }

    static void install(ClassLoader cl) {
        try {
            Class c = Class.forName("com.android.systemui.statusbar.notification.row.NotificationContentView", false, cl);
            XposedBridge.hookMethod(c.getDeclaredMethod("setHeights", int.class, int.class, int.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    int n = want();
                    if (n <= 2) return;
                    float d = ((View) p.thisObject).getResources().getDisplayMetrics().density;
                    int extra = Math.round((n - 2) * 26f * d);
                    p.args[0] = Integer.valueOf(((Integer) p.args[0]).intValue() + extra);
                }
            });
            ElasticCard.log("linhas da central: pronto");
        } catch (Throwable t) {
            ElasticCard.log("linhas da central indisponível: " + t);
            Caps.fail(Keys.LINES4, t);
        }
    }

    /** Chamado a cada notificação montada ou atualizada. */
    static void row(View row) {
        if (!Prefs.lines4 && !used) return;
        int n = want();
        try {
            Object parent = Refl.call(row, "getNotificationParent");
            if (parent instanceof View && n > STACKED && GroupStack.stacked(parent)) n = STACKED;
        } catch (Throwable ignored) {
        }
        set(row, n);
    }

    /** Aplica n linhas no cartão (só se mudou). */
    static void set(View row, int n) {
        Object last = row.getTag(TAG_N);
        if (last instanceof Integer && ((Integer) last).intValue() == n && n == want()) {
            // mesmo valor: ainda assim refaz se o conteúdo foi montado de novo
        }
        if (n > 2) used = true;
        row.setTag(TAG_N, Integer.valueOf(n));
        try {
            Object l = Refl.get(row, "mPrivateLayout");
            if (!(l instanceof View)) return;
            Object v = Refl.call(l, "getContractedChild");
            if (!(v instanceof View)) return;
            root = (View) v;
            int[] k = new int[1];
            walk((View) v, n, k);
            if (k[0] > 0) {
                ((View) l).requestLayout();
                row.requestLayout();
            }
        } catch (Throwable t) {
            if (logs++ < 5) ElasticCard.log("linhas da central: erro " + t);
        }
    }

    /** Grupo em pilha (fechado = 3 linhas) ou aberto (o escolhido). */
    static boolean used;

    static void group(Object summary, boolean stacked) {
        if (!Prefs.lines4 && !used) return;
        int n = want();
        if (n > STACKED && stacked) n = STACKED;
        try {
            Object c = Refl.get(summary, "mChildrenContainer");
            Object kids = c == null ? null : Refl.get(c, "mAttachedChildren");
            if (!(kids instanceof java.util.List)) return;
            for (Object k : (java.util.List) kids) {
                if (!(k instanceof View)) continue;
                Object last = ((View) k).getTag(TAG_N);
                if (last instanceof Integer && ((Integer) last).intValue() == n) continue;
                set((View) k, n);
            }
        } catch (Throwable ignored) {
        }
    }

    static void free(View t, boolean on) {
        View v = t;
        while (v != null) {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                Object was = v.getTag(TAG_H);
                if (on && was == null && lp.height > 0) {
                    v.setTag(TAG_H, Integer.valueOf(lp.height));
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    v.setLayoutParams(lp);
                } else if (!on && was instanceof Integer) {
                    lp.height = ((Integer) was).intValue();
                    v.setTag(TAG_H, null);
                    v.setLayoutParams(lp);
                }
            }
            if (v == root || !(v.getParent() instanceof View)) break;
            v = (View) v.getParent();
        }
    }

    static void walk(View v, int n, int[] k) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            Object was = t.getTag(TAG);
            int m = t.getMaxLines();
            if (was == null && m == 2) {
                was = Integer.valueOf(2);
                t.setTag(TAG, was);
            }
            if (was instanceof Integer && m != n) {
                t.setMaxLines(n);
                free(t, n > 2);
                k[0]++;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), n, k);
        }
    }
}
