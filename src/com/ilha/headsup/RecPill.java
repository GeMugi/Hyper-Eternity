package com.ilha.headsup;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.widget.TextView;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Gravação de tela: o relógio da barra de status ganha uma pílula vermelha atrás, como no iOS.
 * A gravação é percebida pela notificação do gravador do HyperOS (com.miui.screenrecorder) ou
 * pelo gravador do próprio Android (RecordingController).
 */
final class RecPill {
    private RecPill() {}

    static final Map<View, Boolean> recRows = new WeakHashMap<>();
    static final Map<View, Object> padded = new WeakHashMap<>();
    static Object controller, location, hotspot;
        static int color = 0xFFFF3B30, target;          // cor atual e cor pedida (0 = nenhuma)
    static float prog;                       // 0 = sem pílula, 1 = pílula inteira
    static long lastFrame;
    static android.os.Handler h;
    static Class clockCls;
    static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final RectF r = new RectF();
    static boolean last;

    static void install(ClassLoader cl) {
        try {
            clockCls = Class.forName("com.android.systemui.statusbar.views.MiuiClock", false, cl);
        } catch (Throwable t) {
            try {
                clockCls = Class.forName("com.android.systemui.statusbar.policy.Clock", false, cl);
            } catch (Throwable ignored) {
            }
        }
        try {
            Class rc = Class.forName("com.android.systemui.screenrecord.RecordingController", false, cl);
            for (java.lang.reflect.Constructor k : rc.getDeclaredConstructors()) {
                XposedBridge.hookMethod(k, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        controller = p.thisObject;
                    }
                });
            }
        } catch (Throwable ignored) {
        }
        for (String[] cn : new String[][]{{"com.android.systemui.statusbar.policy.LocationControllerImpl", "l"}, {"com.android.systemui.statusbar.policy.HotspotControllerImpl", "h"}}) {
            try {
                final String tag = cn[1];
                Class lc = Class.forName(cn[0], false, cl);
                for (java.lang.reflect.Constructor k : lc.getDeclaredConstructors()) {
                    XposedBridge.hookMethod(k, new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (tag.equals("l")) location = p.thisObject;
                            else hotspot = p.thisObject;
                        }
                    });
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            XposedBridge.hookMethod(TextView.class.getDeclaredMethod("onDraw", Canvas.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    Object v = p.thisObject;
                    if (clockCls == null || !clockCls.isInstance(v)) return;
                    try {
                        draw((TextView) v, (Canvas) p.args[0]);
                    } catch (Throwable ignored) {
                    }
                }

                protected void afterHookedMethod(MethodHookParam p) {
                    Object v = p.thisObject;
                    if (clockCls == null || !clockCls.isInstance(v)) return;
                    try {
                        // nada
                    } catch (Throwable ignored) {
                    }
                }
            });
            watch();
            ElasticCard.log("pílula da gravação: pronta");
        } catch (Throwable t) {
            ElasticCard.log("pílula da gravação indisponível: " + t);
        }
    }

    /** Cor que a pílula deve ter agora (0 = nenhuma). Gravação > roteador > localização. */
    static int wanted() {
        if (Prefs.recPill && recording()) return Prefs.recColor | 0xFF000000;
        try {
            if (Prefs.hotPill && hotspot != null && Integer.valueOf(13).equals(Refl.get(hotspot, "mHotspotState"))) return Prefs.hotColor | 0xFF000000;
        } catch (Throwable ignored) {
        }
        try {
            if (Prefs.locPill && location != null && Boolean.TRUE.equals(Refl.get(location, "mAreActiveLocationRequests"))) return Prefs.locColor | 0xFF000000;
        } catch (Throwable ignored) {
        }
        return 0;
    }

    static boolean recording() {
        if (!Prefs.recPill) return false;
        try {
            if (controller != null && Boolean.TRUE.equals(Refl.get(controller, "mIsRecording"))) return true;
        } catch (Throwable ignored) {
        }
        synchronized (recRows) {
            for (View v : recRows.keySet()) if (v.isAttachedToWindow()) return true;
        }
        return projecting;
    }

    static boolean projecting, projLogged;
    static java.lang.reflect.Method mActive;
    static Object mpm;

    /** HyperOS 4: o gravador não deixa notificação durante a gravação; olha a captura de tela ativa. */
    static void pollProjection() {
        if (!Prefs.recPill) {
            projecting = false;
            return;
        }
        try {
            if (mpm == null) {
                android.content.Context ctx = (android.content.Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
                if (ctx == null) return;
                mpm = ctx.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE);
                mActive = mpm.getClass().getMethod("getActiveProjectionInfo");
            }
            Object info = mActive.invoke(mpm);
            boolean now = info != null;
            if (now != projecting) {
                projecting = now;
                if (!projLogged || now) {
                    projLogged = true;
                    ElasticCard.log("pílula da gravação: captura de tela " + (now ? "ativa (" + info + ")" : "parou"));
                }
            }
        } catch (Throwable t) {
            if (!projLogged) {
                projLogged = true;
                ElasticCard.log("pílula da gravação: captura indisponível: " + t);
            }
            mpm = null;
        }
    }

    /** Chamado a cada notificação montada ou atualizada. */
    static void row(View row) {
        if (!Prefs.recPill) return;
        android.service.notification.StatusBarNotification s = Privacy.sbn(row);
        if (s == null || !"com.miui.screenrecorder".equals(s.getPackageName()) || !s.isOngoing()) return;
        synchronized (recRows) {
            if (recRows.containsKey(row)) return;
            recRows.put(row, Boolean.TRUE);
        }
        row.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) {
                refresh();
            }

            public void onViewDetachedFromWindow(View v) {
                refresh();
            }
        });
        refresh();
    }

    static void refresh() {
        for (View c : StatusClock.clocks.keySet()) c.postInvalidateOnAnimation();
    }

    static void draw(TextView v, Canvas c) {
        float d = v.getResources().getDisplayMetrics().density;
        if (!(v.getHeight() > 0 && v.getHeight() < 40f * d)) return;
        if (!inStatusBar(v)) return;   // só o relógio da barra de status (não as datas da central)
        tick();
        int pad = Math.round(7f * d * ease(prog));
        Object was = padded.get(v);
        int old = was instanceof Integer ? ((Integer) was).intValue() : 0;
        if (pad != old) {
            if (pad == 0) padded.remove(v);
            else padded.put(v, Integer.valueOf(pad));
            v.setPadding(v.getPaddingLeft() - old + pad, v.getPaddingTop(), v.getPaddingRight() - old + pad, v.getPaddingBottom());
        }
        if (prog <= 0f) {
            backdrop(v, false, 0f);
            return;
        }
        float e = ease(prog);
        float hh = Math.min(v.getHeight(), v.getTextSize() * 1.45f);
        float top = (v.getHeight() - hh) / 2f;
        float w = v.getWidth(), cx = w / 2f, half = w / 2f * (0.6f + 0.4f * e);
        r.set(cx - half, top + hh * 0.5f * (1f - e), cx + half, top + hh - hh * 0.5f * (1f - e));
        boolean vib = Prefs.pillVib;
        if (vib) backdrop(v, true, Math.min(1f, prog * 1.4f));
        else backdrop(v, false, 0f);
        paint.setColor(color);
        // com vibrance: cor translúcida, a tela de trás aparece misturada (o desfoque do HyperOS fica por baixo, se a barra aceitar)
        paint.setAlpha(Math.round((vib ? 150 : 255) * Math.min(1f, prog * 1.4f)));
        float rad = r.height() / 2f;
        c.drawRoundRect(r, rad, rad, paint);
    }

    static final Map<View, Object> rootOk = new WeakHashMap<>();
    static boolean rootLogged;

    static boolean inStatusBar(View v) {
        Object k = rootOk.get(v);
        if (k == null) {
            View root = v.getRootView();
            String n = root == null ? "" : root.getClass().getName();
            String id = "";
            try {
                if (v.getId() != View.NO_ID) id = v.getResources().getResourceEntryName(v.getId());
            } catch (Throwable ignored) {
            }
            // fora: datas e relógios da central de notificações / de controle
            boolean ok = !(id.contains("date") || id.equals("horizontal_time") || id.equals("big_time")
                    || n.contains("NotificationShade") || n.contains("ControlCenter") || n.contains("Keyguard"));
            if (!rootLogged || ok) {
                rootLogged = true;
                ElasticCard.log("pílula: relógio " + id + " em " + n + " -> " + (ok ? "usa" : "ignora"));
            }
            if (!v.isAttachedToWindow()) return ok;
            k = Boolean.valueOf(ok);
            rootOk.put(v, k);
        }
        return Boolean.TRUE.equals(k);
    }

    static java.lang.reflect.Method mPass, mMode, mRadius, mAdd, mClear;
    static boolean blendBroken;
    static final Map<View, Float> blended = new WeakHashMap<>();
    static final RectF outline = new RectF();

    /**
     * Pílula com vibrance de verdade: o próprio relógio vira uma "janela" do desfoque do HyperOS
     * que mistura a cor da pílula com o que está passando atrás (o mesmo dos blocos coloridos da central).
     */
    static boolean backdrop(final View v, boolean on, float a) {
        if (blendBroken) return false;
        try {
            if (mMode == null) {
                mPass = View.class.getMethod("setPassWindowBlurEnabled", boolean.class);
                mMode = View.class.getMethod("setMiBackgroundBlurMode", int.class);
                mRadius = View.class.getMethod("setMiBackgroundBlurRadius", int.class);
                mAdd = View.class.getMethod("addMiBackgroundBlendColor", int.class, int.class);
                mClear = View.class.getMethod("clearMiBackgroundBlendColor");
            }
            Float was = blended.get(v);
            if (!on) {
                if (was == null) return false;
                blended.remove(v);
                mClear.invoke(v);
                mMode.invoke(v, Integer.valueOf(0));
                v.setClipToOutline(false);
                v.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
                return false;
            }
            if (was == null) {
                mPass.invoke(v, Boolean.TRUE);
                mMode.invoke(v, Integer.valueOf(1));
                mRadius.invoke(v, Integer.valueOf(Math.round(12 * v.getResources().getDisplayMetrics().density)));
                v.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    public void getOutline(View view, android.graphics.Outline o) {
                        o.setRoundRect(Math.round(outline.left), Math.round(outline.top), Math.round(outline.right), Math.round(outline.bottom), outline.height() / 2f);
                    }
                });
                v.setClipToOutline(true);
                ElasticCard.log("pílula: vibrance com o fundo ligada");
            }
            if (!outline.equals(r)) {
                outline.set(r);
                v.invalidateOutline();
            }
            float key = a + color;
            if (was == null || was.floatValue() != key) {
                mClear.invoke(v);
                mAdd.invoke(v, Integer.valueOf(scale(0xBF7D7D7D, a)), Integer.valueOf(100));
                mAdd.invoke(v, Integer.valueOf(scale(color | 0xFF000000, a)), Integer.valueOf(106));
                blended.put(v, Float.valueOf(key));
            }
            return true;
        } catch (Throwable t) {
            blendBroken = true;
            ElasticCard.log("pílula: vibrance com o fundo indisponível: " + t);
            return false;
        }
    }

    static int scale(int c, float a) {
        int al = Math.round(((c >>> 24) & 255) * a);
        return (c & 0x00FFFFFF) | (al << 24);
    }

    static java.lang.reflect.Field fCur;
    static final Object NO = new Object();

    /**
     * Vibrance do iOS: a hora vira um recorte na pílula e deixa ver o que está passando atrás dela.
     * A pílula e o texto vão para uma camada própria; o texto apaga a pílula (DST_OUT) onde passa.
     */
    static void vibOn(TextView v, XC_MethodHook.MethodHookParam p) throws Throwable {
        p.setObjectExtra("vib", null);
        if (!Prefs.pillVib || prog <= 0f) return;
        float d = v.getResources().getDisplayMetrics().density;
        if (!(v.getHeight() > 0 && v.getHeight() < 40f * d)) return;
        if (fCur == null) {
            fCur = TextView.class.getDeclaredField("mCurTextColor");
            fCur.setAccessible(true);
        }
        Canvas c = (Canvas) p.args[0];
        int sc = c.saveLayer(0, 0, v.getWidth(), v.getHeight(), null);
        int cur = fCur.getInt(v);
        p.setObjectExtra("vib", new int[]{cur, sc});
        // na entrada/saída o recorte acompanha a pílula
        int a = Math.round(255 * ease(prog));
        fCur.setInt(v, (a << 24) | 0x000000);
        v.getPaint().setBlendMode(android.graphics.BlendMode.DST_OUT);
    }

    static void vibOff(TextView v, XC_MethodHook.MethodHookParam p) throws Throwable {
        Object was = p.getObjectExtra("vib");
        if (!(was instanceof int[])) return;
        int[] w = (int[]) was;
        fCur.setInt(v, w[0]);
        v.getPaint().setBlendMode(null);
        ((Canvas) p.args[0]).restoreToCount(w[1]);
    }

    static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return 1f - (1f - t) * (1f - t) * (1f - t);   // sai rápido e assenta devagar
    }

    /** Avança a animação: aparece/some em ~320 ms; troca de cor some e volta na cor nova. */
    static void tick() {
        long now = android.os.SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        int want = wanted();
        target = want;
        boolean show = want != 0 && want == color;
        if (want != 0 && want != color && prog <= 0f) {
            color = want;
            show = true;
        }
        float to = show ? 1f : 0f;
        if (prog != to) {
            float step = dt / 0.32f;
            prog = to > prog ? Math.min(to, prog + step) : Math.max(to, prog - step);
            if (prog != to) refresh();   // continua no próximo quadro
            else lastFrame = 0;
        } else lastFrame = 0;
        if (prog == to && want != 0 && want != color) refresh();
    }

    /** Confere a cada segundo se algo ligou/desligou (localização e roteador não avisam). */
    static void watch() {
        if (h == null) h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            public void run() {
                try {
                    pollProjection();
                    int w = wanted();
                    if ((w == 0) != (prog <= 0f) || (w != 0 && w != color)) refresh();
                } catch (Throwable ignored) {
                }
                h.postDelayed(this, 1000);
            }
        }, 1000);
    }
}
