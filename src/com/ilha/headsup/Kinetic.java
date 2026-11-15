package com.ilha.headsup;

import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Estilo de letra "Cinético" (tipografia cinética dos lyric videos de edição).
 *
 * Cada frase é um bloco de palavras empilhadas numa fonte pesada (cada fileira esticada até a largura
 * do bloco; às vezes uma palavra deitada ao longo da pilha), escrito numa FACE DE UM CUBO. A frase
 * seguinte fica na face vizinha: a câmera gira em volta da quina (no eixo vertical, às vezes no
 * horizontal) até a face nova ficar de frente, e para um pouco antes - então as duas faces aparecem,
 * como uma quina de parede, a anterior escorçada em perspectiva ao lado. A face de lado escurece
 * conforme vira, o giro tem desfoque de movimento e o cubo é visto um pouco de cima.
 * As palavras entram uma a uma no tempo em que são cantadas; uma por frase sai na cor da capa.
 */
final class Kinetic {
    private Kinetic() {}

    static final long WORD_IN = 220, MOVE = 620, LEAD = 90;
    static final float REST = 30f;   // a câmera para antes da face nova: a anterior continua à vista

    static final class Word {
        String s;
        long at;
        float x, base, size, len;
        boolean rot, accent;
    }

    static final class Block {
        Word[] words;
        float W, H;
        long start, exit, next;
        boolean axisX;     // gira no eixo horizontal (a face nova vem de cima/baixo)
        int dir;           // lado do giro
    }

    static List<LyricVideo.Group> builtFor;
    static int builtW = -1;
    static ArrayList<Block> blocks = new ArrayList<>();
    static Typeface tf;
    static boolean tfTried;
    static final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    static final Camera cam = new Camera();
    static final Matrix m3 = new Matrix();

    static Typeface font() {
        if (!tfTried) {
            tfTried = true;
            tf = LyricVideo.ctx == null ? null : LyricVideo.moduleFont(LyricVideo.ctx, "lyric_kinetic.otf");
            if (tf == null) tf = Typeface.create(Typeface.DEFAULT, 900, false);
        }
        return tf;
    }

    static float capH(float size) {
        return size * 0.73f;
    }

    // ------------------------------------------------------------------ blocos

    static void build(List<LyricVideo.Group> gs, float d) {
        builtFor = gs;
        ArrayList<Block> out = new ArrayList<>();
        p.setTypeface(font());
        p.setLetterSpacing(-0.02f);
        float BW = 280 * d;
        for (LyricVideo.Group g : gs) {
            Random r = new Random(g.seed * 0x9E3779B97F4A7C15L + 11);
            r.nextLong();
            Block b = layout(g.lines[0], BW, r);
            if (b == null) continue;
            b.start = g.start;
            b.exit = g.exit;
            b.next = g.next;
            b.axisX = false;   // sempre quina vertical: o giro continua de onde a frase anterior parou
            b.dir = (out.size() & 1) == 0 ? 1 : -1;   // vai e volta na mesma quina: só as duas faces visíveis
            out.add(b);
        }
        blocks = out;
    }

