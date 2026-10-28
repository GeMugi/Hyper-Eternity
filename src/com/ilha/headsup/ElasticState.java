package com.ilha.headsup;

import android.animation.ValueAnimator;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Animações da heads-up.
 *
 * ISLAND  — entrada "saindo da ilha" (estilo OriginOS 7), calibrada quadro a quadro pelo vídeo.
 * IOS_IN  — entrada estilo iOS 26: nasce do ícone do app na barra de status como uma gota: a bolha
 *           incha no ícone e escorre um pouco para baixo, ligada a ele por um "pescoço" de líquido
 *           (DropOverlay) que afina e se rompe; depois cresce, ancorada à esquerda, até o lugar do
 *           cartão (com mola), e o resto do pescoço volta para dentro do ícone.
 * IOS_OUT — saída estilo iOS 26: o cartão encolhe de volta para o ícone, desfocando; perto dele,
 *           o pescoço de líquido aparece e puxa a bolha para dentro do ícone.
 *
 * HyperOS 3: a linha da notificação recorta o próprio desenho (setClipBounds), então a
 * geometria vai numa matriz de animação da View (setAnimationMatrix), que move o recorte
 * junto. O dispatchDraw só cuida do formato de pílula e do fade.
 */
public final class ElasticState {

    static final int ISLAND = 0, IOS_IN = 1, IOS_OUT = 2;

    // ===== Ajustes finos: ilha =====
    static final long BASE_DURATION = 720L;     // ms com velocidade 100% e bounce 100%
    static final float START_WIDTH = 0.42f;     // largura inicial relativa ao cartão (largura da ilha)
    static final float POS_ZETA = 0.52f, POS_OMEGA = 15f;   // descida (passa ~15% e volta)
    static final float W_ZETA = 0.60f, W_OMEGA = 15f;       // largura (passa ~5% e volta)
    static final float H_ZETA = 0.75f, H_OMEGA = 32f;       // altura (quase sem passar)
    static final float FADE_SEC = 0.07f;        // tempo de aparecer
    static final float BLUR_SEC = 0.16f;        // duração do desfoque de movimento
    static final float BLUR_DP = 7f;            // raio máximo do desfoque
    static final float PILL_SEC = 0.26f;        // tempo em formato de pílula

    // ===== Ajustes finos: iOS =====
    static final float IOS_START_W_DP = 46f, IOS_START_H_DP = 12f;   // pílula inicial
    static final float IOS_POS_ZETA = 0.74f, IOS_POS_OMEGA = 17f;    // descida (passa pouco)
    static final float IOS_W_ZETA = 0.72f, IOS_W_OMEGA = 15.5f;      // largura (passa ~4% e volta)
    static final float IOS_X_ZETA = 0.86f;                           // centro horizontal (quase sem passar)
    static final float IOS_H_ZETA = 0.70f, IOS_H_OMEGA = 18f;        // altura
    static final float IOS_FADE_SEC = 0.06f;
    static final float IOS_BLUR_SEC = 0.30f, IOS_BLUR_DP = 12f;      // conteúdo desfocado enquanto cresce
    static final float IOS_PILL_SEC = 0.30f;
    static final long IOS_OUT_MS = 460L;                             // saída (com a gota)
    static final float IOS_END_W_DP = 34f, IOS_END_H_DP = 3f;        // linha final da saída
    static final long IOS_HOLD_MS = 1200L;                           // espera o HyperOS terminar
    static final long IOS_WAIT_MS = 160L;                            // espera o ícone do app aparecer na barra
    static final float IOS_ICON_GROW = 1.15f;                        // tamanho inicial/final em relação ao ícone
    static final float IOS_DRIP_SEC = 0.14f;
    static final float IOS_BAR_SEC = 0.12f;                          // barrinha abaixo do relógio antes de crescer
    static final float IOS_BAR_W_DP = 30f, IOS_BAR_H_DP = 5f;                         // gota se formando embaixo do ícone
    static final float IOS_DRIP_DY_DP = 9f;                          // vão entre o ícone e a gota quando ela solta
    static final float IOS_DRIP_FROM = 0.55f, IOS_DRIP_TO = 1.1f;    // tamanho da gota (fração do ícone)
    static final float IOS_DRIP_STRETCH = 1.2f;                      // a gota estica na vertical
    static final float IOS_NECK_MAX_DP = 26f;                        // distância em que o pescoço rompe
    static final float IOS_NECK_LIFE_SEC = 0.17f;                    // tempo máximo do pescoço (entrada)
    static final float IOS_NECK_BACK_SEC = 0.11f;                    // o resto volta para o ícone
    // =========================

