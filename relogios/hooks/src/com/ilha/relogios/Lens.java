package com.ilha.relogios;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewTreeObserver;
import java.util.WeakHashMap;

/**
 * Vidro com refração nos números, com o mesmo modelo de lente dos cartões de notificação.
 *
 * Tudo acontece na placa de vídeo, a cada quadro, sobre o próprio desenho do número: o relógio
 * desenha o número (branco) e um efeito de renderização na view separa esse desenho em duas partes
 * — a cobertura nítida e uma cópia desfocada (a "altura", que dá a rampa da borda e a direção da
 * normal). O shader da lente lê as duas e o papel de parede do bloqueio (outra janela, entra por uma
 * cópia posicionada como na tela). Como a forma vem do desenho do quadro atual, o vidro acompanha
 * exatamente qualquer animação do relógio (peso, largura, encolher, subir).
 */
final class Lens {
    private Lens() {}

    static final String SRC =
            "uniform shader img;\n" +          // R = cobertura nítida, G = cobertura desfocada
            "uniform shader wall;\n" +
            "uniform shader soft;\n" +
            "uniform float3x3 M;\n" +
            "uniform float depth;\n" +
            "const float IOR = 1.5;\n" +
            "const float EDGE_POW = 2.0;\n" +
            "float H(float2 p) { return float(img.eval(p).g); }\n" +
            "float2 world(float2 q) { float3 v = M * float3(q, 1.0); return v.xy; }\n" +
            "half3 pick(float2 q, float fr) {\n" +
            "  float2 w = world(q);\n" +
            "  return mix(half3(wall.eval(w).rgb), half3(soft.eval(w).rgb), half(fr));\n" +
            "}\n" +
            "half3 hsvv(half3 c, half v) { return c + (half3(1.0) - c) * clamp(v, 0.0, 1.0); }\n" +
            "half lightCone(half3 n, half3 L, half k) {\n" +
            "  half d = dot(n, L);\n" +
            "  half f = clamp(1.0 - sqrt(max(1.0 - d, 0.0)) * k, 0.0, 1.0);\n" +
            "  return max(d, 0.0) * f;\n" +
            "}\n" +
            "half4 main(float2 p) {\n" +
            "  half4 mk = img.eval(p);\n" +
            "  float cov = float(mk.r);\n" +
            "  if (cov < 0.004) return half4(0.0);\n" +
            "  float h = float(mk.g);\n" +
            "  float w = clamp((h - 0.5) * 2.0, 0.0, 1.0);\n" +          // 0 na borda, 1 no miolo
            "  float omw = 1.0 - w;\n" +
            "  float nz = 1.0 - pow(omw, 1.0 / EDGE_POW);\n" +
            "  float2 g = float2(H(p + float2(1.5, 0)) - H(p - float2(1.5, 0)), H(p + float2(0, 1.5)) - H(p - float2(0, 1.5)));\n" +
            "  float2 n2 = length(g) > 0.0001 ? -normalize(g) : float2(0.0);\n" +
            "  half3 N = omw <= 0.0 ? half3(0, 0, 1) : half3(half2(n2 * sqrt(max(0.0, 1.0 - nz * nz))), half(nz));\n" +
            "  half3 LN = half3(N.x, -N.y, N.z);\n" +
            "  half3 L = normalize(half3(-0.45, 0.6, 0.65));\n" +
            "  half l1 = lightCone(LN, L, 0.9);\n" +
            "  half l2 = lightCone(LN, L * half3(-1, -1, 1), 0.9);\n" +
            "  half spec = half(omw) * (l1 * 0.85 + l2 * 0.4);\n" +   // reflexo fino na borda

