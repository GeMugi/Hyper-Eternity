package com.ilha.headsup;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;

/**
 * Ao segurar uma notificação (menu "Desativar / Mais"), o cartão mostra a mensagem inteira:
 * tira o limite de linhas dos textos e das listas de mensagens da cópia do cartão no menu.
 */
final class ModalFull {
    private ModalFull() {}

    static int logs;
    static final int TAG = 0x7e1a7021;

    static void install(ClassLoader cl) {
        try {
            Class c = Class.forName("com.android.systemui.statusbar.notification.modal.ModalControllerImpl", false, cl);
            int n = 0;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("tryAnimEnterModal")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (!Prefs.modalFull) return;
                        try {
                            final Object row = Refl.get(p.thisObject, "modalRow");
                            if (!(row instanceof View)) return;
                            final View v = (View) row;
                            open(v);
                            v.post(new Runnable() {
                                public void run() {
                                    open(v);
                                }
                            });
                            v.postDelayed(new Runnable() {
                                public void run() {
                                    open(v);
                                }
                            }, 120);
                            v.postDelayed(new Runnable() {
                                public void run() {
                                    open(v);
                                }
                            }, 350);
                        } catch (Throwable t) {
                            if (logs++ < 5) ElasticCard.log("mensagem inteira no menu: erro " + t);
                        }
                    }
                });
                n++;
            }
            ElasticCard.log("mensagem inteira no menu: " + (n > 0 ? "pronto" : "não encontrado"));
        } catch (Throwable t) {
            ElasticCard.log("mensagem inteira no menu indisponível: " + t);
            Caps.fail(Keys.MODAL_FULL, t);
        }
    }

    static void open(View row) {
        int[] k = new int[1];
        // altura máxima do cartão: o HyperOS limita a parte aberta da notificação; no menu,
        // libera até 80% da tela
        try {
            int big = (int) (row.getResources().getDisplayMetrics().heightPixels * 0.62f);
            java.lang.reflect.Field f = Refl.field(row.getClass(), "mMaxExpandedHeight");
            if (f != null && f.getInt(row) < big) {
                f.setInt(row, big);
                k[0]++;
            }
            for (String ln : new String[]{"mPrivateLayout", "mPublicLayout"}) {
                Object l = Refl.get(row, ln);
                if (l == null) continue;
                java.lang.reflect.Field h = Refl.field(l.getClass(), "mNotificationMaxHeight");
                if (h != null && h.getInt(l) < big) {
                    h.setInt(l, big);
                    ((View) l).requestLayout();
                    k[0]++;
                }
            }
        } catch (Throwable t) {
            if (logs++ < 5) ElasticCard.log("mensagem inteira no menu: altura erro " + t);
        }
        walk(row, k);
        if (k[0] > 0) {
            row.requestLayout();
            try {
                Refl.heightChanged(row, Boolean.FALSE);
            } catch (Throwable ignored) {
            }
        }
        try {
            int ih = ((Integer) Refl.anyMethod(row.getClass(), "getIntrinsicHeight").invoke(row)).intValue();
            Object ah = Refl.anyMethod(row.getClass(), "getActualHeight").invoke(row);
            if (ah instanceof Integer && ih > ((Integer) ah).intValue())
                row.getClass().getMethod("setActualHeight", int.class, boolean.class).invoke(row, Integer.valueOf(ih), Boolean.FALSE);
        } catch (Throwable ignored) {
        }
        if (logs++ < 6) ElasticCard.log("mensagem inteira no menu: " + k[0] + " ajustes");
    }

    static void walk(View v, int[] k) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            if (t.getMaxLines() != Integer.MAX_VALUE) {
                t.setMaxLines(Integer.MAX_VALUE);
                t.setEllipsize(null);
                k[0]++;
            }
            // texto comprido: fica numa caixa de altura fixa que rola com o dedo
            int cap = (int) (t.getResources().getDisplayMetrics().heightPixels * 0.40f);
            if (t.getTag(TAG) == null && t.getText() != null && t.getText().length() > 120) {
                t.setTag(TAG, Boolean.TRUE);
                t.setMaxHeight(cap);
                t.setVerticalScrollBarEnabled(true);
                t.setVerticalFadingEdgeEnabled(true);
                t.setFadingEdgeLength((int) (24 * t.getResources().getDisplayMetrics().density));
                t.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
                t.setOnTouchListener(new View.OnTouchListener() {
                    float y0;
                    public boolean onTouch(View w, android.view.MotionEvent e) {
                        TextView tv = (TextView) w;
                        boolean can = tv.canScrollVertically(1) || tv.canScrollVertically(-1);
                        if (can) w.getParent().requestDisallowInterceptTouchEvent(true);
                        return false;
                    }
                });
                k[0]++;
            }
        }
        String cn = v.getClass().getName();
        if (cn.contains("MessagingLinearLayout")) {
            try {
                java.lang.reflect.Field f = Refl.field(v.getClass(), "mMaxDisplayedLines");
                if (f != null && f.getInt(v) != Integer.MAX_VALUE) {
                    f.setInt(v, Integer.MAX_VALUE);
                    v.requestLayout();
                    k[0]++;
                }
            } catch (Throwable ignored) {
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), k);
        }
    }
}