    final int mode;
    final long duration;
    final long total;               // duração do animador (entrada iOS inclui a espera pelo ícone)
    final float posZ, posW, wZ, wW, hZ, hW, xZ, fadeSec, blurSec, pillSec;
    final float speedF, dripSec;

    // gota (DropOverlay): retângulo visível do cartão na tela, tempo e estado do pescoço
    float visCX = Float.NaN, visCY, visW, visH;
    float dripY;                    // quanto a gota já escorreu (px, tela)
    float outPs;                    // saída: quanto o cartão já encolheu (0..1)
    float lastT;                    // segundos desde o começo real
    float neckFrom = -1f;           // entrada: quando o pescoço apareceu
    boolean snapped;
    float snapAt, snapLen;
    DropOverlay drop;
    View dropHost;
    final int[] hostLoc = new int[2];

    ValueAnimator anim;
    final WeakReference row;
    final WeakReference content;
    boolean finished;
    boolean drawLogged;
    boolean holding;                // saída terminou: fica invisível até o HyperOS esconder a linha
    boolean released;               // o HyperOS já terminou a parte dele durante a saída

    // lidos pelo DrawHook
    float drawX = 1f;
    float drawY = 1f;
    float drawOffset;
    float drawOffsetX;
    float reveal = 1f;
    float pos = 1f;
    float posX = 1f;
    float clipRadius;

    final float density;
    final float islandCY;
    final float islandH;
    final float cornerR;
    float originX = Float.NaN, originY = Float.NaN;   // iOS: ponto abaixo do relógio (tela)
    float startCX = Float.NaN, startCY = Float.NaN;   // iOS saída: centro do cartão no início
    float startSX = 0.1f;                             // iOS entrada: largura inicial (fração)
    int anchorKind;                                   // 0 = estimado, 1 = relógio, 2 = ícone do app
    float iconSize;                                   // px do ícone do app na barra
    float t0 = 0f;                                    // iOS entrada: início real (s) depois da espera
    int sbHeight;
    boolean blurOn;
    boolean matrixOn;

    private static Field yField;
    private final int[] loc = new int[2];
    private final float[] pt = new float[2];
    private final Matrix matrix = new Matrix();

    /** bounce 100% = curva original; 0% = sem passar do ponto; 200% = o dobro de "pulo". */
    static float zeta(float base, float bounce) {
        float z = 1f - (1f - base) * bounce;
        return z < 0.12f ? 0.12f : (z > 0.97f ? 0.97f : z);
    }

    public ElasticState(View rowView, View contentView) {
        this(rowView, contentView, ISLAND);
    }

