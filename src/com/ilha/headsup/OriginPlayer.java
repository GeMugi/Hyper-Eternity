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
        artId = art; titleId = title; artistId = artist; elapsedId = el; barId = bar; seamId = seam; totalId = tot;
        int[] act = {id(r, pk, "action0"), id(r, pk, "action1"), id(r, pk, "action2"), id(r, pk, "action3"), id(r, pk, "action4")};
        int pad = Math.round(12 * dp), artSize = Math.round(66 * dp), timeW = Math.round(44 * dp), gap = Math.round(2 * dp);
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
        int ah = Math.round(36 * dp);
        for (int a : act) s.height(a, ah);
        s.clear(act[0], TOP);
        s.connect(act[0], TOP, bar, BOTTOM, -Math.round(4 * dp));
        s.connect(act[0], BOTTOM, PARENT, BOTTOM, Math.round(6 * dp));
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
        // espaço dos ícones dos dois lados (saída de áudio à direita, letras à esquerda) com folga
        // para o texto que corre não encostar neles
        int side = Math.round(60 * dp);
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
    static int elapsedId, barId, seamId, totalId;

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
            // posições do cartão recolhido como aparecem (com o espaço que o HMC abre para o ícone de letras)
            if (!isWide && player.getTag(TAG_MOVED) == null && !player.isLayoutRequested() && t.getWidth() > 0) {
                nTitle = new int[]{Math.round(t.getLeft() + t.getTranslationX()), Math.round(t.getTop() + t.getTranslationY())};
                nArtist = new int[]{Math.round(a.getLeft() + a.getTranslationX()), Math.round(a.getTop() + a.getTranslationY())};
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
            // capa expandida: o ícone de letras do HMC vai para o cantinho, acima do tempo decorrido;
            // recolhido, fica escondido atrás da capa (também no modo Canvas, em que o HMC o põe na
            // fileira de botões, em cima do primeiro botão)
            if (isWide) unhide();
            android.view.View icon = lyricIcon(player);
            if (icon != null) {
                if (isWide) placeIcon(icon, player, art);
                else hideBehind(icon, player, art);
            } else if (isWide) {
                // sem o ícone de letras (música sem letra, HMC sem a opção): a saída de áudio se alinha sozinha
                alignSeam(art.getParent() instanceof android.view.View ? (android.view.View) art.getParent() : player);
            }
            if (!isWide) {
                if (player.getTag(TAG_MOVED) != null) {
                    player.setTag(TAG_MOVED, null);
                    back(t, nTitle);
                    back(a, nArtist);
                    el.setTranslationX(0f);
                    el.setTranslationY(0f);
                }
                // recolhido também: a saída de áudio termina onde termina o tempo total
                alignSeam(art.getParent() instanceof android.view.View ? (android.view.View) art.getParent() : player);
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

    /** Volta o texto para onde estava no cartão recolhido. */
    static void back(android.view.View v, int[] n) {
        v.setTranslationX(n == null ? 0f : n[0] - v.getLeft());
        v.setTranslationY(n == null ? 0f : n[1] - v.getTop());
    }

    /** O cartão (ou um dos pais dele) está no layout da capa expandida? */
    static boolean wide(android.view.View v) {
        for (int i = 0; v != null && i < 4; i++) {
            if (Boolean.TRUE.equals(v.getTag(TAG_WIDE))) return true;
            v = v.getParent() instanceof android.view.View ? (android.view.View) v.getParent() : null;
        }
        return false;
    }

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
        if (v != null && Boolean.TRUE.equals(v.getTag(TAG_HID))) return null;   // escondido por nós: já sabemos onde está
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
    static final int TAG_HID = 0x7f7f0106;

    /**
     * Recolhido: o ícone de letras some (fica invisível, sem receber toque). Com Canvas, o HMC o
     * põe na fileira de botões, em cima do primeiro botão. Só mexe na visibilidade: mudar a
     * profundidade (Z) dele derrubava a Interface do Sistema ao bloquear com Canvas.
     */
    static void hideBehind(android.view.View icon, android.view.View player, android.view.View art) {
        if (icon.getVisibility() != android.view.View.VISIBLE) return;
        icon.setTag(TAG_HID, Boolean.TRUE);
        icon.setVisibility(android.view.View.INVISIBLE);
        if (logH++ < 3) plog("ícone de letras escondido (player recolhido)");
    }

    /** Capa expandida: o ícone que escondemos volta. */
    static void unhide() {
        android.view.View v = iconRef == null ? null : iconRef.get();
        if (v != null && Boolean.TRUE.equals(v.getTag(TAG_HID))) {
            v.setTag(TAG_HID, null);
            if (v.getVisibility() == android.view.View.INVISIBLE) v.setVisibility(android.view.View.VISIBLE);
        }
    }

    static int logH;

    static void placeIcon(android.view.View icon, android.view.View player, android.view.View art) {
        int[] pl = new int[2], il = new int[2];
        android.view.View host = art.getParent() instanceof android.view.View ? (android.view.View) art.getParent() : player;
        host.getLocationOnScreen(pl);
        icon.getLocationOnScreen(il);
        float baseX = il[0] - icon.getTranslationX(), baseY = il[1] - icon.getTranslationY();
        // alinhados com os tempos: o ícone de saída de áudio termina onde termina o tempo total e o
        // de letras começa onde começa o tempo decorrido, os dois na mesma altura (a do ícone de saída)
        float cx = pl[0] + art.getLeft() + art.getWidth() / 2f, cy = pl[1] + art.getTop() + art.getHeight() / 2f;
        android.view.View el = elapsedId == 0 ? null : host.findViewById(elapsedId);
        android.view.View tot = totalId == 0 ? null : host.findViewById(totalId);
        android.view.View t = host.findViewById(titleId), ar = host.findViewById(artistId);
        android.view.View seam = seamId == 0 ? null : host.findViewById(seamId);
        android.graphics.RectF gi = glyph(icon);
        if (seam != null && seam.isShown() && seam.getWidth() > 0) {
            android.graphics.RectF gs = glyph(seam);
            // desenho estranho (maior que a view): usa a própria view
            if (gs.width() <= 0 || gs.left < -1 || gs.right > seam.getWidth() + 1) gs.set(0, 0, seam.getWidth(), seam.getHeight());
            // ocupa a view inteira (o espectro que toca é desenhado no meio, com folga em volta):
            // a parte desenhada é ~64% do centro, senão o ícone fica recuado em relação ao tempo
            if (gs.left <= 0.5f && gs.right >= seam.getWidth() - 0.5f) {
                // medido nos prints: o desenho ocupa de ~32% a ~96% da largura da view
                gs.left = seam.getWidth() * 0.32f;
                gs.right = seam.getWidth() * 0.957f;
                if (logS < 3) plog("saída de áudio: " + seam.getClass().getName() + " sem desenho medido, usando o centro");
            }
            int[] sl = new int[2];
            seam.getLocationOnScreen(sl);
            float sBaseX = sl[0] - seam.getTranslationX(), sBaseY = sl[1] - seam.getTranslationY();
            alignSeam(host);
            cy = sBaseY + seam.getTranslationY() + gs.centerY();
        } else if (t != null && ar != null && t.getHeight() > 0) {
            cy = pl[1] + (t.getTop() + t.getTranslationY() + ar.getBottom() + ar.getTranslationY()) / 2f;
        }
        if (el instanceof android.widget.TextView && el.getWidth() > 0) {
            // a borda esquerda do desenho do ícone na borda esquerda do texto do tempo
            cx = pl[0] + textLeft((android.widget.TextView) el) - gi.left + icon.getWidth() / 2f;
        }
        cy += icon.getHeight() / 2f - gi.centerY();
        float tx = cx - icon.getWidth() / 2f - baseX;
        float ty = cy - icon.getHeight() / 2f - baseY;
        if (Math.abs(icon.getTranslationX() - tx) > 0.5f) icon.setTranslationX(tx);
        if (Math.abs(icon.getTranslationY() - ty) > 0.5f) icon.setTranslationY(ty);
        if (logI++ < 3) plog("ícone de letras base=" + baseX + "," + baseY + " desloca=" + tx + "," + ty + " capa centro=" + cx + "," + cy);
    }

    static int logI, logS;

    /** Ícone de saída de áudio: a borda direita do desenho na borda direita do texto do tempo total. */
    static void alignSeam(android.view.View host) {
        android.view.View seam = seamId == 0 ? null : host.findViewById(seamId);
        android.view.View tot = totalId == 0 ? null : host.findViewById(totalId);
        if (seam == null || !seam.isShown() || seam.getWidth() <= 0 || !(tot instanceof android.widget.TextView) || tot.getWidth() <= 0) return;
        // a ponta direita do desenho na ponta direita do texto do tempo total (como na teste.164)
        android.graphics.RectF gs = glyph(seam);
        if (gs.width() <= 0 || gs.left < -1 || gs.right > seam.getWidth() + 1) gs.set(0, 0, seam.getWidth(), seam.getHeight());
        if (gs.left <= 0.5f && gs.right >= seam.getWidth() - 0.5f) {
            // medido nos prints: o desenho ocupa de ~32% a ~96% da largura da view
            gs.left = seam.getWidth() * 0.32f;
            gs.right = seam.getWidth() * 0.957f;
        }
        int[] sl = new int[2], tl = new int[2];
        seam.getLocationOnScreen(sl);
        tot.getLocationOnScreen(tl);
        android.widget.TextView tv = (android.widget.TextView) tot;
        float right = tl[0] + textRight(tv) - tv.getLeft() - tv.getTranslationX();
        float raw = right - (sl[0] - seam.getTranslationX() + gs.right);
        float want = Math.abs(raw) > 64 * seam.getResources().getDisplayMetrics().density ? 0f : raw;
        if (Math.abs(seam.getTranslationX() - want) > 0.5f) seam.setTranslationX(want);
        if (seam.getScaleX() != 1f) { seam.setScaleX(1f); seam.setScaleY(1f); }
        if (logS++ < 6) plog("saída de áudio: fim do tempo=" + right + " precisa " + raw + " desloca " + want);
    }

    static android.graphics.RectF glyph(android.view.View v) {
        android.graphics.RectF r = new android.graphics.RectF(0, 0, v.getWidth(), v.getHeight());
        android.view.View iv = v;
        float ox = 0, oy = 0;
        if (!(v instanceof android.widget.ImageView) && v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View c = g.getChildAt(i);
                if (c instanceof android.widget.ImageView && c.getVisibility() == android.view.View.VISIBLE && c.getWidth() > 0) {
                    iv = c;
                    ox = c.getLeft() + c.getTranslationX();
                    oy = c.getTop() + c.getTranslationY();
                    break;
                }
            }
        }
        if (iv instanceof android.widget.ImageView) {
            android.widget.ImageView im = (android.widget.ImageView) iv;
            android.graphics.drawable.Drawable d = im.getDrawable();
            int dw = d == null ? 0 : d.getIntrinsicWidth(), dh = d == null ? 0 : d.getIntrinsicHeight();
            if (im.getScaleType() == android.widget.ImageView.ScaleType.FIT_XY || dw <= 0 || dh <= 0) {
                r.set(im.getPaddingLeft(), im.getPaddingTop(), im.getWidth() - im.getPaddingRight(), im.getHeight() - im.getPaddingBottom());
            } else {
                r.set(0, 0, dw, dh);
                im.getImageMatrix().mapRect(r);
                r.offset(im.getPaddingLeft(), im.getPaddingTop());
            }
            r.offset(ox, oy);
        }
        return r;
    }

    static float textLeft(android.widget.TextView t) {
        android.text.Layout l = t.getLayout();
        return t.getLeft() + t.getTranslationX() + t.getTotalPaddingLeft() + (l == null || l.getLineCount() == 0 ? 0f : l.getLineLeft(0));
    }

    static void nearby(android.view.View v, int l, int t, int r, int bt, StringBuilder b, int depth) {
        if (depth > 8 || v.getVisibility() != android.view.View.VISIBLE) return;
        int[] p = new int[2];
        v.getLocationOnScreen(p);
        boolean in = p[0] < r && p[0] + v.getWidth() > l && p[1] < bt && p[1] + v.getHeight() > t;
        if (!in) return;
        if (!(v instanceof android.view.ViewGroup) || v.getWidth() < 300) {
            String n = "?";
            try { if (v.getId() != android.view.View.NO_ID) n = v.getResources().getResourceEntryName(v.getId()); } catch (Throwable ignored) {}
            b.append(' ').append(v.getClass().getName()).append('#').append(n).append('@').append(p[0]).append(',').append(p[1])
                    .append(' ').append(v.getWidth()).append('x').append(v.getHeight()).append(" a=").append(v.getAlpha()).append(';');
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) nearby(g.getChildAt(i), l, t, r, bt, b, depth + 1);
        }
    }

    static float textRight(android.widget.TextView t) {
        android.text.Layout l = t.getLayout();
        if (l == null || l.getLineCount() == 0) return t.getRight() + t.getTranslationX() - t.getTotalPaddingRight();
        // texto alinhado à direita ou que corre: a linha é medida numa largura enorme e o "fim" dela
        // sai fora da view; aí vale a borda de dentro da própria view
        float content = t.getWidth() - t.getTotalPaddingLeft() - t.getTotalPaddingRight();
        float lr = l.getLineRight(0);
        if (lr > content + 1f || lr <= 0f) lr = content;
        return t.getLeft() + t.getTranslationX() + t.getTotalPaddingLeft() + lr;
    }

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
                        boolean w = wide(card);
                        if (w) unhide();
                        android.view.View icon = lyricIcon(card);
                        if (icon == null) return;
                        if (w) placeIcon(icon, card, art);
                        else hideBehind(icon, card, art);
                    } catch (Throwable t) {
                        if (logI++ < 6) plog("ícone de letras: " + t);
                    }
                }
            });
            plog("HMC: acompanhando o ícone de letras");
        } catch (Throwable t) {
            plog("HMC sem assertMediaCard: " + t);
        }
        // o HMC encosta o título na esquerda enquanto a capa expande; com a capa expandida quem
        // posiciona somos nós. Recolhido, o HMC continua afastando título e artista do ícone de letras.
        try {
            java.lang.reflect.Method m = hmc.getDeclaredMethod("alignCardTextLeft", android.view.View.class, android.widget.TextView.class, float.class, float.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (Prefs.playerOrigin && p.args[0] instanceof android.view.View && wide((android.view.View) p.args[0])) p.setResult(null);
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
