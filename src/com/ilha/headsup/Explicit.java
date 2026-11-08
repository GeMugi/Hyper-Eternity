package com.ilha.headsup;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.reflect.Method;

/**
 * Selo "E" de conteúdo explícito antes do título da música (como no Spotify e no iOS): um
 * quadradinho cinza-claro de cantos arredondados com o "E" escuro. Vem da marca que o app de
 * música informa (android.media.IS_EXPLICIT, a mesma do Android Auto).
 */
final class Explicit {
    private Explicit() {}

    static final int TAG = 0x7e1a7020;
    static int logs;

    static boolean of(MediaController mc) {
        try {
            MediaMetadata md = mc == null ? null : mc.getMetadata();
            return md != null && md.getLong("android.media.IS_EXPLICIT") == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    /** O selo, do tamanho da altura das maiúsculas do texto. */
    static final class Badge extends Drawable {
        final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG), letter = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        final android.graphics.Path cut = new android.graphics.Path(), glyph = new android.graphics.Path();
        final Paint vib = new Paint(Paint.ANTI_ALIAS_FLAG);
        android.view.View host;   // com host: vibrance como o artista (Prefs.mediaVibPlus)
        int size;

        Badge(float textSize, int textColor) {
            size = Math.round(textSize * 0.72f);
            setColor(textColor);
            letter.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            letter.setTextAlign(Paint.Align.CENTER);
            letter.setColor(0xFF121214);
        }

        void setColor(int textColor) {
            // cinza-claro levemente transparente, do tom do texto
            box.setColor((textColor & 0x00FFFFFF) | 0xB8000000);
        }

        public int getIntrinsicWidth() { return size; }
        public int getIntrinsicHeight() { return size; }

        public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float x = b.left + (b.width() - s) / 2f, y = b.top + (b.height() - s) / 2f;
            r.set(x, y, x + s, y + s);
            letter.setTextSize(s * 0.74f);
            float by = r.centerY() - (letter.ascent() + letter.descent()) / 2f;
            if (host != null && Prefs.mediaVibPlus) {
                // vibrance: o quadrado soma luz (escuro) ou escurece (claro) o que está atrás, e o "E" é
                // um recorte nele, mostrando o fundo do cartão como no artista
                boolean night = MediaCut.night(host);
                cut.reset();
                cut.addRoundRect(r, s * 0.16f, s * 0.16f, android.graphics.Path.Direction.CW);
                glyph.reset();
                letter.getTextPath("E", 0, 1, r.centerX(), by, glyph);
                cut.op(glyph, android.graphics.Path.Op.DIFFERENCE);
                vib.setBlendMode(MediaCut.mode(night));
                vib.setColor(MediaCut.color(night, Prefs.mvExtra));
                c.drawPath(cut, vib);
                return;
            }
            c.drawRoundRect(r, s * 0.16f, s * 0.16f, box);
            c.drawText("E", r.centerX(), by, letter);
        }

        public void setAlpha(int a) {}
        public void setColorFilter(ColorFilter cf) {}
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ---------------------------------------------------------------- player do HyperOS

    static void install(ClassLoader cl) {
        try {
            Class c = Class.forName("com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl", false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!"attach".equals(m.getName()) || m.getParameterTypes().length != 1) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object tv = Refl.get(p.args[0], "titleText");
                            if (tv instanceof TextView) watch((TextView) tv);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        } catch (Throwable t) {
            ElasticCard.log("selo E: player do HyperOS indisponível " + t);
        }
    }

    static void watch(final TextView tv) {
        if (tv.getTag(TAG) != null) return;
        final Badge badge = new Badge(tv.getTextSize(), tv.getCurrentTextColor());
        badge.host = tv;
        tv.setTag(TAG, badge);
        tv.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            long at;
            CharSequence lastText;
            boolean shown;

            public boolean onPreDraw() {
                long now = android.os.SystemClock.uptimeMillis();
                CharSequence t = tv.getText();
                if (now - at < 500 && t == lastText) return true;
                at = now;
                lastText = t;
                boolean want = false;
                if (Prefs.explicitE) {
                    MediaController mc = IosPlayer.controller(tv.getContext());
                    if (of(mc)) {
                        CharSequence mt = mc.getMetadata().getText(MediaMetadata.METADATA_KEY_TITLE);
                        want = mt == null || t == null || t.toString().trim().equals(mt.toString().trim());
                    }
                }
                if (want != shown) {
                    shown = want;
                    if (want) {
                        badge.size = Math.round(tv.getTextSize() * 0.72f);
                        badge.setColor(tv.getCurrentTextColor());
                        badge.setBounds(0, 0, badge.size, badge.size);
                        tv.setCompoundDrawablePadding(Math.round(6 * tv.getResources().getDisplayMetrics().density));
                    }
                    Drawable[] d = tv.getCompoundDrawablesRelative();
                    tv.setCompoundDrawablesRelative(want ? badge : null, d[1], d[2], d[3]);
                    if (logs++ < 3) ElasticCard.log("selo E: " + (want ? "explícito" : "sem selo") + " em '" + t + "'");
                    return false;
                }
                return true;
            }
        });
    }
}
