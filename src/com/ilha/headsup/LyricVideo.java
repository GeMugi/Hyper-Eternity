package com.ilha.headsup;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;

import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Letras do HyperMusicCover no estilo "vídeo lírico": duas linhas por vez, centralizadas, em letra
 * de pincel (Sedgwick Ave Display, OFL), que aparecem pintadas em pedaços e saem descendo e sumindo.
 * O HMC continua decidindo quando e onde as letras aparecem; só o desenho da view dele é trocado.
 */
final class LyricVideo {
    private LyricVideo() {}

    static Class<?> lyricCls;
    static Field fLines, fText, fStart, fEnd;
    static int logs;

    static void log(String m) {
        if (logs++ < 40) XposedBridge.log("[HeadsUpIlha] letras vídeo: " + m);
    }

    static java.lang.ref.WeakReference<View> lyricRef;

    // ------------------------------------------------------------------ fundo escuro com as letras

    /**
     * Uma camada preta no fundo da janela do bloqueio (atrás do relógio, das notificações e do
     * player, logo acima do papel de parede): escurece só o fundo enquanto as letras estão abertas.
     */
    static void installDim(ClassLoader cl) {
        try {
            Class<?> pv = Class.forName("com.android.systemui.shade.NotificationShadeWindowView", false, cl);
            for (java.lang.reflect.Constructor<?> k : pv.getDeclaredConstructors()) {
                XposedBridge.hookMethod(k, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        final ViewGroup root = (ViewGroup) p.thisObject;
                        root.post(new Runnable() {
                            public void run() {
                                attachDim(root);
                            }
                        });
                    }
                });
            }
        } catch (Throwable t) {
            log("fundo escuro: " + t);
        }
    }

    static float dimA;

    static void attachDim(final ViewGroup root) {
        try {
            final View dv = new View(root.getContext());
            dv.setId(View.generateViewId());
            dv.setBackgroundColor(0xFF000000);
            dv.setAlpha(0f);
            dv.setVisibility(View.GONE);
            dv.setClickable(false);
            dv.setFocusable(false);
            dv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(dv, 0, new android.widget.FrameLayout.LayoutParams(-1, -1));
            dv.post(new Runnable() {
                public void run() {
                    if (dv.getParent() == null) return;
                    boolean moving = stepDim(dv);
                    if (moving) dv.postOnAnimation(this);
                    else dv.postDelayed(this, 150);
                }
            });
            log("fundo escuro pronto");
        } catch (Throwable t) {
            log("fundo escuro: " + t);
        }
    }

    /** Um passo do escurecido: segue a opacidade das letras do HMC, entrando e saindo suave. */
    static boolean stepDim(View dv) {
        float target = 0f;
        View lv = lyricRef == null ? null : lyricRef.get();
        if (Prefs.lyricDim && lv != null && lv.isAttachedToWindow() && lv.isShown() && fLines != null) {
            try {
                Object src = fLines.get(lv);
                if (src instanceof List && !((List<?>) src).isEmpty()) {
                    float a = lv.getAlpha();
                    for (android.view.ViewParent q = lv.getParent(); q instanceof View; q = q.getParent()) a *= ((View) q).getAlpha();
                    target = Prefs.lyricDimPct / 100f * 0.92f * a;
                }
            } catch (Throwable ignored) {
            }
        }
        dimA += (target - dimA) * 0.2f;
        if (Math.abs(target - dimA) < 0.004f) dimA = target;
        if (Math.abs(dv.getAlpha() - dimA) > 0.001f) dv.setAlpha(dimA);
        int vis = dimA > 0.004f ? View.VISIBLE : View.GONE;
        if (dv.getVisibility() != vis) dv.setVisibility(vis);
        return dimA != target;
    }

    static void install(ClassLoader cl) {
        installDim(cl);
        // a view das letras do HMC entra no bloqueio (keyguard_foreground_layer) ou numa janela própria
        try {
            for (Method m : ViewGroup.class.getDeclaredMethods()) {
                if (!m.getName().equals("addView") || m.getParameterTypes().length != 3) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (!(p.args[0] instanceof View)) return;
                        if (HmcFix.loader == null) HmcFix.seen((View) p.args[0]);
                        if (lyricCls == null) check((View) p.args[0]);
                    }
                });
            }
            Class<?> wm = Class.forName("android.view.WindowManagerImpl");
            for (Method m : wm.getDeclaredMethods()) {
                if (!m.getName().equals("addView")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        if (p.args.length == 0 || !(p.args[0] instanceof View)) return;
                        if (HmcFix.loader == null) HmcFix.seen((View) p.args[0]);
                        if (lyricCls == null) check((View) p.args[0]);
                    }
                });
            }
        } catch (Throwable t) {
            log("sem gancho: " + t);
        }
    }

    /** A view das letras: classe do HMC que desenha sozinha e guarda a lista de linhas. */
    static void check(View v) {
        Class<?> c = v.getClass();
        if (!c.getName().startsWith("com.os4.musiccover.")) return;
        try {
            Field lines = null;
            int layouts = 0;
            for (Field f : c.getDeclaredFields()) if (f.getType() == StaticLayout[].class) layouts++;
            if (layouts < 2) return;   // a view das letras guarda as linhas já montadas (StaticLayout[])
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != List.class) continue;
                if (f.getName().equals("P")) { lines = f; break; }
                if (lines == null) lines = f;
            }
            Method draw = c.getDeclaredMethod("onDraw", Canvas.class);
            if (lines == null) return;
            lines.setAccessible(true);
            fLines = lines;
            lyricCls = c;
            XposedBridge.hookMethod(draw, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (lyricRef == null || lyricRef.get() != p.thisObject) lyricRef = new java.lang.ref.WeakReference<>((View) p.thisObject);
                    if (!Prefs.lyricVideo) return;
                    try {
                        if (Prefs.lyricVideo && draw((View) p.thisObject, (Canvas) p.args[0])) p.setResult(null);
                    } catch (Throwable t) {
                        log("desenho: " + t);
                    }
                }
            });
            log("view das letras do HMC: " + c.getName() + " linhas em " + lines.getName());
        } catch (Throwable t) {
            log("view " + c.getName() + ": " + t);
        }
    }

    // ------------------------------------------------------------------------------- linhas

    static final class Line {
        String text;
        int start, end;
        int[] sylStart, sylChar;   // tempo de cada sílaba e onde ela termina no texto (quando a letra traz)
    }

    static final class Group {
        Neon neon;
        Line[] lines;
        long start, end, exit, next;
        StaticLayout[] lay;
        int w;
        long seed;
    }

    static List<?> lastSrc;
    static int lastSrcSize;
    static ArrayList<Group> groups = new ArrayList<>();

    static boolean lineFields(Object o) {
        if (fText != null) return true;
        Class<?> c = o.getClass();
        try {
            fText = c.getDeclaredField("a");
            fStart = c.getDeclaredField("h");
            fEnd = c.getDeclaredField("i");
            if (fText.getType() != String.class || fStart.getType() != int.class || fEnd.getType() != int.class) throw new NoSuchFieldException("tipos");
        } catch (Throwable t) {
            // outra versão do HMC: primeiro texto e dois primeiros inteiros
            fText = fStart = fEnd = null;
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() == String.class && fText == null) fText = f;
                else if (f.getType() == int.class && Modifier.isFinal(f.getModifiers()) && fStart == null) fStart = f;
            }
            log("campos da linha por tipo: " + t);
            if (fText == null || fStart == null) { fText = null; return false; }
        }
        fText.setAccessible(true);
        fStart.setAccessible(true);
        if (fEnd != null) fEnd.setAccessible(true);
        return true;
    }

    static Field fSylStart, fSylChar;
    static boolean sylTried;

    /** Sílabas com tempo (HMC: k = início em ms, m = fim da sílaba no texto). */
    static void syllables(Object o, Line l) {
        if (!sylTried) {
            sylTried = true;
            try {
                Field k = o.getClass().getDeclaredField("k"), m = o.getClass().getDeclaredField("m");
                if (k.getType() == int[].class && m.getType() == int[].class) {
                    k.setAccessible(true); m.setAccessible(true);
                    fSylStart = k; fSylChar = m;
                }
            } catch (Throwable t) {
                log("sem tempo por sílaba: " + t);
            }
        }
        if (fSylStart == null) return;
        try {
            int[] a = (int[]) fSylStart.get(o), b = (int[]) fSylChar.get(o);
            if (a != null && b != null && a.length == b.length && a.length > 0) { l.sylStart = a; l.sylChar = b; }
        } catch (Throwable ignored) {
        }
    }

    /** Linhas do HMC (campos ofuscados) para as nossas. */
    static void build(List<?> src) {
        ArrayList<Line> ls = new ArrayList<>();
        for (Object o : new ArrayList<Object>(src)) {
            if (o == null || !lineFields(o)) continue;
            try {
                Line l = new Line();
                Object tx = fText.get(o);
                l.text = tx == null ? "" : tx.toString().trim();
                l.start = fStart.getInt(o);
                l.end = fEnd != null ? Math.max(l.start, fEnd.getInt(o)) : l.start;
                syllables(o, l);
                if (!l.text.isEmpty()) ls.add(l);
            } catch (Throwable ignored) {
            }
        }
        group(ls);
    }

    /** Uma frase por tela (o par, como no vídeo, ficou desligado). */
    static void group(ArrayList<Line> ls) {
        ArrayList<Group> gs = new ArrayList<>();
        for (int i = 0; i < ls.size(); ) {
            Line a = ls.get(i);
            Line b = i + 1 < ls.size() ? ls.get(i + 1) : null;
            boolean pair = false;   // uma frase por vez, como as linhas do app de música
            Group g = new Group();
            g.lines = pair ? new Line[]{a, b} : new Line[]{a};
            g.start = a.start;
            Line last = g.lines[g.lines.length - 1];
            g.end = Math.max(last.end, last.start + 1500);
            g.seed = a.text.hashCode() * 31L + a.start;
            gs.add(g);
            i += g.lines.length;
        }
        for (int i = 0; i < gs.size(); i++) {
            Group g = gs.get(i);
            g.next = i + 1 < gs.size() ? gs.get(i + 1).start : Long.MAX_VALUE;
            long ex = Math.min(g.end + 700, g.next - EXIT);
            g.exit = Math.max(g.start + IN, ex);
        }
        groups = gs;
        log(ls.size() + " linhas em " + gs.size() + " telas");
    }

    // ------------------------------------------------------------------------------- desenho

    static final long IN = 320, EXIT = 480;
    static Typeface brush;
    static boolean brushTried;
    static final TextPaint big = new TextPaint(TextPaint.ANTI_ALIAS_FLAG), small = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    static int layW = -1;

    static Typeface brush(Context c) {
        if (brushTried) return brush;
        brushTried = true;
        brush = moduleFont(c, "lyric_brush.ttf");
        if (brush == null) brush = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC);
        return brush;
    }

    static long position(Context c) {
        long now = android.os.SystemClock.uptimeMillis();
        PlaybackState s = lastState;
        if (s == null || now - stateAt > 300) {   // pergunta ao app de música poucas vezes; no meio, extrapola
            MediaController mc = IosPlayer.controller(c);
            s = mc == null ? null : mc.getPlaybackState();
            lastState = s;
            stateAt = now;
        }
        if (s == null) return -1;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            float sp = s.getPlaybackSpeed() == 0f ? 1f : s.getPlaybackSpeed();
            pos += (long) ((android.os.SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * sp);
        }
        playing = s.getState() == PlaybackState.STATE_PLAYING;
        return pos;
    }

    static boolean playing;
    static PlaybackState lastState;
    static long stateAt;
    static int areaTop = -1, areaBottom = -1;
    static long areaAt;

    /** Faixa livre entre o relógio e o player do bloqueio. */
    static void area(View v) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - areaAt < 1000 && areaTop >= 0) return;
        areaAt = now;
        int h = v.getHeight();
        int top = Math.round(h * 0.17f), bottom = Math.round(h * 0.68f);
        try {
            int[] vl = new int[2];
            v.getLocationOnScreen(vl);
            int id = v.getResources().getIdentifier("mi_media_controls", "id", "com.android.systemui");
            View card = id == 0 ? null : findShown(v.getRootView(), id);
            if (card == null) {
                // janela própria das letras: procura o player no bloqueio pelo mesmo id
                View p = OriginPlayer.lastPlayer == null ? null : OriginPlayer.lastPlayer.get();
                if (p != null && p.isShown()) card = p;
            }
            if (card != null) {
                int[] cl = new int[2];
                card.getLocationOnScreen(cl);
                int b = cl[1] - vl[1] - Math.round(16 * v.getResources().getDisplayMetrics().density);
                if (b > top + h * 0.2f) bottom = b;
            }
        } catch (Throwable ignored) {
        }
        areaTop = top;
        areaBottom = bottom;
    }

    static View findShown(View v, int id) {
        if (v.getId() == id && v.isShown()) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findShown(g.getChildAt(i), id);
                if (r != null) return r;
            }
        }
        return null;
    }

    static final android.graphics.Paint dimP = new android.graphics.Paint();
    static int dimKey;

    static boolean draw(View v, Canvas c) throws Throwable {
        Object src = fLines.get(v);
        if (!(src instanceof List) || ((List<?>) src).isEmpty()) return false;
        List<?> l = (List<?>) src;
        if (l != lastSrc || l.size() != lastSrcSize) {
            lastSrc = l;
            lastSrcSize = l.size();
            build(l);
        }
        if (groups.isEmpty()) return false;
        ctx = v.getContext();
        long t = position(ctx);
        if (t < 0) return false;
        t += Prefs.lyricShift * 100L;   // "Adiantar a letra"
        float d = v.getResources().getDisplayMetrics().density;
        area(v);
        fx0 = 0; fx1 = v.getWidth();
        lx0 = 0; ly0 = 0; lx1 = v.getWidth(); ly1 = v.getHeight();
        int w = v.getWidth() - Math.round(56 * d);
        if (w <= 0) return true;
        long wake = render(c, t, d, w);
        if (playing) {
            if (wake <= 16) v.postInvalidateOnAnimation();
            else if (wake != Long.MAX_VALUE) v.postInvalidateDelayed(Math.min(wake, 1000));
        }
        return true;
    }

    // onde desenhar: o bloqueio usa a tela toda; o StandBy (HyperImmersive), só a coluna das letras
    static Context ctx;
    static float fx0, fx1;               // faixa horizontal das letras
    static float lx0, ly0, lx1, ly1;     // limites da camada (o brilho não pode ser cortado)

    /** Desenha a tela atual das letras na faixa fx0..fx1 / areaTop..areaBottom; devolve quando acordar de novo. */
    static long render(Canvas c, long t, float d, int w) {
        if (w != layW) {
            layW = w;
            for (Group g : groups) { g.lay = null; g.neon = null; }
        }
        // tela atual: a última que já começou
        Group g = null;
        for (Group x : groups) {
            if (x.start <= t) g = x;
            else break;
        }
        long wake = Long.MAX_VALUE;
        if (Prefs.lyricStyle == 1) {
            Neon.frame(c, groups, t, d, w, (areaTop + areaBottom) / 2f);
            wake = 0;
        } else if (g != null && t < g.exit + EXIT) {
            drawGroup(c, g, t, d, w);
            wake = 0;   // a tela nunca fica parada: aproxima devagar enquanto a frase está na tela
        }
        // próxima tela
        for (Group x : groups) {
            if (x.start > t) { wake = Math.min(wake, x.start - t); break; }
        }
        return wake;
    }

    static void drawGroup(Canvas c, Group g, long t, float d, int w) {
        Typeface tf = brush(ctx);
        if (g.lay == null) {
            g.lay = new StaticLayout[g.lines.length];
            for (int i = 0; i < g.lines.length; i++) {
                boolean last = i == g.lines.length - 1;
                TextPaint p = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
                p.setTypeface(tf);
                float size = 38 * d;
                String s = g.lines[i].text.toUpperCase();
                p.setTextSize(size);
                float tw = p.measureText(s);
                if (tw > w) p.setTextSize(Math.max(size * 0.7f, size * w / tw));
                g.lay[i] = StaticLayout.Builder.obtain(s, 0, s.length(), p, w).setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setLineSpacing(0f, 0.92f).setIncludePad(false).build();
            }
        }
        int total = 0;
        float gap = 2 * d;
        for (StaticLayout sl : g.lay) total += sl.getHeight();
        total += Math.round(gap * (g.lay.length - 1));
        float cy = (areaTop + areaBottom) / 2f;
        float y = cy - total / 2f;
        float x = (fx0 + fx1 - w) / 2f;
        // saída: desce e some
        float out = t >= g.exit ? Math.min(1f, (t - g.exit) / (float) EXIT) : 0f;
        float eo = out * out;
        int save = c.save();
        // movimento do vídeo: a frase vai se aproximando devagar (uns 5%) enquanto fica na tela
        long life = Math.max(1500L, g.exit + EXIT - g.start);
        float drift = Math.max(0f, Math.min(1f, (t - g.start) / (float) life));
        float zoom = 1f + 0.05f * drift;
        c.scale(zoom, zoom, (fx0 + fx1) / 2f, cy);
        c.translate(x, y + eo * 18 * d);
        for (int i = 0; i < g.lay.length; i++) {
            StaticLayout sl = g.lay[i];
            TextPaint p = sl.getPaint();
            int top = 0, bottom = sl.getHeight();
            p.setShader(new LinearGradient(0, top, 0, bottom, 0xFFFFFFFF, 0xFFEDEDED, Shader.TileMode.CLAMP));
            // entrada: a linha "tinge" de uma vez, um pouco maior e com a tinta borrada, e assenta
            long into = t - g.start - i * 70L;
            float k = into >= IN ? 1f : Math.max(0f, into / (float) IN);
            if (k <= 0f) { c.translate(0, sl.getHeight() + gap); continue; }
            float e = 1f - (1f - k) * (1f - k) * (1f - k);
            float glow = 1f - e;
            if (glow > 0.15f) p.setShadowLayer(5 * d + glow * 9 * d, 0, 0, (Math.round(glow * 140) << 24) | 0xFFFFFF);   // tinta ainda espalhando: brilho claro
            else p.setShadowLayer(5 * d, 0, 1.5f * d, 0x73000000);
            p.setAlpha(Math.round(255 * (1f - out) * e));
            int sv = c.save();
            if (k < 1f) {
                float sc = 1f + 0.06f * glow;
                c.scale(sc, sc, w / 2f, sl.getHeight() / 2f);
            }
            sl.draw(c);
            c.restoreToCount(sv);
            c.translate(0, sl.getHeight() + gap);
        }
        c.restoreToCount(save);
    }


    // ------------------------------------------------------------------------------- neon

    /** Fontes do neon: arquivo, se é contorno branco, e caixa (0 como veio, 1 maiúsculas, 2 minúsculas). */
    static final String[] NF = {"lyric_neon.ttf", "ln_tilt.ttf", "ln_sacra.ttf", "ln_dafoe.ttf", "ln_monoton.ttf", "lyric_neon_b.ttf", "ln_hair.ttf", "ln_audio.ttf"};
    static final boolean[] NF_LINE = {false, false, false, false, false, true, true, true};
    static final int[] NF_CASE = {0, 1, 2, 2, 1, 1, 1, 1};
    static final float[] NF_SCALE = {1f, 0.9f, 1.25f, 1.15f, 0.85f, 1f, 0.85f, 0.9f};   // altura visual parecida entre fontes
    static Typeface[] nf;
    static final int[] nfCase = new int[NF.length];
    static final float[] nfScale = new float[NF.length];
    static String nfSig;
    static boolean nfChanged;

    /** Fontes do Neon: as do módulo, ou as que o usuário escolheu (entregues pelo app via provedor). */
    static void neonFonts(Context c) {
        String sig = java.util.Arrays.toString(Prefs.lyricFonts);
        if (nf != null && sig.equals(nfSig)) return;
        nfSig = sig;
        nfChanged = nf != null;
        nf = new Typeface[NF.length];
        for (int i = 0; i < NF.length; i++) {
            nfCase[i] = NF_CASE[i];
            nfScale[i] = NF_SCALE[i];
            String own = Prefs.lyricFonts[i];
            if (own != null && !own.isEmpty()) {
                try {
                    java.io.File f = new java.io.File(c.getCacheDir(), "lyric_font_" + i + "_" + Integer.toHexString(own.hashCode()) + ".ttf");
                    if (!f.isFile()) {
                        android.os.ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(android.net.Uri.parse("content://" + Keys.PKG + ".lyricfonts/" + i), "r");
                        java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd);
                        java.io.OutputStream out = new java.io.FileOutputStream(f);
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        in.close();
                        out.close();
                    }
                    nf[i] = new Typeface.Builder(f).build();
                    if (nf[i] != null) {
                        nfCase[i] = 0;      // fonte do usuário: o texto como veio
                        nfScale[i] = 1f;
                        log("fonte própria " + (i + 1) + ": " + own.substring(0, Math.max(0, own.lastIndexOf('|'))));
                    }
                } catch (Throwable t) {
                    log("fonte própria " + (i + 1) + ": " + t);
                    nf[i] = null;
                }
            }
            if (nf[i] == null) nf[i] = moduleFont(c, NF[i]);
            if (nf[i] == null) nf[i] = Typeface.create(NF_LINE[i] ? "sans-serif-condensed" : "serif", Typeface.BOLD);
        }
    }

    static Context modCtx;
    static boolean modTried;

    /**
     * Fonte guardada nos assets do Hyper Eternity. Na Interface do Sistema vem do contexto do app;
     * em outro processo (o StandBy do HyperImmersive, que não enxerga outros apps) é copiada do APK
     * do módulo para o cache uma vez.
     */
    static Typeface moduleFont(Context c, String name) {
        if (!modTried) {
            modTried = true;
            try {
                modCtx = c.createPackageContext(Keys.PKG, Context.CONTEXT_IGNORE_SECURITY);
            } catch (Throwable t) {
                log("fontes pelo APK do módulo (" + t.getClass().getSimpleName() + ")");
            }
        }
        try {
            if (modCtx != null) return new Typeface.Builder(modCtx.getAssets(), name).build();
        } catch (Throwable t) {
            log("fonte " + name + ": " + t);
        }
        try {
            android.content.pm.ApplicationInfo ai = XposedBridge.moduleInfo();
            if (ai == null || ai.sourceDir == null) return null;
            java.io.File apk = new java.io.File(ai.sourceDir);
            String tag = Integer.toHexString((ai.sourceDir + apk.lastModified()).hashCode());
            java.io.File dir = new java.io.File(c.getCacheDir(), "hyper_eternity_fonts");
            java.io.File f = new java.io.File(dir, tag + "_" + name);
            if (!f.isFile()) {
                dir.mkdirs();
                java.io.File[] old = dir.listFiles();
                if (old != null) for (java.io.File o : old) if (o.getName().endsWith("_" + name)) o.delete();
                java.util.zip.ZipFile z = new java.util.zip.ZipFile(apk);
                try {
                    java.util.zip.ZipEntry e = z.getEntry("assets/" + name);
                    if (e == null) return null;
                    java.io.File tmp = new java.io.File(dir, f.getName() + ".tmp");
                    java.io.InputStream in = z.getInputStream(e);
                    java.io.OutputStream out = new java.io.FileOutputStream(tmp);
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close();
                    out.close();
                    tmp.renameTo(f);
                } finally {
                    z.close();
                }
            }
            return new Typeface.Builder(f).build();
        } catch (Throwable t) {
            log("fonte " + name + " (APK): " + t);
            return null;
        }
    }

    /** Capa vinda de outro lugar (o StandBy entrega a dele); sem isto, a do player pela sessão de mídia. */
    static java.util.concurrent.Callable<android.graphics.Bitmap> artSrc;

    // cor da capa: o tom mais vivo da arte do álbum, em versão neon
    static Object colorKey;
    static int[] neonCol = {0xFFFF3D8B, 0xFFFF7AB4, 0xFFFFF0F6};   // halo, halo curto, tubo

    static long colorAt;

    static int[] albumColors(Context c) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - colorAt < 1200) return neonCol;   // a capa só muda com a música: confere de vez em quando
        colorAt = now;
        try {
            android.media.MediaMetadata md = null;
            android.graphics.Bitmap b;
            if (artSrc != null) {
                b = artSrc.call();
            } else {
                MediaController mc = IosPlayer.controller(c);
                md = mc == null ? null : mc.getMetadata();
                b = md == null ? null : md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (b == null && md != null) b = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
                if (b == null && md != null) b = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }
            Object key = b != null ? b : (md == null ? null : md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE));
            if (key == colorKey) return neonCol;
            colorKey = key;
            if (b == null || b.isRecycled()) { neonCol = new int[]{0xFFFF3D8B, 0xFFFF7AB4, 0xFFFFF0F6}; return neonCol; }
            android.graphics.Bitmap sm = android.graphics.Bitmap.createScaledBitmap(b.getConfig() == android.graphics.Bitmap.Config.HARDWARE ? b.copy(android.graphics.Bitmap.Config.ARGB_8888, false) : b, 32, 32, true);
            float[] bins = new float[24], hs = new float[24];
            float[] hsv = new float[3];
            for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
                android.graphics.Color.colorToHSV(sm.getPixel(x, y), hsv);
                if (hsv[2] < 0.22f || hsv[1] < 0.18f) continue;
                float wgt = hsv[1] * hsv[1] * hsv[2];
                int bi = Math.min(23, (int) (hsv[0] / 15f));
                bins[bi] += wgt;
                hs[bi] += wgt * hsv[0];
            }
            int best = -1;
            float bw = 0f;
            for (int i = 0; i < 24; i++) {
                float w = bins[i] + 0.5f * (bins[(i + 23) % 24] + bins[(i + 1) % 24]);
                if (w > bw) { bw = w; best = i; }
            }
            if (best < 0 || bins[best] < 0.6f) { neonCol = new int[]{0xFFFF3D8B, 0xFFFF7AB4, 0xFFFFF0F6}; return neonCol; }   // capa sem cor: rosa
            float h = hs[best] / bins[best];
            neonCol = new int[]{android.graphics.Color.HSVToColor(new float[]{h, 0.85f, 1f}), android.graphics.Color.HSVToColor(new float[]{h, 0.55f, 1f}),
                    android.graphics.Color.HSVToColor(new float[]{h, 0.12f, 1f})};
            log("cor da capa: matiz " + Math.round(h));
        } catch (Throwable t) {
            log("cor da capa: " + t);
        }
        return neonCol;
    }

    static final long N_IN = 260, N_OUT = 520;

    /**
     * Estilo neon: as palavras acendem uma a uma no tempo em que são cantadas, em fileiras que ocupam a
     * largura toda, com fontes que variam a cada frase (tubo neon na cor da capa e contorno branco); o
     * texto rola devagar para baixo por uma bolha curvada, com chuva escorrendo no vidro. Uma frase sai
     * se desfazendo enquanto a próxima já acende.
     */
    static final class Neon {
        static final int MW = 18, MH = 14;
        final String[] words, shown;
        final long[] at;
        final int[] row, rowFont;
        final float[] wx, rowY, rowSize;
        float bw, bh, pad;
        android.graphics.Bitmap bmp;
        int drawnKey = -1, colKey;
        final float[] mesh = new float[(MW + 1) * (MH + 1) * 2];
        final TextPaint p = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        static final android.graphics.Paint bp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);

        Neon(Context ctx, Line l, int w, float d, float maxH) {
            neonFonts(ctx);
            words = l.text.trim().split("\\s+");
            int n = words.length;
            at = new long[n];
            shown = new String[n];
            int ci = 0;
            long span = Math.max(600, l.end - l.start);
            for (int i = 0; i < n; i++) {
                int idx = l.text.indexOf(words[i], ci);
                if (idx < 0) idx = ci;
                ci = idx + words[i].length();
                long t = l.start + span * i / Math.max(1, n) * 7 / 10;
                if (l.sylStart != null) {
                    for (int s = 0; s < l.sylChar.length; s++) if (l.sylChar[s] > idx) { t = l.sylStart[s]; break; }
                }
                at[i] = Math.max(l.start, t);
            }
            row = new int[n];
            int rows = 0, len = 0, cnt = 0;
            for (int i = 0; i < n; i++) {
                int wl = words[i].length();
                if (cnt > 0 && (len + 1 + wl > 11 || cnt >= 2)) { rows++; len = 0; cnt = 0; }
                row[i] = rows;
                len += (cnt > 0 ? 1 : 0) + wl;
                cnt++;
            }
            rows++;
            // fontes da frase: sorteadas pela própria frase (sempre iguais para a mesma linha)
            java.util.Random rnd = new java.util.Random(l.text.hashCode() * 131L + l.start);
            rowFont = new int[rows];
            int prev = -1;
            for (int r = 0; r < rows; r++) {
                int f;
                do {
                    f = r % 2 == 0 ? rnd.nextInt(5) : 5 + rnd.nextInt(3);
                } while (f == prev && rows > 1);
                rowFont[r] = prev = f;
            }
            rowSize = new float[rows];
            rowY = new float[rows];
            wx = new float[n];
            pad = 30 * d;
            layout(w * 0.9f, d, n, rows);
            // frase longa: diminui tudo para caber entre o relógio e o player (a bolha aumenta o meio)
            if (bh * 1.25f > maxH) layout(w * 0.9f * Math.max(0.55f, maxH / (bh * 1.25f)), d, n, rows);
        }

        void layout(float width, float d, int n, int rows) {
            bw = width;
            float y = pad;
            for (int r = 0; r < rows; r++) {
                int f = rowFont[r];
                p.setTypeface(nf[f]);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    if (row[i] != r) continue;
                    String s = nfCase[f] == 1 ? words[i].toUpperCase() : nfCase[f] == 2 ? words[i].toLowerCase() : words[i];
                    shown[i] = s;
                    sb.append(sb.length() > 0 ? " " : "").append(s);
                }
                p.setTextSize(100f);
                float tw = p.measureText(sb.toString());
                float size = Math.max(16 * d, Math.min(66 * d * nfScale[f] * width / (bw0 > 0 ? bw0 : width), 100f * bw / Math.max(1f, tw)));
                rowSize[r] = size;
                p.setTextSize(size);
                float total = p.measureText(sb.toString());
                float x = pad + (bw - total) / 2f;
                for (int i = 0; i < n; i++) {
                    if (row[i] != r) continue;
                    wx[i] = x;
                    x += p.measureText(shown[i] + " ");
                }
                android.graphics.Paint.FontMetrics fm = p.getFontMetrics();
                rowY[r] = y - fm.ascent * 0.9f;
                y += (-fm.ascent + fm.descent) * 0.84f;
            }
            bh = y + pad;
            if (bw0 <= 0) bw0 = width;
        }

        float bw0;

        void render(long t, float d, int[] col) {
            int w = Math.round(bw + pad * 2), h = Math.round(bh);
            if (bmp == null || bmp.getWidth() != w || bmp.getHeight() != h) {
                if (bmp != null) bmp.recycle();
                bmp = android.graphics.Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), android.graphics.Bitmap.Config.ARGB_8888);
            }
            bmp.eraseColor(0);
            Canvas c = new Canvas(bmp);
            for (int i = 0; i < words.length; i++) {
                if (t < at[i]) continue;
                float k = Math.min(1f, (t - at[i]) / (float) N_IN);
                float e = 1f - (1f - k) * (1f - k);
                int r = row[i], f = rowFont[r];
                p.setTypeface(nf[f]);
                p.setTextSize(rowSize[r]);
                String s = shown[i];
                float ww = p.measureText(s);
                int sv = c.save();
                float sc = 1f + 0.1f * (1f - e);
                c.scale(sc, sc, wx[i] + ww / 2f, rowY[r] - rowSize[r] * 0.35f);
                int alpha = Math.round(255 * e);
                if (!NF_LINE[f]) {
                    // tubo neon: halo largo e curto na cor da capa, e o tubo quase branco
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    p.setColor(col[1]);
                    p.setAlpha(alpha);
                    p.setShadowLayer(26 * d, 0, 0, (col[0] & 0x00FFFFFF) | 0xF0000000);
                    c.drawText(s, wx[i], rowY[r], p);
                    p.setShadowLayer(7 * d, 0, 0, col[1]);
                    c.drawText(s, wx[i], rowY[r], p);
                    p.clearShadowLayer();
                    p.setColor(col[2]);
                    p.setAlpha(alpha);
                    c.drawText(s, wx[i], rowY[r], p);
                } else {
                    boolean thin = f == 6;
                    p.setStyle(thin ? android.graphics.Paint.Style.FILL : android.graphics.Paint.Style.STROKE);
                    p.setStrokeWidth(1.6f * d);
                    p.setColor(0xFFF4F4F4);
                    p.setAlpha(alpha);
                    p.setShadowLayer(6 * d, 0, 0, 0x99FFFFFF);
                    c.drawText(s, wx[i], rowY[r], p);
                    p.clearShadowLayer();
                }
                c.restoreToCount(sv);
            }
            bloom();
        }

        static final int BS = 12, BP = 9;   // brilho: 1/12 do tamanho, com 9 px (x12) de folga em volta
        android.graphics.Bitmap glow;

        /** O brilho que as próprias letras jogam em volta: o texto encolhido, borrado e esticado de volta. */
        void bloom() {
            int sw = Math.max(1, bmp.getWidth() / BS) + BP * 2, sh = Math.max(1, bmp.getHeight() / BS) + BP * 2;
            if (glow == null || glow.getWidth() != sw || glow.getHeight() != sh) {
                if (glow != null) glow.recycle();
                glow = android.graphics.Bitmap.createBitmap(sw, sh, android.graphics.Bitmap.Config.ARGB_8888);
            }
            glow.eraseColor(0);
            Canvas g = new Canvas(glow);
            android.graphics.Paint fp = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            g.drawBitmap(bmp, null, new android.graphics.RectF(BP, BP, sw - BP, sh - BP), fp);
            Frost.boxBlur(glow, 3);
            Frost.boxBlur(glow, 3);
        }

        /** Desenha o texto curvado; a = opacidade (saída), roll = deslocamento vertical em px. */
        void draw(Canvas c, long t, float d, float cx, float cy, float a, float roll, int[] col) {
            int lit = 0;
            boolean anim = false;
            for (long x : at) if (t >= x) { lit++; if (t - x < N_IN) anim = true; }
            if (lit == 0 || a <= 0f) return;
            int key = anim ? -2 : lit;
            int ck = col[0];
            if (key != drawnKey || anim || bmp == null || ck != colKey) {
                render(t, d, col);
                drawnKey = key;
                colKey = ck;
            }
            float W = bmp.getWidth(), H = bmp.getHeight();
            float hw = W / 2f, hh = H / 2f;
            float R = Math.max(hw, hh) * 1.05f, k = 0.62f, s0 = 1.22f;
            int i = 0;
            for (int y = 0; y <= MH; y++) {
                float Y = (y / (float) MH * 2f - 1f) * hh + roll;
                for (int x = 0; x <= MW; x++) {
                    float X = (x / (float) MW * 2f - 1f) * hw;
                    float r2 = (X * X + Y * Y) / (R * R);
                    float f = s0 / (1f + k * r2);
                    mesh[i++] = cx + X * f;
                    mesh[i++] = cy + Y * f;
                }
            }
            if (glow != null) {
                // o brilho segue o formato do texto e se espalha bem além dele, na cor da capa
                float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
                for (int j = 0; j < mesh.length; j += 2) {
                    x0 = Math.min(x0, mesh[j]); x1 = Math.max(x1, mesh[j]);
                    y0 = Math.min(y0, mesh[j + 1]); y1 = Math.max(y1, mesh[j + 1]);
                }
                float ex = (x1 - x0) / (glow.getWidth() - BP * 2) * BP, ey = (y1 - y0) / (glow.getHeight() - BP * 2) * BP;
                gpaint.setColorFilter(new android.graphics.PorterDuffColorFilter(col[0], android.graphics.PorterDuff.Mode.SRC_IN));
                gpaint.setAlpha(Math.round(185 * a));
                float gx = (x0 + x1) / 2f, gy = (y0 + y1) / 2f, gw = (x1 - x0) / 2f + ex, gh = (y1 - y0) / 2f + ey;
                c.drawBitmap(glow, null, new android.graphics.RectF(gx - gw * 1.5f, gy - gh * 1.65f, gx + gw * 1.5f, gy + gh * 1.65f), gpaint);
                gpaint.setAlpha(Math.round(235 * a));
                c.drawBitmap(glow, null, new android.graphics.RectF(gx - gw, gy - gh, gx + gw, gy + gh), gpaint);
            }
            bp.setAlpha(Math.round(255 * a));
            c.drawBitmapMesh(bmp, MW, MH, mesh, 0, null, 0, bp);
            lastRoll = roll; lastA = a; lastCx = cx; lastCy = cy; lastR = R; lastK = k; lastS0 = s0;
        }

        float lastRoll, lastA, lastCx, lastCy, lastR, lastK, lastS0;
        static final android.graphics.Paint gpaint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);

        /** Ponto da tela -> ponto do bitmap (inverso da bolha) e o aumento local; null fora do bitmap. */
        boolean toBitmap(float px, float py, float[] out) {
            if (bmp == null) return false;
            float X = (px - lastCx) / lastS0, Y = (py - lastCy) / lastS0, f = lastS0;
            for (int it = 0; it < 6; it++) {
                float r2 = (X * X + Y * Y) / (lastR * lastR);
                f = lastS0 / (1f + lastK * r2);
                X = (px - lastCx) / f;
                Y = (py - lastCy) / f;
            }
            out[0] = X + bmp.getWidth() / 2f;
            out[1] = Y - lastRoll + bmp.getHeight() / 2f;
            out[2] = f;
            return true;
        }

        // ------------------------------------------------------------------ quadro inteiro

        static float bgA;
        static long lastFrame;

        /** Rolagem para baixo (~9dp/s), no máximo 50dp no total, passando pelo centro no meio da frase. */
        static float rollOf(long start, long life, long t, float d) {
            float travel = Math.min(50 * d, 9f * d * life / 1000f);
            float k = Math.max(0f, Math.min(1f, (t - start) / (float) life));
            return travel * (k - 0.5f);
        }

        static long exitOf(Group g) {
            return Math.max(g.start + N_IN, Math.min(g.end + 900, g.next - 160));
        }

        static void frame(Canvas c, ArrayList<Group> gs, long t, float d, int w, float cy) {
            int[] col = albumColors(ctx);
            neonFonts(ctx);
            if (nfChanged) {
                nfChanged = false;
                for (Group x : gs) x.neon = null;   // fontes trocadas nos ajustes: monta as frases de novo
            }
            float cx = (fx0 + fx1) / 2f;
            // frases na tela: a atual e a anterior ainda se desfazendo
            Group cur = null, prev = null;
            for (Group x : gs) {
                if (x.start <= t) { prev = cur; cur = x; } else break;
            }
            boolean curOn = cur != null && t < exitOf(cur) + N_OUT;
            boolean prevOn = prev != null && t < exitOf(prev) + N_OUT;
            float maxH = areaBottom - areaTop;
            if (curOn && cur.neon == null) cur.neon = new Neon(ctx, cur.lines[0], w, d, maxH);
            if (prevOn && prev.neon == null) prev.neon = new Neon(ctx, prev.lines[0], w, d, maxH);
            // fundo (escurecido + chuva) contínuo entre as frases: só some em pausas longas
            long now = android.os.SystemClock.uptimeMillis();
            float dt = lastFrame == 0 ? 16f : Math.min(64f, now - lastFrame);
            lastFrame = now;
            float target = curOn || prevOn ? 1f : 0f;
            bgA += (target - bgA) * Math.min(1f, dt / (target > bgA ? 400f : 1600f));
            // o texto e a chuva numa camada só: as gotas só aparecem onde há neon (como nos vídeos)
            int layer = c.saveLayer(lx0, ly0, lx1, ly1, null);   // tela toda: o brilho não pode ser cortado
            if (prevOn) {
                long ex = exitOf(prev);
                float out = t >= ex ? Math.min(1f, (t - ex) / (float) N_OUT) : 0f;
                long life = Math.max(1500L, ex + N_OUT - prev.start);
                float roll = rollOf(prev.start, life, t, d) + out * out * 14 * d;
                prev.neon.draw(c, t, d, cx, cy, 1f - out * (2f - out), roll, col);
            }
            if (curOn) {
                long ex = exitOf(cur);
                float out = t >= ex ? Math.min(1f, (t - ex) / (float) N_OUT) : 0f;
                long life = Math.max(1500L, ex + N_OUT - cur.start);
                float roll = rollOf(cur.start, life, t, d) + out * out * 14 * d;
                cur.neon.draw(c, t, d, cx, cy, 1f - out * (2f - out), roll, col);
            }
            Neon lensSrc = curOn && cur.neon.bmp != null && cur.neon.lastA > 0.3f ? cur.neon : prevOn && prev.neon.bmp != null ? prev.neon : null;
            if (bgA > 0.01f && Prefs.lyricRain) Rain.draw(c, cx, cy, (fx1 - fx0) * 0.5f, (areaBottom - areaTop) * 0.5f + 40 * d, dt, d, lensSrc);
            c.restoreToCount(layer);
        }
    }

    /**
     * Chuva no vidro, como nos vídeos: só aparece onde há luz atrás (desenhada na mesma camada do neon,
     * com DST_OUT e SRC_ATOP). Cada gota "apaga" um pouco o neon (fica escura) e ganha um brilho e uma
     * borda clara embaixo. Gotas novas caem o tempo todo, crescem um pouco, se juntam; as maiores
     * escorrem aos trancos e limpam um caminho fino no vidro, que some aos poucos.
     */
    static final class Rain {
        static final int MAX = 1100;
        static final float[] x = new float[MAX], y = new float[MAX], r = new float[MAX], asp = new float[MAX], vy = new float[MAX],
                life = new float[MAX], age = new float[MAX], ty = new float[MAX], ox = new float[MAX], oy = new float[MAX], hold = new float[MAX];
        static final boolean[] on = new boolean[MAX], slide = new boolean[MAX];
        static final float[] dxv = new float[MAX];
        // rastros: o caminho de cada gota que escorreu, como uma linha contínua (pontos com a hora em que passou)
        static final int TN = 240, TP = 72;
        static final float[][] tpx = new float[TN][TP], tpy = new float[TN][TP], tpt = new float[TN][TP];
        static final int[] tcnt = new int[TN], trailOf = new int[MAX];
        static final float[] twid = new float[TN];
        static final boolean[] tuse = new boolean[TN];
        static final android.graphics.Path[] tpath = new android.graphics.Path[TN];
        static final float[] sxp = new float[TP], syp = new float[TP];
        static float clock;
        static final java.util.Random rnd = new java.util.Random();
        static final android.graphics.Paint out = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG),
                atop = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        static {
            out.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT));
            atop.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP));
        }
        static float spawnAcc, slideAcc;
        static final float TRAIL_DRY = 2600f;
        static boolean seeded;

        static int spawn(float px, float py, float rr) {
            for (int i = 0; i < MAX; i++) {
                if (on[i]) continue;
                on[i] = true;
                x[i] = px; y[i] = py; r[i] = rr;
                asp[i] = 0.7f + rnd.nextFloat() * 0.45f;
                ox[i] = (rnd.nextFloat() - 0.5f) * 0.9f;   // gota irregular: um segundo lóbulo deslocado
                oy[i] = rnd.nextFloat() * 0.6f;
                vy[i] = 0f;
                dxv[i] = 0f;
                age[i] = 0f;
                life[i] = 6000f + rnd.nextFloat() * 9000f;
                thr[i] = 3.6f + rnd.nextFloat() * 2.2f;    // quanto a gota aguenta parada antes de escorrer (o vidro segura umas mais que outras)
                trav[i] = 0f;
                slide[i] = false;
                hold[i] = 0f;
                trailOf[i] = -1;
                return i;
            }
            return -1;
        }

        static final float[] thr = new float[MAX], trav = new float[MAX];
        static float cHw = 1f, cHh = 1f, cD = 1f;

        /**
         * Uma gota batendo no vidro. Se cai em cima de outra, as duas viram uma só, maior: é assim que
         * as gotas crescem até ficarem pesadas demais e escorrerem.
         */
        static void randomDrop(boolean old) {
            float u = rnd.nextFloat();
            float rr = u < 0.5f ? 1.1f + rnd.nextFloat() * 1.2f : u < 0.9f ? 2.4f + rnd.nextFloat() * 1.8f : 4.2f + rnd.nextFloat() * 1.8f;
            double ang = rnd.nextDouble() * Math.PI * 2, rad = Math.sqrt(rnd.nextDouble());
            float px = (float) (Math.cos(ang) * rad), py = (float) (Math.sin(ang) * rad);
            if (!old) {
                for (int j = 0; j < MAX; j++) {
                    if (!on[j] || slide[j]) continue;
                    float dx = (x[j] - px) * cHw, dy = (y[j] - py) * cHh, rs = (r[j] + rr) * cD * 1.25f;
                    if (dx * dx + dy * dy < rs * rs) {
                        r[j] = (float) Math.sqrt(r[j] * r[j] + rr * rr);   // juntou: mesma água, gota maior
                        x[j] = (x[j] * r[j] + px * rr) / (r[j] + rr);
                        life[j] = Math.max(life[j], age[j] + 5000f);
                        return;
                    }
                }
            }
            int i = spawn(px, py, rr);
            if (i >= 0 && old) age[i] = rnd.nextFloat() * life[i] * 0.8f;
        }

        static int newTrail(float w) {
            int best = 0;
            float oldest = Float.MAX_VALUE;
            for (int j = 0; j < TN; j++) {
                if (!tuse[j]) { best = j; oldest = -1; break; }
                float last = tcnt[j] > 0 ? tpt[j][tcnt[j] - 1] : 0f;
                if (last < oldest) { oldest = last; best = j; }
            }
            for (int i = 0; i < MAX; i++) if (trailOf[i] == best) trailOf[i] = -1;
            tuse[best] = true;
            tcnt[best] = 0;
            twid[best] = w;
            return best;
        }

        static void trailPoint(int j, float fx, float fy) {
            int n = tcnt[j];
            if (n > 0) {
                float dx = fx - tpx[j][n - 1], dy = fy - tpy[j][n - 1];
                if (dx * dx + dy * dy < 0.00004f) { tpt[j][n - 1] = clock; return; }   // anda pouco: só renova a hora
            }
            if (n == TP) {
                System.arraycopy(tpx[j], 1, tpx[j], 0, TP - 1);
                System.arraycopy(tpy[j], 1, tpy[j], 0, TP - 1);
                System.arraycopy(tpt[j], 1, tpt[j], 0, TP - 1);
                n--;
            }
            tpx[j][n] = fx; tpy[j][n] = fy; tpt[j][n] = clock;
            tcnt[j] = n + 1;
        }

        static float wcx, wcy, wR2, wf;

        /** Mesma bolha do texto: o vidro é curvo, então a água anda em curva e encolhe nas bordas. */
        static float wx(float fx, float fy) {
            float r2 = (fx * fx + fy * fy) / wR2;
            wf = 1.22f / (1f + 0.62f * r2);
            return wcx + fx * wf;
        }

        // lente: a água mostra o neon de trás deslocado (caminho) ou invertido e aumentado (gota)
        static final android.graphics.Paint lensP = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        static android.graphics.BitmapShader lensSh;
        static android.graphics.Bitmap lensBmp;
        static final android.graphics.Matrix lm = new android.graphics.Matrix(), pm = new android.graphics.Matrix();
        static final android.graphics.Path tmpPath = new android.graphics.Path();
        static final float[] q = new float[3];
        static Neon lensN;

        static boolean lensReady(Neon n) {
            lensN = n;
            if (n == null || n.bmp == null || n.bmp.isRecycled()) return false;
            if (lensBmp != n.bmp) {
                lensBmp = n.bmp;
                lensSh = new android.graphics.BitmapShader(n.bmp, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            }
            return true;
        }

        static void draw(Canvas c, float cx, float cy, float hwF, float hhF, float dt, float d, Neon lens) {
            boolean lensOn = lensReady(lens);
            if (!seeded) {
                seeded = true;
                for (int i = 0; i < 260; i++) randomDrop(true);
            }
            float hw = hwF, hh = hhF;
            wcx = cx; wcy = cy;
            float wr = Math.max(hw, hh) * 1.05f;
            wR2 = wr * wr;
            cHw = hw; cHh = hh; cD = d;
            float amt = Prefs.lyricRainAmt / 100f, slideF = Prefs.lyricRainSlide / 100f;
            spawnAcc += dt * 0.09f * amt;       // gotas batendo no vidro (~90/s no 100%)
            while (spawnAcc >= 1f) { spawnAcc -= 1f; randomDrop(false); }
            clock += dt;
            // rastros: uma linha lisa que segue o caminho da gota e vai secando (o fim mais velho some antes)
            out.setStyle(android.graphics.Paint.Style.STROKE);
            atop.setStyle(android.graphics.Paint.Style.STROKE);
            out.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            atop.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            out.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            atop.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            for (int j = 0; j < TN; j++) {
                if (!tuse[j]) continue;
                int n = tcnt[j];
                // tira do começo os pontos que já secaram
                int dry = 0;
                while (dry < n && clock - tpt[j][dry] > TRAIL_DRY) dry++;
                if (dry > 0 && dry < n) {
                    System.arraycopy(tpx[j], dry, tpx[j], 0, n - dry);
                    System.arraycopy(tpy[j], dry, tpy[j], 0, n - dry);
                    System.arraycopy(tpt[j], dry, tpt[j], 0, n - dry);
                    n -= dry;
                    tcnt[j] = n;
                } else if (dry >= n) {
                    boolean owned = false;
                    for (int i = 0; i < MAX; i++) if (trailOf[i] == j && on[i]) { owned = true; break; }
                    if (!owned) { tuse[j] = false; tcnt[j] = 0; }
                    continue;
                }
                if (n < 2) continue;
                for (int k = 0; k < n; k++) {
                    sxp[k] = wx(tpx[j][k] * hw, tpy[j][k] * hh);
                    syp[k] = wcy + tpy[j][k] * hh * wf;
                }
                android.graphics.Path p = tpath[j];
                if (p == null) p = tpath[j] = new android.graphics.Path();
                p.reset();
                p.moveTo(sxp[0], syp[0]);
                for (int k = 1; k < n - 1; k++) p.quadTo(sxp[k], syp[k], (sxp[k] + sxp[k + 1]) / 2f, (syp[k] + syp[k + 1]) / 2f);
                p.lineTo(sxp[n - 1], syp[n - 1]);
                float ka = Math.max(0f, 1f - (clock - tpt[j][0]) / TRAIL_DRY), kb = Math.max(0f, 1f - (clock - tpt[j][n - 1]) / TRAIL_DRY);
                float w = twid[j];
                // o caminho molhado, claro
                atop.setShader(new android.graphics.LinearGradient(sxp[0], syp[0], sxp[n - 1], syp[n - 1],
                        0xFFFFFF | (Math.round(0x70 * ka) << 24), 0xFFFFFF | (Math.round(0x70 * kb) << 24), android.graphics.Shader.TileMode.CLAMP));
                atop.setColor(0xFFFFFFFF);
                atop.setStrokeWidth(w * 0.85f);
                c.drawPath(p, atop);
                atop.setShader(null);
                // a água do caminho funciona como uma lente comprida: o neon de trás aparece deslocado dentro dele
                int km = n / 2;
                if (lensOn && lensN.toBitmap(sxp[km], syp[km], q)) {
                    float m = 1.18f * q[2];
                    lm.reset();
                    lm.setTranslate(-q[0], -q[1]);
                    lm.postScale(m, m);
                    lm.postTranslate(sxp[km] + w * 0.45f, syp[km]);
                    lensSh.setLocalMatrix(lm);
                    android.graphics.Shader fade = new android.graphics.LinearGradient(sxp[0], syp[0], sxp[n - 1], syp[n - 1],
                            Math.round(0xE6 * ka * lensN.lastA) << 24, Math.round(0xE6 * kb * lensN.lastA) << 24, android.graphics.Shader.TileMode.CLAMP);
                    lensP.setShader(new android.graphics.ComposeShader(lensSh, fade, android.graphics.PorterDuff.Mode.DST_IN));
                    lensP.setStyle(android.graphics.Paint.Style.STROKE);
                    lensP.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                    lensP.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                    lensP.setStrokeWidth(w * 0.95f);
                    c.drawPath(p, lensP);
                    lensP.setShader(null);
                }
                // e uma beirada escura de um lado, para dar volume
                out.setShader(new android.graphics.LinearGradient(sxp[0], syp[0], sxp[n - 1], syp[n - 1],
                        Math.round(0x80 * ka) << 24, Math.round(0x80 * kb) << 24, android.graphics.Shader.TileMode.CLAMP));
                out.setColor(0xFF000000);
                out.setStrokeWidth(Math.max(1f, w * 0.18f));
                int sv = c.save();
                c.translate(-w * 0.46f, 0f);
                c.drawPath(p, out);
                c.restoreToCount(sv);
                out.setShader(null);
            }
            atop.setStyle(android.graphics.Paint.Style.FILL);
            out.setStyle(android.graphics.Paint.Style.FILL);
            for (int i = 0; i < MAX; i++) {
                if (!on[i]) continue;
                age[i] += dt;
                if (!slide[i] && slideF > 0f) {
                    // parada: escorre quando pesa mais do que o vidro segura (ou, perto disso, de vez em quando)
                    float lim = thr[i] * (1.5f - 0.5f * Math.min(2f, slideF));
                    if (r[i] > lim || (r[i] > lim * 0.8f && rnd.nextFloat() < dt * slideF / 5000f)) {
                        slide[i] = true;
                        vy[i] = 0f;
                        hold[i] = 0f;
                        trav[i] = 0f;
                        trailOf[i] = -1;
                        dxv[i] = (rnd.nextFloat() - 0.5f) * 0.00004f;
                    }
                }
                if (slide[i]) {
                    // velocidade pelo tamanho: gota grande desce mais rápido; o vidro freia e às vezes segura
                    float vt = Math.min(0.0011f, 0.00008f + (r[i] - 2.6f) * 0.0001f);
                    if (hold[i] > 0f) hold[i] -= dt;
                    else {
                        vy[i] += (vt - vy[i]) * Math.min(1f, dt / 140f);
                        if (rnd.nextFloat() < dt / (260f + r[i] * 160f)) { hold[i] = (40f + rnd.nextFloat() * 180f) * 3f / Math.max(2f, r[i]); vy[i] *= 0.25f; }
                    }
                    float py0 = y[i];
                    if (hold[i] <= 0f) y[i] += vy[i] * dt;
                    // procura caminho: desvia devagar de um lado para o outro, com embalo
                    dxv[i] += (rnd.nextFloat() - 0.5f) * 0.0000022f * dt;
                    dxv[i] *= (float) Math.pow(0.965f, dt / 16f);
                    x[i] += dxv[i] * dt;
                    // perde água pelo caminho: diminui e de vez em quando larga uma gotinha para trás
                    float moved = y[i] - py0;
                    trav[i] += moved;
                    r[i] -= moved * 1.6f;
                    if (trav[i] > 0.035f) {
                        trav[i] = 0f;
                        if (rnd.nextFloat() < 0.55f) {
                            int k2 = spawn(x[i] + (rnd.nextFloat() - 0.5f) * r[i] * d / hw, y[i] - r[i] * 1.6f * d / hh, 0.45f + rnd.nextFloat() * 0.6f);
                            if (k2 >= 0) { life[k2] = 2500f + rnd.nextFloat() * 4000f; thr[k2] = 99f; }
                        }
                    }
                    if (trailOf[i] < 0) trailOf[i] = newTrail(Math.max(2.4f * d, r[i] * d * 1.5f));
                    trailPoint(trailOf[i], x[i], y[i] - r[i] * d / hh * 0.3f);
                    // engole as gotas do caminho e cresce (pode acelerar)
                    for (int j = 0; j < MAX; j++) {
                        if (j == i || !on[j] || slide[j]) continue;
                        if (Math.abs(x[j] - x[i]) * hw < (r[i] + r[j] * 0.5f) * d && (y[j] - py0) * hh > -r[i] * d && (y[j] - y[i]) * hh < r[i] * d) {
                            on[j] = false;
                            r[i] = Math.min(9f, (float) Math.sqrt(r[i] * r[i] + r[j] * r[j] * 0.8f));
                        }
                    }
                    // ficou leve demais: para ali mesmo
                    if (r[i] < 2.8f) { slide[i] = false; vy[i] = 0f; thr[i] = 99f; trailOf[i] = -1; }
                    if (y[i] > 1.15f) { on[i] = false; continue; }
                    life[i] = Math.max(life[i], age[i] + 800f);
                }
                if (age[i] > life[i] || x[i] * x[i] + y[i] * y[i] > 1.1f) { on[i] = false; continue; }
                float k = Math.min(1f, age[i] / 180f) * Math.min(1f, (life[i] - age[i]) / 1500f);
                if (k <= 0.01f) continue;
                float grow = 0.75f + 0.25f * Math.min(1f, age[i] / 300f);   // ao bater, espalha um pouco
                float px = wx(x[i] * hw, y[i] * hh), py = wcy + y[i] * hh * wf;
                float rr = r[i] * d * grow * wf;
                float sx = rr * asp[i], sy = rr;
                if (slide[i] && vy[i] > 0f) sy *= 1f + Math.min(0.18f, vy[i] * 160f);
                int sv = c.save();
                c.translate(px, py);
                c.scale(sx, sy);
                android.graphics.Path sh = shape[i % SHAPES];
                if (rr < 1.3f * d) {
                    // gotinha: só um reflexo e uma sombrinha
                    atop.setColor(0xFFFFFF | (Math.round(0x80 * k) << 24));
                    c.drawPath(sh, atop);
                    c.restoreToCount(sv);
                    atop.setColor(0xFFFFFF | (Math.round(0xF0 * k) << 24));
                    c.drawCircle(px - sx * 0.25f, py - sy * 0.3f, Math.max(0.8f, rr * 0.45f), atop);
                    continue;
                }
                // a gota é água transparente: o corpo quase não muda a luz; a borda de cima escurece
                // (a luz desvia), a de baixo acende, e tem um reflexo
                atop.setColor(0xFFFFFF | (Math.round(0x6E * k) << 24));
                c.drawPath(sh, atop);                                      // água clara: a gota junta e espalha a luz
                c.restoreToCount(sv);
                if (lensOn && rr >= 1.9f * d && lensN.toBitmap(px, py, q)) {
                    // gota: lentezinha que mostra o neon de trás de cabeça para baixo e aumentado
                    float m = 1.75f * q[2];
                    lm.reset();
                    lm.setTranslate(-q[0], -q[1]);
                    lm.postScale(-m, -m);
                    lm.postTranslate(px, py);
                    lensSh.setLocalMatrix(lm);
                    pm.reset();
                    pm.setScale(sx * 0.92f, sy * 0.92f);
                    pm.postTranslate(px, py);
                    sh.transform(pm, tmpPath);
                    lensP.setShader(lensSh);
                    lensP.setStyle(android.graphics.Paint.Style.FILL);
                    lensP.setAlpha(Math.round(240 * k * lensN.lastA));
                    c.drawPath(tmpPath, lensP);
                    lensP.setShader(null);
                    lensP.setAlpha(255);
                }
                float lw = Math.max(0.7f * d * 0.6f, rr * 0.2f);
                sv = c.save();
                c.translate(px, py - sy * 0.12f);
                c.scale(sx, sy);
                out.setStyle(android.graphics.Paint.Style.STROKE);
                out.setStrokeWidth(lw / rr);
                out.setColor(Math.round(0xA0 * k) << 24);
                c.drawPath(sh, out);                                       // contorno escuro fino em cima
                out.setStyle(android.graphics.Paint.Style.FILL);
                c.restoreToCount(sv);
                sv = c.save();
                c.translate(px, py + sy * 0.1f);
                c.scale(sx * 0.82f, sy * 0.8f);
                c.clipRect(-1.5f, 0.15f, 1.5f, 1.5f);
                atop.setStyle(android.graphics.Paint.Style.STROKE);
                atop.setStrokeWidth(lw * 1.6f / rr);
                atop.setColor(0xFFFFFF | (Math.round(0xFF * k) << 24));
                c.drawPath(sh, atop);                                      // luz que atravessa: borda de baixo clara
                atop.setStyle(android.graphics.Paint.Style.FILL);
                c.restoreToCount(sv);
                atop.setColor(0xFFFFFF | (Math.round(0xF0 * k) << 24));
                c.drawOval(px - sx * 0.55f, py - sy * 0.6f, px - sx * 0.05f, py - sy * 0.22f, atop);   // reflexo
            }
        }

        /** Formatos de gota (raio 1): contornos tortos e suaves, nenhum redondo perfeito. */
        static final int SHAPES = 16;
        static final android.graphics.Path[] shape = new android.graphics.Path[SHAPES];
        static {
            java.util.Random r = new java.util.Random(11);
            for (int s = 0; s < SHAPES; s++) {
                int n = 7 + r.nextInt(3);
                float[] px = new float[n], py = new float[n];
                for (int i = 0; i < n; i++) {
                    double a = Math.PI * 2 * i / n + (r.nextFloat() - 0.5f) * 0.4f;
                    float rad = 0.78f + r.nextFloat() * 0.34f;
                    if (Math.sin(a) > 0.3) rad *= 1.06f;   // a água pesa embaixo
                    px[i] = (float) Math.cos(a) * rad;
                    py[i] = (float) Math.sin(a) * rad;
                }
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo((px[n - 1] + px[0]) / 2f, (py[n - 1] + py[0]) / 2f);
                for (int i = 0; i < n; i++) {
                    int j = (i + 1) % n;
                    p.quadTo(px[i], py[i], (px[i] + px[j]) / 2f, (py[i] + py[j]) / 2f);
                }
                p.close();
                shape[s] = p;
            }
        }

        /** Fora do neon: só o reflexo das gotas maiores, bem fraco, para o vidro molhado aparecer no escuro também. */
        static void glass(Canvas c, float vw, float top, float bottom, float d, float a) {
            float cx = vw / 2f, cy = (top + bottom) / 2f, hw = vw / 2f, hh = (bottom - top) / 2f;
            android.graphics.Paint g = gp;
            for (int i = 0; i < MAX; i++) {
                if (!on[i] || r[i] < 1.8f) continue;
                float k = a * Math.min(1f, age[i] / 180f) * Math.min(1f, (life[i] - age[i]) / 1500f);
                if (k <= 0.02f) continue;
                float px = cx + x[i] * hw, py = cy + y[i] * hh, rr = r[i] * d;
                g.setStyle(android.graphics.Paint.Style.STROKE);
                g.setStrokeWidth(Math.max(0.7f, rr * 0.16f));
                g.setColor(0xFFFFFF | (Math.round(0x2A * k) << 24));
                c.drawArc(px - rr * asp[i], py - rr, px + rr * asp[i], py + rr, 20, 140, false, g);
                g.setStyle(android.graphics.Paint.Style.FILL);
                g.setColor(0xFFFFFF | (Math.round(0x55 * k) << 24));
                c.drawCircle(px - rr * asp[i] * 0.35f, py - rr * 0.4f, Math.max(0.6f, rr * 0.2f), g);
            }
        }

        static final android.graphics.Paint gp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    }
}
