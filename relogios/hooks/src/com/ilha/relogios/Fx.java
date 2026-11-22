package com.ilha.relogios;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import com.ilha.relogios.xp.XC_MethodHook;
import com.ilha.relogios.xp.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Efeitos nos números dos relógios All in One (teste), por cima do material escolhido no editor.
 *
 * Dois tipos:
 *  - "mistura": o número vira um recorte que mistura uma cor com o fundo desfocado de verdade
 *    (o mesmo motor da textura avançada), num modo de mistura escolhido (sobrepor, tela...);
 *  - "pintura": o que preenche o número (papel de parede, duas cores, holográfico) ou um filtro
 *    sobre o relógio inteiro (granulado, separação RGB). Esses não enxergam o que está atrás.
 */
final class Fx {
    private Fx() {}

    static final int NONE = 0, WALL = 1, DUO = 2, HOLO = 3, GRAIN = 4, RGB = 5, POSTER = 6, TRAIL = 7, GLASS = 8;
    /** Modos de mistura oferecidos (números do motor de mistura; os baixos seguem os modos do Skia). */
    static final int[] BLEND_MODES = {0, 15, 21, 14, 18, 24, 22, 23, 12, 28};

    static final int TAG_SHADED = 0x7e1a0e31;

    static Field fPaint;
    static Class<?> tvCls;
    static Method mContainer, mBlurMode, mBlendColors, mClearBlend, mAddBlend, mClearOne;
    static boolean installed;
    static int logs;
    static final Set<View> clocks = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    static final Set<View> numbers = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    static final Handler main = new Handler(Looper.getMainLooper());

    static void log(String s) {
        XposedBridge.log("[Relógios HOS4] efeitos: " + s);
    }

    static boolean shading() {
        return Cfg.fx == WALL || Cfg.fx == DUO || Cfg.fx == HOLO || Cfg.fx == POSTER;
    }

    static boolean glass() {
        return Cfg.fx == GLASS;
    }

    static boolean blending() {
        return Cfg.fxBlend != 0;
    }

