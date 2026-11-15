package com.ilha.headsup;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Correções do HyperMusicCover-Enhanced (HMC) para o HyperOS 3, feitas daqui, sem alterar o APK
 * dele: o HMC continua com a assinatura oficial e as extensões dele (HyperImmersive/StandBy,
 * HyperCanvas) continuam a reconhecê-lo. O HMC roda na Interface do Sistema, como o Hyper
 * Eternity, então as correções entram por ganchos no mesmo processo.
 *
 * 1) Vidro da ilha e dos atalhos: o "Blur background" do HMC pede por reflexão duas funções que
 *    só existem no HyperOS 4 (MiuiBlurUtils.setMiBlurWinType e View.setMiViewMaterialType). No
 *    HyperOS 3 a primeira falta e o desfoque inteiro desiste. Quando o HMC (e só ele) pede uma
 *    delas e ela não existe, recebe uma substituta que não faz nada, e o desfoque segue com as
 *    funções que o HyperOS 3 tem. A camada de cor criada em seguida passa a acompanhar a capa
 *    ou o papel de parede.
 * 2) Tempo das letras: a posição que guia as letras vem de uma cópia guardada do estado do
 *    player, que fica velha quando o aviso de mudança se perde. Ela é relida do player (no
 *    máximo a cada 100 ms) logo antes de o HMC calcular a posição, e o resultado recebe o ajuste
 *    "Adiantar a letra". O método muda de nome a cada versão (R na 1.1.1, S na 1.1.1-2), então é
 *    reconhecido pelo valor que devolve.
 */
public final class HmcFix {
    private HmcFix() {}