    static Block layout(LyricVideo.Line l, float BW, Random r) {
        String text = l.text.trim().toUpperCase();
        if (text.isEmpty()) return null;
        String[] ws = text.split("\\s+");
        int n = ws.length;
        Word[] words = new Word[n];
        int ci = 0;
        long span = Math.max(600, l.end - l.start);
        String up = l.text.toUpperCase();
        for (int i = 0; i < n; i++) {
            Word w = new Word();
            w.s = ws[i];
            int idx = up.indexOf(ws[i], ci);
            if (idx < 0) idx = ci;
            ci = idx + ws[i].length();
            long t = l.start + span * i / Math.max(1, n) * 7 / 10;
            if (l.sylStart != null) {
                for (int s = 0; s < l.sylChar.length; s++) if (l.sylChar[s] > idx) { t = l.sylStart[s]; break; }
            }
            w.at = Math.max(l.start, t);
            words[i] = w;
        }
        int acc = n == 1 ? 0 : (r.nextFloat() < 0.5f ? n - 1 : r.nextInt(n));
        words[acc].accent = true;
        int rot = -1;
        if (n >= 3 && r.nextFloat() < 0.55f) {
            int cand = 1 + r.nextInt(n - 1);
            if (ws[cand].length() >= 2 && ws[cand].length() <= 8) rot = cand;
        }
        float gap = 0.06f * BW;
        float stripW = rot >= 0 ? 0.2f * BW : 0f;
        float stackW = BW - (rot >= 0 ? stripW + gap * 0.6f : 0f);
        ArrayList<int[]> rows = new ArrayList<>();
        int i = 0;
        while (i < n) {
            if (i == rot) { i++; continue; }
            int j = i + 1;
            while (j < n && j == rot) j++;
            boolean pair = j < n && ws[i].length() + ws[j].length() <= 8 && ws[i].length() <= 5 && ws[j].length() <= 5;
            rows.add(pair ? new int[]{i, j} : new int[]{i});
            i = pair ? j + 1 : i + 1;
        }
        float y = 0f, last = 0f;
        float maxSize = 0.62f * BW;
        for (int[] row : rows) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < row.length; k++) sb.append(k == 0 ? "" : " ").append(ws[row[k]]);
            p.setTextSize(100f);
            float size = Math.min(maxSize, 100f * stackW / Math.max(1f, p.measureText(sb.toString())));
            p.setTextSize(size);
            float x = (stackW - p.measureText(sb.toString())) / 2f;
            float base = y + capH(size);
            for (int k = 0; k < row.length; k++) {
                Word w = words[row[k]];
                w.size = size;
                w.x = x;
                w.base = base;
                x += p.measureText(w.s) + p.measureText(" ");
            }
            y = base + 0.1f * size;
            last = 0.1f * size;
        }
        float H = Math.max(y - last, capH(40));
        if (rot >= 0) {
            Word w = words[rot];
            w.rot = true;
            p.setTextSize(100f);
            float size = Math.min(100f * H / Math.max(1f, p.measureText(w.s)), stripW / 0.73f);
            p.setTextSize(size);
            w.size = size;
            w.len = p.measureText(w.s);
            w.x = BW;
            w.base = H - (H - w.len) / 2f;
        }
        Block b = new Block();
        b.words = words;
        b.W = BW;
        b.H = H;
        return b;
    }

    // ------------------------------------------------------------------ cubo

    static float ease(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x < 0.5f ? 4 * x * x * x : 1 - (float) Math.pow(-2 * x + 2, 3) / 2;
    }

    static final RenderNode node = new RenderNode("he-kinetic");
    static final Paint mask = new Paint();
    static int maskKey = -1;

    /** Giro da câmera em volta da quina: -90·dir (olhando a face anterior) até -REST·dir (de frente para a nova). */
    static float orbit(Block b, Block pv, long t) {
        float rest = -REST * b.dir;
        if (pv == null) return rest;
        float k = ease((t - (b.start - LEAD)) / (float) MOVE);
        // começa exatamente onde a anterior estava parada (a face dela a REST graus)
        float from = -90f * b.dir - REST * pv.dir;
        return from + (rest - from) * k;
    }

    static void frame(Canvas c, List<LyricVideo.Group> gs, long t, float d, int w) {
        if (gs != builtFor || w != builtW) {
            builtW = w;
            build(gs, d);
        }
        if (blocks.isEmpty()) return;
        int cur = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).start - LEAD <= t) cur = i;
            else break;
        }
        if (cur < 0) return;
        Block b = blocks.get(cur);
        float all = 1f;
        if (b.next - t > 1200 && t > b.exit + 500) all = 1f - Math.min(1f, (t - b.exit - 500) / 700f);
        if (all <= 0.01f) return;

        float areaW = LyricVideo.fx1 - LyricVideo.fx0, areaH = LyricVideo.areaBottom - LyricVideo.areaTop;
        float scx = (LyricVideo.fx0 + LyricVideo.fx1) / 2f, scy = (LyricVideo.areaTop + LyricVideo.areaBottom) / 2f;
        int accent = LyricVideo.albumColors(LyricVideo.ctx)[0];
        boolean first = cur == 0;
        Block pvb = first ? null : blocks.get(cur - 1);
        float th = orbit(b, pvb, t);

        float L = LyricVideo.fx0, T = LyricVideo.areaTop - 40 * d, R = LyricVideo.fx1, B = LyricVideo.areaBottom + 40 * d;
        if (!c.isHardwareAccelerated()) {
            int sv = c.save();
            c.clipRect(L, T, R, B);
            scene(c, cur, th, t, d, areaW, areaH, scx, scy, accent, all);
            c.restoreToCount(sv);
            return;
        }
        int nw = Math.max(1, Math.round(R - L)), nh = Math.max(1, Math.round(B - T));
        node.setPosition(0, 0, nw, nh);
        Canvas rc = node.beginRecording(nw, nh);
        try {
            rc.translate(-L, -T);
            scene(rc, cur, th, t, d, areaW, areaH, scx, scy, accent, all);
        } finally {
            node.endRecording();
        }
        // desfoque no sentido do giro (horizontal no eixo vertical e vice-versa)
        float spd = first ? 0f : Math.abs(th - orbit(b, pvb, t - 30));
        float bl = Math.min(14 * d, spd * 0.55f * d);
        node.setRenderEffect(bl > 0.8f ? RenderEffect.createBlurEffect(b.axisX ? 0.01f : bl, b.axisX ? bl : 0.01f, Shader.TileMode.DECAL) : null);
        int sv = c.saveLayer(L, T, R, B, null);
        c.translate(L, T);
        c.drawRenderNode(node);
        float fade = 46 * d;
        if (maskKey != nh) {
            maskKey = nh;
            mask.setShader(new LinearGradient(0, 0, 0, nh, new int[]{0x00000000, 0xFF000000, 0xFF000000, 0x00000000},
                    new float[]{0f, Math.min(0.45f, fade / nh), Math.max(0.55f, 1f - fade / nh), 1f}, Shader.TileMode.CLAMP));
            mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        }
        c.drawRect(0, 0, nw, nh, mask);
        c.restoreToCount(sv);
    }

    static void scene(Canvas c, int cur, float th, long t, float d, float areaW, float areaH, float scx, float scy, int accent, float alpha) {
        Block b = blocks.get(cur);
        Block pv = cur > 0 ? blocks.get(cur - 1) : null;
        // tamanho: a face nova cabe na faixa, mesmo vista de lado
        float z = Math.min(areaW * 0.74f / b.W, areaH * 0.66f / b.H);
        float life = Math.max(0f, Math.min(1f, (t - b.start) / (float) Math.max(1500, b.exit - b.start + 800)));
        z *= 1f + 0.04f * life;
        // na troca a câmera recua um pouco e inclina mais: o cubo aparece inteiro, em 3D
        float hop = pv == null ? 0f : (float) Math.sin(Math.max(0f, Math.min(1f, (t - (b.start - LEAD)) / (float) MOVE)) * Math.PI);
        z *= 1f - 0.16f * hop;
        // cubo: a quina entre as duas faces; a profundidade é o lado da face que gira
        float depth = b.axisX ? Math.max(b.H, pv == null ? b.H : pv.H) : b.W;
        float tilt = 9f + 3f * (float) Math.sin(t / 1400.0) + 9f * hop;
        // a anterior primeiro (fica atrás na quina), depois a atual
        if (pv != null) face(c, pv, -90f * b.dir - th, b.axisX, depth, tilt, z, t, d, accent, alpha, false, scx, scy);
        face(c, b, -th, b.axisX, depth, tilt, z, t, d, accent, alpha, true, scx, scy);
    }

    /** Desenha um bloco numa face do cubo, girada `phi` graus em relação à câmera. */
    static void face(Canvas c, Block b, float phi, boolean axisX, float depth, float tilt, float z, long t, float d,
                     int accent, float alpha, boolean live, float scx, float scy) {
        if (Math.abs(phi) >= 88f) return;   // de costas ou de quina: não aparece
        float light = 0.42f + 0.58f * (float) Math.cos(Math.toRadians(phi));
        int sv = c.save();
        c.translate(scx, scy);
        c.scale(z, z);
        cam.save();
        cam.setLocation(0, 0, -11);   // câmera mais perto: perspectiva mais forte
        cam.rotateX(axisX ? 0f : tilt);   // visto um pouco de cima
        cam.translate(0, 0, depth / 2f);
        if (axisX) cam.rotateX(phi);
        else cam.rotateY(phi);
        cam.translate(0, 0, -depth / 2f);
        cam.getMatrix(m3);
        cam.restore();
        c.concat(m3);
        c.translate(-b.W / 2f, -b.H / 2f);
        drawBlock(c, b, t, d, accent, alpha, light, live);
        c.restoreToCount(sv);
    }

    static int shade(int col, float light) {
        int r = Math.round(((col >> 16) & 0xFF) * light), g = Math.round(((col >> 8) & 0xFF) * light), bl = Math.round((col & 0xFF) * light);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    static void drawBlock(Canvas c, Block b, long t, float d, int accent, float alpha, float light, boolean live) {
        p.setTypeface(font());
        p.setLetterSpacing(-0.02f);
        for (Word w : b.words) {
            long into = t - w.at;
            if (into < 0) continue;
            float k = live ? Math.min(1f, into / (float) WORD_IN) : 1f;
            float e = 1f - (1f - k) * (1f - k) * (1f - k);
            float sc = 1f + 0.5f * (1f - e);
            float a = alpha * Math.min(1f, k * 2.5f);
            p.setTextSize(w.size);
            int col = shade(w.accent ? accent : 0xFFF4F4EE, light);
            int ws = c.save();
            if (w.rot) {
                c.translate(w.x, w.base);
                c.rotate(-90);
                c.scale(sc, sc, w.len / 2f, -capH(w.size) / 2f);
                text(c, w.s, 0, 0, col, a, d);
            } else {
                float tw = p.measureText(w.s);
                c.scale(sc, sc, w.x + tw / 2f, w.base - capH(w.size) / 2f);
                text(c, w.s, w.x, w.base, col, a, d);
            }
            c.restoreToCount(ws);
        }
    }

    static void text(Canvas c, String s, float x, float y, int col, float a, float d) {
        p.setColor(col);
        p.setAlpha(Math.round(255 * a));
        p.setShadowLayer(6 * d, 0, 2 * d, Math.round(0x5E * a) << 24);
        c.drawText(s, x, y, p);
    }
}