    static void install(ClassLoader cl) {
        if (installed) return;
        installed = true;
        if (Safe.blocked("efeitos")) {
            log("desligados pela proteção (o SystemUI caiu em sequência com eles)");
            return;
        }
        try {
            tvCls = cl.loadClass("com.miui.clock.allInOne.TimeView");
            fPaint = tvCls.getDeclaredField("textPaint");
            fPaint.setAccessible(true);
            Class<?> mbu = cl.loadClass("com.miui.clock.utils.MiuiBlurUtils");
            mContainer = mbu.getMethod("setContainerPassBlur", View.class, int.class, boolean.class);
            mClearBlend = mbu.getMethod("clearMemberBlendColor", View.class);
            mBlurMode = View.class.getMethod("setMiViewBlurMode", int.class);
            // no HyperOS 3 o método recebe ArrayList (não List); se não houver, usa o de uma cor por vez
            try { mBlendColors = View.class.getMethod("setMiBackgroundBlendColors", ArrayList.class); } catch (Throwable ignored) {}
            try { mAddBlend = View.class.getMethod("addMiBackgroundBlendColor", int.class, int.class); } catch (Throwable ignored) {}
            try { mClearOne = View.class.getMethod("clearMiBackgroundBlendColor"); } catch (Throwable ignored) {}
            log("mistura: lista " + (mBlendColors != null) + ", uma a uma " + (mAddBlend != null));
        } catch (Throwable t) {
            log("mistura indisponível: " + t);
        }
        try {
            Class<?> ceu = cl.loadClass("com.miui.clock.utils.ClockEffectUtils");
            for (Method m : ceu.getDeclaredMethods()) {
                if (m.getName().equals("setClockEffectsContainer")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (!(blending() || glass()) || mContainer == null || !(p.args[0] instanceof View)) return;
                            try {
                                Object rad = p.args[1];
                                int[] own = glass() ? rc() : null;
                                if (own != null && own[10] > 0) rad = Integer.valueOf(own[10]);
                                mContainer.invoke(null, p.args[0], rad, Boolean.FALSE);
                                p.setResult(null);
                            } catch (Throwable t) {
                                if (logs++ < 8) log("recipiente: " + t);
                            }
                        }
                    });
                }
                if (m.getName().equals("setClockEffectsView")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (!(p.args[0] instanceof View) || tvCls == null || !tvCls.isInstance(p.args[0])) return;
                            View v = (View) p.args[0];
                            try {
                                if (blending()) {
                                    blend(v, ((Integer) p.args[3]).intValue());
                                    p.setResult(null);
                                } else if (glass()) {
                                    numbers.add(v);
                                    libGlass(v);
                                    p.setResult(null);
                                } else if (shading()) {
                                    // pintura: o número fica sem a mistura, para a tinta aparecer como é
                                    if (mClearBlend != null) mClearBlend.invoke(null, v);
                                    p.setResult(null);
                                }
                            } catch (Throwable t) {
                                if (logs++ < 8) log("número: " + t);
                            }
                        }
                    });
                }
            }
            XposedBridge.hookMethod(tvCls.getDeclaredMethod("onDraw", Canvas.class), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if (glass()) params((View) p.thisObject, (Canvas) p.args[0]);
                }

                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!Lens.states.isEmpty()) Lens.off((View) p.thisObject);
                    if (glass()) {
                        View v = (View) p.thisObject;
                        numbers.add(v);
                        if (!Integer.valueOf(glassColor()).equals(v.getTag(TAG_NOTIF))) libGlass(v);
                    }
                    paint((View) p.thisObject);
                }
            });
            captureEarly();
            Cfg.watch(new Runnable() {
                public void run() { refresh(); }
            });
            log("prontos");
        } catch (Throwable t) {
            log("indisponíveis: " + t);
        }
    }

    /** Um relógio (a caixa da extensão) passou a existir. */
    static void attach(View box) {
        clocks.add(box);
        box.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { sensors(); }
            public void onViewDetachedFromWindow(View v) { sensors(); }
        });
        filter(box);
    }

    static int lastFx = -1, lastBlend = -1;

    /** Ajuste mudou: refaz os relógios para os ganchos acima valerem. */
    static void refresh() {
        boolean blendChanged = lastBlend != -1 && lastBlend != Cfg.fxBlend;
        boolean fxChanged = lastFx != Cfg.fx;
        boolean wasShade = lastFx == WALL || lastFx == DUO || lastFx == HOLO || lastFx == POSTER || lastFx == GLASS;
        lastFx = Cfg.fx;
        lastBlend = Cfg.fxBlend;
        if (fxChanged || blendChanged) {
            log("efeito " + Cfg.fx + ", mistura " + Cfg.fxBlend);
            if (Cfg.fx != NONE || Cfg.fxBlend != 0) Safe.touch("efeitos");
        }
        if (!glass()) { Lens.offAll(); Rim.detachAll(); for (View n : new ArrayList<View>(numbers)) if (n.getTag(TAG_BLOOM) != null) libGlassOff(n); }
        for (View b : new ArrayList<View>(clocks)) filter(b);
        for (View n : new ArrayList<View>(numbers)) n.invalidate();
        if (blendChanged || (fxChanged && (shading() || glass() || wasShade))) Clocks.rebuildAll("efeito mudou");
        sensors();
    }

    // ------------------------------------------------------------------ material das notificações

    static final int TAG_NOTIF = 0x7e1a0e32;
    /** As cores de mistura que o sistema usa no fundo dos cartões de notificação (lidas quando ele as aplica). */
    static volatile ArrayList<Point> notifList;
    static int notifLogs;

    /**
     * O vidro das notificações é o desfoque de verdade do sistema (enxerga o papel de parede, o HMC,
     * tudo que está atrás) com uma lista de cores de mistura por cima. O fundo dos cartões recebe essa
     * lista pelo setMiBackgroundBlendColors: a primeira vez que isso acontece, guardamos a lista e os
     * números passam a usar exatamente a mesma (desfoque recortado no formato do número).
     */
    static void captureNotif() {
        try {
            for (final Method m : View.class.getDeclaredMethods()) {
                if (!m.getName().equals("setMiBackgroundBlendColors") || m.getParameterTypes().length < 1) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (!(p.args[0] instanceof java.util.List)) return;
                            String n = p.thisObject.getClass().getName();
                            if (!n.contains("Notification") || !n.contains("Background")) return;
                            java.util.List<?> l = (java.util.List<?>) p.args[0];
                            if (l.isEmpty()) return;
                            ArrayList<Point> c = new ArrayList<Point>();
                            for (Object o : l) if (o instanceof Point) c.add(new Point((Point) o));
                            if (c.isEmpty() || c.equals(notifList)) return;
                            notifList = c;
                            if (notifLogs++ < 4) {
                                StringBuilder b = new StringBuilder();
                                for (Point pt : c) b.append(Integer.toHexString(pt.x)).append('/').append(pt.y).append(' ');
                                log("material das notificações lido de " + p.thisObject.getClass().getSimpleName() + ": " + b.toString().trim());
                            }
                            if (glass()) main.post(new Runnable() {
                                public void run() {
                                    for (View v : new ArrayList<View>(numbers)) libGlass(v);
                                }
                            });
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable t) {
            log("material das notificações: " + t);
        }
    }

    static final int TAG_BLOOM = 0x7e1a0e33;
    /** Os parâmetros do vidro da lib (setMiBloomStroke) que o Hyper Eternity aplica nos cartões de notificação. */
    static volatile float[] bloomCfg;
    static volatile boolean bloomFromNotif;
    static Method mBloom;
    static int bloomLogs;

    static boolean captured;

    /** Chamado logo que o SystemUI abre: os cartões recebem o vidro antes de o relógio existir. */
    static synchronized void captureEarly() {
        if (captured) return;
        captured = true;
        captureNotif();
        captureBloom();
    }

    static void captureBloom() {
        try {
            mBloom = View.class.getMethod("setMiBloomStroke", float[].class);
            XposedBridge.hookMethod(mBloom, new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.args[0] instanceof float[])) return;
                        String cn = p.thisObject.getClass().getName();
                        boolean notif = cn.endsWith("NotificationBackgroundView");
                        // o cartão de notificação manda; sem ele, qualquer vidro de cartão do bloqueio serve
                        if (!notif && bloomFromNotif) return;
                        float[] c = ((float[]) p.args[0]).clone();
                        boolean any = false;
                        for (float f : c) if (f != 0f) { any = true; break; }
                        if (!any) return;
                        if (c.length > 6) c[6] = 0f;   // altura visível do cartão: não vale para o relógio
                        if (java.util.Arrays.equals(c, bloomCfg)) return;
                        bloomCfg = c;
                        bloomFromNotif = notif;
                        if (bloomLogs++ < 3) log("vidro da lib lido de " + p.thisObject.getClass().getSimpleName() + " (" + c.length + " parâmetros)");
                        if (glass()) main.post(new Runnable() {
                            public void run() {
                                for (View v : new ArrayList<View>(numbers)) { libGlass(v); v.invalidate(); }
                            }
                        });
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            log("vidro da lib indisponível (sem setMiBloomStroke): " + t);
        }
    }

    /**
     * Vidro da lib nos números: o desfoque do sistema recortado no formato do número (modo de mistura
     * da view, o mesmo da "mistura") com as cores dos cartões, e o material da lib (setMiBloomStroke,
     * o shader do módulo "Vidro com refração") com os mesmos parâmetros dos cartões de notificação.
     */
    /**
     * Parâmetros do vidro da lib (o shader do módulo "Vidro com refração"), no mesmo formato que o
     * Hyper Eternity usa: os valores padrão do material avançado. Se o Hyper Eternity aplicar o vidro
     * num cartão, os ajustes dele passam a valer aqui também.
     */
    static final float[] LIB_DEFAULT = {5.7750006f, 206652.0f, 1.0f, 1.0f, 0.9f, 0.0f, 0.0f, -0.20710677f, 1.4071068f, 32767.0f,
            9212966.0f, 3352166.0f, 5963904.0f, 3309608.0f, 1.5f, 0.7f, 0.0f, 4937028.0f, 7553152.0f, 8401677.0f, 8010524.0f};
    static final int TAG_BLOOM_SIZE = 0x7e1a0e34, TAG_BLOOM_L = 0x7e1a0e35, TAG_BLOOM_FAIL = 0x7e1a0e36;

    /**
     * Vidro da lib direto no número: o desfoque do sistema recortado no formato do número (modo de
     * mistura da view) e o material avançado (setMiBloomStroke). O sistema guarda o tamanho da view no
     * momento em que o material é aplicado: com a view ainda sem tamanho (0x0) ele não aparecia; por
     * isso só aplica com tamanho e reaplica quando o tamanho muda.
     */
    static final Object FROST_KEY = new Object();

    static Object notifKey() {
        ArrayList<Point> l = notifList;
        return l != null ? l : FROST_KEY;
    }

    static void libGlass(final View v) {
        if (!Integer.valueOf(glassColor()).equals(v.getTag(TAG_NOTIF))) notifMaterial(v);
        float[] c = bloomCfg != null ? bloomCfg : LIB_DEFAULT;
        if (v.getTag(TAG_BLOOM_L) == null) {
            View.OnLayoutChangeListener l = new View.OnLayoutChangeListener() {
                public void onLayoutChange(View x, int l1, int t1, int r1, int b1, int l0, int t0, int r0, int b0) {
                    if (glass() && (r1 - l1 != r0 - l0 || b1 - t1 != b0 - t0)) libGlass(x);
                }
            };
            v.addOnLayoutChangeListener(l);
            v.setTag(TAG_BLOOM_L, l);
        }
        int w = v.getWidth(), h = v.getHeight();
        if (true) return;   // o material avançado (bloom) é de retângulo: o vidro do número vem do modo 250
        if (w <= 0 || h <= 0 || mBloom == null) return;
        String key = w + "x" + h + java.util.Arrays.toString(c);
        if (key.equals(v.getTag(TAG_BLOOM_SIZE))) return;
        try {
            mBloom.invoke(v, (Object) new float[c.length]);
            mBloom.invoke(v, (Object) c);
            v.setTag(TAG_BLOOM_SIZE, key);
            v.setTag(TAG_BLOOM, c);
            if (bloomLogs++ < 8) log("vidro da lib aplicado no número " + Glass.name(v) + " (" + w + "x" + h + (c == LIB_DEFAULT ? ", parâmetros padrão" : ", parâmetros do Hyper Eternity") + ")");
            v.invalidate();
        } catch (Throwable t) {
            v.setTag(TAG_BLOOM_FAIL, Boolean.TRUE);
            if (logs++ < 8) log("vidro da lib no número: " + t);
        }
    }

    static void libGlassOff(View v) {
        try {
            if (v.getTag(TAG_BLOOM) != null && mBloom != null) mBloom.invoke(v, (Object) new float[21]);
        } catch (Throwable ignored) {}
        v.setTag(TAG_BLOOM, null);
        v.setTag(TAG_BLOOM_SIZE, null);
        v.setTag(TAG_NOTIF, null);
    }

    /** O número recebe o desfoque do sistema com as mesmas cores de mistura dos cartões. */
    /**
     * Modo de mistura 250: o vidro com refração que o módulo da lib (Vidro com refração v47+) acrescenta
     * ao shader de mistura do desfoque da Xiaomi — o mesmo que o relógio já usa. Lá dentro ele tem o
     * fundo desfocado de verdade (papel de parede, HMC) e o desenho do próprio número: acha a borda de
     * cada número, curva o fundo como uma lente, com desvio de cor e luz. A cor é um véu (branco, 12%).
     * Sem o módulo novo, o sistema trata o 250 como uma mistura simples (fica fosco).
     */
    static final int GLASS_MODE = 250;

    /**
     * Os parâmetros do vidro do número vão na cor da mistura (o shader do módulo lê cada canal):
     * vermelho = largura da lente (px x2), verde = espessura (quanto desloca o fundo), azul = luz.
     * Vêm dos controles do material avançado do Hyper Eternity (largura da lente, espessura, luz
     * principal), lidos quando ele aplica o vidro em algo do SystemUI; sem isso, valores padrão.
     */
    static final Paint px = new Paint();
    static final android.graphics.RectF pr = new android.graphics.RectF();
    static int pxLogs;

    /**
     * Os parâmetros do vidro (os mesmos 21 números que o Hyper Eternity manda para o shader do material
     * avançado) seguem para o shader do número por uma linha de pixels no canto do próprio desenho do
     * número: o shader de mistura lê o desenho da view, então lê também esses pixels (e não os mostra).
     * Assim o relógio usa exatamente o mesmo vidro e os mesmos ajustes das notificações, plugin e launcher.
     */
    static String rcRaw;
    static int[] rcVal;

    /** Ajustes próprios do vidro do relógio, publicados pelo Hyper Eternity (mesmo processo). null = desligados. */
    static int[] rc() {
        String s = System.getProperty("hyper.eternity.relogio.vidro");
        if (s == null) return null;
        if (!s.equals(rcRaw)) {
            rcRaw = s;
            try {
                String[] a = s.split(",");
                int[] v = new int[a.length];
                for (int i = 0; i < a.length; i++) v[i] = Integer.parseInt(a[i].trim());
                rcVal = v.length >= 11 ? v : null;
            } catch (Throwable t) {
                rcVal = null;
            }
        }
        return rcVal != null && rcVal[0] == 1 ? rcVal : null;
    }

    static boolean rcListening;
    static String rcDone;
    static final Runnable rcRedo = new Runnable() {
        public void run() {
            if (!glass()) return;
            for (View n : new ArrayList<View>(numbers)) n.invalidate();
            // o desfoque só muda remontando o relógio
            rc();
            String k = rcRaw;
            if (k != null && !k.equals(rcDone)) {
                rcDone = k;
                Clocks.rebuildAll("ajuste do vidro do relógio");
            }
        }
    };

    /** O app do Hyper Eternity avisa quando um ajuste do material muda: redesenha os números. */
    static void rcListen(Context ctx) {
        if (rcListening) return;
        rcListening = true;
        try {
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                public void onReceive(Context c, android.content.Intent i) {
                    main.removeCallbacks(rcRedo);
                    main.postDelayed(rcRedo, 500);
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter("com.ilha.headsup.MATERIAL");
            ctx.getApplicationContext().registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            log("aviso dos ajustes do vidro: " + t);
        }
    }

    static void params(View v, Canvas c) {
        rcListen(v.getContext());
        int[] own = rc();
        if (own != null) {
            // refr, edge, light, veil, tint, r, g, b, stroke, blur
            int[] rgbo = {
                    rgbOf(231, 17, 90),
                    rgbOf(b8(own[1]), b8(own[3]), b8(own[4] * 2.55f)),
                    rgbOf(b8(own[6]), b8(own[7]), b8(own[8])),
                    rgbOf(b8(own[5] * 2.55f), b8(own[9] * 2.55f), b8(own[2]))};
            drawPx(c, rgbo);
            if (pxLogs++ < 2) log("vidro do relógio: ajustes próprios " + rcRaw);
            return;
        }
        float[] cfg = bloomCfg != null ? bloomCfg : LIB_DEFAULT;
        // do material avançado do Hyper Eternity: espessura (refração), luz principal, véu, cor própria
        // do vidro (cor e força), contorno e largura da lente
        int p13 = (int) cfg[13], p18 = (int) cfg[18], p19 = (int) cfg[19];
        float edge = (p13 >> 16) & 255;
        float thick = (p13 & 255) * 510f / 255f;
        float li = ((p18 >> 16) & 255) * 400f / 255f;
        float ta = (p19 & 255) * 200f / 255f;
        int tz = Math.round(cfg[9]);
        int tr = (tz >> 10) & 31, tg = (tz >> 5) & 31, tb = tz & 31;
        int[] rgb = {
                rgbOf(231, 17, 90),
                rgbOf(b8(thick * 0.45f), b8(li), b8((1f - cfg[2]) * 255)),
                rgbOf(tr * 255 / 31, tg * 255 / 31, tb * 255 / 31),
                rgbOf(b8(ta / 200f * 255 * 0.5f), b8(cfg[5] * 255), b8(Math.max(8f, edge * 0.4f)))};
        drawPx(c, rgb);
        if (pxLogs++ < 2) log("vidro da lib: refração " + Math.round(thick * 0.45f) + "px, luz " + Math.round(li) + "%, cor " + Integer.toHexString(rgb[2]) + " " + Math.round(ta) + "%, contorno " + Math.round(cfg[5] * 100) + "% (" + (cfg == LIB_DEFAULT ? "padrão" : "do Hyper Eternity") + ")");
    }

    static void drawPx(Canvas c, int[] rgb) {
        int sv = c.save();
        c.setMatrix(null);
        px.setAntiAlias(false);
        for (int i = 0; i < rgb.length; i++) {
            px.setColor(rgb[i]);
            pr.set(i, 0, i + 1, 1);
            c.drawRect(pr, px);
        }
        c.restoreToCount(sv);
    }

    static int rgbOf(int r, int g, int b) { return 0xff000000 | (r << 16) | (g << 8) | b; }

    static int b8(float f) { return Math.max(0, Math.min(255, Math.round(f))); }

    static int glassColor() {
        int r = 56, g = 60, b = 102;
        float[] c = bloomCfg;
        try {
            if (c != null && c.length >= 21 && c[20] == 8010524f) {
                int p13 = (int) c[13], p18 = (int) c[18];
                float edge = (p13 >> 16) & 255;                 // largura da lente, 0..255 px
                float thick = (p13 & 255) * 510f / 255f;         // espessura, 0..510 px
                float li = ((p18 >> 16) & 255) * 400f / 255f;    // luz principal, 0..400 %
                r = Math.round(edge * 0.8f);
                g = Math.round(thick * 0.75f);
                b = Math.round(li * 0.729f);
            }
        } catch (Throwable ignored) {}
        r = Math.max(12, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static boolean applying, guardOn;
    static int guardLogs;

    /**
     * Com o papel de parede com profundidade, o relógio do HOS4 troca o material da camada das horas
     * (desliga a mistura) depois que aplicamos o vidro: as horas ficavam brancas lisas. Com o vidro
     * ligado, só nós mexemos na mistura dos números.
     */
    static void guard() {
        if (guardOn) return;
        guardOn = true;
        XC_MethodHook h = new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                if (applying || !glass() || tvCls == null || !tvCls.isInstance(p.thisObject)) return;
                p.setResult(null);
                if (guardLogs++ < 4) log("vidro: o relógio tentou trocar a mistura de " + Glass.name((View) p.thisObject) + " (" + p.method.getName() + "), mantido o vidro");
            }
        };
        for (Method m : View.class.getDeclaredMethods()) {
            String n = m.getName();
            if (n.equals("setMiViewBlurMode") || n.equals("setMiBackgroundBlendColors") || n.equals("clearMiBackgroundBlendColor")
                    || n.equals("addMiBackgroundBlendColor")) {
                try { XposedBridge.hookMethod(m, h); } catch (Throwable ignored) {}
            }
        }
    }

    static void notifMaterial(View v) {
        guard();
        applying = true;
        try {
            notifMaterial0(v);
        } finally {
            applying = false;
        }
    }

    static void notifMaterial0(View v) {
        try {
            if (mBlurMode != null && mBlendColors != null) {
                int col = glassColor();
                ArrayList<Point> g = new ArrayList<Point>();
                g.add(new Point(col, GLASS_MODE));
                mBlurMode.invoke(v, 3);
                mBlendColors.invoke(v, g);
                v.setTag(TAG_NOTIF, Integer.valueOf(col));
                if (notifLogs++ < 6) log("vidro da lib (modo " + GLASS_MODE + ", lente " + ((col >> 16) & 255) / 2 + "px, espessura " + ((col >> 8) & 255) + ", luz " + (col & 255) + ") no número " + Glass.name(v));
                v.invalidate();
                return;
            }
            ArrayList<Point> l = notifList;
            if (l == null || mBlurMode == null || mBlendColors == null) {
                // ainda sem a lista (nenhum cartão apareceu): o fosco do relógio, que é o mesmo motor
                Glass.frost(v);
                v.setTag(TAG_NOTIF, FROST_KEY);
            } else {
                mBlurMode.invoke(v, 3);
                mBlendColors.invoke(v, new ArrayList<Point>(l));
                v.setTag(TAG_NOTIF, l);
            }
            v.invalidate();
        } catch (Throwable t) {
            if (logs++ < 8) log("material das notificações no número: " + t);
        }
    }

    // ------------------------------------------------------------------ mistura com o fundo

    static void blend(View v, int color) throws Exception {
        if (mBlurMode == null || (mBlendColors == null && mAddBlend == null)) return;
        int c = color | 0xff000000;
        mBlurMode.invoke(v, 3);
        if (mBlendColors != null) {
            ArrayList<Point> l = new ArrayList<Point>();
            l.add(new Point(c, Cfg.fxBlend));
            mBlendColors.invoke(v, l);
        } else {
            if (mClearOne != null) mClearOne.invoke(v);
            mAddBlend.invoke(v, c, Cfg.fxBlend);
        }
        v.invalidate();
        if (logs++ < 8) log("mistura modo " + Cfg.fxBlend + " cor " + Integer.toHexString(c));
    }

    // ------------------------------------------------------------------ pintura dos números

    static Bitmap wall, poster;
    static int wallW, wallH;
    static boolean wallLoading;
    static int duoA = 0xffff8fb8, duoB = 0xff8f9bff;
    static float tiltX, tiltY;

    static void paint(View tv) {
        try {
            Paint p = (Paint) fPaint.get(tv);
            if (p == null) return;
            numbers.add(tv);
            if (!shading()) {
                if (tv.getTag(TAG_SHADED) != null) {
                    p.setShader(null);
                    tv.setTag(TAG_SHADED, null);
                }
                return;
            }
            if (Cfg.fx == HOLO && listener == null && tv.isShown()) sensors();
            Shader s = shader(tv);
            p.setShader(s);
            tv.setTag(TAG_SHADED, s == null ? null : Boolean.TRUE);
        } catch (Throwable t) {
            if (logs++ < 8) log("pintura: " + t);
        }
    }

    static final int[] loc = new int[2];

    static Shader shader(View tv) {
        int w = Math.max(1, tv.getWidth()), h = Math.max(1, tv.getHeight());
        switch (Cfg.fx) {
            case WALL:
            case POSTER: {
                Bitmap b = Cfg.fx == POSTER ? poster : wall;
                if (b == null) {
                    loadWall(tv.getContext());
                    return null;
                }
                BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                tv.getLocationOnScreen(loc);
                Matrix m = new Matrix();
                float sc = Math.max(screenW(tv) / (float) b.getWidth(), screenH(tv) / (float) b.getHeight());
                m.setScale(sc, sc);
                m.postTranslate((screenW(tv) - b.getWidth() * sc) / 2f - loc[0], (screenH(tv) - b.getHeight() * sc) / 2f - loc[1]);
                s.setLocalMatrix(m);
                return s;
            }
            case DUO: {
                if (wall == null) loadWall(tv.getContext());
                return new LinearGradient(0, 0, w * 0.35f, h, duoA, duoB, Shader.TileMode.CLAMP);
            }
            case HOLO: {
                int[] c = {0xffffc4e1, 0xffffe0b8, 0xffd8ffd0, 0xffbfe8ff, 0xffd9c8ff, 0xffffc4e1};
                float span = Math.max(w, h) * 0.9f;
                // a inclinação do aparelho desliza e gira o arco-íris
                float ox = tiltX * span * 0.6f, oy = tiltY * span * 0.6f;
                LinearGradient g = new LinearGradient(ox, oy, ox + span, oy + span * 0.55f, c, null, Shader.TileMode.MIRROR);
                Matrix m = new Matrix();
                m.setRotate(tiltX * 25f, w / 2f, h / 2f);
                g.setLocalMatrix(m);
                return g;
            }
            default:
                return null;
        }
    }

    static int screenW(View v) { return v.getResources().getDisplayMetrics().widthPixels; }

    static int screenH(View v) { return v.getResources().getDisplayMetrics().heightPixels; }

    static boolean wallListener;

    /** Papel de parede do bloqueio, reduzido e mais vivo (saturação e brilho), e duas cores tiradas dele. */
    static void loadWall(final Context ctx) {
        if (wallLoading) return;
        wallLoading = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    WallpaperManager wm = WallpaperManager.getInstance(ctx);
                    if (!wallListener) {
                        wallListener = true;
                        wm.addOnColorsChangedListener(new WallpaperManager.OnColorsChangedListener() {
                            public void onColorsChanged(android.app.WallpaperColors colors, int which) {
                                wall = null;
                                wallLoading = false;
                                loadWall(ctx);
                            }
                        }, main);
                    }
                    Drawable d = null;
                    try { d = wm.getDrawable(WallpaperManager.FLAG_LOCK); } catch (Throwable ignored) {}
                    if (d == null) d = wm.getDrawable();
                    if (d == null) { log("papel de parede indisponível"); return; }
                    int sw = ctx.getResources().getDisplayMetrics().widthPixels / 2;
                    int dw = Math.max(1, d.getIntrinsicWidth()), dh = Math.max(1, d.getIntrinsicHeight());
                    float sc = sw / (float) dw;
                    int bw = Math.max(1, Math.round(dw * sc)), bh = Math.max(1, Math.round(dh * sc));
                    Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(b);
                    d.setBounds(0, 0, bw, bh);
                    ColorMatrix cm = new ColorMatrix();
                    cm.setSaturation(1.45f);
                    ColorMatrix br = new ColorMatrix(new float[]{1.12f, 0, 0, 0, 18, 0, 1.12f, 0, 0, 18, 0, 0, 1.12f, 0, 18, 0, 0, 0, 1, 0});
                    cm.postConcat(br);
                    d.setColorFilter(new ColorMatrixColorFilter(cm));
                    d.draw(c);
                    d.setColorFilter(null);
                    pickDuo(b);
                    poster = duotone(b, duoA);
                    LiquidGlass.prepare(b);
                    wall = b;
                    log("papel de parede " + dw + "x" + dh + " carregado; cores " + Integer.toHexString(duoA) + " / " + Integer.toHexString(duoB));
                    main.post(new Runnable() {
                        public void run() {
                            for (View n : new ArrayList<View>(numbers)) n.invalidate();
                        }
                    });
                } catch (Throwable t) {
                    log("papel de parede: " + t);
                } finally {
                    wallLoading = wall != null;
                }
            }
        }, "relogios-papel").start();
    }

    /** Pôster: a foto em duas tintas (sombra escura e luz clara do mesmo tom), com mais contraste. */
    static Bitmap duotone(Bitmap src, int tone) {
        float[] hsv = new float[3];
        Color.colorToHSV(tone, hsv);
        float[] dk = {hsv[0], Math.min(1f, hsv[1] * 0.9f + 0.1f), 0.16f};
        float[] lt = {hsv[0], Math.max(0.25f, hsv[1] * 0.55f), 1f};
        int d = Color.HSVToColor(dk), l = Color.HSVToColor(lt);
        float dr = Color.red(d), dg = Color.green(d), db = Color.blue(d);
        float kr = (Color.red(l) - dr) / 255f, kg = (Color.green(l) - dg) / 255f, kb = (Color.blue(l) - db) / 255f;
        // luminância com contraste 1.35 em torno do meio, depois mapeada entre as duas tintas
        float c = 1.35f, o = -0.175f * 255f;
        float lr = 0.2126f * c, lg = 0.7152f * c, lb = 0.0722f * c;
        ColorMatrix m = new ColorMatrix(new float[]{
                kr * lr, kr * lg, kr * lb, 0, dr + kr * o,
                kg * lr, kg * lg, kg * lb, 0, dg + kg * o,
                kb * lr, kb * lg, kb * lb, 0, db + kb * o,
                0, 0, 0, 1, 0});
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(m));
        new Canvas(out).drawBitmap(src, 0, 0, p);
        return out;
    }

    /** Duas cores: a mais viva do papel de parede e uma vizinha dela, mais clara e com o tom girado. */
    static void pickDuo(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 24, 48, true);
        float best = -1;
        int pick = duoA;
        float[] hsv = new float[3];
        for (int y = 0; y < s.getHeight(); y++) {
            for (int x = 0; x < s.getWidth(); x++) {
                int c = s.getPixel(x, y);
                Color.colorToHSV(c, hsv);
                float score = hsv[1] * (0.35f + hsv[2]);
                if (score > best) { best = score; pick = c; }
            }
        }
        Color.colorToHSV(pick, hsv);
        hsv[1] = Math.max(0.45f, Math.min(0.85f, hsv[1]));
        hsv[2] = Math.max(0.85f, hsv[2]);
        duoA = Color.HSVToColor(hsv);
        hsv[0] = (hsv[0] + 48f) % 360f;
        hsv[1] *= 0.7f;
        hsv[2] = 1f;
        duoB = Color.HSVToColor(hsv);
    }

    // ------------------------------------------------------------------ filtros sobre o relógio inteiro

    static final String GRAIN_SRC =
            "uniform shader content;\n" +
            "uniform float amt;\n" +
            "float h(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453); }\n" +
            "half4 main(float2 p) {\n" +
            "  half4 c = content.eval(p);\n" +
            "  float n = h(floor(p * 0.5)) - 0.5;\n" +
            "  c.rgb = clamp(c.rgb + half3(n * amt) * c.a, half3(0), half3(c.a));\n" +
            "  return c;\n" +
            "}\n";

    static final String RGB_SRC =
            "uniform shader content;\n" +
            "uniform float off;\n" +
            "half4 main(float2 p) {\n" +
            "  half4 r = content.eval(p + float2(off, 0));\n" +
            "  half4 g = content.eval(p);\n" +
            "  half4 b = content.eval(p - float2(off, 0));\n" +
            "  half a = max(max(r.a, g.a), b.a);\n" +
            "  return half4(r.r, g.g, b.b, a);\n" +
            "}\n";

    static final String TRAIL_SRC =
            "uniform shader content;\n" +
            "uniform float2 dir;\n" +
            "uniform float len;\n" +
            "layout(color) uniform half4 c1;\n" +
            "layout(color) uniform half4 c2;\n" +
            "layout(color) uniform half4 c3;\n" +
            "float h(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453); }\n" +
            "half4 main(float2 p) {\n" +
            "  half4 base = content.eval(p);\n" +
            "  if (base.a > 0.98) return base;\n" +
            "  half4 acc = half4(0);\n" +
            "  for (int i = 1; i <= 26; i++) {\n" +
            "    float t = float(i) / 26.0;\n" +
            // rastro em curva: atrás do número (à esquerda), subindo conforme se afasta dele
            "    float2 q = p + dir * len * t + float2(0.0, len * 0.42 * t * t);\n" +
            "    half a = content.eval(q).a;\n" +
            "    if (a > 0.05) {\n" +
            "      half4 col = mix(c1, c2, half(smoothstep(0.0, 0.22, t)));\n" +
            "      col = mix(col, c3, half(smoothstep(0.22, 1.0, t)));\n" +
            "      half f = half((1.0 - t) * (1.0 - t)) * a;\n" +
            "      half n = half(h(floor(p * 0.5)) - 0.5) * 0.25;\n" +
            "      acc = half4(clamp(col.rgb + n, half3(0), half3(1)) * f, f);\n" +
            "      break;\n" +
            "    }\n" +
            "  }\n" +
            "  return base + acc * (1.0 - base.a);\n" +
            "}\n";

    static void filter(View box) {
        try {
            if (Cfg.fx == GRAIN || Cfg.fx == POSTER) {
                RuntimeShader s = new RuntimeShader(GRAIN_SRC);
                s.setFloatUniform("amt", Cfg.fx == POSTER ? 0.55f : 0.38f);
                box.setRenderEffect(RenderEffect.createRuntimeShaderEffect(s, "content"));
            } else if (Cfg.fx == TRAIL) {
                float dp = box.getResources().getDisplayMetrics().density;
                RuntimeShader s = new RuntimeShader(TRAIL_SRC);
                s.setFloatUniform("dir", 1f, 0.18f);
                s.setFloatUniform("len", 130f * dp);
                s.setColorUniform("c1", 0xff6dff5a);   // borda verde-limão colada ao número
                s.setColorUniform("c2", 0xffb7a6ff);   // lavanda
                s.setColorUniform("c3", 0xff8a1cff);   // roxo no fim do rastro
                box.setRenderEffect(RenderEffect.createRuntimeShaderEffect(s, "content"));
            } else if (Cfg.fx == RGB) {
                RuntimeShader s = new RuntimeShader(RGB_SRC);
                s.setFloatUniform("off", box.getResources().getDisplayMetrics().density * 2.5f);
                box.setRenderEffect(RenderEffect.createRuntimeShaderEffect(s, "content"));
            } else {
                box.setRenderEffect(null);
            }
        } catch (Throwable t) {
            if (logs++ < 8) log("filtro: " + t);
        }
    }

    // ------------------------------------------------------------------ inclinação (holográfico)

    static SensorEventListener listener;
    static long lastTilt;

    static void sensors() {
        boolean want = false;
        if (Cfg.fx == HOLO) {
            for (View n : new ArrayList<View>(numbers)) if (n.isShown()) want = true;
        }
        try {
            View any = null;
            for (View b : new ArrayList<View>(clocks)) { any = b; break; }
            if (any == null) return;
            SensorManager sm = (SensorManager) any.getContext().getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return;
            if (want && listener == null) {
                Sensor g = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
                if (g == null) g = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (g == null) return;
                listener = new SensorEventListener() {
                    public void onSensorChanged(SensorEvent e) {
                        float x = Math.max(-1f, Math.min(1f, e.values[0] / 9.81f));
                        float y = Math.max(-1f, Math.min(1f, (e.values[1] - 6.5f) / 9.81f));
                        tiltX += (x - tiltX) * 0.25f;
                        tiltY += (y - tiltY) * 0.25f;
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - lastTilt < 33) return;
                        lastTilt = now;
                        boolean any = false;
                        for (View n : new ArrayList<View>(numbers)) {
                            if (n.isShown()) { any = true; n.invalidate(); }
                        }
                        // tela apagada ou relógio fora da vista: solta o sensor (volta no próximo desenho)
                        if (!any) main.post(new Runnable() { public void run() { sensors(); } });
                    }

                    public void onAccuracyChanged(Sensor s, int a) {}
                };
                sm.registerListener(listener, g, SensorManager.SENSOR_DELAY_GAME, main);
                log("holográfico: acompanhando a inclinação");
            } else if (!want && listener != null) {
                sm.unregisterListener(listener);
                listener = null;
            }
        } catch (Throwable t) {
            if (logs++ < 8) log("sensor: " + t);
        }
    }
}
