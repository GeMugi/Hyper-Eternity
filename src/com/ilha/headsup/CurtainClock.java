package com.ilha.headsup;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;

/**
 * Relógio do bloqueio na cortina, estático: uma cópia feita pela biblioteca de relógios do HyperOS
 * com o modelo atual. Nada de animação. A parte visível é medida pelos pixels desenhados, então a
 * lista e o desfoque do cabeçalho se ajustam a qualquer modelo.
 */
final class CurtainClock {
    private CurtainClock() {}

    static FrameLayout box;
    static Object main, second;
    static boolean dirty = true, broken;
    static float off = -1;          // deslocamento da lista/cabeçalho (px) com o relógio ativo; -1 = sem relógio
    static int visTop = -1, visBot = -1, visLeft = -1;
    static long measuredAt;
    static int logs;

    static String model = "";
    static String nTid;                  // forma reduzida que o bloqueio usa com notificações (eastern A/C)
    static FrameLayout boxN;
    static Object mainN, secondN;
    static int nTop = -1, nBot = -1;

    static float e, r;   // quanto o cabeçalho pode subir / quanto subiu (px)

    /**
     * Ajustes por modelo (cada um tratado separadamente; modelo sem ficha = posição do bloqueio, parado).
     *  classic_max / classic_plus: começa na posição do bloqueio; ao rolar as notificações, relógio,
     *  "Notificações", o X e a lista sobem juntos até o relógio chegar ao topo.
     */
    static boolean follows() {
        return "classic_max".equals(model) || "classic_plus".equals(model) || "oversize_b".equals(model) || magazine();
    }

    /** magazine (texto grande alinhado à esquerda): encolhe ao subir, preso pela borda esquerda. */
    static boolean magazine() {
        return model.startsWith("magazine") && !"magazine_c".equals(model);
    }

    /** Tamanho do relógio quando chega ao topo (1 = não muda). oversize_b: versão reduzida. */
    static float shrink() {
        if (magazine()) return 0.45f;
        return "oversize_b".equals(model) ? 0.42f : 1f;
    }

    /** Até onde a medida aceita o fim do relógio (fração da tela). oversize_b tem a data bem embaixo. */
    static float cap() {
        return "oversize_b".equals(model) || model.startsWith("eastern") ? 0.8f : 0.62f;
    }

    /** Onde fica a lista: já na posição final (relógio no topo); o começo dela é empurrado pelo e. */
    static float listOffset(float d) {
        return off >= 0 ? off - e : Prefs.sheetClock * d;
    }

