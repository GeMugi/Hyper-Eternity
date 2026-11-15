package com.ilha.relogios;

import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import com.ilha.relogios.xp.XposedBridge;

/**
 * Balanço do papel de parede no bloqueio (teste), no estilo do efeito dinâmico do OriginOS: ao
 * sacudir o aparelho ou acender a tela, uma área do papel de parede (a imagem inteira ou a parte
 * pintada no app) balança como gelatina e volta ao lugar.
 *
 * Uma vista atrás de tudo no bloqueio desenha uma cópia do papel de parede deslocada por uma mola;
 * ela só aparece enquanto há movimento e some quando a mola para.
 */
final class Wobble {
    private Wobble() {}

    static final int MW = 72, MH = 160;   // grade da área pintada (o app usa a mesma)
    static final String REQ = Keys.PKG + ".WALL_REQ", RES = Keys.PKG + ".WALL_RES";

    static final Handler main = new Handler(Looper.getMainLooper());
    static Layer layer;
    static java.lang.ref.WeakReference<View> anchor;
    static Bitmap wall, mask;
    static String maskOf;
    static boolean loading, receivers, sensing;
    static float x, y, vx, vy;
    static long last;
    static boolean running;
    static int logs;

    static void log(String s) {
        if (logs++ < 30) XposedBridge.log("[Relógios HOS4] balanço: " + s);
    }

    static final String SRC =
            "uniform shader img;\n" +
            "uniform shader mask;\n" +
            "uniform float2 off;\n" +
            "uniform float full;\n" +
            "half4 main(float2 p) {\n" +
            "  float m = mix(mask.eval(p).a, 1.0, full);\n" +
            "  half4 c = img.eval(p - off * m);\n" +
            "  return c * half(mix(smoothstep(0.0, 0.12, m), 1.0, full));\n" +
            "}\n";

    /** Vista com a cópia do papel de parede. */
    static final class Layer extends View {
        final Paint paint = new Paint();
        RuntimeShader shader;
        Bitmap usedWall, usedMask;
        int usedW, usedH;

        Layer(Context c) {
            super(c);
            setWillNotDraw(false);
        }

