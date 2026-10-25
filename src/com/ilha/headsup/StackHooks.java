package com.ilha.headsup;

import android.graphics.Canvas;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Notificações empilhadas no estilo HyperOS 4, aplicadas sobre o SystemUI do HyperOS 3.
 *
 * Depois de cada StackScrollAlgorithm.resetViewStates, as linhas que passam do limite encolhem e
 * ficam presas em duas linhas (32 dp e 12 dp), com a matemática do HOS4. Funciona na gaveta e na
 * tela de bloqueio; em grupos expandidos cada filho empilha como um cartão.
 */
final class StackHooks {
    private StackHooks() {}

    static final class Info {
        int clip;
        float prog;
        int dim;
        boolean front;
    }

    /** Conteúdo (textos, ícones) do cartão; o fundo fica. */
    static void content(View v, float a) {
        for (String n : new String[]{"mPrivateLayout", "mPublicLayout", "mChildrenContainer"}) {
            try {
                Object l = Refl.get(v, n);
                if (l instanceof View && ((View) l).getAlpha() != a) ((View) l).setAlpha(a);
            } catch (Throwable ignored) {
            }
        }
    }

    static final Map<View, Info> active = new WeakHashMap<View, Info>();
    static boolean installed;
    static boolean loggedShade, loggedKeyguard, loggedGroup;

    // estado da última passada (usado pelo alcance de rolagem e pelo toque no bloqueio)
    static volatile int neededRange;
    static volatile boolean onKeyguard;
    static volatile boolean shadeOpen;   // gaveta aberta (heads-up que some vai para a lista)
    static volatile float listTop = Float.NaN, listEnd = Float.NaN;
    // esconder as notificações do bloqueio deslizando para baixo (e mostrar deslizando para cima)
    static volatile boolean kgHidden;
    static volatile float hideP;
    static android.animation.ValueAnimator hideAnim;
    static View nsslRef;
    static int hiddenCount, lastRows = -1;
    static Hint hint;
    static long maxFixAt;

    static Class rowClass, mediaClass;
    static Method mInnerHeight, mIsOnKeyguard, mGetViewState, mIsPinned, mAwayAnim, mExpChanging;
    static Method mSetY, mSetScaleX, mSetScaleY, mSetZ, mSetAlpha, mSetClipBottom, mGetClipPath, mBlurOpened;
    static Method mSetMaxDisplayed, mGetScrollRange;
    static Field fTempState, fVisible, fPadding, fHost, fTopPadding, fShadeExpanded, fScrollY, fDozeAmount;
    static Field fY, fAlpha, fHidden, fHeight, fInShelf, fClipTop, fLocation, fBackground;
    static final int[] loc = new int[2];
    static int statusBar = -1;
    static Field fSummary, fChildrenExpanded, fChildrenContainer, fAttached;
    static Field fNsslAmbient, fScrollingEnabled, fMaxDisplayed;

    static StackMath math;
    static float mathDensity = -1f, mathPad = -1f;
    static boolean blurCached;
    static long blurAt;

    static synchronized void install(ClassLoader cl) {
        if (installed) return;
        installed = true;
        try {
            String P = "com.android.systemui.statusbar.notification.";
            Class ssa = Class.forName(P + "stack.StackScrollAlgorithm", false, cl);
            Class amb = Class.forName(P + "stack.AmbientState", false, cl);
            Class ssaState = Class.forName(P + "stack.StackScrollAlgorithm$StackScrollAlgorithmState", false, cl);
            Class viewState = Class.forName(P + "stack.ViewState", false, cl);
            Class evState = Class.forName(P + "stack.ExpandableViewState", false, cl);
            Class expView = Class.forName(P + "row.ExpandableView", false, cl);
            Class outline = Class.forName(P + "row.ExpandableOutlineView", false, cl);
            Class activatable = Class.forName(P + "row.ActivatableNotificationView", false, cl);
            Class container = Class.forName(P + "stack.NotificationChildrenContainer", false, cl);
            Class nssl = Class.forName(P + "stack.NotificationStackScrollLayout", false, cl);
            rowClass = Class.forName(P + "row.ExpandableNotificationRow", false, cl);
            try {
                // cartão do player no bloqueio (o HyperMusicCover mostra ele quando a ilha da música abre)
                mediaClass = Class.forName(P + "mediacontrol.MiuiMediaHeaderView", false, cl);
                // quando o player abre, pede uma passada da lista na hora, para o cartão já
                // nascer embaixo e o relógio já ir para o HyperMusicCover
                // (só o setVisibility do próprio cartão: ganchar o de View pesaria em toda a interface)
                XposedBridge.hookMethod(mediaClass.getDeclaredMethod("setVisibility", int.class), new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (!onKeyguard || !(p.thisObject instanceof View)) return;
                        if (((View) p.thisObject).getVisibility() != View.VISIBLE) return;
                        requestStackUpdate((View) p.thisObject);
                    }
                });
            } catch (Throwable t) {
                ElasticCard.log("cartão do player não encontrado: " + t);
            }

