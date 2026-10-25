package com.ilha.headsup;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import java.lang.reflect.Method;

/**
 * Desfoque no topo da tela de bloqueio: quando as notificações rolam para cima e passam do topo,
 * uma faixa com desfoque em degradê (o mesmo do cabeçalho da gaveta do HyperOS) desfoca o que
 * passa por baixo dela — forte na barra de status e sumindo aos poucos logo abaixo.
 *
 * A faixa é uma view no overlay da lista de notificações (não entra no layout nem no toque) e usa
 * o desfoque de fundo do HyperOS: setMiBackgroundBlurMode(1), setMiViewBlurMode(1),
 * setMiBackgroundBlurType(2) e setBackgroundGradientBlurParams([x0, y0, raio0, x1, y1, raio1], 1).
 */
final class TopBlur {
    private TopBlur() {}

    static View band;
    static ViewGroup host;
    static Method mBgMode, mViewMode, mBgType, mGrad;
    static boolean ready, unsupported, enabled, loggedOn;
    static float lastRadius = -1f;
    static int lastTop = Integer.MIN_VALUE, lastH = -1, lastW = -1;

    static boolean init(ClassLoader cl) {
        if (ready) return true;
        if (unsupported) return false;
        try {
            boolean grad = true;
            try {
                Class c = Class.forName("com.miui.systemui.util.MiBlurCompat", false, cl);
                java.lang.reflect.Field f = c.getDeclaredField("gradientBlurSupported");
                f.setAccessible(true);
                grad = f.getBoolean(null);
            } catch (Throwable ignored) {
            }
            if (!grad) {
                unsupported = true;
                ElasticCard.log("desfoque no topo: aparelho sem desfoque em degradê");
                return false;
            }
            mBgMode = View.class.getMethod("setMiBackgroundBlurMode", int.class);
            mViewMode = View.class.getMethod("setMiViewBlurMode", int.class);
            try {
                mBgType = View.class.getMethod("setMiBackgroundBlurType", int.class);
            } catch (Throwable ignored) {
            }
            mGrad = View.class.getMethod("setBackgroundGradientBlurParams", float[].class, int.class);
            ready = true;
            ElasticCard.log("desfoque no topo: pronto");
            return true;
        } catch (Throwable t) {
            unsupported = true;
            ElasticCard.log("desfoque no topo indisponível: " + t);
            return false;
        }
    }

    static View makeBand(Context ctx) {
        View v = new View(ctx) {
            public boolean hasOverlappingRendering() {
                return false;
            }
        };
        v.setFocusable(false);
        v.setClickable(false);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        v.setVisibility(View.GONE);
        return v;
    }

    /**
     * Chamado a cada passada da lista no bloqueio.
     * nssl = lista; visTop = topo da tela em coordenadas da lista; cardTop = topo do 1º cartão na
     * tela (px); scrollY = rolagem; statusBar = altura da barra de status; d = densidade.
     */
    static void update(View nssl, float visTop, float cardTop, int scrollY, float statusBar, float d) {
        if (!(nssl instanceof ViewGroup) || !init(nssl.getClass().getClassLoader())) return;
        try {
            ViewGroup g = (ViewGroup) nssl;
            if (band == null) band = makeBand(g.getContext());
            if (host != g) {
                detach();
                g.getOverlay().add(band);
                host = g;
                lastTop = Integer.MIN_VALUE;
                // a faixa pode ficar acima do topo da lista: o overlay não deve cortá-la
                ViewParent op = band.getParent();
                if (op instanceof ViewGroup) ((ViewGroup) op).setClipChildren(false);
                ElasticCard.log("desfoque no topo: faixa presa à lista (topo da tela em " + Math.round(visTop) + "px da lista)");
            }
            // faixa: desfoque cheio até o fim da barra de status, sumindo até ~56dp abaixo dela
            int h = (int) (statusBar + 56f * d);
            int w = g.getWidth();
            int top = Math.round(visTop);
            if (top != lastTop || h != lastH || w != lastW) {
                band.layout(0, top, w, top + h);
                lastTop = top;
                lastH = h;
                lastW = w;
                lastRadius = -1f;   // geometria mudou: reaplica o degradê
            }
            // intensidade: só quando a lista está rolada e um cartão entrou na faixa
            float amount = 0f;
            if (scrollY > 0 && !Float.isNaN(cardTop)) amount = clamp((h - cardTop) / (h * 0.6f), 0f, 1f);
            float radius = Math.round(amount * 14f * d);
            if (radius < 1f) {
                off();
                return;
            }
            if (!enabled) {
                band.setVisibility(View.VISIBLE);
                mBgMode.invoke(band, Integer.valueOf(1));
                mViewMode.invoke(band, Integer.valueOf(1));
                if (mBgType != null) mBgType.invoke(band, Integer.valueOf(2));
                enabled = true;
                lastRadius = -1f;
                if (!loggedOn) {
                    loggedOn = true;
                    ElasticCard.log("desfoque no topo: ligado (faixa " + h + "px)");
                }
            }
            if (Math.abs(radius - lastRadius) >= 1f) {
                float[] p = new float[] {0f, statusBar, radius, 0f, h, 0f};
                mGrad.invoke(band, p, Integer.valueOf(1));
                lastRadius = radius;
                band.invalidate();
            }
        } catch (Throwable t) {
            unsupported = true;
            ElasticCard.log("desfoque no topo falhou: " + t);
            off();
        }
    }

    /** Desliga o desfoque (fora do bloqueio, AOD, lista parada ou ajuste desligado). */
    static void off() {
        if (!enabled || band == null) return;
        enabled = false;
        lastRadius = -1f;
        try {
            mBgMode.invoke(band, Integer.valueOf(0));
            mViewMode.invoke(band, Integer.valueOf(0));
        } catch (Throwable ignored) {
        }
        band.setVisibility(View.GONE);
    }

    static void detach() {
        off();
        if (band == null) return;
        try {
            ViewParent p = band.getParent();
            if (host != null) host.getOverlay().remove(band);
            else if (p instanceof ViewGroup) ((ViewGroup) p).removeView(band);
        } catch (Throwable ignored) {
        }
        host = null;
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