        void prepare() {
            int w = getWidth(), h = getHeight();
            if (wall == null || w <= 0 || h <= 0) { shader = null; return; }
            Bitmap mk = Cfg.wobArea == 1 ? mask : null;
            if (shader != null && usedWall == wall && usedMask == mk && usedW == w && usedH == h) return;
            usedWall = wall;
            usedMask = mk;
            usedW = w;
            usedH = h;
            shader = new RuntimeShader(SRC);
            BitmapShader img = new BitmapShader(wall, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float sc = Math.max(w / (float) wall.getWidth(), h / (float) wall.getHeight());
            Matrix m = new Matrix();
            m.setScale(sc, sc);
            m.postTranslate((w - wall.getWidth() * sc) / 2f, (h - wall.getHeight() * sc) / 2f);
            img.setLocalMatrix(m);
            img.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            shader.setInputShader("img", img);
            Bitmap mb = mk != null ? mk : Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            BitmapShader ms = new BitmapShader(mb, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix mm = new Matrix();
            mm.setScale(w / (float) mb.getWidth(), h / (float) mb.getHeight());
            ms.setLocalMatrix(mm);
            ms.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            shader.setInputShader("mask", ms);
            shader.setFloatUniform("full", mk != null ? 0f : 1f);
            paint.setShader(shader);
        }

        protected void onDraw(Canvas c) {
            prepare();
            if (shader == null) return;
            shader.setFloatUniform("off", x, y);
            c.drawRect(0, 0, getWidth(), getHeight(), paint);
        }
    }

    /** Uma vista do bloqueio apareceu: a camada entra atrás de tudo na janela dela. */
    static void host(View any) {
        try {
            final Context ctx = any.getContext().getApplicationContext();
            anchor = new java.lang.ref.WeakReference<View>(any);   // o relógio do bloqueio: some/apaga ao desbloquear
            receivers(ctx);
            View root = any.getRootView();
            if (!(root instanceof ViewGroup)) return;
            if (layer != null && layer.getParent() == root) return;
            if (layer != null && layer.getParent() instanceof ViewGroup) ((ViewGroup) layer.getParent()).removeView(layer);
            layer = new Layer(root.getContext());
            layer.setVisibility(View.GONE);
            ((ViewGroup) root).addView(layer, 0, new ViewGroup.LayoutParams(-1, -1));
            log("camada no bloqueio (" + root.getClass().getSimpleName() + ")");
            Cfg.watch(new Runnable() {
                public void run() { settings(ctx); }
            });
            settings(ctx);
        } catch (Throwable t) {
            log("camada: " + t);
        }
    }

    static void settings(Context ctx) {
        if (!Cfg.wob) {
            stop();
            sense(ctx, false);
            return;
        }
        Safe.touch("balanco");
        if (wall == null) loadWall(ctx);
        String s = Cfg.wobMask == null ? "" : Cfg.wobMask;
        if (!s.equals(maskOf)) {
            maskOf = s;
            mask = decode(s);
            if (layer != null) layer.usedMask = null;
        }
        sense(ctx, ready(ctx));
    }

    static Bitmap decode(String s) {
        try {
            byte[] d = Base64.decode(s, Base64.DEFAULT);
            if (d.length != MW * MH) return null;
            int[] px = new int[MW * MH];
            boolean any = false;
            for (int i = 0; i < px.length; i++) {
                int a = d[i] & 0xff;
                any |= a > 0;
                px[i] = a << 24;
            }
            if (!any) return null;
            return Bitmap.createBitmap(px, MW, MH, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return null;
        }
    }

    /** No bloqueio, com a tela acesa. */
    static boolean ready(Context ctx) {
        try {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return Cfg.wob && km != null && km.isKeyguardLocked() && pm != null && pm.isInteractive();
        } catch (Throwable t) {
            return false;
        }
    }

    static void receivers(final Context ctx) {
        if (receivers) return;
        receivers = true;
        if (Safe.blocked("balanco")) {
            log("desligado pela proteção (o SystemUI caiu em sequência com ele)");
            return;
        }
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_WALLPAPER_CHANGED);
        ctx.registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_WALLPAPER_CHANGED.equals(a)) {
                    wall = null;
                    if (Cfg.wob) loadWall(ctx);
                } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    if (ready(ctx)) {
                        sense(ctx, true);
                        // acendeu no bloqueio: um empurrão para baixo, que a mola devolve
                        kick(0f, 1500f * strength(ctx));
                    }
                } else {
                    sense(ctx, false);
                    stop();
                }
            }
        }, f, Context.RECEIVER_EXPORTED);
        // o app pede uma miniatura do papel de parede para pintar a área
        ctx.registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                thumb(ctx);
            }
        }, new IntentFilter(REQ), Context.RECEIVER_EXPORTED);
    }

    static float strength(Context ctx) {
        return ctx.getResources().getDisplayMetrics().density / 3f * (Cfg.wobForce <= 0 ? 50 : Cfg.wobForce) / 50f;
    }

    static void loadWall(final Context ctx) {
        if (loading) return;
        loading = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    Bitmap b = read(ctx, 0.75f);
                    if (b != null) {
                        wall = b;
                        log("papel de parede " + b.getWidth() + "x" + b.getHeight());
                    } else log("papel de parede indisponível");
                } catch (Throwable t) {
                    log("papel de parede: " + t);
                } finally {
                    loading = false;
                }
            }
        }, "relogios-balanco").start();
    }

    static Bitmap read(Context ctx, float part) {
        WallpaperManager wm = WallpaperManager.getInstance(ctx);
        Drawable d = null;
        try { d = wm.getDrawable(WallpaperManager.FLAG_LOCK); } catch (Throwable ignored) {}
        if (d == null) d = wm.getDrawable();
        if (d == null) return null;
        int dw = Math.max(1, d.getIntrinsicWidth()), dh = Math.max(1, d.getIntrinsicHeight());
        float sc = ctx.getResources().getDisplayMetrics().widthPixels * part / dw;
        // corta no formato da tela (centro), como o bloqueio mostra
        float ar = ctx.getResources().getDisplayMetrics().heightPixels / (float) ctx.getResources().getDisplayMetrics().widthPixels;
        if (dh / (float) dw < ar) sc = ctx.getResources().getDisplayMetrics().heightPixels * part / dh;
        int bw = Math.max(1, Math.round(dw * sc)), bh = Math.max(1, Math.round(dh * sc));
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, bw, bh);
        d.draw(new Canvas(b));
        return b;
    }

    static void thumb(final Context ctx) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    Bitmap b = read(ctx, 0.3f);
                    if (b == null) return;
                    // recorte central no formato da tela
                    int sw = ctx.getResources().getDisplayMetrics().widthPixels, sh = ctx.getResources().getDisplayMetrics().heightPixels;
                    int cw = b.getWidth(), ch = Math.min(b.getHeight(), Math.round(cw * sh / (float) sw));
                    if (ch == b.getHeight()) cw = Math.min(b.getWidth(), Math.round(ch * sw / (float) sh));
                    Bitmap cut = Bitmap.createBitmap(b, (b.getWidth() - cw) / 2, (b.getHeight() - ch) / 2, cw, ch);
                    java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                    cut.compress(Bitmap.CompressFormat.JPEG, 80, o);
                    ctx.sendBroadcast(new Intent(RES).setPackage(Keys.PKG).putExtra("jpg", o.toByteArray()));
                    log("miniatura enviada ao app (" + o.size() / 1024 + " KB)");
                } catch (Throwable t) {
                    log("miniatura: " + t);
                }
            }
        }, "relogios-miniatura").start();
    }

    // ------------------------------------------------------------------ sacudida

    static SensorEventListener listener;

    static void sense(final Context ctx, boolean on) {
        try {
            SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return;
            if (on && !sensing) {
                Sensor s = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
                if (s == null) return;
                if (listener == null) {
                    listener = new SensorEventListener() {
                        public void onSensorChanged(SensorEvent e) {
                            float ax = e.values[0], ay = e.values[1];
                            if (Math.abs(ax) < 0.6f && Math.abs(ay) < 0.6f) return;   // mão parada
                            float g = 26f * strength(ctx);
                            // o conteúdo "fica para trás": desloca contra a aceleração do aparelho
                            vx -= ax * g;
                            vy += ay * g;
                            run();
                        }

                        public void onAccuracyChanged(Sensor s, int a) {}
                    };
                }
                sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_GAME, main);
                sensing = true;
            } else if (!on && sensing) {
                sm.unregisterListener(listener);
                sensing = false;
            }
        } catch (Throwable t) {
            log("sensor: " + t);
        }
    }

    static void kick(float dvx, float dvy) {
        vx += dvx;
        vy += dvy;
        run();
    }

    // ------------------------------------------------------------------ mola

    static final Choreographer.FrameCallback TICK = new Choreographer.FrameCallback() {
        public void doFrame(long ns) { step(); }
    };

    /**
     * O bloqueio está inteiro à mostra? Ao desbloquear, o relógio apaga e encolhe antes de o
     * sistema avisar; nesse instante a cópia do papel de parede não pode aparecer.
     */
    static boolean lockShown() {
        View v = anchor == null ? null : anchor.get();
        if (v == null || !v.isShown()) return false;
        float a = 1f;
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) {
            View x = (View) p;
            a *= x.getAlpha() * x.getTransitionAlpha();
            if (a < 0.97f || Math.abs(x.getScaleX() - 1f) > 0.01f) return false;
        }
        return true;
    }

    static void run() {
        if (running || layer == null || wall == null) return;
        if (!lockShown() || !ready(layer.getContext())) { vx = vy = 0f; return; }
        if (Cfg.wobArea == 1 && mask == null) return;
        running = true;
        last = SystemClock.uptimeMillis();
        layer.setVisibility(View.VISIBLE);
        Choreographer.getInstance().postFrameCallback(TICK);
    }

    static void step() {
        if (!running || layer == null) { running = false; return; }
        if (!lockShown()) { stop(); return; }
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.032f, (now - last) / 1000f);
        last = now;
        // mola pouco amortecida: passa do ponto algumas vezes antes de parar
        float k = 210f, c = 9.5f;
        for (int i = 0; i < 2; i++) {
            float h = dt / 2f;
            vx += (-k * x - c * vx) * h;
            vy += (-k * y - c * vy) * h;
            x += vx * h;
            y += vy * h;
        }
        float lim = 46f * layer.getResources().getDisplayMetrics().density;
        if (x > lim) { x = lim; vx = 0; } else if (x < -lim) { x = -lim; vx = 0; }
        if (y > lim) { y = lim; vy = 0; } else if (y < -lim) { y = -lim; vy = 0; }
        layer.invalidate();
        if (Math.abs(x) < 0.3f && Math.abs(y) < 0.3f && Math.abs(vx) < 6f && Math.abs(vy) < 6f) {
            stop();
            return;
        }
        Choreographer.getInstance().postFrameCallback(TICK);
    }

    static void stop() {
        running = false;
        x = y = vx = vy = 0f;
        if (layer != null && layer.getVisibility() != View.GONE) layer.setVisibility(View.GONE);
    }
}
