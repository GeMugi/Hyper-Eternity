package com.ilha.relogios;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import java.util.List;

/**
 * A capa da música tocando, para o vidro dos números quando o HyperMusicCover está no fundo.
 *
 * O HMC desenha a capa desfocada numa janela que o nosso shader não enxerga. Mas a imagem é a capa
 * da música: o SystemUI tem acesso às sessões de mídia, então pegamos a mesma capa e desfocamos aqui.
 * A refração do número passa a curvar as cores certas do fundo do HMC.
 */
final class Art {
    private Art() {}

    static volatile Bitmap cover;
    static volatile int version;
    static MediaSessionManager msm;
    static MediaController current;
    static boolean started;
    static int logs;

    static void log(String s) {
        if (logs++ < 8) com.ilha.relogios.xp.XposedBridge.log("[Relógios HOS4] vidro (capa): " + s);
    }

    static final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { take(m); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { pick(); }
    };

    static void start(final Context ctx) {
        if (started) return;
        started = true;
        try {
            msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            msm.addOnActiveSessionsChangedListener(new MediaSessionManager.OnActiveSessionsChangedListener() {
                public void onActiveSessionsChanged(List<MediaController> l) { pick(); }
            }, null, Fx.main);
            pick();
        } catch (Throwable t) {
            log("sem acesso às sessões de mídia: " + t);
        }
    }

    static void pick() {
        try {
            List<MediaController> l = msm.getActiveSessions(null);
            MediaController best = null;
            for (MediaController c : l) {
                PlaybackState st = c.getPlaybackState();
                if (best == null) best = c;
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
            }
            if (best != current) {
                if (current != null) try { current.unregisterCallback(cb); } catch (Throwable ignored) {}
                current = best;
                if (best != null) best.registerCallback(cb, Fx.main);
            }
            take(best == null ? null : best.getMetadata());
        } catch (Throwable t) {
            log("sessões: " + t);
        }
    }

    static Bitmap last;

    static void take(MediaMetadata m) {
        Bitmap b = null;
        if (m != null) {
            b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        if (b == last) return;
        last = b;
        if (b == null) { cover = null; version++; return; }
        final Bitmap src = b;
        new Thread(new Runnable() {
            public void run() {
                try {
                    // capa pequena e desfocada (o fundo do HMC é a capa bem desfocada e ampliada)
                    Bitmap sm = Bitmap.createScaledBitmap(src.getConfig() == Bitmap.Config.HARDWARE ? src.copy(Bitmap.Config.ARGB_8888, false) : src, 48, 48, true);
                    int[] px = new int[48 * 48];
                    sm.getPixels(px, 0, 48, 0, 0, 48, 48);
                    for (int k = 0; k < 3; k++) LiquidGlass.boxBlur(px, 48, 48, 3);
                    Bitmap out = Bitmap.createBitmap(px, 48, 48, Bitmap.Config.ARGB_8888);
                    cover = out;
                    version++;
                    log("capa da música pronta para o vidro");
                    Fx.main.post(new Runnable() {
                        public void run() {
                            for (android.view.View n : new java.util.ArrayList<android.view.View>(Fx.numbers)) n.invalidate();
                        }
                    });
                } catch (Throwable t) {
                    log("capa: " + t);
                }
            }
        }, "relogios-capa").start();
    }
}
