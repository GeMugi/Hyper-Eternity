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
    static volatile int radius = Keys.D_KB_BLUR_R, alpha = Keys.D_KB_BLUR_A, keys = Keys.D_KB_KEYS_A;
    static final WeakHashMap<View, Integer> keyed = new WeakHashMap<>();
    static final WeakHashMap<View, Drawable> keyBg = new WeakHashMap<>();
    static int lastSig, dumps;
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

    static void install(ClassLoader cl) {
        loadPrefs();
        glassKeys(cl);
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
            XposedBridge.hookMethod(Class.forName("android.view.WindowManagerGlobal").getDeclaredMethod("addView", View.class, ViewGroup.LayoutParams.class,
                    android.view.Display.class, Window.class, int.class), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args[0] instanceof View && p.args[1] instanceof android.view.WindowManager.LayoutParams) popup((View) p.args[0], (android.view.WindowManager.LayoutParams) p.args[1]);
                    } catch (Throwable ignored) {
                    }
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
                        if ("kb_restart".equals(k)) {
                            // pedido do app de ajustes: o Gboard fecha e o Android abre de novo (cores novas do tema)
                            log("teclado: reiniciando o Gboard a pedido");
                            android.os.Process.killProcess(android.os.Process.myPid());
                            return;
                        }
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
            keys = Math.max(10, Math.min(100, sp.getInt(Keys.KB_KEYS_A, Keys.D_KB_KEYS_A)));
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
            // vidro das notificações = desfoque do que está atrás (o do sistema, entre janelas) + a
            // mistura de cores do HyperOS por cima; a mistura sozinha não desfoca nada
            if (miGlass(k)) {
                try { blur.getClass().getMethod("setColor", int.class).invoke(blur, 0); } catch (Throwable ignored) { }
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
    static int pops;

    /**
     * Menus do Gboard (stickers, GIF, configurações...) abrem numa janela própria por cima do
     * teclado: o fundo sólido dela sai e entra o mesmo desfoque.
     */
    private static void popup(final View root, android.view.WindowManager.LayoutParams lp) {
        if (lp.type == android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD) return;
        // qualquer janela nova do teclado (menus, painéis, diálogos), menos avisos curtos
        boolean sub = lp.type != android.view.WindowManager.LayoutParams.TYPE_TOAST;
        if (!sub) return;
        if (pops++ < 12) log("teclado: janela nova '" + lp.getTitle() + "' tipo=" + lp.type + " " + lp.width + "x" + lp.height + " " + root.getClass().getName());
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            Drawable b;
            boolean dumped;

            public boolean onPreDraw() {
                if (!on || root.getWidth() == 0) return true;
                if (root.getWidth() < root.getResources().getDisplayMetrics().widthPixels * 0.5f) return true;
                try {
                    if (!dumped && pops < 14) {
                        dumped = true;
                        dumpWide(root, 0);
                    }
                    int a = alpha * 255 / 100;
                    int tint = (base == 0 ? 0xFF202124 : base) & 0x00FFFFFF | (a << 24);
                    clear(root, root.getWidth(), 0);
                    View host = root instanceof ViewGroup && ((ViewGroup) root).getChildCount() == 1 ? ((ViewGroup) root).getChildAt(0) : root;
                    if (b == null) {
                        Object r = View.class.getMethod("getViewRootImpl").invoke(root);
                        b = (Drawable) r.getClass().getMethod("createBackgroundBlurDrawable").invoke(r);
                    }
                    b.getClass().getMethod("setBlurRadius", int.class).invoke(b, radius);
                    b.getClass().getMethod("setColor", int.class).invoke(b, tint);
                    if (host.getBackground() != b) {
                        if (!orig.containsKey(host)) orig.put(host, host.getBackground());
                        host.setBackground(b);
                    }
                } catch (Throwable t) {
                    if (pops < 14) log("teclado: menu sem desfoque: " + t);
                }
                return true;
            }
        });
    }

    static final WeakHashMap<View, Boolean> hdrCache = new WeakHashMap<>();
    static final WeakHashMap<View, Boolean> miViews = new WeakHashMap<>();
    static boolean miBroken;
    static int[] blend;
    static int blendNight = -1;

    /**
     * O mesmo vidro das notificações do HyperOS: desfoque do sistema (MiBlur) + a mistura de cores
     * das notificações da central (cores e modos lidos da Interface do Sistema). HyperOS 3.
     */
    static boolean miGlass(View v) {
        if (miBroken) return false;
        int night = v.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (night != blendNight) {   // trocou o modo claro/escuro: cores novas, e repinta quem já tinha
            blendNight = night;
            blend = null;
            miViews.clear();
        }
        if (miViews.containsKey(v)) return true;
        try {
            int os = 0;
            try {
                Object o = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, "ro.mi.os.version.code");
                os = Integer.parseInt(String.valueOf(o).trim());
            } catch (Throwable ignored) {
            }
            if (os >= 4 || os == 0) throw new UnsupportedOperationException("HyperOS " + os);
            if (blend == null) {
                android.content.Context sc = v.getContext().createPackageContext("com.android.systemui", 0);
                android.content.res.Resources r = sc.getResources();
                String pk = "com.android.systemui";
                int c1 = r.getIdentifier("notification_element_blend_shade_color_1", "color", pk);
                int c2 = r.getIdentifier("notification_element_blend_shade_color_2", "color", pk);
                int m1 = r.getIdentifier("notification_element_blend_shade_mode_1", "integer", pk);
                int m2 = r.getIdentifier("notification_element_blend_shade_mode_2", "integer", pk);
                if (c1 == 0 || c2 == 0 || m1 == 0 || m2 == 0) throw new IllegalStateException("cores das notificações não encontradas");
                blend = new int[]{sc.getColor(c1), r.getInteger(m1), sc.getColor(c2), r.getInteger(m2)};
            }
            Class<?> V = View.class;
            try { V.getMethod("setPassWindowBlurEnabled", boolean.class).invoke(v, Boolean.TRUE); } catch (Throwable ignored) { }
            V.getMethod("setMiBackgroundBlurMode", int.class).invoke(v, Integer.valueOf(1));
            V.getMethod("setMiBackgroundBlurRadius", int.class).invoke(v, Integer.valueOf(Math.max(1, radius)));
            java.util.ArrayList<android.graphics.Point> l = new java.util.ArrayList<>();
            l.add(new android.graphics.Point(blend[0], blend[1]));
            l.add(new android.graphics.Point(blend[2], blend[3]));
            V.getMethod("setMiBackgroundBlendColors", java.util.ArrayList.class).invoke(v, l);
            miViews.put(v, Boolean.TRUE);
            log("teclado: vidro das notificações em " + v.getClass().getSimpleName() + " " + v.getWidth() + "x" + v.getHeight()
                    + " (cores " + Integer.toHexString(blend[0]) + "/" + blend[1] + ", " + Integer.toHexString(blend[2]) + "/" + blend[3] + ")");
            return true;
        } catch (Throwable t) {
            miBroken = true;
            log("teclado: vidro das notificações indisponível, fica o desfoque comum: " + t);
            return false;
        }
    }
    static int glassLogs;

    /**
     * Teclas de vidro na origem: o Gboard pinta o fundo das teclas (e dos botões e menus com forma)
     * com um invólucro que recebe a lista de cores por estado (normal, tocada...). A cor entra já
     * trocada por vidro claro translúcido; o toque continua funcionando (fica um pouco mais claro).
     */
    private static void glassKeys(ClassLoader cl) {
        try {
            Class w = Class.forName("lsh", false, cl);
            java.lang.reflect.Constructor k = w.getDeclaredConstructor(Drawable.class, android.content.res.ColorStateList.class);
            XposedBridge.hookMethod(k, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!on || keys >= 100 || !(p.args[1] instanceof android.content.res.ColorStateList)) return;
                        p.args[1] = frost((android.content.res.ColorStateList) p.args[1]);
                    } catch (Throwable t) {
                        if (glassLogs++ < 5) log("teclado: vidro das teclas: " + t);
                    }
                }
            });
            log("teclado: vidro das teclas na origem (cores do tema)");
        } catch (Throwable t) {
            log("teclado: vidro das teclas indisponível nesta versão do Gboard: " + t);
        }
    }

    static final WeakHashMap<View, Drawable> fgOrig = new WeakHashMap<>();

    static final int[][] STATES = {
            {android.R.attr.state_pressed}, {android.R.attr.state_hovered}, {android.R.attr.state_selected},
            {android.R.attr.state_activated}, {android.R.attr.state_checked}, {}};

    static android.content.res.ColorStateList frost(android.content.res.ColorStateList c) {
        int def = c.getDefaultColor();
        int[] cols = new int[STATES.length];
        for (int i = 0; i < STATES.length; i++) cols[i] = glass(c.getColorForState(STATES[i], def));
        return new android.content.res.ColorStateList(STATES, cols);
    }

    /** Cor da tecla -> vidro claro: mais clara a tecla, mais forte o vidro (a tocada fica mais clara). */
    static int glass(int c) {
        int a = c >>> 24;
        if (a == 0) return c;
        float lum = (0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255)) / 255f;
        float g = keys * 0.5f * 255 / 100f;
        int na = Math.round(Math.min(255f, g * (0.45f + 1.3f * lum)) * a / 255f);
        return (na << 24) | 0xFFFFFF;
    }

    private static int sig(View v, int d) {
        int h = v.getClass().getName().hashCode() * 31 + (v.getVisibility() == View.VISIBLE ? 1 : 0);
        if (d < 6 && v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) h = h * 17 + sig(g.getChildAt(i), d + 1);
        }
        return h;
    }

    /** Diagnóstico: só as views visíveis e largas (painéis), com o tipo de fundo e cor. */
    private static void dumpBg(View v, int d, int fw) {
        if (d > 12 || logs > 380 || v.getVisibility() != View.VISIBLE) return;
        Drawable bg = v.getBackground();
        if (v.getWidth() >= fw * 0.3f || (bg != null && v.getWidth() > 0)) {
            String c = "";
            if (bg instanceof ColorDrawable) c = " cor=" + Integer.toHexString(((ColorDrawable) bg).getColor());
            else if (bg instanceof android.graphics.drawable.GradientDrawable) {
                android.content.res.ColorStateList cl = ((android.graphics.drawable.GradientDrawable) bg).getColor();
                c = cl == null ? "" : " cor=" + Integer.toHexString(cl.getDefaultColor());
            }
            String id = "";
            try { if (v.getId() > 0) id = " #" + v.getResources().getResourceEntryName(v.getId()); } catch (Throwable ignored) { }
            int[] loc = new int[2];
            v.getLocationInWindow(loc);
            log("teclado: " + d + " " + v.getClass().getName() + id + " " + v.getWidth() + "x" + v.getHeight() + " @" + loc[0] + "," + loc[1] + (bg == null ? "" : " fundo=" + bg.getClass().getSimpleName() + c + " a=" + bg.getAlpha()) + (v.willNotDraw() ? "" : " desenha"));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dumpBg(g.getChildAt(i), d + 1, fw);
        }
    }

    private static void dumpWide(View v, int d) {
        if (d > 6 || logs > 115) return;
        Drawable bg = v.getBackground();
        log("teclado: menu " + new String(new char[d * 2]).replace('\0', ' ') + v.getClass().getName() + " " + v.getWidth() + "x" + v.getHeight() + (bg == null ? "" : " fundo=" + bg.getClass().getSimpleName()));
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount() && i < 6; i++) dumpWide(g.getChildAt(i), d + 1);
        }
    }

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
            if (miGlass(b)) {
                try { blur2.getClass().getMethod("setColor", int.class).invoke(blur2, 0); } catch (Throwable ignored) { }
            }
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
        View fr = frame();
        if (fr != null) clear(fr, fr.getWidth(), 0);
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
        // painéis (fundo do teclado, menus, barra de sugestões): sem fundo, fica o desfoque
        if (bg != null && bg != blur && bg != blur2 && bg != blur3 && v.getWidth() >= fw * 0.6f && v.getHeight() > 0) {
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
        for (java.util.Map.Entry<View, Drawable> e : fgOrig.entrySet()) {
            try { e.getKey().setForeground(e.getValue()); } catch (Throwable ignored) { }
        }
        fgOrig.clear();
        for (View m : miViews.keySet()) {
            try { View.class.getMethod("setMiBackgroundBlurMode", int.class).invoke(m, Integer.valueOf(0)); } catch (Throwable ignored) { }
        }
        miViews.clear();
        for (View k : keyed.keySet()) {
            try {
                Drawable d = k.getBackground();
                if (d != null) d.setColorFilter(null);
                if (k.getAlpha() != 1f) k.setAlpha(1f);
            } catch (Throwable ignored) {
            }
        }
        keyed.clear();
        holder = null;
        bar = null;
        if (nav != null) nav.setAlpha(1f);
        nav = null;
        log("teclado: fundo original de volta");
    }

    static void log(String s) {
        if (logs++ < 400) XposedBridge.log("[HeadsUpIlha] " + s);
    }
}
