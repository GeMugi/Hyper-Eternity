package com.ilha.headsup;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewParent;
import android.widget.SeekBar;
import android.widget.TextView;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.reflect.Method;

/**
 * Player da gaveta: o trilho da barra de progresso e os números do tempo clareiam o cartão
 * (somam luz à cor do vidro, numa camada própria do cartão), como o brilho das notificações. A parte já
 * tocada da barra continua sólida.
 */
final class MediaCut {
    private MediaCut() {}

    static final int TAG = 0x7e1a7011;
    static int logs;

    static void install(ClassLoader cl) {
        try {
            Class c = Class.forName("com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl", false, cl);
            Method attach = null;
            for (Method m : c.getDeclaredMethods()) if ("attach".equals(m.getName()) && m.getParameterTypes().length == 1) attach = m;
            if (attach == null) {
                ElasticCard.log("barra vazada: não encontrei o player");
                return;
            }
            XposedBridge.hookMethod(attach, new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Prefs.refresh();
                        // sempre: assim ligar a opção depois vale no player que já está aberto
                        apply(p.args[0], p.thisObject);
                    } catch (Throwable t) {
                        if (logs++ < 4) ElasticCard.log("barra vazada: erro " + t);
                    }
                }
            });
            ElasticCard.log("barra vazada: instalado");
        } catch (Throwable t) {
            ElasticCard.log("barra vazada indisponível " + t);
        }
    }

    static void apply(Object holder, Object ctl) {
        Object sb = Refl.get(holder, "seekBar");
        if (!(sb instanceof SeekBar)) {
            if (logs++ < 4) ElasticCard.log("barra vazada: sem seekBar no player");
            return;
        }
        SeekBar bar = (SeekBar) sb;
        if (bar.getTag(TAG) != null) return;
        bar.setTag(TAG, Boolean.TRUE);
        float d = bar.getResources().getDisplayMetrics().density;
        // o cartão numa camada própria (assim ficou o desfoque na 5.8.7): a barra e os tempos
        // são desenhados em "diferença" com o cartão, ou seja, mostram o fundo do player
        // invertido. Sobre vidro claro ficam escuros, sobre vidro escuro ficam claros, e
        // acompanham a cor do cartão: sempre contrastados.
        View card = bar;
        ViewParent vp = bar.getParent();
        while (vp instanceof View) {
            card = (View) vp;
            if (card.getBackground() != null) break;
            vp = vp.getParent();
        }
        // sem camada própria no cartão: a camada refazia o desfoque do vidro a cada quadro (lag ao puxar a gaveta); a mistura age direto sobre o que já foi desenhado
        int n = 0;
        if (Prefs.mediaCut) n += wrap(bar, d);
        hookDraw(bar.getClass());
        Comet cm = new Comet(bar, d);
        Object al = Refl.get(holder, "albumImageView");
        if (al instanceof android.widget.ImageView) cm.album = (android.widget.ImageView) al;
        cm.ctl = ctl;
        bar.setTag(TAG_COMET, cm);
        final java.util.ArrayList<TextView> texts = new java.util.ArrayList<>();
        for (String f : new String[]{"elapsedTimeView", "totalTimeView", "scrubbingElapsedTimeView", "scrubbingTotalTimeView", "elapsedTime", "totalTime"}) {
            Object tv = Refl.get(holder, f);
            if (tv instanceof TextView) {
                texts.add((TextView) tv);
                n++;
            }
        }
        // vibrance também no artista e nos botões de pular e voltar
        final Object artistO = Refl.get(holder, "artistText");
        final TextView artist = artistO instanceof TextView ? (TextView) artistO : null;
        final android.content.res.ColorStateList artistOrig = artist != null ? artist.getTextColors() : null;
        final java.util.ArrayList<android.widget.ImageView> skips = new java.util.ArrayList<>();
        for (String f : new String[]{"action0", "action1", "action2", "action3", "action4"}) {
            Object o = Refl.get(holder, f);
            if (!(o instanceof android.widget.ImageView)) continue;
            android.widget.ImageView ib = (android.widget.ImageView) o;
            String nm = "";
            try {
                if (ib.getId() != View.NO_ID) nm = ib.getResources().getResourceEntryName(ib.getId()).toLowerCase();
            } catch (Throwable ignored) {
            }
            if (nm.contains("prev") || nm.contains("next")) skips.add(ib);
        }
        if (logs < 12) ElasticCard.log("vibrance extra: artista=" + (artist != null) + " botões de pular=" + skips.size());
        final Paint skipPaint = new Paint();
        // a cada quadro confere o tema e as intensidades: troca sozinho entre claro e escuro
        bar.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            int lastKey = Integer.MIN_VALUE;
            boolean extraOn;

            public boolean onPreDraw() {
                refresh();
                extra();
                // o HyperOS troca a barra ao mudar o tema: embrulha de novo quando trocar
                if (Prefs.mediaCut) wrap(bar, bar.getResources().getDisplayMetrics().density);
                boolean night = night(bar);
                int key = (night ? 1 : 0) | (Prefs.mvText << 1) | (Prefs.mvTrack << 8) | (Prefs.mvPlayed << 15) | (Prefs.mediaCut ? 1 << 23 : 0) | (Prefs.cometBar ? 1 << 24 : 0);
                if (key == lastKey) return true;
                lastKey = key;
                if (logs < 12) ElasticCard.log("barra: iOS=" + Prefs.mediaCut + " cometa=" + Prefs.cometBar + " barra=" + bar.getProgressDrawable().getClass().getSimpleName() + " " + bar.getWidth() + "x" + bar.getHeight());
                logs++;
                for (TextView t : texts) {
                    if (Prefs.mediaCut) {
                        t.setAlpha(1f);
                        t.setTextColor(color(night, Prefs.mvText));
                        t.getPaint().setBlendMode(mode(night));
                    } else {
                        t.getPaint().setBlendMode(null);
                    }
                    t.invalidate();
                }
                bar.invalidate();
                return true;
            }

            /** Artista e botões de pular/voltar com vibrance (o HyperOS pinta de novo ao trocar de faixa). */
            void extra() {
                boolean night = night(bar);
                if (Prefs.mediaVibPlus) {
                    int col = color(night, Prefs.mvExtra);
                    android.graphics.BlendMode bm = mode(night);
                    if (artist != null) {
                        if (artist.getCurrentTextColor() != col) artist.setTextColor(col);
                        if (artist.getPaint().getBlendMode() != bm) {
                            artist.getPaint().setBlendMode(bm);
                            artist.invalidate();
                        }
                    }
                    skipPaint.setBlendMode(bm);
                    for (android.widget.ImageView ib : skips) {
                        android.content.res.ColorStateList tl = ib.getImageTintList();
                        if (tl == null || tl.getDefaultColor() != col) ib.setImageTintList(android.content.res.ColorStateList.valueOf(col));
                        if (ib.getLayerType() != View.LAYER_TYPE_HARDWARE || !extraOn) ib.setLayerType(View.LAYER_TYPE_HARDWARE, skipPaint);
                    }
                    extraOn = true;
                } else if (extraOn) {
                    extraOn = false;
                    if (artist != null) {
                        artist.getPaint().setBlendMode(null);
                        if (artistOrig != null) artist.setTextColor(artistOrig);
                    }
                    for (android.widget.ImageView ib : skips) {
                        ib.setImageTintList(null);
                        ib.setLayerType(View.LAYER_TYPE_NONE, null);
                    }
                }
            }
        });
        bar.invalidate();
        if (logs++ < 4) ElasticCard.log("barra vazada: ligada (" + n + " partes)");
    }

    /** Troca a barra inteira pela nossa (o HyperOS usa uma barra própria, não em camadas). */
    static int wrap(SeekBar bar, float d) {
        Drawable pd = bar.getProgressDrawable();
        if (pd instanceof Vib) return 0;
        if (logs < 6) ElasticCard.log("barra vazada: barra do sistema " + (pd == null ? null : pd.getClass().getName()));
        Rect r = pd != null ? pd.copyBounds() : null;
        Vib v = new Vib(bar, d);
        bar.setProgressDrawable(v);
        if (r != null && !r.isEmpty()) v.setBounds(r);
        int max = bar.getMax() - bar.getMin();
        v.setLevel(max > 0 ? Math.round(10000f * (bar.getProgress() - bar.getMin()) / max) : 0);
        return 1;
    }

    /** Barra no estilo iOS: trilho e parte tocada desenhados com a mistura "vibrancy". */
    static final class Vib extends Drawable {
        final View host;
        final float d;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Vib(View host, float d) {
            this.host = host;
            this.d = d;
        }

        public void draw(Canvas c) {
            Rect b = getBounds();
            float h = Math.min(b.height(), 4f * d);
            float cy = b.exactCenterY();
            RectF all = new RectF(b.left, cy - h / 2f, b.right, cy + h / 2f);
            boolean night = night(host);
            boolean on = Prefs.mediaCut;
            p.setBlendMode(on ? mode(night) : null);
            p.setColor(on ? color(night, Prefs.mvTrack) : 0x4DFFFFFF);
            c.drawRoundRect(all, h / 2f, h / 2f, p);
            float w = b.width() * getLevel() / 10000f;
            if (w > 0f) {
                p.setColor(on ? color(night, Prefs.mvPlayed) : 0xFFFFFFFF);
                c.drawRoundRect(new RectF(b.left, all.top, b.left + Math.max(w, h), all.bottom), h / 2f, h / 2f, p);
            }
        }

        protected boolean onLevelChange(int level) {
            invalidateSelf();
            return true;
        }

        public void setAlpha(int a) {
        }

        public void setColorFilter(ColorFilter cf) {
        }

        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    static final int TAG_COMET = 0x7e1a7012;
    static final java.util.HashSet<Class> hooked = new java.util.HashSet<>();

    /**
     * A barra do player do HyperOS (HyperProgressSeekBar) desenha tudo no próprio onDraw (pílula ou
     * shader), sem usar a progressDrawable. O cometa é desenhado por cima, depois do onDraw dela.
     */
    static void hookDraw(Class c) {
        if (hooked.contains(c)) return;
        hooked.add(c);
        Method m = null;
        for (Class k = c; k != null && k != SeekBar.class && m == null; k = k.getSuperclass()) {
            try {
                m = k.getDeclaredMethod("onDraw", Canvas.class);
            } catch (Throwable ignored) {
            }
        }
        if (m == null) {
            try {
                m = android.widget.ProgressBar.class.getDeclaredMethod("onDraw", Canvas.class);
            } catch (Throwable t) {
                ElasticCard.log("cometa: sem onDraw na barra " + c.getName());
                return;
            }
        }
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Object v = p.thisObject;
                    if (!Prefs.cometBar || !(v instanceof SeekBar)) return;
                    Object cm = ((SeekBar) v).getTag(TAG_COMET);
                    if (!(cm instanceof Comet)) return;
                    Comet co = (Comet) cm;
                    // tocando, a barra inteira é nossa; pausada, volta a barra do sistema (com uma
                    // passagem suave entre as duas)
                    float a = co.step();
                    co.overlay = 0f;
                    if (a >= 0.99f) {
                        if (co.draw((Canvas) p.args[0])) p.setResult(null);
                    } else {
                        co.overlay = a;
                        if (a <= 0.01f) co.keepAlive();
                    }
                } catch (Throwable t) {
                    if (logs++ < 8) ElasticCard.log("cometa: erro " + t);
                }
            }

            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Object v = p.thisObject;
                    if (!(v instanceof SeekBar)) return;
                    Object cm = ((SeekBar) v).getTag(TAG_COMET);
                    if (!(cm instanceof Comet)) return;
                    Comet co = (Comet) cm;
                    if (!Prefs.cometBar || co.overlay <= 0.01f) return;
                    Canvas c = (Canvas) p.args[0];
                    SeekBar b = (SeekBar) v;
                    int w = b.getWidth(), h = b.getHeight();
                    int sl = c.saveLayerAlpha(-w, -h, 2 * w, 2 * h, Math.round(255 * co.overlay));
                    co.draw(c);
                    c.restoreToCount(sl);
                } catch (Throwable t) {
                    if (logs++ < 8) ElasticCard.log("cometa: erro " + t);
                }
            }
        });
        ElasticCard.log("cometa: desenhando sobre " + c.getName() + " (onDraw de " + m.getDeclaringClass().getSimpleName() + ")");
    }

    /** Barra com cometa: trilho fino, parte tocada que vai da cor da capa ao branco e brilho na ponta. */
    static final class Comet {
        final SeekBar bar;
        final float d;
        android.widget.ImageView album;
        final Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint glowP = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint haloP = new Paint(Paint.ANTI_ALIAS_FLAG);
        float haloBlur = -1f;
        final RectF rf = new RectF();
        final android.graphics.Path tail = new android.graphics.Path();
        int lastProgress = Integer.MIN_VALUE;
        long lastMove, lastFrame;
        float act, glowBlur = -1f;
        int restHeight = -1, drawLogs;
        Drawable tintFrom;
        int tint = 0xFF9EC9FF;

        Comet(SeekBar bar, float d) {
            this.bar = bar;
            this.d = d;
        }

        static int intField(Object o, String f, int def) {
            Object v = Refl.get(o, f);
            return v instanceof Number ? ((Number) v).intValue() : def;
        }

        /** Cor da capa: a mais viva, clareada (como o azul do começo da barra no iOS). */
        int coverTint() {
            if (album == null) return tint;
            Drawable dr = album.getDrawable();
            if (dr == null || dr == tintFrom) return tint;
            tintFrom = dr;
            try {
                android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(12, 12, android.graphics.Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                Rect old = dr.copyBounds();
                dr.setBounds(0, 0, 12, 12);
                dr.draw(c);
                dr.setBounds(old);
                float best = -1f;
                float[] hsv = new float[3], pick = {210f, 0.35f, 1f};
                for (int y = 0; y < 12; y++)
                    for (int x = 0; x < 12; x++) {
                        android.graphics.Color.colorToHSV(b.getPixel(x, y), hsv);
                        float sc = hsv[1] * (0.3f + hsv[2]);
                        if (sc > best) {
                            best = sc;
                            pick = hsv.clone();
                        }
                    }
                b.recycle();
                pick[1] = Math.min(pick[1], 0.5f);
                pick[2] = 1f;
                tint = android.graphics.Color.HSVToColor(pick);
            } catch (Throwable t) {
                // fica a última cor
            }
            return tint;
        }

        boolean unclipped, playing;
        Object ctl;
        float overlay;
        long lastStep, stoppedSince;

        /** Tocando agora? Pelo MediaController do player; sem ele, pelas atualizações da barra. */
        boolean isPlaying(long now) {
            try {
                Object mc = Refl.get(ctl, "mediaController");
                if (mc instanceof android.media.session.MediaController) {
                    android.media.session.PlaybackState ps = ((android.media.session.MediaController) mc).getPlaybackState();
                    if (ps != null) {
                        int st = ps.getState();
                        // trocando de faixa, carregando ou conectando continua "tocando": o cometa não some
                        boolean stopped = st == android.media.session.PlaybackState.STATE_PAUSED
                                || st == android.media.session.PlaybackState.STATE_STOPPED
                                || st == android.media.session.PlaybackState.STATE_ERROR
                                || st == android.media.session.PlaybackState.STATE_NONE;
                        if (!stopped) {
                            stoppedSince = 0;
                            return true;
                        }
                        // só conta como pausa depois de um instante (a troca de faixa passa por pausa)
                        if (stoppedSince == 0) stoppedSince = now;
                        return now - stoppedSince < 450;
                    }
                }
            } catch (Throwable ignored) {
            }
            return now - lastMove < 2500;
        }

        /** Avança o estado (posição lisa e o quanto o cometa aparece). Devolve 0 (pausado) a 1 (tocando). */
        float step() {
            long now = android.os.SystemClock.uptimeMillis();
            int min = bar.getMin(), max = bar.getMax();
            smooth(bar.getProgress(), now, max - min);
            playing = isPlaying(now);
            float dt = lastStep == 0 ? 0f : Math.min(0.1f, (now - lastStep) / 1000f);
            lastStep = now;
            act += ((playing ? 1f : 0f) - act) * Math.min(1f, dt * 4f);
            if (act < 0.01f) act = 0f;
            if (act > 0.99f) act = 1f;
            if (act > 0f && act < 1f && bar.isShown()) bar.postInvalidateOnAnimation();
            return act;
        }

        /** Pausado: continua olhando, para o cometa voltar quando a música tocar de novo. */
        void keepAlive() {
            if (bar.isShown()) bar.postInvalidateDelayed(500);
        }
        float shown = Float.NaN, rate, seen;   // posição desenhada, velocidade (unidades por ms) e a vista
        long lastReal, lastSmooth;

        /**
         * O sistema só atualiza a barra de tempos em tempos (pulos). Entre uma atualização e outra a
         * ponta segue na velocidade da música; a cada atualização a velocidade é corrigida para
         * alcançar a posição certa em 1 s, sem voltar nem pular. Arrastar ou trocar de música encaixa
         * direto.
         */
        float smooth(int prog, long now, int range) {
            float dtm = lastSmooth == 0 ? 0f : Math.min(100f, now - lastSmooth);
            lastSmooth = now;
            boolean drag = Boolean.TRUE.equals(Refl.get(bar, "mIsDragging")) || bar.isPressed();
            // a barra do player conta em milissegundos: começa supondo 1 por ms até medir
            if (seen == 0f && range > 20000) seen = 1f;
            if (prog != lastProgress) {
                boolean first = lastProgress == Integer.MIN_VALUE;
                float obs = first || lastReal == 0 ? 0f : (prog - lastProgress) / (float) Math.max(1L, now - lastReal);
                if (!first) lastMove = now;
                float jump = Math.abs(prog - (Float.isNaN(shown) ? prog : shown));
                boolean seek = first || drag || obs < 0f || obs > 3f || jump > Math.max(2500f * Math.max(seen, 0.2f), range * 0.04f);
                if (obs > 0f && obs <= 3f) seen = seen == 0f ? obs : seen * 0.6f + obs * 0.4f;
                lastProgress = prog;
                lastReal = now;
                if (seek) {
                    shown = prog;
                    rate = drag ? 0f : seen;
                } else {
                    // alcança onde a música vai estar daqui a 1 s
                    rate = Math.max(0f, (prog + seen * 1000f - shown) / 1000f);
                }
                return shown;
            }
            if (drag) {
                shown = prog;
                return shown;
            }
            long since = now - lastReal;
            if (since > 1800) {
                // sem atualização: pausou. Volta suave para a posição real
                rate = 0f;
                shown += (prog - shown) * Math.min(1f, dtm / 120f);
            } else {
                shown += rate * dtm;
                // nunca passa muito à frente da última posição real
                shown = Math.min(shown, prog + seen * 1600f);
            }
            return shown;
        }

        /** O brilho passa da altura da barra: os pais dela (até o cartão) não cortam mais o desenho. */
        void unclip() {
            if (unclipped) return;
            unclipped = true;
            ViewParent vp = bar.getParent();
            for (int i = 0; i < 4 && vp instanceof android.view.ViewGroup; i++) {
                android.view.ViewGroup g = (android.view.ViewGroup) vp;
                if (g.getBackground() != null) break;   // o cartão continua cortando nos cantos
                g.setClipChildren(false);
                g.setClipToPadding(false);
                vp = g.getParent();
            }
        }

        /** Desenha a barra inteira. Falso = deixa a barra do sistema desenhar. */
        boolean draw(Canvas c) {
            unclip();
            int w = bar.getWidth(), hgt = bar.getHeight();
            if (w <= 0 || hgt <= 0) return false;
            int min = bar.getMin(), max = bar.getMax();
            int prog = bar.getProgress();
            long now = android.os.SystemClock.uptimeMillis();
            float frac = max > min ? Math.max(0f, Math.min(1f, (smooth(prog, now, max - min) - min) / (float) (max - min))) : 0f;
            int pad = intField(bar, "mProgressPaddingOffset", bar.getPaddingLeft());
            int mh = bar.getMaxHeight();
            int sysH = intField(bar, "mProgressHeight", mh > 0 && mh < hgt ? mh : Math.round(6f * d));
            if (restHeight < 0 || (sysH < restHeight && sysH > 0)) restHeight = sysH;
            int level = intField(bar, "mDeviceLevel", 0);
            float cy = level == 0 ? bar.getPaddingTop() + sysH / 2f : hgt / 2f;
            // espessura escolhida; cresce junto quando o sistema engrossa a barra ao arrastar
            float th = Prefs.cometThick <= 0 ? sysH : Prefs.cometThick * d * (restHeight > 0 ? Math.max(1f, sysH / (float) restHeight) : 1f);
            float left = pad, right = w - pad;
            float x = left + th / 2f + (right - left - th) * frac;
            if (drawLogs++ < 2) ElasticCard.log("cometa: barra " + w + "x" + hgt + " nível " + level + " trilho do sistema " + sysH + "px, nosso " + Math.round(th) + "px");
            float t = (now % 100000L) / 1000f;
            float pulse = 1f - (Prefs.cometPulse / 100f) * act * 0.5f * (1f - (float) Math.cos(t * Math.PI * 2));
            boolean night = night(bar);

            int save = c.save();
            if (bar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) c.scale(-1f, 1f, w / 2f, 0f);
            float rad = th / 2f;
            // trilho
            cp.setShader(null);
            // trilho: com a grossura do sistema, usa o próprio trilho do HyperOS (mesma cor ao pausar)
            Object sysBg = Prefs.cometThick <= 0 && level == 0 ? Refl.get(bar, "mBackgroundDrawable") : null;
            if (sysBg instanceof Drawable) {
                Drawable bgd = (Drawable) sysBg;
                bgd.setAlpha(255);
                bgd.setBounds(pad, bar.getPaddingTop(), w - pad, bar.getPaddingTop() + sysH);
                bgd.draw(c);
            } else {
                cp.setColor(night ? 0x26FFFFFF : 0x1F000000);
                rf.set(left, cy - rad, right, cy + rad);
                c.drawRoundRect(rf, rad, rad, cp);
            }
            // cometa: a parte tocada é uma faixa da grossura da barra (cor da capa até a ponta branca);
            // atrás da ponta, a cauda é um brilho em V que abre para trás e some, crescendo com a música
            int head = Prefs.cometColor;
            int start = Prefs.cometAuto ? coverTint() : head;
            float fade = Prefs.cometFade / 100f;
            float len = Math.min(x - left, Prefs.cometTail * d);
            float gx = x - len;
            float glow = Prefs.cometGlow / 100f * pulse;
            float size = Prefs.cometSize / 100f;
            if (glow > 0f && len > th) {
                float gb = Math.max(1.5f * d, th * 1.1f * size);
                if (gb != glowBlur) {
                    glowP.setMaskFilter(new android.graphics.BlurMaskFilter(gb, android.graphics.BlurMaskFilter.Blur.NORMAL));
                    glowBlur = gb;
                }
                // abertura da cauda: cresce com o comprimento, até ~3x a barra
                float wv = th * (0.6f + 2.4f * Math.min(1f, len / (Prefs.cometTail * d + 1f))) * size;
                tail.reset();
                tail.moveTo(x, cy - rad * 0.6f);
                tail.lineTo(gx + wv * 0.5f, cy - wv);
                tail.quadTo(gx - wv * 0.4f, cy, gx + wv * 0.5f, cy + wv);
                tail.lineTo(x, cy + rad * 0.6f);
                tail.close();
                int mid = mix(0xFF000000 | start, head, 0.35f);
                android.graphics.LinearGradient vg = new android.graphics.LinearGradient(x, 0, gx, 0,
                        new int[]{mid, (0x88 << 24) | (start & 0x00FFFFFF), start & 0x00FFFFFF}, new float[]{0f, 0.45f, 1f},
                        android.graphics.Shader.TileMode.CLAMP);
                glowP.setShader(vg);
                glowP.setAlpha(Math.round(200 * Math.min(1f, glow)));
                c.drawPath(tail, glowP);
                glowP.setShader(null);
            }
            // parte tocada
            int startA = Math.round(255 * (1f - fade));
            int startC = (startA << 24) | (start & 0x00FFFFFF);
            android.graphics.LinearGradient lg = new android.graphics.LinearGradient(Math.max(left, gx), 0, x + rad, 0,
                    new int[]{startC, mix(startC, head, 0.45f), head}, new float[]{0f, 0.6f, 1f}, android.graphics.Shader.TileMode.CLAMP);
            cp.setShader(lg);
            cp.setAlpha(255);
            rf.set(left, cy - rad, x + rad, cy + rad);
            c.drawRoundRect(rf, rad, rad, cp);
            cp.setShader(null);
            // ponta: luz alongada, como um reflexo, e não uma bola. Um halo largo e baixo atrás da
            // ponta e um filete mais forte por cima da barra, que se desfaz para trás
            if (glow > 0f) {
                float gl = Math.min(x - left + rad, 34f * d * size);
                if (gl > th) {
                    float hb = Math.max(1.5f * d, th * 0.9f);
                    if (hb != haloBlur) {
                        haloP.setMaskFilter(new android.graphics.BlurMaskFilter(hb, android.graphics.BlurMaskFilter.Blur.NORMAL));
                        haloBlur = hb;
                    }
                    float hh2 = th * 0.85f * size;
                    android.graphics.LinearGradient hg = new android.graphics.LinearGradient(x + rad - gl, 0, x + rad, 0,
                            new int[]{head & 0x00FFFFFF, (0xAA << 24) | (head & 0x00FFFFFF), head}, new float[]{0f, 0.6f, 1f},
                            android.graphics.Shader.TileMode.CLAMP);
                    haloP.setShader(hg);
                    haloP.setAlpha(Math.round(255 * Math.min(1f, glow)));
                    rf.set(x + rad - gl, cy - hh2, x + rad, cy + hh2);
                    c.drawRoundRect(rf, hh2, hh2, haloP);
                    haloP.setShader(null);
                    // filete: um pouco mais grosso que a barra, branco forte na ponta
                    float sh = rad * 1.12f;
                    android.graphics.LinearGradient sg = new android.graphics.LinearGradient(x + rad - gl * 0.75f, 0, x + rad, 0,
                            new int[]{head & 0x00FFFFFF, head}, null, android.graphics.Shader.TileMode.CLAMP);
                    cp.setShader(sg);
                    cp.setAlpha(Math.round(255 * Math.min(1f, glow * 1.4f)));
                    rf.set(x + rad - gl * 0.75f, cy - sh, x + rad, cy + sh);
                    c.drawRoundRect(rf, sh, sh, cp);
                    cp.setShader(null);
                }
            }
            // faíscas (opcional)
            int ns = Prefs.cometSparks;
            if (act > 0.01f && ns > 0 && len > 12f * d) {
                float gap = Math.max(6f * d, Math.min(14f * d, len / (ns + 1)));
                float drift = (t * 24f * d) % gap;
                for (int k = 0; k < ns; k++) {
                    float back = 6f * d + k * gap + drift;
                    if (back > len) break;
                    float f = 1f - back / len;
                    cp.setColor(head);
                    cp.setAlpha(Math.round(230 * f * f * act));
                    c.drawCircle(x - back, cy + (float) Math.sin(t * 5f + k * 1.7f) * th * 1.6f, Math.max(0.9f * d, 1.5f * d * f), cp);
                }
            }
            c.restoreToCount(save);
            // tocando, a ponta anda a cada quadro (lisa), não só quando o sistema atualiza a barra
            boolean anim = playing || (act > 0f && act < 1f) || Math.abs(shown - prog) > 0.5f;
            if (anim && bar.isShown()) bar.postInvalidateOnAnimation();
            return true;
        }

        static int mix(int a, int b, float f) {
            int aa = (a >>> 24), ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = (b >>> 24), br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }
    }

    static long lastRefresh;

    static void refresh() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastRefresh < 1000) return;
        lastRefresh = now;
        Prefs.refresh();
    }

    static boolean night(View v) {
        return (v.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** "vibrancy" do iOS: no escuro soma luz ao vidro (plus-lighter), no claro multiplica (plus-darker). */
    static android.graphics.BlendMode mode(boolean night) {
        return night ? android.graphics.BlendMode.PLUS : android.graphics.BlendMode.MULTIPLY;
    }

    /** Intensidade 0–100 em cinza: no escuro, quanto de luz soma; no claro, quanto escurece. */
    static int color(boolean night, int pct) {
        int g = night ? Math.round(255f * pct / 100f) : Math.round(255f * (1f - pct / 100f));
        g = Math.max(0, Math.min(255, g));
        return 0xFF000000 | (g << 16) | (g << 8) | g;
    }

    /** Trilho. */
    static final class Track extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float d;

        final View host;

        Track(float d, View host) {
            this.d = d;
            this.host = host;
        }

        public void draw(Canvas c) {
            boolean night = night(host);
            if (Prefs.mediaCut) {
                p.setColor(color(night, Prefs.mvTrack));
                p.setBlendMode(mode(night));
            } else {
                p.setColor(0x4DFFFFFF);
                p.setBlendMode(null);
            }
            Rect b = getBounds();
            float h = Math.min(b.height(), 6f * d);
            float cy = b.exactCenterY();
            c.drawRoundRect(new RectF(b.left, cy - h / 2f, b.right, cy + h / 2f), h / 2f, h / 2f, p);
        }

        public void setAlpha(int a) {
        }

        public void setColorFilter(ColorFilter cf) {
        }

        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Parte tocada: desenhada como recorte da pílula (mostra o cartão atrás). */
    static final class Cut extends Drawable implements Drawable.Callback {
        final Drawable orig;
        final Paint p = new Paint();

        final View host;

        Cut(Drawable orig, View host) {
            this.orig = orig;
            this.host = host;
            orig.setCallback(this);
        }

        public void draw(Canvas c) {
            if (!Prefs.mediaCut) {
                orig.setTintList(null);
                orig.draw(c);
                return;
            }
            boolean night = night(host);
            p.setBlendMode(mode(night));
            Rect b = getBounds();
            int s = c.saveLayer(b.left, b.top, b.right, b.bottom, p);
            orig.setTint(color(night, Prefs.mvPlayed));
            orig.draw(c);
            c.restoreToCount(s);
        }

        protected void onBoundsChange(Rect b) {
            orig.setBounds(b);
        }

        protected boolean onLevelChange(int level) {
            orig.setLevel(level);
            invalidateSelf();
            return true;
        }

        protected boolean onStateChange(int[] st) {
            return orig.setState(st);
        }

        public boolean isStateful() {
            return orig.isStateful();
        }

        public void invalidateDrawable(Drawable who) {
            invalidateSelf();
        }

        public void scheduleDrawable(Drawable who, Runnable r, long when) {
            scheduleSelf(r, when);
        }

        public void unscheduleDrawable(Drawable who, Runnable r) {
            unscheduleSelf(r);
        }

        public void setAlpha(int a) {
        }

        public void setColorFilter(ColorFilter cf) {
        }

        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
