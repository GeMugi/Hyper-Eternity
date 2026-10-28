package com.ilha.headsup;

import android.app.KeyguardManager;
import android.graphics.Outline;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Central estilo iOS (cortina): a central desce do topo acompanhando o dedo, com o fundo do
 * bloqueio (ou um desfoque), relógio grande, lanterna e câmera. As animações próprias do HyperOS
 * (entrada dos cartões, cabeçalho, desfoque da janela) ficam desligadas enquanto ela está ligada.
 */
final class ShadeSheet {
    private ShadeSheet() {}

    static WeakReference<View> panel;
    static Object expandCtl;
    static boolean on, clipped, logged, preHooked;
    static float radius, lastF;
    static android.graphics.drawable.Drawable bgDrawable, orig;
    static boolean bgBlur;
    static final ArrayList<View> hidden = new ArrayList<>();
    static SheetDeco decoV;
    static View hdr;

    /** Opção desligada: nenhum estado da cortina pode ficar preso segurando o painel original. */
    static long lastOurTouch;
    static int ccLogs;

    static boolean dead() {
        if (Prefs.shadeSheet) return false;
        if (transfer || dragging) { transfer = false; dragging = false; }
        if (on) {
            final View v = panel == null ? null : panel.get();
            if (v != null) v.post(new Runnable() { public void run() { if (on) off(v); } });
        }
        return true;
    }

    static boolean enabled(View v) {
        return Prefs.shadeSheet && !locked(v);
    }

