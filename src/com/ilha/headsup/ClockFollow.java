package com.ilha.headsup;

import android.graphics.Matrix;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.TextView;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Relógio da tela de bloqueio acompanha a rolagem das notificações: sobe junto com a lista e
 * diminui um pouco enquanto se afasta. Usa a matriz de animação da view (setAnimationMatrix), que
 * o HyperOS não usa no relógio, então não briga com as animações dele (posição, alpha, Folme).
 *
 * Também mantém o relógio na posição inicial (a mesma de quando não há notificações): o HyperOS
 * encolhe e sobe o relógio quando chega notificação, via AnimationHelper.doAnimationToNotification.
 * Aqui esse "tem notificação" vira "não tem" para o relógio (fora do AOD), e nada mais muda.
 */
final class ClockFollow {
    private ClockFollow() {}

    static final Map<View, Boolean> clocks = new WeakHashMap<View, Boolean>();
    static float offset;          // px que o relógio sobe
    static float screenH = 1f;
    static final Map<View, Boolean> extras = new WeakHashMap<View, Boolean>();
    static boolean logged, loggedFix, loggedLayers;
    static Field fSecondary, fMagazine;
    static Boolean lastFixed;
    static Field fToAod, fHelper, fHasNotif, fHelperHas, fAnima, fAnimaHas;
    static Method mToNotification, mAnimaToNotification;
    static boolean loggedHeal, loggedGap;
    static float restBottom = Float.NaN;   // base visível do relógio na tela (px)
    static long measuredAt;
    static final int[] tmp = new int[2];

