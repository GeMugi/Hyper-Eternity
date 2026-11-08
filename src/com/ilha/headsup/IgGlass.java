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
    static volatile int style;   // 0 = líquido (transparente, lente nas bordas), 1 = fosco
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
                    style = sp.getInt(Keys.IG_GLASS_STYLE, Keys.D_IG_GLASS_STYLE);
                    sp.registerOnSharedPreferenceChangeListener(new SharedPreferences.OnSharedPreferenceChangeListener() {
                        public void onSharedPreferenceChanged(SharedPreferences s, String k) {
                            if (Keys.IG_GLASS.equals(k)) on = s.getBoolean(Keys.IG_GLASS, Keys.D_IG_GLASS);
                            if (Keys.IG_GLASS_STYLE.equals(k)) style = s.getInt(Keys.IG_GLASS_STYLE, Keys.D_IG_GLASS_STYLE);
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
            hookDrag();
            log("pronto");
        } catch (Throwable t2) {
            log("falhou: " + t2);
        }
    }

    static final WeakHashMap<View, Boolean> drawHooked = new WeakHashMap<>();
    static boolean selfCancel;

    /**
     * Segurar e deslizar na barra (como no iOS): a bolha segue o dedo, crescida como lupa; ao soltar,
     * abre a aba que ficou embaixo do dedo. Um toque simples continua sendo o toque do Instagram.
     */
    static void hookDrag() {
        try {
            XposedBridge.hookMethod(ViewGroup.class.getDeclaredMethod("dispatchTouchEvent", android.view.MotionEvent.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!on || selfCancel) return;
                    View v = (View) p.thisObject;
                    Drawable bg = v.getBackground();
                    if (!(bg instanceof Backdrop)) return;
                    Backdrop bd = (Backdrop) bg;
                    if (bd.bar != v || bd.flat) return;
                    try {
                        bd.touch(v, (android.view.MotionEvent) p.args[0], p);
                    } catch (Throwable t) {
                        log("deslizar: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            log("deslizar indisponível: " + t);
        }
    }

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
                if (!(pill.getForeground() instanceof Fg)) pill.setForeground(new Fg((Backdrop) bg));
                pill.invalidate();
                return;
            }
            bg = ((Backdrop) bg).orig;
        }
        boolean dark = (bar.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        Backdrop nb = new Backdrop(pill, bar, bg, 24 * dp, dark);
        pill.setBackground(nb);
        try {
            // a bolha que se move fica por cima dos ícones (como no iOS, o ícone aparece ampliado nela)
            pill.setForeground(new Fg(nb));
        } catch (Throwable t) {
            log("bolha por cima: " + t);
        }
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
    /** Primeiro plano da barra: a bolha de vidro limpo que aparece ao segurar/deslizar. */
    static final class Fg extends Drawable {
        final Backdrop bd;

        Fg(Backdrop bd) { this.bd = bd; }

        @Override public void draw(android.graphics.Canvas c) {
            try {
                if (on && !bd.flat) bd.drawBubble(c);
            } catch (Throwable t) {
                if (logs++ < 30) log("bolha: " + t);
            }
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    static final class Backdrop extends Drawable {
        final View owner, bar;
        final Drawable orig;
        final float radius;
        final boolean dark;
        final android.graphics.RenderNode node = new android.graphics.RenderNode("ilha-vidro");
        final android.graphics.RenderNode raw = new android.graphics.RenderNode("ilha-fundo");
        final android.graphics.RenderNode capNode = new android.graphics.RenderNode("ilha-bolha");
        android.graphics.RuntimeShader bubble;
        static final String BUBBLE = "uniform shader content; uniform float2 c0; uniform float2 hs; uniform float mag; uniform float chroma;"
                + "half4 main(float2 p){ float2 d=p-c0; float rad=hs.y; float2 q=abs(d)-(hs-rad); float2 qp=max(q,0.0);"
                + " float sd=length(qp)+min(max(q.x,q.y),0.0)-rad;"
                + " float2 g= length(qp)>0.0 ? qp/length(qp) : (q.x>q.y ? float2(1.0,0.0) : float2(0.0,1.0));"
                + " float2 n=g*sign(d); float e=clamp(1.0+sd/(rad*0.9),0.0,1.0); float k=1.0-sqrt(max(1.0-e*e,0.0));"
                + " float2 base=c0+d/(1.0+mag); float2 o=n*k*rad*0.7; float4 col=float4(0.0,0.0,0.0,1.0);"
                + " half4 mid=content.eval(base+o); col.r=content.eval(base+o*(1.0+chroma)).r; col.g=mid.g; col.b=content.eval(base+o*(1.0-chroma)).b;"
                + " col.a=mid.a; col.r=min(col.r,col.a); col.b=min(col.b,col.a); return half4(col*clamp(0.5-sd,0.0,1.0)); }";
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
            rim.setStyle(android.graphics.Paint.Style.STROKE);
            effect(0, 0, 0);
        }

        // vidro líquido (iOS 26): desfoque + cor mais viva + lente nas bordas (o fundo se curva e
        // separa um pouco as cores perto da borda), com reflexo claro na borda de cima
        // lente de vidro líquido: perto da borda o fundo é puxado de fora para dentro (perfil de uma
        // lente redonda), comprimindo o que está além da borda, com leve separação de cores
        static final String LENS = "uniform shader content; uniform float2 size; uniform float2 off; uniform float rad; uniform float depth; uniform float band; uniform float chroma; uniform float2 lo; uniform float2 hi;"
                + "half4 main(float2 pn){ float2 p=pn-off; float2 c=size*0.5; float2 s=sign(p-c); float2 q=abs(p-c)-(c-rad);"
                + " float2 qp=max(q,0.0); float d=length(qp)+min(max(q.x,q.y),0.0)-rad;"
                + " float2 g= length(qp)>0.0 ? qp/length(qp) : (q.x>q.y ? float2(1.0,0.0) : float2(0.0,1.0));"
                + " float2 n=g*s; float e=clamp(1.0+d/band,0.0,1.0); float k=1.0-sqrt(max(1.0-e*e,0.0));"
                + " float2 o=n*k*depth; float4 col=float4(0.0,0.0,0.0,1.0); col.r=content.eval(clamp(pn+o*(1.0+chroma),lo,hi)).r;"
                + " half4 mid=content.eval(clamp(pn+o,lo,hi)); col.g=mid.g; col.b=content.eval(clamp(pn+o*(1.0-chroma),lo,hi)).b;"
                + " col.a=mid.a; col.r=min(col.r,col.a); col.b=min(col.b,col.a); return half4(col*clamp(0.5-d,0.0,1.0)); }";
        android.graphics.RuntimeShader lens;
        int ew = -1, eh = -1, es = -1, margin;
        // onde existe conteúdo de verdade (fora disso a lente pegava o vazio: listras pretas)
        final android.graphics.RectF valid = new android.graphics.RectF(), validNow = new android.graphics.RectF();
        float blurPx;
        final android.graphics.Paint rim = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Paint tint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Paint shine = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Paint spec = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        boolean chainLogged;
        final android.graphics.Rect hit = new android.graphics.Rect();

        /**
         * Tudo o que fica atrás da barra, em todos os níveis da tela: de cima (janela) para baixo, os
         * irmãos anteriores de cada ancestral da barra que cruzam a área da pílula. O feed não é irmão
         * direto da barra no Instagram (o pai dela só tem a faixa escura de baixo), por isso antes o
         * vidro ficava escuro e sem nada atrás.
         */
        void behind(android.graphics.RecordingCanvas rc, float ox, float oy, int w, int h) {
            java.util.ArrayList<View> chain = new java.util.ArrayList<>();
            View v = bar;
            while (v != null) {
                chain.add(0, v);
                v = v.getParent() instanceof View ? (View) v.getParent() : null;
            }
            View root = chain.get(0);
            hit.set(Math.round(ox), Math.round(oy), Math.round(ox) + w, Math.round(oy) + h);
            // fundo da janela
            Drawable rb = root.getBackground();
            if (rb != null) {
                int sv = rc.save();
                rc.translate(-ox, -oy);
                rb.draw(rc);
                rc.restoreToCount(sv);
            }
            StringBuilder lg = chainLogged ? null : new StringBuilder();
            for (int i = 0; i < chain.size() - 1; i++) {
                if (!(chain.get(i) instanceof ViewGroup)) break;
                ViewGroup g = (ViewGroup) chain.get(i);
                View next = chain.get(i + 1);
                for (int k = 0; k < g.getChildCount(); k++) {
                    View c = g.getChildAt(k);
                    if (c == next) break;
                    if (c.getVisibility() != View.VISIBLE || c.getAlpha() == 0f || c.getWidth() == 0 || c.getHeight() == 0) continue;
                    if (scrim(c)) continue;
                    c.getLocationInWindow(b);
                    if (!android.graphics.Rect.intersects(hit, new android.graphics.Rect(b[0], b[1], b[0] + c.getWidth(), b[1] + c.getHeight()))) continue;
                    int sv = rc.save();
                    rc.translate(b[0] - ox, b[1] - oy);
                    c.draw(rc);
                    rc.restoreToCount(sv);
                    if (lg != null) lg.append(" | ").append(i).append(':').append(name(c));
                }
            }
            if (lg != null) {
                chainLogged = true;
                log("atrás da barra:" + lg);
            }
        }

        /** A faixa escura que o Instagram põe embaixo da barra flutuante: não entra no vidro. */
        boolean scrim(View c) {
            try {
                return c.getId() > 0 && c.getResources().getResourceEntryName(c.getId()).contains("nav_bar_gradient");
            } catch (Throwable t) {
                return false;
            }
        }

        /** Devolve a marca da aba do Instagram (onde não há vidro). */
        void showMarks() {
            for (Drawable d : hidden.keySet()) d.setAlpha(255);
            hidden.clear();
        }

        // aba selecionada: cápsula de vidro que desliza até a aba (no lugar da marca do Instagram)
        float capX = Float.NaN, capW;
        final android.graphics.Paint cap = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Paint capRim = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final java.util.ArrayList<View> tabs = new java.util.ArrayList<>();
        final java.util.WeakHashMap<Drawable, Boolean> hidden = new java.util.WeakHashMap<>();
        boolean dumped;

        void collect(View v, int d, float bw, float bh) {
            if (d > 6 || v.getVisibility() != View.VISIBLE) return;
            if (v != bar && v.getWidth() >= bw * 0.1f && v.getWidth() <= bw * 0.32f && v.getHeight() >= bh * 0.45f) {
                tabs.add(v);
                return;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), d + 1, bw, bh);
            }
        }

        void dump(View v, int d) {
            if (d > 6 || logs > 28) return;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d; i++) sb.append("  ");
            log("abas: " + sb + name(v) + " fundo=" + cls(v.getBackground()) + (v.isSelected() ? " SELECIONADA" : "") + (v.isActivated() ? " ativa" : ""));
            if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) dump(((ViewGroup) v).getChildAt(i), d + 1);
        }

        final android.graphics.Path capPath = new android.graphics.Path();
        boolean pressed, dragging, flat;
        float dragX, downX, grow;

        void touch(View v, android.view.MotionEvent e, XC_MethodHook.MethodHookParam p) {
            int[] o = new int[2];
            owner.getLocationOnScreen(o);
            float x = e.getRawX() - o[0];
            float slop = 10 * owner.getResources().getDisplayMetrics().density;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    pressed = true;
                    dragging = false;
                    downX = dragX = x;
                    // o arrasto na barra é dela: o Instagram não troca de página (câmera/direct) junto
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    owner.invalidate();
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (!pressed) return;
                    dragX = x;
                    if (!dragging && Math.abs(x - downX) > slop) {
                        dragging = true;
                        // o toque deixa de ser do Instagram: a aba não abre no meio do arrasto
                        android.view.MotionEvent c = android.view.MotionEvent.obtain(e);
                        c.setAction(android.view.MotionEvent.ACTION_CANCEL);
                        selfCancel = true;
                        try { v.dispatchTouchEvent(c); } finally { selfCancel = false; c.recycle(); }
                    }
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    if (dragging) p.setResult(Boolean.TRUE);
                    owner.invalidate();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    boolean was = dragging;
                    pressed = false;
                    dragging = false;
                    if (was) {
                        p.setResult(Boolean.TRUE);
                        if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                            View hitTab = null;
                            for (View t : tabs) {
                                t.getLocationOnScreen(b);
                                float l = b[0] - o[0];
                                if (x >= l && x < l + t.getWidth()) { hitTab = t; break; }
                            }
                            if (hitTab == null && !tabs.isEmpty()) hitTab = x < 0 ? tabs.get(0) : tabs.get(tabs.size() - 1);
                            if (hitTab != null) hitTab.performClick();
                        }
                    }
                    owner.invalidate();
                    break;
            }
        }

        // geometria da bolha (para o primeiro plano)
        float bCx, bCy, bW, bH, bOx, bOy;
        final android.graphics.Paint glow = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Paint inner = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        /** Aba selecionada parada: um clarão suave de vidro atrás do ícone (sem contorno). */
        void capsule(android.graphics.Canvas c, android.graphics.Rect r, boolean dark, int m) {
            tabs.clear();
            collect(bar, 0, bar.getWidth(), bar.getHeight());
            if (tabs.size() < 2) return;
            if (!dumped) { dumped = true; dump(bar, 0); }
            View sel = null;
            for (View t : tabs) if (t.isSelected() || hasSelected(t, 0)) { sel = t; break; }
            for (View t : tabs) hideBg(t, 0);
            if (sel == null) return;
            owner.getLocationInWindow(a);
            sel.getLocationInWindow(b);
            float tx = b[0] - a[0] + sel.getWidth() / 2f;
            float tw = sel.getWidth();
            if (pressed) tx = Math.max(r.left, Math.min(r.right, dragX));
            if (Float.isNaN(capX)) { capX = tx; capW = tw; }
            float diff = tx - capX;
            capX += diff * (pressed ? 0.55f : 0.2f);
            capW += (tw - capW) * 0.2f;
            float gt = pressed ? 1f : Math.min(1f, Math.abs(diff) / (tw * 0.6f));
            grow += (gt - grow) * (gt > grow ? 0.35f : 0.18f);
            if (Math.abs(diff) > 0.5f || Math.abs(gt - grow) > 0.01f || pressed) owner.postInvalidateOnAnimation(); else { capX = tx; grow = gt; }
            float dp = owner.getResources().getDisplayMetrics().density;
            float pad = 5 * dp;
            float h0 = r.height() - 2 * pad;
            float w0 = Math.max(h0 * 1.55f, capW * 0.95f);
            float cx0 = Math.max(r.left + pad + w0 / 2f, Math.min(r.right - pad - w0 / 2f, capX));
            float cy = r.exactCenterY();
            // parado: clarão suave (some enquanto a bolha aparece)
            float rest = 1f - grow;
            if (rest > 0.02f) {
                // cápsula clara de vidro, com a borda levemente esfumada
                // cápsula de vidro como na referência: forma nítida, preenchimento claro uniforme
                // (um pouco mais claro à esquerda) e borda fina mais brilhante à esquerda
                float gl = cx0 - w0 / 2f, gt2 = cy - h0 / 2f, gr = h0 / 2f;
                glow.setMaskFilter(null);
                glow.setStyle(android.graphics.Paint.Style.FILL);
                glow.setShader(new android.graphics.LinearGradient(gl, 0, gl + w0, 0,
                        new int[]{((int) (0x4D * rest) << 24) | 0xFFFFFF, ((int) (0x33 * rest) << 24) | 0xFFFFFF, ((int) (0x29 * rest) << 24) | 0xFFFFFF},
                        new float[]{0f, 0.5f, 1f}, android.graphics.Shader.TileMode.CLAMP));
                c.drawRoundRect(gl, gt2, gl + w0, gt2 + h0, gr, gr, glow);
                inner.setMaskFilter(null);
                inner.setStyle(android.graphics.Paint.Style.STROKE);
                float iw = 1.2f * dp;
                inner.setStrokeWidth(iw);
                inner.setShader(new android.graphics.LinearGradient(gl, 0, gl + w0, 0,
                        new int[]{((int) (0xB3 * rest) << 24) | 0xFFFFFF, ((int) (0x26 * rest) << 24) | 0xFFFFFF, ((int) (0x4D * rest) << 24) | 0xFFFFFF},
                        new float[]{0f, 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP));
                c.drawRoundRect(gl + iw / 2, gt2 + iw / 2, gl + w0 - iw / 2, gt2 + h0 - iw / 2, gr - iw / 2, gr - iw / 2, inner);
                inner.setShader(null);
            }
            // a bolha (desenhada no primeiro plano): maior que a pílula, segue o dedo
            bH = h0 * (1f + 0.42f * grow) + pad * 2 * grow;
            bW = w0 * (1f + 0.22f * grow);
            bCx = Math.max(r.left + bW / 2f - 8 * dp, Math.min(r.right - bW / 2f + 8 * dp, capX));
            bCy = cy;
            bOx = r.left - m;
            bOy = r.top - m;
        }

        /** Bolha de vidro limpo: fundo e ícones ampliados como lupa, bordas que entortam e separam as cores. */
        void drawBubble(android.graphics.Canvas c) {
            if (grow < 0.02f || android.os.Build.VERSION.SDK_INT < 33 || raw.getWidth() <= 0) return;
            float dp = owner.getResources().getDisplayMetrics().density;
            float al = Math.min(1f, grow * 1.6f);
            if (bubble == null) bubble = new android.graphics.RuntimeShader(BUBBLE);
            bubble.setFloatUniform("c0", bCx - bOx, bCy - bOy);
            bubble.setFloatUniform("hs", bW / 2f, bH / 2f);
            bubble.setFloatUniform("mag", 0.06f + 0.24f * grow);
            bubble.setFloatUniform("chroma", 0.18f * grow);
            capNode.setPosition(0, 0, raw.getWidth(), raw.getHeight());
            android.graphics.RecordingCanvas rc = capNode.beginRecording(raw.getWidth(), raw.getHeight());
            rc.drawRenderNode(raw);
            // os ícones entram na lupa
            owner.getLocationInWindow(a);
            for (View t : tabs) {
                t.getLocationInWindow(b);
                int sv = rc.save();
                rc.translate(b[0] - a[0] - bOx, b[1] - a[1] - bOy);
                t.draw(rc);
                rc.restoreToCount(sv);
            }
            capNode.endRecording();
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix(new float[]{
                    1.04f, 0, 0, 0, 6, 0, 1.04f, 0, 0, 6, 0, 0, 1.04f, 0, 6, 0, 0, 0, al, 0});
            capNode.setRenderEffect(android.graphics.RenderEffect.createChainEffect(
                    android.graphics.RenderEffect.createColorFilterEffect(new android.graphics.ColorMatrixColorFilter(cm)),
                    android.graphics.RenderEffect.createRuntimeShaderEffect(bubble, "content")));
            int sv = c.save();
            c.translate(bOx, bOy);
            c.drawRenderNode(capNode);
            c.restoreToCount(sv);
            float l = bCx - bW / 2f, t = bCy - bH / 2f, rr = bH / 2f;
            // borda clara e brilho interno
            capRim.setStyle(android.graphics.Paint.Style.STROKE);
            capRim.setStrokeWidth(1.5f * dp);
            capRim.setShader(new android.graphics.LinearGradient(l, t, l + bW, t + bH,
                    new int[]{((int) (0xF0 * al) << 24) | 0xFFFFFF, ((int) (0x55 * al) << 24) | 0xFFFFFF, ((int) (0xC0 * al) << 24) | 0xFFFFFF},
                    new float[]{0f, 0.5f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            c.drawRoundRect(l, t, l + bW, t + bH, rr, rr, capRim);
            inner.setStyle(android.graphics.Paint.Style.STROKE);
            inner.setStrokeWidth(4 * dp);
            inner.setMaskFilter(new android.graphics.BlurMaskFilter(3 * dp, android.graphics.BlurMaskFilter.Blur.NORMAL));
            inner.setColor(((int) (0x40 * al) << 24) | 0xFFFFFF);
            float in = 3 * dp;
            c.drawRoundRect(l + in, t + in, l + bW - in, t + bH - in, rr - in, rr - in, inner);
        }

        boolean hasSelected(View v, int d) {
            if (d > 3 || !(v instanceof ViewGroup)) return false;
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View x = g.getChildAt(i);
                if (x.isSelected() || hasSelected(x, d + 1)) return true;
            }
            return false;
        }

        void hideBg(View v, int d) {
            Drawable bg = v.getBackground();
            if (bg != null && !(bg instanceof Backdrop) && v.getWidth() < bar.getWidth() * 0.5f && v.getHeight() >= bar.getHeight() * 0.4f && v.getWidth() >= bar.getWidth() * 0.08f) {
                if (bg.getAlpha() != 0) { bg.mutate().setAlpha(0); hidden.put(bg, Boolean.TRUE); }
            }
            if (d < 3 && v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) hideBg(g.getChildAt(i), d + 1);
            }
        }

        void effect(int w, int h, float rad) {
            float dp = owner.getResources().getDisplayMetrics().density;
            boolean liquid = style == 0;
            // líquido: quase sem desfoque, quem trabalha é a lente; fosco: desfoque médio e lente leve
            // líquido: vidro limpo (quase sem desfoque, lente forte); fosco: como o Instagram do iOS
            float br = liquid ? 1.5f * dp : 11f * dp;
            blurPx = br;
            float depth = (liquid ? 20f : 9f) * dp;
            margin = (int) Math.ceil(depth + 4 * dp);
            android.graphics.RenderEffect blur = android.graphics.RenderEffect.createBlurEffect(br, br, android.graphics.Shader.TileMode.CLAMP);
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
            cm.setSaturation(liquid ? 1.25f : 1.3f);
            android.graphics.RenderEffect e = android.graphics.RenderEffect.createChainEffect(
                    android.graphics.RenderEffect.createColorFilterEffect(new android.graphics.ColorMatrixColorFilter(cm)), blur);
            if (w > 0 && h > 0 && android.os.Build.VERSION.SDK_INT >= 33) {
                try {
                    if (lens == null) lens = new android.graphics.RuntimeShader(LENS);
                    lens.setFloatUniform("size", w, h);
                    lens.setFloatUniform("off", margin, margin);
                    lens.setFloatUniform("rad", Math.max(1f, rad));
                    lens.setFloatUniform("depth", depth);
                    lens.setFloatUniform("band", Math.min(rad * 0.95f, (liquid ? 26f : 14f) * dp));
                    lens.setFloatUniform("chroma", liquid ? 0.12f : 0.05f);
                    float in = br * 1.5f + 1f;
                    lens.setFloatUniform("lo", valid.left + in, valid.top + in);
                    lens.setFloatUniform("hi", Math.max(valid.left + in, valid.right - in), Math.max(valid.top + in, valid.bottom - in));
                    e = android.graphics.RenderEffect.createChainEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(lens, "content"), e);
                } catch (Throwable t) {
                    if (logs++ < 30) log("instagram: lente indisponível: " + t);
                }
            }
            node.setRenderEffect(e);
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
                flat = r.width() >= owner.getWidth() * 0.97f;
                if (flat) {
                    // aba de vídeos (Reels): a barra ocupa a largura toda sobre fundo preto, sem vidro
                    showMarks();
                    capX = Float.NaN;
                    orig.setAlpha(255);
                    orig.draw(c);
                    return;
                }
                owner.getLocationInWindow(a);
                // área com conteúdo, em coordenadas da gravação (pai da barra, dentro da janela)
                {
                    ViewGroup pp = (ViewGroup) bar.getParent();
                    int mm = margin;
                    float ox = a[0] + r.left - mm, oy = a[1] + r.top - mm;
                    int[] pl = new int[2];
                    if (pp != null) pp.getLocationInWindow(pl);
                    View root = owner.getRootView();
                    int[] bl = new int[2];
                    bar.getLocationInWindow(bl);
                    float L = Math.max(0, bl[0]), T = Math.max(0, Math.min(bl[1], a[1] + r.top - mm));
                    float R = Math.min(root.getWidth(), bl[0] + bar.getWidth()), B = Math.min(root.getHeight(), bl[1] + bar.getHeight());
                    validNow.set(L - ox, T - oy, R - ox, B - oy);
                }
                if (r.width() != ew || r.height() != eh || style != es || !validNow.equals(valid)) {
                    valid.set(validNow);
                    ew = r.width();
                    eh = r.height();
                    es = style;
                    effect(ew, eh, rad);
                    if (logs++ < 30) log("instagram: vidro líquido " + ew + "x" + eh + " raio " + Math.round(rad) + (lens != null ? " com lente" : " sem lente"));
                }
                // a lente puxa o que está um pouco além da borda: grava o fundo com margem
                int m = margin;
                int nw = r.width() + 2 * m, nh = r.height() + 2 * m;
                raw.setPosition(0, 0, nw, nh);
                android.graphics.RecordingCanvas rc = raw.beginRecording(nw, nh);
                behind(rc, a[0] + r.left - m, a[1] + r.top - m, nw, nh);
                raw.endRecording();
                node.setPosition(0, 0, nw, nh);
                rc = node.beginRecording(nw, nh);
                rc.drawRenderNode(raw);
                node.endRecording();
                path.reset();
                path.addRoundRect(r.left, r.top, r.right, r.bottom, rad, rad, android.graphics.Path.Direction.CW);
                boolean liquid = style == 0;
                // sem recorte no canvas: o recorte da pílula é feito pela própria lente, assim a imagem
                // além da borda (que a lente puxa para dentro) não é cortada antes do efeito
                int save = c.save();
                c.translate(r.left - m, r.top - m);
                c.drawRenderNode(node);
                c.restoreToCount(save);
                c.save();
                c.clipPath(path);
                // os dois escurecem um pouco (tema escuro) ou clareiam (tema claro); o fosco mais
                tint.setColor(liquid ? (dark ? 0x2E000000 : 0x26FFFFFF) : (dark ? 0x4D000000 : 0x4DFFFFFF));
                c.drawPath(path, tint);
                c.restore();
                // borda fina de vidro
                float dp = owner.getResources().getDisplayMetrics().density;
                float sw = 1f * dp;
                rim.setStrokeWidth(sw);
                rim.setShader(new android.graphics.LinearGradient(0, r.top, 0, r.bottom,
                        new int[]{dark ? 0x66FFFFFF : 0xCCFFFFFF, dark ? 0x26FFFFFF : 0x59FFFFFF, dark ? 0x40FFFFFF : 0x99FFFFFF},
                        new float[]{0f, 0.5f, 1f}, android.graphics.Shader.TileMode.CLAMP));
                c.drawRoundRect(r.left + sw / 2, r.top + sw / 2, r.right - sw / 2, r.bottom - sw / 2, rad - sw / 2, rad - sw / 2, rim);
                // aba selecionada: bolha de vidro (lupa ao deslizar), por cima da pílula
                capsule(c, r, dark, m);
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
