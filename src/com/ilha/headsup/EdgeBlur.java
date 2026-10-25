package com.ilha.headsup;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.reflect.Method;

/**
 * Pontas desfocadas no título do player de música da gaveta (MiuiMediaViewHolder.titleText).
 *
 * Quando o título passa da largura e rola (marquee), as letras que entram e saem pelas pontas
 * ficam desfocadas e transparentes, em vez de cortadas secas. O desfoque é um RuntimeShader
 * (o mesmo do player do bloqueio do HyperMusicCover): só age nas faixas das pontas, com força
 * proporcional ao "fading edge" que o próprio TextView calcula para cada lado. Assim, com o
 * texto parado no começo, a ponta esquerda fica nítida; ao rolar, as duas desfocam.
 */
final class EdgeBlur {
    private EdgeBlur() {}

    static final int TAG_KEY = 0x7e1a7003;

    static final String SHADER =
            "uniform shader content;\n"
            + "uniform float width;\n"
            + "uniform float edgeL;\n"
            + "uniform float edgeR;\n"
            + "uniform float maxBlur;\n"
            + "half4 main(float2 p) {\n"
            + "    float e = 0.0;\n"
            + "    if (edgeL > 0.0) e = max(e, 1.0 - clamp(p.x / edgeL, 0.0, 1.0));\n"
            + "    if (edgeR > 0.0) e = max(e, 1.0 - clamp((width - p.x) / edgeR, 0.0, 1.0));\n"
            + "    if (e <= 0.0) return content.eval(p);\n"
            + "    float r = e * e * maxBlur;\n"
            + "    half4 c = half4(0.0);\n"
            + "    float total = 0.0;\n"
            + "    for (int i = -4; i <= 4; i++) {\n"
            + "        float w = exp(-float(i * i) / 8.0);\n"
            + "        c += content.eval(p + float2(float(i) * r / 4.0, 0.0)) * w;\n"
            + "        total += w;\n"
            + "    }\n"
            + "    return c / total;\n"
            + "}\n";

    static Method mLeft, mRight;
    static int logs;

    static void install(ClassLoader cl) {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            mLeft = TextView.class.getDeclaredMethod("getLeftFadingEdgeStrength");
            mLeft.setAccessible(true);
            mRight = TextView.class.getDeclaredMethod("getRightFadingEdgeStrength");
            mRight.setAccessible(true);
        } catch (Throwable t) {
            ElasticCard.log("título da música: fading edge indisponível " + t);
            return;
        }
        int ok = 0;
        try {
            Class c = Class.forName("com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl", false, cl);
            Method attach = null;
            for (Method m : c.getDeclaredMethods()) {
                if ("attach".equals(m.getName()) && m.getParameterTypes().length == 1) attach = m;
            }
            if (attach != null) {
                XposedBridge.hookMethod(attach, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object tv = Refl.get(p.args[0], "titleText");
                            if (tv instanceof TextView) apply((TextView) tv);
                        } catch (Throwable t) {
                            if (logs++ < 4) ElasticCard.log("título da música: erro " + t);
                        }
                    }
                });
                ok++;
            }
        } catch (Throwable t) {
            ElasticCard.log("título da música: player do HyperOS indisponível " + t);
        }
        ElasticCard.log("título da música com as pontas desfocadas: " + (ok > 0 ? "instalado" : "não encontrei o player"));
    }

    static void apply(TextView tv) {
        if (tv.getTag(TAG_KEY) != null) return;
        Edge e = new Edge(tv);
        tv.setTag(TAG_KEY, e);
        tv.addOnAttachStateChangeListener(e);
        if (tv.isAttachedToWindow()) e.onViewAttachedToWindow(tv);
        if (logs++ < 2) ElasticCard.log("título da música: pontas desfocadas ligadas");
    }

    static final class Edge implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        final TextView tv;
        final float edge, blur;
        RuntimeShader shader;
        ViewTreeObserver vto;
        float lastL = -1f, lastR = -1f;
        int lastW = -1;
        boolean on, prevFade, dirty;
        int prevFadeLen = -1;

        Edge(TextView tv) {
            this.tv = tv;
            float d = tv.getResources().getDisplayMetrics().density;
            this.edge = 16f * d;
            this.blur = 3f * d;
        }

        public void onViewAttachedToWindow(View v) {
            ViewTreeObserver o = v.getViewTreeObserver();
            if (vto != null && vto.isAlive()) vto.removeOnPreDrawListener(this);
            vto = o;
            o.addOnPreDrawListener(this);
        }

        public void onViewDetachedFromWindow(View v) {
            if (vto != null && vto.isAlive()) vto.removeOnPreDrawListener(this);
            vto = null;
        }

        public boolean onPreDraw() {
            try {
                Prefs.refresh();
                if (!Prefs.mediaEdge) {
                    if (on) off();
                    return true;
                }
                if (!tv.isShown() || tv.getWidth() <= 0) return true;
                if (!on) {
                    prevFade = tv.isHorizontalFadingEdgeEnabled();
                    prevFadeLen = tv.getHorizontalFadingEdgeLength();
                    tv.setHorizontalFadingEdgeEnabled(true);
                    tv.setFadingEdgeLength((int) edge);
                    on = true;
                }
                float l = q(((Float) mLeft.invoke(tv)).floatValue());
                float r = q(((Float) mRight.invoke(tv)).floatValue());
                int w = tv.getWidth();
                if (l == lastL && r == lastR && w == lastW && !dirty) return true;
                lastL = l;
                lastR = r;
                lastW = w;
                build();
            } catch (Throwable t) {
                if (logs++ < 6) ElasticCard.log("título da música: erro no desfoque " + t);
            }
            return true;
        }

        /** Aplica (ou tira) o desfoque das pontas. */
        void build() {
            dirty = false;
            RenderEffect fx = null;
            if (on && (lastL > 0f || lastR > 0f) && lastW > 0) {
                if (shader == null) shader = new RuntimeShader(SHADER);
                shader.setFloatUniform("width", lastW);
                shader.setFloatUniform("edgeL", edge * lastL);
                shader.setFloatUniform("edgeR", edge * lastR);
                shader.setFloatUniform("maxBlur", blur);
                fx = RenderEffect.createRuntimeShaderEffect(shader, "content");
            }
            tv.setRenderEffect(fx);
        }

        void off() {
            on = false;
            lastL = lastR = -1f;
            lastW = -1;
            try {
                build();
                tv.setHorizontalFadingEdgeEnabled(prevFade);
                if (prevFadeLen >= 0) tv.setFadingEdgeLength(prevFadeLen);
            } catch (Throwable ignored) {
            }
        }

        /** Arredonda para 0,1 (evita recriar o efeito a cada quadro da rolagem). */
        static float q(float v) {
            if (v <= 0f) return 0f;
            if (v >= 1f) return 1f;
            return Math.round(v * 10f) / 10f;
        }
    }
}