    static void install(ClassLoader cl) {
        try {
            Class pv = Class.forName("com.android.systemui.shade.NotificationShadeWindowView", false, cl);
            for (java.lang.reflect.Constructor k : pv.getDeclaredConstructors()) {
                XposedBridge.hookMethod(k, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        panel = new WeakReference<>((View) p.thisObject);
                        ensureMonitor((View) p.thisObject);
                    }
                });
            }
            // durante o nosso puxar, a lista não recebe o dedo (senão as notificações rolavam junto)
            XposedBridge.hookMethod(pv.getDeclaredMethod("dispatchTouchEvent", android.view.MotionEvent.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    android.view.MotionEvent me = (android.view.MotionEvent) p.args[0];
                    if (forwardLand(me)) { p.setResult(Boolean.TRUE); return; }   // player movido (paisagem)
                    if (dead()) return;
                    // HyperOS 4: com uma heads-up na tela, o puxar começa em cima dela (a barra de status fica
                    // por baixo) e chega aqui; a cortina assume como se fosse da barra de status
                    if (huGesture(p, me)) return;   // puxar por cima de uma heads-up (lógica própria)
                    if ((on || dragging) && !transfer && !sbTake) feed(me);   // cortina aberta: barrinha de baixo para fechar
                    if (me.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && (transfer || dragging)
                            && android.os.SystemClock.uptimeMillis() - lastOurTouch > 400) {
                        // gesto da cortina ficou "preso" (o fim dele nunca chegou): solta, senão a janela
                        // da central engole todos os toques e o centro de controle não abre
                        ElasticCard.log("cortina: gesto preso liberado (transfer=" + transfer + " drag=" + dragging + ")");
                        transfer = false;
                        dragging = false;
                        candidate = false;
                    }
                    if (transfer || dragging) p.setResult(Boolean.TRUE);   // só enquanto o dedo arrasta a cortina
                }
            });
            try {
                for (java.lang.reflect.Constructor k : Class.forName("com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl", false, cl).getDeclaredConstructors()) {
                    XposedBridge.hookMethod(k, new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam p) {
                            hum = p.thisObject;
                        }
                    });
                }
            } catch (Throwable ignored) {
            }
            for (java.lang.reflect.Constructor k : Class.forName("com.android.systemui.shade.ShadeControllerImpl", false, cl).getDeclaredConstructors()) {
                XposedBridge.hookMethod(k, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        shadeCtl = p.thisObject;
                    }
                });
            }
            Class npvc = Class.forName("com.android.systemui.shade.NotificationPanelViewController", false, cl);
            try {
                XposedBridge.hookMethod(npvc.getDeclaredMethod("startInputFocusTransfer"), new XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            View v = panel == null ? null : panel.get();
                            if (v == null || !Prefs.shadeSheet || locked(v) || on || animating) return;
                            // puxar na tela inicial: o launcher passa o gesto; a cortina assume no lugar do HyperOS
                            p.setResult(null);
                            transfer = true;
                            candidate = true;
                            closing = false;
                            if (vt == null) vt = android.view.VelocityTracker.obtain();
                            vt.clear();
                            transferY0 = -1f;
                            begin(v);
                            dragging = true;
                            ElasticCard.log("cortina: puxada pela tela inicial");
                        } catch (Throwable t) {
                            ElasticCard.log("cortina transfer: " + t);
                        }
                    }
                });
            } catch (Throwable t) {
                // HyperOS 4: o puxar da tela inicial e da barra de status chega como "toque externo"
                hookExternal(cl);
            }
            try {
                XC_MethodHook skip = new XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam p) {
                        // o launcher termina/cancela o gesto dele; quem decide é o nosso dedo
                        if (dead()) return;
                        if (transfer || on) p.setResult(null);
                    }
                };
                XposedBridge.hookMethod(npvc.getDeclaredMethod("onTrackingStarted$2"), new XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            View v = panel == null ? null : panel.get();
                            if (!on || dragging || transfer || v == null || !expanded()) return;
                            // o HyperOS já decidiu que o dedo está fechando a central (não rolando a lista):
                            // a partir daqui quem sobe é a cortina, presa ao dedo
                            p.setResult(null);
                            pilfer();
                            if (anim != null) anim.cancel();
                            if (vt == null) vt = android.view.VelocityTracker.obtain();
                            vt.clear();
                            candidate = true;
                            closing = true;
                            dragging = true;
                            startTy = v.getTranslationY();
                            downY = lastRawY;
                        } catch (Throwable t) {
                            ElasticCard.log("cortina fechar (lista): " + t);
                        }
                    }
                });
                XposedBridge.hookMethod(npvc.getDeclaredMethod("flingToHeight", float.class, boolean.class, float.class, float.class, boolean.class), new XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!on || Boolean.TRUE.equals(p.args[1])) return;
                            p.setResult(null);
                            if (android.os.SystemClock.uptimeMillis() < noCloseUntil) return;   // depois do limpar tudo: fica aberta
                            View v = panel == null ? null : panel.get();
                            // fechar pelo HyperOS (arrastar para cima, tocar fora, voltar): a cortina sobe
                            if (v != null && !animating) settle(v, false, -Math.abs(((Float) p.args[0]).floatValue()));
                        } catch (Throwable t) {
                            ElasticCard.log("cortina fechar: " + t);
                        }
                    }
                });
                for (java.lang.reflect.Method m : npvc.getDeclaredMethods()) {
                    String n = m.getName();
                    if ("finishInputFocusTransfer".equals(n) || "cancelInputFocusTransfer".equals(n)) XposedBridge.hookMethod(m, skip);
                }
            } catch (Throwable t) {
                ElasticCard.log("cortina: fechar pela lista indisponível: " + t);
            }
            final Class pc = Class.forName("com.android.systemui.shade.NotificationPanelExpandController", false, cl);
            try {
            for (java.lang.reflect.Constructor k : pc.getDeclaredConstructors()) {
                XposedBridge.hookMethod(k, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        expandCtl = p.thisObject;
                    }
                });
            }
            } catch (Throwable t) {
                ElasticCard.log("cortina: passo 1 (controle da abertura): " + t);
            }
            // progresso da abertura (o mesmo que anima o relógio da central)
            try {
            Class em = Class.forName("com.android.systemui.shade.NotificationPanelExpandController$2$1$1", false, cl);
            final java.lang.reflect.Field fid = em.getDeclaredField("$r8$classId");
            fid.setAccessible(true);
            XposedBridge.hookMethod(em.getDeclaredMethod("emit", Object.class, Class.forName("kotlin.coroutines.Continuation", false, cl)), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (fid.getInt(p.thisObject) != 0) return;
                        apply(((Number) p.args[0]).floatValue());
                    } catch (Throwable t) {
                        ElasticCard.log("cortina: " + t);
                    }
                }
            });
            } catch (Throwable t) {
                ElasticCard.log("cortina: passo 2 (progresso da abertura): " + t);
            }
            // conteúdo: os cartões não fazem a entrada do HyperOS, já estão prontos dentro da cortina
            try {
            Class ac = Class.forName("com.android.systemui.shade.MiuiNotificationPanelAnimControllerImpl", false, cl);
            for (java.lang.reflect.Method m : ac.getDeclaredMethods()) {
                if (m.getName().equals("startPanelExpandAnimation$default")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (!active() || !Boolean.TRUE.equals(p.args[1])) return;
                            p.args[2] = Float.valueOf(thresh());
                            p.args[3] = Boolean.FALSE;
                        }
                    });
                } else if (m.getName().equals("startPanelVisibleAnimation")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (!active() || !Boolean.TRUE.equals(p.args[0])) return;
                            p.args[1] = Boolean.FALSE;
                        }
                    });
                }
            }
            } catch (Throwable t) {
                ElasticCard.log("cortina: passo 3 (entrada dos cartões): " + t);
            }
            try {
            XposedBridge.hookMethod(pc.getDeclaredMethod("notifyAppearanceChanged", boolean.class, boolean.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!active() || !Boolean.TRUE.equals(p.args[0])) return;
                    p.args[1] = Boolean.FALSE;
                }
            });
            } catch (Throwable t) {
                ElasticCard.log("cortina: passo 4 (aparência): " + t);
            }
            // HyperOS 4: fechar (gesto de início, voltar, tocar fora) chama hide(true), que fazia a animação
            // da central padrão por cima da cortina. Com a cortina aberta, quem fecha é ela (sobe).
            try {
                XposedBridge.hookMethod(pc.getDeclaredMethod("hide", boolean.class), new XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!Boolean.TRUE.equals(p.args[0]) || !active() || closingWait) return;
                            View v = panel == null ? null : panel.get();
                            if (v == null) return;
                            p.setResult(null);
                            if (android.os.SystemClock.uptimeMillis() < noCloseUntil) return;   // depois do limpar tudo: fica aberta
                            if (!animating && !dragging) settle(v, false, -2500f);
                            if (hideLogs++ < 3) ElasticCard.log("cortina: fechar do HyperOS trocado pela subida da cortina");
                        } catch (Throwable t) {
                            ElasticCard.log("cortina hide: " + t);
                        }
                    }
                });
            } catch (Throwable t) {
                ElasticCard.log("cortina: passo 4b (fechar animado): " + t);
            }
            try {
                hookSwitch(cl);
            } catch (Throwable t) {
                ElasticCard.log("cortina: passo 4c (troca lateral): " + t);
            }
            String suf = "$packages__apps__MiuiSystemUI__packages__SystemUI__android_common__MiuiSystemUI_core";
            try {
                Class st = Class.forName("com.android.systemui.shade.NotificationPanelStretchAnimator", false, cl);
                XposedBridge.hookMethod(st.getDeclaredMethod("updateStretch" + suf, float.class, float.class, boolean.class, boolean.class), new XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (!active()) return;
                        p.args[0] = p.args[1];
                        p.args[2] = Boolean.FALSE;
                    }
                });
            } catch (Throwable t) {
                ElasticCard.log("cortina: esticar indisponível: " + t);
            }
            try {
                Class bl = Class.forName("com.android.systemui.shade.NotificationPanelBlurAnimator", false, cl);
                XposedBridge.hookMethod(bl.getDeclaredMethod("updateBlurRatio" + suf, float.class, boolean.class), new XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam p) {
                        // desfoque do HyperOS segue a abertura normalmente (forçar cheio piscava a tela)
                    }
                });
            } catch (Throwable t) {
                ElasticCard.log("cortina: desfoque indisponível: " + t);
            }
            Kg.hookAffordances(cl);
            try {
                Kg.hookBlur(cl);
            } catch (Throwable t) {
                ElasticCard.log("cortina: passo 5 (material do relógio): " + t);
            }
            try {
                hookGradient();
            } catch (Throwable t) {
                ElasticCard.log("cortina: passo 6 (degradê): " + t);
            }
            try {
                CurtainClock.install(cl);
            } catch (Throwable t) {
                ElasticCard.log("cortina: passo 7 (relógio): " + t);
            }
            try {
                hookStackShift(cl);
            } catch (Throwable t) {
                ElasticCard.log("cortina: passo 8 (lista): " + t);
            }
            try {
                hookSources(cl);
            } catch (Throwable t) {
                ElasticCard.log("cortina: passo 9 (toques): " + t);
            }
            // teste: o vidro do HyperOS 4 (setMiGlass / setMiViewMaterialType) existe neste sistema?
            try {
                StringBuilder gl = new StringBuilder();
                for (java.lang.reflect.Method m : View.class.getMethods()) {
                    String n = m.getName();
                    if (n.contains("Glass") || n.contains("MaterialType") || n.contains("Sdf")) gl.append(n).append(java.util.Arrays.toString(m.getParameterTypes())).append(' ');
                }
                ElasticCard.log("teste vidro HOS4: " + (gl.length() == 0 ? "nenhum método de vidro neste sistema" : gl));
            } catch (Throwable t) {
                ElasticCard.log("teste vidro HOS4: " + t);
            }
            ElasticCard.log("cortina: pronta");
        } catch (Throwable t) {
            ElasticCard.log("cortina indisponível: " + t);
            Caps.fail(Keys.SHADE_SHEET, t);
        }
    }

    static boolean active() {
        View v = panel == null ? null : panel.get();
        return on && v != null && enabled(v);
    }

    static boolean tracking() {
        try {
            Object fl = Refl.get(expandCtl, "trackingInternal");
            Object val = fl.getClass().getMethod("getValue").invoke(fl);
            return Boolean.TRUE.equals(val);
        } catch (Throwable t) {
            return true;
        }
    }

    static float thresh() {
        try {
            return ((Number) Refl.get(expandCtl, "expandThresh")).floatValue();
        } catch (Throwable t) {
            return 1000f;
        }
    }

    static void apply(float f) {
        // a abertura agora é do nosso gesto; aqui só encerra quando a central fecha de outro jeito
        final View v = panel == null ? null : panel.get();
        if (v == null) return;
        lastF = f;
        if (switchingNow) return;   // troca lateral: quem decide é o fim da troca
        if (on && f <= 0f && !dragging && !animating) {
            if (closingWait) finishClose(v);
            else off(v);
        }
    }

    // ------------------------------------------------------------------ gesto próprio

    static Object shadeCtl, monitor, receiver;
    static boolean dragging, animating, candidate, closing, monitorTried, transfer, listClose;
    static float downX, downY, startTy, transferY0 = -1f, lastRawY;
    static long noCloseUntil;
    static android.view.VelocityTracker vt;
    static android.animation.ValueAnimator anim;

    static void ensureMonitor(final View v) {
        try {
            Prefs.refresh();
        } catch (Throwable ignored) {
        }
        if (monitorTried || !Prefs.shadeSheet) return;
        monitorTried = true;
        if (NO_MONITOR) { v.post(new Runnable() { public void run() { hookPreDraw(v); } }); ElasticCard.log("cortina: toques pela barra de status e pela tela inicial"); return; }
        // o monitor de toques roda numa linha própria: a linha principal da interface às vezes demora
        // centenas de ms, e o sistema espera o monitor antes de entregar o toque (central de controle travava)
        final android.os.HandlerThread ht = new android.os.HandlerThread("hyper-eternity-input");
        ht.start();
        final android.os.Handler bg = new android.os.Handler(ht.getLooper());
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        v.post(new Runnable() {
            public void run() {
                hookPreDraw(v);
                bg.post(new Runnable() {
                    public void run() {
                        try {
                            ClassLoader cl = v.getClass().getClassLoader();
                            Class mc = Class.forName("com.android.systemui.shared.system.InputMonitorCompat", false, cl);
                            monitor = mc.getConstructor(String.class, int.class).newInstance("hyper-eternity-curtain", Integer.valueOf(0));
                            Class lc = Class.forName("com.android.systemui.shared.system.InputChannelCompat$InputEventListener", false, cl);
                            final float d = v.getResources().getDisplayMetrics().density;
                            final int sw = v.getResources().getDisplayMetrics().widthPixels, sh = v.getResources().getDisplayMetrics().heightPixels;
                            Object listener = java.lang.reflect.Proxy.newProxyInstance(cl, new Class[]{lc}, new java.lang.reflect.InvocationHandler() {
                                boolean track;
                                public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                                    if (pxLogs < 4) { pxLogs++; ElasticCard.log("cortina monitor: " + m.getName() + " " + (args != null && args.length > 0 && args[0] != null ? args[0].getClass().getSimpleName() : "-")); }
                                    if (args == null || args.length == 0 || !(args[0] instanceof android.view.MotionEvent)) return null;
                                    android.view.MotionEvent e = (android.view.MotionEvent) args[0];
                                    int a = e.getActionMasked();
                                    if (a == android.view.MotionEvent.ACTION_DOWN) {
                                        float x = e.getRawX(), y = e.getRawY();
                                        // só interessa: topo do lado das notificações, barrinha de baixo com a cortina aberta,
                                        // ou gesto já em andamento. O resto nem passa pela linha principal.
                                        track = !Prefs.shadeSheet ? false : (transfer || on || dragging || (y < 48f * d && x < sw * 0.5f) || y > sh - 90f * d);
                                        if (y < 60f * d) {
                                            final float fx = x;
                                            ui.post(new Runnable() { public void run() { sanity(fx); } });
                                        }
                                    }
                                    if (!track && !transfer) return null;
                                    final android.view.MotionEvent c = android.view.MotionEvent.obtain(e);
                                    ui.post(new Runnable() {
                                        public void run() {
                                            try {
                                                touch(c);
                                            } catch (Throwable t) {
                                                ElasticCard.log("cortina gesto: " + t);
                                            } finally {
                                                c.recycle();
                                            }
                                        }
                                    });
                                    return null;
                                }
                            });
                            receiver = mc.getMethod("getInputReceiver", android.os.Looper.class, android.view.Choreographer.class, lc)
                                    .invoke(monitor, ht.getLooper(), android.view.Choreographer.getInstance(), listener);
                            ElasticCard.log("cortina: gesto próprio pronto (linha própria)");
                        } catch (Throwable t) {
                            ElasticCard.log("cortina: gesto próprio indisponível: " + t);
                        }
                    }
                });
            }
        });
    }

    static int sanityLogs, pxLogs;

    /** Rolagem atual da lista (px), só leitura. */
    static int scrollY() {
        try {
            if (nssl == null || !on) return 0;
            return Math.max(0, ((Integer) Refl.call(nssl, "getOwnScrollY")).intValue());
        } catch (Throwable t) {
            return 0;
        }
    }

    static void requestStack() {
        try {
            if (nssl == null) return;
            java.lang.reflect.Method m = Refl.anyMethod(nssl.getClass(), "requestChildrenUpdate");
            m.setAccessible(true);
            m.invoke(nssl);
        } catch (Throwable ignored) {
        }
    }

    static int shiftLogs, diagN, listExtra, lastSc = -1;
    static long diagAt;

    /**
     * Relógio que sobe ao rolar: a lista já fica na posição final e o próprio HyperOS desenha os cartões
     * e px mais abaixo (depois de calcular as posições). A rolagem ganha esses e px; rolar até e leva os
     * cartões e o cabeçalho juntos para cima. O toque continua 100% do sistema.
     */
    static void hookStackShift(ClassLoader cl) {
        try {
            String P = "com.android.systemui.statusbar.notification.stack.";
            Class ssa = Class.forName(P + "StackScrollAlgorithm", false, cl);
            Class amb = Class.forName(P + "AmbientState", false, cl);
            final java.lang.reflect.Field fState = Refl.field(ssa, "mTempAlgorithmState");
            final java.lang.reflect.Field fVis = Refl.field(Class.forName(P + "StackScrollAlgorithm$StackScrollAlgorithmState", false, cl), "visibleChildren");
            XposedBridge.hookMethod(ssa.getDeclaredMethod("resetViewStates", amb, int.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    shiftedNow = false;
                    landHide(p.thisObject, p.args[0]);   // paisagem: o player sai da conta da lista nesta passada
                }

                protected void afterHookedMethod(MethodHookParam p) {
                    landRestore();
                    try {
                        if (on && landNow) {
                            titleExtra = 0;
                            if (playerGap != 0) playerGap = 0;
                            landPlayer(p.thisObject, p.args[0]);
                            return;
                        }
                        if (!on && (splitNow || landDirty || splitTx || splitLp != null)) {   // gaveta padrão em paisagem (opção)
                            landPlayer(p.thisObject, p.args[0]);
                            return;
                        }
                        landMoved = false;
                        resetLandFrom(p.thisObject);
                        int e = Math.round(CurtainClock.e) + listExtra;
                        if (!on || e <= 0) {
                            titleExtra = 0;
                            return;
                        }
                        Object st = fState.get(p.thisObject);
                        Object vis = st == null ? null : fVis.get(st);
                        if (!(vis instanceof java.util.List)) return;
                        for (Object c : (java.util.List) vis) {
                            Object vs = Refl.call(c, "getViewState");
                            if (vs == null) continue;
                            float y = Refl.field(vs.getClass(), "mYTranslation").getFloat(vs);
                            // com o player na lista, os cartões depois dele descem a faixa de "Notificações"
                            int gapNow = (playerGap > 0 && !c.getClass().getSimpleName().contains("MediaHeader")) ? playerGap : 0;
                            GroupStack.call(vs, "setYTranslation", y + e + gapNow);
                            // o corte de cima foi calculado antes de descer: desconta o que desceu
                            java.lang.reflect.Field fc = Refl.field(vs.getClass(), "clipTopAmount");
                            if (fc != null) fc.setInt(vs, Math.max(0, fc.getInt(vs) - e));
                            // linha que o sistema escondeu por ter "saído por cima" mas que, descida, ainda aparece
                            java.lang.reflect.Field fh = Refl.field(vs.getClass(), "hidden");
                            java.lang.reflect.Field fs = Refl.field(vs.getClass(), "inShelf");
                            int hgt = Refl.field(vs.getClass(), "height").getInt(vs);
                            boolean shelf = fs != null && fs.getBoolean(vs);
                            if (fh != null && fh.getBoolean(vs) && !shelf && y + e + hgt > 0) fh.setBoolean(vs, false);
                        }
                        pile((java.util.List) vis, p.thisObject);
                        shiftedNow = true;   // a pilha da gaveta, se vier depois, já vê as posições descidas
                        if (shiftLogs++ < 3) ElasticCard.log("cortina: cartões descidos " + e + "px (relógio sobe ao rolar)");
                        if (decoV != null) decoV.postInvalidateOnAnimation();
                    } catch (Throwable t) {
                        if (shiftLogs++ < 6) ElasticCard.log("cortina: deslocar cartões: " + t);
                    }
                }
            });
            Class nc = Class.forName(P + "NotificationStackScrollLayout", false, cl);
            // cortina: a lista some suave logo acima da lanterna e da câmera (nada passa por baixo delas)
            try {
                XposedBridge.hookMethod(nc.getDeclaredMethod("dispatchDraw", android.graphics.Canvas.class), new XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam p) {
                        fadeSave = -1;
                        try {
                            View l = (View) p.thisObject;
                            if (!active() || l.getWidth() > 0 && l.getRootView().getWidth() > l.getRootView().getHeight()) return;
                            float lim = buttonLimit(l);
                            if (Float.isNaN(lim) || lim <= 0 || lim > l.getHeight() + 4000) return;
                            float d = l.getResources().getDisplayMetrics().density;
                            android.graphics.Canvas c = (android.graphics.Canvas) p.args[0];
                            int w = l.getWidth();
                            fadeLim = lim + 10 * d;
                            // só corte (sem camada): a camada desligava o vidro/desfoque dos cartões
                            fadeSave = c.save();
                            c.clipRect(-w, -100000f, 2f * w, fadeLim);
                            if (fadeLogs++ < 2) ElasticCard.log("cortina: lista termina acima dos botões (" + Math.round(fadeLim) + "px)");
                        } catch (Throwable t) {
                            fadeSave = -1;
                        }
                    }

                    protected void afterHookedMethod(MethodHookParam p) {
                        if (fadeSave < 0) return;
                        try {
                            android.graphics.Canvas c = (android.graphics.Canvas) p.args[0];
                            c.restoreToCount(fadeSave);
                        } catch (Throwable ignored) {
                        } finally {
                            fadeSave = -1;
                        }
                    }
                });
            } catch (Throwable t) {
                ElasticCard.log("cortina: fim da lista acima dos botões indisponível: " + t);
            }
            // paisagem: o player movido para baixo do relógio não é "o cartão debaixo do dedo" na coluna
            // das notificações (o HyperOS ignora o deslocamento lateral ao procurar o cartão tocado)
            try {
                for (java.lang.reflect.Method m : nc.getDeclaredMethods()) {
                    if (!m.getName().equals("getChildAtPosition")) continue;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        protected void beforeHookedMethod(MethodHookParam p) {
                            pickHidden = null;
                            try {
                                View pv = playerView;
                                if (!landMoved || pv == null || pv.getParent() != p.thisObject || fViewFlags == null) return;
                                int f = fViewFlags.getInt(pv);
                                fViewFlags.setInt(pv, (f & ~0x0C) | 0x08);
                                pickHidden = pv;
                            } catch (Throwable ignored) {
                            }
                        }

                        protected void afterHookedMethod(MethodHookParam p) {
                            View pv = pickHidden;
                            pickHidden = null;
                            if (pv == null) return;
                            try { fViewFlags.setInt(pv, fViewFlags.getInt(pv) & ~0x0C); } catch (Throwable ignored) {}
                        }
                    });
                }
            } catch (Throwable t) {
                ElasticCard.log("cortina paisagem: toque nos cartões: " + t);
            }
            XposedBridge.hookMethod(nc.getDeclaredMethod("getScrollRange"), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if (landMoved) {   // paisagem: o player saiu da lista, a rolagem perde a altura dele
                        p.setResult(Integer.valueOf(Math.max(0, ((Integer) p.getResult()).intValue() - Math.round(landUp))));
                        return;
                    }
                    int e = Math.round(CurtainClock.e) + listExtra + playerGap;
                    if (on && e > 0 && !landNow) p.setResult(Integer.valueOf(((Integer) p.getResult()).intValue() + e));
                }
            });
            // sem o elástico do fim da lista na cortina: ao chegar no topo ou no fim, a lista (com o
            // player e o título) dava um pulo e voltava
            XC_MethodHook noOver = new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (active() && p.args.length > 0 && p.args[0] instanceof Float && ((Float) p.args[0]).floatValue() != 0f) {
                        p.args[0] = Float.valueOf(0f);
                        if (overLogs++ < 2) ElasticCard.log("cortina: elástico do fim da lista desligado");
                    }
                }
            };
            int nOver = 0;
            for (java.lang.reflect.Method m : nc.getDeclaredMethods()) {
                String n = m.getName();
                if (("setOverScrollAmount".equals(n) || "setOverScrolledPixels".equals(n)) && m.getParameterTypes().length > 0 && m.getParameterTypes()[0] == float.class) {
                    XposedBridge.hookMethod(m, noOver);
                    nOver++;
                }
            }
            ElasticCard.log("cortina: elástico do fim da lista: " + nOver + " ganchos");
        } catch (Throwable t) {
            ElasticCard.log("cortina: relógio que sobe indisponível: " + t);
        }
    }
    static int endLogs, overLogs, hideLogs, swLogs, fadeLogs, fadeSave = -1;
    static float fadeLim;
    static android.graphics.Paint fadePaint;

    /** Quanto a cortina desce os cartões depois do algoritmo do HyperOS (relógio + lista + título). */
    static boolean shiftedNow;

    /** Quanto a pilha da gaveta ainda precisa descontar: 0 se a cortina já desceu os cartões nesta passada. */
    static int pendingShift() {
        return shiftedNow ? 0 : shift();
    }

    static int shift() {
        if (landNow) return 0;
        int e = Math.round(CurtainClock.e) + listExtra;
        return e > 0 ? e + playerGap : 0;
    }
    static boolean sbTake;
    static float titleExtra;
    static View playerView;
    static int playerGap;   // px a mais para "Notificações" e o X (ficam abaixo do player)
    static int pileLogs;

    static final java.util.WeakHashMap<View, Boolean> topTouched = new java.util.WeakHashMap<>();
    static final java.util.WeakHashMap<View, Boolean> hdrFaded = new java.util.WeakHashMap<>();

    /** Um cartão (ou filho de grupo) na pilha de cima: espelho de StackHooks.stackOne em torno de T. */
    static boolean topOne(View v, Object vs, float y, float h, float T, StackMath m, boolean first, float base, float z) throws Throwable {
        float d = v.getResources().getDisplayMetrics().density;
        float piv = h - v.getPivotY();          // pivô espelhado
        float c = 2 * T - y;                    // fundo do cartão no espaço espelhado
        boolean grp = false;
        try { grp = Prefs.groupStack && GroupStack.stacked(v); } catch (Throwable ignored) {}
        float[] r = (h <= 107f * d || grp) ? m.normal(first, h, c, T, piv, 0f) : m.big(h, c, 72f * d, piv, T, first, 0f);
        if (r == null) {
            if (topTouched.remove(v) != null) {
                VS.inv(StackHooks.mSetScaleX, vs, 1f);
                VS.inv(StackHooks.mSetScaleY, vs, 1f);
            }
            return false;
        }
        topTouched.put(v, Boolean.TRUE);
        VS.inv(StackHooks.mSetY, vs, y + base - r[0]);
        VS.inv(StackHooks.mSetScaleX, vs, r[1]);
        VS.inv(StackHooks.mSetScaleY, vs, r[1]);
        if (StackHooks.mSetZ != null) VS.inv(StackHooks.mSetZ, vs, z);   // os de cima ficam atrás
        StackHooks.fInShelf.setBoolean(vs, false);
        StackHooks.fClipTop.setInt(vs, Math.max(0, (int) r[2]));
        StackHooks.fHidden.setBoolean(vs, r[3] > 3f);
        float a = StackHooks.fAlpha.getFloat(vs);
        if (r[3] > 2f) a = a * StackMath.clamp(3f - r[3], 0f, 1f);
        VS.inv(StackHooks.mSetAlpha, vs, a);
        return true;
    }

    static float vsF(Object vs, String f) throws Throwable { return Refl.field(vs.getClass(), f).getFloat(vs); }

    /**
     * Player de música: fica logo abaixo do relógio e, ao rolar, para (fixo) quando o relógio chega ao
     * lugar dele; "Notificações" e o X vêm logo abaixo do player. Os cartões que sobem até essa linha
     * viram uma pilha (empilhamento reverso): encolhem, apagam e ficam presos ali.
     */
    static void pile(java.util.List vis, Object ssa) throws Throwable {
        if (Prefs.sheetPlayerFree) {   // player rola com a lista (sem fixar abaixo do relógio)
            if (playerGap != 0) { playerGap = 0; requestStack(); }
            if (titleExtra != 0) { titleExtra = 0; playerView = null; if (decoV != null) decoV.postInvalidateOnAnimation(); }
            return;
        }
        Object player = null, pvs = null;
        for (Object c : vis) {
            if (c.getClass().getSimpleName().contains("MediaHeader")) { player = c; pvs = Refl.call(c, "getViewState"); break; }
        }
        if (player == null || pvs == null || ((View) player).getVisibility() == View.GONE) {
            if (playerGap != 0) { playerGap = 0; requestStack(); }
            if (titleExtra != 0) { titleExtra = 0; playerView = null; if (decoV != null) decoV.postInvalidateOnAnimation(); }
            return;
        }
        float d = ((View) player).getResources().getDisplayMetrics().density;
        float gap = 48 * d;   // faixa do título
        float py = vsF(pvs, "mYTranslation");
        int ph = Refl.field(pvs.getClass(), "height").getInt(pvs);
        int sc = scrollY(), e = Math.round(CurtainClock.e);
        // fixo: depois que o relógio terminou de subir (ou desde o começo, se ele não sobe)
        float pin = py + sc - e;
        float pyShown = Math.max(py, pin);
        GroupStack.call(pvs, "setYTranslation", pyShown);
        java.lang.reflect.Field fcp = Refl.field(pvs.getClass(), "clipTopAmount");
        if (fcp != null) fcp.setInt(pvs, 0);
        // fixo na tela: o sistema o daria por "rolado para fora" e esconderia
        java.lang.reflect.Field fhp = Refl.field(pvs.getClass(), "hidden");
        if (fhp != null) fhp.setBoolean(pvs, false);
        GroupStack.call(pvs, "setAlpha", 1f);
        float line = pyShown + ph + gap;   // começo da área dos cartões (abaixo do título)
        playerView = (View) player;
        if (playerGap != Math.round(gap)) { playerGap = Math.round(gap); requestStack(); }
        titleExtra = ph + gap;
        if (decoV != null) decoV.postInvalidateOnAnimation();
        // pilha reversa: a mesma matemática da pilha de baixo (StackMath), espelhada na linha de cima.
        // As linhas da pilha ficam logo abaixo do título; grupos abertos empilham cartão por cartão.
        if (StackHooks.mSetY == null || StackHooks.fPadding == null) return;
        View host = (View) player.getClass().getMethod("getParent").invoke(player);
        StackMath m = StackHooks.math(host, StackHooks.fPadding.getFloat(ssa));
        float T = line + m.h1 + m.h2;   // onde os cartões começam a empilhar (linhas entre o título e T)
        int firstRow = -1;
        for (int i = 0; i < vis.size(); i++) if (vis.get(i) != player && StackHooks.rowClass != null && StackHooks.rowClass.isInstance(vis.get(i))) { firstRow = i; break; }
        for (int i = 0; i < vis.size(); i++) {
            Object c = vis.get(i);
            if (c == player || !(c instanceof View)) continue;
            View v = (View) c;
            Object vs = StackHooks.mGetViewState.invoke(v);
            if (vs == null) continue;
            float y = StackHooks.fY.getFloat(vs);
            float h = StackHooks.fHeight.getInt(vs);
            boolean first = i == firstRow;
            if (Prefs.stGroups && StackHooks.expandedGroup(v) && !StackHooks.stackedGroup(v)) {
                Object cont = StackHooks.fChildrenContainer.get(v);
                java.util.List kids = cont != null ? (java.util.List) StackHooks.fAttached.get(cont) : null;
                boolean any = false;
                if (kids != null) for (int j = 0; j < kids.size(); j++) {
                    View ch = (View) kids.get(j);
                    Object cvs = StackHooks.mGetViewState.invoke(ch);
                    if (cvs == null) continue;
                    float cy = StackHooks.fY.getFloat(cvs);
                    float chh = StackHooks.fHeight.getInt(cvs);
                    if (topOne(ch, cvs, y + cy, chh, T, m, first && j == 0, -y, -(kids.size() - j))) any = true;
                }
                // cabeçalho do grupo (nome do app, seta): some ao chegar na linha e volta ao descer
                if (cont != null) {
                    float ga = StackMath.clamp((y - (T - m.h1 - m.h2)) / (28f * v.getResources().getDisplayMetrics().density), 0f, 1f);
                    for (String sn : new String[]{"mHeaderViewState", "mTopLineViewState", "mExpandButtonViewState", "mGroupOverFlowState"}) {
                        Object hs = Refl.get(cont, sn);
                        if (hs == null) continue;
                        try {
                            float cur = Refl.field(hs.getClass(), "alpha").getFloat(hs);
                            GroupStack.call(hs, "setAlpha", Math.min(cur, ga));
                        } catch (Throwable ignored) {
                        }
                    }
                    // HyperOS: o cabeçalho visível ("WhatsApp (2)" e a seta) é o do injetor do container
                    Object inj = Refl.get(cont, "mInjector");
                    if (inj != null) for (String sn : new String[]{"mGroupHeaderViewState", "mAppIconViewState"}) {
                        Object hs = Refl.get(inj, sn);
                        if (hs != null) {
                            try {
                                float cur = Refl.field(hs.getClass(), "alpha").getFloat(hs);
                                GroupStack.call(hs, "setAlpha", Math.min(cur, ga));
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    for (Object[] hp : new Object[][]{{cont, "mGroupHeader"}, {cont, "mMinimizedGroupHeader"}, {cont, "mCurrentHeader"}, {inj, "mMiuiGroupHeader"}, {inj, "mAppIcon"}, {inj, "mCollapsedButton"}, {inj, "mGroupInfo"}}) {
                        if (hp[0] == null) continue;
                        Object hv = Refl.get(hp[0], (String) hp[1]);
                        if (hv instanceof View) {
                            if (ga < 1f) { ((View) hv).setAlpha(ga); hdrFaded.put((View) hv, Boolean.TRUE); }
                            else if (hdrFaded.remove(hv) != null) ((View) hv).setAlpha(1f);
                        }
                    }
                }
                // o fundo do grupo é cortado na linha de cima da pilha
                java.lang.reflect.Field fc = StackHooks.fClipTop;
                if (any && fc != null) fc.setInt(vs, Math.max(0, Math.round((T - m.h1 - m.h2) - y)));
                continue;
            }
            topOne(v, vs, y, h, T, m, first, 0f, -(vis.size() - i));
        }
        if (pileLogs++ < 2) ElasticCard.log("cortina: player fixo abaixo do relógio, título abaixo dele (pilha reversa)");
    }

    static void feed(android.view.MotionEvent e) {
        android.view.MotionEvent c = android.view.MotionEvent.obtain(e);
        try {
            c.setLocation(e.getRawX(), e.getRawY());
            touch(c);
        } catch (Throwable t) {
            ElasticCard.log("cortina gesto: " + t);
        } finally {
            c.recycle();
        }
    }

    /** Sem monitor global: o puxar vem da barra de status (lado das notificações) e do launcher. */
    /**
     * HyperOS 4: não há mais startInputFocusTransfer. O puxar da tela inicial (e da barra de status)
     * chega ao NotificationPanelViewControllerInjector.handleExternalTouch, já depois de o HyperOS
     * decidir que é a central de notificações (e não o centro de controle). A cortina assume aqui.
     */
    static void hookExternal(ClassLoader cl) {
        try {
            Class inj = Class.forName("com.android.systemui.shade.NotificationPanelViewControllerInjector", false, cl);
            XposedBridge.hookMethod(inj.getDeclaredMethod("handleExternalTouch", android.view.MotionEvent.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        android.view.MotionEvent e = (android.view.MotionEvent) p.args[0];
                        View v = panel == null ? null : panel.get();
                        int a = e.getActionMasked();
                        if (a == android.view.MotionEvent.ACTION_DOWN) {
                            if (v == null || !Prefs.shadeSheet || locked(v) || on || animating) return;
                            transfer = true;
                            candidate = true;
                            closing = false;
                            if (vt == null) vt = android.view.VelocityTracker.obtain();
                            vt.clear();
                            transferY0 = -1f;
                            begin(v);
                            dragging = true;
                            if (extLogs++ < 4) ElasticCard.log("cortina: puxada por toque externo (HyperOS 4)");
                        }
                        if (!transfer) return;
                        feed(e);
                        lastOurTouch = android.os.SystemClock.uptimeMillis();
                        p.setResult(Boolean.TRUE);
                    } catch (Throwable t) {
                        ElasticCard.log("cortina toque externo: " + t);
                    }
                }
            });
            ElasticCard.log("cortina: toque externo do HyperOS 4 assumido");
        } catch (Throwable t) {
            ElasticCard.log("cortina: tela inicial indisponível: " + t);
        }
    }

    static int extLogs;

    static void hookSources(ClassLoader cl) {
        try {
            final Class sb = Class.forName("com.android.systemui.statusbar.phone.PhoneStatusBarView", false, cl);
            XposedBridge.hookMethod(ViewGroup.class.getDeclaredMethod("dispatchTouchEvent", android.view.MotionEvent.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!sb.isInstance(p.thisObject)) return;
                    android.view.MotionEvent e = (android.view.MotionEvent) p.args[0];
                    View bar = (View) p.thisObject;
                    View v = panel == null ? null : panel.get();
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN)
                        sbTake = Prefs.shadeSheet && v != null && !locked(v) && !on && e.getRawX() < bar.getResources().getDisplayMetrics().widthPixels * 0.5f;
                    if (!sbTake) return;   // lado direito: centro de controle do HyperOS, intocado
                    feed(e);
                    p.setResult(Boolean.TRUE);
                    int a = e.getActionMasked();
                    if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) sbTake = false;
                }
            });
        } catch (Throwable t) {
            ElasticCard.log("cortina: barra de status indisponível: " + t);
        }
        try {
            // o launcher repassa o puxar da tela inicial como eventos da barra de status
            Class lam = Class.forName("com.android.systemui.recents.LauncherProxyService$1$$ExternalSyntheticLambda1", false, cl);
            final java.lang.reflect.Field fid = lam.getDeclaredField("$r8$classId");
            final java.lang.reflect.Field fev = lam.getDeclaredField("f$1");
            fid.setAccessible(true);
            fev.setAccessible(true);
            XposedBridge.hookMethod(lam.getDeclaredMethod("run"), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (fid.getInt(p.thisObject) != 2 || !transfer) return;
                        Object ev = fev.get(p.thisObject);
                        if (ev instanceof android.view.MotionEvent) feed((android.view.MotionEvent) ev);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            ElasticCard.log("cortina: eventos do launcher indisponíveis: " + t);
        }
    }
    static final boolean NO_MONITOR = true;   // o monitor global de toques quebrava o centro de controle do HyperOS

    /**
     * Toque na barra de status com a central fechada: se a cortina ficou marcada como aberta (fechou por
     * outro caminho), os ganchos dela seguravam as animações do HyperOS e o centro de controle não aparecia.
     */
    static void sanity(float x) {
        final View v = panel == null ? null : panel.get();
        if (v == null) return;
        boolean exp = expanded();
        if (sanityLogs++ < 15) ElasticCard.log("cortina barra: x=" + Math.round(x) + " on=" + on + " exp=" + exp + " anim=" + animating + " drag=" + dragging + " transfer=" + transfer + " espera=" + closingWait + " ty=" + Math.round(v.getTranslationY()) + " a=" + v.getAlpha());
        if (exp || animating || dragging || transfer) return;
        if (on || closingWait || v.getTranslationY() != 0f || v.getAlpha() != 1f) {
            ElasticCard.log("cortina: estado preso desfeito");
            closingWait = false;
            if (on) off(v);
            v.setTranslationY(0f);
            v.setAlpha(1f);
        }
    }

    static void pilfer() {
        if (monitor == null) return;   // sem monitor: os toques já chegam só para a cortina
        try {
            Object im = Refl.get(monitor, "mInputMonitor");
            im.getClass().getMethod("pilferPointers").invoke(im);
        } catch (Throwable t) {
            ElasticCard.log("cortina: pilfer " + t);
        }
    }

    static boolean expanded() {
        try {
            return Boolean.TRUE.equals(shadeCtl.getClass().getMethod("isExpandedVisible").invoke(shadeCtl));
        } catch (Throwable t) {
            return false;
        }
    }

    static void touch(android.view.MotionEvent e) {
        final View v = panel == null ? null : panel.get();
        if (v == null || !Prefs.shadeSheet || locked(v)) return;
        float d = v.getResources().getDisplayMetrics().density;
        int act = e.getActionMasked();
        if ((transfer || dragging) && act != android.view.MotionEvent.ACTION_DOWN) lastOurTouch = android.os.SystemClock.uptimeMillis();
        if (act == android.view.MotionEvent.ACTION_DOWN && e.getRawY() < 60f * d && ccLogs++ < 12)
            ElasticCard.log("cortina toque topo: x=" + Math.round(e.getRawX()) + " on=" + on + " exp=" + expanded() + " transfer=" + transfer + " drag=" + dragging + " anim=" + animating);
        if (transfer) {
            if (act == android.view.MotionEvent.ACTION_MOVE) {
                vt.addMovement(e);
                float h = v.getHeight() > 0 ? v.getHeight() : v.getResources().getDisplayMetrics().heightPixels;
                if (transferY0 < 0f) transferY0 = e.getRawY();
                v.setTranslationY(Math.min(0f, -h + 24f * d + Math.max(0f, e.getRawY() - transferY0) * 1.1f));
            } else if (act == android.view.MotionEvent.ACTION_UP || act == android.view.MotionEvent.ACTION_CANCEL) {
                vt.addMovement(e);
                transfer = false;
                candidate = false;
                dragging = false;
                vt.computeCurrentVelocity(1000);
                float vy = vt.getYVelocity(), h = v.getHeight();
                boolean open = act == android.view.MotionEvent.ACTION_UP && (vy > 800f || (vy > -800f && h + v.getTranslationY() > h * 0.35f));
                settle(v, open, vy);
            }
            return;
        }
        if (act == android.view.MotionEvent.ACTION_DOWN) {
            candidate = false;
            closing = false;
            listClose = false;
            sideOk = false;
            if (animating) return;
            downX = e.getRawX();
            downY = e.getRawY();
            sideFired = false;
            sideOk = on && swCtl != null;
            if (on && sideLogs < 6 && !sideOk) { sideLogs++; ElasticCard.log("cortina: deslizar lateral desligado neste toque (cartão embaixo do dedo=" + overCard(downX, downY) + ", controle=" + (swCtl != null) + ")"); }
            int w = v.getResources().getDisplayMetrics().widthPixels, h = v.getResources().getDisplayMetrics().heightPixels;
            boolean open = on && expanded();
            if (!open && !expanded() && downY < 48f * d && downX < w * 0.5f) candidate = true;          // topo, lado das notificações (ou a heads-up)
            else if (open && downY > h - 90f * d && !inLandList(v, downX, downY, d)) { candidate = true; closing = true; }   // barrinha de baixo
            if (candidate) {
                if (vt == null) vt = android.view.VelocityTracker.obtain();
                vt.clear();
                vt.addMovement(e);
            }
            return;
        }
        lastRawY = e.getRawY();
        if (act == android.view.MotionEvent.ACTION_MOVE) sideSwipe(v, e, d);
        if (!candidate) return;
        vt.addMovement(e);
        float dy = e.getRawY() - downY, dx = e.getRawX() - downX;
        if (act == android.view.MotionEvent.ACTION_MOVE) {
            if (!dragging) {
                if (listClose) {
                    // na lista: só fecha se ela já está no fim (senão o dedo rola as notificações)
                    // só um arrasto claramente vertical e longo vira "fechar"; o resto é da lista (rolar, arrastar cartão)
                    if (Math.abs(dy) < 28f * d || Math.abs(dy) < Math.abs(dx) * 2.5f) return;
                    if (dy > 0 || (nssl != null && nssl.canScrollVertically(1))) { candidate = false; listClose = false; return; }
                }
                if (Math.abs(dy) < 10f * d || Math.abs(dy) < Math.abs(dx)) return;
                if (!closing && dy < 0) { candidate = false; return; }
                if (closing && dy > 0) { candidate = false; return; }
                dragging = true;
                pilfer();
                if (anim != null) anim.cancel();
                if (!closing) begin(v);
                startTy = v.getTranslationY() + (listClose ? 0f : 0f);
                if (listClose) downY = e.getRawY();   // a cortina sai daqui, sem pulo
                listClose = false;
            }
            float h = v.getHeight() > 0 ? v.getHeight() : v.getResources().getDisplayMetrics().heightPixels;
            float ty = closing ? Math.min(0f, startTy + dy) : Math.min(0f, -h + e.getRawY() + 24f * d);
            v.setTranslationY(ty);
            return;
        }
        if (act == android.view.MotionEvent.ACTION_UP || act == android.view.MotionEvent.ACTION_CANCEL) {
            candidate = false;
            if (!dragging) return;
            dragging = false;
            vt.computeCurrentVelocity(1000);
            float vy = vt.getYVelocity();
            float h = v.getHeight();
            float shown = h + v.getTranslationY();
            boolean open = act == android.view.MotionEvent.ACTION_UP && (vy > 800f || (vy > -800f && shown > h * 0.35f));
            settle(v, open, vy);
        }
    }

    /** Começo do puxar: a central abre na hora (sem as animações do HyperOS) escondida acima da tela. */
    static void begin(View v) {
        closingWait = false;
        if ((splitTx || splitLp != null) && nssl != null) splitOff(nssl);
        if (v.getAlpha() != 1f) v.setAlpha(1f);
        float h = v.getHeight() > 0 ? v.getHeight() : v.getResources().getDisplayMetrics().heightPixels;
        on = true;
        v.setTranslationY(-h);
        if (!Prefs.sheetBlur) background(v);
        follow(v, 0f, 1f);
        deco(v, true);
        hookPreDraw(v);
        round(v);
        try {
            shadeCtl.getClass().getMethod("instantExpandShade").invoke(shadeCtl);
        } catch (Throwable t) {
            ElasticCard.log("cortina: abrir falhou: " + t);
        }
        if (!Prefs.sheetBlur) hideMiui(v);
        layoutList(v, true);
        if (!logged) {
            logged = true;
            ElasticCard.log("cortina: descendo pelo gesto (fundo " + (Prefs.sheetBlur ? "desfocado" : "do bloqueio") + ")");
        }
    }

    static void settle(final View v, final boolean open, float vy) {
        float from = v.getTranslationY(), to = open ? 0f : -v.getHeight();
        anim = android.animation.ValueAnimator.ofFloat(from, to);
        long dur = (long) Math.max(320, Math.min(560, Math.abs(to - from) / Math.max(2.2f, Math.abs(vy) / 1000f) * 0.75f));
        anim.setDuration(dur);
        anim.setInterpolator(new android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f));   // sai rápido e assenta suave
        animating = true;
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                v.setTranslationY((Float) a.getAnimatedValue());
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;

            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            public void onAnimationEnd(android.animation.Animator a) {
                animating = false;
                if (cancelled) return;
                if (!open) {
                    try {
                        shadeCtl.getClass().getMethod("instantCollapseShade").invoke(shadeCtl);
                    } catch (Throwable t) {
                        ElasticCard.log("cortina: fechar falhou: " + t);
                    }
                    // a central ainda leva alguns quadros para fechar de fato: a cortina fica escondida
                    // lá em cima (e invisível) até o HyperOS terminar, senão a central original aparecia
                    closingWait = true;
                    v.setAlpha(0f);
                    v.postDelayed(new Runnable() {
                        public void run() {
                            if (closingWait) finishClose(v);
                        }
                    }, 900);
                }
            }
        });
        anim.start();
    }

    static boolean closingWait;

    /** Paisagem: a lista desce até embaixo; o toque na coluna dela é da lista (rolar, limpar, tocar). */
    static boolean inLandList(View v, float x, float y, float d) {
        if (!landNow) return false;
        float w = v.getWidth(), h = v.getHeight();
        if (y > h - 22f * d) return false;   // barrinha de baixo continua fechando
        return x >= w * LAND_SPLIT && x <= w - LAND_RIGHT * d;
    }

    // ------------------------------------------------------------------ paisagem: player embaixo do relógio

    static boolean landNow, landMoved, landFwd, landDirty;
    static float landUp;

    /** Desfaz o player movido (voltou ao retrato ou acabaram as notificações). */
    static void resetLand(Object pvs, java.lang.reflect.Method mx) {
        if (!landDirty || pvs == null) return;
        try {
            if (mx == null) mx = VS.find(pvs.getClass(), "setXTranslation");
            VS.inv(mx, pvs, 0f);
            if (StackHooks.mSetScaleX != null) VS.inv(StackHooks.mSetScaleX, pvs, 1f);
            if (StackHooks.mSetScaleY != null) VS.inv(StackHooks.mSetScaleY, pvs, 1f);
            landDirty = false;
        } catch (Throwable ignored) {
        }
    }

    static void resetLandFrom(Object ssa) {
        if (!landDirty) return;
        try {
            Object st = StackHooks.fTempState.get(ssa);
            Object vis = st == null ? null : StackHooks.fVisible.get(st);
            if (!(vis instanceof java.util.List)) return;
            for (Object c : (java.util.List) vis)
                if (c.getClass().getSimpleName().contains("MediaHeader")) resetLand(Refl.call(c, "getViewState"), null);
        } catch (Throwable ignored) {
        }
    }
    static int landLogs2;

    /**
     * Paisagem com o player e notificações: o player vai para a coluna do relógio (embaixo dele,
     * reduzido se precisar, sem cobrir a lanterna) e as notificações sobem na coluna da direita.
     * Sem notificações, o player fica onde o HyperOS o põe.
     */
    static View landHidden, pickHidden;
    static java.lang.reflect.Field fViewFlags;

    /**
     * Antes do HyperOS calcular a lista em paisagem: o player conta como "fora" (GONE só nas flags, sem
     * layout) e as notificações ficam no lugar dele de verdade, rolagem e cortes do próprio sistema.
     */
    static boolean splitNow, splitTx;

    static boolean splitWant;
    static long splitOkAt;
    static int landVisBits;

    static void landHide(Object ssa, Object amb) {
        landHidden = null;
        splitNow = false;
        splitWant = false;
        try {
            if (StackHooks.fHost == null) return;
            ViewGroup host = (ViewGroup) StackHooks.fHost.get(ssa);
            if (host == null) return;
            boolean curtain = on && landNow;
            // medidas originais da lista em retrato (para voltar certo depois da paisagem)
            View rt = host.getRootView();
            // (o HyperOS reaplica as medidas dele ao girar: se a largura não é mais a nossa, é a original do retrato)
            if (rt != null && rt.getWidth() < rt.getHeight() && host.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                    && (splitLp == null || ((ViewGroup.MarginLayoutParams) host.getLayoutParams()).width != splitColW)) {
                ViewGroup.MarginLayoutParams ml = (ViewGroup.MarginLayoutParams) host.getLayoutParams();
                int g = ml instanceof android.widget.FrameLayout.LayoutParams ? ((android.widget.FrameLayout.LayoutParams) ml).gravity : -1;
                portLp = new int[]{ml.width, ml.leftMargin, ml.rightMargin, g};
            }
            if (!curtain) {
                // gaveta padrão: paisagem, fora do bloqueio, com a opção ligada
                View root = host.getRootView();
                if (on || !Prefs.landSplit || root == null || root.getWidth() <= root.getHeight()) return;
                if (Boolean.TRUE.equals(Refl.call(amb, "isOnKeyguard"))) return;
                float d = host.getResources().getDisplayMetrics().density;
            }
            View player = null;
            boolean rows = false;
            for (int i = 0; i < host.getChildCount(); i++) {
                View c = host.getChildAt(i);
                boolean media = c.getClass().getSimpleName().contains("MediaHeader");
                // o player que nós mesmos deixamos sem alfa pode ter ficado INVISIBLE: continua sendo o player
                if (c.getVisibility() != View.VISIBLE && !(media && landDirty && c.getVisibility() == View.INVISIBLE)) continue;
                if (media) player = c;
                else if (StackHooks.rowClass != null && StackHooks.rowClass.isInstance(c)) rows = true;
            }
            if (player == null || !rows) {
                // uma passada sem o player/cartões (rolando, animação de abrir) não desfaz a divisão
                if (!curtain && android.os.SystemClock.uptimeMillis() - splitOkAt < 700) splitWant = true;
                return;
            }
            if (!curtain) splitOkAt = android.os.SystemClock.uptimeMillis();
            if (!curtain) {
                View root = host.getRootView();
                float d = host.getResources().getDisplayMetrics().density;
                splitWant = true;
                if (splitLogs++ < 3) ElasticCard.log("gaveta paisagem: raiz " + root.getWidth() + "x" + root.getHeight() + ", lista " + host.getWidth() + "px em x=" + host.getLeft());
                if (!splitLayout(host, root.getWidth(), d)) return;   // coluna ainda sendo montada
            }
            if (fViewFlags == null) { fViewFlags = View.class.getDeclaredField("mViewFlags"); fViewFlags.setAccessible(true); }
            int f = fViewFlags.getInt(player);
            landVisBits = f & 0x0C;
            fViewFlags.setInt(player, (f & ~0x0C) | 0x08);   // GONE
            landHidden = player;
            splitNow = !curtain;
        } catch (Throwable t) {
            landHidden = null;
            if (landLogs2++ < 4) ElasticCard.log("cortina paisagem: tirar o player da lista: " + t);
        }
    }

    static int splitLogs;
    static int[] splitLp, portLp;
    static int splitColW = -99;

    /** Retrato sem medida guardada: ocupa o que o pai der (0 = "pelas âncoras" no ConstraintLayout). */
    static int lp0Width(View host) {
        return host.getParent() != null && host.getParent().getClass().getName().contains("Constraint")
                || (host.getParent() != null && host.getParent().getClass().getSuperclass() != null && host.getParent().getClass().getSuperclass().getName().contains("Constraint"))
                ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
    }
    static int chainLogs;   // largura, margem esquerda, gravidade originais da lista (gaveta padrão)

    /**
     * Gaveta padrão em paisagem: a lista vira a coluna da direita. Se ela ocupa a tela toda (cartões
     * com margem), a largura dela passa a ~45% da tela, encostada no X de limpar.
     */
    static boolean splitLayout(View host, int W, float d) {
        try {
            int colW = Math.round(W * 0.45f);
            // já cabe em duas colunas (ou já foi encolhida): segue; senão encolhe para ~45% da tela
            int wantL = Math.round(W - 112 * d - colW), wantR = Math.round(112 * d);
            ViewGroup.LayoutParams lp0 = host.getLayoutParams();
            boolean ours = lp0 instanceof ViewGroup.MarginLayoutParams && lp0.width == colW
                    && ((ViewGroup.MarginLayoutParams) lp0).leftMargin == wantL && ((ViewGroup.MarginLayoutParams) lp0).rightMargin == wantR;
            // já é a nossa coluna (no lugar pelas margens, como na cortina: deslocar a lista cortava o player)
            if (ours && Math.abs(host.getWidth() - colW) <= 2) return true;
            if (!ours && host.getWidth() <= colW + 2 && splitLp == null) return W - 112 * d - host.getWidth() >= W * 0.35f;
            if (!(host.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return false;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) host.getLayoutParams();
            if (splitLp == null) {
                int g = lp instanceof android.widget.FrameLayout.LayoutParams ? ((android.widget.FrameLayout.LayoutParams) lp).gravity : -1;
                splitLp = new int[]{lp.width, lp.leftMargin, lp.rightMargin, g};
            }
            lp.width = colW;
            splitColW = colW;
            lp.leftMargin = wantL;
            lp.rightMargin = wantR;
            if (lp instanceof android.widget.FrameLayout.LayoutParams) ((android.widget.FrameLayout.LayoutParams) lp).gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
            host.setLayoutParams(lp);
            if (splitLogs++ < 4) ElasticCard.log("gaveta paisagem: lista de " + host.getWidth() + "px vira coluna de " + colW + "px à direita");
            return false;   // a largura nova vale na próxima passada
        } catch (Throwable t) {
            if (splitLogs++ < 6) ElasticCard.log("gaveta paisagem: coluna: " + t);
            return false;
        }
    }

    /** Desfaz a lista deslocada da gaveta padrão. */
    /** Área (janela) em que os pais da lista deixam desenhar: o menor retângulo dos que cortam os filhos. */
    static float[] clipArea(View host) {
        float l = -1e9f, t = -1e9f, r = 1e9f, b = 1e9f;
        boolean any = false;
        int[] p = new int[2];
        for (android.view.ViewParent vp = host.getParent(); vp instanceof ViewGroup; vp = vp.getParent()) {
            ViewGroup g = (ViewGroup) vp;
            if (g.getParent() == null) break;   // a janela inteira não conta
            // corta quem corta os filhos, quem corta pelo contorno (cantos do painel) ou tem recorte próprio
            android.graphics.Rect cb = g.getClipBounds();
            if (!g.getClipChildren() && !g.getClipToOutline() && cb == null) continue;
            g.getLocationInWindow(p);
            float gl = p[0], gt = p[1], gr = p[0] + g.getWidth(), gb = p[1] + g.getHeight();
            if (cb != null) { gl = p[0] + cb.left; gt = p[1] + cb.top; gr = p[0] + cb.right; gb = p[1] + cb.bottom; }
            l = Math.max(l, gl);
            t = Math.max(t, gt);
            r = Math.min(r, gr);
            b = Math.min(b, gb);
            any = true;
        }
        if (chainLogs++ < 1) {
            StringBuilder sb = new StringBuilder("gaveta paisagem: pais da lista:");
            for (android.view.ViewParent vp = host.getParent(); vp instanceof ViewGroup; vp = vp.getParent()) {
                ViewGroup g = (ViewGroup) vp;
                g.getLocationInWindow(p);
                sb.append(" | ").append(g.getClass().getSimpleName()).append(' ').append(p[0]).append('+').append(g.getWidth())
                        .append(g.getClipChildren() ? " clipF" : "").append(g.getClipToOutline() ? " clipO" : "").append(g.getClipBounds() != null ? " clipB" + g.getClipBounds() : "");
            }
            ElasticCard.log(sb.toString());
        }
        if (any && splitLogs++ < 6) ElasticCard.log("gaveta paisagem: área que não corta " + Math.round(l) + "," + Math.round(t) + "–" + Math.round(r) + "," + Math.round(b));
        return any ? new float[]{l, t, r, b} : null;
    }

    static void splitOff(View host) {
        splitTx = false;
        LandMini.hide();
        if (host != null && host.getTranslationX() != 0f) host.setTranslationX(0f);
        if (host != null && splitLp != null && host.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            // voltou ao retrato: as medidas do retrato (as da paisagem deixavam a lista de ponta a ponta)
            View rt = host.getRootView();
            boolean port = rt != null && rt.getWidth() < rt.getHeight();
            int[] o = port && portLp != null ? portLp : splitLp;
            if (port && portLp == null) o = new int[]{lp0Width(host), splitLp[1], splitLp[2], splitLp[3]};
            if (splitLogs++ < 12) ElasticCard.log("gaveta paisagem: lista volta a " + o[0] + "px (margens " + o[1] + "/" + o[2] + ")" + (port ? " no retrato" : ""));
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) host.getLayoutParams();
            lp.width = o[0];
            lp.leftMargin = o[1];
            lp.rightMargin = o[2];
            if (o[3] != -1 && lp instanceof android.widget.FrameLayout.LayoutParams) ((android.widget.FrameLayout.LayoutParams) lp).gravity = o[3];
            host.setLayoutParams(lp);
        }
        splitLp = null;
    }

    static void landRestore() {
        View v = landHidden;
        if (v == null) return;
        try {
            int f = fViewFlags.getInt(v);
            fViewFlags.setInt(v, (f & ~0x0C) | landVisBits);   // como estava
        } catch (Throwable ignored) {
        }
    }

    /**
     * Paisagem com o player e notificações: o player vai para a coluna do relógio (embaixo dele,
     * reduzido se precisar) e as notificações ocupam a coluna da direita desde o topo.
     * Sem notificações, o player fica onde o HyperOS o põe.
     */
    static void landPlayer(Object ssa, Object amb) {
        landMoved = false;
        View pv = landHidden;
        landHidden = null;
        Object pvs = null;
        java.lang.reflect.Method mx = null;
        try {
            if (pv == null && !splitWant && (splitTx || splitLp != null) && StackHooks.fHost != null) {
                // gaveta fechando não desfaz (senão ao puxar de novo ela aparecia no meio antes de dividir)
                View hv = (View) StackHooks.fHost.get(ssa);
                View root = hv == null ? null : hv.getRootView();
                boolean land = root != null && root.getWidth() > root.getHeight();
                if (!land || !Prefs.landSplit || on || expanded()) splitOff(hv);
            }
            if (pv == null && splitWant) return;   // passada sem o player: mantém tudo como está
            if (pv == null) LandMini.hide();
            if (pv == null) {   // sem player fora da lista: desfaz o que tinha sido movido
                if (landDirty && StackHooks.fHost != null) {
                    ViewGroup host = (ViewGroup) StackHooks.fHost.get(ssa);
                    for (int i = 0; host != null && i < host.getChildCount(); i++) {
                        View c = host.getChildAt(i);
                        if (c.getClass().getSimpleName().contains("MediaHeader")) resetLand(Refl.call(c, "getViewState"), null);
                    }
                }
                return;
            }
            View host = (View) StackHooks.fHost.get(ssa);
            pvs = Refl.call(pv, "getViewState");
            if (host == null || pvs == null || StackHooks.mSetY == null || (!splitNow && decoV == null)) return;
            mx = VS.find(pvs.getClass(), "setXTranslation");
            float d = host.getResources().getDisplayMetrics().density;
            int ph = pv.getHeight(), pw = pv.getWidth();
            try { Object ih = Refl.call(pv, "getIntrinsicHeight"); if (ih instanceof Integer && ((Integer) ih).intValue() > 0) ph = ((Integer) ih).intValue(); } catch (Throwable ignored) {}
            if (ph <= 0 || pw <= 0) return;
            int[] hl = new int[2], dl = new int[2];
            host.getLocationInWindow(hl);
            float x0, x1, y0, y1;
            if (splitNow) {
                // gaveta padrão: a lista vai para a direita (antes do X de limpar) e o player fica à esquerda
                View root = host.getRootView();
                float W = root.getWidth(), H = root.getHeight();
                float baseLeft = hl[0] - host.getTranslationX();
                float newLeft = W - 112 * d - host.getWidth();
                if (newLeft < W * 0.4f) { splitOff(host); return; }   // lista larga demais para duas colunas
                float dx = newLeft - baseLeft;
                if (Math.abs(host.getTranslationX() - dx) > 0.5f) host.setTranslationX(dx);
                splitTx = true;
                hl[0] = Math.round(newLeft);
                float topPad = 0f;
                try { if (StackHooks.fTopPadding != null && amb != null) topPad = StackHooks.fTopPadding.getInt(amb); } catch (Throwable ignored) {}
                x0 = 24 * d;
                x1 = newLeft - 24 * d;
                y0 = hl[1] + topPad;
                y1 = H - 24 * d;
                // os painéis acima da lista cortam o que sai deles: o player fica dentro dessa área
                float[] cl = clipArea(host);
                if (cl != null) {
                    float cx0 = Math.max(x0, cl[0] + 12 * d), cy0 = Math.max(y0, cl[1]), cy1 = Math.min(y1, cl[3] - 12 * d);
                    // só vale se ainda sobrar espaço de verdade (senão o player sumia)
                    if (Math.min((x1 - cx0) / pw, (cy1 - cy0) / ph) >= 0.45f) { x0 = cx0; y0 = cy0; y1 = cy1; }
                    else if (splitLogs++ < 8) ElasticCard.log("gaveta paisagem: área sem corte pequena demais, ignorada");
                }
            } else {
                decoV.getLocationInWindow(dl);
                float colL = decoV.getWidth() * LAND_SPLIT;
                x0 = dl[0] + 16 * d;
                x1 = dl[0] + colL - 16 * d;   // lanterna foi para a direita
                y0 = dl[1] + decoV.landClockBot + 14 * d;
                y1 = dl[1] + decoV.getHeight() - 14 * d;
            }
            float s = Math.min(1f, Math.min((x1 - x0) / pw, (y1 - y0) / ph));
            if (s < 0.4f) {
                if (!splitNow) return;
                s = 0.4f;   // gaveta padrão: o player já saiu da lista; nunca fica sem aparecer
            }
            if (splitNow && splitLogs++ < 10) ElasticCard.log("gaveta paisagem: player em " + Math.round(x0) + "–" + Math.round(x1) + " x " + Math.round(y0) + "–" + Math.round(y1) + " (" + Math.round(s * 100) + "%)");
            float vw = pw * s, vh = ph * s;
            float X = x0 + (x1 - x0 - vw) / 2f - hl[0], Y = y0 + Math.max(0f, (y1 - y0 - vh) / 2f) - hl[1];
            float pivX = pv.getPivotX(), pivY = pv.getPivotY();
            Refl.field(pvs.getClass(), "height").setInt(pvs, ph);
            // com o player compacto, o do HyperOS sai da tela (a animação de abrir mexe no alfa dele)
            VS.inv(mx, pvs, X - pivX * (1f - s) - pv.getLeft());
            VS.inv(StackHooks.mSetY, pvs, Y - pivY * (1f - s));
            if (StackHooks.mSetScaleX != null) VS.inv(StackHooks.mSetScaleX, pvs, s);
            if (StackHooks.mSetScaleY != null) VS.inv(StackHooks.mSetScaleY, pvs, s);
            // gaveta padrão com o player compacto: o do HyperOS fica invisível e o nosso aparece no lugar
            boolean mini = false;   // o player compacto ficou de fora: vale o player original do HyperOS
            // (alfa 0 de verdade faria o HyperOS deixar o player INVISIBLE e a próxima passada não o acharia)
            if (StackHooks.mSetAlpha != null) VS.inv(StackHooks.mSetAlpha, pvs, 1f);   // fora da tela: serve de referência da animação
            if (mini) {
                if (StackHooks.mSetScaleX != null) VS.inv(StackHooks.mSetScaleX, pvs, 1f);
                if (StackHooks.mSetScaleY != null) VS.inv(StackHooks.mSetScaleY, pvs, 1f);
                LandMini.refY = vsF(pvs, "mYTranslation");
                LandMini.ref = new java.lang.ref.WeakReference<>(pv);
                LandMini.hostRef = new java.lang.ref.WeakReference<>(host);
                float aw = x1 - x0, ah = y1 - y0;
                float cw = Math.min(aw, ah * 320f / 340f), ch = cw * 340f / 320f;
                int[] rl = new int[2];
                host.getRootView().getLocationInWindow(rl);
                LandMini.show(host.getRootView(), x0 + (aw - cw) / 2f - rl[0], y0 + Math.max(0f, (ah - ch) / 2f) - rl[1], cw, ch);
            } else LandMini.hide();
            java.lang.reflect.Field fc = Refl.field(pvs.getClass(), "clipTopAmount");
            if (fc != null) fc.setInt(pvs, 0);
            java.lang.reflect.Field fh = Refl.field(pvs.getClass(), "hidden");
            if (fh != null) fh.setBoolean(pvs, false);
            java.lang.reflect.Field fs = Refl.field(pvs.getClass(), "inShelf");
            if (fs != null) fs.setBoolean(pvs, false);
            java.lang.reflect.Field fg = Refl.field(pvs.getClass(), "gone");   // lido com as flags de "fora"
            if (fg != null) fg.setBoolean(pvs, false);
            float pad = StackHooks.fPadding != null ? StackHooks.fPadding.getFloat(ssa) : 0f;
            landUp = ph + pad;
            landMoved = true;
            landDirty = true;
            playerView = pv;
            if (landLogs2++ < 2) ElasticCard.log("cortina paisagem: player embaixo do relógio (" + Math.round(s * 100) + "%), fora da lista; notificações à direita");
        } catch (Throwable t) {
            if (landLogs2++ < 4) ElasticCard.log("cortina paisagem: player: " + t);
        }
    }

    /** O player movido fica fora da lista: o toque nele é entregue direto a ele. */
    static boolean forwardLand(android.view.MotionEvent me) {
        try {
            int a = me.getActionMasked();
            View pv = playerView;
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                landFwd = false;
                if (!landMoved || pv == null || !pv.isShown() || LandMini.showing()) return false;
                int[] l = new int[2];
                pv.getLocationOnScreen(l);
                float w = pv.getWidth() * pv.getScaleX(), h = pv.getHeight() * pv.getScaleY();
                if (me.getRawX() < l[0] || me.getRawX() > l[0] + w || me.getRawY() < l[1] || me.getRawY() > l[1] + h) return false;
                landFwd = true;
            }
            if (!landFwd || pv == null) return false;
            int[] l = new int[2];
            pv.getLocationOnScreen(l);
            android.view.MotionEvent c = android.view.MotionEvent.obtain(me);
            c.setLocation((me.getRawX() - l[0]) / Math.max(0.01f, pv.getScaleX()), (me.getRawY() - l[1]) / Math.max(0.01f, pv.getScaleY()));
            try { pv.dispatchTouchEvent(c); } finally { c.recycle(); }
            if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) landFwd = false;
            return true;
        } catch (Throwable t) {
            landFwd = false;
            return false;
        }
    }

    /** Cortina: o cartão que chega na lanterna/câmera vai apagando (o resto é cortado no desenho da lista). */
    static void fadeBottom(Object ssa) {
        try {
            if (StackHooks.fHost == null || StackHooks.fTempState == null || StackHooks.fVisible == null) return;
            View host = (View) StackHooks.fHost.get(ssa);
            Object st = StackHooks.fTempState.get(ssa);
            Object vis = st == null ? null : StackHooks.fVisible.get(st);
            if (host == null || !(vis instanceof java.util.List)) return;
            float lim = buttonLimit(host);
            if (Float.isNaN(lim)) return;
            float d = host.getResources().getDisplayMetrics().density;
            lim += 10 * d;
            for (Object c : (java.util.List) vis) {
                Object vs = Refl.call(c, "getViewState");
                if (vs == null) continue;
                float y = vsF(vs, "mYTranslation");
                int h = Refl.field(vs.getClass(), "height").getInt(vs);
                if (h <= 0 || y + h <= lim) continue;
                float o = y + h - lim;
                float k = StackMath.clamp(1f - o / (h * 0.55f), 0f, 1f);
                float a = Refl.field(vs.getClass(), "mAlpha").getFloat(vs);
                GroupStack.call(vs, "setAlpha", a * k);
                if (k <= 0f) {
                    java.lang.reflect.Field fh = Refl.field(vs.getClass(), "hidden");
                    if (fh != null) fh.setBoolean(vs, true);
                }
            }
        } catch (Throwable t) {
            if (fadeLogs++ < 4) ElasticCard.log("cortina: apagar no fim: " + t);
        }
    }

    /** Topo dos botões de baixo da cortina (com folga), nas coordenadas da lista; NaN sem botões. */
    static float buttonLimit(View host) {
        try {
            SheetDeco dv = decoV;
            if (landNow) return Float.NaN;   // paisagem: os botões ficam fora da coluna das notificações
            if (dv == null || dv.btnY <= 0 || dv.getParent() == null || host == null) return Float.NaN;
            float d = host.getResources().getDisplayMetrics().density;
            int[] hl = new int[2], dl = new int[2];
            host.getLocationInWindow(hl);
            dv.getLocationInWindow(dl);
            return dl[1] + dv.btnY - 28f * d - 14f * d - hl[1];
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    // ------------------------------------------------------------------ troca lateral (centro de controle <-> cortina)

    static Object swCtl;
    static boolean switchingNow;
    static float swX;
    static int swLogs2;

    static boolean huPull, passed;
    static int huState;   // 0 nada, 1 dedo na heads-up (o sistema ainda recebe), 2 a cortina está descendo
    static float huX0, huY0;
    static android.view.VelocityTracker huVt;

    /**
     * Puxar a cortina começando em cima de uma heads-up (só a cortina do iOS). Independente do resto
     * dos gestos: o dedo desce na heads-up, o sistema continua recebendo até ficar claro que é um
     * puxar para baixo; aí o sistema ganha um "cancelar", a cortina abre escondida no alto e segue o
     * dedo; ao soltar, abre ou volta como no puxar pela barra de status.
     */
    static boolean huGesture(XC_MethodHook.MethodHookParam p, android.view.MotionEvent me) {
        View root = (View) p.thisObject;
        View v = panel == null ? null : panel.get();
        int a = me.getActionMasked();
        float d = root.getResources().getDisplayMetrics().density;
        if (a == android.view.MotionEvent.ACTION_DOWN) {
            huState = 0;
            if (v != null && !on && !dragging && !transfer && !animating && Prefs.shadeSheet && !locked(v) && hunHit(root, me.getRawY())) {
                huState = 1;
                huX0 = me.getRawX();
                huY0 = me.getRawY();
                if (huVt == null) huVt = android.view.VelocityTracker.obtain();
                huVt.clear();
                huVt.addMovement(me);
            }
            return false;
        }
        if (huState == 0 || v == null) return false;
        huVt.addMovement(me);
        float dy = me.getRawY() - huY0, dx = me.getRawX() - huX0;
        if (huState == 1) {
            if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) { huState = 0; return false; }
            if (a != android.view.MotionEvent.ACTION_MOVE) return false;
            if (dy < 8 * d || dy < Math.abs(dx) * 1.2f) {
                if (dy < -8 * d || Math.abs(dx) > 16 * d) huState = 0;   // para cima ou para o lado: é da heads-up
                return false;
            }
            huState = 2;
            dragging = true;
            closing = false;
            candidate = false;
            begin(v);
            lastOurTouch = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent c = android.view.MotionEvent.obtain(me);
            c.setAction(android.view.MotionEvent.ACTION_CANCEL);
            p.args[0] = c;   // o sistema larga a heads-up
            if (huLogs++ < 6) ElasticCard.log("cortina: puxada por cima da heads-up");
            return true;
        }
        // huState == 2: a cortina segue o dedo
        lastOurTouch = android.os.SystemClock.uptimeMillis();
        float h = v.getHeight() > 0 ? v.getHeight() : v.getResources().getDisplayMetrics().heightPixels;
        if (a == android.view.MotionEvent.ACTION_MOVE) {
            v.setTranslationY(Math.min(0f, -h + 24f * d + Math.max(0f, dy) * 1.1f + huY0));
        } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
            huState = 0;
            dragging = false;
            huVt.computeCurrentVelocity(1000);
            float vy = huVt.getYVelocity();
            boolean open = a == android.view.MotionEvent.ACTION_UP && (vy > 800f || (vy > -800f && h + v.getTranslationY() > h * 0.35f));
            settle(v, open, vy);
        }
        p.setResult(Boolean.TRUE);
        return true;
    }
    static int huLogs;

    /** O dedo desceu em cima (ou logo acima) de uma heads-up fixada no topo. */
    static Object hum;   // HeadsUpManagerImpl (HyperOS 4)

    static boolean hunHit(View root, float y) {
        try {
            float d0 = root.getResources().getDisplayMetrics().density;
            if (hum != null) {
                // a heads-up que está no topo, pelo próprio gerenciador do sistema (funciona com grupos)
                Object e = Refl.call(hum, "getTopEntry");
                if (e == null) return false;
                Object r = Refl.get(e, "row");
                if (r == null) r = Refl.call(e, "getRow");
                boolean hit = true;
                if (r instanceof View && ((View) r).isShown()) {
                    int[] a = new int[2];
                    ((View) r).getLocationOnScreen(a);
                    int h = ((View) r).getHeight();
                    Object ah = Refl.call(r, "getActualHeight");
                    if (ah instanceof Integer && (Integer) ah > 0) h = (Integer) ah;
                    hit = y <= a[1] + h + 16 * d0;
                } else hit = y <= 160 * d0;
                if (huLogs < 6) { huLogs++; ElasticCard.log("cortina: toque com heads-up na tela (y=" + Math.round(y) + ", em cima dela=" + hit + ")"); }
                return hit;
            }
            View ns = nssl != null ? nssl : findCls(root, "NotificationStackScrollLayout", 0);
            if (!(ns instanceof ViewGroup)) return false;
            if (nssl == null) nssl = ns;
            ViewGroup g = (ViewGroup) ns;
            float d = root.getResources().getDisplayMetrics().density;
            int[] a = new int[2];
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c.getVisibility() != View.VISIBLE || !Boolean.TRUE.equals(Refl.call(c, "isPinned"))) continue;
                c.getLocationOnScreen(a);
                int h = c.getHeight();
                Object ah = Refl.call(c, "getActualHeight");
                if (ah instanceof Integer && (Integer) ah > 0) h = (Integer) ah;
                if (y <= a[1] + h + 16 * d) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static boolean sideFired, sideOk;
    static int sideLogs;

    /**
     * HyperOS 4: deslizar para o lado na cortina leva ao centro de controle. Se o HyperOS não começar a
     * troca sozinho (a cortina não é o painel dele), a troca é pedida aqui, como o botão do cabeçalho faz.
     */
    static void sideSwipe(View v, android.view.MotionEvent e, float d) {
        if (!sideOk || sideFired || dragging || candidate && closing && Math.abs(e.getRawY() - downY) > 10 * d) return;
        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
        boolean rtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        if (Math.abs(dx) < 56 * d || Math.abs(dx) < Math.abs(dy) * 2f) return;   // para qualquer lado: o único vizinho é o centro de controle
        // um cartão sendo arrastado para o lado (dispensar): o gesto é dele
        if (cardSwiping(d)) { sideFired = true; return; }
        sideFired = true;
        if (swBool("getSwitching")) return;   // o HyperOS já está trocando com o dedo
        boolean can = swBool("isSwitchable");
        if (sideLogs++ < 6) ElasticCard.log("cortina: deslizar para o centro de controle (troca do sistema " + (can ? "liberada" : "bloqueada") + ")");
        try {
            swCtl.getClass().getMethod("switchToControlCenter").invoke(swCtl);
        } catch (Throwable t) {
            if (sideLogs++ < 6) ElasticCard.log("cortina: troca para o centro de controle falhou: " + t);
        }
    }

    static boolean cardSwiping(float d) {
        try {
            if (!(nssl instanceof ViewGroup)) return false;
            ViewGroup g = (ViewGroup) nssl;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (Math.abs(c.getTranslationX()) > 4 * d) return true;
                Object kids = Refl.call(c, "getAttachedChildren");
                if (kids instanceof java.util.List) for (Object k : (java.util.List) kids)
                    if (k instanceof View && Math.abs(((View) k).getTranslationX()) > 4 * d) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** O dedo começou em cima de um cartão de notificação (deslizar ali é para dispensar o cartão). */
    static boolean overCard(float x, float y) {
        try {
            if (!(nssl instanceof ViewGroup) || !nssl.isShown()) return false;
            ViewGroup g = (ViewGroup) nssl;
            int[] a = new int[2];
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                // só cartões de verdade (não rodapé, prateleira, cabeçalhos), pela parte que aparece
                if (!c.getClass().getName().endsWith("ExpandableNotificationRow")) continue;
                if (c.getVisibility() != View.VISIBLE || c.getAlpha() < 0.05f || c.getHeight() <= 0) continue;
                c.getLocationOnScreen(a);
                int h = c.getHeight();
                Object ah = Refl.call(c, "getActualHeight");
                if (ah instanceof Integer && (Integer) ah > 0) h = (Integer) ah;
                Object ct = Refl.call(c, "getClipTopAmount"), cb = Refl.call(c, "getClipBottomAmount");
                int top = a[1] + (ct instanceof Integer ? (Integer) ct : 0);
                int bot = a[1] + h - (cb instanceof Integer ? (Integer) cb : 0);
                if (x >= a[0] && x <= a[0] + c.getWidth() && y >= top && y <= bot) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static boolean swBool(String m) {
        try { return Boolean.TRUE.equals(swCtl.getClass().getMethod(m).invoke(swCtl)); } catch (Throwable t) { return false; }
    }

    static float swProgress() {
        try { return ((Number) swCtl.getClass().getMethod("getSwitchProgress").invoke(swCtl)).floatValue(); } catch (Throwable t) { return 1f; }
    }

    /**
     * HyperOS 4: o deslizar para o lado entre o centro de controle e as notificações. Com a cortina, quem
     * entra/sai deslizando é ela (relógio, título, botões, fundo), acompanhando o painel do HyperOS.
     */
    static void hookSwitch(ClassLoader cl) throws Throwable {
        Class sw = Class.forName("com.miui.systemui.shade.ShadeSwitchControllerImpl", false, cl);
        for (java.lang.reflect.Constructor k : sw.getDeclaredConstructors()) {
            XposedBridge.hookMethod(k, new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    swCtl = p.thisObject;
                }
            });
        }
        XposedBridge.hookMethod(sw.getDeclaredMethod("isSwitchable"), new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                swCtl = p.thisObject;
                View v = panel == null ? null : panel.get();
                if (v == null || !Prefs.shadeSheet || locked(v)) return;
                // no meio do nosso gesto (puxar/fechar a cortina) não troca
                if (dragging || animating || transfer || closingWait) p.setResult(Boolean.FALSE);
            }
        });
        final java.lang.reflect.Field fSw = Refl.field(sw, "switching");
        XposedBridge.hookMethod(sw.getDeclaredMethod("setSwitching", boolean.class), new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    swCtl = p.thisObject;
                    boolean z = Boolean.TRUE.equals(p.args[0]);
                    if (fSw != null && fSw.getBoolean(p.thisObject) == z) return;
                    View v = panel == null ? null : panel.get();
                    if (!z || v == null || !Prefs.shadeSheet || locked(v)) return;
                    switchingNow = true;
                    boolean cc = swBool("getControlCenterExpanded"), nt = swBool("getNotificationExpanded");
                    if (!on && cc && !nt) {
                        enterInPlace(v);
                        if (swLogs2++ < 4) ElasticCard.log("cortina: entrando de lado pelo centro de controle");
                    } else if (on && swLogs2++ < 4) ElasticCard.log("cortina: saindo de lado para o centro de controle");
                } catch (Throwable t) {
                    ElasticCard.log("cortina troca: " + t);
                }
            }

            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (Boolean.TRUE.equals(p.args[0]) || !switchingNow) return;
                    switchingNow = false;
                    final View v = panel == null ? null : panel.get();
                    if (v == null || !on) return;
                    follow(v, 0f, 1f);
                    if (!swBool("getNotificationExpanded")) off(v);   // terminou no centro de controle
                } catch (Throwable t) {
                    ElasticCard.log("cortina troca fim: " + t);
                }
            }
        });
        Class nw = Class.forName("com.miui.systemui.shade.ShadeSwitchControllerImpl$notification$1", false, cl);
        final java.lang.reflect.Field fId = Refl.field(nw, "$r8$classId");
        final java.lang.reflect.Field fShade = Refl.field(nw, "shade");
        XposedBridge.hookMethod(nw.getDeclaredMethod("updateTransX"), new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (!on || !switchingNow || fId.getInt(p.thisObject) != 0) return;
                    Object sh = fShade.get(p.thisObject);
                    View c = (View) sh.getClass().getMethod("getContainer").invoke(sh);
                    View v = panel == null ? null : panel.get();
                    if (v != null && c != null) follow(v, c.getTranslationX(), swProgress());
                } catch (Throwable t) {
                    if (swLogs2++ < 6) ElasticCard.log("cortina troca: " + t);
                }
            }
        });
        ElasticCard.log("cortina: troca lateral com o centro de controle acompanhada");
    }

    /** A cortina aparece no lugar (sem descer): a troca lateral do HyperOS já abriu a central. */
    static void enterInPlace(View v) {
        closingWait = false;
        if (anim != null) anim.cancel();
        if (v.getAlpha() != 1f) v.setAlpha(1f);
        on = true;
        v.setTranslationY(0f);
        if (!Prefs.sheetBlur) background(v);
        deco(v, true);
        hookPreDraw(v);
        round(v);
        if (!Prefs.sheetBlur) hideMiui(v);
        layoutList(v, true);
        follow(v, -v.getWidth(), 0f);
    }

    /** Desloca para o lado tudo o que a cortina desenha; p = 1 na cortina, 0 no centro de controle. */
    static void follow(View v, float tx, float p) {
        swX = tx;
        p = Math.max(0f, Math.min(1f, p));
        for (View x : new View[]{decoV, CurtainClock.box, CurtainClock.boxN, band, Kg.box, Kg.hbox}) {
            if (x != null && x.getParent() == v && x.getTranslationX() != tx) x.setTranslationX(tx);
        }
        if (bgDrawable != null && v.getBackground() == bgDrawable) bgDrawable.setAlpha(Math.round(255 * p));
        if (!Prefs.sheetBlur) for (View x : hidden) x.setAlpha(switchingNow ? 1f - p : 0f);
        if (decoV != null) decoV.invalidate();
    }

    static void finishClose(final View v) {
        closingWait = false;
        off(v);
        // volta a ficar visível só no próximo quadro, já com a central recolhida
        v.post(new Runnable() {
            public void run() {
                v.setAlpha(1f);
            }
        });
    }

    static void round(View v) {
        if (clipped || !Prefs.sheetRound) return;
        radius = 34f * v.getResources().getDisplayMetrics().density;
        v.setOutlineProvider(new ViewOutlineProvider() {
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, -(int) radius, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
        clipped = true;
    }

    static final float LAND_SPLIT = 0.42f, LAND_RIGHT = 112f;
    static int landLogs;
    static View nssl;
    static int[] nsslOrig;

    /** A lista desce junto com o relógio e termina acima da lanterna e da câmera. */
    static void layoutList(View v, boolean apply) {
        try {
            if (nssl == null) nssl = findCls(v, "NotificationStackScrollLayout", 0);
            if (nssl == null || !(nssl.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) nssl.getLayoutParams();
            if (apply) {
                float d = v.getResources().getDisplayMetrics().density;
                if (nsslOrig == null) nsslOrig = new int[]{lp.topMargin, lp.bottomMargin, lp.leftMargin, lp.rightMargin, lp.width};
                boolean land = v.getWidth() > v.getHeight();
                if (land != landNow) { landNow = land; requestStack(); }
                int t, b, l, r, wd;
                if (land) {
                    // paisagem: a lista vai para a coluna da direita, entre o relógio e a câmera
                    t = nsslOrig[0] + Math.round(48 * d);
                    b = nsslOrig[1] + Math.round(16 * d);
                    // a lista desce até a linha de baixo da câmera (os botões ficam ao lado, fora da coluna)
                    if (decoV != null && decoV.btnY > 0 && nssl.getParent() instanceof View) {
                        View par = (View) nssl.getParent();
                        int[] pl = new int[2], dl = new int[2];
                        par.getLocationInWindow(pl);
                        decoV.getLocationInWindow(dl);
                        b = Math.max(0, Math.round(pl[1] + par.getHeight() - (dl[1] + decoV.btnY + 28 * d)));
                    }
                    l = Math.round(v.getWidth() * LAND_SPLIT);
                    r = Math.round(LAND_RIGHT * d);
                    wd = v.getWidth() - l - r;
                    // a descida dos cartões é do relógio do retrato: em paisagem não existe
                    if (listExtra != 0) { listExtra = 0; requestStack(); }
                } else {
                    t = nsslOrig[0] + Math.round(CurtainClock.listOffset(d) + 48 * d);
                    if (true) {
                        // relógio que sobe: a lista ocupa a tela desde o topo; o começo dela vira deslocamento
                        // dos cartões, que assim passam por baixo do relógio até sair pela borda de cima
                        int x = t - nsslOrig[0];
                        if (x != listExtra) { listExtra = x; requestStack(); }
                        t = nsslOrig[0];
                    } else if (listExtra != 0) { listExtra = 0; requestStack(); }
                    b = nsslOrig[1] + Math.round(104 * d);
                    // (o fim acima da lanterna/câmera fica na pilha e no corte do desenho: encurtar a lista
                    // tirava o toque dos cartões de baixo, que a cortina desce para fora dela)
                    l = nsslOrig[2];
                    r = nsslOrig[3];
                    wd = nsslOrig[4];
                }
                if (lp.topMargin != t || lp.bottomMargin != b || lp.leftMargin != l || lp.rightMargin != r || lp.width != wd) {
                    lp.topMargin = t;
                    lp.bottomMargin = b;
                    lp.leftMargin = l;
                    lp.rightMargin = r;
                    lp.width = wd;
                    nssl.setLayoutParams(lp);
                    if (land && landLogs++ < 2) ElasticCard.log("cortina: paisagem, lista em " + l + "–" + (l + wd) + "px");
                }
            } else if (nsslOrig != null) {
                nssl.setClipBounds(null);
                lp.topMargin = nsslOrig[0];
                lp.bottomMargin = nsslOrig[1];
                lp.leftMargin = nsslOrig[2];
                lp.rightMargin = nsslOrig[3];
                lp.width = nsslOrig[4];
                nssl.setLayoutParams(lp);
                nsslOrig = null;
            }
        } catch (Throwable t) {
            ElasticCard.log("cortina lista: " + t);
        }
    }

    static View band;
    static float bandFor = -1;

    /**
     * Desfoque de cima (como o cabeçalho do HyperOS): cobre o relógio e termina em degradê onde a
     * lista começa, então os cartões somem desfocando ao rolar para cima, seguindo a posição do relógio.
     */
    static void topBlur(View deco, float listTopWin) {
        try {
            if (!(deco.getParent() instanceof ViewGroup) || !TopBlur.init(deco.getClass().getClassLoader())) return;
            ViewGroup root = (ViewGroup) deco.getParent();
            float d = deco.getResources().getDisplayMetrics().density;
            if (band == null) band = TopBlur.makeBand(root.getContext());
            if (band.getParent() != root) {
                if (band.getParent() != null) ((ViewGroup) band.getParent()).removeView(band);
                int idx = root.indexOfChild(deco);
                if (Kg.box != null && Kg.box.getParent() == root) idx = Math.min(idx, root.indexOfChild(Kg.box));
                root.addView(band, Math.max(0, idx), new ViewGroup.LayoutParams(-1, -1));
                band.setVisibility(View.VISIBLE);
                TopBlur.mBgMode.invoke(band, Integer.valueOf(1));
                TopBlur.mViewMode.invoke(band, Integer.valueOf(1));
                if (TopBlur.mBgType != null) TopBlur.mBgType.invoke(band, Integer.valueOf(2));
                bandFor = -1;
            }
            int[] l = new int[2];
            root.getLocationInWindow(l);
            float y = listTopWin - l[1] - root.getTranslationY();
            int h = Math.round(y + 14 * d);
            if (Math.abs(bandFor - y) >= 1f || band.getHeight() != h) {
                band.layout(0, 0, root.getWidth(), h);
                float[] p = new float[]{0f, Math.max(0f, y - 46 * d), 40f * d, 0f, h, 0f};
                TopBlur.mGrad.invoke(band, p, Integer.valueOf(1));
                bandFor = y;
                band.invalidate();
            }
        } catch (Throwable t) {
            ElasticCard.log("cortina desfoque de cima: " + t);
        }
    }

    static View bottomBand;
    static int bbTop = Integer.MIN_VALUE, bbLogs;

    /**
     * Desfoque de baixo em degradê, como o do cabeçalho do HyperOS: os cartões vão desfocando aos
     * poucos ao chegar perto da lanterna e da câmera, em vez de sumir numa linha.
     */
    static float bottomCutWin = Float.NaN;

    /** O degradê do cabeçalho do HyperOS ganha um segundo trecho embaixo: do nítido ao desfocado até o corte. */
    static float[] withBottom(View v, float[] b) {
        if (!on || Float.isNaN(bottomCutWin) || b.length != 6) return b;
        int[] l = new int[2];
        v.getLocationInWindow(l);
        float d = v.getResources().getDisplayMetrics().density;
        float cut = bottomCutWin - l[1], fade = 110 * d;
        if (cut - fade <= b[4]) return b;
        float st = b[2] > 0 ? b[2] : 2.3f;
        return new float[]{b[0], b[1], b[2], b[3], b[4], b[5], 0f, cut - fade, 0f, 0f, cut, st};
    }

    static void bottomBlur(View deco) {
        try {
            View host = nssl;
            float lim = host == null || !on ? Float.NaN : buttonLimit(host);
            float cut = Float.NaN;
            if (!Float.isNaN(lim)) {
                int[] hl = new int[2];
                host.getLocationInWindow(hl);
                cut = hl[1] + lim + 10 * deco.getResources().getDisplayMetrics().density;
            }
            boolean changed = Float.isNaN(cut) != Float.isNaN(bottomCutWin) || (!Float.isNaN(cut) && Math.abs(cut - bottomCutWin) > 2);
            if (!changed) return;
            bottomCutWin = cut;
            gradShift.clear();   // reaplica o degradê com o trecho de baixo
            shiftGradients(shiftNow);
            if (bbLogs++ < 3) ElasticCard.log("cortina: desfoque de baixo no degradê do HyperOS (corte em " + Math.round(cut) + ", " + gradOrig.size() + " fundo(s))");
        } catch (Throwable t) {
            if (bbLogs++ < 4) ElasticCard.log("cortina: desfoque de baixo: " + t);
        }
    }

    static View blurL, blurR;
    static boolean blurBroken;

    /** Círculos de vidro desfocado atrás da lanterna e da câmera (mesmo desfoque dos botões do bloqueio). */
    static void buttonBlur(View deco, float lx, float rx, float cyL, float cy, float r) {
        if (blurBroken || !(deco.getParent() instanceof ViewGroup)) return;
        try {
            ViewGroup root = (ViewGroup) deco.getParent();
            if (blurL == null) {
                blurL = circle(root.getContext());
                blurR = circle(root.getContext());
            }
            for (View c : new View[]{blurL, blurR}) {
                if (c.getParent() != root) {
                    if (c.getParent() != null) ((ViewGroup) c.getParent()).removeView(c);
                    root.addView(c, root.indexOfChild(deco), new ViewGroup.LayoutParams(Math.round(r * 2), Math.round(r * 2)));
                    // vidro: desfoque do que está atrás + véu claro (chamado direto na View, sem a biblioteca dos relógios)
                    MiBlend.container(c, Math.round(40 * root.getResources().getDisplayMetrics().density));
                    try {
                        View.class.getMethod("addMiBackgroundBlendColor", int.class, int.class).invoke(c, Integer.valueOf(0x40FFFFFF), Integer.valueOf(3));
                    } catch (Throwable ignored) {
                    }
                    ElasticCard.log("cortina: vidro dos botões ligado");
                }
            }
            int dt = Math.round(deco.getTop() + deco.getTranslationY());
            blurL.setTranslationX(lx - r + deco.getTranslationX());
            blurL.setTranslationY(dt + cyL - r);
            blurR.setTranslationX(rx - r + deco.getTranslationX());
            blurR.setTranslationY(dt + cy - r);
        } catch (Throwable t) {
            blurBroken = true;
            ElasticCard.log("cortina: vidro dos botões indisponível: " + t);
        }
    }

    static View circle(android.content.Context ctx) {
        View v = new View(ctx);
        v.setOutlineProvider(new ViewOutlineProvider() {
            public void getOutline(View view, Outline o) {
                o.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        v.setClipToOutline(true);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    static View headerBox;
    static final java.util.WeakHashMap<View, float[]> gradOrig = new java.util.WeakHashMap<>();
    static final java.util.WeakHashMap<View, Integer> gradShift = new java.util.WeakHashMap<>();
    static boolean gradSelf, gradHooked;
    static java.lang.reflect.Method mGrad;
    static int gradLogs;

    /** Guarda o degradê de desfoque que o HyperOS põe no cabeçalho, para poder deslocá-lo. */
    static void hookGradient() {
        if (gradHooked) return;
        gradHooked = true;
        try {
            mGrad = View.class.getMethod("setBackgroundGradientBlurParams", float[].class, int.class);
            XposedBridge.hookMethod(mGrad, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (gradSelf) return;
                    View v = (View) p.thisObject;
                    if (v == TopBlur.band || !(p.args[0] instanceof float[])) return;
                    float[] a = (float[]) p.args[0];
                    if (a.length < 5) return;
                    gradOrig.put(v, a.clone());
                    gradShift.put(v, Integer.valueOf(0));
                    if (gradLogs++ < 3) ElasticCard.log("cortina: degradê do cabeçalho em " + v.getClass().getName() + " " + java.util.Arrays.toString(a));
                    if (on) {
                        int s = shiftNow;
                        float[] b = a.clone();
                        b[1] += s;
                        b[4] += s;
                        p.args[0] = withBottom(v, b);
                        gradShift.put(v, Integer.valueOf(s));
                    }
                }
            });
        } catch (Throwable t) {
            ElasticCard.log("cortina: degradê indisponível: " + t);
        }
    }

    static int shiftNow;
    static final java.util.WeakHashMap<View, Integer> gradH = new java.util.WeakHashMap<>();

    static void shiftGradients(int s) {
        shiftNow = s;
        if (mGrad == null) return;
        for (java.util.Map.Entry<View, float[]> e : new java.util.ArrayList<>(gradOrig.entrySet())) {
            View v = e.getKey();
            Integer cur = gradShift.get(v);
            if (cur != null && cur.intValue() == s) continue;
            float[] b = e.getValue().clone();
            b[1] += s;
            b[4] += s;
            b = withBottom(v, b);
            // o degradê só aparece dentro da própria view: se ela for mais baixa que o fim do degradê,
            // o desfoque termina numa linha reta. A view cresce até o fim do degradê (e volta depois).
            try {
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp != null) {
                    if (!gradH.containsKey(v)) gradH.put(v, Integer.valueOf(lp.height));
                    int oh = gradH.get(v).intValue();
                    float maxY = 0;
                    for (int k = 1; k < b.length; k += 3) maxY = Math.max(maxY, b[k]);
                    int need = s == 0 && b.length <= 6 ? oh : Math.max(v.getHeight(), Math.round(maxY) + 2);
                    if ((s == 0 && b.length <= 6) || need > v.getHeight()) {
                        if (lp.height != need) { lp.height = need; v.setLayoutParams(lp); }
                    }
                    if (gradLogs++ < 6) ElasticCard.log("cortina: degradê " + v.getClass().getSimpleName() + " h=" + v.getHeight() + " fim=" + Math.max(b[1], b[4]) + " -> " + need);
                }
            } catch (Throwable ignored) {
            }
            try {
                gradSelf = true;
                mGrad.invoke(v, b, Integer.valueOf(1));
                v.invalidate();
            } catch (Throwable ignored) {
            } finally {
                gradSelf = false;
            }
            gradShift.put(v, Integer.valueOf(s));
        }
    }

    /** O cabeçalho original do HyperOS (com o desfoque em degradê dele) desce junto com o relógio. */
    static void moveHeader(View deco) {
        try {
            if (!(deco.getParent() instanceof ViewGroup)) return;
            float d = deco.getResources().getDisplayMetrics().density;
            boolean land = ((View) deco.getParent()).getWidth() > ((View) deco.getParent()).getHeight();
            shiftGradients(Math.round((land ? 0 : CurtainClock.offset(d)) + 48 * d));
        } catch (Throwable ignored) {
        }
    }

    static void removeBand() {
        shiftGradients(0);
        for (View c : new View[]{blurL, blurR}) if (c != null && c.getParent() instanceof ViewGroup) ((ViewGroup) c.getParent()).removeView(c);
        if (band != null && band.getParent() instanceof ViewGroup) ((ViewGroup) band.getParent()).removeView(band);
        bandFor = -1;
    }

    /** A lista não passa por baixo do relógio: corta acima da linha "Notificações". */
    static void clipList(float windowY) {
        try {
            if (nssl == null || !on) return;
            int[] l = new int[2];
            nssl.getLocationInWindow(l);
            int top = Math.max(0, Math.round(windowY - l[1] - nssl.getTranslationY()));
            android.graphics.Rect r = nssl.getClipBounds();
            if (r == null || r.top != top || r.bottom != nssl.getHeight() + 4000) nssl.setClipBounds(new android.graphics.Rect(-200, top, nssl.getWidth() + 200, nssl.getHeight() + 4000));
        } catch (Throwable ignored) {
        }
    }

    static View findCls(View v, String suffix, int depth) {
        if (v.getClass().getName().endsWith(suffix)) return v;
        if (depth > 8 || !(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View r = findCls(g.getChildAt(i), suffix, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    static void off(View v) {
        on = false;
        if (bottomBand != null) bottomBand.setVisibility(View.GONE);
        if (!Float.isNaN(bottomCutWin)) {
            bottomCutWin = Float.NaN;
            gradShift.clear();
            try { shiftGradients(0); } catch (Throwable ignored) {}
        }
        switchingNow = false;
        follow(v, 0f, 1f);
        layoutList(v, false);
        for (View x : hidden) x.setAlpha(1f);
        hidden.clear();
        if (v.getTranslationY() != 0f) v.setTranslationY(0f);
        if (clipped) {
            v.setClipToOutline(false);
            v.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            clipped = false;
        }
        if (bgDrawable != null && v.getBackground() == bgDrawable) v.setBackground(orig);
        if (dismissView != null) {
            final View dc = dismissView.getParent() instanceof View ? (View) dismissView.getParent() : dismissView;
            dc.postDelayed(new Runnable() {
                public void run() {
                    if (!on) {
                        dc.setAlpha(1f);
                        dc.setVisibility(View.VISIBLE);
                    }
                }
            }, 400);
        }
        deco(v, false);
    }

    /** O HyperOS repõe o escurecimento/desfoque dele: a cada quadro, com a cortina ativa, some de novo. */
    static void hookPreDraw(final View v) {
        if (preHooked) return;
        preHooked = true;
        v.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            public boolean onPreDraw() {
                try {
                    if (!on && Prefs.shadeSheet && locked(v) && (Kg.snapL == null || android.os.SystemClock.uptimeMillis() - Kg.snapAt > 30000)) Kg.snapBottom(v);   // copia os botões do bloqueio
                    Kg.fadeGlass(v);   // o vidro do relógio/título não segue o painel: some junto com ele
                    if (on) {
                        layoutList(v, true);
                        if (decoV != null) {   // relógio e título acompanham cada quadro da rolagem (inclusive a volta ao topo)
                            int sc = scrollY();
                            if (sc != lastSc) { lastSc = sc; decoV.invalidate(); }
                        }
                        if (!enabled(v)) off(v);
                        else {
                            if (!Prefs.sheetBlur && !switchingNow) for (View x : hidden) if (x.getAlpha() != 0f) x.setAlpha(0f);
                            if (hdr != null && hdr.getAlpha() != 0f) hdr.setAlpha(0f);
                            if (dismissView == null) dismissView = findId(v, "notification_dismiss_view", 0);
                            if (dismissView != null) {
                                // o botão do HyperOS sai de baixo de vez (não só invisível): o nosso fica no cabeçalho
                                View dc = dismissView.getParent() instanceof View ? (View) dismissView.getParent() : dismissView;
                                if (dc.getVisibility() != View.GONE) dc.setVisibility(View.GONE);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return true;
            }
        });
    }

    static void hideMiui(View v) {
        if (!hidden.isEmpty() || !(v instanceof ViewGroup)) {
            for (View x : hidden) if (x.getAlpha() != 0f) x.setAlpha(0f);
            return;
        }
        walk((ViewGroup) v, 0);
    }

    static void walk(ViewGroup g, int depth) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            String n = c.getClass().getName();
            if (n.contains("Keyguard")) continue;
            if (n.endsWith("ScrimView") || n.endsWith("ShadeBackgroundView")) {
                c.setAlpha(0f);
                if (!hidden.contains(c)) hidden.add(c);
            }
            if (c instanceof ViewGroup && depth < 2) walk((ViewGroup) c, depth + 1);
        }
    }

    static View dismissView;

    static View findId(View v, String name, int depth) {
        try {
            if (v.getId() != View.NO_ID && name.equals(v.getResources().getResourceEntryName(v.getId()))) return v;
        } catch (Throwable ignored) {
        }
        if (depth > 10 || !(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View r = findId(g.getChildAt(i), name, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    static boolean hasNotifications() {
        try {
            if (!(nssl instanceof ViewGroup)) return true;
            ViewGroup g = (ViewGroup) nssl;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                String n = c.getClass().getName();
                if (n.endsWith("ExpandableNotificationRow") && c.getVisibility() == View.VISIBLE) {
                    android.service.notification.StatusBarNotification sb = Privacy.sbn(c);
                    if (sb != null && sb.isClearable()) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    static TextView hTitle;
    static ImageView hClear;
    static int hLogs;

    /**
     * "Notificações" (no idioma do sistema) e o limpar tudo, em views de verdade com a vibrance do
     * HyperOS (a mesma mistura de cor dos textos da central), acima da lista.
     */
    static void header(View deco, float gy, float alpha) {
        try {
            if (!(deco.getParent() instanceof ViewGroup)) return;
            ViewGroup root = (ViewGroup) deco.getParent();
            android.content.Context ctx = root.getContext();
            float d = ctx.getResources().getDisplayMetrics().density;
            if (hTitle == null) {
                hTitle = new TextView(ctx);
                String t = null;
                try {
                    int id = ctx.getResources().getIdentifier("notification_section_header_alerting", "string", "com.android.systemui");
                    if (id != 0) t = ctx.getString(id);
                } catch (Throwable ignored) {
                }
                hTitle.setText(t != null ? t : Tr.t("Notificações"));
                hTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 19 * d);
                hTitle.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL), 650, false));
                hTitle.setTextColor(0xFFFFFFFF);
                hTitle.setIncludeFontPadding(false);
                hClear = new ImageView(ctx);
                hClear.setImageDrawable(new GroupClear.XDrawable(d));
                int pad = Math.round(8 * d);
                hClear.setPadding(pad, pad, pad, pad);
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                bg.setColor(0x40FFFFFF);
                hClear.setBackground(bg);
                hClear.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
                        clearAll();
                    }
                });
            }
            int sz = Math.round(30 * d);
            for (View v : new View[]{hTitle, hClear}) {
                if (v.getParent() != root) {
                    if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
                    root.addView(v, root.indexOfChild(deco), v == hClear ? new ViewGroup.LayoutParams(sz, sz) : new ViewGroup.LayoutParams(-2, -2));
                    try {
                        // vibrance dos textos da central: a cor se mistura com o que está atrás
                        MiBlend.container(v, 0);
                        MiBlend.vibrant(v);
                        if (hLogs++ < 2) ElasticCard.log("cortina: vibrance no cabeçalho aplicada");
                    } catch (Throwable t) {
                        if (hLogs++ < 2) ElasticCard.log("cortina: vibrance no cabeçalho indisponível: " + t);
                    }
                }
            }
            float top = deco.getTop();
            hTitle.setTranslationX(20 * d + deco.getTranslationX());
            hTitle.setTranslationY(top + gy - hTitle.getHeight() / 2f);
            hClear.setTranslationX(deco.getWidth() - 36 * d - sz / 2f + deco.getTranslationX());
            hClear.setTranslationY(top + gy - sz / 2f);
            hTitle.setAlpha(alpha);
            hClear.setAlpha(alpha);
            hClear.setClickable(alpha > 0.5f);
            hClear.setVisibility(dismissView != null ? View.VISIBLE : View.INVISIBLE);
        } catch (Throwable t) {
            if (hLogs++ < 4) ElasticCard.log("cortina cabeçalho: " + t);
        }
    }

    static String titleCache;

    /** "Notificações" no idioma do sistema (texto do próprio HyperOS). */
    static String titleText(android.content.Context ctx) {
        if (titleCache != null) return titleCache;
        try {
            int id = ctx.getResources().getIdentifier("notification_section_header_alerting", "string", "com.android.systemui");
            if (id != 0) titleCache = ctx.getString(id);
        } catch (Throwable ignored) {
        }
        if (titleCache == null) titleCache = Tr.t("Notificações");
        return titleCache;
    }

    static void removeHeader() {
        for (View v : new View[]{hTitle, hClear}) if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    static void clearAll() {
        try {
            noCloseUntil = android.os.SystemClock.uptimeMillis() + 2500;   // limpar não fecha a central
            if (dismissView != null) dismissView.performClick();
        } catch (Throwable t) {
            ElasticCard.log("cortina limpar: " + t);
        }
    }

    static void deco(View v, boolean show) {
        try {
            if (!(v instanceof ViewGroup)) return;
            ViewGroup g = (ViewGroup) v;
            if (!show) {
                if (decoV != null && decoV.getParent() == g) g.removeView(decoV);
                Kg.removeGlass();
                CurtainClock.remove();
                removeHeader();
                removeBand();
                if (hdr != null) hdr.setAlpha(1f);
                return;
            }
            if (decoV == null) decoV = new SheetDeco(g.getContext());
            if (decoV.getParent() != g) {
                if (decoV.getParent() != null) ((ViewGroup) decoV.getParent()).removeView(decoV);
                g.addView(decoV, new ViewGroup.LayoutParams(-1, -1));
            }
            hdr = SheetDeco.findHeader(g, 0);
            decoV.header = hdr;
            if (hdr != null) hdr.setAlpha(0f);
        } catch (Throwable t) {
            ElasticCard.log("cortina enfeites: " + t);
        }
    }

    static void collapse() {
        try {
            Object sb = panel.get().getContext().getSystemService("statusbar");
            sb.getClass().getMethod("collapsePanels").invoke(sb);
        } catch (Throwable t) {
            ElasticCard.log("cortina recolher: " + t);
        }
    }

    /** Fundo: papel de parede do bloqueio, ou o da tela inicial desfocado ("fundo desfocado"). */
    static void background(View v) {
        try {
            if (bgDrawable == null || bgBlur != Prefs.sheetBlur) {
                bgBlur = Prefs.sheetBlur;
                android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(v.getContext());
                android.graphics.drawable.Drawable d = null;
                if (!bgBlur) {
                    try {
                        d = wm.getDrawable(android.app.WallpaperManager.FLAG_LOCK);
                    } catch (Throwable ignored) {
                    }
                }
                if (d == null) d = wm.getDrawable();
                if (!(d instanceof android.graphics.drawable.BitmapDrawable)) return;
                android.graphics.Bitmap bm = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                if (bgBlur) {
                    int w = Math.max(1, bm.getWidth() / 8), h = Math.max(1, bm.getHeight() / 8);
                    android.graphics.Bitmap s = android.graphics.Bitmap.createScaledBitmap(bm, w, h, true).copy(android.graphics.Bitmap.Config.ARGB_8888, true);
                    Frost.boxBlur(s, 6);
                    Frost.boxBlur(s, 6);
                    bm = s;
                }
                final android.graphics.Bitmap fb = bm;
                final int dim = bgBlur ? 0x55000000 : 0x33000000;
                bgDrawable = new android.graphics.drawable.Drawable() {
                    final android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
                    final android.graphics.Matrix m = new android.graphics.Matrix();

                    int al = 255;

                    public void draw(android.graphics.Canvas c) {
                        if (al <= 0) return;
                        pt.setAlpha(al);
                        android.graphics.Rect b = getBounds();
                        float sc = Math.max(b.width() / (float) fb.getWidth(), b.height() / (float) fb.getHeight());
                        m.setScale(sc, sc);
                        m.postTranslate(b.left + (b.width() - fb.getWidth() * sc) / 2f, b.top + (b.height() - fb.getHeight() * sc) / 2f);
                        c.drawBitmap(fb, m, pt);
                        c.drawColor(((((dim >>> 24) * al) / 255) << 24));
                    }

                    public void setAlpha(int a) {
                        if (a != al) { al = a; invalidateSelf(); }
                    }

                    public void setColorFilter(android.graphics.ColorFilter cf) {}

                    public int getOpacity() {
                        return al >= 255 ? android.graphics.PixelFormat.OPAQUE : android.graphics.PixelFormat.TRANSLUCENT;
                    }
                };
            }
            if (v.getBackground() != bgDrawable) {
                orig = v.getBackground();
                v.setBackground(bgDrawable);
            }
        } catch (Throwable t) {
            ElasticCard.log("cortina: fundo indisponível: " + t);
        }
    }

    static boolean locked(View v) {
        try {
            KeyguardManager km = (KeyguardManager) v.getContext().getSystemService(KeyguardManager.class);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            return false;
        }
    }
}