    static void install(ClassLoader cl) {
        try {
            Class c = Class.forName("com.android.keyguard.clock.KeyguardClockContainer", false, cl);
            try {
                fSecondary = c.getDeclaredField("mKeyguardSecondaryClockContainer");
                fSecondary.setAccessible(true);
            } catch (Throwable t) {
                ElasticCard.log("relógio: camada secundária não encontrada: " + t);
            }
            try {
                fMagazine = c.getDeclaredField("mLockScreenMagazineClockView");
                fMagazine.setAccessible(true);
            } catch (Throwable t) {
                ElasticCard.log("relógio: texto da revista não encontrado: " + t);
            }
            XposedBridge.hookMethod(c.getDeclaredMethod("onAttachedToWindow"), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    View v = (View) p.thisObject;
                    clocks.put(v, Boolean.TRUE);
                    applyTo(v);
                }
            });
            XposedBridge.hookMethod(c.getDeclaredMethod("onDetachedFromWindow"), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    View v = (View) p.thisObject;
                    clocks.remove(v);
                    ArrayList<View> layers = layersOf(v);
                    for (int i = 0; i < layers.size(); i++) layers.get(i).setAnimationMatrix(null);
                    clearExtras();
                }
            });
            ElasticCard.log("relógio do bloqueio: acompanhamento instalado");
            installFixed(cl, c);
        } catch (Throwable t) {
            ElasticCard.log("relógio do bloqueio indisponível: " + t);
            Caps.fail(Keys.KG_CLOCK, t);
        }
    }

    /** Relógio na posição inicial: o relógio sempre se comporta como se não houvesse notificações. */
    static void installFixed(ClassLoader cl, Class container) {
        try {
            Class ah = Class.forName("com.android.keyguard.clock.animation.AnimationHelper", false, cl);
            fToAod = ah.getDeclaredField("sToAod");
            fToAod.setAccessible(true);
            fHelper = container.getDeclaredField("mAnimationHelper");
            fHelper.setAccessible(true);
            fHasNotif = container.getDeclaredField("mHasNotification");
            fHasNotif.setAccessible(true);
            mToNotification = ah.getDeclaredMethod("doAnimationToNotification", boolean.class, boolean.class);
            XposedBridge.hookMethod(mToNotification, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (Boolean.TRUE.equals(p.args[0]) && fixed() && !toAod()) {
                        p.args[0] = Boolean.FALSE;
                        if (!loggedFix) {
                            loggedFix = true;
                            ElasticCard.log("relógio mantido na posição inicial (há notificações)");
                        }
                    }
                }
            });
            // ao sair do AOD o HyperOS reaplica o estado do relógio com hasNotification
            XposedBridge.hookMethod(ah.getDeclaredMethod("doAnimationToAod", boolean.class, boolean.class, boolean.class),
                    new XC_MethodHook() {
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (Boolean.FALSE.equals(p.args[0]) && Boolean.TRUE.equals(p.args[1]) && fixed()) p.args[1] = Boolean.FALSE;
                        }
                    });
            // ao trocar o estilo do relógio, o novo relógio lê o estado daqui
            XposedBridge.hookMethod(container.getDeclaredMethod("getMHasNotification"), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if (Boolean.TRUE.equals(p.getResult()) && fixed() && !toAod()) p.setResult(Boolean.FALSE);
                }
            });
            fHelperHas = ah.getDeclaredField("mHasNotification");
            fHelperHas.setAccessible(true);
            fAnima = ah.getDeclaredField("mClockAnima");
            fAnima.setAccessible(true);
            Class cba = Class.forName("com.android.keyguard.clock.animation.ClockBaseAnimation", false, cl);
            fAnimaHas = cba.getDeclaredField("mHasNotification");
            fAnimaHas.setAccessible(true);
            mAnimaToNotification = cba.getDeclaredMethod("doAnimationToNotification", boolean.class, boolean.class);
            // ao acordar (bloquear e ligar a tela de novo) o HyperOS reposiciona o relógio com
            // resetAnimationState(mHasNotification), lendo o valor direto do campo do contêiner
            XposedBridge.hookMethod(cba.getDeclaredMethod("resetAnimationState", boolean.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!fixed() || toAod()) return;
                    p.args[0] = Boolean.FALSE;
                    clearFlags(p.thisObject);
                }
            });
            // mudança de configuração (tema, fonte, rotação) também lê o campo direto
            XC_MethodHook cfg = new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (Boolean.TRUE.equals(p.args[1]) && fixed() && !toAod()) p.args[1] = Boolean.FALSE;
                }
            };
            XposedBridge.hookMethod(cba.getDeclaredMethod("updateTimeViewOnConfigChange", int.class, boolean.class), cfg);
            try {
                Class os = Class.forName("com.android.keyguard.clock.animation.oversize.OverSizeAnimation", false, cl);
                XposedBridge.hookMethod(os.getDeclaredMethod("updateTimeViewOnConfigChange", int.class, boolean.class), cfg);
            } catch (Throwable ignored) {
            }
            ElasticCard.log("relógio do bloqueio: posição inicial fixa instalada");
        } catch (Throwable t) {
            ElasticCard.log("relógio fixo indisponível: " + t);
            Caps.fail(Keys.KG_CLOCK_FIXED, t);
        }
    }

    /** Player da música aberto no bloqueio (a capa do HyperMusicCover está na tela). */
    static volatile boolean coverUp;
    static int coverLogs;

    static boolean fixed() {
        Prefs.refresh();
        return Prefs.kgClockFixed && !(Prefs.kgHmc && coverUp);
    }

    /**
     * Com o bloqueio entregue ao HyperMusicCover, o relógio é dele enquanto o player (a capa) está
     * aberto: o relógio fixo solta e o relógio volta ao estado real do HyperOS, sobre o qual o
     * HyperMusicCover aplica o tamanho dele. Fechado o player, o relógio volta à posição inicial
     * (heal(), na mesma passada).
     */
    static void setCover(boolean up) {
        if (up == coverUp) return;
        coverUp = up;
        Prefs.refresh();
        if (!up || !Prefs.kgHmc || !Prefs.kgClockFixed || mToNotification == null || toAod()) return;
        ArrayList<View> list = new ArrayList<View>(clocks.keySet());
        for (int i = 0; i < list.size(); i++) {
            try {
                Object h = fHelper.get(list.get(i));
                if (h == null) continue;
                boolean real = fHasNotif.getBoolean(list.get(i));
                if (real) mToNotification.invoke(h, Boolean.TRUE, Boolean.TRUE);
            } catch (Throwable ignored) {
            }
        }
        if (coverLogs++ < 6) ElasticCard.log("player aberto: relógio entregue ao HyperMusicCover");
    }

    static boolean toAod() {
        try {
            return fToAod != null && fToAod.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Zera o "tem notificação" guardado no relógio e no ajudante dele (fora do AOD). */
    static void clearFlags(Object anima) {
        try {
            fAnimaHas.setBoolean(anima, false);
            ArrayList<View> list = new ArrayList<View>(clocks.keySet());
            for (int i = 0; i < list.size(); i++) {
                Object h = fHelper.get(list.get(i));
                if (h != null && fAnima.get(h) == anima) fHelperHas.setBoolean(h, false);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Rede de segurança no bloqueio: se algo deixou o relógio no modo "com notificação"
     *  (ex.: notificação que chegou com a tela apagada), volta para a posição inicial. */
    static void heal() {
        if (mToNotification == null || clocks.isEmpty() || !fixed() || toAod()) return;
        ArrayList<View> list = new ArrayList<View>(clocks.keySet());
        for (int i = 0; i < list.size(); i++) {
            try {
                Object h = fHelper.get(list.get(i));
                if (h == null) continue;
                Object a = fAnima.get(h);
                boolean hh = fHelperHas.getBoolean(h);
                boolean ah = a != null && fAnimaHas.getBoolean(a);
                if (!hh && !ah) continue;
                if (hh) mToNotification.invoke(h, Boolean.FALSE, Boolean.TRUE);
                else mAnimaToNotification.invoke(a, Boolean.FALSE, Boolean.TRUE);
                if (!loggedHeal) {
                    loggedHeal = true;
                    ElasticCard.log("relógio voltou para a posição inicial depois de acordar");
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** Quando o ajuste muda com o bloqueio aberto, refaz o estado do relógio na hora. */
    static void sync() {
        boolean f = Prefs.kgClockFixed;
        if (lastFixed == null) {
            lastFixed = Boolean.valueOf(f);
            return;
        }
        if (lastFixed.booleanValue() == f || mToNotification == null || toAod()) return;
        lastFixed = Boolean.valueOf(f);
        ArrayList<View> list = new ArrayList<View>(clocks.keySet());
        for (int i = 0; i < list.size(); i++) {
            try {
                Object h = fHelper.get(list.get(i));
                if (h == null) continue;
                boolean real = fHasNotif.getBoolean(list.get(i));
                mToNotification.invoke(h, Boolean.valueOf(real), Boolean.TRUE);
            } catch (Throwable ignored) {
            }
        }
        ElasticCard.log("relógio fixo " + (f ? "ligado" : "desligado") + ": estado reaplicado");
    }

    /**
     * Chamado a cada passada da lista do bloqueio. As notificações sobem livres até chegar perto do
     * relógio; a partir daí, o que a lista subir a mais empurra o relógio junto.
     * cardTop = topo do primeiro cartão na tela (px), scrollY = quanto a lista está rolada.
     */
    static void follow(float cardTop, int scrollY, float h, float density) {
        if (h > 0f) screenH = h;
        float px = 0f;
        if (scrollY > 0) {
            long now = SystemClock.uptimeMillis();
            if (Float.isNaN(restBottom) || now - measuredAt > 400L) {
                measuredAt = now;
                float b = measureBottom();
                if (!Float.isNaN(b)) restBottom = b;
            }
            if (Float.isNaN(restBottom) || Float.isNaN(cardTop)) {
                px = scrollY;
            } else {
                float gap = 14f * density;   // distância que sobra entre o relógio e o primeiro cartão
                px = Math.min(scrollY, Math.max(0f, restBottom + gap - cardTop));
                if (!loggedGap) {
                    loggedGap = true;
                    ElasticCard.log("relógio: base em " + (int) restBottom + "px, notificações encostam antes de empurrar");
                }
            }
        } else {
            measuredAt = 0L;   // parado: mede de novo na próxima rolagem
        }
        update(px, h);
    }

    /** Base do conteúdo visível do relógio (todas as camadas), em px na tela. NaN se não achar. */
    static float measureBottom() {
        float bottom = Float.NaN;
        ArrayList<View> list = new ArrayList<View>(clocks.keySet());
        for (int i = 0; i < list.size(); i++) {
            View c = list.get(i);
            if (!c.isShown()) continue;
            ArrayList<View> layers = layersOf(c);
            for (int j = 0; j < layers.size(); j++) {
                View l = layers.get(j);
                if (j > 0 && !l.isShown()) continue;
                float b = scan(l, 0);
                if (!Float.isNaN(b) && (Float.isNaN(bottom) || b > bottom)) bottom = b;
            }
        }
        return bottom;
    }

    static float scan(View v, int depth) {
        if (depth > 24 || v.getVisibility() != View.VISIBLE || v.getAlpha() < 0.02f
                || v.getTransitionAlpha() < 0.02f || v.getWidth() <= 0 || v.getHeight() <= 0) return Float.NaN;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            float best = Float.NaN;
            for (int i = 0; i < g.getChildCount(); i++) {
                float b = scan(g.getChildAt(i), depth + 1);
                if (!Float.isNaN(b) && (Float.isNaN(best) || b > best)) best = b;
            }
            return best;
        }
        if (v instanceof TextView && ((TextView) v).length() == 0) return Float.NaN;
        if (v instanceof ImageView && ((ImageView) v).getDrawable() == null) return Float.NaN;
        float hh = v.getHeight() * Math.abs(v.getScaleY());
        if (hh > screenH * 0.5f) return Float.NaN;   // fundo/camada inteira, não é conteúdo do relógio
        v.getLocationOnScreen(tmp);
        if (tmp[1] > screenH * 0.75f) return Float.NaN;
        return tmp[1] + hh;
    }

    /** Aplica o deslocamento do relógio (px que ele sobe). */
    static void update(float px, float h) {
        if (px < 0f) px = 0f;
        if (h > 0f) screenH = h;
        if (Math.abs(px - offset) < 0.5f) return;
        offset = px;
        if (clocks.isEmpty()) return;
        ArrayList<View> list = new ArrayList<View>(clocks.keySet());
        for (int i = 0; i < list.size(); i++) applyTo(list.get(i));
        if (!logged && px > 0f) {
            logged = true;
            ElasticCard.log("relógio acompanhando a rolagem (" + list.size() + " relógio)");
        }
    }

    /** Camadas do relógio: o contêiner e as partes que o HyperOS põe em outras camadas
     *  (relógio secundário dos estilos em camadas e o texto da revista/papel de parede). */
    static ArrayList<View> layersOf(View c) {
        ArrayList<View> out = new ArrayList<View>(3);
        out.add(c);
        try {
            View sec = (View) fSecondary.get(c);
            if (sec != null && sec != c) out.add(sec);
        } catch (Throwable ignored) {
        }
        try {
            View mag = (View) fMagazine.get(c);
            if (mag != null && mag.getParent() != null && mag.getParent() != c) out.add(mag);
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Posição na tela do canto (left, top) da view, antes das transformações dela mesma. */
    static void origin(View v, int[] out) {
        out[0] = 0;
        out[1] = 0;
        ViewParent p = v.getParent();
        if (p instanceof View) {
            View pv = (View) p;
            pv.getLocationOnScreen(out);
            out[0] -= pv.getScrollX();
            out[1] -= pv.getScrollY();
        }
        out[0] += v.getLeft();
        out[1] += v.getTop();
    }

    static void applyTo(View c) {
        try {
            ArrayList<View> layers = layersOf(c);
            if (offset < 0.5f) {
                for (int i = 0; i < layers.size(); i++) layers.get(i).setAnimationMatrix(null);
                clearExtras();
                return;
            }
            // recua: sobe junto com a lista e encolhe até 92% ao longo de meia tela. Todas as camadas
            // usam o mesmo ponto de apoio na tela (topo central do relógio), então andam juntas.
            float t = Math.min(1f, offset / (screenH * 0.5f));
            float s = 1f - 0.08f * t;
            int[] o = new int[2];
            origin(c, o);
            float px = o[0] + c.getWidth() / 2f, py = o[1];
            for (int i = 0; i < layers.size(); i++) {
                View v = layers.get(i);
                origin(v, o);
                Matrix m = new Matrix();
                m.setScale(s, s, px - o[0], py - o[1]);
                m.postTranslate(0f, -offset);
                v.setAnimationMatrix(m);
                if (v != c) extras.put(v, Boolean.TRUE);
            }
            if (!loggedLayers && layers.size() > 1) {
                loggedLayers = true;
                ElasticCard.log("relógio em " + layers.size() + " camadas: todas acompanham a rolagem");
            }
        } catch (Throwable ignored) {
        }
    }

    static void clearExtras() {
        if (extras.isEmpty()) return;
        ArrayList<View> list = new ArrayList<View>(extras.keySet());
        extras.clear();
        for (int i = 0; i < list.size(); i++) {
            try {
                list.get(i).setAnimationMatrix(null);
            } catch (Throwable ignored) {
            }
        }
    }
}
