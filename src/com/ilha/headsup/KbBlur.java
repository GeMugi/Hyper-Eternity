package com.ilha.headsup;

import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * Gboard com fundo desfocado. Roda no processo do Gboard: o fundo largo do teclado fica
 * transparente e atrás dele entra o desfoque do próprio Android (o mesmo da cortina), recortado
 * na área do teclado. Se algo não for encontrado, o teclado fica como está.
 */
final class KbBlur {
    private KbBlur() {}

    static volatile boolean on;
    static volatile int radius = Keys.D_KB_BLUR_R, alpha = Keys.D_KB_BLUR_A;
    static volatile SharedPreferences sp;
    static WeakReference<View> input;
    static WeakReference<InputMethodService> ims;
    static final WeakHashMap<View, Drawable> orig = new WeakHashMap<>();
    static final WeakHashMap<View, Boolean> listening = new WeakHashMap<>();
    static Drawable blur, blur2;
    static View bar, nav;
    static Drawable blur3;
    static int base = 0;
    static boolean logged, mode;
    static int logs;

    static void install() {
        loadPrefs();
        try {
            XposedBridge.hookMethod(InputMethodService.class.getMethod("setInputView", View.class), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    Object v = p.args[0];
                    if (v instanceof View) input = new WeakReference<>((View) v);
                    ims = new WeakReference<>((InputMethodService) p.thisObject);
                    schedule();
                }
            });
            XposedBridge.hookMethod(InputMethodService.class.getMethod("onWindowShown"), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    ims = new WeakReference<>((InputMethodService) p.thisObject);
                    schedule();
                }
            });
            log("teclado: ganchos prontos");
        } catch (Throwable t) {
            log("teclado: ganchos falharam: " + t);
        }
    }

    private static void loadPrefs() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 6 && sp == null; i++) {
                    try {
                        sp = XposedBridge.remotePrefs(Keys.GROUP);
                    } catch (Throwable ignored) {
                    }
                    if (sp == null) try { Thread.sleep(2000); } catch (Throwable ignored) {}
                }
                if (sp == null) return;
                read();
                sp.registerOnSharedPreferenceChangeListener(new SharedPreferences.OnSharedPreferenceChangeListener() {
                    public void onSharedPreferenceChanged(SharedPreferences s, String k) {
                        if (k != null && !k.startsWith("kb_")) return;
                        read();
                        schedule();
                    }
                });
                schedule();
            }
        }, "HeadsUpIlha-teclado");
        t.setDaemon(true);
        t.start();
    }

    private static void read() {
        try {
            on = sp.getBoolean(Keys.KB_BLUR, Keys.D_KB_BLUR);
            radius = Math.max(0, Math.min(150, sp.getInt(Keys.KB_BLUR_R, Keys.D_KB_BLUR_R)));
            alpha = Math.max(0, Math.min(100, sp.getInt(Keys.KB_BLUR_A, Keys.D_KB_BLUR_A)));
        } catch (Throwable ignored) {
        }
    }

    private static final Runnable PASS = new Runnable() {
        public void run() {
            try {
                apply();
            } catch (Throwable t) {
                log("teclado: erro: " + t);
            }
        }
    };

    static void schedule() {
        View f = frame();
        if (f == null) return;
        f.removeCallbacks(PASS);
        f.postDelayed(PASS, 120);
    }

    private static View frame() {
        View v = input == null ? null : input.get();
        if (v == null) return null;
        Object p = v.getParent();
        return p instanceof View ? (View) p : v;
    }

    private static void apply() {
        final View f = frame();
        if (f == null || f.getWidth() == 0) return;
        final View rootV = f.getRootView();
        if (!listening.containsKey(rootV)) {
            listening.put(rootV, Boolean.TRUE);
            rootV.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                public boolean onPreDraw() {
                    try {
                        guard();
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
            });
        }
        if (!listening.containsKey(f)) {
            listening.put(f, Boolean.TRUE);
            f.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                public void onGlobalLayout() {
                    if (on || !orig.isEmpty()) schedule();
                }
            });
        }
        if (!on) {
            restore(f);
            return;
        }
        int a = alpha * 255 / 100;
        clear(f, f.getWidth(), a == 0 ? 0 : 0);
        int tint = (base == 0 ? 0xFF202124 : base) & 0x00FFFFFF | (a << 24);
        if (blur == null) {
            try {
                Method gv = View.class.getMethod("getViewRootImpl");
                Object root = gv.invoke(f);
                Method cb = root.getClass().getMethod("createBackgroundBlurDrawable");
                blur = (Drawable) cb.invoke(root);
                mode = true;
                log("teclado: desfoque recortado pronto");
            } catch (Throwable t) {
                log("teclado: desfoque recortado indisponível (" + t + "), usando o da janela");
            }
        }
        if (blur != null) {
            try {
                blur.getClass().getMethod("setBlurRadius", int.class).invoke(blur, radius);
                blur.getClass().getMethod("setColor", int.class).invoke(blur, tint);
            } catch (Throwable ignored) {
            }
            View k = body(f);
            if (k == null) {
                if (logs < 100) log("teclado: corpo do teclado não encontrado");
                return;
            }
            if (k != holder) {
                View old = holder;
                if (old != null && old.getBackground() == blur) old.setBackground(orig.containsKey(old) ? orig.get(old) : null);
                holder = k;
                log("teclado: desfoque em " + k.getClass().getName() + " " + k.getWidth() + "x" + k.getHeight());
            }
            if (k.getBackground() != blur) {
                if (!orig.containsKey(k)) orig.put(k, k.getBackground());
                k.setBackground(blur);
            }
            extra(f, k, tint);
            navStrip(f, bar != null ? bar : k, tint);
        } else {
            InputMethodService s = ims == null ? null : ims.get();
            Window w = s == null || s.getWindow() == null ? null : s.getWindow().getWindow();
            if (w == null) return;
            View d = w.getDecorView();
            int sh = d.getResources().getDisplayMetrics().heightPixels;
            if (d.getHeight() > sh * 0.6f) {
                if (logs < 30) log("teclado: janela ocupa a tela toda (" + d.getHeight() + "/" + sh + "), desfoque não aplicado");
                return;
            }
            w.setBackgroundDrawable(new ColorDrawable(tint));
            w.setBackgroundBlurRadius(radius);
        }
        f.invalidate();
    }

    static View holder;

    /**
     * O corpo do teclado: a view mais alta que ocupa a largura toda mas não a tela toda (a de fora
     * vai até o topo da tela, e o desfoque nela borrava o app inteiro).
     */
    private static View body(View f) {
        int sh = f.getResources().getDisplayMetrics().heightPixels;
        float dp = f.getResources().getDisplayMetrics().density;
        View[] best = new View[1];
        find(f, f.getWidth(), sh * 0.6f, 100 * dp, best);
        return best[0];
    }

    private static void find(View v, int fw, float max, float min, View[] best) {
        if (v.getVisibility() != View.VISIBLE) return;
        int h = v.getHeight();
        if (v.getWidth() >= fw * 0.9f && h >= min && h <= max && (best[0] == null || h > best[0].getHeight())) best[0] = v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) find(g.getChildAt(i), fw, max, min, best);
        }
    }

    /**
     * A barra do "teclado aprimorado" do HyperOS (frases frequentes, área de transferência) fica
     * abaixo do Gboard, fora da view dele: procura na janela uma faixa larga logo abaixo do corpo.
     */
    private static void extra(View f, View k, int tint) {
        View root = f.getRootView();

        int[] p = new int[2];
        k.getLocationInWindow(p);
        int bottom = p[1] + k.getHeight();
        float dp = f.getResources().getDisplayMetrics().density;
        View[] got = new View[1];
        below(root, k, root.getWidth(), bottom, 24 * dp, 260 * dp, got);
        View b = got[0];
        if (b == null) return;
        if (b != bar) {
            bar = b;
            log("teclado: barra de baixo " + b.getClass().getName() + " " + b.getWidth() + "x" + b.getHeight());
        }
        clear(b, b.getWidth(), 0);
        try {
            if (blur2 == null) {
                Object r = View.class.getMethod("getViewRootImpl").invoke(b);
                blur2 = (Drawable) r.getClass().getMethod("createBackgroundBlurDrawable").invoke(r);
            }
            blur2.getClass().getMethod("setBlurRadius", int.class).invoke(blur2, radius);
            blur2.getClass().getMethod("setColor", int.class).invoke(blur2, tint);
            if (b.getBackground() != blur2) {
                if (!orig.containsKey(b)) orig.put(b, b.getBackground());
                b.setBackground(blur2);
            }
        } catch (Throwable t) {
            log("teclado: barra de baixo sem desfoque: " + t);
        }
    }

    /**
     * A faixa da barra de gestos embaixo do teclado: o Android pinta ali a cor da barra de
     * navegação da janela do teclado. Fica transparente e ganha o mesmo desfoque.
     */
    private static void navStrip(View f, View above, int tint) {
        View n = f.getRootView().findViewById(android.R.id.navigationBarBackground);
        if (n == null) {
            if (nav == null) log("teclado: faixa dos gestos não encontrada");
            return;
        }
        if (n != nav) {
            nav = n;
            log("teclado: faixa dos gestos " + n.getWidth() + "x" + n.getHeight() + " (fica transparente)");
        }
        if (n.getAlpha() != 0f) n.setAlpha(0f);
    }

    /**
     * O HyperOS repinta a barra de baixo e a faixa dos gestos (a cor da barra de navegação) a
     * qualquer momento; antes de cada quadro, se alguma voltou a ter cor, sai de novo.
     */
    private static void guard() {
        if (!on) return;
        View b = bar;
        if (b != null && blur2 != null) {
            if (b.getBackground() != blur2) {
                if (!orig.containsKey(b)) orig.put(b, b.getBackground());
                b.setBackground(blur2);
            }
            clear(b, b.getWidth(), 0);
        }
        View n = nav;
        if (n != null && n.getAlpha() != 0f) n.setAlpha(0f);
    }

    private static void below(View v, View k, int fw, int top, float min, float max, View[] got) {
        if (got[0] != null || v == k || v == bar && k != bar || v.getVisibility() != View.VISIBLE) return;
        int[] p = new int[2];
        v.getLocationInWindow(p);
        int h = v.getHeight();
        if (v.getWidth() >= fw * 0.9f && p[1] >= top - 4 && h >= min && h <= max) {
            got[0] = v;
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) below(g.getChildAt(i), k, fw, top, min, max, got);
        }
    }

    /** Deixa transparente o fundo das views que ocupam a largura do teclado (não as teclas). */
    private static void clear(View v, int fw, int unused) {
        Drawable bg = v.getBackground();
        if (bg != null && bg != blur && bg != blur2 && bg != blur3 && v.getWidth() >= fw * 0.9f && v.getHeight() > 0) {
            if (!orig.containsKey(v)) {
                orig.put(v, bg);
                if (base == 0 && bg instanceof ColorDrawable) base = ((ColorDrawable) bg).getColor() | 0xFF000000;
            }
            v.setBackground(null);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) clear(g.getChildAt(i), fw, unused);
        }
    }

    private static void restore(View f) {
        if (orig.isEmpty()) return;
        for (java.util.Map.Entry<View, Drawable> e : orig.entrySet()) {
            try {
                e.getKey().setBackground(e.getValue());
            } catch (Throwable ignored) {
            }
        }
        orig.clear();
        holder = null;
        bar = null;
        if (nav != null) nav.setAlpha(1f);
        nav = null;
        log("teclado: fundo original de volta");
    }

    static void log(String s) {
        if (logs++ < 120) XposedBridge.log("[HeadsUpIlha] " + s);
    }
}
