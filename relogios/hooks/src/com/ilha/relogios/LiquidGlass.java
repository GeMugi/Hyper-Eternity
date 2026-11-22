package com.ilha.relogios;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Efeito "vidro líquido" nos números dos relógios All in One, desenhado por nós.
 *
 * O vidro do HyperOS 4 depende do motor gráfico dele (que o HyperOS 3 não tem). Aqui cada número
 * vira uma lente: por dentro, o papel de parede desfocado e levemente ampliado (refração), com um
 * véu claro; na borda interna, um reflexo de luz que vem do alto à esquerda e um retorno mais fraco
 * embaixo à direita; por fora, um contorno quase invisível que separa o número de fundos claros.
 * Tudo com o mesmo contorno que o relógio usa para desenhar o número (o caminho do texto).
 */
final class LiquidGlass {
    private LiquidGlass() {}

    static volatile Bitmap blur;
    static Field fText, fPath, fColon, fMinute, fX, fY, fSplit, fColonA, fColonW, fAlpha, fInfo;
    static Method mHandle, mDisplay, mIsAod;
    static boolean ready, failed;
    static int logs;

    static final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    static final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final RectF bounds = new RectF();
    static final Matrix m = new Matrix();
    static final int[] loc = new int[2];

    static void log(String s) {
        if (logs++ < 12) com.ilha.relogios.xp.XposedBridge.log("[Relógios HOS4] vidro líquido: " + s);
    }

    /** Papel de parede já reduzido (do Fx): uma cópia bem desfocada para o "dentro" do vidro. */
    static void prepare(Bitmap src) {
        try {
            int w = Math.max(8, src.getWidth() / 6), h = Math.max(8, src.getHeight() / 6);
            Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
            int[] px = new int[w * h];
            small.getPixels(px, 0, w, 0, 0, w, h);
            int r = Math.max(2, w / 40);
            for (int i = 0; i < 3; i++) boxBlur(px, w, h, r);
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(px, 0, w, 0, 0, w, h);
            blur = out;
            log("fundo do vidro pronto (" + w + "x" + h + ", raio " + r + ")");
        } catch (Throwable t) {
            log("fundo: " + t);
        }
    }