            fTempState = Refl.field(ssa, "mTempAlgorithmState");
            fPadding = Refl.field(ssa, "mPaddingBetweenElements");
            fHost = Refl.field(ssa, "mHostView");
            fVisible = Refl.field(ssaState, "visibleChildren");
            fTopPadding = Refl.field(amb, "mTopPadding");
            fShadeExpanded = Refl.field(amb, "mShadeExpanded");
            fScrollY = Refl.field(amb, "mScrollY");
            fDozeAmount = Refl.field(amb, "mDozeAmount");
            mInnerHeight = amb.getMethod("getInnerHeight", boolean.class);
            mIsOnKeyguard = amb.getMethod("isOnKeyguard");
            mGetViewState = expView.getMethod("getViewState");
            mIsPinned = rowClass.getMethod("isPinned");
            mAwayAnim = Refl.anyMethod(rowClass, "isHeadsUpAnimatingAway$1");
            mExpChanging = rowClass.getMethod("isGroupExpansionChanging");
            mSetY = VS.find(viewState, "setYTranslation");
            mSetScaleX = VS.find(viewState, "setScaleX");
            mSetScaleY = VS.find(viewState, "setScaleY");
            mSetZ = VS.find(viewState, "setZTranslation");
            mSetAlpha = VS.find(viewState, "setAlpha");
            fY = Refl.field(viewState, "mYTranslation");
            fAlpha = Refl.field(viewState, "mAlpha");
            fHidden = Refl.field(viewState, "hidden");
            fHeight = Refl.field(evState, "height");
            fInShelf = Refl.field(evState, "inShelf");
            fClipTop = Refl.field(evState, "clipTopAmount");
            try {
                fLocation = Refl.field(evState, "location");
            } catch (Throwable ignored) {
                fLocation = null;
            }
            fBackground = Refl.field(activatable, "mBackgroundNormal");
            fSummary = Refl.field(rowClass, "mIsSummaryWithChildren");
            fChildrenExpanded = Refl.field(rowClass, "mChildrenExpanded");
            fChildrenContainer = Refl.field(rowClass, "mChildrenContainer");
            fAttached = Refl.field(container, "mAttachedChildren");
            fNsslAmbient = Refl.field(nssl, "mAmbientState");
            fScrollingEnabled = Refl.field(nssl, "mScrollingEnabled");
            fMaxDisplayed = Refl.field(nssl, "mMaxDisplayedNotifications");
            mSetClipBottom = rowClass.getMethod("setClipBottomAmount", int.class);
            mGetClipPath = outline.getMethod("getClipPath", boolean.class);
            mSetMaxDisplayed = nssl.getMethod("setMaxDisplayedNotifications", int.class);
            mGetScrollRange = nssl.getMethod("getScrollRange");
            try {
                Class util = Class.forName("com.miui.systemui.notification.MiuiBaseNotifUtil", false, cl);
                mBlurOpened = util.getMethod("isBackgroundBlurOpened", android.content.Context.class);
            } catch (Throwable ignored) {
            }