            // o mesmo material dos cartões: o fundo desfocado no miolo, a lente (refração com desvio
            // de cor) só na faixa da borda, e a luz de cima à esquerda
            "  float edge = depth;\n" +
            "  float thick = depth * 1.35;\n" +
            "  float fr = 0.55 * w + 0.1;\n" +
            "  float rt = mix((thick - edge) * 2.0, thick * 2.0, w) * float(omw);\n" +
            "  half3 col;\n" +
            "  col.r = pick(p + float2(refract(half3(0, 0, -1), N, half(1.0 / (IOR - 0.02))).xy) * rt, fr).r;\n" +
            "  col.g = pick(p + float2(refract(half3(0, 0, -1), N, half(1.0 / IOR)).xy) * rt, fr).g;\n" +
            "  col.b = pick(p + float2(refract(half3(0, 0, -1), N, half(1.0 / (IOR + 0.03))).xy) * rt, fr).b;\n" +
            "  if (omw > 0.0) {\n" +
            "    float2 rxy = 2.0 * float(N.z) * float2(N.xy);\n" +
            "    half3 refl = pick(p + rxy * (depth * 0.8 * omw), 0.6);\n" +
            "    col = mix(col, refl, half((1.0 - nz) * 0.35));\n" +
            "  }\n" +
            "  half lum = dot(col, half3(0.299, 0.587, 0.114));\n" +
            "  col = hsvv(col, 0.10 + spec * 1.6 * (0.35 + 0.65 * (1.0 - lum)));\n" +   // fosco claro + reflexo
            "  half a = half(cov);\n" +
            "  return half4(clamp(col, 0.0, 1.0) * a, a);\n" +
            "}\n";

    static boolean failed;
    static Boolean lastHmc;
    static int logs;

    static final class St {
        RuntimeShader rs;
        String key;
        boolean on;
        boolean watching;
        float depth;
    }

    static final WeakHashMap<View, St> states = new WeakHashMap<>();
    static final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final float[] mv = new float[9];
    static final android.graphics.RectF rb = new android.graphics.RectF();

    static void log(String s) {
        if (logs++ < 12) com.ilha.relogios.xp.XposedBridge.log("[Relógios HOS4] vidro (refração): " + s);
    }

    static void off(View tv) {
        St s = states.get(tv);
        if (s == null || !s.on) return;
        try { tv.setRenderEffect(null); } catch (Throwable ignored) {}
        s.on = false;
        s.key = null;
    }

    static void offAll() {
        for (View v : new java.util.ArrayList<View>(states.keySet())) if (v != null) off(v);
    }

    /** true: o número foi desenhado (em branco) e o efeito da lente cuida da aparência. */
    static boolean draw(final View tv, Canvas c) {
        if (failed) return false;
        try {
            if (tv.getWidth() <= 0 || tv.getHeight() <= 0) return false;
            BitmapShader[] ws = wall(tv);
            if (ws == null) return false;
            St s = states.get(tv);
            if (s == null) states.put(tv, s = new St());
            // o número em branco puro: a forma do quadro atual
            float a = LiquidGlass.fAlpha.getFloat(tv);
            float x = LiquidGlass.fX.getFloat(tv), y = LiquidGlass.fY.getFloat(tv);
            int split = LiquidGlass.fSplit.getInt(tv);
            float colonA = LiquidGlass.fColonA.getFloat(tv);
            white.setColor(0xffffffff);
            white.setStyle(Paint.Style.FILL);
            int save = c.save();
            c.translate(x, y);
            Path main;
            if (split <= 0 || colonA >= 1f) {
                main = (Path) LiquidGlass.fPath.get(tv);
                white.setAlpha(Math.round(255 * clamp(a)));
                c.drawPath(main, white);
            } else {
                if (colonA > 0f) {
                    white.setAlpha(Math.round(255 * clamp(a * colonA)));
                    c.drawPath((Path) LiquidGlass.fColon.get(tv), white);
                }
                c.translate(LiquidGlass.fColonW.getFloat(tv), 0f);
                main = (Path) LiquidGlass.fMinute.get(tv);
                white.setAlpha(Math.round(255 * clamp(a)));
                c.drawPath(main, white);
            }
            c.restoreToCount(save);
            main.computeBounds(rb, true);
            effect(tv, s, ws, rb.height());
            watch(tv, s);
            return true;
        } catch (Throwable t) {
            Throwable e = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            log("falhou: " + e);
            failed = true;
            off(tv);
            return false;
        }
    }

