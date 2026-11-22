package com.ilha.relogios;

import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.util.WeakHashMap;

/**
 * A espessura do vidro por cima do material: o número continua com o desfoque de verdade do sistema
 * (o mesmo das notificações, que enxerga papel de parede e HMC) e esta camada desenha só a borda de
 * uma peça de vidro — o reflexo de luz de cima à esquerda, a faixa clara onde o vidro "dobra" e a
 * sombra fina do lado oposto. O miolo fica transparente para o material aparecer.
 *
 * Fica no overlay do próprio número e é calculada na placa de vídeo a partir do desenho do quadro
 * atual, então acompanha qualquer animação do relógio.
 */
final class Rim extends Drawable {

    static final String SRC =
            "uniform shader img;\n" +            // R = cobertura nítida, G = cobertura desfocada
            "uniform float depth;\n" +
            "float H(float2 p) { return float(img.eval(p).g); }\n" +
            "half lightCone(half3 n, half3 L, half k) {\n" +
            "  half d = dot(n, L);\n" +
            "  half f = clamp(1.0 - sqrt(max(1.0 - d, 0.0)) * k, 0.0, 1.0);\n" +
            "  return max(d, 0.0) * f;\n" +
            "}\n" +
            "half4 main(float2 p) {\n" +
            "  half4 mk = img.eval(p);\n" +
            "  float cov = float(mk.r);\n" +
            "  if (cov < 0.004 || depth <= 0.0) return half4(0.0);\n" +
            "  float h = float(mk.g);\n" +
            "  float w = clamp((h - 0.5) * 2.0, 0.0, 1.0);\n" +            // 0 na borda, 1 no miolo
            "  float omw = 1.0 - w;\n" +
            "  float nz = 1.0 - pow(omw, 0.5);\n" +
            "  float2 g = float2(H(p + float2(1.5, 0)) - H(p - float2(1.5, 0)), H(p + float2(0, 1.5)) - H(p - float2(0, 1.5)));\n" +
            "  float2 n2 = length(g) > 0.0001 ? -normalize(g) : float2(0.0);\n" +
            "  half3 N = half3(half2(n2 * sqrt(max(0.0, 1.0 - nz * nz))), half(nz));\n" +
            "  half3 LN = half3(N.x, -N.y, N.z);\n" +
            "  half3 L = normalize(half3(-0.45, 0.6, 0.65));\n" +
            "  half l1 = lightCone(LN, L, 0.9);\n" +
            "  half l2 = lightCone(LN, L * half3(-1, -1, 1), 0.9);\n" +
            "  half o = half(omw);\n" +
            // reflexo forte do lado da luz, mais fraco do lado oposto
            "  half spec = pow(o, 1.3) * (l1 * 1.0 + l2 * 0.45);\n" +
            // faixa clara onde o vidro dobra (a espessura), mais fina que a borda toda
            "  half band = smoothstep(0.35, 0.95, o) * 0.22;\n" +
            // sombra fina do lado contrário à luz
            "  half dark = o * clamp(-dot(half2(LN.xy), normalize(half2(L.xy))), 0.0, 1.0) * 0.22;\n" +
            "  half aw = clamp(spec + band + 0.05, 0.0, 1.0) * half(cov);\n" +
            "  half ad = dark * half(cov) * (1.0 - aw);\n" +
            "  return half4(half3(aw), aw + ad);\n" +
            "}\n";

    static final WeakHashMap<View, Rim> rims = new WeakHashMap<>();
    static boolean failed;
    static int logs;

    static void log(String s) {
        if (logs++ < 8) com.ilha.relogios.xp.XposedBridge.log("[Relógios HOS4] vidro (borda): " + s);
    }

    /** Liga (uma vez) a borda de vidro no número. */
    static void attach(View tv) {
        if (failed || rims.containsKey(tv)) return;
        try {
            Rim r = new Rim(tv);
            tv.getOverlay().add(r);
            rims.put(tv, r);
            log("borda de vidro ligada por cima do material");
        } catch (Throwable t) {
            failed = true;
            log("indisponível: " + t);
        }
    }

