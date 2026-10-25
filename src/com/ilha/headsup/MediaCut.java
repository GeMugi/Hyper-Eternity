package com.ilha.headsup;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewParent;
import android.widget.SeekBar;
import android.widget.TextView;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.reflect.Method;

/**
 * Player da gaveta: o trilho da barra de progresso e os números do tempo clareiam o cartão
 * (somam luz à cor do vidro, numa camada própria do cartão), como o brilho das notificações. A parte já
 * tocada da barra continua sólida.
 */
final class MediaCut {
    private MediaCut() {}

    static final int TAG = 0x7e1a7011;
    static int logs;

    static void install(ClassLoader cl) {
        try {
            Class c = Class.forName("com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl", false, cl);
            Method attach = null;
            for (Method m : c.getDeclaredMethods()) if ("attach".equals(m.getName()) && m.getParameterTypes().length == 1) attach = m;
            if (attach == null) {
                ElasticCard.log("barra vazada: não encontrei o player");
                return;
            }
            XposedBridge.hookMethod(attach, new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Prefs.refresh();
                        if (!Prefs.mediaCut) return;
                        apply(p.args[0]);
                    } catch (Throwable t) {
                        if (logs++ < 4) ElasticCard.log("barra vazada: erro " + t);
                    }
                }
            });
            ElasticCard.log("barra vazada: instalado");
        } catch (Throwable t) {
            ElasticCard.log("barra vazada indisponível " + t);
        }
    }

    static void apply(Object holder) {
        Object sb = Refl.get(holder, "seekBar");
        if (!(sb instanceof SeekBar)) {
            if (logs++ < 4) ElasticCard.log("barra vazada: sem seekBar no player");
            return;
        }
        SeekBar bar = (SeekBar) sb;
        if (bar.getTag(TAG) != null) return;
        bar.setTag(TAG, Boolean.TRUE);
        float d = bar.getResources().getDisplayMetrics().density;
        // o cartão numa camada própria (assim ficou o desfoque na 5.8.7): a barra e os tempos
        // são desenhados em "diferença" com o cartão, ou seja, mostram o fundo do player
        // invertido. Sobre vidro claro ficam escuros, sobre vidro escuro ficam claros, e
        // acompanham a cor do cartão: sempre contrastados.
        View card = bar;
        ViewParent vp = bar.getParent();
        while (vp instanceof View) {
            card = (View) vp;
            if (card.getBackground() != null) break;
            vp = vp.getParent();
        }
        // sem camada própria no cartão: a camada refazia o desfoque do vidro a cada quadro (lag ao puxar a gaveta); a mistura age direto sobre o que já foi desenhado
        int n = 0;
        n += wrap(bar, d);
        final java.util.ArrayList<TextView> texts = new java.util.ArrayList<>();
        for (String f : new String[]{"elapsedTimeView", "totalTimeView", "scrubbingElapsedTimeView", "scrubbingTotalTimeView", "elapsedTime", "totalTime"}) {
            Object tv = Refl.get(holder, f);
            if (tv instanceof TextView) {
                texts.add((TextView) tv);
                n++;
            }
        }
        // a cada quadro confere o tema e as intensidades: troca sozinho entre claro e escuro
        bar.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            int lastKey = Integer.MIN_VALUE;

            public boolean onPreDraw() {
                refresh();
                // o HyperOS troca a barra ao mudar o tema: embrulha de novo quando trocar
                wrap(bar, bar.getResources().getDisplayMetrics().density);
                boolean night = night(bar);
                int key = (night ? 1 : 0) | (Prefs.mvText << 1) | (Prefs.mvTrack << 8) | (Prefs.mvPlayed << 15) | (Prefs.mediaCut ? 1 << 23 : 0);
                if (key == lastKey) return true;
                lastKey = key;
                for (TextView t : texts) {
                    if (Prefs.mediaCut) {
                        t.setAlpha(1f);
                        t.setTextColor(color(night, Prefs.mvText));
                        t.getPaint().setBlendMode(mode(night));
                    } else {
                        t.getPaint().setBlendMode(null);
                    }
                    t.invalidate();
                }
                bar.invalidate();
                return true;
            }
        });
        bar.invalidate();
        if (logs++ < 4) ElasticCard.log("barra vazada: ligada (" + n + " partes)");
    }

    /** Troca a barra inteira pela nossa (o HyperOS usa uma barra própria, não em camadas). */
    static int wrap(SeekBar bar, float d) {
        Drawable pd = bar.getProgressDrawable();
        if (pd instanceof Vib) return 0;
        if (logs < 6) ElasticCard.log("barra vazada: barra do sistema " + (pd == null ? null : pd.getClass().getName()));
        Rect r = pd != null ? pd.copyBounds() : null;
        Vib v = new Vib(bar, d);
        bar.setProgressDrawable(v);
        if (r != null && !r.isEmpty()) v.setBounds(r);
        int max = bar.getMax() - bar.getMin();
        v.setLevel(max > 0 ? Math.round(10000f * (bar.getProgress() - bar.getMin()) / max) : 0);
        return 1;
    }

    /** Barra no estilo iOS: trilho e parte tocada desenhados com a mistura "vibrancy". */
    static final class Vib extends Drawable {
        final View host;
        final float d;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Vib(View host, float d) {
            this.host = host;
            this.d = d;
        }

        public void draw(Canvas c) {
            Rect b = getBounds();
            float h = Math.min(b.height(), 4f * d);
            float cy = b.exactCenterY();
            RectF all = new RectF(b.left, cy - h / 2f, b.right, cy + h / 2f);
            boolean night = night(host);
            boolean on = Prefs.mediaCut;
            p.setBlendMode(on ? mode(night) : null);
            p.setColor(on ? color(night, Prefs.mvTrack) : 0x4DFFFFFF);
            c.drawRoundRect(all, h / 2f, h / 2f, p);
            float w = b.width() * getLevel() / 10000f;
            if (w > 0f) {
                p.setColor(on ? color(night, Prefs.mvPlayed) : 0xFFFFFFFF);
                c.drawRoundRect(new RectF(b.left, all.top, b.left + Math.max(w, h), all.bottom), h / 2f, h / 2f, p);
            }
        }

        protected boolean onLevelChange(int level) {
            invalidateSelf();
            return true;
        }

        public void setAlpha(int a) {
        }

        public void setColorFilter(ColorFilter cf) {
        }

        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    static long lastRefresh;

    static void refresh() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastRefresh < 1000) return;
        lastRefresh = now;
        Prefs.refresh();
    }

    static boolean night(View v) {
        return (v.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** "vibrancy" do iOS: no escuro soma luz ao vidro (plus-lighter), no claro multiplica (plus-darker). */
    static android.graphics.BlendMode mode(boolean night) {
        return night ? android.graphics.BlendMode.PLUS : android.graphics.BlendMode.MULTIPLY;
    }

    /** Intensidade 0–100 em cinza: no escuro, quanto de luz soma; no claro, quanto escurece. */
    static int color(boolean night, int pct) {
        int g = night ? Math.round(255f * pct / 100f) : Math.round(255f * (1f - pct / 100f));
        g = Math.max(0, Math.min(255, g));
        return 0xFF000000 | (g << 16) | (g << 8) | g;
    }

    /** Trilho. */
    static final class Track extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float d;

        final View host;

        Track(float d, View host) {
            this.d = d;
            this.host = host;
        }

        public void draw(Canvas c) {
            boolean night = night(host);
            if (Prefs.mediaCut) {
                p.setColor(color(night, Prefs.mvTrack));
                p.setBlendMode(mode(night));
            } else {
                p.setColor(0x4DFFFFFF);
                p.setBlendMode(null);
            }
            Rect b = getBounds();
            float h = Math.min(b.height(), 6f * d);
            float cy = b.exactCenterY();
            c.drawRoundRect(new RectF(b.left, cy - h / 2f, b.right, cy + h / 2f), h / 2f, h / 2f, p);
        }

        public void setAlpha(int a) {
        }

        public void setColorFilter(ColorFilter cf) {
        }

        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Parte tocada: desenhada como recorte da pílula (mostra o cartão atrás). */
    static final class Cut extends Drawable implements Drawable.Callback {
        final Drawable orig;
        final Paint p = new Paint();

        final View host;

        Cut(Drawable orig, View host) {
            this.orig = orig;
            this.host = host;
            orig.setCallback(this);
        }

        public void draw(Canvas c) {
            if (!Prefs.mediaCut) {
                orig.setTintList(null);
                orig.draw(c);
                return;
            }
            boolean night = night(host);
            p.setBlendMode(mode(night));
            Rect b = getBounds();
            int s = c.saveLayer(b.left, b.top, b.right, b.bottom, p);
            orig.setTint(color(night, Prefs.mvPlayed));
            orig.draw(c);
            c.restoreToCount(s);
        }

        protected void onBoundsChange(Rect b) {
            orig.setBounds(b);
        }

        protected boolean onLevelChange(int level) {
            orig.setLevel(level);
            invalidateSelf();
            return true;
        }

        protected boolean onStateChange(int[] st) {
            return orig.setState(st);
        }

        public boolean isStateful() {
            return orig.isStateful();
        }

        public void invalidateDrawable(Drawable who) {
            invalidateSelf();
        }

        public void scheduleDrawable(Drawable who, Runnable r, long when) {
            scheduleSelf(r, when);
        }

        public void unscheduleDrawable(Drawable who, Runnable r) {
            unscheduleSelf(r);
        }

        public void setAlpha(int a) {
        }

        public void setColorFilter(ColorFilter cf) {
        }

        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
