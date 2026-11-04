package com.ilha.headsup;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Player da central (o detalhe do MiPlay do plugin, também no mod "iOS26 Control Center") no
 * estilo do iOS: a capa ocupa o topo do cartão de ponta a ponta e some num degradê; título e
 * artista centralizados; o play num círculo; coração e repetir dos lados. Nada muda se as peças
 * não forem encontradas.
 */
final class IosPlayer {
    private IosPlayer() {}

    static final WeakHashMap<View, Boolean> seen = new WeakHashMap<>();
    static int logs, miss, missP, diag, dumps, dumped, off, plogs;

    static void plog(String m) {
        if (plogs++ < 220) XposedBridge.log("[HeadsUpIlha] " + m);
    }

    static void dump(View v, int d) {
        if (d > 9 || dumped > 140) return;
        dumped++;
        String id = "";
        try {
            if (v.getId() > 0) id = "#" + v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable ignored) {
        }
        plog("player iOS: " + new String(new char[d * 2]).replace('\0', ' ') + v.getClass().getName() + id + " " + v.getWidth() + "x" + v.getHeight()
                + (v.getVisibility() != View.VISIBLE ? " vis=" + v.getVisibility() : "") + (v.getBackground() != null ? " fundo" : ""));
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) dump(((ViewGroup) v).getChildAt(i), d + 1);
    }

    static void install() {
        try {
            XposedBridge.hookMethod(LayoutInflater.class.getMethod("inflate", org.xmlpull.v1.XmlPullParser.class, ViewGroup.class, boolean.class),
                    new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam p) {
                            Object r = p.getResult();
                            if (r instanceof View) check((View) r);
                        }
                    });
            plog("player iOS: pronto");
        } catch (Throwable t) {
            plog("player iOS indisponível: " + t);
        }
    }

    static final WeakHashMap<Resources, Integer> ids = new WeakHashMap<>();

    static void check(View root) {
        if (!classHooked) {
            ClassLoader cl = root.getClass().getClassLoader();
            if (cl != null && cl != View.class.getClassLoader()) {
                try {
                    hookClass(Class.forName("com.android.systemui.QSControlMiPlayDetailHeader", false, cl));
                } catch (Throwable ignored) {
                }
            }
        }
        Resources r = root.getResources();
        Integer id = ids.get(r);
        if (id == null) {
            int i = r.getIdentifier("qs_control_detail_miplay_header", "id", "miui.systemui.plugin");
            if (i == 0) i = r.getIdentifier("qs_control_detail_miplay_header", "id", root.getContext().getPackageName());
            id = i;
            ids.put(r, id);
        }
        if (id == 0) {
            if (miss++ < 3) plog("player iOS: sem o id do detalhe nos recursos de " + root.getContext().getPackageName());
            return;
        }
        try {
            if (root.getId() > 0 && diag < 12) {
                String rn = r.getResourceEntryName(root.getId());
                if (rn.contains("miplay") || rn.contains("media") || rn.contains("detail")) {
                    diag++;
                    plog("player iOS: layout '" + rn + "' (" + root.getContext().getPackageName() + ")");
                    final View rv = root;
                    if (rn.equals("media_player") && dumps < 2) rv.postDelayed(new Runnable() { public void run() { dumps++; dump(rv, 0); } }, 1500);
                }
            }
        } catch (Throwable ignored) {
        }
        View v = root.getId() == id ? root : root.findViewById(id);
        if (v == null || seen.containsKey(v)) return;
        seen.put(v, Boolean.TRUE);
        hookClass(v.getClass());
        final View header = v;
        header.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() {
                Prefs.refresh();
                if (!Prefs.playerIos) {
                    if (off++ < 2) plog("player iOS: opção desligada");
                    return;
                }
                try {
                    apply(header);
                } catch (Throwable t) {
                    if (logs++ < 6) plog("player iOS: " + t);
                }
            }
        });
        if (logs++ < 3) plog("player iOS: detalhe do player encontrado");
    }

    static boolean classHooked;

    /** O cabeçalho que aparece na tela nem sempre é o recém-inflado: os ganchos vão na classe dele. */
    static void hookClass(Class<?> c) {
        if (classHooked) return;
        classHooked = true;
        XC_MethodHook h = new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                final View v = (View) p.thisObject;
                scan();
                v.post(new Runnable() {
                    public void run() {
                        Prefs.refresh();
                        if (!Prefs.playerIos) {
                            if (off++ < 2) plog("player iOS: opção desligada");
                            return;
                        }
                        try {
                            apply(v);
                        } catch (Throwable t) {
                            if (logs++ < 6) plog("player iOS: " + t);
                        }
                    }
                });
            }
        };
        int n = 0;
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            String nm = m.getName();
            if (nm.equals("onAttachedToWindow") || nm.equals("onSizeChanged") || nm.equals("onOrientationChanged") || nm.equals("updateSize")
                    || nm.equals("prepareShowPanel") || nm.equals("setShowing") || nm.equals("updateTextAppearance")) {
                try {
                    XposedBridge.hookMethod(m, h);
                    n++;
                } catch (Throwable ignored) {
                }
            }
        }
        plog("player iOS: " + n + " ganchos em " + c.getName());
        hClass = c;
        IosMediaPage.install(c.getClassLoader());
        // mod iOS26 Control Center: a página de música reestiliza o cabeçalho (IosDetail.restyle/align/frame)
        try {
            Class<?> d = Class.forName("com.kashi.ios.IosDetail", false, c.getClassLoader());
            XC_MethodHook after = new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!Prefs.playerIos) return;
                    for (Object a : p.args) if (a instanceof View) found((View) a);
                    try {
                        java.lang.reflect.Field f = p.thisObject.getClass().getDeclaredField("stock");
                        f.setAccessible(true);
                        Object st = f.get(p.thisObject);
                        if (st instanceof View) found((View) st);
                    } catch (Throwable ignored) {
                    }
                }
            };
            int k = 0;
            for (java.lang.reflect.Method m : d.getDeclaredMethods()) {
                String nm = m.getName();
                if (nm.equals("restyle") || nm.equals("align") || nm.equals("frame")) {
                    XposedBridge.hookMethod(m, after);
                    k++;
                }
            }
            plog("player iOS: mod iOS26 encontrado (" + k + " ganchos)");
        } catch (Throwable t) {
            plog("player iOS: sem o mod iOS26 (" + t.getClass().getSimpleName() + ")");
        }
        scan();
    }

    static int foundLogs;

    /** Aplica nos cabeçalhos dentro da view (a página de música do mod). */
    static void found(View v) {
        if (hClass == null) return;
        java.util.ArrayList<View> out = new java.util.ArrayList<>();
        collect(v, out, 0);
        for (final View h : out) {
            if (foundLogs++ < 4) plog("player iOS: página do mod " + h.getWidth() + "x" + h.getHeight());
            try {
                apply(h);
            } catch (Throwable ignored) {
            }
            h.post(new Runnable() {
                public void run() {
                    try {
                        apply(h);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
    }

    static void collect(View v, java.util.List<View> out, int d) {
        if (d > 12) return;
        if (hClass.isInstance(v)) {
            out.add(v);
            return;
        }
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) collect(((ViewGroup) v).getChildAt(i), out, d + 1);
    }

    static Class<?> hClass;
    static long lastScan;

    /** Cabeçalhos que já existiam antes dos ganchos (a página de música do mod é criada junto com a central). */
    static void scan() {
        if (hClass == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastScan < 1500) return;
        lastScan = now;
        try {
            Class<?> g = Class.forName("android.view.WindowManagerGlobal");
            Object wmg = g.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field f = g.getDeclaredField("mRoots");
            f.setAccessible(true);
            java.util.List<?> roots = new java.util.ArrayList<>((java.util.List<?>) f.get(wmg));
            for (Object r : roots) {
                View v = (View) r.getClass().getMethod("getView").invoke(r);
                if (v != null) find(v);
            }
        } catch (Throwable t) {
            if (logs++ < 6) plog("player iOS: busca " + t);
        }
    }

    static void find(View v) {
        if (hClass.isInstance(v)) {
            if (!found.containsKey(v)) {
                found.put(v, Boolean.TRUE);
                plog("player iOS: cabeçalho já existente " + v.getWidth() + "x" + v.getHeight());
                final View h = v;
                h.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    public void onGlobalLayout() {
                        Prefs.refresh();
                        if (!Prefs.playerIos) return;
                        try {
                            apply(h);
                        } catch (Throwable ignored) {
                        }
                    }
                });
                if (Prefs.playerIos) try { apply(h); } catch (Throwable ignored) {}
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup gr = (ViewGroup) v;
            for (int i = 0; i < gr.getChildCount(); i++) find(gr.getChildAt(i));
        }
    }

    static final WeakHashMap<View, Boolean> found = new WeakHashMap<>();

    static View byId(View root, String name) {
        Resources r = root.getResources();
        int id = r.getIdentifier(name, "id", root.getContext().getPackageName());
        if (id == 0) id = r.getIdentifier(name, "id", "miui.systemui.plugin");
        return id == 0 ? null : root.findViewById(id);
    }

    static final int[] A = new int[2], B = new int[2];

    /**
     * O plugin refaz o tamanho e a posição da capa por conta própria; então a capa dele fica
     * invisível (mantém o lugar) e a arte é desenhada no fundo do cartão, de ponta a ponta no
     * topo, sumindo num degradê atrás do título. O título e o artista ficam no centro.
     */
    static void apply(View header) {
        if (header.getWidth() == 0) return;
        if (dumps == 0) {
            dumps++;
            dump(header, 0);
        }
        float dp = header.getResources().getDisplayMetrics().density;
        View coverParent = byId(header, "coverParent");
        final ImageView cover = (ImageView) byId(header, "cover");
        TextView title = (TextView) byId(header, "title"), sub = (TextView) byId(header, "subtitle");
        if (coverParent == null || cover == null || title == null) {
            if (missP++ < 4) plog("player iOS: faltam peças capaPai=" + (coverParent != null) + " capa=" + (cover != null) + " título=" + (title != null));
            return;
        }
        View card = null;
        for (Object p = header.getParent(); p instanceof View; p = ((View) p).getParent()) {
            View pv = (View) p;
            if (pv.getBackground() != null && pv.getWidth() >= header.getWidth()) {
                card = pv;
                break;
            }
        }
        if (card == null) card = header;   // o vidro do cartão é do sistema (sem fundo desenhado): a arte vai no próprio cabeçalho
        if (!(card.getBackground() instanceof Art)) {
            Drawable ob = card.getBackground();
            card.setBackground(new Art(card, ob != null ? ob : new android.graphics.drawable.ColorDrawable(0), header, 30 * dp));
            if (logs++ < 6) plog("player iOS: arte no fundo do cartão " + card.getClass().getName() + " " + card.getWidth() + "x" + card.getHeight());
        }
        ((Art) card.getBackground()).header = header;
        if (cover.getAlpha() != 0f) cover.setAlpha(0f);
        View icon = byId(header, "app_icon_container");
        if (icon != null && icon.getAlpha() != 0f) icon.setAlpha(0f);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        View nh = byId(header, "title_no_play_back_history");
        if (nh instanceof TextView) ((TextView) nh).setGravity(Gravity.CENTER);
        if (sub != null) sub.setGravity(Gravity.CENTER);
        if (!watching.containsKey(header)) {
            watching.put(header, Boolean.TRUE);
            final View c2 = card;
            header.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                Drawable last;

                public boolean onPreDraw() {
                    if (!Prefs.playerIos) {
                        if (cover.getAlpha() == 0f) {
                            cover.setAlpha(1f);
                            View ic = byId(cover.getRootView(), "app_icon_container");
                            if (ic != null) ic.setAlpha(1f);
                            c2.invalidate();
                        }
                        return true;
                    }
                    if (cover.getAlpha() != 0f) cover.setAlpha(0f);
                    Drawable d = cover.getDrawable();
                    if (d != last) {
                        last = d;
                        c2.invalidate();
                    }
                    return true;
                }
            });
        }
    }

    static final WeakHashMap<View, Boolean> watching = new WeakHashMap<>();

    /** Fundo do cartão: o original e, por cima, a capa de ponta a ponta com degradê. */
    static final class Art extends Drawable {
        final View card;
        final Drawable orig;
        View header;
        final float radius;
        final Paint fade = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path clip = new Path();
        final int[] a = new int[2], b = new int[2];

        Art(View card, Drawable orig, View header, float radius) {
            this.card = card;
            this.orig = orig;
            this.header = header;
            this.radius = radius;
            fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        }

        protected void onBoundsChange(android.graphics.Rect r) {
            orig.setBounds(r);
        }

        public void draw(Canvas c) {
            orig.draw(c);
            if (!Prefs.playerIos || header == null || !header.isShown()) return;
            ImageView cover = (ImageView) byId(header, "cover");
            View cp = byId(header, "coverParent");
            View t = byId(header, "subtitle");
            if (t == null || t.getVisibility() != View.VISIBLE) t = byId(header, "title");
            if (cover == null || cp == null) return;
            Drawable d = cover.getDrawable();
            if (d == null || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0) return;
            android.graphics.Rect bd = getBounds();
            int w = bd.width();
            card.getLocationInWindow(a);
            cp.getLocationInWindow(b);
            int coverBottom = b[1] - a[1] + cp.getHeight();
            int bottom = coverBottom;
            if (t != null && t.getVisibility() == View.VISIBLE) {
                t.getLocationInWindow(b);
                bottom = Math.max(bottom, b[1] - a[1] + t.getHeight());
            }
            bottom += Math.round(radius * 0.6f);
            if (bottom <= 0) return;
            int save = c.saveLayer(bd.left, bd.top, bd.right, bd.top + bottom, null);
            clip.reset();
            clip.addRoundRect(bd.left, bd.top, bd.right, bd.top + bottom + radius, radius, radius, Path.Direction.CW);
            c.clipPath(clip);
            // centerCrop
            float iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            float sc = Math.max(w / iw, bottom / ih);
            float dw = iw * sc, dh = ih * sc;
            android.graphics.Rect keep = new android.graphics.Rect(d.getBounds());
            int left = Math.round(bd.left + (w - dw) / 2f), top = Math.round(bd.top + (bottom - dh) / 2f);
            d.setBounds(left, top, Math.round(left + dw), Math.round(top + dh));
            int alpha = d.getAlpha();
            d.setAlpha(255);
            d.draw(c);
            d.setAlpha(alpha);
            d.setBounds(keep);
            float f0 = bd.top + coverBottom * 0.62f;
            fade.setShader(new LinearGradient(0, f0, 0, bd.top + bottom, new int[]{0x00000000, 0x99000000, 0xFF000000},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(bd.left, f0, bd.right, bd.top + bottom, fade);
            c.restoreToCount(save);
        }

        public void setAlpha(int x) { orig.setAlpha(x); }
        public void setColorFilter(ColorFilter cf) { orig.setColorFilter(cf); }
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        public void getOutline(Outline o) { orig.getOutline(o); }
    }

    static void setInt(Object o, String f, int v) {
        try {
            o.getClass().getField(f).setInt(o, v);
        } catch (Throwable ignored) {
        }
    }

    /** Degradê que apaga a parte de baixo da capa (ela some no fundo do cartão). */
    static final class Fade extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int lastH;

        Fade() {
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        }

        public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            int h = b.height();
            if (h != lastH) {
                lastH = h;
                p.setShader(new LinearGradient(0, b.top + h * 0.55f, 0, b.bottom, new int[]{0x00000000, 0xC0000000, 0xFF000000},
                        new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
            }
            c.drawRect(b, p);
        }

        public void setAlpha(int a) {}
        public void setColorFilter(ColorFilter cf) {}
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ---------------------------------------------------------------- coração e repetir

    static void sides(View header, ViewGroup inner, View audio, float dp) {
        if (inner.findViewWithTag("ilha_heart") != null) {
            refresh(inner);
            return;
        }
        ViewGroup.MarginLayoutParams al = (ViewGroup.MarginLayoutParams) audio.getLayoutParams();
        int side = Math.round(72 * dp);
        al.setMarginStart(side);
        al.setMarginEnd(side);
        audio.setLayoutParams(al);
        Context c = header.getContext();
        for (int i = 0; i < 2; i++) {
            final boolean heart = i == 0;
            ImageView b = new ImageView(c);
            b.setTag(heart ? "ilha_heart" : "ilha_repeat");
            b.setImageDrawable(new Glyph(heart));
            int s = Math.round(44 * dp);
            ViewGroup.LayoutParams lp;
            try {
                lp = (ViewGroup.LayoutParams) audio.getLayoutParams().getClass().getConstructor(int.class, int.class).newInstance(s, s);
            } catch (Throwable t) {
                return;
            }
            setInt(lp, heart ? "startToStart" : "endToEnd", 0);
            setInt(lp, "topToTop", audio.getId());
            setInt(lp, "bottomToBottom", audio.getId());
            ((ViewGroup.MarginLayoutParams) lp).setMarginStart(heart ? Math.round(14 * dp) : 0);
            ((ViewGroup.MarginLayoutParams) lp).setMarginEnd(heart ? 0 : Math.round(14 * dp));
            b.setPadding(Math.round(9 * dp), Math.round(9 * dp), Math.round(9 * dp), Math.round(9 * dp));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    MediaController mc = controller(v.getContext());
                    if (mc == null) return;
                    try {
                        if (heart) {
                            PlaybackState.CustomAction a = likeAction(mc);
                            if (a != null) mc.getTransportControls().sendCustomAction(a, null);
                        } else {
                            PlaybackState.CustomAction a = action(mc, "repeat", "loop", "repetir");
                            if (a != null) {
                                mc.getTransportControls().sendCustomAction(a, null);
                            } else {
                                // sessões MediaSessionCompat/Media3: 0 nenhum, 1 uma, 2 todas
                                repeat = (repeat + 1) % 3;
                                int mode = repeat == 1 ? 2 : repeat == 2 ? 1 : 0;
                                android.os.Bundle bd = new android.os.Bundle();
                                bd.putInt("android.support.v4.media.session.action.ARGUMENT_REPEAT_MODE", mode);
                                mc.getTransportControls().sendCustomAction("android.support.v4.media.session.action.SET_REPEAT_MODE", bd);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    v.postDelayed(new Runnable() {
                        public void run() {
                            refresh((ViewGroup) v.getParent());
                        }
                    }, 400);
                }
            });
            inner.addView(b, lp);
        }
        refresh(inner);
    }

    static void refresh(ViewGroup inner) {
        if (inner == null) return;
        MediaController mc = controller(inner.getContext());
        View h = inner.findViewWithTag("ilha_heart"), r = inner.findViewWithTag("ilha_repeat");
        if (h instanceof ImageView) {
            PlaybackState.CustomAction a = mc == null ? null : likeAction(mc);
            h.setVisibility(a == null ? View.INVISIBLE : View.VISIBLE);
            Glyph g = (Glyph) ((ImageView) h).getDrawable();
            g.on = a != null && a.getName() != null && a.getName().toString().toLowerCase().matches(".*(un|remove|dis|des|retirar|remover).*");
            g.invalidateSelf();
        }
        if (r instanceof ImageView) {
            Glyph g = (Glyph) ((ImageView) r).getDrawable();
            g.on = repeat != 0;
            g.one = repeat == 2;
            g.invalidateSelf();
        }
    }

    static int repeat;   // 0 desligado, 1 todas, 2 uma (o estado de repetir não é público na sessão)

    static PlaybackState.CustomAction likeAction(MediaController mc) {
        return action(mc, "like", "favor", "heart", "love", "curtir", "thumb");
    }

    static PlaybackState.CustomAction action(MediaController mc, String... keys) {
        PlaybackState st = mc.getPlaybackState();
        if (st == null || st.getCustomActions() == null) return null;
        for (PlaybackState.CustomAction a : st.getCustomActions()) {
            String s = (a.getAction() + " " + a.getName()).toLowerCase();
            for (String k : keys) if (s.contains(k)) return a;
        }
        return null;
    }

    static MediaController cached;
    static long cachedAt;

    static MediaController controller(Context c) {
        long now = android.os.SystemClock.uptimeMillis();
        if (cached != null && now - cachedAt < 700) return cached;
        cachedAt = now;
        cached = find(c);
        return cached;
    }

    static MediaController find(Context c) {
        try {
            MediaSessionManager m = c.getSystemService(MediaSessionManager.class);
            List<MediaController> l = m.getActiveSessions(null);
            MediaController first = null;
            for (MediaController mc : l) {
                if (first == null) first = mc;
                PlaybackState s = mc.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) return mc;
            }
            return first;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Ícones desenhados: coração e repetir (aceso quando ligado). */
    static final class Glyph extends Drawable {
        final boolean heart;
        boolean on, one;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();

        Glyph(boolean heart) {
            this.heart = heart;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float w = b.width(), h = b.height(), x = b.left, y = b.top;
            p.setColor(0xFFFFFFFF);
            p.setAlpha(heart ? 255 : (on ? 255 : 150));
            p.setStrokeWidth(w * 0.085f);
            path.reset();
            if (heart) {
                p.setStyle(on ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
                path.moveTo(x + w * 0.5f, y + h * 0.86f);
                path.cubicTo(x + w * 0.1f, y + h * 0.58f, x + w * 0.02f, y + h * 0.36f, x + w * 0.16f, y + h * 0.22f);
                path.cubicTo(x + w * 0.3f, y + h * 0.08f, x + w * 0.46f, y + h * 0.16f, x + w * 0.5f, y + h * 0.3f);
                path.cubicTo(x + w * 0.54f, y + h * 0.16f, x + w * 0.7f, y + h * 0.08f, x + w * 0.84f, y + h * 0.22f);
                path.cubicTo(x + w * 0.98f, y + h * 0.36f, x + w * 0.9f, y + h * 0.58f, x + w * 0.5f, y + h * 0.86f);
                c.drawPath(path, p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                float l = x + w * 0.16f, r = x + w * 0.84f, t = y + h * 0.32f, bo = y + h * 0.68f;
                path.moveTo(l, bo - h * 0.04f);
                path.lineTo(l, t + h * 0.08f);
                path.quadTo(l, t, l + w * 0.08f, t);
                path.lineTo(r - w * 0.04f, t);
                path.moveTo(r - w * 0.16f, t - h * 0.12f);
                path.lineTo(r - w * 0.04f, t);
                path.lineTo(r - w * 0.16f, t + h * 0.12f);
                path.moveTo(r, t + h * 0.04f);
                path.lineTo(r, bo - h * 0.08f);
                path.quadTo(r, bo, r - w * 0.08f, bo);
                path.lineTo(l + w * 0.04f, bo);
                path.moveTo(l + w * 0.16f, bo + h * 0.12f);
                path.lineTo(l + w * 0.04f, bo);
                path.lineTo(l + w * 0.16f, bo - h * 0.12f);
                c.drawPath(path, p);
                if (one) {
                    p.setStyle(Paint.Style.FILL);
                    p.setTextSize(h * 0.26f);
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setTypeface(Typeface.DEFAULT_BOLD);
                    c.drawText("1", x + w * 0.5f, y + h * 0.59f, p);
                }
            }
        }

        public void setAlpha(int a) {}
        public void setColorFilter(ColorFilter cf) {}
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
