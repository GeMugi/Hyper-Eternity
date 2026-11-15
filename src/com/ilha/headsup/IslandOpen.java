package com.ilha.headsup;

import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.ref.WeakReference;

/**
 * Abrir e fechar a ilha de notificações do bloqueio como no HyperOS 4 (teste).
 *
 * A ilha (do HyperMusicCover) guarda as notificações deixando o transitionAlpha delas em 0 e as
 * devolve animando esse valor, cartão por cartão. No HyperOS 4 a lista inteira é que se mexe:
 * ao abrir, ela nasce pequena e desfocada perto da ilha (embaixo) e cresce até o lugar, ficando
 * nítida; ao fechar, encolhe e desfoca de volta para a ilha. Aqui a lista inteira (o recipiente
 * das notificações) faz esse movimento por cima da animação da ilha.
 */
final class IslandOpen {
    private IslandOpen() {}

    static final int CLOSED = 0, OPENING = 1, OPEN = 2, CLOSING = 3;
    static int state = CLOSED;
    static WeakReference<View> listRef;
    static long start;
    static float from;   // progresso (0 = recolhida na ilha, 1 = aberta) no início do movimento
    static float prog = 1f;
    static int logs;
    static boolean installed;

    static final float SCALE_MIN = 0.5f, BLUR_DP = 22f;

    static void log(String s) {
        ElasticCard.log("ilha (abrir/fechar): " + s);
    }

    /** O recipiente da lista de notificações (para o movimento). */
    static void setList(View nssl) {
        View old = listRef == null ? null : listRef.get();
        if (old != nssl) listRef = new WeakReference<View>(nssl);
    }

