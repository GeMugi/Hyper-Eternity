package com.ilha.headsup;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * Player compacto (estilo iOS) da gaveta em paisagem: capa no alto que se dissolve no cartão, título,
 * artista, progresso, controles e volume. Desenhado aqui, falando direto com a sessão de mídia.
 */
final class LandMini extends View {
    static LandMini view;
    static int logs;
    /** Capa e botões do player do HyperOS (desenhados aqui com os mesmos drawables, sem refazer nada). */
    static java.util.ArrayList<java.lang.ref.WeakReference<android.widget.ImageView>> srcActions;
    static java.lang.ref.WeakReference<android.widget.ImageView> srcAlbum;
    static java.lang.ref.WeakReference<View> srcBar, ref, hostRef;
    static float refY, baseY;
    static boolean listening;
    final java.util.ArrayList<android.widget.ImageView> btns = new java.util.ArrayList<>();
    final float[] btnX = new float[5];
    final android.graphics.Rect keep = new android.graphics.Rect();

    /** Mostra o player compacto no retângulo (coordenadas da janela). */
    static void show(View root, float x, float y, float w, float h) {
        try {
            if (!(root instanceof ViewGroup)) return;
            ViewGroup g = (ViewGroup) root;
            if (view == null) view = new LandMini(g.getContext());
            if (view.getParent() != g) {
                if (view.getParent() != null) ((ViewGroup) view.getParent()).removeView(view);
                g.addView(view, new ViewGroup.LayoutParams(Math.round(w), Math.round(h)));
                if (logs++ < 2) ElasticCard.log("gaveta paisagem: player compacto ligado (" + Math.round(w) + "x" + Math.round(h) + ")");
            }
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp.width != Math.round(w) || lp.height != Math.round(h)) {
                lp.width = Math.round(w);
                lp.height = Math.round(h);
                view.setLayoutParams(lp);
            }
            view.setTranslationX(x);
            baseY = y;
            if (view.getVisibility() == GONE) { view.setVisibility(VISIBLE); view.follow(); }
            if (!listening) {
                listening = true;
                // a cada quadro: segue a animação do player do HyperOS (fora da tela), como os cartões
                g.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                    public boolean onPreDraw() {
                        if (view != null && view.getVisibility() != GONE) view.follow();
                        return true;
                    }
                });
            }
            view.bind();
        } catch (Throwable t) {
            if (logs++ < 6) ElasticCard.log("gaveta paisagem: player compacto: " + t);
        }
    }

    /** Alfa, deslocamento e escala do player do HyperOS (a animação de abrir/fechar dos cartões). */
    void follow() {
        View pv = ref == null ? null : ref.get(), h = hostRef == null ? null : hostRef.get();
        float a = 0f, dy = 0f, sc = 1f;
        if (pv != null && h != null && pv.isShown()) {
            a = pv.getAlpha();
            for (View v = h; v != null && v != getParent(); v = v.getParent() instanceof View ? (View) v.getParent() : null) a *= v.getAlpha();
            dy = pv.getTranslationY() - refY;
            sc = pv.getScaleY();
        }
        a = Math.max(0f, Math.min(1f, a));
        if (getAlpha() != a) setAlpha(a);
        setTranslationY(baseY + dy);
        setScaleX(sc);
        setScaleY(sc);
        int vis = a > 0.02f ? VISIBLE : INVISIBLE;   // invisível não recebe toque (heads-up, centro de controle)
        if (getVisibility() != vis) setVisibility(vis);
    }

    static void hide() {
        if (view != null && view.getVisibility() != GONE) {
            view.setVisibility(GONE);
            view.unbind();
        }
    }

    static boolean showing() {
        return view != null && view.getVisibility() == VISIBLE && view.getParent() != null;
    }

    // ------------------------------------------------------------------------------------------

    final float d;
    MediaController ctl;
    MediaController.Callback cb;
    Bitmap art;
    String title = "", artist = "", pkg;
    android.graphics.drawable.Drawable appIcon;
    final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    final TextPaint tTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG), tArtist = new TextPaint(Paint.ANTI_ALIAS_FLAG), tTime = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final RectF r = new RectF();
    long lastFind;
    int pressed = -1;   // 0 anterior, 1 tocar, 2 próxima
    int dragging = -1;  // 3 progresso, 4 volume
    float dragFrac;
    // geometria (coordenadas da view), calculada no desenho
    float k, barL, barR, barY, volL, volR, volY, cy, cxPrev, cxPlay, cxNext, artBottom;

    LandMini(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        card.setColor(0xF01C1C1E);
        tTitle.setColor(0xFFFFFFFF);
        tTitle.setTextAlign(Paint.Align.CENTER);
        tTitle.setTypeface(Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 700, false));
        tArtist.setColor(0xB3FFFFFF);
        tArtist.setTextAlign(Paint.Align.CENTER);
        tTime.setColor(0x8CFFFFFF);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void bind() {
        long now = android.os.SystemClock.uptimeMillis();
        if (ctl != null && now - lastFind < 3000) return;
        lastFind = now;
        try {
            MediaSessionManager m = getContext().getSystemService(MediaSessionManager.class);
            java.util.List<MediaController> l = m.getActiveSessions(null);
            MediaController best = null;
            for (MediaController c : l) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
                if (best == null && c.getMetadata() != null) best = c;
            }
            if (best == null) return;
            if (ctl != null && ctl.getSessionToken().equals(best.getSessionToken())) return;
            unbind();
            ctl = best;
            cb = new MediaController.Callback() {
                public void onMetadataChanged(MediaMetadata md) { read(); invalidate(); }
                public void onPlaybackStateChanged(PlaybackState s) { invalidate(); }
                public void onSessionDestroyed() { ctl = null; lastFind = 0; invalidate(); }
            };
            ctl.registerCallback(cb, getHandler() != null ? getHandler() : new android.os.Handler(android.os.Looper.getMainLooper()));
            pkg = ctl.getPackageName();
            try { appIcon = getContext().getPackageManager().getApplicationIcon(pkg); } catch (Throwable t) { appIcon = null; }
            read();
            invalidate();
        } catch (Throwable t) {
            if (logs++ < 6) ElasticCard.log("gaveta paisagem: sessão de mídia: " + t);
        }
    }

    void unbind() {
        try { if (ctl != null && cb != null) ctl.unregisterCallback(cb); } catch (Throwable ignored) {}
        ctl = null;
        cb = null;
    }

    void read() {
        MediaMetadata md = ctl == null ? null : ctl.getMetadata();
        if (md == null) return;
        String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (t == null) t = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (a == null) a = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (a == null) a = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        title = t == null ? "" : t;
        artist = a == null ? "" : a;
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        art = b;
    }

    long duration() {
        MediaMetadata md = ctl == null ? null : ctl.getMetadata();
        return md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    long position() {
        PlaybackState s = ctl == null ? null : ctl.getPlaybackState();
        if (s == null) return 0;
        long p = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            p += (long) ((android.os.SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        long du = duration();
        return du > 0 ? Math.max(0, Math.min(du, p)) : Math.max(0, p);
    }

    boolean playing() {
        PlaybackState s = ctl == null ? null : ctl.getPlaybackState();
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    float volFrac() {
        try {
            AudioManager am = getContext().getSystemService(AudioManager.class);
            int mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            return mx <= 0 ? 0f : am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) mx;
        } catch (Throwable t) {
            return 0.5f;
        }
    }

    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (ctl == null) bind();
        // desenho pensado em 320 x 340 dp, encolhido para caber
        k = Math.min(w / (320f * d), h / (340f * d));
        float W = w, H = h, rad = 30 * d * k;
        path.reset();
        r.set(0, 0, W, H);
        path.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.save();
        c.clipPath(path);
        c.drawRect(r, card);
        // capa no alto, dissolvendo no cartão
        artBottom = H * 0.50f;
        android.widget.ImageView av = srcAlbum == null ? null : srcAlbum.get();
        android.graphics.drawable.Drawable ad = av == null ? null : av.getDrawable();
        if (ad != null && ad.getIntrinsicWidth() > 0 && ad.getIntrinsicHeight() > 0) {
            float sc = Math.max(W / ad.getIntrinsicWidth(), artBottom / ad.getIntrinsicHeight());
            float bw = ad.getIntrinsicWidth() * sc, bh = ad.getIntrinsicHeight() * sc;
            c.save();
            c.clipRect(0, 0, W, artBottom);
            ad.copyBounds(keep);
            ad.setBounds(Math.round((W - bw) / 2f), Math.round((artBottom - bh) / 2f), Math.round((W + bw) / 2f), Math.round((artBottom + bh) / 2f));
            ad.draw(c);
            ad.setBounds(keep);
            c.restore();
            fill.setShader(new LinearGradient(0, artBottom * 0.45f, 0, artBottom, 0x001C1C1E, 0xF01C1C1E, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, artBottom + 1, fill);
            fill.setShader(null);
        } else if (art != null && !art.isRecycled()) {
            float sc = Math.max(W / art.getWidth(), artBottom / art.getHeight());
            float bw = art.getWidth() * sc, bh = art.getHeight() * sc;
            c.save();
            c.clipRect(0, 0, W, artBottom);
            r.set((W - bw) / 2f, (artBottom - bh) / 2f, (W + bw) / 2f, (artBottom + bh) / 2f);
            c.drawBitmap(art, null, r, bmp);
            c.restore();
            fill.setShader(new LinearGradient(0, artBottom * 0.45f, 0, artBottom, 0x001C1C1E, 0xF01C1C1E, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, W, artBottom + 1, fill);
            fill.setShader(null);
        }
        float u = d * k;   // 1 dp desenhado
        // ícone do app no canto
        if (appIcon != null) {
            int is = Math.round(26 * u);
            int ix = Math.round(W - 14 * u - is), iy = Math.round(14 * u);
            appIcon.setBounds(ix, iy, ix + is, iy + is);
            appIcon.draw(c);
        }
        // título e artista
        tTitle.setTextSize(21 * u);
        tArtist.setTextSize(15 * u);
        float ty = artBottom + 4 * u;
        String ts = TextUtils.ellipsize(title, tTitle, W - 40 * u, TextUtils.TruncateAt.END).toString();
        String as = TextUtils.ellipsize(artist, tArtist, W - 40 * u, TextUtils.TruncateAt.END).toString();
        c.drawText(ts, W / 2f, ty, tTitle);
        c.drawText(as, W / 2f, ty + 22 * u, tArtist);
        // progresso
        barL = 22 * u;
        barR = W - 22 * u;
        barY = ty + 46 * u;
        long du = duration(), po = position();
        float pf = dragging == 3 ? dragFrac : (du > 0 ? po / (float) du : 0f);
        View sb = srcBar == null ? null : srcBar.get();
        if (dragging != 3 && sb != null && sb.getWidth() > 0 && sb.getHeight() > 0) {
            // a barra do próprio player (com o cometa, quando ligado), só redimensionada
            float sx = (barR - barL) / sb.getWidth();
            c.save();
            c.translate(barL, barY - sb.getHeight() * sx / 2f);
            c.scale(sx, sx);
            sb.draw(c);
            c.restore();
        } else {
            fill.setColor(0x33FFFFFF);
            c.drawRoundRect(barL, barY - 2 * u, barR, barY + 2 * u, 2 * u, 2 * u, fill);
            fill.setColor(0xE6FFFFFF);
            c.drawRoundRect(barL, barY - 2 * u, barL + (barR - barL) * pf, barY + 2 * u, 2 * u, 2 * u, fill);
        }
        tTime.setTextSize(12 * u);
        tTime.setTextAlign(Paint.Align.LEFT);
        c.drawText(time(dragging == 3 ? (long) (du * dragFrac) : po), barL, barY + 18 * u, tTime);
        tTime.setTextAlign(Paint.Align.RIGHT);
        c.drawText(du > 0 ? time(du) : "--:--", barR, barY + 18 * u, tTime);
        // controles: os botões do player do HyperOS, na mesma ordem (anterior, tocar, próxima...)
        cy = barY + 50 * u;
        btns.clear();
        if (srcActions != null) {
            for (java.lang.ref.WeakReference<android.widget.ImageView> wr : srcActions) {
                android.widget.ImageView iv = wr.get();
                if (iv != null && iv.getVisibility() == VISIBLE && iv.getDrawable() != null) btns.add(iv);
            }
            java.util.Collections.sort(btns, new java.util.Comparator<android.widget.ImageView>() {
                public int compare(android.widget.ImageView a1, android.widget.ImageView b1) { return Float.compare(xOf(a1), xOf(b1)); }
            });
        }
        int n = Math.min(5, btns.size());
        if (n > 0) {
            float gap = 76 * u;
            float x0 = W / 2f - gap * (n - 1) / 2f;
            int mid = n % 2 == 1 ? n / 2 : 0;
            for (int i = 0; i < n; i++) {
                btnX[i] = x0 + gap * i;
                if (i == mid || pressed == i) {
                    fill.setColor(pressed == i ? 0x40FFFFFF : 0x26FFFFFF);
                    c.drawCircle(btnX[i], cy, (i == mid ? 27 : 24) * u, fill);
                }
                android.graphics.drawable.Drawable bd = btns.get(i).getDrawable();
                int is = Math.round((i == mid ? 30 : 26) * u);
                bd.copyBounds(keep);
                bd.setBounds(Math.round(btnX[i] - is / 2f), Math.round(cy - is / 2f), Math.round(btnX[i] + is / 2f), Math.round(cy + is / 2f));
                bd.draw(c);
                bd.setBounds(keep);
            }
        } else {
            cxPlay = W / 2f;
            fill.setColor(0x26FFFFFF);
            c.drawCircle(cxPlay, cy, 27 * u, fill);
            fill.setColor(0xFFFFFFFF);
            if (playing()) {
                c.drawRoundRect(cxPlay - 9 * u, cy - 11 * u, cxPlay - 3 * u, cy + 11 * u, 2 * u, 2 * u, fill);
                c.drawRoundRect(cxPlay + 3 * u, cy - 11 * u, cxPlay + 9 * u, cy + 11 * u, 2 * u, 2 * u, fill);
            } else tri(c, cxPlay - 7 * u, cy, 20 * u, 22 * u, true);
        }
        // volume
        volY = cy + 50 * u;
        volL = 52 * u;
        volR = W - 52 * u;
        float vf = dragging == 4 ? dragFrac : volFrac();
        fill.setColor(0x33FFFFFF);
        c.drawRoundRect(volL, volY - 3 * u, volR, volY + 3 * u, 3 * u, 3 * u, fill);
        fill.setColor(0xE6FFFFFF);
        c.drawRoundRect(volL, volY - 3 * u, volL + (volR - volL) * vf, volY + 3 * u, 3 * u, 3 * u, fill);
        speaker(c, 30 * u, volY, u, false);
        speaker(c, W - 32 * u, volY, u, true);
        c.restore();
        if (dragging >= 0 || (playing() && Prefs.cometBar)) postInvalidateOnAnimation();   // cometa anima a cada quadro
        else if (playing()) postInvalidateDelayed(500);
    }

    static float xOf(View v) {
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        return l[0];
    }

    void tri(Canvas c, float x, float y, float w, float h, boolean right) {
        path.reset();
        if (right) {
            path.moveTo(x, y - h / 2f);
            path.lineTo(x + w, y);
            path.lineTo(x, y + h / 2f);
        } else {
            path.moveTo(x, y - h / 2f);
            path.lineTo(x - w, y);
            path.lineTo(x, y + h / 2f);
        }
        path.close();
        c.drawPath(path, fill);
    }

    void speaker(Canvas c, float x, float y, float u, boolean loud) {
        fill.setColor(0x99FFFFFF);
        c.drawRect(x - 7 * u, y - 3 * u, x - 3 * u, y + 3 * u, fill);
        path.reset();
        path.moveTo(x - 3 * u, y - 3 * u);
        path.lineTo(x + 2 * u, y - 7 * u);
        path.lineTo(x + 2 * u, y + 7 * u);
        path.lineTo(x - 3 * u, y + 3 * u);
        path.close();
        c.drawPath(path, fill);
        if (loud) {
            Paint st = new Paint(Paint.ANTI_ALIAS_FLAG);
            st.setStyle(Paint.Style.STROKE);
            st.setStrokeWidth(1.6f * u);
            st.setColor(0x99FFFFFF);
            r.set(x - 1 * u, y - 5 * u, x + 7 * u, y + 5 * u);
            c.drawArc(r, -60, 120, false, st);
            r.set(x - 3 * u, y - 9 * u, x + 11 * u, y + 9 * u);
            c.drawArc(r, -60, 120, false, st);
        }
    }

    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY(), u = d * k;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = -1;
                dragging = -1;
                if (Math.abs(y - cy) < 30 * u) {
                    if (btns.isEmpty()) { if (Math.abs(x - cxPlay) < 34 * u) pressed = 9; }
                    else for (int i = 0; i < Math.min(5, btns.size()); i++) if (Math.abs(x - btnX[i]) < 34 * u) pressed = i;
                } else if (Math.abs(y - barY) < 16 * u && x > barL - 12 * u && x < barR + 12 * u && duration() > 0) {
                    dragging = 3;
                    dragFrac = frac(x, barL, barR);
                } else if (Math.abs(y - volY) < 18 * u && x > volL - 24 * u && x < volR + 24 * u) {
                    dragging = 4;
                    dragFrac = frac(x, volL, volR);
                    setVol(dragFrac);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging == 3 || dragging == 4) {
                    dragFrac = frac(x, dragging == 3 ? barL : volL, dragging == 3 ? barR : volR);
                    if (dragging == 4) setVol(dragFrac);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                try {
                    if (pressed >= 0 && pressed < 5 && pressed < btns.size()) btns.get(pressed).performClick();   // o próprio botão do HyperOS
                    else if (ctl != null) {
                        MediaController.TransportControls tc = ctl.getTransportControls();
                        if (pressed == 9) { if (playing()) tc.pause(); else tc.play(); }
                        else if (dragging == 3) tc.seekTo((long) (duration() * dragFrac));
                        else if (dragging < 0 && y < artBottom + 30 * u && ctl.getSessionActivity() != null) ctl.getSessionActivity().send();
                    }
                } catch (Throwable t) {
                    if (logs++ < 8) ElasticCard.log("gaveta paisagem: player compacto toque: " + t);
                }
                pressed = -1;
                dragging = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                dragging = -1;
                invalidate();
                return true;
        }
        return true;
    }

    static float frac(float x, float l, float r) {
        return Math.max(0f, Math.min(1f, (x - l) / Math.max(1f, r - l)));
    }

    void setVol(float f) {
        try {
            AudioManager am = getContext().getSystemService(AudioManager.class);
            int mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(f * mx), 0);
        } catch (Throwable ignored) {
        }
    }
}
