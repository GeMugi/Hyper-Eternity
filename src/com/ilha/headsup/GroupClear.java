package com.ilha.headsup;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;

/**
 * Grupo aberto: botão "x" ao lado do botão de recolher, que limpa só aquele grupo. Opcional:
 * vibrance (o material de vidro do relógio do bloqueio) no nome do grupo e nos botões.
 */
final class GroupClear {
    private GroupClear() {}

    static final int TAG = 0x7e1a7021;
    static Class rowCls;
    static View downOn;

    static boolean touchX(ViewGroup row, android.view.MotionEvent e) {
        int a = e.getActionMasked();
        if (a == android.view.MotionEvent.ACTION_DOWN) {
            downOn = null;
            Object cc = Refl.get(row, "mChildrenContainer");
            if (!(cc instanceof ViewGroup)) return false;
            View col = ShadeSheet.findId((View) cc, "collapse_button", 0);
            if (col == null || !(col.getParent() instanceof View)) return false;
            Object x = ((View) col.getParent()).getTag(TAG);
            if (!(x instanceof View) || !((View) x).isShown()) return false;
            View xv = (View) x;
            int[] l = new int[2];
            xv.getLocationOnScreen(l);
            float slop = 6 * xv.getResources().getDisplayMetrics().density;
            if (e.getRawX() >= l[0] - slop && e.getRawX() <= l[0] + xv.getWidth() + slop && e.getRawY() >= l[1] - slop && e.getRawY() <= l[1] + xv.getHeight() + slop) {
                downOn = xv;
                xv.setPressed(true);
                row.requestDisallowInterceptTouchEvent(true);
                return true;
            }
            return false;
        }
        if (downOn == null) return false;
        if (a == android.view.MotionEvent.ACTION_UP) {
            downOn.setPressed(false);
            downOn = null;
            Object cc = Refl.get(row, "mChildrenContainer");
            if (cc instanceof ViewGroup) clear((ViewGroup) cc);
        } else if (a == android.view.MotionEvent.ACTION_CANCEL) {
            downOn.setPressed(false);
            downOn = null;
        }
        return true;
    }
    static int logs;

