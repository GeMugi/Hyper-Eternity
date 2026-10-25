package com.ilha.headsup;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

public final class ElasticCard {
    static WeakHashMap states = new WeakHashMap();
    static WeakHashMap pending = new WeakHashMap();
    static boolean installed;
    static int logs;

    static void log(String msg) {
        try {
            if (logs++ < 80) {
                com.ilha.headsup.xp.XposedBridge.log("[HeadsUpIlha] " + msg);
                android.util.Log.i("HeadsUpIlha", msg);
            }
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void install(ClassLoader cl) {
        if (installed) {
            return;
        }
        installed = true;
        try {
            Class row = Class.forName("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", false, cl);
            Method pin = Refl.anyMethod(row, "setPinnedStatus");
            String which = "setPinnedStatus";
            if (pin == null) {
                pin = Refl.anyMethod(row, "setPinned");
                which = "setPinned";
            }
            if (pin != null) {
                com.ilha.headsup.xp.XposedBridge.hookMethod(pin, new PinHook());
                log("hook de fixação instalado em " + which);
            } else {
                log("ERRO: nem setPinnedStatus nem setPinned encontrados");
            }
            Method away = Refl.anyMethod(row, "setHeadsUpAnimatingAway");
            if (away != null) {
                com.ilha.headsup.xp.XposedBridge.hookMethod(away, new com.ilha.headsup.xp.XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            View v = (View) p.thisObject;
                            if (Boolean.TRUE.equals(p.args[0])) startExit(v);
                            else endExit(v);
                        } catch (Throwable t) {
                            log("ERRO na saída: " + t);
                        }
                    }
                });
                log("hook de saída instalado em setHeadsUpAnimatingAway");
            }
            Method draw = Refl.anyMethod(row, "dispatchDraw");
            if (draw != null) {
                com.ilha.headsup.xp.XposedBridge.hookMethod(draw, new DrawHook());
                log("hook de desenho instalado em " + draw.getDeclaringClass().getName());
            } else {
                log("ERRO: dispatchDraw não encontrado");
            }
        } catch (Throwable t) {
            log("ERRO na instalação: " + t);
        }
    }

    static boolean isFocusPrompt(View view) {
        try {
            Object entry = Refl.call(view, "getEntry");
            Object sbn = Refl.get(entry, "mSbn");
            return Boolean.TRUE.equals(Refl.get(sbn, "statusBarPrompting"));
        } catch (Throwable t) {
            return false;
        }
    }

    static void invalidateChildren(Object obj) {
        if (obj instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) obj;
            int n = vg.getChildCount();
            for (int i = 0; i < n; i++) {
                vg.getChildAt(i).invalidate();
            }
        }
    }

    static void schedule(View view) {
        try {
            if (pending.containsKey(view)) {
                return;
            }
            ElasticStart s = new ElasticStart(view);
            pending.put(view, s);
            s.observer.addOnPreDrawListener(s);
            view.invalidate();
        } catch (Throwable t) {
            log("ERRO ao agendar: " + t);
        }
    }

    static void start(View view) {
        try {
            ElasticState old = (ElasticState) states.get(view);
            if (old != null && !old.finished && old.mode != ElasticState.IOS_OUT && old.anim != null
                    && old.anim.getCurrentPlayTime() < 90L) {
                return;
            }
            Prefs.refresh();
            if (!Prefs.huEnabled) {
                return;
            }
            log("notificação fixada, iniciando animação");
            if (isFocusPrompt(view)) {
                log("notificação em foco: o HyperOS já anima a partir da cápsula, efeito ignorado");
                return;
            }
            Object content = Refl.call(view, "getShowingLayout");
            if (!(content instanceof View)) {
                log("ERRO: getShowingLayout ausente");
                return;
            }
            if (old != null) {
                ValueAnimator a = old.anim;
                if (a != null) a.cancel();
                old.finish();
            }
            int mode = Prefs.huStyle == 1 ? ElasticState.IOS_IN : ElasticState.ISLAND;
            ElasticState st = new ElasticState(view, (View) content, mode);
            ValueAnimator anim = ValueAnimator.ofFloat(0.0f, 1.0f);
            st.anim = anim;
            anim.setDuration(st.total);
            anim.setInterpolator(new LinearInterpolator());
            ElasticFrame frame = new ElasticFrame(st);
            anim.addUpdateListener(frame);
            anim.addListener(frame);
            states.put(view, st);
            if (mode == ElasticState.IOS_IN) st.attachDrop(view);
            st.tick(0.0f);
            anim.start();
            if (mode == ElasticState.ISLAND) {
                log("animação OriginOS iniciada (altura=" + Refl.height(view) + "px, ilha em y=" + (int) st.islandCY + ")");
            } else {
                log("animação iOS iniciada, com a gota (altura=" + Refl.height(view) + "px, nasce do " + st.anchorName() + " em x=" + (int) st.originX + " y=" + (int) st.originY + ")");
            }
        } catch (Throwable t) {
            log("ERRO ao iniciar: " + t);
        }
    }

    /** Estilo iOS: quando a heads-up vai embora, encolhe de volta para baixo do relógio. */
    static void startExit(View view) {
        try {
            Prefs.refresh();
            if (!Prefs.huEnabled || Prefs.huStyle != 1) return;
            if (StackHooks.onKeyguard || StackHooks.shadeOpen) return;   // indo para a lista, não some
            if (!view.isAttachedToWindow() || !view.isShown()) return;
            ElasticState old = (ElasticState) states.get(view);
            if (old != null && !old.finished && old.mode == ElasticState.IOS_OUT) return;
            Object content = Refl.call(view, "getShowingLayout");
            if (!(content instanceof View)) return;
            if (old != null) {
                ValueAnimator a = old.anim;
                if (a != null) a.cancel();
                old.finish();
            }
            int[] loc = new int[2];
            view.getLocationOnScreen(loc);
            if (loc[1] > view.getResources().getDisplayMetrics().heightPixels / 3) return;   // não estava no topo
            ElasticState st = new ElasticState(view, (View) content, ElasticState.IOS_OUT);
            st.captureStart(view);
            ValueAnimator anim = ValueAnimator.ofFloat(0.0f, 1.0f);
            st.anim = anim;
            anim.setDuration(st.duration);
            anim.setInterpolator(new LinearInterpolator());
            ElasticFrame frame = new ElasticFrame(st);
            anim.addUpdateListener(frame);
            anim.addListener(frame);
            states.put(view, st);
            st.attachDrop(view);
            st.tick(0.0f);
            anim.start();
            if (exitLogs++ < 3) log("saída iOS iniciada (volta para o " + st.anchorName() + " em x=" + (int) st.originX + " y=" + (int) st.originY + ")");
        } catch (Throwable t) {
            log("ERRO ao iniciar a saída: " + t);
        }
    }

    static int exitLogs, iosLogs;

    /** O HyperOS terminou de tirar a heads-up: encerra a saída (se ela já acabou). */
    static void endExit(View view) {
        try {
            ElasticState st = (ElasticState) states.get(view);
            if (st == null || st.finished || st.mode != ElasticState.IOS_OUT) return;
            if (st.holding) st.finish();
            else st.released = true;
        } catch (Throwable ignored) {
        }
    }

    static void stop(View view) {
        try {
            ElasticStart s = (ElasticStart) pending.remove(view);
            if (s != null) {
                ViewTreeObserver o = s.observer;
                if (o.isAlive()) {
                    o.removeOnPreDrawListener(s);
                }
            }
            ElasticState st = (ElasticState) states.get(view);
            if (st != null && st.mode == ElasticState.IOS_OUT) return;   // a saída segue sozinha
            if (st != null) {
                st.finish();
                ValueAnimator a = st.anim;
                if (a != null) {
                    a.cancel();
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