    static void install(ClassLoader cl) {
        try {
            Class kc = Class.forName("com.android.keyguard.clock.KeyguardClockContainer", false, cl);
            XposedBridge.hookMethod(kc.getDeclaredMethod("addClockView"), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    dirty = true;   // modelo trocado no bloqueio
                }
            });
        } catch (Throwable t) {
            log("troca de modelo não monitorada: " + t);
        }
    }

    /** Deslocamento usado pela lista e pelo degradê: o do relógio quando ativo, senão o do ajuste. */
    static float offset(float d) {
        return off >= 0 ? off - r : Prefs.sheetClock * d;
    }

    /**
     * Chamado no desenho da cortina. top/bot: faixa do cabeçalho original (sem deslocamento).
     * Retorna o deslocamento a usar, ou -1 quando o relógio do bloqueio não está em uso.
     */
    static float place(View deco, float top, float bot, float d) {
        if (!Prefs.sheetLockClock || broken || !(deco.getParent() instanceof ViewGroup)) {
            remove();
            return -1;
        }
        try {
            ViewGroup root = (ViewGroup) deco.getParent();
            if (box == null) {
                box = new FrameLayout(root.getContext()) {
                    public boolean dispatchTouchEvent(android.view.MotionEvent e) { return false; }   // só imagem
                };
            }
            if (box.getParent() != root) {
                if (box.getParent() != null) ((ViewGroup) box.getParent()).removeView(box);
                // logo abaixo do desenho da cortina: fica por cima do desfoque do cabeçalho
                root.addView(box, Math.max(0, root.indexOfChild(deco)), new ViewGroup.LayoutParams(-1, -1));
                dirty = true;
            }
            if (boxN == null) {
                boxN = new FrameLayout(root.getContext()) {
                    public boolean dispatchTouchEvent(android.view.MotionEvent e) { return false; }
                };
            }
            if (boxN.getParent() != root) {
                if (boxN.getParent() != null) ((ViewGroup) boxN.getParent()).removeView(boxN);
                root.addView(boxN, Math.max(0, root.indexOfChild(deco)), new ViewGroup.LayoutParams(-1, -1));
                dirty = true;
            }
            if (dirty) build(root);
            if (nTid == null) boxN.setAlpha(0f);
            if (box.getWidth() == 0 || box.getHeight() == 0) { deco.postInvalidateDelayed(32); return off; }
            long now = android.os.SystemClock.uptimeMillis();
            if (visBot < 0 || now - measuredAt > 20000) {
                try {
                    measure();
                } catch (Throwable t) {   // algum efeito só desenha em hardware: usa uma faixa segura
                    if (logs++ < 10) log("medida por pixels falhou: " + t);
                    visTop = 0;
                    visBot = Math.round(box.getHeight() * 0.35f);
                }
                if (nTid != null && boxN.getWidth() > 0) {
                    FrameLayout keep = box;
                    int vt = visTop, vb = visBot;
                    box = boxN;
                    visTop = visBot = -1;
                    try { measure(); } catch (Throwable ignored) {}
                    nTop = visTop;
                    nBot = visBot;
                    box = keep;
                    visTop = vt;
                    visBot = vb;
                    log("reduzido " + nTid + " desenhado de " + nTop + " a " + nBot);
                }
                measuredAt = now;
                if (visBot < 0) { deco.postInvalidateDelayed(100); return off; }
            }
            // posição da tela de bloqueio (sem deslocamento); a lista começa logo abaixo da parte desenhada
            int[] dl = new int[2], bl = new int[2];
            deco.getLocationInWindow(dl);
            box.getLocationInWindow(bl);
            // sem a transformação da própria caixa (escala e deslocamento mudam a posição medida na tela)
            float baseY = (box.getTop() - deco.getTop()) - (deco.getTranslationY());
            float clockBottom = visBot + baseY;
            off = Math.max(0f, clockBottom + 10 * d - bot);
            // modelos que sobem ao rolar: quanto o relógio pode subir até o topo (abaixo da barra de status)
            if (nTid != null && nBot > 0) {
                // como o bloqueio: o modelo grande sobe e some, e a forma reduzida de verdade aparece chegando
                // na posição dela; o fim visível fica colado no título, que sobe junto com a lista
                float e2 = Math.max(0f, Math.min(off, clockBottom - (nBot + baseY)));
                if (Math.abs(e2 - e) >= 1f) { e = Math.round(e2); ShadeSheet.requestStack(); }
                r = Math.min(e, ShadeSheet.scrollY());
                float p = e > 0 ? r / e : 0f;
                box.setScaleX(1f);
                box.setScaleY(1f);
                box.setTranslationY(-r);
                box.setAlpha(Math.max(0f, 1f - p * 1.6f));
                boxN.setTranslationY(e - r);
                boxN.setAlpha(Math.max(0f, Math.min(1f, (p - 0.3f) / 0.7f)));
                return off - r;
            }
            box.setAlpha(1f);
            if ("magazine_c".equals(model)) {
                float rr = magazineC(clockBottom, baseY);
                if (rr >= 0) return off - rr;
            }
            float e2 = 0f;
            float H = visBot - visTop, S = shrink();
            if (follows()) {
                int sb = 0;
                int id = root.getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id != 0) sb = root.getResources().getDimensionPixelSize(id);
                // no topo o relógio fica com S do tamanho; o fim dele (e o título) sobe exatamente o quanto a lista sobe
                e2 = Math.max(0f, Math.min(off, clockBottom - H * S - (sb + 8 * d)));
            }
            if (Math.abs(e2 - e) >= 1f) { e = Math.round(e2); ShadeSheet.requestStack(); }
            r = Math.min(e, ShadeSheet.scrollY());
            float p = e > 0 ? r / e : 0f;
            float sc = 1f - (1f - S) * p;
            box.setPivotX(magazine() && visLeft >= 0 ? visLeft : box.getWidth() / 2f);
            box.setPivotY(visTop);
            box.setScaleX(sc);
            box.setScaleY(sc);
            // topo do relógio = fim (que sobe r) menos a altura já reduzida
            float topNow = clockBottom - r - H * sc;
            box.setTranslationY(topNow - (visTop + baseY));
            return off - r;
        } catch (Throwable t) {
            broken = true;
            log("falhou: " + t);
            remove();
            return -1;
        }
    }

    static void build(ViewGroup root) throws Throwable {
        dirty = false;
        visTop = visBot = -1;
        release();
        box.removeAllViews();
        View kc = Kg.find(root, "KeyguardClockContainer", 0);
        ClassLoader cl = kc != null ? kc.getClass().getClassLoader() : root.getClass().getClassLoader();
        Class mc = Class.forName("com.miui.clock.MiuiClockController", false, cl);
        Class bc = Class.forName("com.miui.clock.module.ClockBean", false, cl);
        java.lang.reflect.Constructor<?> make = mc.getConstructor(android.content.Context.class, ViewGroup.class, bc, int.class);
        java.lang.reflect.Method add = mc.getMethod("addClockView", bc, boolean.class);
        main = make.newInstance(root.getContext(), box, null, Integer.valueOf(0));
        Object bean = Refl.get(main, "mInClockBean");
        if (bean != null) add.invoke(main, bean, Boolean.FALSE);
        mc.getMethod("onAttachedToWindow").invoke(main);
        // modelos com hora e minuto em camadas separadas
        try {
            Object cb = Refl.get(main, "mClockBean");
            String tid = (String) bc.getMethod("getTemplateId").invoke(cb);
            int style = ((Integer) bc.getMethod("getStyle").invoke(cb)).intValue();
            Class ku = Class.forName("com.miui.keyguard.utils.MiuiKeyguardUtils", false, cl);
            String t2 = (String) ku.getMethod("getSecondaryClockTemplateId", Integer.class, String.class).invoke(null, Integer.valueOf(style), tid);
            if (t2 != null && t2.length() > 0) {
                Object b2 = bc.getMethod("clone").invoke(cb);
                bc.getMethod("setTemplateId", String.class).invoke(b2, t2);
                second = make.newInstance(root.getContext(), box, b2, Integer.valueOf(0x40));
                add.invoke(second, Refl.get(second, "mInClockBean"), Boolean.FALSE);
                mc.getMethod("onAttachedToWindow").invoke(second);
            }
            model = tid;
            if ("magazine_c".equals(tid)) prepMagazineC();
            nTid = null;
            nTop = nBot = -1;
            if (boxN != null) boxN.removeAllViews();
            try {
                Object hc = ku.getField("HARD_CUT_TEMPLATE_ID").get(null);
                if (false && hc instanceof java.util.Map && !tid.endsWith("_notification")) nTid = (String) ((java.util.Map) hc).get(tid);   // eastern A/C: fora por enquanto
            } catch (Throwable ignored) {
            }
            if (nTid != null && boxN != null) {
                Object bn = bc.getMethod("clone").invoke(cb);
                bc.getMethod("setTemplateId", String.class).invoke(bn, nTid);
                mainN = make.newInstance(root.getContext(), boxN, bn, Integer.valueOf(0));
                add.invoke(mainN, Refl.get(mainN, "mInClockBean"), Boolean.FALSE);
                mc.getMethod("onAttachedToWindow").invoke(mainN);
                String n2 = (String) ku.getMethod("getSecondaryClockTemplateId", Integer.class, String.class).invoke(null, Integer.valueOf(style), nTid);
                if (n2 != null && n2.length() > 0) {
                    Object bn2 = bc.getMethod("clone").invoke(cb);
                    bc.getMethod("setTemplateId", String.class).invoke(bn2, n2);
                    secondN = make.newInstance(root.getContext(), boxN, bn2, Integer.valueOf(0x40));
                    add.invoke(secondN, Refl.get(secondN, "mInClockBean"), Boolean.FALSE);
                    mc.getMethod("onAttachedToWindow").invoke(secondN);
                }
                log("forma reduzida " + nTid + (n2 != null && n2.length() > 0 ? " + " + n2 : ""));
            }
            log("modelo " + tid + (t2 != null && t2.length() > 0 ? " + " + t2 : ""));
        } catch (Throwable t) {
            if (logs++ < 6) log("2ª camada: " + t);
        }
    }

    /** Faixa vertical realmente desenhada (pixels não transparentes), em 1/4 da resolução. */
    static void measure() {
        int w = box.getWidth(), h = box.getHeight();
        int sw = Math.max(1, w / 4), sh = Math.max(1, h / 4);
        Bitmap bm = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        try {
            Canvas c = new Canvas(bm);
            c.scale(sw / (float) w, sh / (float) h);
            box.draw(c);
            int[] row = new int[sw];
            int t = -1, b = -1, minX = -1;
            for (int y = 0; y < sh; y++) {
                bm.getPixels(row, 0, sw, 0, y, sw, 1);
                int n = 0;
                int fx = -1;
                for (int x = 0; x < sw; x++) if ((row[x] >>> 24) > 40) { n++; if (fx < 0) fx = x; }
                if (n > 0 && n < sw * 0.95f && fx >= 0 && (minX < 0 || fx < minX)) minX = fx;
                // linha com algo desenhado, mas não uma faixa cheia (fundo/efeito de tela inteira)
                if (n > 0 && n < sw * 0.95f) {
                    if (t < 0) t = y;
                    b = y;
                }
            }
            if (t < 0) {   // nada saiu no desenho por software (vidro/efeitos de hardware): usa as views
                int[] r = {Integer.MAX_VALUE, -1};
                leaves(box, 0, r, h);
                if (r[1] < 0) return;
                visTop = r[0];
                visBot = Math.min(r[1], Math.round(h * cap()));
                if (logs++ < 10) log("relógio (views) de " + visTop + " a " + visBot);
                return;
            }
            visLeft = minX < 0 ? -1 : minX * w / sw;
            visTop = t * h / sh;
            visBot = Math.min((b + 1) * h / sh, Math.round(h * cap()));   // nunca empurra a lista para o rodapé
            if (logs++ < 10) log("relógio desenhado de " + visTop + " a " + visBot + " (tela " + h + ")");
        } finally {
            bm.recycle();
        }
    }

    static void leaves(View v, int y0, int[] r, int h) {
        if (v.getVisibility() != View.VISIBLE) return;
        int y = y0 + (v == box ? 0 : v.getTop() + Math.round(v.getTranslationY()));
        if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) leaves(((ViewGroup) v).getChildAt(i), y, r, h);
            return;
        }
        if (v == box || v.getHeight() <= 0 || v.getWidth() <= 0 || v.getAlpha() <= 0.01f) return;
        if (v.getHeight() > h * 0.6f) return;   // camadas de tela inteira
        r[0] = Math.min(r[0], y);
        r[1] = Math.max(r[1], y + v.getHeight());
    }

    static Object vType(String n) throws Throwable {
        Class vt = Class.forName("com.miui.clock.module.ClockViewType", false, main.getClass().getClassLoader());
        return vt.getField(n).get(null);
    }

    static View iView(String n) {
        try {
            Object cv = Refl.get(main, "mClockView");
            return (View) cv.getClass().getMethod("getIClockView", vType(n).getClass()).invoke(cv, vType(n));
        } catch (Throwable t) {
            return null;
        }
    }

    static int yIn(View v) {
        int y = 0;
        View c = v;
        while (c != null && c != box) {
            y += c.getTop();
            c = c.getParent() instanceof View ? (View) c.getParent() : null;
        }
        return y;
    }

    static int mcLogs;

    /** Logo ao montar: a data pequena entra no layout (invisível) e o resto começa na forma grande. */
    static void prepMagazineC() {
        View nd = iView("NOTIFICATION_DATE");
        if (nd != null) {
            nd.setAlpha(0f);
            if (nd.getVisibility() != View.VISIBLE) nd.setVisibility(View.VISIBLE);
        }
        for (String n : new String[]{"FULL_DATE", "FULL_WEEK"}) {
            View v = iView(n);
            if (v != null) v.setAlpha(1f);
        }
    }

    /**
     * magazine_c, como o bloqueio faz com notificações: o relógio fica onde está, a data grande e o dia
     * da semana somem e aparece a data pequena (NOTIFICATION_DATE) logo abaixo da hora. Ao rolar,
     * "Notificações" e a lista sobem até ficar logo abaixo da data pequena. Retorna r, ou -1 sem as views.
     */
    static float magazineC(float clockBottom, float baseY) {
        View fd = iView("FULL_DATE"), fw = iView("FULL_WEEK"), nd = iView("NOTIFICATION_DATE");
        if (nd != null && nd.getVisibility() != View.VISIBLE) {   // fica oculta sem notificações: entra no layout invisível
            nd.setAlpha(0f);
            nd.setVisibility(View.VISIBLE);
            nd.requestLayout();
        }
        if (nd == null) {
            if (mcLogs++ < 3) log("magazine_c: data pequena não encontrada");
            return -1;
        }
        if (nd.getHeight() <= 0) {   // ainda sem layout: fica na forma grande, parado (sem piscar)
            box.setTranslationY(0f);
            box.setScaleX(1f);
            box.setScaleY(1f);
            r = 0;
            if (ShadeSheet.decoV != null) ShadeSheet.decoV.postInvalidateDelayed(32);
            return 0;
        }
        float ndBot = yIn(nd) + nd.getHeight() + baseY;
        float e2 = Math.max(0f, Math.min(off, clockBottom - ndBot));
        if (Math.abs(e2 - e) >= 1f) { e = Math.round(e2); ShadeSheet.requestStack(); if (mcLogs++ < 4) log("magazine_c: sobe " + e + "px até a data pequena"); }
        r = Math.min(e, ShadeSheet.scrollY());
        float p = e > 0 ? r / e : 0f;
        box.setScaleX(1f);
        box.setScaleY(1f);
        box.setTranslationY(0f);
        float hide = Math.max(0f, 1f - p * 1.8f);
        if (fd != null) fd.setAlpha(hide);
        if (fw != null) fw.setAlpha(hide);
        nd.setAlpha(Math.max(0f, Math.min(1f, (p - 0.35f) / 0.65f)));
        return r;
    }

    static void release() {
        for (Object c : new Object[]{main, second, mainN, secondN}) {
            if (c == null) continue;
            try {
                c.getClass().getMethod("removeClockView").invoke(c);
                c.getClass().getMethod("onDetachedFromWindow").invoke(c);
            } catch (Throwable ignored) {
            }
        }
        main = second = mainN = secondN = null;
    }

    static void remove() {
        off = -1;
        if (boxN != null && boxN.getParent() instanceof ViewGroup) ((ViewGroup) boxN.getParent()).removeView(boxN);
        if (e != 0f) { e = 0f; r = 0f; ShadeSheet.requestStack(); }
        if (box != null && box.getParent() instanceof ViewGroup) {
            release();
            ((ViewGroup) box.getParent()).removeView(box);
            dirty = true;
        }
    }

    static void log(String s) {
        ElasticCard.log("cortina relógio: " + s);
    }
}
