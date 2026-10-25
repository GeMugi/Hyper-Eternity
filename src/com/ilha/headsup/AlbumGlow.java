package com.ilha.headsup;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Capa que gira e solta um brilho ao trocar de música, como no ColorOS 17.
 *
 * O HyperOS já tem o giro da capa (MiuiMediaAlbumAnimationUtils.startFlipAnimation), mas só
 * em aparelhos que ele considera de ponta: nos de nível médio-baixo ele troca a capa seca. Aqui,
 * quando ele pularia o giro, a capa gira do mesmo jeito (sai até 90°, troca a imagem de lado,
 * volta com um leve passo além). No meio do giro, quando a capa nova aparece, sai dela um
 * brilho suave na cor da capa, que se espalha pelo cartão e some.
 */
final class AlbumGlow {
    private AlbumGlow() {}

    static final String UTILS = "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaAlbumAnimationUtils";
    static final int TAG_FLIP = 0x7e1a7009;
    static final int TAG_GLOW = 0x7e1a700a;
    static final PathInterpolator IN = new PathInterpolator(0.55f, 0f, 1f, 0.45f);
    static final PathInterpolator BACK = new PathInterpolator(0.2f, 1.3f, 0.35f, 1f);
    static final PathInterpolator OUT = new PathInterpolator(0.2f, 0.9f, 0.3f, 1f);
    static final PathInterpolator SOFT = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    static Class listenerType;
    static boolean gated;
    static int logs;

    static final String CTRL = "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl";
    static final int TAG_PENDING = 0x7e1a700b;
    static final int TAG_LAST = 0x7e1a700c;
    static final long PENDING_MS = 6000L;
    static boolean inCardBind, flippedInBind;

