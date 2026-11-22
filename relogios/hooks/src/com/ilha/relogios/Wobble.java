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
    static Bitmap wall, mask, subj;
    static String maskOf, subjOf;

    /** Máscara de uma área: 0 imagem completa, 1 pintada, 2 assunto, 3 plano de fundo (assunto ao contrário). */
    static Bitmap maskFor(int area) {
        if (area == 1) return mask;
        if (area == 2 || area == 3) return subj;
        return null;
    }
    static boolean loading, receivers, sensing;
    static float x, y, vx, vy;
    /** Brilho: inclinação suavizada (-1..1) e se a camada está à mostra por causa dele. */
    static float tx, ty;
    static boolean glowOn;
    static long last;
    static boolean running;
    static int logs;

    static void log(String s) {
        if (logs++ < 30) XposedBridge.log("[Relógios HOS4] balanço: " + s);
    }

    static final String SRC =
            "uniform shader img;\n" +
            "uniform shader mask;\n" +
            "uniform shader gmask;\n" +
            "uniform float2 off;\n" +
            "uniform float full;\n" +
            "uniform float inv;\n" +
            "uniform float gfull;\n" +
            "uniform float ginv;\n" +
            "uniform float glow;\n" +
            "uniform float2 light;\n" +
            "uniform float rad;\n" +
            "uniform float hue;\n" +
            "half4 main(float2 p) {\n" +
            "  float m0 = mask.eval(p).a;\n" +
            "  float m = mix(mix(m0, 1.0 - m0, inv), 1.0, full);\n" +
            "  half4 c = img.eval(p - off * m);\n" +
            "  if (glow > 0.001) {\n" +
            // brilho: um foco de luz que corre com a inclinação e acende mais as partes claras
            "    float2 q = (p - light) / rad;\n" +
            "    float g = exp(-dot(q, q) * 1.4);\n" +
            "    float lum = dot(float3(c.rgb), float3(0.299, 0.587, 0.114));\n" +
            "    float3 rgb = float3(c.rgb);\n" +
            // as cores vivas giram de tom com a inclinação (como a aurora do tema Brilho)
            "    float mx = max(rgb.r, max(rgb.g, rgb.b)), mn = min(rgb.r, min(rgb.g, rgb.b));\n" +
            "    float sat = (mx - mn) / max(mx, 0.001);\n" +
            "    float a = hue * glow * sat;\n" +
            "    float cs = cos(a), sn = sin(a);\n" +
            "    float3 yiq = float3(dot(rgb, float3(0.299, 0.587, 0.114)), dot(rgb, float3(0.596, -0.274, -0.322)), dot(rgb, float3(0.211, -0.523, 0.312)));\n" +
            "    yiq.yz = float2(yiq.y * cs - yiq.z * sn, yiq.y * sn + yiq.z * cs);\n" +
            "    rgb = float3(dot(yiq, float3(1.0, 0.956, 0.621)), dot(yiq, float3(1.0, -0.272, -0.647)), dot(yiq, float3(1.0, -1.106, 1.703)));\n" +
            "    rgb += rgb * g * glow * (0.25 + lum * 1.1);\n" +
            // só na área escolhida para o brilho (pintada, assunto, plano de fundo ou tudo)
            "    float g0 = gmask.eval(p).a;\n" +
            "    float gm = mix(mix(g0, 1.0 - g0, ginv), 1.0, gfull);\n" +
            "    rgb = mix(float3(c.rgb), rgb, smoothstep(0.0, 0.5, gm));\n" +
            "    return half4(half3(clamp(rgb, 0.0, 1.0)), 1.0);\n" +
            "  }\n" +
            "  return c * half(mix(smoothstep(0.0, 0.12, m), 1.0, full));\n" +
            "}\n";

    /** Vista com a cópia do papel de parede. */
    static final class Layer extends View {
        final Paint paint = new Paint();
        RuntimeShader shader;
        Bitmap usedWall, usedMask, usedG;
        int usedW, usedH;

        Layer(Context c) {
            super(c);
            setWillNotDraw(false);
        }

        void prepare() {
            int w = getWidth(), h = getHeight();
            if (wall == null || w <= 0 || h <= 0) { shader = null; return; }
            Bitmap mk = maskFor(Cfg.wobArea), gk = maskFor(Cfg.glowArea);
            if (shader != null && usedWall == wall && usedMask == mk && usedG == gk && usedW == w && usedH == h) return;
            usedWall = wall;
            usedMask = mk;
            usedG = gk;
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
            shader.setInputShader("mask", maskShader(mk, w, h));
            shader.setFloatUniform("full", mk != null ? 0f : 1f);
            shader.setFloatUniform("inv", mk != null && Cfg.wobArea == 3 ? 1f : 0f);
            shader.setInputShader("gmask", maskShader(gk, w, h));
            shader.setFloatUniform("gfull", gk != null ? 0f : 1f);
            shader.setFloatUniform("ginv", gk != null && Cfg.glowArea == 3 ? 1f : 0f);
            paint.setShader(shader);
        }

        static BitmapShader maskShader(Bitmap mk, int w, int h) {
            Bitmap mb = mk != null ? mk : Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            BitmapShader ms = new BitmapShader(mb, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix mm = new Matrix();
            mm.setScale(w / (float) mb.getWidth(), h / (float) mb.getHeight());
            ms.setLocalMatrix(mm);
            ms.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            return ms;
        }

        protected void onDraw(Canvas c) {
            prepare();
            if (shader == null) return;
            shader.setFloatUniform("off", x, y);
            float w = getWidth(), h = getHeight();
            boolean gl = Cfg.glow && glowOn;
            shader.setFloatUniform("glow", gl ? (Cfg.glowForce <= 0 ? 50 : Cfg.glowForce) / 50f : 0f);
            shader.setFloatUniform("light", w / 2f + tx * w * 0.7f, h * 0.42f + ty * h * 0.45f);
            shader.setFloatUniform("rad", h * 0.42f);
            shader.setFloatUniform("hue", tx * 0.9f - ty * 0.5f);
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
        String ss = Cfg.subjMask == null ? "" : Cfg.subjMask;
        if (!ss.equals(subjOf)) {
            subjOf = ss;
            subj = decode(ss);
        }
        String ps = Cfg.wobMask == null ? "" : Cfg.wobMask;
        if (!ps.equals(maskOf)) {
            maskOf = ps;
            mask = decode(ps);
        }
        if (layer != null) layer.shader = null;   // refaz com as áreas atuais
        tilt(ctx, Cfg.glow && ready(ctx));
        if (Cfg.glow && wall == null) loadWall(ctx);
        if (!Cfg.wob) {
            stop();
            sense(ctx, false);
            return;
        }
        Safe.touch("balanco");
        if (wall == null) loadWall(ctx);
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
            return (Cfg.wob || Cfg.glow) && km != null && km.isKeyguardLocked() && pm != null && pm.isInteractive();
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
                    if (Cfg.wob || Cfg.glow) loadWall(ctx);
                } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    if (ready(ctx) && Cfg.glow) tilt(ctx, true);
                    if (ready(ctx) && Cfg.wob) {
                        sense(ctx, true);
                        // acendeu no bloqueio: um empurrão para baixo, que a mola devolve
                        kick(0f, 1500f * strength(ctx));
                    }
                } else {
                    tilt(ctx, false);
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

    // ------------------------------------------------------------------ brilho ao inclinar

    static SensorEventListener tiltL;
    static boolean tilting;
    static long lastTiltDraw;

    static void tilt(final Context ctx, boolean on) {
        try {
            SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) return;
            if (on && !tilting) {
                if (Safe.blocked("brilho")) return;
                Safe.touch("brilho");
                Sensor g = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
                if (g == null) g = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (g == null) return;
                if (tiltL == null) {
                    tiltL = new SensorEventListener() {
                        public void onSensorChanged(SensorEvent e) {
                            float nx = Math.max(-1f, Math.min(1f, -e.values[0] / 6f));
                            float ny = Math.max(-1f, Math.min(1f, (e.values[1] - 7f) / 5f));
                            tx += (nx - tx) * 0.12f;
                            ty += (ny - ty) * 0.12f;
                            long now = SystemClock.uptimeMillis();
                            if (now - lastTiltDraw < 33) return;
                            lastTiltDraw = now;
                            showGlow();
                        }

                        public void onAccuracyChanged(Sensor s, int a) {}
                    };
                }
                sm.registerListener(tiltL, g, SensorManager.SENSOR_DELAY_GAME, main);
                tilting = true;
                log("brilho: acompanhando a inclinação");
            } else if (!on && tilting) {
                sm.unregisterListener(tiltL);
                tilting = false;
                glowOn = false;
                if (!running && layer != null) layer.setVisibility(View.GONE);
            }
        } catch (Throwable t) {
            log("brilho: " + t);
        }
    }

    static void showGlow() {
        if (layer == null || wall == null) return;
        boolean ok = Cfg.glow && lockShown();
        if (ok != glowOn) {
            glowOn = ok;
            if (!ok && !running) { layer.setVisibility(View.GONE); return; }
        }
        if (!ok) return;
        if (layer.getVisibility() != View.VISIBLE) layer.setVisibility(View.VISIBLE);
        layer.invalidate();
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
        if (hmcBackdrop(v)) return false;
        return true;
    }

    // ------------------------------------------------------------------ fundo do HyperMusicCover

    static Boolean hmcInstalled;
    static java.lang.ref.WeakReference<View> player;
    static long playerAt;
    static boolean hmcLogged;

    /**
     * Com música no bloqueio, o HyperMusicCover troca o papel de parede pela capa desfocada (na janela
     * do papel de parede). A nossa cópia fica na janela do bloqueio, por cima dela: a área escolhida
     * do papel de parede original aparecia em cima do fundo do HMC. Com o player à mostra e o HMC
     * instalado, a cópia não aparece.
     */
    static boolean hmcBackdrop(View any) {
        try {
            if (hmcInstalled == null) {
                boolean ok = false;
                for (String pk : new String[]{"com.yzc26623.HyperMusicCoverEnhanced", "com.os4.musiccover"}) {
                    try { any.getContext().getPackageManager().getPackageInfo(pk, 0); ok = true; break; } catch (Throwable ignored) { }
                }
                hmcInstalled = ok;
            }
            if (!hmcInstalled) return false;
            View pl = player == null ? null : player.get();
            long now = SystemClock.uptimeMillis();
            if ((pl == null || !pl.isAttachedToWindow()) && now - playerAt > 1000) {
                playerAt = now;
                View root = any.getRootView();
                for (String id : new String[]{"media_bg", "mi_media_controls", "keyguard_media_container"}) {
                    int rid = any.getResources().getIdentifier(id, "id", "com.android.systemui");
                    View f = rid == 0 ? null : root.findViewById(rid);
                    if (f != null) { pl = f; player = new java.lang.ref.WeakReference<View>(f); break; }
                }
            }
            boolean on = pl != null && pl.isShown() && pl.getHeight() > 0;
            if (on && !hmcLogged) { hmcLogged = true; log("com o fundo do HyperMusicCover (música no bloqueio), a cópia do papel de parede não aparece"); }
            return on;
        } catch (Throwable t) {
            return false;
        }
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
        if (glowOn && layer != null) { layer.invalidate(); return; }   // o brilho segura a camada à mostra
        if (layer != null && layer.getVisibility() != View.GONE) layer.setVisibility(View.GONE);
    }
}