    static volatile ClassLoader loader;
    static volatile boolean active;
    static final Set<ClassLoader> checked = Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>());
    static final Map<String, Boolean> callers = new ConcurrentHashMap<>();
    static Method stubWin, noopMat;
    static int logs;

    static void log(String m) {
        if (logs++ < 40) XposedBridge.log("[HeadsUpIlha] HMC: " + m);
    }

    static void install(ClassLoader cl) {
        try {
            stubWin = HmcFix.class.getDeclaredMethod("setMiBlurWinType", View.class);
            stubWin.setAccessible(true);
            // setMiViewMaterialType(0) vira setImportantForAutofill(0): 0 é o valor padrão, nada muda
            noopMat = View.class.getMethod("setImportantForAutofill", int.class);
            Method gm = Class.class.getMethod("getMethod", String.class, Class[].class);
            XposedBridge.hookMethod(gm, new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!(p.getThrowable() instanceof NoSuchMethodException) || !active) return;
                    Object n = p.args[0];
                    Class<?>[] pt = p.args.length > 1 ? (Class<?>[]) p.args[1] : null;
                    if ("setMiBlurWinType".equals(n)) {
                        if (pt == null || pt.length != 1 || pt[0] != View.class || !fromHmc()) return;
                        p.setResult(stubWin);
                        once("vidro da ilha: HyperOS 3, desfoque segue sem o tipo de janela do HyperOS 4");
                    } else if ("setMiViewMaterialType".equals(n) && p.thisObject == View.class) {
                        if (pt == null || pt.length != 1 || pt[0] != int.class || !fromHmc()) return;
                        p.setResult(noopMat);
                    }
                }
            });
        } catch (Throwable t) {
            log("sem gancho do vidro: " + t);
        }
    }

    static boolean said;

    static void once(String m) {
        if (!said) {
            said = true;
            log(m);
        }
    }

    /** Toda view que entra numa janela passa por aqui até o HMC ser achado (pelo carregador da classe dela). */
    static void seen(View v) {
        if (loader != null || v == null) return;
        ClassLoader l = v.getClass().getClassLoader();
        if (l == null || l == View.class.getClassLoader() || l == HmcFix.class.getClassLoader()) return;
        synchronized (checked) {
            if (!checked.add(l)) return;
        }
        try {
            Class.forName("com.os4.musiccover.Main", false, l);
        } catch (Throwable t) {
            return;
        }
        found(l);
    }

    static synchronized void found(ClassLoader l) {
        if (loader != null || l == null) return;
        loader = l;
        // a versão do HMC com o patch antigo (APK reassinado) já faz tudo isto por dentro
        boolean patched = false;
        try {
            patched = Class.forName("com.ilha.headsup.HmcGlass", false, l).getClassLoader() == l;
        } catch (Throwable ignored) {
        }
        if (patched) {
            log("encontrado (versão com patch no APK: as correções daqui ficam desligadas)");
            return;
        }
        active = true;
        lyrics(l);
        nullSession(l);
        log("encontrado; correções do HyperOS 3 ativas");
    }

    /**
     * O HMC atualiza o player com o controle da sessão de mídia sem conferir se ele ainda existe. Quando
     * a sessão some no meio (troca de saída de áudio, app que recria a sessão), ele derrubava a Interface
     * do Sistema em loop. Sem controle, a atualização é pulada.
     */
    static void nullSession(ClassLoader l) {
        try {
            Class<?> c = Class.forName("com.os4.musiccover.d0", false, l);
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (!Modifier.isStatic(m.getModifiers()) || pt.length == 0 || pt[0] != MediaController.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.args[0] != null) return;
                        p.setResult(null);
                        if (nullLogs++ < 5) log("player: sessão de mídia sumiu, atualização pulada (" + p.method.getName() + ")");
                    }
                });
                n++;
            }
            log("player: " + n + " atualizações protegidas contra sessão vazia");
        } catch (Throwable t) {
            log("player: sem proteção de sessão vazia: " + t);
        }
    }

    static int nullLogs;

    /** A chamada vem do HMC? (alguma das classes na pilha é dele) */
    static boolean fromHmc() {
        ClassLoader l = loader;
        if (l == null) return false;
        StackTraceElement[] st = new Throwable().getStackTrace();
        for (int i = 0; i < st.length && i < 30; i++) {
            String c = st[i].getClassName();
            Boolean b = callers.get(c);
            if (b == null) {
                b = Boolean.FALSE;
                if (!c.startsWith("java.") && !c.startsWith("android.") && !c.startsWith("dalvik.") && !c.startsWith("com.ilha.headsup.")) {
                    try {
                        b = Class.forName(c, false, l).getClassLoader() == l;
                    } catch (Throwable ignored) {
                    }
                }
                callers.put(c, b);
            }
            if (b) return true;
        }
        return false;
    }

    // ---------- vidro da ilha ----------

    /** No lugar de MiuiBlurUtils.setMiBlurWinType(view), que não existe no HyperOS 3. */
    public static void setMiBlurWinType(View parent) {
        if (!(parent instanceof ViewGroup)) return;
        final ViewGroup g = (ViewGroup) parent;
        // o HMC cria a camada de cor (um GradientDrawable numa ImageView filha) logo depois
        g.post(new Runnable() {
            public void run() {
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c instanceof ImageView && ((ImageView) c).getDrawable() instanceof GradientDrawable) overlay((ImageView) c);
                }
            }
        });
    }

    static final WeakHashMap<ImageView, int[]> tints = new WeakHashMap<>();
    static Method coverTint;
    static int wallColor;
    static long wallAt;
    static boolean tintLogged;

    /** A camada de cor passa a acompanhar o fundo (mesmo brilho escolhido no HMC). */
    static void overlay(final ImageView iv) {
        Drawable d = iv.getDrawable();
        if (!(d instanceof GradientDrawable)) return;
        android.content.res.ColorStateList cs = ((GradientDrawable) d).getColor();
        if (cs == null) return;
        int base = cs.getDefaultColor();
        int[] old = tints.get(iv);
        if (old != null && old[1] == base) return;   // a cor é a nossa (não foi recriada)
        boolean fresh = old == null;
        tints.put(iv, new int[]{base, base});
        if (fresh) iv.post(new Runnable() {
            public void run() {
                if (!tints.containsKey(iv)) return;
                try {
                    step(iv);
                } catch (Throwable ignored) {
                }
                if (iv.isAttachedToWindow() || iv.getParent() != null) iv.postDelayed(this, iv.isShown() && Power.interactive() ? 120 : 1000);
            }
        });
    }

    static void step(ImageView iv) {
        int[] st = tints.get(iv);
        Drawable d = iv.getDrawable();
        if (st == null || !(d instanceof GradientDrawable) || !iv.isShown()) return;
        // o HMC trocou a cor (ex.: modo claro/escuro): ela vira a nova base
        android.content.res.ColorStateList now = ((GradientDrawable) d).getColor();
        if (now != null && now.getDefaultColor() != st[1]) st[0] = st[1] = now.getDefaultColor();
        int bg = background(iv);
        int base = st[0];
        int want = base;
        if (bg != 0) {
            float k = 0.45f;
            want = (base & 0xFF000000)
                    | (mix((base >> 16) & 255, (bg >> 16) & 255, k) << 16)
                    | (mix((base >> 8) & 255, (bg >> 8) & 255, k) << 8)
                    | mix(base & 255, bg & 255, k);
        }
        int cur = st[1];
        if (cur == want) return;
        int next = (want & 0xFF000000)
                | (ease((cur >> 16) & 255, (want >> 16) & 255) << 16)
                | (ease((cur >> 8) & 255, (want >> 8) & 255) << 8)
                | ease(cur & 255, want & 255);
        st[1] = next;
        ((GradientDrawable) d).setColor(next);
        if (!tintLogged) {
            tintLogged = true;
            log("vidro da ilha: cor acompanhando o fundo");
        }
    }

    static int mix(int a, int b, float k) {
        return Math.max(0, Math.min(255, Math.round(a + (b - a) * k)));
    }

    static int ease(int a, int b) {
        int d = b - a;
        if (Math.abs(d) <= 3) return b;
        return a + Math.round(d * 0.3f);
    }

    /** Cor do que está atrás: a média da capa grande (Main.coverTint do HMC), ou a do papel de parede do bloqueio. */
    static int background(View v) {
        try {
            if (coverTint == null) {
                coverTint = Class.forName("com.os4.musiccover.Main", false, loader).getDeclaredMethod("coverTint");
                coverTint.setAccessible(true);
            }
            int t = (Integer) coverTint.invoke(null);
            if (t != 0) return t;
        } catch (Throwable ignored) {
        }
        long now = SystemClock.uptimeMillis();
        if (now - wallAt > 3000) {
            wallAt = now;
            try {
                android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(v.getContext());
                android.app.WallpaperColors c = wm.getWallpaperColors(android.app.WallpaperManager.FLAG_LOCK);
                if (c == null) c = wm.getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM);
                wallColor = c == null ? 0 : c.getPrimaryColor().toArgb();
            } catch (Throwable t) {
                wallColor = 0;
            }
        }
        return wallColor;
    }

    // ---------- tempo das letras ----------

    static Field fM, fN, fO;
    static volatile Method pos;
    static final Map<Method, Integer> hits = new HashMap<>();
    static int looks;
    static boolean gaveUp;
    static long lastFetch;
    static int freshLogs;

    static void lyrics(ClassLoader l) {
        try {
            Class<?> d0 = Class.forName("com.os4.musiccover.d0", false, l);
            for (Field f : d0.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() == MediaController.class) fM = f;
                else if (f.getType() == PlaybackState.class) fN = f;
                else if (f.getType() == long.class && f.getName().equals("o")) fO = f;
            }
            if (fM == null || fN == null) {
                log("letras: estado do player não encontrado nesta versão");
                return;
            }
            fM.setAccessible(true);
            fN.setAccessible(true);
            if (fO != null) fO.setAccessible(true);
            int n = 0;
            for (final Method m : d0.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) || m.getParameterTypes().length != 0 || m.getReturnType() != int.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam p) {
                        Method c = pos;
                        if (c == m || (c == null && !gaveUp)) fresh();
                    }

                    protected void afterHookedMethod(MethodHookParam p) {
                        Method c = pos;
                        if (c == m) shift(p);
                        else if (c == null && !gaveUp && p.getResult() instanceof Integer) identify(m, (Integer) p.getResult());
                    }
                });
                n++;
            }
            log("letras: " + n + " candidatos para a posição");
        } catch (Throwable t) {
            log("letras: " + t);
        }
    }

    /** Relê o estado do player (como o HMC faz a cada 1 s), no máximo a cada 100 ms. */
    static void fresh() {
        try {
            MediaController c = (MediaController) fM.get(null);
            if (c == null) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastFetch < 100L) return;
            lastFetch = now;
            PlaybackState live = c.getPlaybackState();
            if (live == null) return;
            PlaybackState old = (PlaybackState) fN.get(null);
            if (old != null && freshLogs < 5) {
                long d = at(live) - at(old);
                if (d > 400L || d < -400L) {
                    freshLogs++;
                    log("letras: a posição guardada estava " + (d > 0 ? "atrasada " : "adiantada ") + Math.abs(d) + " ms, corrigida");
                }
            }
            fN.set(null, live);
            if (fO != null) fO.setLong(null, now);
        } catch (Throwable ignored) {
        }
    }

    /** O método da posição devolve exatamente a posição do estado guardado (os outros, não). */
    static void identify(Method m, int r) {
        try {
            PlaybackState s = (PlaybackState) fN.get(null);
            if (s == null || s.getState() != PlaybackState.STATE_PLAYING) return;
            long e = at(s);
            synchronized (hits) {
                if (pos != null) return;
                if (e > 1500 && Math.abs(r - e) <= 60) {
                    Integer h = hits.get(m);
                    int k = h == null ? 1 : h + 1;
                    hits.put(m, k);
                    if (k >= 3) {
                        pos = m;
                        log("letras: posição em d0." + m.getName() + "()");
                    }
                } else if (++looks > 20000) {
                    gaveUp = true;
                    log("letras: método da posição não reconhecido nesta versão");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static void shift(XC_MethodHook.MethodHookParam p) {
        int s = Prefs.lyricShift;
        if (s == 0 || !(p.getResult() instanceof Integer)) return;
        try {
            if (fN.get(null) == null) return;
        } catch (Throwable t) {
            return;
        }
        long r = (Integer) p.getResult() + s * 100L;
        p.setResult((int) Math.max(0L, Math.min(Integer.MAX_VALUE, r)));
    }

    /** Mesma conta do HMC: posição + tempo passado desde a última atualização, se tocando. */
    static long at(PlaybackState s) {
        long p = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            long e = SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime();
            if (e > 0) p += (long) (s.getPlaybackSpeed() * e);
        }
        return p;
    }
}
