package com.ilha.headsup;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Página de música do mod "iOS26 Control Center" (com.kashi.ios.IosMediaView, modo alto): ela é
 * desenhada à mão (capa, título, controles). Antes do corpo ser desenhado, o módulo desenha a
 * capa de ponta a ponta no topo com degradê e o título/artista no centro; a capa e o texto
 * originais são escondidos só durante esse desenho (os toques continuam nas mesmas áreas).
 */
final class IosMediaPage {
    private IosMediaPage() {}

    static Field fArt, fTy, fTitle, fSub, fMode, fBmp;
    static Method mTitleStr, mSubStr, mPlaying;
    static int logs, why;
    static final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    static final Paint fade = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG), sp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    static final Path clip = new Path();
    static final RectF keep = new RectF();
    static int aT = -1, aS = -1;
    static float zT, zS;
    static Field fSeek, fD, fDragging, fDragFrac, fTarget;
    static int seekSave = -1;
    static final Paint trackP = new Paint(Paint.ANTI_ALIAS_FLAG), cometP = new Paint(Paint.ANTI_ALIAS_FLAG), glowP = new Paint(Paint.ANTI_ALIAS_FLAG);
    static Bitmap tintFrom;
    static int tint = 0xFF9EC9FF;
    static float shownFrac = -1f;
    static Field fSmall, fFill;
    static Paint small, fill;
    static Explicit.Badge eBadge;

    static void install(ClassLoader cl) {
        try {
            Class<?> c = Class.forName("com.kashi.ios.IosMediaView", false, cl);
            fArt = f(c, "rArt");
            fTy = f(c, "ty");
            fTitle = f(c, "mTitle");
            fSub = f(c, "mSub");
            fMode = f(c, "mMode");
            fBmp = f(c, "mShaderBmp");
            fSmall = f(c, "mSmall");
            fFill = f(c, "mFill");
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 0 || !java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getName().startsWith("m2433")) mTitleStr = m;
                if (m.getName().startsWith("m2557")) mSubStr = m;
                if (m.getName().startsWith("m2431")) mPlaying = m;
            }
            if (mTitleStr != null) mTitleStr.setAccessible(true);
            if (mSubStr != null) mSubStr.setAccessible(true);
            if (mPlaying != null) mPlaying.setAccessible(true);
            fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            Method body = c.getDeclaredMethod("drawBody", Canvas.class);
            XposedBridge.hookMethod(body, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        before((View) p.thisObject, (Canvas) p.args[0]);
                    } catch (Throwable t) {
                        if (logs++ < 5) XposedBridge.log("[HeadsUpIlha] player iOS (página do mod): " + t);
                    }
                }

                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        after(p.thisObject);
                    } catch (Throwable ignored) {
                    }
                }
            });
            fSeek = f(c, "rSeek");
            fD = f(c, "d");
            fDragging = f(c, "mDragging");
            fDragFrac = f(c, "mDragFrac");
            fTarget = f(c, "mTarget");
            XposedBridge.hookMethod(c.getDeclaredMethod("drawSeek", Canvas.class, boolean.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    seekSave = -1;
                    try {
                        seekBefore((View) p.thisObject, (Canvas) p.args[0]);
                    } catch (Throwable t) {
                        if (logs++ < 6) XposedBridge.log("[HeadsUpIlha] player iOS (barra): " + t);
                    }
                }

                protected void afterHookedMethod(MethodHookParam p) {
                    if (seekSave >= 0) ((Canvas) p.args[0]).restoreToCount(seekSave);
                    seekSave = -1;
                }
            });
            XposedBridge.hookMethod(c.getDeclaredMethod("onDraw", Canvas.class), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if (small != null) small.setBlendMode(null);
                    if (fill != null) fill.setBlendMode(null);
                    small = fill = null;
                }
            });
            XposedBridge.log("[HeadsUpIlha] player iOS: página de música do mod iOS26 acompanhada");
        } catch (Throwable t) {
            XposedBridge.log("[HeadsUpIlha] player iOS: página do mod indisponível (" + t + ")");
        }
    }

    static Field f(Class<?> c, String n) throws Exception {
        Field x = c.getDeclaredField(n);
        x.setAccessible(true);
        return x;
    }

    static void before(View v, Canvas c) throws Exception {
        aT = aS = -1;
        int mode = fMode.getInt(v);
        if (!Prefs.playerIos || mode != 4) {
            if (why++ < 4) XposedBridge.log("[HeadsUpIlha] player iOS: página do mod modo=" + mode + " opção=" + Prefs.playerIos + " " + v.getWidth() + "x" + v.getHeight());
            return;
        }
        Bitmap bmp = (Bitmap) fBmp.get(v);
        // título e artista da sessão de mídia (os métodos do mod têm nomes embaralhados)
        boolean playing = true;
        String title = null, sub = null;
        boolean explicit = false;
        android.media.session.MediaController mc = IosPlayer.controller(v.getContext());
        if (mc != null && mc.getMetadata() != null) {
            android.media.MediaMetadata md = mc.getMetadata();
            CharSequence a = md.getText(android.media.MediaMetadata.METADATA_KEY_TITLE);
            CharSequence b = md.getText(android.media.MediaMetadata.METADATA_KEY_ARTIST);
            if (b == null) b = md.getText(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            title = a == null ? null : a.toString();
            sub = b == null ? null : b.toString();
            explicit = md.getLong("android.media.IS_EXPLICIT") == 1;
        }
        if (title == null) {
            title = sub;
            sub = null;
        }
        if (bmp == null || bmp.isRecycled() || title == null) {
            if (why++ < 8) XposedBridge.log("[HeadsUpIlha] player iOS: página do mod sem capa/título (capa=" + (bmp != null) + " tocando=" + playing + " título=" + title
                    + " métodos " + (mTitleStr == null ? "-" : mTitleStr.getName()) + "/" + (mSubStr == null ? "-" : mSubStr.getName()) + "/" + (mPlaying == null ? "-" : mPlaying.getName()) + ")");
            return;
        }
        RectF r = (RectF) fArt.get(v);
        TextPaint t0 = (TextPaint) fTitle.get(v), s0 = (TextPaint) fSub.get(v);
        float dp = v.getResources().getDisplayMetrics().density;
        float w = v.getWidth();
        float ty = fTy.getFloat(v);
        float titleBase = ty - t0.descent();
        float subBase = titleBase + t0.getFontSpacing() + 2 * dp;
        float textEnd = (sub != null ? subBase + s0.descent() : titleBase + t0.descent());
        float bottom = textEnd + 44 * dp;
        float radius = 34 * dp;

        // capa de ponta a ponta, sumindo num degradê até o fim do texto
        int save = c.saveLayer(0, 0, w, bottom, null);
        clip.reset();
        clip.addRoundRect(0, 0, w, bottom + radius, radius, radius, Path.Direction.CW);
        c.clipPath(clip);
        float sc = Math.max(w / bmp.getWidth(), bottom / bmp.getHeight());
        Matrix m = new Matrix();
        m.setScale(sc, sc);
        m.postTranslate((w - bmp.getWidth() * sc) / 2f, (bottom - bmp.getHeight() * sc) / 2f);
        BitmapShader bs = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        bs.setLocalMatrix(m);
        art.setShader(bs);
        c.drawRect(0, 0, w, bottom, art);
        float f0 = bottom * 0.5f;
        fade.setShader(new LinearGradient(0, f0, 0, bottom, new int[]{0x00000000, 0x55000000, 0xB0000000, 0xFF000000}, new float[]{0f, 0.4f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, f0, w, bottom, fade);
        c.restoreToCount(save);

        // título e artista no centro
        tp.set(t0);
        tp.setTypeface(Typeface.create(t0.getTypeface(), Typeface.BOLD));
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setAlpha(255);
        float maxW = w - 48 * dp;
        boolean night = (v.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        night = true;   // a página fica sobre o vidro escuro da central
        if (explicit && Prefs.explicitE) {
            // selo "E" antes do título (como no Spotify e no iOS), o par centralizado
            float bh = tp.getTextSize() * 0.72f, gap = 6 * dp;
            String t = TextUtils.ellipsize(title, tp, maxW - bh - gap, TextUtils.TruncateAt.END).toString();
            float tw = tp.measureText(t);
            float x0 = (w - (bh + gap + tw)) / 2f;
            float by = titleBase - tp.getTextSize() * 0.36f - bh / 2f;
            if (eBadge == null) eBadge = new Explicit.Badge(tp.getTextSize(), tp.getColor());
            eBadge.setColor(tp.getColor());
            eBadge.setBounds(Math.round(x0), Math.round(by), Math.round(x0 + bh), Math.round(by + bh));
            eBadge.draw(c);
            c.drawText(t, x0 + bh + gap + tw / 2f, titleBase, tp);
        } else {
            c.drawText(TextUtils.ellipsize(title, tp, maxW, TextUtils.TruncateAt.END).toString(), w / 2f, titleBase, tp);
        }
        if (sub != null) {
            sp.set(s0);
            sp.setTextAlign(Paint.Align.CENTER);
            c.drawText(TextUtils.ellipsize(sub, sp, maxW, TextUtils.TruncateAt.END).toString(), w / 2f, subBase, sp);
        }
        // vibrance nos tempos, na barra de progresso, no volume e na pílula do fone: o mod desenha
        // com mSmall (textos) e mFill (trilhos e fundo da pílula); vale até o fim do onDraw
        try {
            small = (Paint) fSmall.get(v);
            fill = (Paint) fFill.get(v);
            small.setBlendMode(MediaCut.mode(night));
            fill.setBlendMode(MediaCut.mode(night));
        } catch (Throwable ignored) {
        }

        // a capa e o texto do mod ficam de fora só neste desenho
        keep.set(r);
        r.setEmpty();
        // o mod troca a cor (e a transparência) do texto antes de desenhar: some pelo tamanho
        zT = t0.getTextSize();
        zS = s0.getTextSize();
        aT = aS = 1;
        t0.setTextSize(0.01f);
        s0.setTextSize(0.01f);
        if (logs++ < 2) XposedBridge.log("[HeadsUpIlha] player iOS: página do mod desenhada (" + (int) w + "x" + (int) bottom + ")");
    }

    /**
     * Barra de progresso andando lisa (como no outro player): o mod só atualiza a posição de
     * tempos em tempos e a barra pulava; aqui ela segue o tempo da música a cada quadro. O desenho
     * da barra do mod é cortado; os tempos continuam sendo dele.
     */
    static void seekBefore(View v, Canvas c) throws Exception {
        if (!Prefs.playerIos || fMode.getInt(v) != 4) return;
        RectF rs = (RectF) fSeek.get(v);
        float d = fD.getFloat(v);
        boolean drag = fDragging.getBoolean(v) && fTarget.getInt(v) == 6;
        float th = (drag ? 9 : 5) * d, cy = rs.centerY();
        float frac;
        if (drag) {
            frac = fDragFrac.getFloat(v);
        } else {
            frac = -1f;
            android.media.session.MediaController mc = IosPlayer.controller(v.getContext());
            if (mc != null && mc.getPlaybackState() != null && mc.getMetadata() != null) {
                long dur = mc.getMetadata().getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
                android.media.session.PlaybackState ps = mc.getPlaybackState();
                long pos = ps.getPosition();
                if (ps.getState() == android.media.session.PlaybackState.STATE_PLAYING) {
                    long e = android.os.SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime();
                    if (e > 0) pos += (long) (e * ps.getPlaybackSpeed());
                    v.postInvalidateOnAnimation();
                }
                if (dur > 0) frac = Math.max(0f, Math.min(1f, pos / (float) dur));
            }
            if (frac < 0f) return;   // sem tempo: fica a barra do mod
        }
        boolean night = true;
        // a mesma barra do mod (trilho com vibrance e parte tocada branca), mas andando lisa
        trackP.setColor(0x33FFFFFF);
        trackP.setBlendMode(MediaCut.mode(night));
        c.drawRoundRect(rs.left, cy - th / 2f, rs.right, cy + th / 2f, th / 2f, th / 2f, trackP);
        float x = rs.left + Math.max(th, (rs.right - rs.left) * frac);
        cometP.setShader(null);
        cometP.setColor(0xE6FFFFFF);
        cometP.setBlendMode(MediaCut.mode(night));
        c.drawRoundRect(rs.left, cy - th / 2f, x, cy + th / 2f, th / 2f, th / 2f, cometP);
        // corta a barra do mod (o trilho e o preenchimento) deste desenho
        seekSave = c.save();
        c.clipOutRect(rs.left - th, cy - th * 0.9f, rs.right + th, cy + th * 0.9f);
    }

    /** Cor da capa: a mais viva, clareada (como no outro player). */
    static int tintOf(Bitmap b) {
        if (b == null || b.isRecycled() || b == tintFrom) return tint;
        tintFrom = b;
        try {
            Bitmap src = b.getConfig() == Bitmap.Config.HARDWARE ? b.copy(Bitmap.Config.ARGB_8888, false) : b;
            Bitmap s = Bitmap.createScaledBitmap(src, 12, 12, true);
            if (src != b) src.recycle();
            float best = -1f;
            float[] hsv = new float[3], pick = {210f, 0.35f, 1f};
            for (int y = 0; y < 12; y++)
                for (int x = 0; x < 12; x++) {
                    android.graphics.Color.colorToHSV(s.getPixel(x, y), hsv);
                    float sc = hsv[1] * (0.3f + hsv[2]);
                    if (sc > best) {
                        best = sc;
                        pick = hsv.clone();
                    }
                }
            if (s != b) s.recycle();
            pick[1] = Math.min(pick[1], 0.5f);
            pick[2] = 1f;
            tint = android.graphics.Color.HSVToColor(pick);
        } catch (Throwable ignored) {
        }
        return tint;
    }

    static void after(Object v) throws Exception {
        if (aT < 0) return;
        ((RectF) fArt.get(v)).set(keep);
        ((TextPaint) fTitle.get(v)).setTextSize(zT);
        ((TextPaint) fSub.get(v)).setTextSize(zS);
        aT = aS = -1;
    }
}
