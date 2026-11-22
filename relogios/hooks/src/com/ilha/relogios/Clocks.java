package com.ilha.relogios;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.ilha.relogios.xp.XC_MethodHook;
import com.ilha.relogios.xp.XposedBridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Relógios do HyperOS 4 no bloqueio do HyperOS 3.
 *
 * Quando o modelo escolhido no editor é um que o HOS3 não conhece (All in One), o relógio é montado
 * pela biblioteca de relógios do HOS4 que vem no app da tela sempre ativa (com.miui.aod) instalado
 * no aparelho: o código roda num carregador próprio, com os recursos do próprio app, e só o desenho
 * (uma View) entra no lugar do relógio do bloqueio. As peças do HOS3 não se misturam com as do HOS4.
 */
final class Clocks {
    private Clocks() {}

    static int logs, pals;
    static Context aod;
    static Class<?> clockViewCls;
    static Field fCtl, fBean;
    static Method mTemplate;
    static final int TAG = 0x7e1a0e01;

    static void log(String m) {
        if (logs++ < 60) XposedBridge.log("[Relógios HOS4] " + m);
    }

    static void install(ClassLoader cl) {
        try {
            Class<?> kc = Class.forName("com.android.keyguard.clock.KeyguardClockContainer", false, cl);
            fCtl = kc.getDeclaredField("mMiuiClockController");
            fCtl.setAccessible(true);
            Class<?> ctl = Class.forName("com.miui.clock.MiuiClockController", false, cl);
            fBean = ctl.getDeclaredField("mClockBean");
            fBean.setAccessible(true);
            XposedBridge.hookMethod(kc.getDeclaredMethod("addClockView"), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    containers.put((ViewGroup) p.thisObject, Boolean.TRUE);
                    check((ViewGroup) p.thisObject, "montagem");
                }
            });
            // troca de modelo sem remontar o bloqueio
            for (Method m : ctl.getDeclaredMethods()) {
                if (!m.getName().equals("setClockBean")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        for (ViewGroup g : containers.keySet()) {
                            try {
                                if (fCtl.get(g) == p.thisObject) check(g, "modelo trocado");
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                });
            }
            try {
                XposedBridge.hookMethod(kc.getDeclaredMethod("updateClockAndMagazineColor", int.class, boolean.class, java.util.Map.class, boolean.class), new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        lastPalette = p.args.clone();
                        View mine = (View) ((ViewGroup) p.thisObject).getTag(TAG);
                        if (mine instanceof FrameLayout) palette((FrameLayout) mine);
                        if (pals++ < 4) log("cores do bloqueio repassadas (" + (p.args[2] == null ? "sem paleta" : ((java.util.Map<?, ?>) p.args[2]).size() + " cores") + ")");
                    }
                });
            } catch (Throwable t) {
                log("cores: sem gancho " + t);
            }
            Cfg.watch(new Runnable() {
                public void run() {
                    for (ViewGroup g : containers.keySet()) check(g, "ajuste mudou");
                }
            });
            bridges();
            Aod.plugin();
            Fx.captureEarly();
            log("bloqueio preparado");
        } catch (Throwable t) {
            log("não instalado: " + t);
        }
    }

    static final java.util.WeakHashMap<ViewGroup, Boolean> containers = new java.util.WeakHashMap<>();

    static String templateOf(ViewGroup g) {
        try {
            Object ctl = fCtl.get(g);
            Object bean = ctl == null ? null : fBean.get(ctl);
            if (bean == null) return null;
            if (mTemplate == null) mTemplate = bean.getClass().getMethod("getTemplateId");
            return (String) mTemplate.invoke(bean);
        } catch (Throwable t) {
            return null;
        }
    }

    /** O modelo pede a biblioteca do HOS4? Monta (ou tira) o relógio do HOS4 no container. */
    static void check(final ViewGroup g, String why) {
        Wobble.host(g);
        String tid = templateOf(g);
        boolean want = Cfg.lock && tid != null && tid.startsWith("all_in_one");
        View mine = (View) g.getTag(TAG);
        if (!want) {
            if (mine != null) {
                dropFront(mine);
                g.removeView(mine);
                g.setTag(TAG, null);
                for (int i = 0; i < g.getChildCount(); i++) g.getChildAt(i).setVisibility(View.VISIBLE);
                log("modelo " + tid + (Cfg.lock ? "" : " (relógio do HOS4 desligado no app)") + ": de volta ao relógio do HOS3");
            }
            return;
        }
        try {
            if (mine == null) {
                View v = build(g.getContext());
                if (v == null) return;
                g.addView(v, new FrameLayout.LayoutParams(-1, -1));
                g.setTag(TAG, v);
                mine = v;
                if (v instanceof FrameLayout) palette((FrameLayout) v);
                log("modelo " + tid + " (" + why + "): relógio do HOS4 montado");
            }
            // o relógio do HOS3 fica escondido por baixo (o bloqueio continua usando ele para posições)
            hideOthers(g, mine);
            guard(g);
        } catch (Throwable t) {
            log("falha ao montar: " + t + " em " + stack(t));
        }
    }

    static void hideOthers(ViewGroup g, View mine) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c != mine && c.getVisibility() == View.VISIBLE) c.setVisibility(View.INVISIBLE);
        }
    }

    static final int TAG_GUARD = 0x7e1a0e02;
    static int aodLogs;
    static boolean screenRcv;

    /**
     * Tela sempre ativa (AOD), como no HyperOS 4: quem desenha o relógio no AOD é o app da tela
     * sempre ativa (com.miui.aod), que já monta o modelo All in One sozinho, com as notificações do
     * AOD no lugar certo. O relógio que a extensão põe no bloqueio fica escondido enquanto a tela
     * está no AOD (antes ficavam os dois, um por cima do outro); o do HyperOS 3 fica sempre escondido.
     */
    static void guard(final ViewGroup g) {
        if (g.getTag(TAG_GUARD) != null) return;
        g.setTag(TAG_GUARD, Boolean.TRUE);
        g.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() {
                try {
                    View mine = (View) g.getTag(TAG);
                    if (mine == null) return true;
                    hideOthers(g, mine);
                    aodState(mine, aodNow(g));
                    depthLayer(g, mine);
                } catch (Throwable ignored) {
                }
                return true;
            }
        });
        if (!screenRcv) {
            screenRcv = true;
            try {
                android.content.IntentFilter f = new android.content.IntentFilter();
                f.addAction(android.content.Intent.ACTION_SCREEN_OFF);
                f.addAction(android.content.Intent.ACTION_SCREEN_ON);
                f.addAction(android.content.Intent.ACTION_USER_PRESENT);
                g.getContext().getApplicationContext().registerReceiver(new android.content.BroadcastReceiver() {
                    public void onReceive(Context c, android.content.Intent i) {
                        boolean off = android.content.Intent.ACTION_SCREEN_OFF.equals(i.getAction());
                        for (ViewGroup x : containers.keySet()) {
                            View mine = (View) x.getTag(TAG);
                            if (mine != null) aodState(mine, off);
                        }
                    }
                }, f);
            } catch (Throwable t) {
                log("AOD: sem aviso de tela " + t);
            }
        }
    }

    // ------------------------------------------------------------------ profundidade (recorte do papel de parede)

    static final int TAG_FRONT = 0x7e1a0e09;
    static Field fSec;
    static Method mDepth;
    static int depthLogs;
    static final int[] la = new int[2], lb = new int[2];

    /**
     * Papel de parede com profundidade: no HyperOS o relógio é dividido em duas camadas — uma atrás do
     * recorte (a pessoa) e outra na frente. O bloqueio do HOS3 tem o lugar da camada da frente
     * (mKeyguardSecondaryClockContainer, numa camada acima do recorte); o KeyguardClockContainer fica
     * atrás. Com profundidade, as horas vão para a camada da frente (numa caixa alinhada à nossa) e os
     * minutos ficam atrás, como no HyperOS 4.
     */
    static void depthLayer(ViewGroup g, View box) {
        try {
            if (!(box instanceof FrameLayout)) return;
            FrameLayout b = (FrameLayout) box;
            View hour = (View) b.getTag(TAG_HOUR);
            if (hour == null) return;
            if (fSec == null) {
                fSec = g.getClass().getDeclaredField("mKeyguardSecondaryClockContainer");
                fSec.setAccessible(true);
            }
            FrameLayout sec = (FrameLayout) fSec.get(g);
            long now = android.os.SystemClock.uptimeMillis();
            Object last = b.getTag(TAG_FRONT + 1);
            if (!(last instanceof long[]) || now - ((long[]) last)[0] > 1000) {
                b.setTag(TAG_FRONT + 1, new long[]{now, depthOn(g) ? 1 : 0});
                last = b.getTag(TAG_FRONT + 1);
            }
            boolean depth = sec != null && ((long[]) last)[1] == 1;
            FrameLayout front = (FrameLayout) b.getTag(TAG_FRONT);
            if (depth) {
                if (front == null || front.getParent() != sec) {
                    if (front != null && front.getParent() instanceof ViewGroup) ((ViewGroup) front.getParent()).removeView(front);
                    front = new Sealed(b.getContext());
                    sec.addView(front, new FrameLayout.LayoutParams(b.getWidth() > 0 ? b.getWidth() : -1, b.getHeight() > 0 ? b.getHeight() : -1));
                    b.setTag(TAG_FRONT, front);
                }
                if (hour.getParent() != front) {
                    ((ViewGroup) hour.getParent()).removeView(hour);
                    front.addView(hour, new FrameLayout.LayoutParams(-1, -1));
                    if (depthLogs++ < 4) log("profundidade: horas na camada da frente, minutos atrás do recorte");
                }
                // sem filhos do HOS3 na camada da frente (só a nossa caixa)
                for (int i = 0; i < sec.getChildCount(); i++) {
                    View c = sec.getChildAt(i);
                    if (c != front && c.getVisibility() == View.VISIBLE) c.setVisibility(View.INVISIBLE);
                }
                if (sec.getVisibility() != View.VISIBLE && g.getVisibility() == View.VISIBLE) sec.setVisibility(View.VISIBLE);
                // alinhada à caixa: tamanho, posição na tela, visibilidade e transparência
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) front.getLayoutParams();
                if (b.getWidth() > 0 && (lp.width != b.getWidth() || lp.height != b.getHeight())) {
                    lp.width = b.getWidth();
                    lp.height = b.getHeight();
                    front.setLayoutParams(lp);
                }
                b.getLocationOnScreen(la);
                sec.getLocationOnScreen(lb);
                float tx = la[0] - lb[0] - front.getLeft();
                float ty = la[1] - lb[1] - front.getTop();
                if (front.getTranslationX() != tx) front.setTranslationX(tx);
                if (front.getTranslationY() != ty) front.setTranslationY(ty);
                front.setScaleX(b.getScaleX());
                front.setScaleY(b.getScaleY());
                float a = 1f;
                for (android.view.ViewParent pp = b; pp instanceof View && pp != g.getParent(); pp = pp.getParent()) a *= ((View) pp).getAlpha();
                if (front.getAlpha() != a) front.setAlpha(a);
                int vis = b.isShown() ? View.VISIBLE : View.INVISIBLE;
                if (front.getVisibility() != vis) front.setVisibility(vis);
            } else if (front != null) {
                if (hour.getParent() == front) {
                    front.removeView(hour);
                    b.addView(hour, 0, new FrameLayout.LayoutParams(-1, -1));
                    if (depthLogs++ < 4) log("profundidade desligada: horas de volta à caixa");
                }
                if (front.getParent() instanceof ViewGroup) ((ViewGroup) front.getParent()).removeView(front);
                b.setTag(TAG_FRONT, null);
            }
        } catch (Throwable t) {
            if (depthLogs++ < 4) log("profundidade: " + t);
        }
    }

    /** A caixa vai sair: a camada da frente (com as horas) sai junto. */
    static void dropFront(View box) {
        try {
            Object f = box.getTag(TAG_FRONT);
            if (f instanceof View && ((View) f).getParent() instanceof ViewGroup) ((ViewGroup) ((View) f).getParent()).removeView((View) f);
            box.setTag(TAG_FRONT, null);
        } catch (Throwable ignored) {}
    }

    static boolean depthOn(ViewGroup g) {
        try {
            Object ctl = fCtl.get(g);
            if (ctl == null) return false;
            if (mDepth == null) {
                mDepth = ctl.getClass().getMethod("isWallpaperSupportDepth");
                mDepth.setAccessible(true);
            }
            return Boolean.TRUE.equals(mDepth.invoke(ctl));
        } catch (Throwable t) {
            return false;
        }
    }

    static void aodState(View mine, boolean aod) {
        int vis = aod ? View.INVISIBLE : View.VISIBLE;
        if (mine.getVisibility() == vis) return;
        mine.setVisibility(vis);
        if (mine.getScaleX() != 1f) { mine.setScaleX(1f); mine.setScaleY(1f); }
        if (aodLogs++ < 8) log("AOD: relógio da extensão " + (aod ? "escondido (o AOD desenha o dele)" : "de volta no bloqueio"));
    }

    /** A tela está no AOD (sempre ativa): não interativa, com o bloqueio desenhando. */
    static boolean aodNow(View v) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) v.getContext().getSystemService(Context.POWER_SERVICE);
            return pm != null && !pm.isInteractive();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Monta de novo os relógios do bloqueio (para um efeito novo valer desde o começo). */
    static String lastClockInfo;
    static boolean editorWatch;

    /**
     * O editor salva o modelo em constant_template_editor_info. O relógio do HOS3 só recebe o que
     * conhece (o modelo), não a fonte nem o estilo do All in One: o nosso ficava com a fonte antiga até
     * reiniciar o SystemUI. Mudou o relógio no editor (clockInfo): o relógio do HOS4 é remontado.
     */
    static void watchEditor(final Context ctx) {
        if (editorWatch) return;
        editorWatch = true;
        try {
            final android.content.ContentResolver cr = ctx.getContentResolver();
            lastClockInfo = clockInfo(cr);
            cr.registerContentObserver(android.provider.Settings.Secure.getUriFor("constant_template_editor_info"), false,
                    new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
                        public void onChange(boolean self) {
                            String now = clockInfo(cr);
                            if (now == null || now.equals(lastClockInfo)) return;
                            lastClockInfo = now;
                            Fx.main.removeCallbacks(rebuildEditor);
                            Fx.main.postDelayed(rebuildEditor, 400);
                        }
                    });
        } catch (Throwable t) {
            log("editor: " + t);
        }
    }

    static final Runnable rebuildEditor = new Runnable() {
        public void run() { rebuildAll("relógio mudou no editor"); }
    };

    static String clockInfo(android.content.ContentResolver cr) {
        try {
            String j = android.provider.Settings.Secure.getString(cr, "constant_template_editor_info");
            if (j == null) return null;
            org.json.JSONObject o = new org.json.JSONObject(j).optJSONObject("lockscreenInfo");
            Object ci = o == null ? null : o.opt("clockInfo");
            return ci == null ? null : ci.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    static void rebuildAll(String why) {
        for (ViewGroup g : new java.util.ArrayList<ViewGroup>(containers.keySet())) {
            View mine = (View) g.getTag(TAG);
            if (mine == null) continue;
            dropFront(mine);
            g.removeView(mine);
            g.setTag(TAG, null);
            check(g, why);
        }
    }

    static String stack(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        StackTraceElement[] st = c.getStackTrace();
        StringBuilder b = new StringBuilder(c.toString());
        for (int i = 0; i < Math.min(6, st.length); i++) b.append(" ← ").append(st[i]);
        return b.toString();
    }

    static View build(Context sys) throws Exception {
        return build(sys, true);
    }

    /** follow=false: relógio parado (cópia da cortina), sem o ajuste às notificações do bloqueio. */
    static View build(Context sys, final boolean follow) throws Exception {
        if (Safe.blocked("relogios_hos4")) {
            if (blockedLogs++ < 2) log("relógios do HOS4 desligados pela proteção (o SystemUI caiu em sequência com eles); volta na próxima versão");
            return null;
        }
        Safe.touch("relogios_hos4");   // conta só na primeira vez (por versão do módulo)
        if (aod == null) {
            aod = sys.createPackageContext("com.miui.aod", Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            try {
                clockViewCls = aod.getClassLoader().loadClass("com.miui.clock.MiuiClockView");
                aod.getClassLoader().loadClass("com.miui.clock.allInOne.AllInOneBase");
            } catch (ClassNotFoundException e) {
                log("o app da tela sempre ativa instalado não é o do HyperOS 4 (sem os relógios All in One)");
                aod = null;
                return null;
            }
            log("biblioteca de relógios do HOS4 carregada de " + aod.getApplicationInfo().sourceDir);
            Aod.fonts(aod.getClassLoader(), aod.getAssets());
            Glass.install(aod.getClassLoader());
            Fx.install(aod.getClassLoader());
            try {
                // fonte variável (tamanho livre) só em aparelhos de nível alto: só na biblioteca do HOS4
                StringBuilder b = new StringBuilder();
                flip(b, "com.miui.clock.utils.DeviceConfig", aod.getClassLoader(), "COMPUTILITY_V2_LEVEL", 4);
                log("nível do aparelho: " + b);
            } catch (Throwable t) {
                log("nível do aparelho: " + t);
            }
        }
        watchEditor(sys.getApplicationContext() != null ? sys.getApplicationContext() : sys);
        Context cx = sysCtx(aod);
        final View hour = (View) clockViewCls.getConstructor(Context.class).newInstance(cx);
        // sem modelo: o próprio controlador do HOS4 lê o modelo escolhido nas configurações
        clockViewCls.getMethod("init", int.class, boolean.class).invoke(hour, 0, false);
        final FrameLayout box = new Sealed(sys);
        box.addView(hour, new FrameLayout.LayoutParams(-1, -1));
        box.setTag(TAG_HOUR, hour);
        // o All in One é desenhado em duas camadas: horas (esta) e minutos, que o HOS4 monta à parte
        // com o mesmo modelo em "all_in_one_minute", como camada secundária (tipo 64)
        syncMinute(box, "montagem");
        // mudanças feitas no editor chegam pelo controlador das horas: a camada dos minutos acompanha
        try {
            Class<?> li = clockViewCls.getClassLoader().loadClass("com.miui.clock.MiuiClockController$MiuiClockInfoListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(li.getClassLoader(), new Class<?>[]{li}, new java.lang.reflect.InvocationHandler() {
                public Object invoke(Object o, Method m, Object[] a) {
                    final String n = m.getName();
                    if (n.equals("onClockBeanChange") || n.equals("onMiuiClockInfoChange")) box.post(new Runnable() { public void run() { syncMinute(box, n); } });
                    if (n.equals("hashCode")) return System.identityHashCode(o);
                    if (n.equals("equals")) return o == a[0];
                    if (n.equals("toString")) return "Clock4Listener";
                    return null;
                }
            });
            clockViewCls.getMethod("setMiuiClockInfoListener", li).invoke(hour, proxy);
        } catch (Throwable t) {
            log("aviso de mudanças: " + stack(t));
        }
        box.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() { if (follow && box.isAttachedToWindow()) squeeze(box); return true; }
        });
        box.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int orr, int ob) {
                if (r - l != orr - ol || b - t != ob - ot) v.post(new Runnable() { public void run() { rect(box); } });
            }
        });
        Fx.attach(box);
        return box;
    }

    /**
     * As views do relógio vêm de outro app (com.miui.aod): os ids delas podem ter o mesmo número
     * que ids do SystemUI. Uma busca do SystemUI por id (ex.: o recipiente da central de controle)
     * achava um TimeView do relógio e o SystemUI caía em loop. Esta caixa não deixa buscas de fora
     * entrarem; a biblioteca do relógio busca a partir das próprias views, então continua igual.
     */
    static final class Sealed extends FrameLayout {
        Sealed(Context c) { super(c); }
        // sobrepõe o método oculto ViewGroup.findViewTraversal(int)
        protected View findViewTraversal(int id) { return id == getId() ? this : null; }
        protected View findViewWithTagTraversal(Object tag) { return tag != null && tag.equals(getTag()) ? this : null; }
    }

    static final int TAG_HOUR = 0x7e1a0e02, TAG_MIN = 0x7e1a0e03;

    /** Contexto da biblioteca do HOS4 que se apresenta como SystemUI: o desfoque/mistura de cor do
     *  "material avançado", a fonte variável e as camadas só seguem o caminho do bloqueio assim. */
    static Context sysCtx(Context base) {
        return new android.content.ContextWrapper(base) {
            android.view.LayoutInflater inf;
            @Override public String getPackageName() { return "com.android.systemui"; }
            @Override public Object getSystemService(String name) {
                if (Context.LAYOUT_INFLATER_SERVICE.equals(name)) {
                    if (inf == null) inf = android.view.LayoutInflater.from(getBaseContext()).cloneInContext(this);
                    return inf;
                }
                return super.getSystemService(name);
            }
        };
    }

    static Object beanOf(View v) {
        try { return v == null ? null : clockViewCls.getMethod("getClockBean").invoke(v); } catch (Throwable t) { return null; }
    }

    static String strField(Object o, String f) {
        try { Field x = o.getClass().getDeclaredField(f); x.setAccessible(true); return (String) x.get(o); } catch (Throwable t) { return null; }
    }

    /** Monta ou atualiza a camada dos minutos a partir do modelo atual das horas. */
    static void syncMinute(FrameLayout box, String why) {
        try {
            View hour = (View) box.getTag(TAG_HOUR);
            Object bean = beanOf(hour);
            if (bean == null) return;
            String t = strField(bean, "templateId");
            View minute = (View) box.getTag(TAG_MIN);
            if (t == null || !t.equals("all_in_one")) {
                if (minute != null) { box.removeView(minute); box.setTag(TAG_MIN, null); }
                return;
            }
            Object minBean = bean.getClass().getMethod("clone").invoke(bean);
            Field tid = bean.getClass().getDeclaredField("templateId");
            tid.setAccessible(true);
            tid.set(minBean, t + "_minute");
            String key = String.valueOf(minBean);
            if (minute != null && key.equals(box.getTag(TAG_MIN + 1))) { rect(box); return; }
            if (minute != null) box.removeView(minute);
            minute = (View) clockViewCls.getConstructor(Context.class).newInstance(sysCtx(aod));
            clockViewCls.getMethod("init", bean.getClass(), int.class, boolean.class).invoke(minute, minBean, 64, false);
            box.addView(minute, new FrameLayout.LayoutParams(-1, -1));
            box.setTag(TAG_MIN, minute);
            box.setTag(TAG_MIN + 1, key);
            log("minutos (" + why + "): camada " + t + "_minute montada");
            rect(box);
            palette(box);
        } catch (Throwable t) {
            log("minutos: " + stack(t));
        }
    }

    /** Tamanho/peso do relógio e ligação das camadas, como o bloqueio do HOS4 faz. */
    static void rect(FrameLayout box) {
        try {
            View hour = (View) box.getTag(TAG_HOUR), minute = (View) box.getTag(TAG_MIN);
            Object bean = beanOf(hour);
            Object info = clockViewCls.getMethod("getClockStyleInfo").invoke(hour);
            if (bean == null || info == null) return;
            Method cr = null;
            for (Class<?> k = info.getClass(); k != null && cr == null; k = k.getSuperclass())
                try { cr = k.getDeclaredMethod("computeInitParamsRect", Context.class); } catch (NoSuchMethodException ignored) {}
            if (cr == null) return;
            cr.setAccessible(true);
            Object rp = cr.invoke(info, hour.getContext());
            if (rp == null) return;
            int w = rp.getClass().getField("timeWidth").getInt(rp), h = rp.getClass().getField("timeHeight").getInt(rp);
            int wt = (Integer) bean.getClass().getMethod("getClockWeight").invoke(bean);
            Sq q = sq(box);
            float tr = rp.getClass().getField("clockTopRatio").getFloat(rp), br = rp.getClass().getField("clockBottomRatio").getFloat(rp);
            boolean dbl = false;
            try { dbl = Boolean.TRUE.equals(info.getClass().getMethod("isDoubleRow").invoke(info)); } catch (Throwable ignored) {}
            int sig = 0;
            try {
                Object sg = bean.getClass().getMethod("getClassicSignature").invoke(bean);
                if (sg instanceof String && ((String) sg).length() > 0) sig = ((String) sg).indexOf('\n') >= 0 ? 2 : 1;
            } catch (Throwable ignored) {}
            if (q.w0 != w || q.h0 != h || q.wt0 != wt || q.topRatio != tr || q.botRatio != br || q.dbl != dbl || q.sig != sig) {
                q.w0 = w; q.h0 = h; q.wt0 = wt; q.topRatio = tr; q.botRatio = br; q.dbl = dbl; q.sig = sig;
                q.curH = h; q.curW = w; q.curWt = wt; q.curY = 0f; q.lastNt = Float.NaN; q.lastScene = "";
                q.drawnH = q.drawnW = -1; q.pending = false;
                q.edit = editRect(hour.getContext());
                log("parâmetros: números " + w + "x" + h + " peso " + wt + ", topo " + tr + " base " + br + (dbl ? ", duas linhas" : ", uma linha") + ", assinatura " + sig + ", área " + q.edit);
            }
            apply(box, q);
            // desfoque: com profundidade REGION_2 as horas usam o recipiente dos minutos, senão o contrário
            Object depth = null;
            try { depth = bean.getClass().getMethod("getClockDepthType").invoke(bean); } catch (Throwable ignored) {}
            boolean r2 = depth != null && "REGION_2".equals(String.valueOf(depth));
            Method getC = clockViewCls.getMethod("getBackgroundBlurContainer"), setC = clockViewCls.getMethod("setBackgroundBlurContainer", View.class);
            if (minute != null) {
                setC.invoke(hour, r2 ? getC.invoke(minute) : null);
                setC.invoke(minute, r2 ? null : getC.invoke(hour));
            }
            if (rects++ < 8) log("tamanho " + w + "x" + h + " peso " + wt + " profundidade " + depth + (minute == null ? " (sem camada dos minutos)" : ""));
        } catch (Throwable t) {
            log("tamanho: " + stack(t));
        }
    }

    static int rects, sqLogs, sceneLogs, blockedLogs;

    /** Estado do "aperto" do relógio quando há notificações (como o bloqueio do HOS4 faz). */
    static final class Sq {
        int w0, h0, wt0;                 // tamanho escolhido no editor
        float curH, curY;                // altura dos números e deslocamento atuais
        float bottom0 = Float.NaN, top0 = Float.NaN;   // faixa do relógio na tela com o tamanho cheio
        float topLimit;                  // até onde ele pode subir
        int lastH = -1, lastW = -1, lastWt = -1;
        float targetH;
        boolean moving, unscale, pending;
        int drawnH = -1, drawnW = -1;      // tamanho do relógio que está de fato desenhado
        long realAt;
        float curW, curWt, topRatio, botRatio;
        boolean dbl;
        int sig;
        android.graphics.Rect edit;
        float pivotY;
        long sceneAt;
        int pH = -1;                       // altura dos números na última medida em pixels
        float pTop = Float.NaN, pBot = Float.NaN;
        float kB = 1f, kT = 0f;            // quanto a base/topo do desenho andam por px de altura
        float lastNt = Float.NaN;
        String lastScene = "";
        long pixAt, spanAt;
        int spanH = -2;
        float spanTop = Float.NaN, spanBot = Float.NaN;
        float extraTop, extraBot;     // quanto o desenho passa das views (px, no tamanho cheio)
    }

    static boolean sameF(float a, float b) {
        return Float.isNaN(a) ? Float.isNaN(b) : !Float.isNaN(b) && Math.abs(a - b) < 0.5f;
    }

    static final int TAG_SQ = 0x7e1a0e05;

    static Sq sq(FrameLayout box) {
        Sq q = (Sq) box.getTag(TAG_SQ);
        if (q == null) { q = new Sq(); box.setTag(TAG_SQ, q); }
        return q;
    }

    static java.lang.ref.WeakReference<ViewGroup> stackRef;
    static final int[] loc = new int[2];

    static ViewGroup findStack(View v, int depth) {
        if (!(v instanceof ViewGroup) || depth > 16) return null;
        if (v.getClass().getName().endsWith("NotificationStackScrollLayout")) return (ViewGroup) v;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            ViewGroup r = findStack(g.getChildAt(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    /** Topo (na tela) do primeiro cartão visível do bloqueio: notificações ou player. NaN se não há. */
    static float notifTop(View anyView) {
        // empilhamento do módulo ativo: usa a linha onde ele pôs o primeiro cartão (e o player)
        // (vem do Hyper Eternity, quando ele está ativo, pela ponte em System.getProperties())
        Object kg = System.getProperties().get(Keys.BRIDGE_KGTOP);
        if (kg instanceof java.util.function.DoubleSupplier) {
            try {
                double v = ((java.util.function.DoubleSupplier) kg).getAsDouble();
                if (!Double.isNaN(v)) return (float) v;
            } catch (Throwable ignored) {
            }
        }
        ViewGroup st = stackRef == null ? null : stackRef.get();
        if (st == null || !st.isAttachedToWindow()) {
            st = findStack(anyView.getRootView(), 0);
            stackRef = st == null ? null : new java.lang.ref.WeakReference<ViewGroup>(st);
            if (st == null) return Float.NaN;
        }
        if (!st.isShown()) return Float.NaN;
        st.getLocationOnScreen(loc);
        float best = Float.NaN;
        for (int i = 0; i < st.getChildCount(); i++) {
            View c = st.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE || c.getAlpha() < 0.05f || c.getTransitionAlpha() < 0.05f || c.getHeight() <= 0) continue;
            String n = c.getClass().getSimpleName();
            if (n.contains("Shelf") || n.contains("Footer") || n.contains("EmptyShade")) continue;
            float y = loc[1] + c.getTop() + c.getTranslationY();
            if (Float.isNaN(best) || y < best) best = y;
        }
        return best;
    }

    /** Área útil do relógio no bloqueio, calculada pela própria biblioteca do HOS4. */
    static android.graphics.Rect editRect(Context c) {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        ((android.view.WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        try {
            Class<?> u = clockViewCls.getClassLoader().loadClass("com.miui.clock.utils.avoid.ClockDepthAvoidRuleUtils");
            Field cf = u.getDeclaredField("DEFAULT_CONFIG");
            cf.setAccessible(true);
            Object cfg = cf.get(null);
            Method m = u.getMethod("createEditRect", Context.class, int.class, int.class, cfg.getClass(), Float.class, Float.class);
            android.graphics.Rect r = (android.graphics.Rect) m.invoke(null, c, dm.widthPixels, dm.heightPixels, cfg, null, null);
            if (r != null && r.height() > 0) return r;
        } catch (Throwable t) {
            log("área do relógio: " + stack(t));
        }
        return new android.graphics.Rect(0, (int) (dm.heightPixels * 0.06f), dm.widthPixels, (int) (dm.heightPixels * 0.8f));
    }

    /**
     * Tamanho e posição do relógio para uma dada linha das notificações, com as mesmas regras e
     * medidas do bloqueio do HOS4:
     *  - sobra espaço: o relógio só sobe, repartindo o espaço perdido entre cima e baixo;
     *  - não sobra: vai para o topo da área e os números perdem altura (a largura acompanha a
     *    partir de certa proporção e o peso cai até 15%);
     *  - na altura mínima: a lista continua empurrando o relógio para cima, para fora da tela.
     * out = {deslocamento, largura, altura, peso}; devolve o nome do caso.
     */
    static String hos4(Sq q, float notifY, float d, float[] out) {
        out[0] = 0f; out[1] = q.w0; out[2] = q.h0; out[3] = q.wt0;
        android.graphics.Rect e = q.edit;
        if (e == null) return "sem área";
        float margin = 18f * d, dateH = (14f + 25f) * d;
        float sigH = q.sig == 1 ? (14f + 23f) * d : q.sig == 2 ? (14f + 48f) * d : 0f;
        float minH = q.dbl ? 164f * d : (q.h0 >= 120f * d ? 110f * d : q.h0);
        boolean weightScale = q.dbl || q.h0 >= 130f * d;
        float H = e.height();
        if (q.h0 <= 0 || q.w0 <= 0 || H <= minH) return "inválido";
        if (Float.isNaN(notifY)) notifY = e.bottom + margin;
        float baseMax = q.h0 + dateH + sigH, baseMin = minH + dateH + sigH;
        float space = H - baseMax;                                   // folga da área com o relógio cheio
        float pre = Math.max(e.bottom - notifY + margin, 0f);        // quanto a lista invade a área
        float top = H * q.topRatio;                                  // distância do topo da área ao relógio
        float squeezeRatio = q.dbl ? 0.71428573f : 2f, ratio = q.w0 / (float) q.h0;
        if (space - pre >= 0f) {
            float tr = q.topRatio, br = q.botRatio, sum = tr + br;
            if (sum <= 0f) { tr = 0.46153846f; br = 0.53846157f; } else { tr /= sum; br /= sum; }
            float up = tr * pre, above = top - up, below = space - top;
            out[0] = (above < 0f || below - br * pre < 0f) ? (above >= 0f ? below - pre : -top) : -up;
            if (out[0] > 0f) out[0] = 0f;
            return pre > 0f ? "sobe" : "livre";
        }
        float need = baseMax + pre - H;                              // quanto a altura precisa ceder
        float pushed = (H - pre - baseMin) - top;                    // no mínimo: empurrado pela lista
        out[0] = -top;
        if (q.h0 <= minH) { out[0] = pushed; return "sem aperto, empurrado"; }
        if (ratio < squeezeRatio) {
            float hr = q.w0 / squeezeRatio;                          // altura em que a largura começa a ceder
            if (hr < minH) {
                if (q.h0 - minH - need >= 0f) { out[2] = (int) (q.h0 - need); return "achata"; }
                out[2] = minH; out[0] = pushed; return "mínimo, empurrado";
            }
            float a = q.h0 - hr;
            if (a - need >= 0f) { out[2] = (int) (q.h0 - need); return "achata"; }
            float b = hr - minH, rest = need - a;
            if (b - rest >= 0f) {
                out[2] = (int) (q.h0 - need); out[1] = (int) ((q.h0 - need) * squeezeRatio);
                if (weightScale) out[3] = (int) (q.wt0 * (1f - rest / b * 0.15f));
                return "achata e estreita";
            }
            out[2] = minH; out[1] = (int) (minH * squeezeRatio); out[0] = pushed;
            if (weightScale) out[3] = (int) (q.wt0 * 0.85f);
            return "mínimo, empurrado";
        }
        float a = q.h0 - minH;
        if (a - need >= 0f) {
            out[2] = (int) (q.h0 - need); out[1] = (int) ((q.h0 - need) * ratio);
            if (weightScale) out[3] = (int) (q.wt0 * (1f - need / a * 0.15f));
            return "achata e estreita";
        }
        out[2] = minH; out[1] = (int) (minH * ratio); out[0] = pushed;
        if (weightScale) out[3] = (int) (q.wt0 * 0.85f);
        return "mínimo, empurrado";
    }

    static final float[] calc = new float[4];
    static Method setRect;

    /** A cada quadro: leva o relógio ao que o cálculo do HOS4 pede para a linha atual das notificações. */
    static void squeeze(FrameLayout box) {
        Sq q = (Sq) box.getTag(TAG_SQ);
        if (q == null || q.h0 <= 0 || !box.isShown()) return;
        squeeze(box, q);
    }

    static void squeeze(FrameLayout box, Sq q) {
        try {
            if (q.pending) {
                // o relógio refeito no quadro anterior está na tela agora: a escala passa a ser sobre ele
                q.pending = false;
                q.drawnH = q.lastH;
                q.drawnW = q.lastW;
                apply(box, q);
            }
            float d = box.getResources().getDisplayMetrics().density;
            long now = android.os.SystemClock.uptimeMillis();
            // ajuste desligado: o relógio fica (ou volta) no tamanho cheio, como sem notificações
            float nt = Cfg.follow ? notifTop(box) : Float.NaN;
            if (floorBox == null || floorBox.get() != box) floorBox = new java.lang.ref.WeakReference<View>(box);
            floorY = 0f;
            if (!q.moving && sameF(nt, q.lastNt)) return;   // nada mudou desde o último quadro
            q.lastNt = nt;
            String scene = hos4(q, nt, d, calc);
            float tY = calc[0], tW = calc[1], tH = calc[2], tWt = calc[3];
            float k = 0.4f;
            float nh = q.curH + (tH - q.curH) * k, ny = q.curY + (tY - q.curY) * k, nw = q.curW + (tW - q.curW) * k;
            if (Math.abs(tH - nh) < 1f) nh = tH;
            if (Math.abs(tW - nw) < 1f) nw = tW;
            if (Math.abs(tY - ny) < 0.5f) ny = tY;
            boolean moving = nh != tH || ny != tY || nw != tW;
            q.moving = moving;
            q.targetH = tH;
            // o peso acompanha a altura (mesma fração do caminho)
            q.curWt = Math.abs(tH - q.h0) < 1f ? tWt : q.wt0 + (tWt - q.wt0) * Math.min(1f, Math.abs(nh - q.h0) / Math.abs(tH - q.h0));
            if (nh != q.curH || ny != q.curY || nw != q.curW || !moving) {
                q.curH = nh; q.curY = ny; q.curW = nw;
                apply(box, q);
            }
            if (!scene.equals(q.lastScene) && now - q.sceneAt > 1200L) {
                q.lastScene = scene;
                q.sceneAt = now;
                log("notificações: " + scene + " (lista em " + (Float.isNaN(nt) ? "—" : (int) nt + "px") + ", números " + (int) tW + "x" + (int) tH + " de " + q.w0 + "x" + q.h0 + ", peso " + (int) tWt + ", desloca " + (int) tY + "px)");
            }
            if (moving) box.invalidate();   // próximo quadro continua a animação
        } catch (Throwable t) {
            if (sqLogs++ < 30) log("aperto: " + stack(t));
        }
    }

    static volatile float floorY = Float.NaN;
    static volatile long floorAt;
    static volatile java.lang.ref.WeakReference<View> floorBox;

    /** Base atual do relógio do HOS4 na tela (px); NaN quando ele não está à mostra. */
    static float floor() {
        View b = floorBox == null ? null : floorBox.get();
        return b != null && b.isAttachedToWindow() && b.isShown() ? floorY : Float.NaN;
    }

    /** Aplica o tamanho atual às duas camadas. */
    static void apply(FrameLayout box, Sq q) {
        try {
            View hour = (View) box.getTag(TAG_HOUR), minute = (View) box.getTag(TAG_MIN);
            int h = Math.round(q.curH), w = Math.round(q.curW), wt = Math.round(q.curWt);
            long now = android.os.SystemClock.uptimeMillis();
            // Refazer o relógio (fonte variável das duas camadas) custa mais que um quadro neste
            // sistema. Então, em movimento, a imagem do último relógio refeito é esticada pela GPU, e
            // ele é refeito de verdade ao parar — ou quando a imagem já destoaria (mais de 8%).
            boolean first = q.drawnH <= 0 || q.drawnW <= 0;
            float sy = first ? 1f : h / (float) q.drawnH, sx = first ? 1f : w / (float) q.drawnW;
            float dev = Math.max(Math.abs(sy - 1f), Math.abs(sx - 1f));
            boolean changed = h != q.lastH || w != q.lastW || wt != q.lastWt;
            if (first || (!q.pending && changed && (!q.moving || Aod.nativeVar || (dev > 0.05f && now - q.realAt > 50L)))) {
                q.lastH = h; q.lastW = w; q.lastWt = wt;
                if (setRect == null) {
                    Class<?> region = clockViewCls.getClassLoader().loadClass("com.miui.clock.allInOne.AllInOneClockStyle$Region");
                    setRect = clockViewCls.getMethod("setAllInOneClockRectParams", region, float.class, float.class, int.class, int.class, int.class);
                }
                setRect.invoke(hour, null, -1f, -1f, w, h, wt);
                if (minute != null) setRect.invoke(minute, null, -1f, -1f, w, h, wt);
                q.realAt = now;
                // O relógio muda a fonte na hora, mas a posição só no próximo quadro: por um quadro
                // ficava fonte nova + escala/posição velhas (a piscada). Mede e posiciona já, antes de
                // desenhar este quadro, e a escala volta a 1 junto.
                if (box.getWidth() > 0 && box.getHeight() > 0 && box.isLayoutRequested()) {
                    box.measure(View.MeasureSpec.makeMeasureSpec(box.getWidth(), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(box.getHeight(), View.MeasureSpec.EXACTLY));
                    box.layout(box.getLeft(), box.getTop(), box.getRight(), box.getBottom());
                }
                q.drawnH = h; q.drawnW = w; sx = sy = 1f;
                q.pending = false;
            }
            if (sx != box.getScaleX() || sy != box.getScaleY()) {
                if (box.getScaleY() == 1f && box.getScaleX() == 1f) {
                    box.getLocationOnScreen(loc);
                    float clockTop = q.edit == null ? 0f : q.edit.top + q.edit.height() * q.topRatio;
                    q.pivotY = clockTop - (loc[1] - box.getTranslationY());
                }
                box.setPivotX(box.getWidth() / 2f);
                box.setPivotY(q.pivotY);
                box.setScaleX(sx);
                box.setScaleY(sy);
            }
            // desloca a nossa caixa (as views da biblioteca têm animações próprias que zeram a delas)
            box.setTranslationY(q.curY);
            if (hour.getTranslationY() != 0f) hour.setTranslationY(0f);
            if (minute != null && minute.getTranslationY() != 0f) minute.setTranslationY(0f);
        } catch (Throwable t) {
            if (sqLogs++ < 30) log("aplicar tamanho: " + stack(t));
        }
    }
    static Object[] lastPalette;

    /** Cores do bloqueio (as mesmas que o HOS3 manda pro relógio dele) para as duas camadas. */
    static void palette(FrameLayout box) {
        Object[] a = lastPalette;
        if (a == null) return;
        try {
            Method m = clockViewCls.getMethod("setClockPalette", int.class, boolean.class, java.util.Map.class);
            Method d = clockViewCls.getMethod("setInfoTextColorDark", boolean.class);
            for (int tag : new int[]{TAG_HOUR, TAG_MIN}) {
                View v = (View) box.getTag(tag);
                if (v == null) continue;
                m.invoke(v, a[0], a[1], a[2]);
                d.invoke(v, a[3]);
            }
        } catch (Throwable t) {
            log("cores: " + stack(t));
        }
    }

    /** Pontes com o Hyper Eternity: o relógio da cortina e a base do relógio (para o relógio dele não brigar com este). */
    static void bridges() {
        java.util.Properties p = System.getProperties();
        p.put(Keys.BRIDGE_FLOOR, new java.util.function.DoubleSupplier() {
            public double getAsDouble() { return floor(); }
        });
        p.put(Keys.BRIDGE_BUILD, new java.util.function.Function<Context, View>() {
            public View apply(Context c) {
                if (!Cfg.curtain) return null;
                try {
                    View v = build(c, false);
                    if (v instanceof FrameLayout) palette((FrameLayout) v);
                    if (v != null) log("relógio da cortina montado");
                    return v;
                } catch (Throwable t) {
                    log("cortina: " + stack(t));
                    return null;
                }
            }
        });
    }

    static Object unsafe;
    static Method mPutInt, mGetOff;

    /** Troca o valor de um campo estático (mesmo final), para a biblioteca do HOS4 se achar num aparelho de nível alto. */
    static void flip(StringBuilder out, String cls, ClassLoader cl, String name, int val) {
        try {
            Class<?> k = Class.forName(cls, true, cl);
            Field f = k.getDeclaredField(name);
            f.setAccessible(true);
            Object before = f.get(null);
            try {
                f.set(null, val);
            } catch (Throwable t) {
                if (unsafe == null) {
                    Class<?> u = Class.forName("sun.misc.Unsafe");
                    Field tu = u.getDeclaredField("theUnsafe");
                    tu.setAccessible(true);
                    unsafe = tu.get(null);
                    mPutInt = u.getMethod("putInt", Object.class, long.class, int.class);
                    mGetOff = Field.class.getDeclaredMethod("getOffset");
                    mGetOff.setAccessible(true);
                }
                long off = ((Number) mGetOff.invoke(f)).longValue();
                mPutInt.invoke(unsafe, k, off, val);
            }
            out.append(k.getSimpleName()).append('.').append(name).append(' ').append(before).append("->").append(f.get(null));
        } catch (Throwable t) {
            out.append(name).append(" (").append(t.getClass().getSimpleName()).append(")");
        }
    }
}