    static void install(ClassLoader cl) {
        try {
            Class ct = Class.forName("com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer", false, cl);
            XposedBridge.hookMethod(ct.getDeclaredMethod("onLayout", boolean.class, int.class, int.class, int.class, int.class), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!Prefs.groupClear && !Prefs.groupVib) return;
                    try {
                        attach((ViewGroup) p.thisObject);
                    } catch (Throwable t) {
                        if (logs++ < 3) ElasticCard.log("limpar grupo: " + t);
                    }
                }
            });
            // o HyperOS trata o toque no cabeçalho do grupo por conta própria (nem chega aos botões):
            // aqui o toque em cima do "x" é pego antes, no próprio cartão do grupo
            Class row = Class.forName("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", false, cl);
            XposedBridge.hookMethod(android.view.ViewGroup.class.getDeclaredMethod("dispatchTouchEvent", android.view.MotionEvent.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (true) return;
                    try {
                        if (touchX((ViewGroup) p.thisObject, (android.view.MotionEvent) p.args[0])) p.setResult(Boolean.TRUE);
                    } catch (Throwable t) {
                        if (logs++ < 6) ElasticCard.log("limpar grupo toque: " + t);
                    }
                }
            });
            rowCls = row;
            // o HyperOS refaz o cabeçalho do grupo (e o clique do botão) a cada atualização:
            // logo depois dele, o botão volta a ser "limpar" e o nome volta a recolher
            Class inj = Class.forName("com.android.systemui.statusbar.notification.stack.NotificationChildrenContainerInjectorImpl", false, cl);
            XposedBridge.hookMethod(inj.getDeclaredMethod("updateMiuiHeader", boolean.class), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!Prefs.groupClear && !Prefs.groupVib) return;
                    try {
                        rewire(p.thisObject);
                    } catch (Throwable t) {
                        if (logs++ < 6) ElasticCard.log("limpar grupo cabeçalho: " + t);
                    }
                }
            });
            ElasticCard.log("limpar grupo: pronto");
        } catch (Throwable t) {
            ElasticCard.log("limpar grupo indisponível: " + t);
        }
    }

    static void attach(final ViewGroup container) throws Throwable {
        View col = ShadeSheet.findId(container, "collapse_button", 0);
        if (!(col instanceof ImageView) || !(col.getParent() instanceof RelativeLayout)) return;
        RelativeLayout hdr = (RelativeLayout) col.getParent();
        View info = ShadeSheet.findId(hdr, "group_info", 0);
        if (false && Prefs.groupClear && hdr.getTag(TAG) == null) {
            // o botão de recolher vira o "x" (limpa só o grupo); tocar no nome do grupo recolhe
            final ImageView c = (ImageView) col;
            final View.OnClickListener orig = clickOf(c);
            c.setImageDrawable(new XDrawable(hdr.getResources().getDisplayMetrics().density));
            c.setContentDescription("Limpar grupo");
            c.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    clear(container);
                }
            });
            if (orig != null) {
                if (info != null) info.setOnClickListener(orig);
                hdr.setOnClickListener(orig);
            }
            hdr.setTag(TAG, c);
            if (logs++ < 3) ElasticCard.log("limpar grupo: botão de recolher virou limpar (recolher " + (orig != null ? "no nome" : "sem ação original") + ")");
        }
        if (Prefs.groupVib && hdr.getTag(TAG + 1) == null) {
            hdr.setTag(TAG + 1, Boolean.TRUE);
            vibrance(hdr, info, col);
        }
    }

    static void rewire(Object inj) throws Throwable {
        final ViewGroup container = (ViewGroup) Refl.get(inj, "view");
        ImageView col = (ImageView) Refl.get(inj, "mCollapsedButton");
        TextView info = (TextView) Refl.get(inj, "mGroupInfo");
        View hdr = (View) Refl.get(inj, "mMiuiGroupHeader");
        if (container == null || col == null) return;
        View.OnClickListener collapse = (View.OnClickListener) Refl.get(container, "mHeaderClickListener");
        if (Prefs.groupClear) {
            col.setImageDrawable(new XDrawable(col.getResources().getDisplayMetrics().density));
            col.setContentDescription("Limpar grupo");
            col.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    clear(container);
                }
            });
            if (info != null && collapse != null) info.setOnClickListener(collapse);
            if (hdr != null && collapse != null) hdr.setOnClickListener(collapse);
            if (logs++ < 3) ElasticCard.log("limpar grupo: botão de recolher virou limpar");
        }
        if (Prefs.groupVib && hdr != null && hdr.getTag(TAG + 1) != Boolean.TRUE) {
            hdr.setTag(TAG + 1, Boolean.TRUE);
            vibrance(hdr, info, col);
        }
    }

    static View.OnClickListener clickOf(View v) {
        try {
            java.lang.reflect.Method gl = View.class.getDeclaredMethod("getListenerInfo");
            gl.setAccessible(true);
            Object li = gl.invoke(v);
            java.lang.reflect.Field f = li.getClass().getDeclaredField("mOnClickListener");
            f.setAccessible(true);
            return (View.OnClickListener) f.get(li);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Some o grupo inteiro (só ele), do mesmo jeito que arrastar o grupo para o lado. */
    static void clear(ViewGroup container) {
        try {
            Object row = Refl.get(container, "mContainingNotification");
            if (row == null) return;
            ((View) row).performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            java.lang.reflect.Method pd = Refl.method(row.getClass(), "performDismiss");
            java.util.List kids = (java.util.List) Refl.get(container, "mAttachedChildren");
            int n = 0;
            if (kids != null) for (Object k : new java.util.ArrayList(kids)) {
                try {
                    pd.invoke(k, Boolean.FALSE);
                    n++;
                } catch (Throwable ignored) {
                }
            }
            pd.invoke(row, Boolean.FALSE);
            ElasticCard.log("limpar grupo: " + n + " notificações limpas");
        } catch (Throwable t) {
            ElasticCard.log("limpar grupo falhou: " + t);
        }
    }

    static void vibrance(View container, View... members) {
        try {
            MiBlend.container(container, 0);
            for (View v : members) if (v != null) MiBlend.member(v);
            if (logs++ < 6) ElasticCard.log("vibrance do grupo aplicada (" + (Kg.memberArgs != null ? "cores do bloqueio" : "padrão") + ")");
        } catch (Throwable t) {
            if (logs++ < 6) ElasticCard.log("vibrance do grupo: " + t);
        }
    }

    /** "x" no mesmo traço do botão de recolher. */
    static final class XDrawable extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float d;

        XDrawable(float d) {
            this.d = d;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f * d);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xFFFFFFFF);
        }

        public int getIntrinsicWidth() {
            return Math.round(16 * d);
        }

        public int getIntrinsicHeight() {
            return Math.round(16 * d);
        }

        public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float k = Math.min(b.width(), b.height()) * 0.32f, cx = b.exactCenterX(), cy = b.exactCenterY();
            c.drawLine(cx - k, cy - k, cx + k, cy + k, p);
            c.drawLine(cx - k, cy + k, cx + k, cy - k, p);
        }

        public void setAlpha(int a) {
            p.setAlpha(a);
        }

        public void setColorFilter(ColorFilter cf) {
            p.setColorFilter(cf);
        }

        public void setTintList(android.content.res.ColorStateList t) {
            if (t != null) p.setColor(t.getDefaultColor());
        }

        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
