package com.ilha.headsup;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/**
 * Desenho da "gota" da heads-up estilo iOS: fica no overlay da raiz da janela (por cima do cartão e
 * da barra de status) e, a cada quadro, pede ao ElasticState o pescoço de líquido entre o ícone do
 * app e o cartão. Usa a cor de fundo da heads-up do HyperOS (notification_heads_up_bg_color).
 */
final class DropOverlay extends Drawable {
    final ElasticState st;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final int baseAlpha;

    DropOverlay(ElasticState st, int color) {
        this.st = st;
        this.baseAlpha = (color >>> 24) & 0xff;
        paint.setColor(color | 0xff000000);
        paint.setStyle(Paint.Style.FILL);
    }

    public void draw(Canvas c) {
        try {
            st.drawDrop(c, path, paint, baseAlpha);
        } catch (Throwable t) {
        }
    }

    public void setAlpha(int alpha) {
    }

    public void setColorFilter(ColorFilter cf) {
    }

    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