    static void install() {
        if (installed || !Keys.TEST) return;   // ainda em experiência: só na versão de teste
        installed = true;
        try {
            // a ilha devolve/guarda as notificações mexendo no transitionAlpha delas
            XposedBridge.hookMethod(View.class.getDeclaredMethod("setTransitionAlpha", float.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!Prefs.kgIslandAnim || !StackHooks.onKeyguard || StackHooks.rowClass == null) return;
                    View v = (View) p.thisObject;
                    if (!StackHooks.rowClass.isInstance(v)) return;
                    float was = v.getTransitionAlpha(), now = ((Float) p.args[0]).floatValue();
                    // a ilha devolvendo: vale se a lista estava toda guardada agora há pouco
                    if (now > was + 0.001f && was < 0.05f && state != OPENING
                            && (state == CLOSED || state == CLOSING || SystemClock.uptimeMillis() - lastAllHidden < 700L)) open();
                    else if (now < was - 0.001f && was > 0.95f && state == OPEN) close();
                }
            });
            log("pronta");
        } catch (Throwable t) {
            log("indisponível: " + t);
        }
    }

    static long lastOpenEnd, lastAllHidden;

    static void open() {
        View l = list();
        if (l == null) return;
        from = state == CLOSING ? prog : 0f;
        state = OPENING;
        start = SystemClock.uptimeMillis();
        if (logs++ < 40) log("abrindo (de " + from + ")");
        dump(l, "abrindo");
        frame();
    }

    static int dumps, morphLogs;
    static final java.util.WeakHashMap<View, Boolean> morphs = new java.util.WeakHashMap<View, Boolean>();

    /**
     * A ilha do HyperMusicCover, ao abrir, estica a própria pílula até virar um cartão (sem
     * conteúdo e borrado) que sobe e some. Com o nosso movimento, esse cartão sobra: enquanto a
     * lista abre, ele fica escondido. A pílula que fica no lugar continua à mostra.
     */
    static void hideMorph(View l, boolean on) {
        try {
            if (!on || l == null) {
                for (View v : morphs.keySet()) v.setTransitionAlpha(1f);
                morphs.clear();
                return;
            }
            float d = l.getResources().getDisplayMetrics().density;
            int sh = l.getResources().getDisplayMetrics().heightPixels;
            find(l.getRootView(), l, d, sh, 0);
        } catch (Throwable t) {
            if (morphLogs++ < 4) log("pílula: " + t);
        }
    }

    static void find(View v, View list, float d, int sh, int depth) {
        if (v == list || depth > 14 || v.getVisibility() != View.VISIBLE) return;
        // as vistas do HMC têm nomes curtos e sem pacote (código ofuscado)
        String cn = v.getClass().getName();
        if (cn.indexOf('.') < 0 && v.getParent() instanceof View && v.getParent().getClass().getName().indexOf('.') < 0) {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            float h = v.getHeight() * v.getScaleY();
            // esticada (mais alta que a pílula) ou já saindo do lugar dela
            if (h > 40 * d && (h > 54 * d || loc[1] < sh - 96 * d - h)) {
                if (!morphs.containsKey(v)) {
                    morphs.put(v, Boolean.TRUE);
                    if (morphLogs++ < 6) log("pílula esticando escondida: " + cn + " " + v.getWidth() + "x" + v.getHeight() + " em " + loc[1]);
                }
                v.setTransitionAlpha(0f);
            }
            return;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) find(g.getChildAt(i), list, d, sh, depth + 1);
        }
    }

    /** Diagnóstico: o que mais está desenhado perto da ilha durante o movimento (o cartão escuro). */
    static void dump(final View l, final String why) {
        if (dumps >= 4) return;
        dumps++;
        final long t0 = SystemClock.uptimeMillis();
        for (int k = 0; k < 3; k++) {
            l.postDelayed(new Runnable() {
                public void run() {
                    try {
                        StringBuilder b = new StringBuilder("vistas (" + why + " +" + (SystemClock.uptimeMillis() - t0) + " ms):");
                        int[] n = {0};
                        walk(l.getRootView(), l, b, n, 0);
                        log(b.toString());
                    } catch (Throwable t) {
                        log("vistas: " + t);
                    }
                }
            }, 60L + 110L * k);
        }
    }

    static void walk(View v, View list, StringBuilder b, int[] n, int depth) {
        if (v == list || v.getVisibility() != View.VISIBLE || n[0] > 28 || depth > 14) return;
        float d = v.getResources().getDisplayMetrics().density;
        int sw = v.getResources().getDisplayMetrics().widthPixels, sh = v.getResources().getDisplayMetrics().heightPixels;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float w = v.getWidth() * v.getScaleX(), h = v.getHeight() * v.getScaleY();
        boolean leaf = !(v instanceof android.view.ViewGroup) || ((android.view.ViewGroup) v).getChildCount() == 0;
        if (h > 24 * d && h < sh * 0.6f && w > sw * 0.3f && loc[1] > sh * 0.35f && (leaf || v.getBackground() != null)) {
            n[0]++;
            b.append("\n  ").append(v.getClass().getName()).append(" id=").append(v.getId() == View.NO_ID ? "-" : idName(v))
                    .append(' ').append(Math.round(w)).append('x').append(Math.round(h)).append(" em ").append(loc[0]).append(',').append(loc[1])
                    .append(" alfa ").append(v.getAlpha()).append('/').append(v.getTransitionAlpha())
                    .append(" fundo=").append(v.getBackground() == null ? "-" : v.getBackground().getClass().getSimpleName())
                    .append(" pai=").append(v.getParent() == null ? "-" : v.getParent().getClass().getSimpleName());
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), list, b, n, depth + 1);
        }
    }

    static String idName(View v) {
        try { return v.getResources().getResourceEntryName(v.getId()); } catch (Throwable t) { return "0x" + Integer.toHexString(v.getId()); }
    }

    static void close() {
        View l = list();
        if (l == null) return;
        from = prog;
        state = CLOSING;
        start = SystemClock.uptimeMillis();
        if (logs++ < 40) log("fechando (de " + from + ")");
        dump(l, "fechando");
        frame();
    }

    static View list() {
        return listRef == null ? null : listRef.get();
    }

    static final Choreographer.FrameCallback TICK = new Choreographer.FrameCallback() {
        public void doFrame(long ns) { frame(); }
    };

    static void frame() {
        View l = list();
        if (l == null || !StackHooks.onKeyguard) { reset(); return; }
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        boolean done;
        if (state == OPENING) {
            // mola com um leve passar do ponto, como a do HyperOS 4
            float s = IslandSpring.step(t, 0.78f, 19f);
            prog = from + (1f - from) * s;
            done = t > 0.65f;
        } else if (state == CLOSING) {
            float s = IslandSpring.smooth(Math.min(1f, t / 0.26f));
            prog = from * (1f - s);
            done = t >= 0.26f;
        } else return;
        draw(l, prog);
        if (state == OPENING && !done) hideMorph(l, true);
        if (state == OPENING && done) {
            l.postDelayed(new Runnable() { public void run() { hideMorph(list() == null ? null : list(), false); } }, 150L);
        }
        if (done) {
            if (state == OPENING) {
                state = OPEN;
                lastOpenEnd = SystemClock.uptimeMillis();
                reset();
            } else {
                state = CLOSED;
                // fica recolhida até a ilha terminar de apagar os cartões; depois volta ao normal
                l.postDelayed(new Runnable() {
                    public void run() { if (state == CLOSED) reset(); }
                }, 260L);
            }
            return;
        }
        Choreographer.getInstance().postFrameCallback(TICK);
    }

    /** p: 0 = pequena e desfocada junto da ilha, 1 = lista no lugar. */
    static void draw(View l, float p) {
        float d = l.getResources().getDisplayMetrics().density;
        float sc = SCALE_MIN + (1f - SCALE_MIN) * p;
        // a ilha fica embaixo, no meio, logo acima da lanterna e da câmera
        int[] loc = new int[2];
        l.getLocationOnScreen(loc);
        float pillY = l.getResources().getDisplayMetrics().heightPixels - 118f * d - loc[1];
        l.setPivotX(l.getWidth() / 2f);
        l.setPivotY(Math.max(0f, Math.min(l.getHeight() * 1.2f, pillY)));
        l.setScaleX(sc);
        l.setScaleY(sc);
        l.setTransitionAlpha(IslandSpring.clamp(p * 1.8f, 0f, 1f));
        float r = BLUR_DP * d * IslandSpring.clamp(1f - p * 1.25f, 0f, 1f);
        try {
            l.setRenderEffect(r > 0.5f ? RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL) : null);
        } catch (Throwable ignored) {
        }
    }

    static void reset() {
        View l = list();
        if (l == null) return;
        if (l.getScaleX() != 1f || l.getScaleY() != 1f) {
            l.setScaleX(1f);
            l.setScaleY(1f);
        }
        if (l.getTransitionAlpha() != 1f) l.setTransitionAlpha(1f);
        try { l.setRenderEffect(null); } catch (Throwable ignored) {}
        prog = 1f;
    }

    /** A cada passada da lista no bloqueio: lista à mostra sem movimento = aberta; toda na ilha = recolhida. */
    static void sync(View nssl, int shown, int ghost, int full) {
        setList(nssl);
        if (state == OPENING || state == CLOSING) return;
        // só muda com a lista inteira num estado só (no meio da animação da ilha há dos dois)
        if (shown == 0 && ghost > 0) {
            lastAllHidden = SystemClock.uptimeMillis();
            state = CLOSED;
        } else if (ghost == 0 && shown > 0 && full == shown) state = OPEN;   // apagando aos poucos ainda não é "aberta"
    }

    /** Saiu do bloqueio ou a opção foi desligada: tudo volta ao normal. */
    static void off() {
        if (state != OPEN && state != CLOSED || prog != 1f) {
            state = CLOSED;
            reset();
        }
    }
}
