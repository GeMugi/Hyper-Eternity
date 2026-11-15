package com.ilha.headsup;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * HyperCanvas (vídeo do Spotify Canvas no bloqueio, extensão do HyperMusicCover).
 *
 * 1) Correção: quando o primeiro quadro do vídeo chega depois de a tela acender, o HyperCanvas
 *    tira a imagem congelada da camada dele (CanvasBackground.finishThaw -> dropFrozenFrame)
 *    dentro do aviso de quadro novo da TextureView, que o Android dá no meio do desenho dessa
 *    mesma camada. Remover uma view de um grupo enquanto ele se desenha derruba a Interface do
 *    Sistema (NullPointerException em ViewGroup.dispatchDraw). A troca é adiada para logo
 *    depois do desenho.
 * 2) "Canvas no fundo do player": o vidro do player usa o desfoque da janela, que só enxerga o
 *    que está abaixo da janela do bloqueio (o papel de parede, onde o HMC põe a capa desfocada). O
 *    vídeo fica dentro da janela, então o cartão mostrava a capa e não o vídeo. Com o Canvas
 *    tocando, o fundo do player passa a usar o desfoque da ilha do HMC, que enxerga o vídeo.
 */
final class CanvasCard {
    private CanvasCard() {}

    static WeakReference<View> video;
    static boolean thawHooked, deferring;
    static final Handler main = new Handler(Looper.getMainLooper());
    static final WeakHashMap<View, Watch> cards = new WeakHashMap<>();
    static int logs;

    static void log(String m) {
        if (logs++ < 20) ElasticCard.log("Canvas: " + m);
    }

    static View video() {
        View v = video == null ? null : video.get();
        return v != null && v.isAttachedToWindow() ? v : null;
    }

    /** Toda view adicionada passa por aqui (gancho de addView): acha o vídeo do HyperCanvas. */
    static void seen(View v) {
        try {
            if (v instanceof TextureView) {
                TextureView.SurfaceTextureListener l = ((TextureView) v).getSurfaceTextureListener();
                if (l != null && l.getClass().getName().startsWith("com.os4.canvas.")) found(v, l.getClass().getClassLoader());
            } else if (v instanceof SurfaceView && v.getClass().getName().startsWith("com.os4.canvas.")) {
                found(v, v.getClass().getClassLoader());
            }
        } catch (Throwable ignored) {
        }
    }

    static void found(View v, ClassLoader cl) {
        video = new WeakReference<>(v);
        if (!thawHooked) {
            thawHooked = true;
            hookThaw(cl);
        }
        log("vídeo encontrado (" + v.getClass().getSimpleName() + ")");
        try {
            int id = v.getResources().getIdentifier("media_bg", "id", "com.android.systemui");
            if (id != 0) attachAll(v.getRootView(), id);
        } catch (Throwable ignored) {
        }
        for (View h : cards.keySet().toArray(new View[0])) if (h != null) h.invalidate();
    }

