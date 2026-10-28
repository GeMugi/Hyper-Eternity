package com.ilha.headsup;

import android.graphics.Canvas;
import android.graphics.Path;
import android.view.View;
import com.ilha.headsup.xp.XC_MethodHook;

/**
 * dispatchDraw da linha: atualiza a matriz com a posição deste quadro, recorta em formato
 * de pílula (no espaço local, compensando a escala) e aplica o fade inicial.
 */
public final class DrawHook extends XC_MethodHook {
    private final Path path = new Path();
    static final int TAG_SIDE = 0x7e1e0031;
    static final int[] loc = new int[2];
    static int sideLogs;

    /** iOS em paisagem: a heads-up fica do lado do relógio (esquerda), não no meio da tela. */
    static void side(View v) {
        try {
            boolean want = false;
            float tx = 0f;
            if (Prefs.huStyle == 1 && v.getWidth() > 0) {
                View root = v.getRootView();
                boolean land = root.getWidth() > root.getHeight();
                if (land && Boolean.TRUE.equals(Refl.call(v, "isPinned"))) {
                    View clock = StatusClock.leftClock();
                    float left = clock != null ? -1 : 16 * v.getResources().getDisplayMetrics().density;
                    if (clock != null) { clock.getLocationOnScreen(loc); left = loc[0]; }
                    v.getLocationOnScreen(loc);
                    float base = loc[0] - v.getTranslationX();
                    tx = left - base;
                    want = tx < 0f;   // só puxa para a esquerda
                }
            }
            Object tag = v.getTag(TAG_SIDE);
            if (want) {
                if (Math.abs(v.getTranslationX() - tx) > 1f) v.setTranslationX(tx);
                if (tag == null) { v.setTag(TAG_SIDE, Boolean.TRUE); if (sideLogs++ < 2) ElasticCard.log("heads-up iOS em paisagem: do lado do relógio"); }
            } else if (tag != null) {
                v.setTag(TAG_SIDE, null);
                v.setTranslationX(0f);
            }
        } catch (Throwable ignored) {
        }
    }

    protected void beforeHookedMethod(MethodHookParam p) {
        Canvas canvas = null;
        int save = -1;
        try {
            View view = (View) p.thisObject;
            side(view);
            ElasticState st = (ElasticState) ElasticCard.states.get(view);
            if (st == null || st.finished) return;
            float w = view.getWidth();
            float h = Refl.height(view);
            if (w <= 0f || h <= 0f) return;
            canvas = (Canvas) p.args[0];
            st.applyMatrix(view);
            save = canvas.save();
            float cr = st.clipRadius;
            float sx = st.drawX;
            float sy = st.drawY;
            if (cr > 0f && sx > 0.01f && sy > 0.01f) {
                path.reset();
                path.addRoundRect(0f, 0f, w, h, cr / sx, cr / sy, Path.Direction.CW);
                canvas.clipPath(path);
            }
            if (st.reveal < 0.999f) {
                canvas.saveLayerAlpha(0f, 0f, w, h, (int) (st.reveal * 255f));
            }
            p.setObjectExtra("ilha_save", Integer.valueOf(save));
            if (!st.drawLogged) {
                st.drawLogged = true;
                ElasticCard.log("desenho aplicado: deslocamento=" + (int) st.drawOffset + "px escala=" + sx + "x" + sy);
            }
        } catch (Throwable t) {
            if (canvas != null && save >= 0) {
                try { canvas.restoreToCount(save); } catch (Throwable ignored) { }
            }
            ElasticCard.log("ERRO no desenho: " + t);
        }
    }

    protected void afterHookedMethod(MethodHookParam p) {
        try {
            Object s = p.getObjectExtra("ilha_save");
            if (s instanceof Integer) {
                ((Canvas) p.args[0]).restoreToCount(((Integer) s).intValue());
            }
        } catch (Throwable ignored) {
        }
    }
}
