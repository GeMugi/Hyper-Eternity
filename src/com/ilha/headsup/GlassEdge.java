package com.ilha.headsup;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Teste "vidro do HyperOS 4" (imitação): cada cartão ganha uma borda de luz em degradê (mais forte no
 * canto de cima), um brilho suave no topo e um leve tom colorido nas pontas, desenhados por cima do
 * fundo do próprio cartão. Não há refração do fundo (isso é do sistema gráfico do HyperOS 4).
 */
final class GlassEdge extends Drawable {
    static final int TAG = 0x7e1a7031;

    static void row(View row) {
        Object t = row.getTag(TAG);
        if (!Prefs.glassEdge) {
            if (t instanceof GlassEdge) {
                row.getOverlay().remove((Drawable) t);
                row.setTag(TAG, null);
            }
            return;
        }
        if (t instanceof GlassEdge) return;
        GlassEdge g = new GlassEdge(row);
        row.getOverlay().add(g);
        row.setTag(TAG, g);
        row.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t2, int r, int b, int ol, int ot, int or, int ob) {
                Object x = v.getTag(TAG);
                if (x instanceof GlassEdge) ((GlassEdge) x).setBounds(0, 0, r - l, b - t2);
            }
        });
        g.setBounds(0, 0, row.getWidth(), row.getHeight());
    }

    final View row;
    final float d;
    final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG), tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF r = new RectF();
    int lastW = -1, lastH = -1, lastI = -1;

    GlassEdge(View row) {
        this.row = row;
        d = row.getResources().getDisplayMetrics().density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.4f * d);
        tint.setStyle(Paint.Style.STROKE);
        tint.setStrokeWidth(2.2f * d);
    }

    /** Área do cartão: o fundo do HyperOS (a linha pode ser mais alta que o cartão visível). */
    boolean card(RectF out) {
        try {
            Object bg = Refl.get(row, "mBackgroundNormal");
            int h = row.getHeight();
            try {
                Object ah = Refl.method(row.getClass(), "getActualHeight").invoke(row);
                if (ah instanceof Integer && (Integer) ah > 0) h = (Integer) ah;
            } catch (Throwable ignored) {
            }
            if (bg instanceof View && ((View) bg).getVisibility() != View.VISIBLE) return false;
            float l = 0, t = 0, rr = row.getWidth();
            if (bg instanceof View) {
                View b = (View) bg;
                l = b.getLeft() + b.getPaddingLeft();
                rr = b.getRight() - b.getPaddingRight();
                t = b.getTop() + b.getPaddingTop();
            }
            out.set(l, t, rr, Math.max(t + 1, h));
            return out.width() > 10 && out.height() > 10;
        } catch (Throwable e) {
            return false;
        }
    }

    public void draw(Canvas c) {
        if (!Prefs.glassEdge || !card(r)) return;
        float rad = 24 * d;
        int w = Math.round(r.width()), h = Math.round(r.height()), in = Prefs.glassEdgeN;
        if (w != lastW || h != lastH || in != lastI) {
            lastW = w;
            lastH = h;
            lastI = in;
            int a = Math.round(2.4f * in);   // 0..240
            rim.setShader(new LinearGradient(r.left, r.top, r.right * 0.6f, r.bottom, new int[]{(a << 24) | 0xFFFFFF, ((a / 5) << 24) | 0xFFFFFF, ((a / 2) << 24) | 0xFFFFFF},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            sheen.setShader(new LinearGradient(0, r.top, 0, r.top + Math.min(h, 46 * d), ((a / 4) << 24) | 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            tint.setShader(new LinearGradient(r.left, r.top, r.right, r.top, new int[]{((a / 6) << 24) | 0x66CCFF, 0x00FFFFFF, ((a / 6) << 24) | 0xFF99CC},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        }
        // brilho do topo (dentro do cartão)
        c.save();
        android.graphics.Path p = new android.graphics.Path();
        p.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
        c.clipPath(p);
        c.drawRect(r.left, r.top, r.right, r.top + Math.min(h, 46 * d), sheen);
        c.restore();
        // tom colorido nas pontas + borda de luz
        float k = tint.getStrokeWidth() / 2f;
        c.drawRoundRect(r.left + k, r.top + k, r.right - k, r.bottom - k, rad - k, rad - k, tint);
        k = rim.getStrokeWidth() / 2f;
        c.drawRoundRect(r.left + k, r.top + k, r.right - k, r.bottom - k, rad - k, rad - k, rim);
    }

    public void setAlpha(int a) {
    }

    public void setColorFilter(ColorFilter cf) {
    }

    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