    static void hookThaw(ClassLoader cl) {
        try {
            Class<?> cb = Class.forName("com.os4.canvas.CanvasBackground", false, cl);
            final Method m = cb.getDeclaredMethod("finishThaw");
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (deferring) return;
                    p.setResult(null);
                    main.post(new Runnable() {
                        public void run() {
                            deferring = true;
                            try {
                                m.invoke(null);
                            } catch (Throwable t) {
                                log("descongelar: " + t);
                            } finally {
                                deferring = false;
                            }
                        }
                    });
                }
            });
            log("troca do quadro congelado adiada para depois do desenho");
        } catch (Throwable t) {
            log("sem finishThaw nesta versão: " + t);
        }
    }

    /**
     * Fundo de um player (media_bg). Com o Canvas tocando, ganha o mesmo desfoque da ilha do HMC:
     * desfoque próprio do HyperOS na view (modo 1 + raio, modo de view 1, passando o desfoque da
     * janela), que enxerga o que está dentro da janela do bloqueio, inclusive o vídeo. A mistura de
     * cor do vidro (do HyperOS) continua por cima. Sem Canvas, volta ao desfoque da janela.
     */
    static void attach(View bg) {
        if (bg == null) return;
        Watch w = cards.get(bg);
        if (w == null) {
            w = new Watch(bg);
            cards.put(bg, w);
        }
        android.view.ViewTreeObserver o = bg.getViewTreeObserver();
        if (w.vto != o) {
            w.vto = o;
            o.addOnPreDrawListener(w);
        }
    }

    static Method mMode, mRadius, mViewMode, mPass, gMode, gRadius, gPass;
    static boolean reflected;

    static boolean reflect() {
        if (reflected) return mMode != null;
        reflected = true;
        try {
            mMode = View.class.getMethod("setMiBackgroundBlurMode", int.class);
            mRadius = View.class.getMethod("setMiBackgroundBlurRadius", int.class);
            mViewMode = View.class.getMethod("setMiViewBlurMode", int.class);
            mPass = View.class.getMethod("setPassWindowBlurEnabled", boolean.class);
        } catch (Throwable t) {
            mMode = null;
            log("desfoque de view indisponível: " + t);
            return false;
        }
        try { gMode = View.class.getMethod("getMiBackgroundBlurMode"); } catch (Throwable ignored) { }
        try { gRadius = View.class.getMethod("getMiBackgroundBlurRadius"); } catch (Throwable ignored) { }
        try { gPass = View.class.getMethod("getPassWindowBlurEnabled"); } catch (Throwable ignored) { }
        return true;
    }

    static int getInt(Method m, View v) {
        try {
            Object o = m == null ? null : m.invoke(v);
            return o instanceof Integer ? (Integer) o : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    static final class Watch implements android.view.ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> bg;
        android.view.ViewTreeObserver vto;
        boolean on;
        int[] orig;

        Watch(View bg) {
            this.bg = new WeakReference<>(bg);
        }

        public boolean onPreDraw() {
            View b = bg.get();
            if (b == null || !b.isAttachedToWindow()) return true;
            View v = Prefs.canvasCard ? video() : null;
            boolean want = v != null && b.isShown() && active(v);
            if (want != on) {
                on = want;
                apply(b, want);
            }
            return true;
        }

        void apply(View b, boolean want) {
            if (!reflect()) return;
            try {
                if (want) {
                    if (orig == null) {
                        boolean pass = false;
                        try { pass = gPass != null && Boolean.TRUE.equals(gPass.invoke(b)); } catch (Throwable ignored) { }
                        orig = new int[]{getInt(gMode, b), getInt(gRadius, b), pass ? 1 : 0};
                    }
                    int r = Math.round(40 * b.getResources().getDisplayMetrics().density);
                    mMode.invoke(b, 1);
                    mRadius.invoke(b, r);
                    mViewMode.invoke(b, 1);
                    mPass.invoke(b, true);
                    log("player com o desfoque da ilha (vê o Canvas)");
                } else if (orig != null) {
                    mMode.invoke(b, orig[0]);
                    mRadius.invoke(b, orig[1]);
                    mPass.invoke(b, orig[2] == 1);
                    orig = null;
                    log("player de volta ao desfoque da janela");
                }
                b.invalidate();
            } catch (Throwable t) {
                log("desfoque do player: " + t);
            }
        }
    }

    static void attachAll(View v, int id) {
        if (v.getId() == id) attach(v);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) attachAll(g.getChildAt(i), id);
        }
    }

    static boolean active(View v) {
        if (!v.isShown() || v.getWidth() == 0 || v.getHeight() == 0) return false;
        if (v instanceof TextureView && !((TextureView) v).isAvailable()) return false;
        float a = v.getAlpha();
        for (Object p = v.getParent(); p instanceof View; p = ((View) p).getParent()) a *= ((View) p).getAlpha();
        return a > 0.05f;
    }
}
