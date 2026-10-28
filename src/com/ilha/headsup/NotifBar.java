package com.ilha.headsup;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * iOS: depois que a heads-up recua, fica uma barrinha logo abaixo da barra de status, sob o
 * relógio, avisando que há notificações. Janela própria (por cima de tudo, sem toque), então
 * aparece em qualquer tela, inclusive no launcher. Some quando a central abre ou outra heads-up chega.
 */
final class NotifBar extends View {
    static NotifBar bar;
    static WindowManager wm;
    static Handler h;
    static int logs;

    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    float a, target;
    final float d;

    NotifBar(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    static void show() {
        try {
            Prefs.refresh();
            if (!Prefs.barOn) return;
            View clock = StatusClock.leftClock();
            Context ctx = clock != null ? clock.getContext() : null;
            if (ctx == null) {
                Class at = Class.forName("android.app.ActivityThread");
                ctx = (Context) at.getMethod("currentApplication").invoke(null);
            }
            if (ctx == null) return;
            float dd = ctx.getResources().getDisplayMetrics().density;
            int sb = 0;
            int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id != 0) sb = ctx.getResources().getDimensionPixelSize(id);
            int cx;
            if (clock != null) {
                int[] l = new int[2];
                clock.getLocationOnScreen(l);
                cx = l[0] + clock.getWidth() / 2;
            } else cx = Math.round(48 * dd);
            Prefs.refresh();
            cx += Math.round(Prefs.barDx * dd);
            int w = Math.round((Prefs.barW + 12) * dd), hh = Math.round((Prefs.barH + 10) * dd);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, hh, 2015 /* TYPE_SECURE_SYSTEM_OVERLAY */,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = cx - w / 2;
            lp.y = sb + Math.round(Prefs.barDy * dd) - hh / 2 + Math.round(Prefs.barH * dd / 2f);
            lp.setTitle("HyperEternityBar");
            try { lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS; } catch (Throwable ignored) {}
            if (wm == null) wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (bar == null) {
                bar = new NotifBar(ctx);
                wm.addView(bar, lp);
            } else wm.updateViewLayout(bar, lp);
            bar.target = 1f;
            bar.invalidate();
            if (h == null) h = new Handler(Looper.getMainLooper());
            h.removeCallbacks(WATCH);
            h.postDelayed(WATCH, 500);
            if (logs++ < 3) ElasticCard.log("heads-up iOS: barrinha de notificações abaixo do relógio");
        } catch (Throwable t) {
            if (logs++ < 5) ElasticCard.log("barrinha: " + t);
        }
    }

    static void hide(boolean now) {
        if (bar == null) return;
        if (now) {
            try { wm.removeView(bar); } catch (Throwable ignored) {}
            bar = null;
            return;
        }
        bar.target = 0f;
        bar.invalidate();
    }

    static final Runnable WATCH = new Runnable() {
        public void run() {
            if (bar == null) return;
            if (ShadeSheet.on || ShadeSheet.expanded()) { hide(false); return; }   // central aberta: já viu
            h.postDelayed(this, 500);
        }
    };

    protected void onDraw(Canvas c) {
        a += (target - a) * 0.2f;
        if (Math.abs(target - a) > 0.01f) invalidate();
        else if (target == 0f) { post(new Runnable() { public void run() { hide(true); } }); return; }
        float w = Prefs.barW * d * (0.4f + 0.6f * a), hh = Prefs.barH * d;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        p.setColor(0xFFFFFFFF);
        p.setAlpha(Math.round(255 * Prefs.barA / 100f * a));
        c.drawRoundRect(cx - w / 2f, cy - hh / 2f, cx + w / 2f, cy + hh / 2f, hh / 2f, hh / 2f, p);
    }
}