    static void boxBlur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length];
        pass(px, tmp, w, h, r, true);
        pass(tmp, px, w, h, r, false);
    }

    static void pass(int[] in, int[] out, int w, int h, int r, boolean horiz) {
        int n = horiz ? w : h, lines = horiz ? h : w;
        int div = r * 2 + 1;
        for (int l = 0; l < lines; l++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[idx(Math.min(n - 1, Math.max(0, i)), l, w, horiz)];
                sa += c >>> 24; sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
            }
            for (int i = 0; i < n; i++) {
                out[idx(i, l, w, horiz)] = ((sa / div) << 24) | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int a = in[idx(Math.min(n - 1, i + r + 1), l, w, horiz)];
                int b = in[idx(Math.max(0, i - r), l, w, horiz)];
                sa += (a >>> 24) - (b >>> 24);
                sr += ((a >> 16) & 255) - ((b >> 16) & 255);
                sg += ((a >> 8) & 255) - ((b >> 8) & 255);
                sb += (a & 255) - (b & 255);
            }
        }
    }

    static int idx(int i, int l, int w, boolean horiz) {
        return horiz ? l * w + i : i * w + l;
    }

    static boolean init(Class<?> tv) {
        if (ready) return true;
        if (failed) return false;
        try {
            fPath = f(tv, "mTextPath");
            fColon = f(tv, "mColonPath");
            fMinute = f(tv, "mMinuteOnlyPath");
            fX = f(tv, "mDrawTextX");
            fY = f(tv, "mDrawTextY");
            fSplit = f(tv, "mColonSplitIndex");
            fColonA = f(tv, "mColonAlpha");
            fColonW = f(tv, "mColonAdvanceWidth");
            fAlpha = f(tv, "fontAlpha");
            try { fInfo = f(tv, "clockStyleInfo"); } catch (Throwable ignored) {}
            try { fText = f(tv, "mText"); } catch (Throwable ignored) {}
            for (Class<?> k = tv; k != null && mHandle == null; k = k.getSuperclass()) {
                try {
                    mHandle = k.getDeclaredMethod("handleParams");
                    mHandle.setAccessible(true);
                } catch (Throwable ignored) {}
            }
            Class<?> info = Class.forName("com.miui.clock.module.ClockStyleInfo", false, tv.getClassLoader());
            mDisplay = info.getMethod("getDisplayType");
            mIsAod = info.getMethod("isAODType", int.class);
            ready = true;
            log("pronto");
            return true;
        } catch (Throwable t) {
            failed = true;
            log("indisponível: " + t);
            return false;
        }
    }

    static Field f(Class<?> c, String n) throws NoSuchFieldException {
        Field x = c.getDeclaredField(n);
        x.setAccessible(true);
        return x;
    }

    /** Desenha o número como vidro. false: deixa o relógio desenhar do jeito dele. */
    static boolean draw(View tv, Canvas c) {
        if (!init(tv.getClass())) return false;
        try {
            if (fInfo != null) {
                Object info = fInfo.get(tv);
                if (info != null && ((Boolean) mIsAod.invoke(null, mDisplay.invoke(info))).booleanValue()) {
                    Lens.off(tv);
                    return false;
                }
            }
            // o próprio relógio refaz aqui o contorno do quadro (peso, largura, altura da animação)
            if (mHandle != null) mHandle.invoke(tv);
            if (Lens.draw(tv, c)) return true;
            Bitmap bg = blur;
            if (bg == null) {
                Fx.loadWall(tv.getContext());
                return false;
            }
            float alpha = fAlpha.getFloat(tv);
            float x = fX.getFloat(tv), y = fY.getFloat(tv);
            int split = fSplit.getInt(tv);
            float colonA = fColonA.getFloat(tv);
            tv.getLocationOnScreen(loc);
            int save = c.save();
            c.translate(x, y);
            if (split <= 0 || colonA >= 1f) {
                lens(tv, c, (Path) fPath.get(tv), alpha, bg, x, y);
            } else {
                if (colonA > 0f) lens(tv, c, (Path) fColon.get(tv), alpha * colonA, bg, x, y);
                float cw = fColonW.getFloat(tv);
                c.translate(cw, 0f);
                lens(tv, c, (Path) fMinute.get(tv), alpha, bg, x + cw, y);
            }
            c.restoreToCount(save);
            return true;
        } catch (Throwable t) {
            log("desenho: " + t);
            return false;
        }
    }

    static void lens(View tv, Canvas c, Path p, float alpha, Bitmap bg, float ox, float oy) {
        if (p == null || p.isEmpty() || alpha <= 0f) return;
        p.computeBounds(bounds, true);
        float d = tv.getResources().getDisplayMetrics().density;
        float hgt = Math.max(1f, bounds.height());
        int a = Math.round(255 * Math.min(1f, alpha));

        // dentro: o fundo desfocado alinhado à tela e ampliado em torno do número (refração)
        int sw = tv.getResources().getDisplayMetrics().widthPixels, sh = tv.getResources().getDisplayMetrics().heightPixels;
        float sc = Math.max(sw / (float) bg.getWidth(), sh / (float) bg.getHeight());
        m.reset();
        m.setScale(sc, sc);
        m.postTranslate((sw - bg.getWidth() * sc) / 2f - loc[0] - ox, (sh - bg.getHeight() * sc) / 2f - loc[1] - oy);
        m.postScale(1.12f, 1.12f, bounds.centerX(), bounds.centerY());
        BitmapShader bs = new BitmapShader(bg, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        bs.setLocalMatrix(m);
        body.setShader(bs);
        body.setAlpha(a);

        int save = c.save();
        c.clipPath(p);
        c.drawPath(p, body);

        // véu: claro em cima, quase nada no meio, um pouco de volta embaixo
        veil.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                new int[]{argb(a * 0x55 / 255), argb(a * 0x14 / 255), argb(a * 0x08 / 255), argb(a * 0x22 / 255)},
                new float[]{0f, 0.38f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(p, veil);

        // brilho suave junto à borda (o vidro fica mais claro onde é mais "grosso")
        float e = Math.max(1.4f * d, hgt * 0.018f);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeJoin(Paint.Join.ROUND);
        glow.setStrokeWidth(e * 5f);
        glow.setShader(null);
        glow.setColor(argb(a * 0x1c / 255));
        c.drawPath(p, glow);

        // reflexo na borda interna: luz do alto à esquerda, retorno fraco embaixo à direita
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeJoin(Paint.Join.ROUND);
        rim.setStrokeWidth(e * 2f);
        rim.setShader(new LinearGradient(bounds.left, bounds.top, bounds.right, bounds.bottom,
                new int[]{argb(a * 0xf0 / 255), argb(a * 0x40 / 255), argb(a * 0x10 / 255), argb(a * 0x30 / 255), argb(a * 0xa8 / 255)},
                new float[]{0f, 0.28f, 0.5f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(p, rim);
        c.restoreToCount(save);

        // fora: contorno quase invisível, para o número não sumir em fundos claros
        save = c.save();
        c.clipOutPath(p);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeJoin(Paint.Join.ROUND);
        edge.setStrokeWidth(Math.max(1f, d * 1.2f));
        edge.setColor(((a * 0x26 / 255) << 24));
        c.drawPath(p, edge);
        c.restoreToCount(save);
    }

    static int argb(int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | 0xffffff;
    }
}
