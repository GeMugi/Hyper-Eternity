package com.ilha.headsup;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * Instagram: a barra de abas de baixo vira uma pílula de vidro flutuante (liquid glass), com o
 * desfoque do sistema e o feed passando por trás. Se a barra não for encontrada, nada muda.
 */
final class IgGlass {
    private IgGlass() {}

    static volatile boolean on;
    static int logs;
    static final WeakHashMap<View, Boolean> watched = new WeakHashMap<>();
    static final WeakHashMap<View, Boolean> styled = new WeakHashMap<>();
    static boolean mapped;

    static void install() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    SharedPreferences sp = XposedBridge.remotePrefs(Keys.GROUP);
                    if (sp == null) return;
                    on = sp.getBoolean(Keys.IG_GLASS, Keys.D_IG_GLASS);
                    sp.registerOnSharedPreferenceChangeListener(new SharedPreferences.OnSharedPreferenceChangeListener() {
                        public void onSharedPreferenceChanged(SharedPreferences s, String k) {
                            if (Keys.IG_GLASS.equals(k)) on = s.getBoolean(Keys.IG_GLASS, Keys.D_IG_GLASS);
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        }, "HeadsUpIlha-ig");
        t.setDaemon(true);
        t.start();
        try {
            XposedBridge.hookMethod(Activity.class.getDeclaredMethod("onResume"), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    final View d = ((Activity) p.thisObject).getWindow().getDecorView();
                    if (watched.containsKey(d)) return;
                    watched.put(d, Boolean.TRUE);
                    d.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                        public void onGlobalLayout() {
                            if (!on) return;
                            try {
                                apply(d);
                            } catch (Throwable t) {
                                log("erro: " + t);
                            }
                        }
                    });
                }
            });
            log("pronto");
        } catch (Throwable t2) {
            log("falhou: " + t2);
        }
    }

    static final WeakHashMap<View, Boolean> drawHooked = new WeakHashMap<>();

    /** Roda a cada quadro e a cada mudança de layout: o Instagram troca a barra e o fundo da pílula ao mudar de aba. */
    private static void apply(final View decor) {
        if (!drawHooked.containsKey(decor)) {
            drawHooked.put(decor, Boolean.TRUE);
            decor.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                public boolean onPreDraw() {
                    if (!on) return true;
                    try {
                        wrap(decor, false);
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
            });
        }
        wrap(decor, true);
    }

    static java.lang.ref.WeakReference<View> lastBar;

    private static void wrap(View decor, boolean search) {
        float dp = decor.getResources().getDisplayMetrics().density;
        View bar = lastBar == null ? null : lastBar.get();
        if (search || bar == null || !bar.isAttachedToWindow() || bar.getRootView() != decor) {
            if (!search && bar != null && bar.isAttachedToWindow() && bar.getRootView() != decor) return;
            bar = find(decor, decor.getWidth(), dp);
            lastBar = bar == null ? null : new java.lang.ref.WeakReference<>(bar);
        }
        if (bar == null || !(bar.getParent() instanceof ViewGroup)) return;
        View pill = bar.getBackground() != null ? bar : null;
        if (pill == null && bar instanceof ViewGroup) pill = withBg((ViewGroup) bar, 0);
        if (!mapped) {
            mapped = true;
            log("barra: " + name(bar) + " fundo=" + cls(bar.getBackground()) + " pílula=" + name(pill) + " fundo=" + (pill == null ? "-" : cls(pill.getBackground())));
        }
        if (pill == null) return;
        Drawable bg = pill.getBackground();
        if (bg instanceof Backdrop) {
            if (((Backdrop) bg).bar == bar) {
                pill.invalidate();
                return;
            }
            bg = ((Backdrop) bg).orig;
        }
        boolean dark = (bar.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        pill.setBackground(new Backdrop(pill, bar, bg, 24 * dp, dark));
        if (logs < 25) log("vidro aplicado em " + name(pill));
    }

    private static View withBg(ViewGroup g, int d) {
        if (d > 4) return null;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() == View.VISIBLE && c.getBackground() != null && c.getWidth() >= g.getWidth() * 0.5f) return c;
        }
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof ViewGroup) {
                View f = withBg((ViewGroup) c, d + 1);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static String cls(Object o) {
        return o == null ? "-" : o.getClass().getName();
    }

    /**
     * Fundo de vidro feito dentro do app: redesenha o que está atrás da pílula (o feed) num
     * RenderNode com desfoque, recortado no formato da pílula, e por cima o fundo original do
     * Instagram com transparência.
     */
    static final class Backdrop extends Drawable {
        final View owner, bar;
        final Drawable orig;
        final float radius;
        final boolean dark;
        final android.graphics.RenderNode node = new android.graphics.RenderNode("ilha-vidro");
        final Outline outline = new Outline();
        final android.graphics.Rect r = new android.graphics.Rect();
        final android.graphics.Path path = new android.graphics.Path();
        final int[] a = new int[2], b = new int[2];
        boolean drawing;

        Backdrop(View owner, View bar, Drawable orig, float radius, boolean dark) {
            this.owner = owner;
            this.bar = bar;
            this.orig = orig.mutate();
            this.radius = radius;
            this.dark = dark;
            node.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP));
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect bounds) {
            orig.setBounds(bounds);
            super.onBoundsChange(bounds);
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect bd = getBounds();
            if (!on || drawing || !c.isHardwareAccelerated() || bd.isEmpty()) {
                orig.setAlpha(255);
                orig.draw(c);
                return;
            }
            drawing = true;
            try {
                orig.getOutline(outline);
                float rad = outline.getRadius();
                if (!outline.getRect(r) || r.isEmpty()) {
                    r.set(bd);
                    rad = Math.min(bd.width(), bd.height()) / 2f;
                }
                if (rad <= 0) rad = Math.min(r.width(), r.height()) / 2f;
                owner.getLocationInWindow(a);
                node.setPosition(0, 0, r.width(), r.height());
                android.graphics.RecordingCanvas rc = node.beginRecording(r.width(), r.height());
                ViewGroup par = (ViewGroup) bar.getParent();
                for (int i = 0; par != null && i < par.getChildCount(); i++) {
                    View v = par.getChildAt(i);
                    if (v == bar) break;
                    if (v.getVisibility() != View.VISIBLE || v.getAlpha() == 0f || v.getHeight() <= bar.getHeight()) continue;
                    v.getLocationInWindow(b);
                    int save = rc.save();
                    rc.translate(b[0] - a[0] - r.left, b[1] - a[1] - r.top);
                    v.draw(rc);
                    rc.restoreToCount(save);
                }
                node.endRecording();
                path.reset();
                path.addRoundRect(r.left, r.top, r.right, r.bottom, rad, rad, android.graphics.Path.Direction.CW);
                int save = c.save();
                c.clipPath(path);
                c.translate(r.left, r.top);
                c.drawRenderNode(node);
                c.restoreToCount(save);
                orig.setAlpha(dark ? 120 : 150);
                orig.draw(c);
                orig.setAlpha(255);
            } catch (Throwable t) {
                orig.draw(c);
            } finally {
                drawing = false;
            }
        }

        @Override public void setAlpha(int alpha) { orig.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { orig.setColorFilter(cf); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public void getOutline(Outline o) { orig.getOutline(o); }
    }

    private static View find(View v, int w, float dp) {
        if (v.getVisibility() != View.VISIBLE) return null;
        try {
            if (v.getId() > 0) {
                String n = v.getResources().getResourceEntryName(v.getId());
                if (n.equals("tab_bar") || n.endsWith("_tab_bar") || n.equals("bottom_navigation")) return v;
            }
        } catch (Throwable ignored) {
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = find(g.getChildAt(i), w, dp);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static String name(View v) {
        if (v == null) return "-";
        String id = "";
        try {
            if (v.getId() > 0) id = "#" + v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable ignored) {
        }
        return v.getClass().getName() + id + " " + v.getWidth() + "x" + v.getHeight();
    }

    static void log(String s) {
        if (logs++ < 30) XposedBridge.log("[HeadsUpIlha] instagram: " + s);
    }
}