            XposedBridge.hookMethod(ssa.getMethod("resetViewStates", amb, int.class), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    apply(p.thisObject, p.args[0]);
                }
            });
            XposedBridge.hookMethod(mSetClipBottom, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    Info inf = active.get(p.thisObject);
                    if (inf != null) p.args[0] = Integer.valueOf(inf.clip);
                }
            });
            XposedBridge.hookMethod(outline.getDeclaredMethod("drawChild", Canvas.class, View.class, long.class), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    dim(p.thisObject, (Canvas) p.args[0], p.args[1]);
                }
            });
            // alcance de rolagem: o último cartão precisa conseguir subir até o limite
            XposedBridge.hookMethod(mGetScrollRange, new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    int need = neededRange;
                    if (need <= 0) return;
                    Object r = p.getResult();
                    if (r instanceof Integer && ((Integer) r).intValue() < need) p.setResult(Integer.valueOf(need));
                }
            });
            // bloqueio: mostrar todas as notificações
            XposedBridge.hookMethod(mSetMaxDisplayed, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (wantKeyguardAll(p.thisObject)) p.args[0] = Integer.valueOf(-1);
                }
            });
            // bloqueio: liberar a rolagem da lista
            XposedBridge.hookMethod(nssl.getMethod("setScrollingEnabled", boolean.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (wantKeyguardScroll(p.thisObject)) p.args[0] = Boolean.TRUE;
                }
            });
            // bloqueio: toque que começa sobre a lista rola em vez de desbloquear
            XposedBridge.hookMethod(nssl.getMethod("dispatchTouchEvent", MotionEvent.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (keyguardTouch((View) p.thisObject, (MotionEvent) p.args[0])) p.setResult(Boolean.TRUE);
                }
            });
            ElasticCard.log("empilhamento instalado (gaveta, bloqueio e grupos)");
        } catch (Throwable t) {
            ElasticCard.log("ERRO ao instalar o empilhamento: " + t);
        }
    }

    // ---------------------------------------------------------------- bloqueio

    static boolean keyguardState(Object nsslView) {
        try {
            Object amb = fNsslAmbient.get(nsslView);
            return amb != null && Boolean.TRUE.equals(mIsOnKeyguard.invoke(amb)) && fDozeAmount.getFloat(amb) <= 0f;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean wantKeyguardAll(Object nsslView) {
        Prefs.refresh();
        return Prefs.stEnabled && Prefs.kgEnabled && keyguardState(nsslView);
    }

    static boolean wantKeyguardScroll(Object nsslView) {
        Prefs.refresh();
        return Prefs.stEnabled && Prefs.kgEnabled && Prefs.kgScroll && keyguardState(nsslView);
    }

    static float tX, tY;
    static boolean tTrack;

    static volatile float pull, pullMax;
    static volatile boolean collapsed;   // terminou de recolher: só então o player desce
    static volatile float mediaTop = Float.NaN, mediaBottom = Float.NaN;   // player, em coordenadas da lista
    static android.animation.ValueAnimator pullAnim;
    static boolean pulling, cancelling;
    static float lastY;
    static android.view.VelocityTracker vt;

    /**
     * Rolar a lista do bloqueio para baixo além do topo: em vez de só esticar, as notificações
     * descem juntas e vão se empilhando no limite de baixo (o mesmo empilhamento de quando
     * passam do limite), até sobrar só o player. Rolar para cima traz de volta. Ao soltar, vai
     * para o fim mais perto (todas empilhadas ou todas à mostra).
     */
    static boolean hideGesture(View nsslView, MotionEvent ev) {
        if (!onKeyguard || cancelling) {
            pulling = false;
            return false;
        }
        Prefs.refresh();
        if (!Prefs.kgHide || !Prefs.stEnabled || !Prefs.kgEnabled || !Prefs.kgScroll) return false;
        nsslRef = nsslView;
        float d = nsslView.getResources().getDisplayMetrics().density;
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            tX = ev.getX();
            tY = lastY = ev.getY();
            pulling = false;
            float top = listTop, end = listEnd;
            boolean onMedia = !Float.isNaN(mediaTop) && tY >= mediaTop && tY <= mediaBottom;
            tTrack = onMedia || (!Float.isNaN(top) && !Float.isNaN(end) && tY >= top && tY <= end);
            if (tTrack && onMedia) {
                // começando no player: o bloqueio não pega o arrasto (puxar a gaveta, desbloquear)
                ViewParent parent = nsslView.getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            }
            if (pullAnim != null && tTrack) pullAnim.cancel();
            if (vt != null) vt.clear();
            else vt = android.view.VelocityTracker.obtain();
            vt.addMovement(ev);
            return false;
        }
        if (!tTrack) return false;
        if (vt != null) vt.addMovement(ev);
        if (a == MotionEvent.ACTION_MOVE) {
            float y = ev.getY();
            float step = y - lastY;
            lastY = y;
            if (!pulling) {
                float dy = y - tY, dx = Math.abs(ev.getX() - tX);
                boolean down = dy > 10f * d && dx < dy && scrollY(nsslView) <= 0;
                boolean up = dy < -10f * d && pull > 0f && dx < -dy;
                if (!down && !up) return false;
                pulling = true;
                if (collapsed) {
                    collapsed = false;
                    updateNssl();
                }
                step = dy;
                // a lista não rola junto: cancela o arrasto que ela começou
                MotionEvent c = MotionEvent.obtain(ev);
                c.setAction(MotionEvent.ACTION_CANCEL);
                cancelling = true;
                try {
                    nsslView.dispatchTouchEvent(c);
                } catch (Throwable ignored) {
                }
                cancelling = false;
                c.recycle();
                ViewParent parent = nsslView.getParent();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            }
            float max = pullMax;
            float np = pull + step;
            if (np < 0f) np = 0f;
            if (np > max) np = max + (np - max) * 0.25f;   // um pouco de elástico no fim
            pull = Math.min(np, max + 40f * d);
            updateNssl();
            return true;
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            boolean was = pulling;
            pulling = false;
            tTrack = false;
            if (was) {
                float v = 0f;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    v = vt.getYVelocity();
                }
                float max = pullMax;
                boolean toEnd = Math.abs(v) > 600f * d ? v > 0 : pull > max * 0.4f;
                animatePull(toEnd ? max : 0f);
                return true;
            }
        }
        return false;
    }

    static void animatePull(final float target) {
        if (pullAnim != null) pullAnim.cancel();
        collapsed = false;
        pullAnim = android.animation.ValueAnimator.ofFloat(pull, target);
        pullAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;

            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            public void onAnimationEnd(android.animation.Animator a) {
                if (!cancelled && target > 0f) {
                    collapsed = true;   // chegou ao fim: o player desce de uma vez
                    updateNssl();
                }
            }
        });
        pullAnim.setDuration(380);
        pullAnim.setInterpolator(new android.view.animation.PathInterpolator(0.25f, 0.9f, 0.2f, 1.05f));
        pullAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator va) {
                pull = Math.max(0f, ((Float) va.getAnimatedValue()).floatValue());
                updateNssl();
            }
        });
        pullAnim.start();
    }

    static int scrollY(View nssl) {
        try {
            return fScrollY.getInt(fNsslAmbient.get(nssl));
        } catch (Throwable t) {
            return 0;
        }
    }

    static void setHidden(final boolean h) {
        float target = h ? 1f : 0f;
        if (kgHidden == h && hideP == target) return;
        kgHidden = h;
        if (hideAnim != null) hideAnim.cancel();
        hideAnim = android.animation.ValueAnimator.ofFloat(hideP, target);
        hideAnim.setDuration(Math.round(340 * Math.abs(target - hideP)) + 1);
        hideAnim.setInterpolator(new android.view.animation.PathInterpolator(0.3f, 0f, 0.1f, 1f));
        hideAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator va) {
                hideP = ((Float) va.getAnimatedValue()).floatValue();
                updateNssl();
            }
        });
        hideAnim.start();
        ElasticCard.log(h ? "bloqueio: notificações escondidas (deslizar para baixo)" : "bloqueio: notificações de volta");
    }

    static void updateNssl() {
        View s = nsslRef;
        if (s == null) return;
        try {
            if (mRequestUpdate == null || mRequestUpdate.getDeclaringClass() != s.getClass()) {
                Method m = null;
                for (Class c = s.getClass(); c != null && m == null; c = c.getSuperclass()) {
                    try {
                        m = c.getDeclaredMethod("requestChildrenUpdate");
                    } catch (Throwable ignored) {
                    }
                }
                if (m == null) return;
                m.setAccessible(true);
                mRequestUpdate = m;
            }
            mRequestUpdate.invoke(s);
            s.invalidate();
        } catch (Throwable ignored) {
        }
    }

    /** Altura (na lista) do aviso "N notificações", logo abaixo do player, acima dos atalhos. */
    static float hintY(View nssl) {
        float d = nssl.getResources().getDisplayMetrics().density;
        float e = listEnd;
        if (Float.isNaN(e)) e = nssl.getHeight() - Prefs.kgOffset * d;
        return e - 6f * d;
    }

    /** O aviso desenhado sobre a lista quando as notificações estão escondidas. */
    static final class Hint extends android.graphics.drawable.Drawable {
        final View host;
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Hint(View host) {
            this.host = host;
            p.setColor(0xE6FFFFFF);
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            p.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            p.setShadowLayer(6f, 0f, 1f, 0x66000000);
        }

        public void draw(Canvas c) {
            float a = hideP;
            if (!onKeyguard || a <= 0.01f || hiddenCount <= 0) return;
            float d = host.getResources().getDisplayMetrics().density;
            p.setTextSize(14f * d);
            p.setAlpha(Math.round(230 * a));
            float y = hintY(host);
            float x = host.getWidth() / 2f;
            String s = hiddenCount == 1 ? "1 notificação" : hiddenCount + " notificações";
            p.setStyle(android.graphics.Paint.Style.FILL);
            c.drawText(s, x, y, p);
            p.setStrokeWidth(2f * d);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            float ay = y - 22f * d;
            c.drawLine(x - 9f * d, ay + 4f * d, x, ay - 2f * d, p);
            c.drawLine(x + 9f * d, ay + 4f * d, x, ay - 2f * d, p);
            p.setStyle(android.graphics.Paint.Style.FILL);
        }

        public void setAlpha(int a) {
        }

        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    static boolean keyguardTouch(View nsslView, MotionEvent ev) {
        try {
            if (hideGesture(nsslView, ev)) return true;
        } catch (Throwable ignored) {
        }
        keyguardTouch0(nsslView, ev);
        return false;
    }

    static void keyguardTouch0(View nsslView, MotionEvent ev) {
        if (ev.getActionMasked() != MotionEvent.ACTION_DOWN || !onKeyguard) return;
        if (!wantKeyguardScroll(nsslView)) return;
        float y = ev.getY();
        float top = listTop, end = listEnd;
        boolean inside = neededRange > 0 && !Float.isNaN(top) && !Float.isNaN(end) && y >= top && y <= end;
        // Fora das notificações a lista não rola: o toque volta para o sistema (segurar para
        // editar a tela de bloqueio, dois toques para apagar, desbloquear...).
        try {
            if (fScrollingEnabled.getBoolean(nsslView) != inside) fScrollingEnabled.setBoolean(nsslView, inside);
        } catch (Throwable ignored) {
        }
        if (!inside) return;
        ViewParent parent = nsslView.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
    }

    // ---------------------------------------------------------------- cálculo

    static StackMath math(View host, float pad) {
        float d = host.getResources().getDisplayMetrics().density;
        if (math == null || d != mathDensity || pad != mathPad) {
            math = new StackMath(32f * d, 12f * d, pad);
            mathDensity = d;
            mathPad = pad;
        }
        return math;
    }

    static boolean blurOpened(View host) {
        long now = SystemClock.uptimeMillis();
        if (now - blurAt > 1000L) {
            blurAt = now;
            try {
                blurCached = mBlurOpened != null && Boolean.TRUE.equals(mBlurOpened.invoke(null, host.getContext()));
            } catch (Throwable t) {
                blurCached = false;
            }
        }
        return blurCached;
    }

    static boolean skip(View v) {
        try {
            if (Boolean.TRUE.equals(mIsPinned.invoke(v))) return true;
            if (mAwayAnim != null && Boolean.TRUE.equals(mAwayAnim.invoke(v))) return true;
            ElasticState es = (ElasticState) ElasticCard.states.get(v);
            return es != null && !es.finished;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Grupo em pilha (fechado no nosso estilo): entra na pilha de baixo como um cartão só. */
    static boolean stackedGroup(View v) {
        try {
            return Prefs.groupStack && GroupStack.stacked(v);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean expandedGroup(View v) {
        try {
            return fSummary.getBoolean(v) && fChildrenExpanded.getBoolean(v)
                    && !Boolean.TRUE.equals(mExpChanging.invoke(v));
        } catch (Throwable t) {
            return false;
        }
    }

    static int revealLogs;

    /**
     * No bloqueio, o HyperMusicCover esconde as notificações que viram ilha deixando o
     * transitionAlpha delas em 0, e devolve quando elas saem da ilha. No HyperOS 3 nem sempre
     * ele devolve (falta o gancho de remoção que ele usa), e a notificação fica invisível na
     * gaveta, ocupando o lugar dela. Fora do bloqueio, isso é desfeito aqui.
     */
    static void revealRows(Object ssa) {
        try {
            List list = (List) fVisible.get(fTempState.get(ssa));
            if (list == null) return;
            int n = 0;
            for (int i = 0; i < list.size(); i++) {
                Object o = list.get(i);
                if (rowClass.isInstance(o)) n += reveal((View) o, 0);
            }
            if (n > 0 && revealLogs++ < 6) ElasticCard.log("gaveta: " + n + " notificação(ões) invisível(is) da ilha voltaram a aparecer");
        } catch (Throwable ignored) {
        }
    }

    static int reveal(View v, int depth) {
        int n = 0;
        if (v.getTransitionAlpha() < 0.99f) {
            v.setTransitionAlpha(1f);
            n++;
        }
        // notificações dentro de um grupo
        if (depth < 3 && v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (rowClass.isInstance(c)) n += reveal(c, depth + 1);
                else if (c instanceof ViewGroup && depth < 2) {
                    ViewGroup cg = (ViewGroup) c;
                    for (int j = 0; j < cg.getChildCount(); j++) {
                        View cc = cg.getChildAt(j);
                        if (rowClass.isInstance(cc)) n += reveal(cc, depth + 1);
                    }
                }
            }
        }
        return n;
    }

    static Method mRequestUpdate;

    /** Pede à lista (NotificationStackScrollLayout) que recalcule as posições no próximo quadro. */
    static void requestStackUpdate(View child) {
        try {
            ViewParent pp = child.getParent();
            if (!(pp instanceof View)) return;
            final View stack = (View) pp;
            if (mRequestUpdate == null || mRequestUpdate.getDeclaringClass() != stack.getClass()) {
                Method m = null;
                for (Class c = stack.getClass(); c != null && m == null; c = c.getSuperclass()) {
                    try {
                        m = c.getDeclaredMethod("requestChildrenUpdate");
                    } catch (Throwable ignored) {
                    }
                }
                if (m == null) return;
                m.setAccessible(true);
                mRequestUpdate = m;
            }
            final Method m = mRequestUpdate;
            stack.post(new Runnable() {
                public void run() {
                    try {
                        m.invoke(stack);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** Distância entre a base do cartão do player e o fim da tela (a fileira da ilha fica embaixo). */
    static final float MEDIA_BOTTOM_DP = 116f;
    static int mediaLogs;

    static final int[] loc2 = new int[2];

    /**
     * Com o bloqueio entregue ao HyperMusicCover e a ilha dele ligada, as notificações recolhidas
     * na ilha continuam "à mostra" para a lista, mas ficam apagadas ou fora da tela. Aqui conta se
     * o cartão aparece de verdade: transparência somando a dos pais e posição dentro da tela.
     * Com a ilha desligada, as notificações aparecem e o player não desce por cima delas.
     */
    static boolean reallyOnScreen(View v) {
        float a = 1f;
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) {
            View x = (View) p;
            if (x.getVisibility() != View.VISIBLE) return false;
            a *= x.getAlpha() * x.getTransitionAlpha();
            if (a < 0.05f) return false;
        }
        v.getLocationOnScreen(loc2);
        View root = v.getRootView();
        int sh = root != null && root.getHeight() > 0 ? root.getHeight() : 1 << 20;
        return loc2[1] < sh - 1 && loc2[1] + v.getHeight() > 1;
    }

    static boolean shownView(View v) {
        return v.getVisibility() == View.VISIBLE && v.isAttachedToWindow() && v.getHeight() > 0
                && v.getAlpha() > 0.05f && v.getTransitionAlpha() > 0.05f;
    }

    /**
     * O cartão do player da música (o do HyperOS, que o HyperMusicCover mostra quando a ilha da
     * música abre) fica embaixo, logo acima da fileira da lanterna/ilha/câmera, como no HyperOS 4.
     * O HyperOS 3 põe o cartão no topo da lista, colado no relógio; aqui, quando não há
     * notificações à mostra (elas ficam na ilha), ele desce. O espaço entre o relógio e o cartão é
     * onde o HyperMusicCover desenha a capa do álbum.
     */
    static boolean placeMedia(Object ssa) {
        if (mediaClass == null) return false;
        try {
            View host = (View) fHost.get(ssa);
            List list = (List) fVisible.get(fTempState.get(ssa));
            if (host == null || list == null) return false;
            View media = null;
            Object mvs = null;
            for (int i = 0; i < list.size(); i++) {
                View v = (View) list.get(i);
                Object vs = mGetViewState.invoke(v);
                if (vs == null) continue;
                if (mediaClass.isInstance(v)) {
                    // Só a visibilidade conta: o HyperMusicCover deixa o cartão INVISIBLE enquanto
                    // o player está recolhido e, ao abrir, faz ele aparecer com alpha 0 subindo aos
                    // poucos. Exigir alpha aqui fazia a primeira passada (a da abertura) não
                    // reconhecer o cartão, e ele ficava no topo por segundos até outra passada.
                    if (v.getVisibility() == View.VISIBLE && v.isAttachedToWindow() && v.getHeight() > 0) {
                        media = v;
                        mvs = vs;
                    }
                    continue;
                }
                // notificações à mostra: a lista (e a Lista baixa) cuida da posição de tudo. Com o
                // bloqueio entregue ao HyperMusicCover, as notificações e o player nunca abrem
                // juntos (um recolhe na ilha quando o outro abre), e as que ele está escondendo
                // ainda contam como "à mostra" aqui; por isso, nesse modo, o player desce sempre.
                if (rowClass.isInstance(v) && shownView(v) && !fHidden.getBoolean(vs)
                        && fAlpha.getFloat(vs) > 0.05f && !skip(v) && (!Prefs.kgHmc || reallyOnScreen(v))) {
                    if (mediaLogs++ < 6) ElasticCard.log("player fica no lugar: há notificações à mostra no bloqueio");
                    return false;
                }
            }
            if (media == null) return false;
            float d = host.getResources().getDisplayMetrics().density;
            host.getLocationOnScreen(loc);
            View root = host.getRootView();
            float screenBottom = (root != null && root.getHeight() > 0 ? root.getHeight() : host.getHeight() + loc[1]);
            float h = fHeight.getInt(mvs);
            float want = screenBottom - MEDIA_BOTTOM_DP * d - loc[1] - h;   // topo do cartão, em coordenadas da lista
            float y = fY.getFloat(mvs);
            if (want > y + 0.5f) {
                VS.inv(mSetY, mvs, want);
                if (mediaLogs++ < 4) ElasticCard.log("player da música embaixo: topo " + (int) (want + loc[1]) + "px na tela (antes " + (int) (y + loc[1]) + "px), altura " + (int) h + "px");
            }
            return true;
        } catch (Throwable t) {
            if (mediaLogs++ < 4) ElasticCard.log("player embaixo falhou: " + t);
            return false;
        }
    }

    /** Ponto de entrada: depois que o HOS3 calculou posições, aplica o empilhamento. */
    static void apply(Object ssa, Object amb) {
        try {
            Prefs.refresh();
            ClockFollow.sync();
            boolean keyguard = Boolean.TRUE.equals(mIsOnKeyguard.invoke(amb));
            boolean dozing = fDozeAmount.getFloat(amb) > 0f;
            boolean shade = !keyguard && fShadeExpanded.getBoolean(amb);
            onKeyguard = keyguard;
            if (!keyguard && pull > 0f) {
                // saiu do bloqueio: na próxima vez as notificações aparecem
                if (pullAnim != null) pullAnim.cancel();
                pull = 0f;
                pulling = false;
                collapsed = false;
                lastRows = -1;
            }
            shadeOpen = shade;
            // player da música aberto no bloqueio: o cartão desce para perto da fileira da ilha
            boolean cover = keyguard && !dozing && placeMedia(ssa);
            if (keyguard && !dozing) ClockFollow.setCover(cover);
            else if (!keyguard) ClockFollow.setCover(false);
            if (keyguard && !dozing) ClockFollow.heal();
            if (!keyguard || dozing || !Prefs.kgClock) ClockFollow.update(0f, 0f);
            // fora do bloqueio, nenhuma notificação pode ficar escondida pela ilha do HyperMusicCover
            if (shade) revealRows(ssa);
            if (!Prefs.stEnabled || dozing || (keyguard ? !Prefs.kgEnabled : !shade)) {
                TopBlur.off();
                neededRange = 0;
                listTop = Float.NaN;
                clearAll();
                settle();
                return;
            }
            View host = (View) fHost.get(ssa);
            List list = (List) fVisible.get(fTempState.get(ssa));
            if (host == null || list == null) return;
            float pad = fPadding.getFloat(ssa);
            StackMath m = math(host, pad);
            float d = mathDensity;
            float E;
            if (keyguard) {
                E = host.getHeight() - Prefs.kgOffset * d;
                fixKeyguard(host);
            } else if (ShadeSheet.active()) {
                // cortina: a altura que o HyperOS informa é a da central dele (bem menor), e a cortina
                // ainda desce os cartões depois. O limite é o fim da tela, descontada essa descida.
                E = host.getHeight() - Prefs.stOffset * d - ShadeSheet.shift();
                // nunca por cima da lanterna/câmera da cortina (a posição deles vem do bloqueio)
                float lim = ShadeSheet.buttonLimit(host);
                if (!Float.isNaN(lim)) E = Math.min(E, lim - Prefs.stOffset * d - ShadeSheet.shift());
            } else {
                E = fTopPadding.getInt(amb) + ((Integer) mInnerHeight.invoke(amb, Boolean.FALSE)).intValue()
                        - Prefs.stOffset * d;
            }
            float B = E - m.h1 - m.h2;
            boolean blur = blurOpened(host);
            boolean groups = Prefs.stGroups;
            int scrollY = fScrollY.getInt(amb);

            // 1ª passada: posições reais (o HOS3 trava no fim da área as que transbordam)
            int n = list.size();
            View[] views = new View[n];
            Object[] states = new Object[n];
            float[] ys = new float[n];
            float[] hs = new float[n];
            boolean[] rows = new boolean[n];
            boolean[] skips = new boolean[n];
            float prevBottom = Float.NaN, firstTop = Float.NaN, lastBottom = Float.NaN;
            int last = -1;
            for (int i = 0; i < n; i++) {
                View v = (View) list.get(i);
                Object vs = mGetViewState.invoke(v);
                views[i] = v;
                states[i] = vs;
                if (vs == null) continue;
                float y = fY.getFloat(vs);
                float h = fHeight.getInt(vs);
                if (fInShelf.getBoolean(vs) && !Float.isNaN(prevBottom)) y = prevBottom + pad;
                boolean row = rowClass.isInstance(v);
                // As que o HyperMusicCover guarda na ilha ficam só invisíveis (transitionAlpha 0) e
                // continuam na conta: assim já estão no lugar da lista baixa/pilha quando ele as
                // mostra, em vez de aparecerem no topo e pularem para baixo na passada seguinte.
                boolean sk = row && skip(v);
                ys[i] = y;
                hs[i] = h;
                rows[i] = row;
                skips[i] = sk;
                if (sk) continue;
                prevBottom = y + h;
            }

            for (int i = 0; i < n; i++) {
                if (states[i] == null || skips[i] || !rows[i]) continue;
                if (Float.isNaN(firstTop)) firstTop = ys[i];
                lastBottom = ys[i] + hs[i];
                last = i;
            }

            if (keyguard) {
                int cnt = 0;
                for (int i = 0; i < n; i++) if (states[i] != null && rows[i] && !skips[i]) cnt++;
                // chegou notificação nova (ou não sobrou nenhuma): as escondidas voltam
                if (pull > 0f && !pulling && (cnt == 0 || (lastRows >= 0 && cnt > lastRows))) animatePull(0f);
                if (pull <= 0f) collapsed = false;
                lastRows = cnt;
                hiddenCount = cnt;
            }
            // bloqueio estilo iOS: a lista começa mais baixo; com poucas, encosta no limite de baixo
            float shift = 0f;
            if (keyguard && Prefs.kgIos && !Float.isNaN(firstTop)) {
                float content = lastBottom - firstTop;
                float start = host.getHeight() * (Prefs.kgStart / 100f);
                float wantTop = content <= E - start ? E - content : start;
                shift = wantTop - (firstTop + scrollY);
                if (shift < 0f) shift = 0f;   // nunca sobe acima de onde o HyperOS colocou
            }
            // bloqueio: topo real da tela (em coordenadas da lista) e faixa da barra de status
            float visTop = 0f, fadeTop = 0f;
            if (keyguard) {
                host.getLocationOnScreen(loc);
                visTop = -loc[1];
                fadeTop = visTop + statusBarHeight(host);
            }

            // puxar para baixo: as notificações descem e empilham no limite de baixo
            float pullNow = 0f, pullFrac = 0f;
            if (keyguard && Prefs.kgHide && !Float.isNaN(firstTop)) {
                pullMax = Math.max(0f, E - (firstTop + shift) - 24f * d);
                pullNow = Math.min(pull, pullMax + 40f * d);
                pullFrac = pullMax > 0f ? Math.min(1f, pullNow / pullMax) : 0f;
            } else {
                pullMax = 0f;
            }
            // 2ª passada: aplica deslocamento e empilhamento
            ArrayList<View> touched = new ArrayList<View>();
            for (int i = 0; i < n; i++) {
                View v = views[i];
                Object vs = states[i];
                if (vs == null || skips[i]) continue;
                float y = ys[i] + shift + (rows[i] ? pullNow : 0f);
                float h = hs[i];
                if (!rows[i]) {
                    // o player da música já foi posto embaixo (placeMedia) no modo HyperMusicCover
                    if (keyguard && Prefs.kgHmc && mediaClass != null && mediaClass.isInstance(v)) {
                        // com notificações à mostra (ilha desligada), o player fica logo acima da
                        // primeira delas, em vez de lá em cima perto do relógio
                        if (!cover && !Float.isNaN(firstTop)) {
                            float want = firstTop + shift - 12f * d - h;
                            // puxando: o player desce junto até o lugar de quando está sozinho
                            if (collapsed && pullFrac > 0.9f) {
                                host.getLocationOnScreen(loc);
                                View root = host.getRootView();
                                float sb = (root != null && root.getHeight() > 0 ? root.getHeight() : host.getHeight() + loc[1]);
                                float low = sb - MEDIA_BOTTOM_DP * d - loc[1] - h;
                                if (low > want) want = low;
                            }
                            if (want > fY.getFloat(vs) + 0.5f) VS.inv(mSetY, vs, want);
                        }
                        continue;
                    }
                    if (y != fY.getFloat(vs)) VS.inv(mSetY, vs, y);
                    continue;
                }
                if (keyguard) {
                    // O HOS3 esconde (e corta) as que rolam acima do início original da lista.
                    // Aqui elas continuam visíveis até sair da tela.
                    boolean off = y + h <= visTop;
                    fHidden.setBoolean(vs, off);
                    fClipTop.setInt(vs, 0);
                    if (fLocation != null) fLocation.setInt(vs, off ? 2 : 4);
                }
                boolean first = i == last;
                if (groups && expandedGroup(v) && !stackedGroup(v)) {
                    if (shift != 0f || y != fY.getFloat(vs)) VS.inv(mSetY, vs, y);
                    stackGroup(v, vs, y, h, E, B, m, first, blur, touched);
                    continue;
                }
                float[] r = stackOne(v, vs, y, h, y + h, B, m, first, blur, 0f, -(i + 1));
                if (r != null) {
                    touched.add(v);
                    continue;
                }
                if (y != fY.getFloat(vs)) VS.inv(mSetY, vs, y);
                if (keyguard && y < fadeTop) {
                    // some suavemente ao passar por baixo da barra de status
                    float len = StackMath.clamp(h * 0.6f, 24f * d, 96f * d);
                    float t = StackMath.clamp((y - fadeTop + len) / len, 0f, 1f);
                    VS.inv(mSetAlpha, vs, fAlpha.getFloat(vs) * t);
                }
            }
            // no fim do puxar as notificações somem, ficando só o player
            if (pullFrac > 0.55f) {
                float fade = Math.max(0f, 1f - (pullFrac - 0.55f) / 0.4f);
                for (int i = 0; i < n; i++) {
                    if (states[i] == null || skips[i] || !rows[i]) continue;
                    VS.inv(mSetAlpha, states[i], fAlpha.getFloat(states[i]) * fade);
                    if (fade <= 0.001f) fHidden.setBoolean(states[i], true);
                }
            }
            // onde o player ficou (para o gesto de puxar começar nele)
            mediaTop = mediaBottom = Float.NaN;
            if (keyguard && mediaClass != null) {
                for (int i = 0; i < n; i++) {
                    if (states[i] != null && mediaClass.isInstance(views[i]) && views[i].getVisibility() == View.VISIBLE) {
                        mediaTop = fY.getFloat(states[i]);
                        mediaBottom = mediaTop + hs[i];
                    }
                }
            }
            neededRange = Float.isNaN(lastBottom) ? 0 : Math.max(0, (int) Math.ceil(lastBottom + shift + scrollY - E));
            listTop = Float.isNaN(firstTop) ? Float.NaN : Math.min(firstTop + shift + pullNow, pullNow > 0f ? E - 110f * d : Float.MAX_VALUE);
            if (keyguard && Prefs.kgBlur)    // desfoque quando as notificações passam do topo
                TopBlur.update(host, visTop, Float.isNaN(listTop) ? Float.NaN : listTop - visTop, scrollY, statusBarHeight(host), d);
            else TopBlur.off();
            if (keyguard && Prefs.kgClock)   // relógio é empurrado quando a lista encosta nele
                ClockFollow.follow(Float.isNaN(listTop) ? Float.NaN : listTop - visTop, scrollY, host.getHeight(), d);
            listEnd = E;
            for (int i = 0; i < touched.size(); i++) {
                View v = touched.get(i);
                Info inf = active.get(v);
                if (inf != null) mSetClipBottom.invoke(v, Integer.valueOf(inf.clip));
                v.invalidate();
            }
            settle();
            if (keyguard && !loggedKeyguard && (!touched.isEmpty() || shift > 0f)) {
                loggedKeyguard = true;
                ElasticCard.log("bloqueio: limite=" + (int) E + "px, deslocamento iOS=" + (int) shift + "px, alcance=" + neededRange + "px, topo=" + (int) visTop + "px");
            } else if (!keyguard && !loggedShade && !touched.isEmpty()) {
                loggedShade = true;
                ElasticCard.log("empilhando na gaveta: limite=" + (int) E + "px, alcance=" + neededRange + "px");
            }
        } catch (Throwable t) {
            ElasticCard.log("ERRO no empilhamento: " + t);
        }
    }

    /**
     * O HOS3 copia a escala atual da view para o estado a cada passada. Se um cartão sai da pilha
     * durante uma animação, a escala intermediária "gruda". Por isso, quem saiu da pilha continua
     * recebendo escala 1 no estado até a view realmente voltar a 1.
     */
    static final Map<View, int[]> settling = new WeakHashMap<View, int[]>();   // {passadas restantes, passadas seguidas em 1}

    static void settle() {
        if (settling.isEmpty()) return;
        ArrayList<View> views = new ArrayList<View>(settling.keySet());
        for (int i = 0; i < views.size(); i++) {
            View v = views.get(i);
            int[] st = settling.get(v);
            if (st == null || active.containsKey(v) || (rowClass.isInstance(v) && skip(v))) {
                settling.remove(v);
                continue;
            }
            try {
                Object vs = mGetViewState.invoke(v);
                if (vs != null) {
                    VS.inv(mSetScaleX, vs, 1f);
                    VS.inv(mSetScaleY, vs, 1f);
                }
                st[0]--;
                st[1] = (v.getScaleX() == 1f && v.getScaleY() == 1f) ? st[1] + 1 : 0;
                if (st[1] >= 3 || st[0] <= 0) settling.remove(v);
            } catch (Throwable t) {
                settling.remove(v);
            }
        }
    }

    /**
     * Empilha uma linha (ou um filho de grupo). y/c em coordenadas absolutas da lista; base é o
     * quanto somar para voltar à coordenada do estado (0 para linhas, -yDoGrupo para filhos).
     */
    static float[] stackOne(View v, Object vs, float y, float h, float c, float B, StackMath m,
                            boolean first, boolean blur, float base, float z) throws Throwable {
        float d = mathDensity;
        float piv = v.getPivotY();
        // grupo em pilha (cartões de trás aparecendo embaixo): empilha inteiro, como um cartão
        // normal; cortar a parte de baixo dele apagava os cartões de trás e deixava um vão
        boolean grp = false;
        try {
            grp = Prefs.groupStack && GroupStack.stacked(v);
        } catch (Throwable ignored) {
        }
        float[] r = (h <= 107f * d || grp) ? m.normal(first, h, c, B, piv, 0f) : m.big(h, c, 72f * d, piv, B, first, 0f);
        if (r == null) {
            restore(v, vs);
            return null;
        }
        VS.inv(mSetY, vs, y + base + r[0]);
        VS.inv(mSetScaleX, vs, r[1]);
        VS.inv(mSetScaleY, vs, r[1]);
        VS.inv(mSetZ, vs, z);
        fInShelf.setBoolean(vs, false);
        fClipTop.setInt(vs, 0);
        fHidden.setBoolean(vs, r[3] > 3f);
        float a = fAlpha.getFloat(vs);
        if (onKeyguard && a < 1f && r[3] <= 2f) a = 1f;   // o HOS3 apaga as que passam do limite dele
        if (!blur && r[3] > 2f) a = a * StackMath.clamp(3f - r[3], 0f, 1f);
        VS.inv(mSetAlpha, vs, a);
        Info inf = active.get(v);
        if (inf == null) {
            inf = new Info();
            active.put(v, inf);
        }
        inf.clip = (int) r[2];
        inf.prog = r[3];
        inf.dim = StackMath.dimAlpha(r[3]);
        inf.front = first;
        return r;
    }

    /** Grupo expandido: cada filho empilha como um cartão; o fundo do grupo é cortado no limite. */
    static void stackGroup(View row, Object vs, float gy, float gh, float E, float B, StackMath m,
                           boolean lastRow, boolean blur, ArrayList<View> touched) throws Throwable {
        Object container = fChildrenContainer.get(row);
        List kids = container != null ? (List) fAttached.get(container) : null;
        if (kids == null || kids.isEmpty()) return;
        boolean any = false;
        int k = kids.size();
        for (int j = 0; j < k; j++) {
            View ch = (View) kids.get(j);
            Object cvs = mGetViewState.invoke(ch);
            if (cvs == null) continue;
            float cy = fY.getFloat(cvs);
            float chh = fHeight.getInt(cvs);
            float ay = gy + cy;
            float[] r = stackOne(ch, cvs, ay, chh, ay + chh, B, m, lastRow && j == k - 1, blur, -gy, -(j + 1));
            if (r != null) {
                any = true;
                touched.add(ch);
            }
        }
        if (any) {
            Info inf = active.get(row);
            if (inf == null) {
                inf = new Info();
                active.put(row, inf);
            }
            inf.clip = Math.max(0, (int) (gy + gh - E));
            inf.prog = 0f;
            inf.dim = 0;
            touched.add(row);
            if (!loggedGroup) {
                loggedGroup = true;
                ElasticCard.log("grupo expandido empilhando por cartão (" + k + " filhos)");
            }
        } else if (active.remove(row) != null) {
            mSetClipBottom.invoke(row, Integer.valueOf(0));
        }
    }

    static int statusBarHeight(View host) {
        if (statusBar < 0) {
            int px = 0;
            try {
                int id = host.getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id != 0) px = host.getResources().getDimensionPixelSize(id);
            } catch (Throwable ignored) {
            }
            statusBar = px > 0 ? px : (int) (28f * host.getResources().getDisplayMetrics().density);
        }
        return statusBar;
    }

    /** No bloqueio, garante que o HOS3 mostre todas as notificações (sem o limite dele). */
    static void fixKeyguard(View host) {
        try {
            if (fMaxDisplayed.getInt(host) == -1) return;
            long now = SystemClock.uptimeMillis();
            if (now - maxFixAt < 500L) return;
            maxFixAt = now;
            final View h = host;
            host.post(new Runnable() {
                public void run() {
                    try {
                        mSetMaxDisplayed.invoke(h, Integer.valueOf(-1));
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    static void restore(View v, Object vs) {
        if (active.remove(v) == null) return;
        settling.put(v, new int[]{600, 0});
        try {
            VS.inv(mSetScaleX, vs, 1f);
            VS.inv(mSetScaleY, vs, 1f);
            mSetClipBottom.invoke(v, Integer.valueOf(0));
            v.invalidate();
        } catch (Throwable ignored) {
        }
    }

    static void clearAll() {
        if (active.isEmpty()) return;
        ArrayList<View> views = new ArrayList<View>(active.keySet());
        for (int i = 0; i < views.size(); i++) {
            View v = views.get(i);
            try {
                Object vs = mGetViewState.invoke(v);
                if (vs != null) restore(v, vs);
                else active.remove(v);
            } catch (Throwable t) {
                active.remove(v);
            }
        }
    }

    /** Escurece só o fundo do cartão empilhado (como o HOS4), por baixo do conteúdo. */
    static void dim(Object row, Canvas canvas, Object child) {
        Info inf = active.get(row);
        if (inf == null || inf.dim <= 0) return;
        try {
            if (child != fBackground.get(row)) return;
            Path path = (Path) mGetClipPath.invoke(row, Boolean.FALSE);
            int save = canvas.save();
            if (path != null) canvas.clipPath(path);
            canvas.drawColor(inf.dim << 24);
            canvas.restoreToCount(save);
        } catch (Throwable ignored) {
        }
    }
}