    public ElasticState(View rowView, View contentView, int mode) {
        Prefs.refresh();
        this.mode = mode;
        boolean ios = mode != ISLAND;
        // cada animação tem a própria velocidade e o próprio bounce
        float speed = (ios ? Prefs.huIosSpeed : Prefs.huSpeed) / 100f;
        float bounce = (ios ? Prefs.huIosBounce : Prefs.huBounce) / 100f;
        this.speedF = speed;
        this.dripSec = 0f;   // entrada como antes (sem barrinha, sem gota)
        this.posZ = zeta(ios ? IOS_POS_ZETA : POS_ZETA, bounce);
        this.wZ = zeta(ios ? IOS_W_ZETA : W_ZETA, bounce);
        this.hZ = zeta(ios ? IOS_H_ZETA : H_ZETA, bounce);
        this.xZ = zeta(IOS_X_ZETA, bounce);
        this.posW = (ios ? IOS_POS_OMEGA : POS_OMEGA) * speed;
        this.wW = (ios ? IOS_W_OMEGA : W_OMEGA) * speed;
        this.hW = (ios ? IOS_H_OMEGA : H_OMEGA) * speed;
        this.fadeSec = (ios ? IOS_FADE_SEC : FADE_SEC) / speed;
        this.blurSec = (ios ? IOS_BLUR_SEC : BLUR_SEC) / speed;
        this.pillSec = (ios ? IOS_PILL_SEC : PILL_SEC) / speed;
        if (mode == IOS_OUT) {
            this.duration = Math.min((long) (IOS_OUT_MS / speed), 900L);
        } else {
            // tempo para a mola mais lenta assentar (~1% de amplitude), limitado a 2,5 s
            float settle = Math.max(4.6f / (posZ * posW), Math.max(4.6f / (wZ * wW), 4.6f / (hZ * hW)));
            long ms = (long) (Math.max(BASE_DURATION / 1000f / speed, settle) * 1000f);
            this.duration = Math.min(ms, 2500L);
        }
        this.total = duration + (mode == IOS_IN ? (long) (dripSec * 1000f) : 0L);
        this.row = new WeakReference(rowView);
        this.content = new WeakReference(contentView);
        Resources res = rowView.getResources();
        this.density = res.getDisplayMetrics().density;

        int sb = 0;
        int id = res.getIdentifier("status_bar_height", "dimen", "android");
        if (id != 0) {
            try { sb = res.getDimensionPixelSize(id); } catch (Throwable ignored) { }
        }
        if (sb <= 0) sb = (int) (28f * density);
        this.sbHeight = sb;

        float cy = -1f;
        float ch = 0f;
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                WindowInsets wi = rowView.getRootWindowInsets();
                DisplayCutout dc = wi != null ? wi.getDisplayCutout() : null;
                if (dc != null) {
                    List<Rect> rects = dc.getBoundingRects();
                    int w = rowView.getRootView().getWidth();
                    float best = Float.MAX_VALUE;
                    for (Rect r : rects) {
                        if (r.top > sb) continue;
                        float d = Math.abs(r.exactCenterX() - w * 0.5f);
                        if (d < best) {
                            best = d;
                            cy = r.exactCenterY();
                            ch = r.height();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (cy < 0f) cy = sb * 0.5f;
        this.islandCY = cy;
        this.islandH = Math.max(Math.max(ch * 1.15f, sb * 0.8f), 24f * density);

        float cr = 0f;
        int cid = res.getIdentifier("notification_corner_radius", "dimen", "com.android.systemui");
        if (cid != 0) {
            try { cr = res.getDimension(cid); } catch (Throwable ignored) { }
        }
        if (cr <= 0f) cr = 20f * density;
        this.cornerR = cr;

        if (ios) {
            // ponto de origem: o ícone do app na barra de status; sem ele, logo abaixo do relógio;
            // sem relógio visível (ex.: tela de bloqueio), a posição em que ele costuma ficar
            if (!findIcon(rowView)) {
                float[] o = StatusClock.anchor(sb);
                if (o != null) {
                    originX = o[0];
                    originY = o[1];
                    anchorKind = 1;
                } else {
                    int w = rowView.getRootView().getWidth();
                    originX = Math.max(30f * density, w * 0.1f);
                    originY = sb * 0.92f;
                    anchorKind = 0;
                }
                // na entrada, o ícone costuma aparecer junto com a heads-up: espera um pouco por ele
                // (sem espera pelo ícone: a barrinha aparece na hora)
            }
        }
    }

    /** Procura o ícone deste app na barra de status; se achar, ele vira a origem. */
    boolean findIcon(View r) {
        if (true) return false;   // sem gota: nasce sempre como a barrinha abaixo do relógio
        float[] a = StatusClock.iconAnchor(r, sbHeight);
        if (a == null) return false;
        originX = a[0];
        originY = a[1];
        iconSize = a[2];
        anchorKind = 2;
        return true;
    }

    String anchorName() {
        return anchorKind == 2 ? "ícone do app" : (anchorKind == 1 ? "relógio" : "canto estimado");
    }

    /** Saída: guarda de onde o cartão parte (centro visível na tela agora). */
    void captureStart(View r) {
        float h = Refl.height(r);
        r.getLocationOnScreen(loc);
        startCX = loc[0] + r.getWidth() * 0.5f;
        startCY = loc[1] + h * 0.5f;
    }

    /** Chamado a cada quadro do ValueAnimator (f = 0..1, linear). */
    public void tick(float f) {
        if (finished) return;
        try {
            View r = (View) row.get();
            boolean alive = r != null && r.isAttachedToWindow()
                    && (mode == IOS_OUT || Boolean.TRUE.equals(Refl.call(r, "isPinned")));
            if (alive) {
                float tRaw = f * (total / 1000f);
                if (mode == IOS_IN && t0 < 0f) {
                    // esperando o ícone do app aparecer na barra de status (no máximo IOS_WAIT_MS)
                    if (findIcon(r) || tRaw >= IOS_WAIT_MS / 1000f || f >= 1f) {
                        t0 = tRaw;
                        if (ElasticCard.iosLogs++ < 3) ElasticCard.log("heads-up iOS: origem = " + anchorName() + " (x=" + (int) originX + " y=" + (int) originY + ")");
                    } else {
                        reveal = 0f;
                        drawX = 0.001f;
                        drawY = 0.001f;
                        clipRadius = 0f;
                        applyMatrix(r);
                        r.invalidate();
                        ElasticCard.invalidateChildren(r);
                        return;
                    }
                }
                float t = Math.max(0f, tRaw - t0);
                float h = Refl.height(r);
                float w = r.getWidth();
                View c = (View) content.get();
                float blurPx;
                if (mode == ISLAND) {
                    float sy0 = IslandSpring.clamp(h > 0f ? islandH / h : 0.3f, 0.18f, 0.6f);
                    pos = IslandSpring.step(t, posZ, posW);
                    posX = 1f;
                    drawX = START_WIDTH + (1f - START_WIDTH) * IslandSpring.step(t, wZ, wW);
                    drawY = sy0 + (1f - sy0) * IslandSpring.step(t, hZ, hW);
                    reveal = IslandSpring.smooth(t / fadeSec);
                    if (f >= 1f) {
                        pos = 1f; drawX = 1f; drawY = 1f; reveal = 1f;
                    }
                    // raio visto na tela: pílula no começo, depois o canto normal do cartão
                    clipRadius = t < pillSec
                            ? Math.min(h * drawY * 0.5f, Math.max(cornerR, h * drawY * 0.5f * (1f - t / pillSec)))
                            : 0f;
                    blurPx = BLUR_DP * density * (1f - IslandSpring.smooth(t / blurSec));
                } else if (mode == IOS_IN && t < dripSec) {
                    // a barrinha surge logo abaixo do relógio (como no iOS) e fica um instante
                    lastT = t;
                    drawX = IslandSpring.clamp(w > 0f ? IOS_BAR_W_DP * density / w : 0.05f, 0.005f, 0.5f);
                    drawY = IslandSpring.clamp(h > 0f ? IOS_BAR_H_DP * density / h : 0.03f, 0.005f, 0.5f);
                    startSX = drawX;
                    dripY = 0f;
                    pos = 0f;
                    posX = 0f;
                    reveal = IslandSpring.smooth(t / (dripSec * 0.6f));
                    clipRadius = h * drawY * 0.5f;
                    blurPx = IOS_BLUR_DP * density * 0.5f;
                } else if (mode == IOS_IN) {
                    float tAll = t;
                    lastT = tAll;
                    t = tAll - dripSec;   // a mola cresce a partir da barrinha
                    dripY = 0f;
                    float sx0 = IslandSpring.clamp(w > 0f ? IOS_START_W_DP * density / w : 0.1f, 0.01f, 0.5f);
                    float sy0 = IslandSpring.clamp(h > 0f ? IOS_START_H_DP * density / h : 0.1f, 0.02f, 0.6f);
                    float ws = IslandSpring.step(t, wZ, wW);
                    startSX = sx0;
                    pos = IslandSpring.step(t, posZ, posW);
                    posX = IslandSpring.step(t, xZ, wW);
                    drawX = sx0 + (1f - sx0) * ws;
                    drawY = sy0 + (1f - sy0) * IslandSpring.step(t, hZ, hW);
                    reveal = IslandSpring.smooth(tAll / fadeSec);
                    if (f >= 1f) {
                        pos = 1f; posX = 1f; drawX = 1f; drawY = 1f; reveal = 1f;
                    }
                    clipRadius = t < pillSec
                            ? Math.min(h * drawY * 0.5f, Math.max(cornerR, h * drawY * 0.5f * (1f - t / pillSec)))
                            : 0f;
                    blurPx = IOS_BLUR_DP * density * (1f - IslandSpring.smooth(t / blurSec));
                } else {
                    // saída: acelera no fim, como no iOS (quase parado no começo, some rápido)
                    lastT = f * (total / 1000f);
                    float u = f;
                    float p = (float) Math.pow(IslandSpring.smooth(u), 1.15);
                    if (anchorKind == 2) {
                        // inverso da entrada: o cartão encolhe até virar a gota embaixo do ícone
                        // (presa a ele pelo pescoço) e, no fim, a gota é sugada para dentro dele
                        float ps = IslandSpring.smooth(u / 0.72f);
                        float pu = IslandSpring.smooth((u - 0.72f) / 0.28f);
                        float base = dripBase();
                        float dw = base * IOS_DRIP_TO, dh = base * IOS_DRIP_TO * IOS_DRIP_STRETCH, fw = base * IOS_DRIP_FROM;
                        float cw = dw + (fw - dw) * pu, ch = dh + (fw - dh) * pu;
                        float sx1 = IslandSpring.clamp(w > 0f ? cw / w : 0.05f, 0.005f, 0.4f);
                        float sy1 = IslandSpring.clamp(h > 0f ? ch / h : 0.1f, 0.01f, 0.4f);
                        drawX = 1f + (sx1 - 1f) * ps;
                        drawY = 1f + (sy1 - 1f) * (float) Math.pow(ps, 0.9);
                        dripY = dripCenter(1f) + (dripCenter(0f) - dripCenter(1f)) * pu;
                        pos = ps;
                        posX = ps;
                        outPs = ps;
                        reveal = 1f - IslandSpring.smooth((u - 0.8f) / 0.2f);
                        float half = Math.min(h * drawY, w * drawX) * 0.5f;
                        clipRadius = ps < 0.02f ? 0f : Math.min(half, Math.max(cornerR, half * (0.4f + ps)));
                        blurPx = IOS_BLUR_DP * density * IslandSpring.smooth(ps / 0.7f);
                    } else {
                    float ew = IOS_END_W_DP * density;
                    float eh = IOS_END_H_DP * density;
                    dripY = 0f;
                    float sx1 = IslandSpring.clamp(w > 0f ? ew / w : 0.1f, 0.01f, 0.4f);
                    float sy1 = IslandSpring.clamp(h > 0f ? eh / h : 0.05f, 0.02f, 0.3f);
                    drawX = 1f + (sx1 - 1f) * p;
                    drawY = 1f + (sy1 - 1f) * (float) Math.pow(p, 0.9);
                    pos = p;
                    posX = p;
                    outPs = p;
                    reveal = 1f - IslandSpring.smooth((p - 0.6f) / 0.4f);
                    float half = h * drawY * 0.5f;
                    clipRadius = p < 0.02f ? 0f : Math.min(half, Math.max(cornerR, half * (0.4f + p)));
                    blurPx = IOS_BLUR_DP * density * IslandSpring.smooth(p / 0.7f);
                    }
                    if (f >= 1f) {
                        // fica escondido (bem pequeno e transparente) até o HyperOS tirar a linha
                        if (!holding) NotifBar.show();   // e fica a barrinha avisando que há notificações
                        holding = true;
                        drawX = 0.001f;
                        drawY = 0.001f;
                        reveal = 0f;
                        blurPx = 0f;
                    }
                }

                if (c != null && Build.VERSION.SDK_INT >= 31) {
                    if (blurPx > 0.6f) {
                        c.setRenderEffect(RenderEffect.createBlurEffect(blurPx * 0.6f, blurPx, Shader.TileMode.DECAL));
                        blurOn = true;
                    } else if (blurOn) {
                        c.setRenderEffect(Privacy.base(row.get(), c));
                        blurOn = false;
                    }
                }
                applyMatrix(r);
                if (c != null) c.invalidate();
                r.invalidate();
                ElasticCard.invalidateChildren(r);
                if (drop != null) drop.invalidateSelf();
                return;
            }
        } catch (Throwable t) {
            ElasticCard.log("ERRO no quadro: " + t);
        }
        if (f < 1f && mode != IOS_OUT) {
            ElasticCard.log("animação interrompida em " + (int) (f * duration) + " ms (a notificação deixou de estar fixada)");
        }
        finish();
        ValueAnimator a = anim;
        if (a != null) a.cancel();
    }

    /**
     * Monta a matriz: escala em torno do centro atual do cartão e desloca para que o centro
     * vá da origem (ilha ou relógio) até a posição final, sem depender da animação nativa.
     */
    void applyMatrix(View r) {
        if (finished || Build.VERSION.SDK_INT < 29) return;
        float h = Refl.height(r);
        float w = r.getWidth();
        if (h <= 0f || w <= 0f) return;
        // centro do cartão no espaço em que a matriz de animação é aplicada
        pt[0] = w * 0.5f;
        pt[1] = h * 0.5f;
        r.getMatrix().mapPoints(pt);
        if (mode == ISLAND) {
            drawOffsetX = 0f;
            offset(r, h);
        } else {
            offsetIos(r, h, w);
        }
        matrix.reset();
        matrix.setScale(drawX, drawY, pt[0], pt[1]);
        matrix.postTranslate(drawOffsetX, drawOffset);
        r.setAnimationMatrix(matrix);
        matrixOn = true;
    }

    float offset(View r, float h) {
        r.getLocationInWindow(loc);
        float curCY = loc[1] + h * 0.5f;
        float targetCY = targetCY(r, curCY, h);
        float drawn = islandCY + (targetCY - islandCY) * pos;
        drawOffset = drawn - curCY;
        return drawOffset;
    }

    /** iOS: o centro vai do ponto abaixo do relógio até o lugar do cartão (ou volta, na saída). */
    void offsetIos(View r, float h, float w) {
        r.getLocationOnScreen(loc);
        float curCX = loc[0] + w * 0.5f;
        float curCY = loc[1] + h * 0.5f;
        float fromX, fromY, toX, toY, kx, ky;
        if (mode == IOS_OUT) {
            if (Float.isNaN(startCX)) {
                startCX = curCX;
                startCY = curCY;
            }
            fromX = startCX;
            fromY = startCY;
            toX = originX;
            toY = originY + dripY;
            kx = posX;
            ky = pos;
        } else {
            // entrada: a borda esquerda vai de baixo do relógio até a do cartão; a largura cresce
            // para a direita (com o pulo da mola), como no iOS
            float leftFrom = originX - w * startSX * 0.5f;
            float leftTo = curCX - w * 0.5f;
            float left = leftFrom + (leftTo - leftFrom) * posX;
            drawOffsetX = left + w * drawX * 0.5f - curCX;
            float toYc = targetCY(r, curCY, h);
            float oy = originY + dripY;
            drawOffset = oy + (toYc - oy) * pos - curCY;
            setVis(curCX, curCY, w, h);
            return;
        }
        drawOffsetX = fromX + (toX - fromX) * kx - curCX;
        drawOffset = fromY + (toY - fromY) * ky - curCY;
        setVis(curCX, curCY, w, h);
    }

    /** Tamanho de referência da gota: o ícone do app (ou 16 dp sem ele). */
    float dripBase() {
        return anchorKind == 2 && iconSize > 0f ? iconSize : 16f * density;
    }

    /** Centro da gota abaixo do centro do ícone: e=0 colada embaixo dele, e=1 solta. */
    float dripCenter(float e) {
        float rI = dripBase() * 0.5f;
        float h0 = dripBase() * IOS_DRIP_FROM, h1 = dripBase() * IOS_DRIP_TO * IOS_DRIP_STRETCH;
        return e <= 0f ? rI * 0.9f : rI + IOS_DRIP_DY_DP * density + h1 * 0.5f;
    }

    void setVis(float curCX, float curCY, float w, float h) {
        visCX = curCX + drawOffsetX;
        visCY = curCY + drawOffset;
        visW = w * drawX;
        visH = h * drawY;
    }

    // ------------------------------------------------------------------ gota

    /** Liga o desenho do pescoço por cima de tudo na janela da heads-up (overlay da raiz). */
    void attachDrop(View r) {
        try {
            View root = r.getRootView();
            if (!(root instanceof android.view.ViewGroup) || root.getWidth() <= 0) return;
            int color = 0xe6171717;
            try {
                int id = r.getResources().getIdentifier("notification_heads_up_bg_color", "color", "com.android.systemui");
                if (id != 0) color = r.getContext().getColor(id);
            } catch (Throwable ignored) {
            }
            DropOverlay d = new DropOverlay(this, color);
            d.setBounds(0, 0, root.getWidth(), root.getHeight());
            ((android.view.ViewGroup) root).getOverlay().add(d);
            root.getLocationOnScreen(hostLoc);
            drop = d;
            dropHost = root;
        } catch (Throwable t) {
            ElasticCard.log("gota indisponível: " + t);
        }
    }

    void detachDrop() {
        try {
            if (drop != null && dropHost instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) dropHost).getOverlay().remove(drop);
            }
        } catch (Throwable ignored) {
        }
        drop = null;
        dropHost = null;
    }

    /**
     * O pescoço de líquido entre o ícone e o cartão, desenhado em coordenadas da raiz.
     * Entrada: aparece quando a bolha descola do ícone, afina com a distância e com o tempo e
     * rompe; o resto encolhe de volta para dentro do ícone. Saída: na segunda metade, quando o
     * cartão chega perto, o pescoço se forma e engrossa até a bolha entrar no ícone.
     */
    void drawDrop(android.graphics.Canvas c, android.graphics.Path path, android.graphics.Paint paint, int baseAlpha) {
        if (finished || Float.isNaN(originX) || Float.isNaN(visCX) || visW <= 0f || visH <= 0f) return;
        float ix = originX - hostLoc[0];
        float iy = originY - hostLoc[1];
        float rI = (anchorKind == 2 && iconSize > 0f ? iconSize : 14f * density) * 0.5f;
        float left = visCX - visW * 0.5f - hostLoc[0];
        float right = visCX + visW * 0.5f - hostLoc[0];
        float top = visCY - visH * 0.5f - hostLoc[1];
        float topY = iy + rI * 0.9f;              // a gota pende da parte de baixo do ícone
        float gap = top - topY;
        float neckMax = IOS_NECK_MAX_DP * density;
        float a = rI * 0.6f;                      // meia largura junto ao ícone
        float alpha = reveal;
        float thin;                               // 0 = grosso, 1 = rompido
        if (mode == IOS_IN) {
            if (!snapped) {
                if (gap <= 1f) return;            // a bolha ainda cobre o ícone
                if (neckFrom < 0f) neckFrom = lastT;
                float life = IOS_NECK_LIFE_SEC / speedF;
                thin = Math.max(gap / neckMax, (lastT - neckFrom) / life);
                if (thin >= 1f) {
                    snapped = true;
                    snapAt = lastT;
                    snapLen = Math.min(gap * 0.45f, 12f * density);
                } else {
                    neck(c, path, paint, ix, topY, a, left, right, top, gap, thin, alpha, baseAlpha);
                    return;
                }
            }
            // o resto do pescoço volta para dentro do ícone
            float k = (lastT - snapAt) / (IOS_NECK_BACK_SEC / speedF);
            if (k >= 1f) return;
            float e = IslandSpring.smooth(k);
            stub(c, path, paint, ix, topY, a * (1f - 0.45f * e), snapLen * (1f - e), alpha, baseAlpha);
        } else if (mode == IOS_OUT) {
            if (holding || gap <= 1f) return;
            float on = IslandSpring.smooth((outPs - 0.3f) / 0.3f);
            if (on <= 0f) return;
            if (gap < neckMax) {
                thin = 1f - (1f - gap / neckMax) * on;
                neck(c, path, paint, ix, topY, a, left, right, top, gap, thin, alpha, baseAlpha);
            } else if (gap < neckMax * 1.7f) {
                // antes de encostar, uma gotinha estica do ícone em direção ao cartão
                float reach = (neckMax * 1.7f - gap) / (neckMax * 0.7f);
                stub(c, path, paint, ix, topY, a * 0.8f, 12f * density * reach * on, alpha, baseAlpha);
            }
        }
    }

    /** Ponte entre o ícone e a borda de cima do cartão, com a cintura afinando (thin 0..1). */
    void neck(android.graphics.Canvas c, android.graphics.Path path, android.graphics.Paint paint, float ix, float topY, float a,
              float left, float right, float top, float gap, float thin, float alpha, int baseAlpha) {
        float half = (right - left) * 0.5f;
        float b = Math.min(a * 1.6f, half * 0.6f);                     // meia largura no cartão
        float corner = Math.min(half, Math.max(clipRadius, cornerR * Math.min(drawX, drawY)));
        float lo = left + Math.min(corner * 0.7f, half - b) + b, hi = right - Math.min(corner * 0.7f, half - b) - b;
        float bx = lo <= hi ? IslandSpring.clamp(ix, lo, hi) : (left + right) * 0.5f;
        float bottom = top + Math.min(3f * density, visH * 0.2f);      // entra um pouco no cartão
        float midY = topY + gap * 0.5f;
        float mx = (ix + bx) * 0.5f;
        float wm = a * 0.85f * (float) Math.pow(1f - IslandSpring.clamp(thin, 0f, 1f), 0.8);
        float cap = a * 0.4f;
        path.reset();
        path.moveTo(ix - a, topY);
        path.cubicTo(ix - a, topY + (midY - topY) * 0.55f, mx - wm, midY - (midY - topY) * 0.35f, mx - wm, midY);
        path.cubicTo(mx - wm, midY + (bottom - midY) * 0.45f, bx - b, bottom - (bottom - midY) * 0.7f, bx - b, bottom);
        path.lineTo(bx + b, bottom);
        path.cubicTo(bx + b, bottom - (bottom - midY) * 0.7f, mx + wm, midY + (bottom - midY) * 0.45f, mx + wm, midY);
        path.cubicTo(mx + wm, midY - (midY - topY) * 0.35f, ix + a, topY + (midY - topY) * 0.55f, ix + a, topY);
        path.cubicTo(ix + a, topY - cap, ix - a, topY - cap, ix - a, topY);
        path.close();
        paint.setAlpha((int) (baseAlpha * IslandSpring.clamp(alpha, 0f, 1f)));
        c.drawPath(path, paint);
    }

    /** Gotinha pendurada no ícone (len = comprimento para baixo). */
    void stub(android.graphics.Canvas c, android.graphics.Path path, android.graphics.Paint paint, float ix, float topY, float a,
              float len, float alpha, int baseAlpha) {
        if (len < 0.5f || a < 0.5f) return;
        float cap = a * 0.4f;
        path.reset();
        path.moveTo(ix - a, topY);
        path.cubicTo(ix - a, topY + len * 0.6f, ix - a * 0.45f, topY + len, ix, topY + len);
        path.cubicTo(ix + a * 0.45f, topY + len, ix + a, topY + len * 0.6f, ix + a, topY);
        path.cubicTo(ix + a, topY - cap, ix - a, topY - cap, ix - a, topY);
        path.close();
        paint.setAlpha((int) (baseAlpha * IslandSpring.clamp(alpha, 0f, 1f)));
        c.drawPath(path, paint);
    }

    /** Centro final (y) do cartão: a posição-alvo do HyperOS, se ela estiver no topo da tela. */
    float targetCY(View r, float curCY, float h) {
        float targetCY = curCY;
        Float yt = targetTranslation(r);
        if (yt != null) {
            float cand = curCY - r.getTranslationY() + yt.floatValue();
            float top = cand - h * 0.5f;
            // só confia no alvo se ele estiver no topo da tela (heads-up fixado)
            if (top > -h * 0.5f && top < 220f * density) targetCY = cand;
        }
        return targetCY;
    }

    private static Float targetTranslation(View r) {
        try {
            Object vs = Refl.call(r, "getViewState");
            if (vs == null) return null;
            Field fy = yField;
            if (fy == null) {
                fy = Refl.field(vs.getClass(), "mYTranslation");
                if (fy == null) fy = Refl.field(vs.getClass(), "yTranslation");
                yField = fy;
            }
            if (fy != null) return Float.valueOf(fy.getFloat(vs));
        } catch (Throwable ignored) {
        }
        return null;
    }

    public void finish() {
        if (finished) return;
        finished = true;
        detachDrop();
        try {
            View c = (View) content.get();
            if (c != null) {
                if (blurOn && Build.VERSION.SDK_INT >= 31) {
                    c.setRenderEffect(Privacy.base(row.get(), c));
                    blurOn = false;
                }
                c.invalidate();
            }
            Object r = row.get();
            if (r != null) {
                if (matrixOn && Build.VERSION.SDK_INT >= 29) {
                    ((View) r).setAnimationMatrix(null);
                    matrixOn = false;
                }
                WeakHashMap map = ElasticCard.states;
                if (map.get(r) == this) {
                    map.remove(r);
                }
                ((View) r).invalidate();
                ElasticCard.invalidateChildren(r);
            }
        } catch (Throwable ignored) {
        }
    }
}