    static void install(ClassLoader cl) {
        try {
            Class u = Class.forName(UTILS, false, cl);
            listenerType = Class.forName(UTILS + "$OnFlipListener", false, cl);
            try {
                Class cu = Class.forName("com.miui.utils.ComputilityUtils", false, cl);
                gated = flag(cu, "isLowLevel") || flag(cu, "isMidLowLevel");
            } catch (Throwable ignored) {
            }
            Method m = u.getDeclaredMethod("startFlipAnimation", View.class, boolean.class, boolean.class, listenerType);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        before(p);
                    } catch (Throwable t) {
                        if (logs++ < 6) ElasticCard.log("capa girando: erro " + t);
                    }
                }
            });
            // só o player normal (gaveta e bloqueio): a ilha fica como o HyperOS faz
            Class c = Class.forName(CTRL, false, cl);
            Method bind = null;
            for (Method k : c.getDeclaredMethods()) {
                if ("bindMediaData".equals(k.getName()) && k.getParameterTypes().length == 1) bind = k;
            }
            XposedBridge.hookMethod(bind, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    inCardBind = true;
                    flippedInBind = false;
                }

                protected void afterHookedMethod(MethodHookParam p) {
                    inCardBind = false;
                    try {
                        afterBind(p.thisObject);
                    } catch (Throwable t) {
                        if (logs++ < 6) ElasticCard.log("capa girando: erro " + t);
                    }
                }
            });
            ElasticCard.log("capa girando com brilho: instalada no player" + (gated ? " (o HyperOS não gira a capa neste aparelho; o giro vem daqui)" : ""));
        } catch (Throwable t) {
            ElasticCard.log("capa girando com brilho: animação da capa não encontrada " + t);
        }
    }

    static boolean flag(Class c, String name) {
        try {
            java.lang.reflect.Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * A troca de capa do player. O HyperOS só gira quando a música nova e a capa nova chegam
     * juntas; com o YouTube e outros, o nome chega antes e a capa num segundo momento, e aí
     * ela troca seca. Por isso a música nova fica anotada na capa por alguns segundos: a capa
     * que chegar nesse tempo gira e brilha.
     */
    static void before(XC_MethodHook.MethodHookParam p) {
        if (!inCardBind) return;
        Prefs.refresh();
        if (!Prefs.albumGlow) return;
        final View album = (View) p.args[0];
        final Object orig = p.args[3];
        if (album == null || orig == null) return;
        boolean isNew = Boolean.TRUE.equals(p.args[2]);
        Object pend = album.getTag(TAG_PENDING);
        boolean late = !isNew && pend instanceof Long && SystemClock.uptimeMillis() - ((Long) pend).longValue() < PENDING_MS;
        if (!isNew && !late) return;
        album.setTag(TAG_PENDING, null);
        flippedInBind = true;
        Object last = album.getTag(TAG_LAST);
        if (last instanceof Long && SystemClock.uptimeMillis() - ((Long) last).longValue() < 1200L) return;   // acabou de girar
        if (!album.isAttachedToWindow() || !album.isShown()) return;
        boolean positive = Boolean.TRUE.equals(p.args[1]);
        if (gated) {
            flip(album, positive, new Runnable() {
                public void run() {
                    callFlip(orig);
                    glowSafe(album);
                }
            });
            p.setResult(null);
        } else {
            album.setTag(TAG_LAST, Long.valueOf(SystemClock.uptimeMillis()));
            p.args[2] = Boolean.TRUE;
            p.args[3] = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class[]{listenerType}, new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    String n = method.getName();
                    if ("onFlip".equals(n)) {
                        Object r = method.invoke(orig, args);
                        glowSafe(album);
                        return r;
                    }
                    if ("equals".equals(n)) return Boolean.valueOf(proxy == args[0]);
                    if ("hashCode".equals(n)) return Integer.valueOf(System.identityHashCode(proxy));
                    if ("toString".equals(n)) return "ilha-flip";
                    return method.invoke(orig, args);
                }
            });
        }
        if (logs++ < 4) ElasticCard.log("capa girando: música nova" + (late ? " (capa chegou depois)" : "") + (gated ? ", giro daqui" : ", giro do HyperOS"));
    }

    /** Música nova sem capa nova ainda: anota e, se a capa não vier logo, gira com a capa atual. */
    static void afterBind(Object ctrl) {
        if (flippedInBind) return;
        Prefs.refresh();
        if (!Prefs.albumGlow) return;
        Object isNew = Refl.get(ctrl, "isNewSongUpdate");
        if (!Boolean.TRUE.equals(isNew)) return;
        Object holder = Refl.get(ctrl, "holder");
        Object av = Refl.get(holder, "albumView");
        if (!(av instanceof View)) return;
        final View album = (View) av;
        final Long token = Long.valueOf(SystemClock.uptimeMillis());
        album.setTag(TAG_PENDING, token);
        final boolean positive = !Boolean.FALSE.equals(Refl.get(ctrl, "isFlipPositive"));
        album.postDelayed(new Runnable() {
            public void run() {
                if (album.getTag(TAG_PENDING) != token) return;      // a capa chegou e já girou
                album.setTag(TAG_PENDING, null);
                if (!album.isAttachedToWindow() || !album.isShown()) return;
                flip(album, positive, new Runnable() {
                    public void run() {
                        glowSafe(album);
                    }
                });
                if (logs++ < 4) ElasticCard.log("capa girando: música nova sem capa nova, girou com a atual");
            }
        }, 1500L);
    }

    static void glowSafe(View album) {
        try {
            glow(album);
        } catch (Throwable t) {
            if (logs++ < 6) ElasticCard.log("capa girando: erro no brilho " + t);
        }
    }

    // ------------------------------------------------------------------ giro

    static boolean lastPositive = true;

    static void flip(final View v, boolean positive, final Runnable onSwap) {
        Object old = v.getTag(TAG_FLIP);
        Object last = v.getTag(TAG_LAST);
        if (old instanceof ValueAnimator || (last instanceof Long && SystemClock.uptimeMillis() - ((Long) last).longValue() < 1200L)) {
            // já girou agora há pouco (a capa chegou em duas partes): só troca, sem girar de novo
            onSwap.run();
            return;
        }
        v.setTag(TAG_LAST, Long.valueOf(SystemClock.uptimeMillis()));
        lastPositive = positive;
        v.setRotationY(0f);
        final Round round = Round.hold(v);
        final float dir = positive ? 1f : -1f;
        v.setCameraDistance(v.getResources().getDisplayMetrics().density * 9000f);
        final boolean[] swapped = new boolean[1];
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(560L);
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator an) {
                long t = an.getCurrentPlayTime();
                if (t < 170L && !swapped[0]) {
                    v.setRotationY(dir * 90f * IN.getInterpolation(t / 170f));
                    return;
                }
                if (!swapped[0]) {
                    swapped[0] = true;
                    onSwap.run();
                }
                float q = Math.min(1f, (t - 170f) / 390f);
                v.setRotationY(-dir * 90f * (1f - BACK.getInterpolation(q)));
                if (round != null) round.keep();
            }
        });
        a.addListener(new Animator.AnimatorListener() {
            public void onAnimationEnd(Animator an) {
                done();
            }

            public void onAnimationCancel(Animator an) {
                done();
            }

            void done() {
                if (!swapped[0]) {
                    swapped[0] = true;
                    onSwap.run();
                }
                v.setRotationY(0f);
                if (v.getTag(TAG_FLIP) == a) v.setTag(TAG_FLIP, null);
                if (round != null) round.release(v);
            }

            public void onAnimationStart(Animator an) {
            }

            public void onAnimationRepeat(Animator an) {
            }
        });
        v.setTag(TAG_FLIP, a);
        a.start();
    }

    /**
     * Mantém a capa arredondada durante o giro. Na troca de faixa o contorno arredondado da
     * capa é trocado por um quadrado por um instante (o HyperOS e o HyperMusicCover refazem o
     * contorno depois de pôr a imagem nova), e a capa aparecia quadrada no meio do giro.
     */
    static final class Round extends android.view.ViewOutlineProvider {
        final View img;
        final float r;
        final android.view.ViewOutlineProvider before;
        final boolean clipBefore;

        Round(View img, float r) {
            this.img = img;
            this.r = r;
            this.before = img.getOutlineProvider();
            this.clipBefore = img.getClipToOutline();
        }

        static Round hold(View album) {
            try {
                View img = album;
                if (album instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) album;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        View c = g.getChildAt(i);
                        if (c instanceof ImageView && c.getWidth() >= album.getWidth() / 2) {
                            img = c;
                            break;
                        }
                    }
                }
                float r = 0f;
                for (View v : new View[]{img, album}) {
                    if (r > 0f || v.getOutlineProvider() == null || v.getWidth() <= 0) continue;
                    android.graphics.Outline o = new android.graphics.Outline();
                    v.getOutlineProvider().getOutline(v, o);
                    if (v.getClipToOutline() || v == img) r = o.getRadius();
                }
                if (r <= 0f) r = Math.min(album.getWidth(), album.getHeight()) * 0.22f;
                Round rd = new Round(img, r);
                rd.keep();
                return rd;
            } catch (Throwable t) {
                return null;
            }
        }

        public void getOutline(View view, android.graphics.Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
        }

        void keep() {
            if (img.getOutlineProvider() != this) img.setOutlineProvider(this);
            if (!img.getClipToOutline()) img.setClipToOutline(true);
        }

        void release(final View album) {
            // se ninguém repôs o contorno, o nosso (arredondado, no mesmo raio) fica
        }
    }

    static void callFlip(Object listener) {
        try {
            listenerType.getMethod("onFlip").invoke(listener);
        } catch (Throwable t) {
            if (logs++ < 6) ElasticCard.log("capa girando: erro ao trocar a capa " + t);
        }
    }

    // ------------------------------------------------------------------ brilho

    static void glow(View album) {
        if (!(album.getParent() instanceof ViewGroup)) return;
        ViewGroup host = (ViewGroup) album.getParent();
        if (!host.isAttachedToWindow() || host.getWidth() <= 0) return;
        if (!realArt(album)) return;       // ainda é o ícone do app, não a capa
        Object o = host.getTag(TAG_GLOW);
        if (o instanceof Glow && !((Glow) o).done && ((Glow) o).t < 450f) {
            ((Glow) o).end();              // a capa chegou em partes: recomeça já com a definitiva
        } else if (o instanceof Glow && !((Glow) o).done) {
            return;                        // um brilho por música
        }
        if (o instanceof Glow) ((Glow) o).end();
        Glow g = new Glow(host, album);
        host.setTag(TAG_GLOW, g);
        g.go();
    }

    static Drawable artDrawable(View album) {
        if (album instanceof ImageView) return ((ImageView) album).getDrawable();
        if (album instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) album;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof ImageView && c.getWidth() >= album.getWidth() / 2) return ((ImageView) c).getDrawable();
            }
        }
        return null;
    }

    /** Capa de verdade (foto), não o ícone do app que o HyperOS põe quando não há capa. */
    static boolean realArt(View album) {
        Drawable d = artDrawable(album);
        return d instanceof android.graphics.drawable.BitmapDrawable && d.getIntrinsicWidth() >= 64;
    }

    /**
     * Camada onde o brilho é desenhado: a do fundo do cartão (media_bg), que fica atrás da capa,
     * do nome e dos botões, como no ColorOS. Se outro módulo pôs uma camada de fundo própria
     * logo acima dele, desenha por cima de tudo no cartão.
     */
    static View layer(ViewGroup host, View album) {
        int id = host.getResources().getIdentifier("media_bg", "id", "com.android.systemui");
        View bg = id != 0 ? host.findViewById(id) : null;
        if (bg == null || bg.getParent() != host || bg.getVisibility() != View.VISIBLE || bg.getAlpha() < 0.5f) return host;
        int i = host.indexOfChild(bg);
        View next = i + 1 < host.getChildCount() ? host.getChildAt(i + 1) : null;
        if (next != null && next != album && next.getId() == View.NO_ID) return host;
        return bg;
    }

    /** Foto pequena da capa (a nova, depois da troca), para o desfoque. */
    static Bitmap artBitmap(View album) {
        Drawable d = null;
        if (album instanceof ImageView) d = ((ImageView) album).getDrawable();
        if (d == null && album instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) album;
            for (int i = 0; i < g.getChildCount() && d == null; i++) {
                View c = g.getChildAt(i);
                if (c instanceof ImageView && c.getWidth() >= album.getWidth() / 2) d = ((ImageView) c).getDrawable();
            }
        }
        if (d == null) return null;
        try {
            Bitmap bm = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
            Drawable dd = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            android.graphics.Rect keep = new android.graphics.Rect(dd.getBounds());
            dd.setBounds(0, 0, 48, 48);
            dd.draw(new Canvas(bm));
            dd.setBounds(keep);
            return bm;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Brilho = desfoque puro da própria capa, sem cor nenhuma por cima: uma cópia da capa
     * nova, bem desfocada, cresce em volta dela e some. A área da capa fica de fora, então o
     * brilho nunca cobre a capa; a posição é relida a cada quadro, então acompanha o cartão.
     */
    static final class Glow extends Drawable implements ValueAnimator.AnimatorUpdateListener, Animator.AnimatorListener {
        final ViewGroup host;
        final View album;
        final View target;
        final Bitmap art;
        final float density;
        final android.graphics.RectF dst = new android.graphics.RectF();
        final android.graphics.Path clip = new android.graphics.Path();
        final android.graphics.Path hole = new android.graphics.Path();
        float arad = -1f;

        float albumRadius() {
            if (arad >= 0f) return arad;
            float r = 0f;
            try {
                android.graphics.Outline o = new android.graphics.Outline();
                View v = album;
                for (int i = 0; i < 2 && v != null && r <= 0f; i++) {
                    if (v.getOutlineProvider() != null && v.getWidth() > 0) {
                        v.getOutlineProvider().getOutline(v, o);
                        r = o.getRadius();
                    }
                    v = v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0 ? ((ViewGroup) v).getChildAt(0) : null;
                }
            } catch (Throwable ignored) {
            }
            if (r <= 0f) r = Math.min(album.getWidth(), album.getHeight()) * 0.22f;
            return arad = r;
        }
        float rad = -1f;

        float radius() {
            if (rad >= 0f) return rad;
            float r = 0f;
            try {
                android.graphics.Outline o = new android.graphics.Outline();
                View v = host;
                for (int i = 0; i < 4 && v != null && r <= 0f; i++) {
                    if (v.getOutlineProvider() != null && v.getWidth() > 0) {
                        v.getOutlineProvider().getOutline(v, o);
                        r = o.getRadius();
                    }
                    v = v.getParent() instanceof View ? (View) v.getParent() : null;
                }
            } catch (Throwable ignored) {
            }
            if (r <= 0f) {
                int id = host.getResources().getIdentifier("notification_item_bg_radius", "dimen", "com.android.systemui");
                r = id != 0 ? host.getResources().getDimension(id) : 20f * density;
            }
            return rad = r;
        }
        final android.graphics.Rect src = new android.graphics.Rect(0, 0, 48, 48);
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        android.graphics.RenderNode node;
        ValueAnimator anim;
        boolean added, done;
        float t;

        Glow(ViewGroup host, View album) {
            this.host = host;
            this.album = album;
            this.art = artBitmap(album);
            this.target = layer(host, album);
            this.density = host.getResources().getDisplayMetrics().density;
        }

        void go() {
            if (art == null || !host.isAttachedToWindow()) {
                end();
                return;
            }
            setBounds(0, 0, target.getWidth(), target.getHeight());
            target.getOverlay().add(this);
            added = true;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(Math.round((Prefs.glowStyle == 1 ? 1550L : 1400L) * Prefs.glowTime / 100f));
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(this);
            anim.addListener(this);
            anim.start();
            if (logs++ < 4) ElasticCard.log("capa girando: desfoque da capa solto");
        }

        public void onAnimationUpdate(ValueAnimator a) {
            t = a.getCurrentPlayTime();
            invalidateSelf();
        }

        final Paint sweepP = new Paint(Paint.ANTI_ALIAS_FLAG);
        final boolean forward = lastPositive;
        int tintC = 0;

        /** Cor da luz: branca com um pouco da cor média da capa. */
        int sweepColor() {
            if (tintC != 0) return tintC;
            long r = 0, g = 0, b = 0;
            int n = 0;
            for (int y = 0; y < art.getHeight(); y += 4)
                for (int x = 0; x < art.getWidth(); x += 4) {
                    int px = art.getPixel(x, y);
                    r += (px >> 16) & 255;
                    g += (px >> 8) & 255;
                    b += px & 255;
                    n++;
                }
            if (n == 0) return tintC = 0xFFFFFFFF;
            float f = 0.35f;
            int rr = Math.round(255 * (1 - f) + r / n * f), gg = Math.round(255 * (1 - f) + g / n * f), bb = Math.round(255 * (1 - f) + b / n * f);
            return tintC = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
        }

        /**
         * Como no ColorOS 17: depois do giro, uma onda de luz nasce na capa e se espalha pelo cartão
         * todo, como a ondulação de uma gota na água, ficando mais fraca enquanto cresce.
         */
        void sweep(Canvas c) {
            float k = Prefs.glowTime / 100f;
            float dur = 1500f * k;
            float q = clamp(t / dur);
            float W = getBounds().width(), H = getBounds().height();
            // centro da capa, em coordenadas da camada
            float l = album.getLeft(), tp = album.getTop();
            View p = (View) album.getParent();
            while (p != null && p != host && p.getParent() instanceof View) {
                l += p.getLeft();
                tp += p.getTop();
                p = (View) p.getParent();
            }
            float cx = l + album.getWidth() / 2f - target.getLeft();
            float cy = tp + album.getHeight() / 2f - target.getTop();
            float r0 = Math.max(album.getWidth(), album.getHeight()) * 0.5f;
            // até o canto mais longe do cartão
            float far = (float) Math.max(Math.max(Math.hypot(cx, cy), Math.hypot(W - cx, cy)), Math.max(Math.hypot(cx, H - cy), Math.hypot(W - cx, H - cy)));
            float sz = Prefs.glowSize / 100f;
            float band = Math.max(18f * density, Math.min(W, H) * 0.22f * sz);
            // cresce rápido no começo e vai freando, como a onda na água
            float grow = 1f - (1f - q) * (1f - q);
            float R = r0 + (far + band - r0) * grow;
            float rise = SOFT.getInterpolation(clamp(t / (180f * k)));
            float a = Prefs.glowPower / 100f * 0.32f * rise * (1f - q) * (1f - q * 0.35f);
            if (a <= 0.01f) return;
            int col = sweepColor() & 0x00FFFFFF;
            int peak = (Math.round(255 * Math.min(1f, a)) << 24) | col;
            float outer = R + band * 0.5f;
            float inner = Math.max(0f, (R - band) / outer), mid = R / outer;
            sweepP.setShader(new android.graphics.RadialGradient(cx, cy, outer,
                    new int[]{col, col, peak, col}, new float[]{0f, inner, mid, 1f}, Shader.TileMode.CLAMP));
            int save = c.save();
            float rr = radius();
            clip.reset();
            clip.addRoundRect(0f, 0f, W, H, rr, rr, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            c.drawRect(0f, 0f, W, H, sweepP);
            c.restoreToCount(save);
            sweepP.setShader(null);
        }

        public void draw(Canvas c) {
            if (done || art == null || !c.isHardwareAccelerated()) return;
            if (Prefs.glowStyle == 1) {
                sweep(c);
                return;
            }
            float k = Prefs.glowTime / 100f;
            float rise = OUT.getInterpolation(clamp(t / (160f * k)));
            float fade = SOFT.getInterpolation(clamp((t - 220f * k) / (1150f * k)));
            float a = Prefs.glowPower / 100f * rise * (1f - fade);
            if (a <= 0.01f) return;
            // capa em coordenadas do cartão, sem a rotação do giro
            float l = album.getLeft(), tp = album.getTop();
            View p = (View) album.getParent();
            while (p != null && p != host && p.getParent() instanceof View) {
                l += p.getLeft();
                tp += p.getTop();
                p = (View) p.getParent();
            }
            float w = album.getWidth(), h = album.getHeight();
            float cx = l + w / 2f, cy = tp + h / 2f;
            float grow = OUT.getInterpolation(clamp(t / (1000f * k)));
            l -= target.getLeft();
            tp -= target.getTop();
            cx += w * Prefs.glowX / 100f - target.getLeft();
            cy += h * 0.45f * Prefs.glowDist / 100f * grow + h * Prefs.glowY / 100f - target.getTop();
            float sz = Prefs.glowSize / 100f;
            float sc = (1.3f + 1.9f * grow) * sz;
            dst.set(cx - w * sc / 2f, cy - h * sc / 2f, cx + w * sc / 2f, cy + h * sc / 2f);
            float blur = (22f + 26f * grow) * density * Math.max(0.6f, sz);
            if (node == null) node = new android.graphics.RenderNode("ilha-capa");
            int nw = Math.round(dst.width()), nh = Math.round(dst.height());
            float pad = blur * 2f;
            node.setPosition(0, 0, nw + Math.round(pad * 2), nh + Math.round(pad * 2));
            node.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL));
            node.setAlpha(a);
            android.graphics.RecordingCanvas rc = node.beginRecording();
            rc.drawBitmap(art, src, new android.graphics.RectF(pad, pad, pad + nw, pad + nh), paint);
            node.endRecording();
            int save = c.save();
            // só dentro do cartão, com os cantos arredondados dele
            float rr = radius();
            clip.reset();
            clip.addRoundRect(0f, 0f, getBounds().width(), getBounds().height(), rr, rr, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            if (target == host) {
                // por cima de tudo: deixa a capa de fora, com os cantos arredondados dela
                float ar = albumRadius();
                hole.reset();
                hole.addRoundRect(l, tp, l + w, tp + h, ar, ar, android.graphics.Path.Direction.CW);
                c.clipOutPath(hole);
            }
            // no fundo do cartão a capa já fica por cima; sem recorte, para não aparecer um
            // quadrado em volta dos cantos arredondados dela
            c.translate(dst.left - pad, dst.top - pad);
            c.drawRenderNode(node);
            c.restoreToCount(save);
        }

        void end() {
            if (done) return;
            done = true;
            if (anim != null) {
                anim.removeAllUpdateListeners();
                anim.removeAllListeners();
                anim.cancel();
            }
            if (added) target.getOverlay().remove(this);
            added = false;
            if (node != null) node.discardDisplayList();
            if (host.getTag(TAG_GLOW) == this) host.setTag(TAG_GLOW, null);
        }

        public void onAnimationEnd(Animator a) {
            end();
        }

        public void onAnimationCancel(Animator a) {
            end();
        }

        public void onAnimationStart(Animator a) {
        }

        public void onAnimationRepeat(Animator a) {
        }

        public void setAlpha(int a) {
        }

        public void setColorFilter(ColorFilter cf) {
        }

        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    static int withAlpha(int c, float a) {
        int al = Math.round(clamp(a) * 255f);
        return (al << 24) | (c & 0x00FFFFFF);
    }

    static float clamp(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }
}
