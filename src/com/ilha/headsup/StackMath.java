package com.ilha.headsup;

/**
 * Matemática do empilhamento do HyperOS 4 (NotificationStackingCalculator), portada 1:1.
 *
 * Coordenadas: c = borda inferior do cartão, B = fronteira (fim visível - h1 - h2),
 * E = B + h1 + h2 = fim visível. p = pivotY da linha. k = deslocamento extra (0 na gaveta).
 * Resultado: {deslocamentoY, escala, corteInferior, progresso} ou null (sem empilhar).
 */
final class StackMath {
    final float h1;
    final float h2;
    final float pad;

    StackMath(float h1, float h2, float pad) {
        this.h1 = h1;
        this.h2 = h2;
        this.pad = pad;
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Curva suave (Hermite) pelos pontos xs/ys, com velocidade contínua; achata no fim. */
    static float smooth(float x, float[] xs, float[] ys) {
        int n = xs.length;
        if (x <= xs[0]) return ys[0];
        if (x >= xs[n - 1]) return ys[n - 1];
        int i = 0;
        while (x > xs[i + 1]) i++;
        float h = xs[i + 1] - xs[i], t = (x - xs[i]) / h;
        float m0 = slope(i, xs, ys) * h, m1 = slope(i + 1, xs, ys) * h;
        float t2 = t * t, t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * m0 + (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * m1;
    }

    static float slope(int i, float[] xs, float[] ys) {
        int n = xs.length;
        if (i == n - 1) return 0f;   // chega parado na escala final
        float d1 = (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i]);
        if (i == 0) return d1;
        float d0 = (ys[i] - ys[i - 1]) / (xs[i] - xs[i - 1]);
        if (d0 * d1 <= 0f) return 0f;
        return (d0 + d1) / 2f;
    }

    static final float[] FX = {0f, 1f, 1.5f, 3f}, FY = {1f, 0.934f, 0.85f, 0.8f};
    static final float[] NX = {0f, 1f, 2f, 3f}, NY = {1f, 0.934f, 0.85f, 0.8f};

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    float[] normal(boolean first, float h, float c, float B, float p, float k) {
        float E = B + h1 + h2;
        if (first ? !(c > E) : !(c > B + k)) {
            return null;
        }
        if (first) {
            float prog = (c - E) / (h + pad);
            if (prog < 0f) prog = 0f;
            float s = smooth(prog, FX, FY);
            if (s < 0.8f) s = 0.8f;
            float dist = (E - c) + (h - p) * (1f - s);
            return new float[]{dist, s, 0f, prog};
        }
        float r1 = h1 + h2;
        float prog = (c - (B + k)) / (h + pad);
        if (prog < 0f) prog = 0f;
        float s = smooth(prog, NX, NY);
        if (s < 0.8f) s = 0.8f;
        float T;
        if (prog < 1f) {
            T = (h1 - k) * prog + B + k;
            if (T > B + r1) T = B + r1;
        } else if (prog < 2f) {
            float q = clamp(prog - 1f, 0f, 1f);
            float bump = (2f - q) * q;
            float tall = clamp((h - r1) / r1, 0f, 1f);
            float m = clamp(lerp(1f, 2f, tall) * bump, 0f, 1f);
            T = m * h2 + h1 + B;
            if (T > B + r1) T = B + r1;
        } else {
            T = B + r1;
        }
        float dist = (T - c) + (h - p) * (1f - s);
        return new float[]{dist, s, 0f, prog};
    }

    /** Cartões altos: empilha como se tivessem "limit" de altura e corta o resto embaixo. */
    float[] big(float h, float c, float limit, float p, float B, boolean first, float k) {
        float[] n = normal(first, limit, (c - h) + limit, B, p, k);
        float dist = n != null ? n[0] : 0f;
        float s = n != null ? n[1] : 1f;
        float prog = n != null ? n[3] : 0f;
        float over = ((s - 1f) * (h - p) + (c + dist)) - B - h1 - h2;
        if (over < 0f) over = 0f;
        if (n == null && over <= 0f) {
            return null;
        }
        return new float[]{dist, s, (int) (over / s), prog};
    }

    /** Escurecimento do fundo (HOS4 updateStackingDimming), 0..255. */
    static int dimAlpha(float prog) {
        if (prog <= 0f || prog > 4f) return 0;
        float a;
        if (prog <= 1f) {
            a = (float) Math.sqrt(prog) * 0.08f;
        } else if (prog <= 2f) {
            a = (float) Math.sqrt(prog - 1f) * 0.06f + 0.08f;
        } else if (prog <= 3f) {
            a = (float) Math.sqrt(prog - 2f) * 0.06f + 0.14f;
        } else {
            a = 0.2f;
        }
        int v = (int) (255f * a);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