    /** A cor que o relógio usaria sem o vidro (a do editor/paleta, que acompanha o HMC). */
    static int tintOf(View tv) {
        try {
            Paint p = (Paint) Fx.fPaint.get(tv);
            if (p != null) return p.getColor();
        } catch (Throwable ignored) {}
        return 0xffffffff;
    }

    static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    /** Liga (ou atualiza) o efeito quando a posição na tela ou o tamanho do número mudam. */
    static void effect(View tv, St s, BitmapShader[] ws, float glyphH) {
        float dp = tv.getResources().getDisplayMetrics().density;
        // profundidade da borda: proporcional à altura do número (arredondada: não refaz por meio pixel)
        float depth = Math.round(Math.max(4f * dp, Math.min(12f * dp, glyphH * 0.04f)));
        Matrix g = new Matrix();
        tv.transformMatrixToGlobal(g);
        g.getValues(mv);
        String key = depth + "#" + java.util.Arrays.toString(mv) + "#" + ws[0].hashCode();
        if (key.equals(s.key) && s.on) return;
        if (s.rs == null) s.rs = new RuntimeShader(SRC);   // compilar o shader é caro: uma vez por número
        RuntimeShader sh = s.rs;
        sh.setInputShader("wall", ws[0]);
        sh.setInputShader("soft", ws[1]);
        sh.setFloatUniform("M", new float[]{mv[0], mv[3], mv[6], mv[1], mv[4], mv[7], mv[2], mv[5], mv[8]});
        sh.setFloatUniform("depth", depth);
        // R = cobertura nítida (fundo opaco), G = cobertura desfocada, juntas por "tela"
        RenderEffect sharp = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                0, 0, 0, 1, 0,
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 255})));
        RenderEffect soft = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                0, 0, 0, 0, 0,
                0, 0, 0, 1, 0,
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 255})),
                RenderEffect.createBlurEffect(depth, depth, Shader.TileMode.DECAL));
        RenderEffect both = RenderEffect.createBlendModeEffect(sharp, soft, BlendMode.SCREEN);
        tv.setRenderEffect(RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(sh, "img"), both));
        if (!s.on) log("lente no número (borda " + Math.round(depth) + "px, acompanha a animação)");
        s.depth = depth;
        s.key = key;
        s.on = true;
    }

    /** O relógio se move (balanço, notificações) sem redesenhar o número: atualiza a posição. */
    static void watch(final View tv, final St s) {
        if (s.watching) return;
        s.watching = true;
        tv.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() {
                try {
                    if (!s.on) return true;
                    if (!Fx.glass()) { off(tv); return true; }
                    BitmapShader[] ws = wall(tv);
                    if (ws != null) effect(tv, s, ws, s.depth / 0.04f);
                } catch (Throwable ignored) {}
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ papel de parede

    static volatile Bitmap wbmp;
    static BitmapShader wsh, ssh;
    static int wsw, wsh2;
    static boolean loading, listening;

    static BitmapShader[] artSh;
    static int artVer = -1;

    static BitmapShader[] wall(View tv) {
        int sw = tv.getResources().getDisplayMetrics().widthPixels, sh = tv.getResources().getDisplayMetrics().heightPixels;
        Art.start(tv.getContext());
        boolean hmc = Wobble.hmcBackdrop(tv);
        if (!Boolean.valueOf(hmc).equals(lastHmc)) { lastHmc = hmc; log(hmc ? "fundo do HMC: a lente usa a capa da música" : "fundo do papel de parede"); }
        Bitmap art = Art.cover;
        if (hmc && art != null) {
            if (artSh == null || artVer != Art.version) {
                // a capa ampliada para cobrir a tela, como o HMC; a mesma imagem para o nítido e o fosco
                float sc = Math.max(sw, sh) / (float) art.getWidth();
                BitmapShader b = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                Matrix m = new Matrix();
                m.setScale(sc, sc);
                m.postTranslate((sw - art.getWidth() * sc) / 2f, (sh - art.getHeight() * sc) / 2f);
                b.setLocalMatrix(m);
                b.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                artSh = new BitmapShader[]{b, b};
                artVer = Art.version;
            }
            return artSh;
        }
        Bitmap bmp = wbmp;
        if (bmp == null) {
            load(tv.getContext().getApplicationContext() != null ? tv.getContext().getApplicationContext() : tv.getContext());
            return null;
        }
        if (wsh != null && wsw == sw && wsh2 == sh) return new BitmapShader[]{wsh, ssh};
        float sc = Math.max(sw / (float) bmp.getWidth(), sh / (float) bmp.getHeight());
        BitmapShader b = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(sc, sc);
        m.postTranslate((sw - bmp.getWidth() * sc) / 2f, (sh - bmp.getHeight() * sc) / 2f);
        b.setLocalMatrix(m);
        b.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        Bitmap sm = bmp;
        while (sm.getWidth() > 90) sm = Bitmap.createScaledBitmap(sm, Math.max(1, sm.getWidth() / 2), Math.max(1, sm.getHeight() / 2), true);
        BitmapShader sb = new BitmapShader(sm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m2 = new Matrix();
        float s2 = sc * bmp.getWidth() / (float) sm.getWidth();
        m2.setScale(s2, s2);
        m2.postTranslate((sw - sm.getWidth() * s2) / 2f, (sh - sm.getHeight() * s2) / 2f);
        sb.setLocalMatrix(m2);
        sb.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        wsh = b;
        ssh = sb;
        wsw = sw;
        wsh2 = sh;
        return new BitmapShader[]{b, sb};
    }

    static void load(final Context ctx) {
        if (loading) return;
        loading = true;
        if (!listening) {
            listening = true;
            try {
                ctx.registerReceiver(new android.content.BroadcastReceiver() {
                    public void onReceive(Context x, android.content.Intent i) {
                        wbmp = null;
                        wsh = null;
                        loading = false;
                        for (St s : states.values()) if (s != null) s.key = null;
                    }
                }, new android.content.IntentFilter(android.content.Intent.ACTION_WALLPAPER_CHANGED), Context.RECEIVER_EXPORTED);
            } catch (Throwable ignored) {}
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    WallpaperManager wm = WallpaperManager.getInstance(ctx);
                    Drawable d = null;
                    try { d = wm.getDrawable(WallpaperManager.FLAG_LOCK); } catch (Throwable ignored) {}
                    if (d == null) d = wm.getDrawable();
                    if (d == null) { log("papel de parede indisponível"); loading = false; return; }
                    int sw = ctx.getResources().getDisplayMetrics().widthPixels / 2;
                    int dw = Math.max(1, d.getIntrinsicWidth()), dh = Math.max(1, d.getIntrinsicHeight());
                    float sc = sw / (float) dw;
                    int bw = Math.max(1, Math.round(dw * sc)), bh = Math.max(1, Math.round(dh * sc));
                    Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    d.setBounds(0, 0, bw, bh);
                    d.draw(new Canvas(b));
                    wbmp = b;
                    wsh = null;
                    log("papel de parede " + dw + "x" + dh + " pronto");
                    Fx.main.post(new Runnable() {
                        public void run() {
                            for (View n : new java.util.ArrayList<View>(Fx.numbers)) n.invalidate();
                        }
                    });
                } catch (Throwable t) {
                    log("papel de parede: " + t);
                    loading = false;
                }
            }
        }, "relogios-lente").start();
    }
}
