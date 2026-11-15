package com.ilha.headsup;

import android.content.SharedPreferences;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Tela inicial (com.miui.home): recentes no estilo empilhado espelhados.
 *
 * Sem escala negativa (as animações de abrir/fechar apps leem a posição dos cartões e viravam a
 * janela de ponta-cabeça): a posição de cada cartão é espelhada na hora de aplicar (x vira largura − x),
 * e o toque que move a pilha é espelhado do mesmo jeito, para o arrastar seguir o dedo. Achar o cartão
 * tocado usa a posição real. Só no estilo empilhado (TaskStackViewLayoutStyleStack).
 */
final class HomeRecents {
    static Class<?> stackCls, styleStack;
    static Field fStyle, fRect;
    static SharedPreferences prefs;
    static long checkedAt;
    static boolean on;
    static int logs, inFlip, inReal, headLogs, flingLogs, touchLogs;
    static final ThreadLocal<float[]> saved = new ThreadLocal<>();

    static void log(String m) {
        XposedBridge.log("[HeadsUpIlha] recentes: " + m);
    }

    static void install(ClassLoader cl) {
        try {
            stackCls = Class.forName("com.miui.home.recents.views.TaskStackView", false, cl);
            styleStack = Class.forName("com.miui.home.recents.TaskStackViewLayoutStyleStack", false, cl);
            fStyle = stackCls.getDeclaredField("mTaskStackViewLayoutStyle");
            fStyle.setAccessible(true);
            final Class<?> tr = Class.forName("com.miui.home.recents.views.TaskViewTransform", false, cl);
            fRect = tr.getDeclaredField("rect");
            fRect.setAccessible(true);
            // a posição de cada cartão é espelhada já na saída do cálculo da pilha: tudo o que usa esse
            // cálculo (desenho, cabeçalho, animações) passa a ver a pilha do outro lado
            Class<?> alg = Class.forName("com.miui.home.recents.views.TaskStackViewsAlgorithmStack", false, cl);
            int calc = 0;
            for (Method m : alg.getDeclaredMethods()) {
                if (!m.getName().equals("getTaskViewTransform")) continue;
                final Class<?>[] pt = m.getParameterTypes();
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (!enabled()) return;
                            for (int i = 0; i < pt.length; i++) {
                                if (pt[i] != tr || p.args[i] == null) continue;
                                mirror(p.args[i]);
                                break;   // o primeiro é o resultado; o segundo é só referência
                            }
                        } catch (Throwable t) {
                            if (logs++ < 8) log("erro " + t);
                        }
                    }
                });
                calc++;
            }
            if (calc == 0) throw new NoSuchMethodException("getTaskViewTransform");
            // o corte da parte "escondida" da miniatura foi pensado para a pilha original: desligado
            Class<?> thumb = Class.forName("com.miui.home.recents.views.TaskViewThumbnail", false, cl);
            final Field fClip = thumb.getDeclaredField("mEnableClipWidth");
            fClip.setAccessible(true);
            for (Method m : thumb.getDeclaredMethods()) {
                final boolean en = m.getName().equals("setEnableClipWidth");
                if (!en && !m.getName().equals("setClipWidth")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!enabled()) return;
                            if (en) p.args[0] = Boolean.FALSE;
                            else fClip.setBoolean(p.thisObject, false);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
            // cabeçalho (ícone e nome): espelhado dentro do cartão, para o ícone ficar na ponta que
            // aparece quando o cartão está por baixo; ícone e texto são desvirados um a um
            Class<?> taskCls = Class.forName("com.miui.home.recents.views.TaskView", false, cl);
            final Field fHeader = taskCls.getDeclaredField("mHeaderView");
            fHeader.setAccessible(true);
            for (Method m : tr.getDeclaredMethods()) {
                if (!m.getName().equals("applyToTaskView")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object h = fHeader.get(p.args[0]);
                            if (!(h instanceof android.view.ViewGroup)) return;
                            View task = (View) p.args[0];
                            header((android.view.ViewGroup) h, mirrored(task.getParent() instanceof View ? (View) task.getParent() : null));
                        } catch (Throwable t) {
                            if (logs++ < 8) log("erro no cabeçalho " + t);
                        }
                    }
                });
            }
            Class<?> th = Class.forName("com.miui.home.recents.views.TaskStackViewTouchHandler", false, cl);
            Field fStack = null;
            for (Field f : th.getDeclaredFields()) if (stackCls.isAssignableFrom(f.getType())) fStack = f;
            if (fStack == null) throw new NoSuchFieldException("TaskStackView no toque");
            fStack.setAccessible(true);
            final Field stackF = fStack;
            // o toque que move a pilha chega espelhado
            XC_MethodHook flip = new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    View s = stackOf(p.thisObject, stackF);
                    if (!mirrored(s) || !(p.args[0] instanceof MotionEvent)) return;
                    // uma cópia espelhada de verdade (sem o histórico de amostras): só deslocar o evento
                    // original deixava as amostras antigas no sentido real, e a velocidade do deslize
                    // saía errada (a pilha "agarrava")
                    MotionEvent c = mirroredCopy((MotionEvent) p.args[0], s.getWidth());
                    track(c);
                    int a = c.getActionMasked();
                    if ((a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP) && touchLogs++ < 40)
                        log("toque " + p.method.getName() + " " + (a == 0 ? "desce" : "sobe") + " x=" + Math.round(((MotionEvent) p.args[0]).getX()) + "→" + Math.round(c.getX()));
                    p.setObjectExtra("orig", p.args[0]);
                    p.setObjectExtra("copy", c);
                    p.args[0] = c;
                    inFlip++;
                }

                protected void afterHookedMethod(MethodHookParam p) {
                    Object c = p.getObjectExtra("copy");
                    if (!(c instanceof MotionEvent)) return;
                    inFlip--;
                    p.args[0] = p.getObjectExtra("orig");
                }
            };
            // achar o cartão tocado: com a posição real (desfaz o espelho só durante a busca)
            XC_MethodHook real = new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (inFlip <= 0) return;   // só quando chega espelhado (de dentro do toque da pilha)
                    View s = stackOf(p.thisObject, stackF);
                    if (!mirrored(s) || !(p.args[0] instanceof MotionEvent)) return;
                    MotionEvent e = (MotionEvent) p.args[0];
                    e.setLocation(s.getWidth() - e.getX(), e.getY());
                    p.setObjectExtra("flip", s);
                    inReal++;
                }

                protected void afterHookedMethod(MethodHookParam p) {
                    Object s = p.getObjectExtra("flip");
                    if (!(s instanceof View)) return;
                    inReal--;
                    MotionEvent e = (MotionEvent) p.args[0];
                    e.setLocation(((View) s).getWidth() - e.getX(), e.getY());
                }
            };
            int n = 0;
            for (Method m : th.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 1 || pt[0] != MotionEvent.class) continue;
                String nm = m.getName();
                if (nm.equals("onInterceptTouchEvent") || nm.equals("onTouchEvent")) {
                    XposedBridge.hookMethod(m, flip);
                    n++;
                } else if (nm.equals("getChildAtPosition")) {
                    XposedBridge.hookMethod(m, real);
                    n++;
                }
            }
            // o toque espelhado também procura o cartão por coordenadas soltas (x, y): aí vale a posição real
            for (Method m : th.getDeclaredMethods()) {
                if (!m.getName().equals("findViewAtPoint")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (inFlip <= 0 || inReal > 0) return;
                        View s = stackOf(p.thisObject, stackF);
                        if (!mirrored(s) || !(p.args[0] instanceof Float)) return;
                        p.args[0] = s.getWidth() - (Float) p.args[0];
                    }
                });
                n++;
            }
            // diagnóstico do deslize: o que chega ao impulso (fling) e quanto o toque anda
            try {
                Class<?> sc = Class.forName("com.miui.home.recents.views.TaskStackViewStackScroller", false, cl);
                for (Method m : sc.getDeclaredMethods()) {
                    if (!m.getName().equals("fling")) continue;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        protected void beforeHookedMethod(MethodHookParam p) {
                            // a tela inicial perde a velocidade do deslize espelhado (chega 0): usa a nossa,
                            // medida nos mesmos toques espelhados que ela recebeu
                            if (enabled() && p.args.length > 6 && p.args[5] instanceof Integer && (Integer) p.args[5] == 0
                                    && Math.abs(lastVx) > 200f && SystemClock.uptimeMillis() - lastUpAt < 300) {
                                p.args[5] = Math.round(lastVx);
                                p.args[6] = Math.round(lastVy);
                            }
                            if (flingLogs++ < 30) log("impulso " + java.util.Arrays.toString(p.args) + (enabled() ? " (espelhado)" : ""));
                        }
                    });
                }
            } catch (Throwable t) {
                log("sem diagnóstico do impulso: " + t);
            }
            log("pronto (" + n + " ganchos de toque)");
        } catch (Throwable t) {
            log("não instalado: " + t);
        }
    }

    static final java.util.WeakHashMap<Object, float[]> done = new java.util.WeakHashMap<>();
    static float width;

    /** Espelha o retângulo do cartão; se o cálculo não mexeu nele desde a última vez, já está espelhado. */
    static void mirror(Object transform) throws Exception {
        RectF r = (RectF) fRect.get(transform);
        if (r == null || r.isEmpty()) return;
        float[] d = done.get(transform);
        if (d != null && d[0] == r.left && d[1] == r.right) return;
        float w = width > 0 ? width : android.content.res.Resources.getSystem().getDisplayMetrics().widthPixels;
        float l = w - r.right, rt = w - r.left;
        r.left = l;
        r.right = rt;
        done.put(transform, new float[]{l, rt});
        if (logs++ < 4) log("cartão espelhado em " + Math.round(l) + ".." + Math.round(rt) + " de " + Math.round(w));
    }

    static void header(android.view.ViewGroup h, boolean want) {
        if (want && headLogs++ < 3) {
            StringBuilder b = new StringBuilder("cabeçalho " + h.getWidth() + "x" + h.getHeight() + " x=" + h.getX() + " escala=" + h.getScaleX()
                    + " pai=" + (h.getParent() instanceof View ? ((View) h.getParent()).getWidth() : -1) + ":");
            for (int i = 0; i < h.getChildCount(); i++) {
                View c = h.getChildAt(i);
                b.append(' ').append(c.getClass().getSimpleName()).append('@').append(c.getLeft()).append('+').append(c.getWidth())
                        .append(c.getVisibility() == View.VISIBLE ? "" : "(oculto)");
            }
            log(b.toString());
        }
        float target = want ? -1f : 1f;
        if (h.getWidth() <= 0) return;
        if (h.getScaleX() == target && (!want || h.getPivotX() == h.getWidth() / 2f)) return;
        if (!want && h.getScaleX() != -1f) return;
        h.setPivotX(h.getWidth() / 2f);
        h.setScaleX(target);
        for (int i = 0; i < h.getChildCount(); i++) {
            View c = h.getChildAt(i);
            c.setPivotX(c.getWidth() / 2f);
            c.setScaleX(target);
            if (c instanceof android.widget.TextView) {
                android.widget.TextView t = (android.widget.TextView) c;
                int g = t.getGravity() & android.view.Gravity.VERTICAL_GRAVITY_MASK;
                t.setGravity(g | (want ? android.view.Gravity.END : android.view.Gravity.START));
            }
        }
        if (logs++ < 6) log("cabeçalho " + (want ? "espelhado" : "normal") + " " + h.getWidth() + "px, " + h.getChildCount() + " partes");
    }

    static android.view.VelocityTracker vt;
    static float lastVx, lastVy;
    static long lastUpAt, lastEvt = -1;

    static void track(MotionEvent c) {
        int a = c.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            if (vt == null) vt = android.view.VelocityTracker.obtain();
            vt.clear();
            lastEvt = -1;
        }
        if (vt == null) return;
        // o mesmo toque passa por mais de um gancho (interceptar e tratar): conta uma vez
        if (c.getEventTime() == lastEvt && a == MotionEvent.ACTION_MOVE) return;
        lastEvt = c.getEventTime();
        vt.addMovement(c);
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            vt.computeCurrentVelocity(1000);
            lastVx = vt.getXVelocity();
            lastVy = vt.getYVelocity();
            lastUpAt = SystemClock.uptimeMillis();
            if (flingLogs < 30) log("velocidade medida " + Math.round(lastVx) + "," + Math.round(lastVy));
        }
    }

    static MotionEvent mirroredCopy(MotionEvent e, float w) {
        int n = e.getPointerCount();
        MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            pp[i] = new MotionEvent.PointerProperties();
            e.getPointerProperties(i, pp[i]);
            pc[i] = new MotionEvent.PointerCoords();
            e.getPointerCoords(i, pc[i]);
            pc[i].x = w - pc[i].x;
        }
        return MotionEvent.obtain(e.getDownTime(), e.getEventTime(), e.getAction(), n, pp, pc, e.getMetaState(),
                e.getButtonState(), e.getXPrecision(), e.getYPrecision(), e.getDeviceId(), e.getEdgeFlags(), e.getSource(), e.getFlags());
    }

    static View stackOf(Object handler, Field f) {
        try {
            Object o = f.get(handler);
            return o instanceof View ? (View) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean mirrored(View stack) {
        try {
            if (stack != null && stack.getWidth() > 0 && stackCls.isInstance(stack)) width = stack.getWidth();
            return stack != null && stackCls.isInstance(stack) && stack.getWidth() > 0 && enabled()
                    && styleStack.isInstance(fStyle.get(stack));
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean enabled() {
        long now = SystemClock.uptimeMillis();
        if (now - checkedAt > 1000) {
            checkedAt = now;
            try {
                if (prefs == null) prefs = XposedBridge.remotePrefs(Keys.GROUP);
                on = prefs != null && prefs.getBoolean(Keys.RECENTS_MIRROR, Keys.D_RECENTS_MIRROR);
            } catch (Throwable ignored) {
            }
        }
        return on;
    }
}
