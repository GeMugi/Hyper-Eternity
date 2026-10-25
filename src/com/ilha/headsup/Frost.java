package com.ilha.headsup;

import android.graphics.Bitmap;

/** Desfoque rápido de bitmap (fundo da página Sobre). */
final class Frost {
    private Frost() {}

    static void boxBlur(Bitmap b, int r) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], out = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 2; pass++) {
            boolean hor = pass == 0;
            int n = hor ? w : h, lines = hor ? h : w;
            for (int l = 0; l < lines; l++) {
                for (int i = 0; i < n; i++) {
                    int ra = 0, rr = 0, rg = 0, rb = 0, cnt = 0;
                    for (int k = -r; k <= r; k++) {
                        int j = Math.max(0, Math.min(n - 1, i + k));
                        int c = hor ? px[l * w + j] : px[j * w + l];
                        ra += c >>> 24;
                        rr += (c >> 16) & 255;
                        rg += (c >> 8) & 255;
                        rb += c & 255;
                        cnt++;
                    }
                    int v = ((ra / cnt) << 24) | ((rr / cnt) << 16) | ((rg / cnt) << 8) | (rb / cnt);
                    if (hor) out[l * w + i] = v;
                    else out[i * w + l] = v;
                }
            }
            int[] t = px;
            px = out;
            out = t;
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }
}
