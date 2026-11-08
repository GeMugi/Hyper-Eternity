package com.ilha.headsup;

import android.content.Context;
import android.content.res.Resources;

import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;

/**
 * Player de música da central no estilo OriginOS: capa grande à esquerda (com o ícone do app no
 * canto de baixo), título e artista ao lado, a barra de progresso com os tempos logo abaixo deles
 * (na altura do fim da capa) e os controles centralizados embaixo, ocupando a largura toda.
 *
 * É só uma mudança no conjunto de regras de posição (ConstraintSet) que o HyperOS carrega para o
 * player; as peças, as cores e os toques continuam os do sistema.
 */
final class OriginPlayer {
    private OriginPlayer() {
    }

    static final int LEFT = 1, RIGHT = 2, TOP = 3, BOTTOM = 4, PARENT = 0;
    static int logs;

    static void install(ClassLoader cl) {
        try {
            Class<?> c = Class.forName("com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaNotificationControllerImpl", false, cl);
            XposedBridge.hookMethod(c.getDeclaredMethod("loadLayout$1"), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!Prefs.playerOrigin) return;
                    try {
                        Object o = p.thisObject;
                        Context ctx = (Context) Refl.field(o.getClass(), "context").get(o);
                        apply(Refl.field(o.getClass(), "normalLayout").get(o), Refl.field(o.getClass(), "normalAlbumLayout").get(o), ctx);
                        if (logs++ < 3) ElasticCard.log("player OriginOS: aplicado");
                    } catch (Throwable t) {
                        ElasticCard.log("player OriginOS: " + t);
                    }
                }
            });
            XposedBridge.hookMethod(c.getDeclaredMethod("updateLayout$6"), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!Prefs.playerOrigin) return;
                    try {
                        Object h = Refl.field(p.thisObject.getClass(), "mediaViewHolder").get(p.thisObject);
                        if (h == null) return;
                        // o HyperOS acabou de reaplicar as regras normais: a variante precisa ser escolhida de novo
                        android.view.View pl = (android.view.View) Refl.field(h.getClass(), "player").get(h);
                        if (pl != null) pl.setTag(TAG_WIDE, Boolean.FALSE);
                        tune(h);
                    } catch (Throwable t) {
                        if (logs++ < 6) ElasticCard.log("player OriginOS (ajustes): " + t);
                    }
                }
            });
            // o HyperOS fixa a altura do fundo (185dp) a cada mudança de AOD; aqui ela volta a seguir o conteúdo
            Class<?> vc = Class.forName("com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl", false, cl);
            XC_MethodHook fix = new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!Prefs.playerOrigin) return;
                    try {
                        Object h = Refl.field(p.thisObject.getClass(), "holder").get(p.thisObject);
                        if (h == null) return;
                        tune(h);
                        if ("onFullAodStateChanged".equals(((java.lang.reflect.Member) p.method).getName()) && Boolean.TRUE.equals(p.args[0])) return;
                        android.view.View bg = (android.view.View) Refl.field(h.getClass(), "mediaBg").get(h);
                        android.view.ViewGroup.LayoutParams lp = bg.getLayoutParams();
                        if (lp != null && lp.height != 0) {
                            lp.height = 0;
                            bg.setLayoutParams(lp);
                        }
                    } catch (Throwable t) {
                        if (logs++ < 6) ElasticCard.log("player OriginOS (fundo): " + t);
                    }
                }
            };
            XposedBridge.hookMethod(vc.getDeclaredMethod("onFullAodStateChanged", boolean.class), fix);
            XposedBridge.hookMethod(vc.getDeclaredMethod("setInfoText"), fix);
            ElasticCard.log("player OriginOS: pronto");
        } catch (Throwable t) {
            ElasticCard.log("player OriginOS indisponível: " + t);
        }
    }

    /** Variante com a capa expandida na tela de bloqueio (capa grande escondida no cartão): tudo centralizado. */
    static Object wide, normal;
    static int artId, titleId, artistId;

    static void apply(Object set, Object album, Context ctx) throws Throwable {
        Resources r = ctx.getResources();
        float dp = r.getDisplayMetrics().density;
        String pk = ctx.getPackageName();
        Cs s = new Cs(set);
        int art = id(r, pk, "album_art"), seam = id(r, pk, "media_seamless"), title = id(r, pk, "header_title"),
                artist = id(r, pk, "header_artist"), el = id(r, pk, "media_elapsed_time"), tot = id(r, pk, "media_total_time"),
                bar = id(r, pk, "media_progress_bar");
        artId = art; titleId = title; artistId = artist; elapsedId = el; barId = bar;
        int[] act = {id(r, pk, "action0"), id(r, pk, "action1"), id(r, pk, "action2"), id(r, pk, "action3"), id(r, pk, "action4")};
        int pad = Math.round(14 * dp), artSize = Math.round(70 * dp), timeW = Math.round(44 * dp), gap = Math.round(2 * dp);
        // cartão do tamanho do conteúdo (no HyperOS o fundo tem altura fixa de 185dp)
        s.size(id(r, pk, "media_bg"), 0, 0);

        // capa no canto de cima à esquerda
        s.size(art, artSize, artSize);
        s.margin(art, TOP, pad);
        s.margin(art, LEFT, pad);
        s.margin(art, 6, pad);   // START
        s.margin(seam, TOP, pad - Math.round(4 * dp));

        // título e artista ao lado da capa
        s.margin(title, TOP, pad);
        s.margin(artist, TOP, Math.round(1 * dp));

        // tempos com largura fixa: o texto muda a cada segundo sem mexer no resto do cartão
        // (com largura variável o cartão se refazia todo segundo e o título que corre reiniciava)
        s.clear(el);
        s.connect(el, LEFT, art, RIGHT, Math.round(12 * dp));
        s.connect(el, TOP, bar, TOP, 0);
        s.connect(el, BOTTOM, bar, BOTTOM, 0);
        s.size(el, timeW, -2);
        s.clear(bar);
        s.connect(bar, LEFT, el, RIGHT, 0);
        s.connect(bar, RIGHT, tot, LEFT, Math.round(5 * dp));
        s.connect(bar, TOP, artist, BOTTOM, 0);
        s.width(bar, 0);
        s.clear(tot);
        s.connect(tot, RIGHT, PARENT, RIGHT, pad);
        s.connect(tot, TOP, bar, TOP, 0);
        s.connect(tot, BOTTOM, bar, BOTTOM, 0);
        s.size(tot, timeW, -2);

        // controles embaixo, menores e espalhados
        int ah = Math.round(42 * dp);
        for (int a : act) s.height(a, ah);
        s.clear(act[0], TOP);
        s.connect(act[0], TOP, bar, BOTTOM, -Math.round(2 * dp));
        s.connect(act[0], BOTTOM, PARENT, BOTTOM, Math.round(8 * dp));
        s.margin(act[0], LEFT, Math.round(36 * dp));
        s.margin(act[4], RIGHT, Math.round(36 * dp));
        s.chain(act[0], 0);

        // capa com o ícone do app no canto de baixo à direita
        if (album != null) {
            Cs a = new Cs(album);
            int img = id(r, pk, "album_art_image"), icon = id(r, pk, "icon");
            a.size(img, artSize, artSize);
            a.clear(icon);
            a.connect(icon, RIGHT, PARENT, RIGHT, Math.round(5 * dp));
            a.connect(icon, BOTTOM, PARENT, BOTTOM, Math.round(5 * dp));
        }

        // variante "capa expandida": título e artista centralizados, barra na largura toda
        Object w = set.getClass().getConstructor().newInstance();
        w.getClass().getMethod("clone", set.getClass()).invoke(w, set);
        Cs x = new Cs(w);
        int side = Math.round(48 * dp);   // espaço do ícone de saída de áudio dos dois lados
        x.clear(title);
        x.connect(title, LEFT, PARENT, LEFT, side);
        x.connect(title, RIGHT, PARENT, RIGHT, side);
        x.connect(title, TOP, PARENT, TOP, pad);
        x.width(title, 0);
        x.clear(artist);
        x.connect(artist, LEFT, PARENT, LEFT, side);
        x.connect(artist, RIGHT, PARENT, RIGHT, side);
        x.connect(artist, TOP, title, BOTTOM, Math.round(1 * dp));
        x.width(artist, 0);
        x.clear(el, LEFT);
        x.connect(el, LEFT, PARENT, LEFT, pad);
        // a capa fazia corrente com o título (direita dela -> esquerda dele): sem o título ao lado ela
        // recentralizava e ia para a borda do cartão, e o HMC mirava o voo de volta para lá
        x.clear(art, RIGHT);
        x.clear(art, 7);   // END
        x.connect(art, LEFT, PARENT, LEFT, pad);
        normal = set;
        wide = w;
    }

    /** Barra sem recuo nas pontas; tempos alinhados às pontas; liga o acompanhamento da capa expandida. */
    static void tune(Object h) throws Throwable {
        Object sb = Refl.field(h.getClass(), "seekBar").get(h);
        if (sb instanceof android.view.View) {
            android.view.View v = (android.view.View) sb;
            if (v.getPaddingLeft() != 0 || v.getPaddingRight() != 0) v.setPadding(0, v.getPaddingTop(), 0, v.getPaddingBottom());
        }
        android.widget.TextView el = (android.widget.TextView) Refl.field(h.getClass(), "elapsedTimeView").get(h);
        android.widget.TextView tot = (android.widget.TextView) Refl.field(h.getClass(), "totalTimeView").get(h);
        if (el != null) { el.setMinWidth(0); el.setMinEms(0); el.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL); fit(el); }
        if (tot != null) { tot.setMinWidth(0); tot.setMinEms(0); tot.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL); fit(tot); }
        final android.view.View player = (android.view.View) Refl.field(h.getClass(), "player").get(h);
        if (player != null) lastPlayer = new java.lang.ref.WeakReference<>(player);
        if (hmc == null && hmcTries < 30) {
            hmcTries++;
            for (String f : new String[]{"albumImageView", "albumView", "mediaBg", "player", "appIcon"}) {
                try { findHmc((android.view.View) Refl.field(h.getClass(), f).get(h)); } catch (Throwable ignored) { }
                if (hmc != null) break;
            }
        }
        if (player != null && player.getTag(0x7f7f0101) == null) {
            player.setTag(0x7f7f0101, Boolean.TRUE);
            // acompanha a expansão da capa quadro a quadro, antes de cada desenho
            player.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                public boolean onPreDraw() {
                    if (!Prefs.playerOrigin || !player.isAttachedToWindow()) return true;
                    return frame(player);
                }
            });
        }
    }

    static final int TAG_WIDE = 0x7f7f0102, TAG_MOVED = 0x7f7f0105;
    /** Posições do layout normal (relativas ao cartão): título, artista, tempo decorrido. */
    static int[] nTitle, nArtist, nEl;
    static int elapsedId, barId;

    /**
     * Um quadro do player. p = quanto a capa já expandiu (0 = cartão normal, 1 = capa grande).
     * Com p > 0 vale o layout largo (título centralizado, barra na largura toda) e cada peça sai
     * da posição que tinha no layout normal e chega na nova junto com a capa, sem salto.
     */
    static boolean frame(android.view.View player) {
        try {
            if (wide == null || !(player instanceof android.view.ViewGroup)) return true;
            android.view.View art = player.findViewById(artId);
            android.widget.TextView t = (android.widget.TextView) player.findViewById(titleId);
            android.widget.TextView a = (android.widget.TextView) player.findViewById(artistId);
            android.view.View el = elapsedId == 0 ? null : player.findViewById(elapsedId);
            android.view.View bar = barId == 0 ? null : player.findViewById(barId);
            if (art == null || t == null || a == null || el == null || bar == null) return true;
            // capa espelhada (escala negativa sem estar virada): sobra de uma troca de layout no meio da virada
            if (art.getScaleX() < 0f && Math.abs(art.getRotationY() % 360f) < 1f) {
                art.setScaleX(-art.getScaleX());
                plog("capa desespelhada");
            }
            float p = progress(art);
            boolean isWide = Boolean.TRUE.equals(player.getTag(TAG_WIDE));
            // a mola do HMC passa um pouco do fim e volta: recolhendo, depois de chegar no cartão não reabre
            if (morphOn && morphTarget < 0.5f && !isWide) p = 0f;
            if (morphOn && morphTarget >= 0.5f && isWide && p < 0.001f) p = 0.001f;
            boolean wantWide = p > 0.001f;
            if (!isWide && !player.isLayoutRequested() && t.getWidth() > 0) {
                nTitle = new int[]{t.getLeft(), t.getTop()};
                nArtist = new int[]{a.getLeft(), a.getTop()};
                nEl = new int[]{el.getLeft(), el.getTop()};
            }
            if (wantWide != isWide) {
                swap(player, wantWide);
                player.setTag(TAG_WIDE, wantWide);
                plog((wantWide ? "expandindo" : "recolhida") + " p=" + p + " hmc=" + (hmc != null) + " alinhamento=" + (alignHooked ? "nosso" : "HMC")
                        + " cartão h=" + player.getHeight() + " y=" + loc(player) + " capa " + art.getLeft() + "," + art.getTop() + " " + art.getWidth() + "x" + art.getHeight()
                        + " escala=" + art.getScaleX() + " girar=" + art.getRotationY() + " tx=" + art.getTranslationX() + "/" + art.getTranslationY());
                return false;   // refaz o layout antes de desenhar: nada aparece fora do lugar
            }
            // o ícone de letras do HMC fica no cantinho da capa (centro do quadrado onde a capa fica)
            android.view.View icon = lyricIcon(player);
            if (icon != null) placeIcon(icon, player, art);
            if (!isWide) {
                if (player.getTag(TAG_MOVED) != null) {
                    player.setTag(TAG_MOVED, null);
                    for (android.view.View v : new android.view.View[]{t, a, el}) { v.setTranslationX(0f); v.setTranslationY(0f); }
                }
                return true;
            }
            player.setTag(TAG_MOVED, Boolean.TRUE);
            float q = 1f - p;
            slideText(t, nTitle, p, q, 0f);
            slideText(a, nArtist, p, q, 0f);
            if (nEl != null) {
                el.setTranslationX(q * (nEl[0] - el.getLeft()));
                el.setTranslationY(q * (nEl[1] - el.getTop()));
            }
            // a barra começa logo depois do tempo, onde quer que ele esteja agora
            android.view.ViewGroup.LayoutParams lp = bar.getLayoutParams();
            int ml = lp instanceof android.view.ViewGroup.MarginLayoutParams ? ((android.view.ViewGroup.MarginLayoutParams) lp).leftMargin : 0;
            int left = Math.round(el.getLeft() + el.getTranslationX() + el.getWidth()) + ml;
            if (left < bar.getRight() - 1 && bar.getLeft() != left) bar.setLeft(left);
            if (logW++ < 4) plog("quadro p=" + p + " título x=" + t.getLeft() + " w=" + t.getWidth() + " tx=" + t.getTranslationX() + " alpha=" + t.getAlpha() + "/" + t.getTransitionAlpha());
        } catch (Throwable th) {
            plog("quadro: " + th);
        }
        return true;
    }

    static int logW;

    static String loc(android.view.View v) {
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        return l[0] + "," + l[1];
    }

    /** Ícone de letras que o HMC põe no cartão (io0.b), só quando ele estiver dentro deste player. */
    static java.lang.reflect.Field fIcon;

    static java.lang.ref.WeakReference<android.view.View> iconRef;
    static long iconLook;
    static int iconLogs;

    static android.view.View lyricIcon(android.view.View player) {
        android.view.View v = iconRef == null ? null : iconRef.get();
        if (v == null || !ok(v, player) || !isLyricDesc(v)) {
            v = null;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - iconLook > 500) {
                iconLook = now;
                v = findDesc(player.getRootView());
                if (v == null && fIcon != null) {
                    try {
                        Object o = fIcon.get(null);
                        if (o instanceof android.view.View) v = (android.view.View) o;
                    } catch (Throwable t) { fIcon = null; }
                }
                iconRef = v == null ? null : new java.lang.ref.WeakReference<>(v);
                if (v != null && iconLogs++ < 3) plog("ícone de letras achado: " + v.getClass().getName() + " pai=" + (v.getParent() == null ? "-" : v.getParent().getClass().getSimpleName()) + " " + v.getWidth() + "x" + v.getHeight());
            }
        }
        return v != null && ok(v, player) ? v : null;
    }

    static boolean ok(android.view.View v, android.view.View player) {
        return v.isAttachedToWindow() && v.getVisibility() == android.view.View.VISIBLE && v.getWidth() > 0;
    }

    static boolean isLyricDesc(android.view.View v) {
        CharSequence d = v.getContentDescription();
        if (d == null) return false;
        String s = d.toString();
        return s.equals("Show lyrics") || s.equals("Hide lyrics") || s.equals("\u663e\u793a\u6b4c\u8bcd") || s.equals("\u9690\u85cf\u6b4c\u8bcd");
    }

    static android.view.View findDesc(android.view.View v) {
        if (v == null) return null;
        if (v instanceof android.widget.ImageView && isLyricDesc(v)) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View r = findDesc(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    /** Põe o ícone de letras no centro do lugar da capa (contas na tela: o HMC pode pôr o ícone fora do player). */
    static void placeIcon(android.view.View icon, android.view.View player, android.view.View art) {
        int[] pl = new int[2], il = new int[2];
        android.view.View host = art.getParent() instanceof android.view.View ? (android.view.View) art.getParent() : player;
        host.getLocationOnScreen(pl);
        icon.getLocationOnScreen(il);
        float baseX = il[0] - icon.getTranslationX(), baseY = il[1] - icon.getTranslationY();
        // no canto, centralizado acima do tempo decorrido e na altura do bloco título + artista
        float cx = pl[0] + art.getLeft() + art.getWidth() / 2f, cy = pl[1] + art.getTop() + art.getHeight() / 2f;
        android.view.View el = elapsedId == 0 ? null : host.findViewById(elapsedId);
        android.view.View t = host.findViewById(titleId), ar = host.findViewById(artistId);
        if (el != null && el.getWidth() > 0) cx = pl[0] + el.getLeft() + el.getTranslationX() + el.getWidth() / 2f;
        if (t != null && ar != null && t.getHeight() > 0) cy = pl[1] + (t.getTop() + t.getTranslationY() + ar.getBottom() + ar.getTranslationY()) / 2f;
        float tx = cx - icon.getWidth() / 2f - baseX;
        float ty = cy - icon.getHeight() / 2f - baseY;
        if (Math.abs(icon.getTranslationX() - tx) > 0.5f) icon.setTranslationX(tx);
        if (Math.abs(icon.getTranslationY() - ty) > 0.5f) icon.setTranslationY(ty);
        if (logI++ < 3) plog("ícone de letras base=" + baseX + "," + baseY + " desloca=" + tx + "," + ty + " capa centro=" + cx + "," + cy);
    }

    static int logI;

    /** Título/artista: do lugar no cartão normal até centralizado (texto longo: começa na margem e corre). */
    static void slideText(android.widget.TextView t, int[] n, float p, float q, float iconShift) {
        int padL = t.getTotalPaddingLeft();
        int content = t.getWidth() - padL - t.getTotalPaddingRight();
        CharSequence cs = t.getText();
        float tw = cs == null ? 0f : t.getPaint().measureText(cs.toString());
        float center = Math.max(0f, (content - tw) / 2f);
        // com o ícone de letras do HMC antes do título, o par ícone + título fica centralizado
        if (iconShift > 0f) center = Math.min(Math.max(0f, content - tw), center + iconShift);
        float tx = p * center;
        float ty = 0f;
        if (n != null) {
            tx += q * (n[0] - t.getLeft());
            ty = q * (n[1] - t.getTop());
        }
        if (t.getTranslationX() != tx) t.setTranslationX(tx);
        if (t.getTranslationY() != ty) t.setTranslationY(ty);
    }

    /** Troca o conjunto de regras mantendo o que o HyperOS/HMC deixou em cada peça (visibilidade, alfa, escala). */
    static void swap(android.view.View player, boolean toWide) throws Throwable {
        android.view.ViewGroup g = (android.view.ViewGroup) player;
        int n = g.getChildCount();
        int[] vis = new int[n];
        float[][] pr = new float[n][];
        for (int i = 0; i < n; i++) {
            android.view.View c = g.getChildAt(i);
            vis[i] = c.getVisibility();
            pr[i] = new float[]{c.getAlpha(), c.getScaleX(), c.getScaleY(), c.getTranslationX(), c.getTranslationY(), c.getRotation(), c.getElevation(), c.getTranslationZ(),
                    c.getRotationX(), c.getRotationY(), c.getPivotX(), c.getPivotY(), c.isPivotSet() ? 1f : 0f, c.getCameraDistance()};
        }
        Object set = toWide ? wide : normal;
        set.getClass().getMethod("applyTo", findCl(player.getClass())).invoke(set, player);
        for (int i = 0; i < n && i < g.getChildCount(); i++) {
            android.view.View c = g.getChildAt(i);
            if (c.getVisibility() != vis[i]) c.setVisibility(vis[i]);
            float[] v = pr[i];
            c.setAlpha(v[0]); c.setScaleX(v[1]); c.setScaleY(v[2]); c.setTranslationX(v[3]); c.setTranslationY(v[4]);
            c.setRotation(v[5]); c.setElevation(v[6]); c.setTranslationZ(v[7]);
            c.setRotationX(v[8]); c.setRotationY(v[9]); c.setCameraDistance(v[13]);
            if (v[12] > 0f) { c.setPivotX(v[10]); c.setPivotY(v[11]); } else c.resetPivot();
        }
    }

    /** Progresso da capa expandida: o mesmo valor que o HyperMusicCover usa para sumir com a capa pequena. */
    static float progress(android.view.View art) {
        float m = morph();
        if (m >= 0f) return m;   // capa grande voando para o cartão (ou saindo dele): segue a mola do HMC
        float p = -1f;
        if (hmc != null) {
            try {
                if (fForced != null && fForced.getBoolean(null)) p = fCover != null && fCover.getBoolean(null) ? 1f : 0f;
                else if (fP != null) p = fP.getFloat(null);
            } catch (Throwable ignored) {
            }
        }
        boolean shown = art.getVisibility() == android.view.View.VISIBLE;
        if (p < 0f) p = shown ? 1f - art.getAlpha() : 1f;           // sem o HMC: pela capa pequena
        else if (shown && art.getAlpha() > 0.995f) p = 0f;          // capa pequena inteira: cartão normal
        return Math.max(0f, Math.min(1f, p));
    }

    /** Animação em que a capa grande voa até o cartão (com.os4.musiccover.l): 0 = no cartão, 1 = expandida; -1 = parada. */
    static java.lang.reflect.Field mW, mT, mK, mD, mA, mC;

    static boolean morphOn;
    static float morphTarget;

    static float morph() {
        morphOn = false;
        if (mW == null) return -1f;
        try {
            Object w = mW.get(null);
            if (w == null || !mT.getBoolean(w)) return -1f;
            if (mK != null && !mK.getBoolean(w)) return -1f;
            Object d = mD.get(w);
            if (d == null) return -1f;
            if (mA == null) { mA = d.getClass().getDeclaredField("a"); mA.setAccessible(true); }
            if (mC == null) { mC = d.getClass().getDeclaredField("c"); mC.setAccessible(true); }
            morphOn = true;
            morphTarget = mC.getFloat(d);
            if (logM++ < 3) plog("mola da capa: " + mA.getFloat(d));
            return Math.max(0f, Math.min(1f, mA.getFloat(d)));
        } catch (Throwable t) {
            plog("mola da capa: " + t);
            mW = null;
            return -1f;
        }
    }

    static int logM;

    static java.lang.ref.WeakReference<android.view.View> lastPlayer;
    static Class<?> hmc;
    static int hmcTries;
    static java.lang.reflect.Field fP, fForced, fCover;
    static boolean alignHooked;

    /** Acha o HyperMusicCover (outro módulo, outro carregador de classes) pelos ouvintes que ele põe nas views do player. */
    static void findHmc(android.view.View v) {
        if (v == null || hmc != null) return;
        try {
            java.lang.reflect.Field li = android.view.View.class.getDeclaredField("mListenerInfo");
            li.setAccessible(true);
            scan(li.get(v));
        } catch (Throwable ignored) {
        }
        if (hmc == null) scan(v.getViewTreeObserver());
        if (hmc == null) return;
        try {
            fP = hmc.getDeclaredField("sCardP"); fP.setAccessible(true);
        } catch (Throwable t) { plog("HMC sem sCardP: " + t); }
        try {
            fForced = hmc.getDeclaredField("sCardForced"); fForced.setAccessible(true);
            fCover = hmc.getDeclaredField("sCoverMode"); fCover.setAccessible(true);
        } catch (Throwable t) { fForced = null; fCover = null; }
        try {
            Class<?> mc = Class.forName("com.os4.musiccover.l", false, hmc.getClassLoader());
            java.lang.reflect.Field w = mc.getDeclaredField("w"), t = mc.getDeclaredField("t"), d = mc.getDeclaredField("d");
            if (w.getType() == mc && t.getType() == boolean.class && !d.getType().isPrimitive()) {
                w.setAccessible(true); t.setAccessible(true); d.setAccessible(true);
                try {
                    java.lang.reflect.Field k = mc.getDeclaredField("k");
                    if (k.getType() == boolean.class) { k.setAccessible(true); mK = k; }
                } catch (Throwable ignored) { }
                mT = t; mD = d; mW = w;
            }
        } catch (Throwable t) { plog("HMC sem animação da capa: " + t); }
        try {
            // a classe muda de nome a cada versão do HMC (io0 na 1.1.1, ro0 na 1.1.1-2)
            for (String n : new String[]{"ro0", "io0"}) {
                try {
                    Class<?> ic = Class.forName(n, false, hmc.getClassLoader());
                    java.lang.reflect.Field a = ic.getDeclaredField("a"), b = ic.getDeclaredField("b");
                    if (a.getType() == android.view.ViewGroup.class && b.getType() == android.widget.ImageView.class
                            && java.lang.reflect.Modifier.isStatic(b.getModifiers())) { b.setAccessible(true); fIcon = b; break; }
                } catch (Throwable ignored) { }
            }
            if (fIcon == null) plog("HMC sem ícone de letras (classe não encontrada)");
        } catch (Throwable t) { plog("HMC sem ícone de letras: " + t); }
        // o HMC põe o ícone de letras ao lado do título a cada atualização do cartão (assertMediaCard);
        // logo depois dele, o ícone vai para o lugar da capa
        try {
            java.lang.reflect.Method am = hmc.getDeclaredMethod("assertMediaCard", android.view.View.class);
            XposedBridge.hookMethod(am, new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!Prefs.playerOrigin || !(p.args[0] instanceof android.view.View)) return;
                    try {
                        android.view.View card = (android.view.View) p.args[0];
                        android.view.View art = artId == 0 ? null : card.findViewById(artId);
                        if (art == null) return;
                        android.view.View icon = lyricIcon(card);
                        if (icon != null) placeIcon(icon, card, art);
                    } catch (Throwable t) {
                        if (logI++ < 6) plog("ícone de letras: " + t);
                    }
                }
            });
            plog("HMC: acompanhando o ícone de letras");
        } catch (Throwable t) {
            plog("HMC sem assertMediaCard: " + t);
        }
        // o HMC encosta o título na esquerda enquanto a capa expande; com este player quem posiciona somos nós
        try {
            java.lang.reflect.Method m = hmc.getDeclaredMethod("alignCardTextLeft", android.view.View.class, android.widget.TextView.class, float.class, float.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (Prefs.playerOrigin) p.setResult(null);
                }
            });
            alignHooked = true;
        } catch (Throwable t) {
            plog("HMC sem alignCardTextLeft: " + t);
        }
        plog("HMC encontrado (progresso=" + (fP != null) + ", mola=" + (mW != null) + ")");
    }

    static void scan(Object holder) {
        if (holder == null) return;
        for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(holder);
                    if (v == null) continue;
                    if (!(v instanceof java.util.Collection)) {
                        try {
                            java.lang.reflect.Field d = v.getClass().getDeclaredField("mData");
                            d.setAccessible(true);
                            Object dv = d.get(v);
                            if (dv instanceof java.util.Collection) v = dv;
                        } catch (Throwable ignored) {
                        }
                    }
                    if (v instanceof java.util.Collection) {
                        for (Object o : new java.util.ArrayList<Object>((java.util.Collection<?>) v)) if (check(o)) return;
                    } else if (check(v)) return;
                } catch (Throwable ignored) {
                }
            }
        }
    }

    static boolean check(Object o) {
        if (o == null) return false;
        ClassLoader l = o.getClass().getClassLoader();
        if (l == null || l == OriginPlayer.class.getClassLoader() || l == android.view.View.class.getClassLoader()) return false;
        try {
            hmc = Class.forName("com.os4.musiccover.Main", false, l);
            HmcFix.found(l);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Largura exata de "00:00" na fonte do tempo (sem sobra dos lados da barra). */
    static void fit(android.widget.TextView t) {
        CharSequence cs = t.getText();
        String sample = cs != null && cs.length() > 5 ? cs.toString().replaceAll("[0-9]", "0") : "00:00";
        int w = (int) Math.ceil(t.getPaint().measureText(sample)) + t.getPaddingLeft() + t.getPaddingRight() + 2;
        android.view.ViewGroup.LayoutParams lp = t.getLayoutParams();
        if (lp != null && lp.width != w) {
            lp.width = w;
            t.setLayoutParams(lp);
        }
    }

    static int plogs;

    static void plog(String m) {
        if (plogs++ < 150) XposedBridge.log("[HeadsUpIlha] player OriginOS: " + m);
    }

    static Class<?> findCl(Class<?> c) {
        while (c != null && !c.getName().equals("androidx.constraintlayout.widget.ConstraintLayout")) c = c.getSuperclass();
        return c;
    }

    static int id(Resources r, String pk, String n) {
        int i = r.getIdentifier(n, "id", pk);
        if (i == 0) throw new IllegalStateException("id " + n);
        return i;
    }

    /** Acesso ao ConstraintSet da Interface do Sistema (androidx com os nomes originais). */
    static final class Cs {
        final Object set;

        Cs(Object set) { this.set = set; }

        Object layout(int id) throws Throwable {
            Object c = set.getClass().getMethod("get", int.class).invoke(set, id);
            return c.getClass().getField("layout").get(c);
        }

        void clear(int id, int side) throws Throwable {
            set.getClass().getMethod("clear", int.class, int.class).invoke(set, id, side);
        }

        void clear(int id) throws Throwable {
            for (int side = 1; side <= 7; side++) set.getClass().getMethod("clear", int.class, int.class).invoke(set, id, side);
        }

        void connect(int a, int as, int b, int bs, int m) throws Throwable {
            set.getClass().getMethod("connect", int.class, int.class, int.class, int.class, int.class).invoke(set, a, as, b, bs, m);
        }

        void margin(int id, int side, int m) throws Throwable {
            set.getClass().getMethod("setMargin", int.class, int.class, int.class).invoke(set, id, side, m);
        }

        void width(int id, int w) throws Throwable {
            set.getClass().getMethod("constrainWidth", int.class, int.class).invoke(set, id, w);
        }

        void height(int id, int h) throws Throwable {
            set.getClass().getMethod("constrainHeight", int.class, int.class).invoke(set, id, h);
        }

        void size(int id, int w, int h) throws Throwable {
            width(id, w);
            set.getClass().getMethod("constrainHeight", int.class, int.class).invoke(set, id, h);
        }

        void chain(int head, int style) throws Throwable {
            Object l = layout(head);
            l.getClass().getField("horizontalChainStyle").setInt(l, style);
        }
    }
}
