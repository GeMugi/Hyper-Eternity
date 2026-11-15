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

    static final int NONE = 0, WALL = 1, DUO = 2, HOLO = 3, GRAIN = 4, RGB = 5, POSTER = 6, TRAIL = 7;
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
                            if (!blending() || mContainer == null || !(p.args[0] instanceof View)) return;
                            try {
                                mContainer.invoke(null, p.args[0], p.args[1], Boolean.FALSE);
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
                protected void beforeHookedMethod(MethodHookParam p) {
                    paint((View) p.thisObject);
                }
            });
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
        boolean wasShade = lastFx == WALL || lastFx == DUO || lastFx == HOLO || lastFx == POSTER;
        lastFx = Cfg.fx;
        lastBlend = Cfg.fxBlend;
        if (fxChanged || blendChanged) {
            log("efeito " + Cfg.fx + ", mistura " + Cfg.fxBlend);
            if (Cfg.fx != NONE || Cfg.fxBlend != 0) Safe.touch("efeitos");
        }
        for (View b : new ArrayList<View>(clocks)) filter(b);
        for (View n : new ArrayList<View>(numbers)) n.invalidate();
        if (blendChanged || (fxChanged && (shading() || wasShade))) Clocks.rebuildAll("efeito mudou");
        sensors();
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