    static void detach(View tv) {
        Rim r = rims.remove(tv);
        if (r != null) try { tv.getOverlay().remove(r); } catch (Throwable ignored) {}
    }

    static void detachAll() {
        for (View v : new java.util.ArrayList<View>(rims.keySet())) if (v != null) detach(v);
    }

    final java.lang.ref.WeakReference<View> host;
    final RenderNode node = new RenderNode("relogios-borda");
    final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
    final android.graphics.RectF rb = new android.graphics.RectF();
    float depth = -1f;

    Rim(View tv) {
        host = new java.lang.ref.WeakReference<View>(tv);
        white.setColor(0xffffffff);
    }

    @Override
    public void draw(Canvas c) {
        View tv = host.get();
        if (tv == null || failed || !c.isHardwareAccelerated() || !Fx.glass()) return;
        try {
            if (!LiquidGlass.init(tv.getClass())) return;
            int w = tv.getWidth(), h = tv.getHeight();
            if (w <= 0 || h <= 0) return;
            setBounds(0, 0, w, h);
            float a = LiquidGlass.fAlpha.getFloat(tv);
            float x = LiquidGlass.fX.getFloat(tv), y = LiquidGlass.fY.getFloat(tv);
            int split = LiquidGlass.fSplit.getInt(tv);
            float colonA = LiquidGlass.fColonA.getFloat(tv);
            node.setPosition(0, 0, w, h);
            RecordingCanvas rc = node.beginRecording(w, h);
            Path main;
            try {
                rc.translate(x, y);
                if (split <= 0 || colonA >= 1f) {
                    main = (Path) LiquidGlass.fPath.get(tv);
                    white.setAlpha(Math.round(255 * Lens.clamp(a)));
                    rc.drawPath(main, white);
                } else {
                    if (colonA > 0f) {
                        white.setAlpha(Math.round(255 * Lens.clamp(a * colonA)));
                        rc.drawPath((Path) LiquidGlass.fColon.get(tv), white);
                    }
                    rc.translate(LiquidGlass.fColonW.getFloat(tv), 0f);
                    main = (Path) LiquidGlass.fMinute.get(tv);
                    white.setAlpha(Math.round(255 * Lens.clamp(a)));
                    rc.drawPath(main, white);
                }
            } finally {
                node.endRecording();
            }
            main.computeBounds(rb, true);
            float dp = tv.getResources().getDisplayMetrics().density;
            float d = Math.round(Math.max(4f * dp, Math.min(10f * dp, rb.height() * 0.035f)));
            if (d != depth) {
                depth = d;
                RuntimeShader sh = new RuntimeShader(SRC);
                sh.setFloatUniform("depth", d);
                RenderEffect sharp = RenderEffect.createColorFilterEffect(cm(new float[]{
                        0, 0, 0, 1, 0,
                        0, 0, 0, 0, 0,
                        0, 0, 0, 0, 0,
                        0, 0, 0, 0, 255}));
                RenderEffect soft = RenderEffect.createColorFilterEffect(cm(new float[]{
                        0, 0, 0, 0, 0,
                        0, 0, 0, 1, 0,
                        0, 0, 0, 0, 0,
                        0, 0, 0, 0, 255}),
                        RenderEffect.createBlurEffect(d, d, Shader.TileMode.DECAL));
                RenderEffect both = RenderEffect.createBlendModeEffect(sharp, soft, BlendMode.SCREEN);
                node.setRenderEffect(RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(sh, "img"), both));
                log("borda de " + Math.round(d) + "px");
            }
            // o relógio desloca o canvas no onDraw e não desfaz: volta à origem da view antes de desenhar
            int sv = c.save();
            c.setMatrix(null);
            c.drawRenderNode(node);
            c.restoreToCount(sv);
        } catch (Throwable t) {
            failed = true;
            log("falhou: " + t);
        }
    }

    static ColorFilter cm(float[] m) {
        return new ColorMatrixColorFilter(new ColorMatrix(m));
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
