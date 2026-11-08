package com.ilha.headsup;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Letras "estilo vídeo lírico" (pincel ou neon) também no StandBy do HyperImmersive.
 *
 * O StandBy é uma tela do próprio app HyperImmersive (outro processo, que precisa estar no escopo
 * do Hyper Eternity). A coluna das letras é desenhada por LyricsFlow.draw(canvas, esquerda, topo,
 * direita, base, alfa), com as linhas que o HyperMusicCover manda (HmcLyrics) e a posição do
 * player (MediaArt.positionNow). O gancho desenha o nosso estilo no mesmo retângulo; a tela do
 * StandBy continua se redesenhando enquanto o desenho devolve true (aqui: enquanto toca).
 */
public final class StandbyLyrics {
    private StandbyLyrics() {}

    static Object lyr, art;
    static Method mLines, mPos, mPlaying, mArt;
    static Method lText, lStart, lEnd, lSyl, lChar;
    static List<?> lastSrc;
    static Context app;
    static int logs;

    static void log(String m) {
        if (logs++ < 30) XposedBridge.log("[HeadsUpIlha] letras no StandBy: " + m);
    }

    static void install(ClassLoader cl) {
        Prefs.load();
        try {
            Class<?> hl = cl.loadClass("com.os4.immersive.HmcLyrics");
            lyr = hl.getField("INSTANCE").get(null);
            mLines = hl.getMethod("getLines");
            Class<?> ma = cl.loadClass("com.os4.immersive.MediaArt");
            art = ma.getField("INSTANCE").get(null);
            mPos = ma.getMethod("positionNow");
            mPlaying = ma.getMethod("getPlaying");
            mArt = ma.getMethod("getArt");
            Class<?> lf = cl.loadClass("com.os4.immersive.LyricsFlow");
            Method draw = lf.getDeclaredMethod("draw", Canvas.class, float.class, float.class, float.class, float.class, float.class);
            XposedBridge.hookMethod(draw, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    Prefs.refresh();
                    if (!Prefs.lyricVideo || !Prefs.lyricStandby) return;
                    try {
                        Boolean r = draw((Canvas) p.args[0], (Float) p.args[1], (Float) p.args[2], (Float) p.args[3], (Float) p.args[4], (Float) p.args[5]);
                        if (r != null) p.setResult(r);
                    } catch (Throwable t) {
                        log("desenho: " + t);
                    }
                }
            });
            LyricVideo.artSrc = new java.util.concurrent.Callable<Bitmap>() {
                public Bitmap call() throws Exception {
                    return (Bitmap) mArt.invoke(art);
                }
            };
            log("pronto");
        } catch (Throwable t) {
            log("indisponível nesta versão do HyperImmersive: " + t);
        }
    }

    /** null: deixa o HyperImmersive desenhar (sem letra); senão, o que o desenho original devolveria. */
    static Boolean draw(Canvas c, float l, float top, float r, float b, float alpha) throws Throwable {
        Object o = mLines.invoke(lyr);
        if (!(o instanceof List) || ((List<?>) o).isEmpty()) return null;
        List<?> src = (List<?>) o;
        if (src != lastSrc) {
            lastSrc = src;
            LyricVideo.group(convert(src));
        }
        if (LyricVideo.groups.isEmpty()) return null;
        Context ctx = context();
        if (ctx == null) return null;
        LyricVideo.ctx = ctx;
        float d = ctx.getResources().getDisplayMetrics().density;
        long t = (Long) mPos.invoke(art) + Prefs.lyricShift * 100L;
        boolean playing = (Boolean) mPlaying.invoke(art);
        LyricVideo.playing = playing;
        float h = b - top;
        LyricVideo.fx0 = l;
        LyricVideo.fx1 = r;
        LyricVideo.areaTop = Math.round(top + h * 0.1f);
        LyricVideo.areaBottom = Math.round(b - h * 0.1f);
        LyricVideo.lx0 = 0;
        LyricVideo.ly0 = 0;
        LyricVideo.lx1 = Math.max(c.getWidth(), r);
        LyricVideo.ly1 = Math.max(c.getHeight(), b);
        int w = Math.round(r - l - 24 * d);
        if (w <= 0) return Boolean.FALSE;
        int sv = alpha < 0.99f ? c.saveLayerAlpha(LyricVideo.lx0, LyricVideo.ly0, LyricVideo.lx1, LyricVideo.ly1, Math.round(255 * Math.max(0f, alpha))) : -1;
        try {
            LyricVideo.render(c, t, d, w);
        } finally {
            if (sv >= 0) c.restoreToCount(sv);
        }
        return playing;
    }

    static Context context() {
        if (app == null) {
            try {
                app = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            } catch (Throwable t) {
                log("sem contexto: " + t);
            }
        }
        return app;
    }

    /** Linhas do HyperImmersive (HmcLyrics.Line: texto, início, fim e, quando há, o tempo de cada sílaba). */
    static ArrayList<LyricVideo.Line> convert(List<?> src) {
        ArrayList<LyricVideo.Line> ls = new ArrayList<>();
        for (Object o : new ArrayList<Object>(src)) {
            if (o == null) continue;
            try {
                if (lText == null) {
                    Class<?> k = o.getClass();
                    lText = k.getMethod("getText");
                    lStart = k.getMethod("getStart");
                    lEnd = k.getMethod("getEnd");
                    try {
                        lSyl = k.getMethod("getSylStart");
                        lChar = k.getMethod("getCharEnd");
                    } catch (Throwable ignored) {
                    }
                }
                LyricVideo.Line l = new LyricVideo.Line();
                Object tx = lText.invoke(o);
                l.text = tx == null ? "" : tx.toString().trim();
                l.start = (Integer) lStart.invoke(o);
                l.end = Math.max(l.start, (Integer) lEnd.invoke(o));
                if (lSyl != null) {
                    int[] a = (int[]) lSyl.invoke(o), b = (int[]) lChar.invoke(o);
                    if (a != null && b != null && a.length == b.length && a.length > 0) {
                        l.sylStart = a;
                        l.sylChar = b;
                    }
                }
                if (!l.text.isEmpty()) ls.add(l);
            } catch (Throwable t) {
                log("linha: " + t);
            }
        }
        return ls;
    }
}
