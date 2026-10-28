package com.ilha.headsup;

import android.os.SystemClock;
import android.content.SharedPreferences;
import com.ilha.headsup.xp.XposedBridge;

/**
 * Leitura dos ajustes dentro da Interface do Sistema. Os valores vêm das preferências remotas do
 * LSPosed (grupo "ilha"): o app grava lá e o aviso de mudança chega aqui na hora.
 */
final class Prefs {
    private Prefs() {}

    private static volatile SharedPreferences prefs;
    private static volatile boolean loading;
    private static volatile long failedAt;
    private static boolean loggedState;
    private static volatile boolean dirty = true;
    private static final SharedPreferences.OnSharedPreferenceChangeListener LISTENER =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                    dirty = true;
                }
            };

    static boolean huEnabled, huNoActions, huSeparate, huHideArrow;
    static int huSpeed, huBounce, huStyle, huIosSpeed, huIosBounce;
    static boolean stEnabled, stGroups, landSplit, landMini;
    static boolean lockWidgets, lwBattery = true, lwWeather = true, lwAgenda = true, lwSteps = true;
    static int stOffset;
    static boolean kgEnabled, kgScroll, kgIos, kgClock, kgClockFixed, kgBlur, kgHmc, kgKeep;
    static int kgOffset, kgStart;
    static int mvText = Keys.D_MV_TEXT, mvTrack = Keys.D_MV_TRACK, mvPlayed = Keys.D_MV_PLAYED;
    static int mvExtra = Keys.D_MV_EXTRA;
    static int cometTail = Keys.D_COMET_TAIL, cometTailA = Keys.D_COMET_TAIL_A, cometGlow = Keys.D_COMET_GLOW, cometSize = Keys.D_COMET_SIZE,
            cometPulse = Keys.D_COMET_PULSE, cometSparks = Keys.D_COMET_SPARKS, cometColor = 0xFFFFFFFF, cometFade = Keys.D_COMET_FADE, cometThick = Keys.D_COMET_THICK;
    static boolean cometAuto = Keys.D_COMET_AUTO;
    static int bloomPower = Keys.D_BLOOM_POWER;
    static int frostBlur = Keys.D_FROST_BLUR, frostRefr = Keys.D_FROST_REFR, frostSat = Keys.D_FROST_SAT, frostVeil = Keys.D_FROST_VEIL;
    static boolean pillVib, shadeSheet, sheetBlur, sheetRound;
    static int sheetClock = 60;
    static boolean bodyVib, glassEdge, sheetLockClock;
    static int glassEdgeN = 50;
    static int bodyVibN = 70;
    static boolean groupClear, groupVib;
    static int recColor = 0xFFFF3B30, locColor = 0xFF0A84FF, hotColor = 0xFF34C759;
    static boolean modalFull, lines4, recPill, locPill, hotPill;
    static int linesN = Keys.D_LINES_N;
    static boolean frost, notifGlass, notifBloom, notifVib, mediaCut, cometBar, mediaVibPlus, convTime, convNoExpand, convMiui, mediaEdge, albumGlow = Keys.D_ALBUM_GLOW, mediaPure = Keys.D_MEDIA_PURE, groupStack = Keys.D_GROUP_STACK;
    static int lyricShift = Keys.D_LYRIC_SHIFT;
    static int glowPower = Keys.D_GLOW_POWER, glowSize = Keys.D_GLOW_SIZE, glowDist = Keys.D_GLOW_DIST,
            glowX = Keys.D_GLOW_X, glowY = Keys.D_GLOW_Y, glowTime = Keys.D_GLOW_TIME, glowStyle = Keys.D_GLOW_SWEEP ? 1 : 0;
    static boolean kgHide = Keys.D_KG_HIDE;
    static boolean privOn = Keys.D_PRIV_ON, privUnlocked = Keys.D_PRIV_UNLOCKED;
    static java.util.Set<String> privApps = new java.util.HashSet<>(), privConvs = new java.util.HashSet<>(), privContacts = new java.util.HashSet<>();
    static boolean barOn = Keys.D_BAR_ON, sheetPlayerFree = Keys.D_SHEET_PLAYER_FREE;
    static int barDx = Keys.D_BAR_DX, barDy = Keys.D_BAR_DY, barW = Keys.D_BAR_W, barH = Keys.D_BAR_H, barA = Keys.D_BAR_A;
    static int gsSpeed = Keys.D_GS_SPEED, gsBounce = Keys.D_GS_BOUNCE, gsDepth = Keys.D_GS_DEPTH, gsShadow = Keys.D_GS_SHADOW;

    static {
        defaults();
    }

    private static void defaults() {
        huEnabled = Keys.D_HU_ENABLED;
        huSpeed = Keys.D_HU_SPEED;
        huStyle = Keys.D_HU_STYLE;
        huBounce = Keys.D_HU_BOUNCE;
        huIosSpeed = Keys.D_HU_IOS_SPEED;
        huIosBounce = Keys.D_HU_IOS_BOUNCE;
        huNoActions = Keys.D_HU_NO_ACTIONS;
        huSeparate = Keys.D_HU_SEPARATE;
        stEnabled = Keys.D_ST_ENABLED;
        stOffset = Keys.D_ST_OFFSET;
        stGroups = Keys.D_ST_GROUPS;
        kgEnabled = Keys.D_KG_ENABLED;
        kgScroll = Keys.D_KG_SCROLL;
        kgOffset = Keys.D_KG_OFFSET;
        kgIos = Keys.D_KG_IOS;
        kgStart = Keys.D_KG_START;
        kgClock = Keys.D_KG_CLOCK;
        kgClockFixed = Keys.D_KG_CLOCK_FIXED;
        kgBlur = Keys.D_KG_BLUR;
        kgHmc = Keys.D_KG_HMC;
        kgKeep = Keys.D_KG_KEEP;
        convTime = Keys.D_CONV_TIME;
        convNoExpand = Keys.D_CONV_NO_EXPAND;
        convMiui = Keys.D_CONV_MIUI;
        mediaEdge = Keys.D_MEDIA_EDGE;
        handOver();
    }

    /**
     * Chamar antes de usar os valores; é barato (só relê quando o LSPosed avisa de mudança).
     * Nunca fala com o LSPosed na linha principal: buscar as preferências remotas é uma chamada
     * ao serviço do LSPosed, que pode demorar segundos quando ele está ocupado (logo depois de
     * instalar uma versão nova, por exemplo) - e a Interface do Sistema travada na partida é
     * derrubada pelo Android. A busca vai para uma linha separada; até lá valem os padrões.
     */
    static void refresh() {
        SharedPreferences p = prefs;
        if (p == null) {
            load();
            return;
        }
        if (!dirty) return;
        try {
            dirty = false;
            read();
        } catch (Throwable t) {
            if (!loggedState) {
                loggedState = true;
                ElasticCard.log("ajustes ilegíveis, usando o padrão: " + t);
            }
        }
    }

    /** Busca as preferências remotas fora da linha principal (uma vez; de novo após 5 s se falhar). */
    static void load() {
        if (prefs != null || loading) return;
        if (failedAt != 0L && SystemClock.uptimeMillis() - failedAt < 5000L) return;
        loading = true;
        try {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        SharedPreferences sp = XposedBridge.remotePrefs(Keys.GROUP);
                        if (sp != null) {
                            sp.registerOnSharedPreferenceChangeListener(LISTENER);
                            dirty = true;
                            prefs = sp;
                        } else {
                            failedAt = SystemClock.uptimeMillis();
                        }
                    } catch (Throwable e) {
                        failedAt = SystemClock.uptimeMillis();
                        if (!loggedState) {
                            loggedState = true;
                            ElasticCard.log("ajustes indisponíveis por enquanto, usando o padrão: " + e);
                        }
                    } finally {
                        loading = false;
                    }
                }
            }, "HeadsUpIlha-ajustes");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            loading = false;
            failedAt = SystemClock.uptimeMillis();
        }
    }

    private static void read() {
        SharedPreferences p = prefs;
        try {
            SafeMode.noteLast(p.getString(Keys.SAFE_LAST, ""), p.getLong(Keys.SAFE_AT, 0L));
            SafeMode.notifyApp();
        } catch (Throwable ignored) {
        }
        huEnabled = b(p, Keys.HU_ENABLED, Keys.D_HU_ENABLED);
        huSpeed = clamp(p.getInt(Keys.HU_SPEED, Keys.D_HU_SPEED), 50, 200);
        huStyle = clamp(p.getInt(Keys.HU_STYLE, Keys.D_HU_STYLE), 0, 1);
        huBounce = clamp(p.getInt(Keys.HU_BOUNCE, Keys.D_HU_BOUNCE), 0, 200);
        huIosSpeed = clamp(p.getInt(Keys.HU_IOS_SPEED, Keys.D_HU_IOS_SPEED), 50, 200);
        huIosBounce = clamp(p.getInt(Keys.HU_IOS_BOUNCE, Keys.D_HU_IOS_BOUNCE), 0, 200);
        huNoActions = b(p, Keys.HU_NO_ACTIONS, Keys.D_HU_NO_ACTIONS);
        huHideArrow = b(p, Keys.HU_HIDE_ARROW, Keys.D_HU_HIDE_ARROW);
        huSeparate = b(p, Keys.HU_SEPARATE, Keys.D_HU_SEPARATE);
        stEnabled = b(p, Keys.ST_ENABLED, Keys.D_ST_ENABLED);
        stOffset = clamp(p.getInt(Keys.ST_OFFSET, Keys.D_ST_OFFSET), 0, 400);
        stGroups = b(p, Keys.ST_GROUPS, Keys.D_ST_GROUPS);
        landSplit = b(p, Keys.LAND_SPLIT, Keys.D_LAND_SPLIT);
        lockWidgets = b(p, Keys.LOCK_WIDGETS, Keys.D_LOCK_WIDGETS);
        lwBattery = b(p, Keys.LW_BATTERY, true);
        lwWeather = b(p, Keys.LW_WEATHER, true);
        lwAgenda = b(p, Keys.LW_AGENDA, true);
        lwSteps = b(p, Keys.LW_STEPS, true);
        landMini = b(p, Keys.LAND_MINI, Keys.D_LAND_MINI);
        kgEnabled = b(p, Keys.KG_ENABLED, Keys.D_KG_ENABLED);
        kgScroll = b(p, Keys.KG_SCROLL, Keys.D_KG_SCROLL);
        kgOffset = clamp(p.getInt(Keys.KG_OFFSET, Keys.D_KG_OFFSET), 40, 500);
        kgIos = b(p, Keys.KG_IOS, Keys.D_KG_IOS);
        kgStart = clamp(p.getInt(Keys.KG_START, Keys.D_KG_START), 25, 90);
        kgClock = b(p, Keys.KG_CLOCK, Keys.D_KG_CLOCK);
        kgClockFixed = b(p, Keys.KG_CLOCK_FIXED, Keys.D_KG_CLOCK_FIXED);
        kgBlur = b(p, Keys.KG_BLUR, Keys.D_KG_BLUR);
        kgHmc = b(p, Keys.KG_HMC, Keys.D_KG_HMC);
        kgKeep = b(p, Keys.KG_KEEP, Keys.D_KG_KEEP);
        convTime = b(p, Keys.CONV_TIME, Keys.D_CONV_TIME);
        convNoExpand = b(p, Keys.CONV_NO_EXPAND, Keys.D_CONV_NO_EXPAND);
        convMiui = b(p, Keys.CONV_MIUI, Keys.D_CONV_MIUI);
        mediaEdge = b(p, Keys.MEDIA_EDGE, Keys.D_MEDIA_EDGE);
        mediaCut = b(p, Keys.MEDIA_CUT, Keys.D_MEDIA_CUT);
        cometBar = b(p, Keys.COMET_BAR, Keys.D_COMET_BAR);
        mediaVibPlus = b(p, Keys.MEDIA_VIB_PLUS, Keys.D_MEDIA_VIB_PLUS);
        mvExtra = clamp(p.getInt(Keys.MV_EXTRA, Keys.D_MV_EXTRA), 0, 100);
        notifVib = b(p, Keys.NOTIF_VIB, Keys.D_NOTIF_VIB);
        bodyVib = b(p, Keys.BODY_VIB, false);
        sheetLockClock = b(p, Keys.SHEET_LOCKCLOCK, false);
        glassEdge = false;   // vidro estilo HyperOS 4: retirado
        glassEdgeN = clamp(p.getInt(Keys.GLASS_EDGE_N, 50), 0, 100);
        bodyVibN = clamp(p.getInt(Keys.BODY_VIB_N, 70), 0, 100);
        notifBloom = b(p, Keys.NOTIF_BLOOM, Keys.D_NOTIF_BLOOM);
        modalFull = b(p, Keys.MODAL_FULL, Keys.D_MODAL_FULL);
        lines4 = b(p, Keys.LINES4, Keys.D_LINES4);
        recPill = b(p, Keys.REC_PILL, Keys.D_REC_PILL);
        pillVib = b(p, Keys.PILL_VIB, false);
        shadeSheet = b(p, Keys.SHADE_SHEET, false);
        sheetBlur = b(p, Keys.SHEET_BLUR, true);
        sheetRound = b(p, Keys.SHEET_ROUND, true);
        groupClear = b(p, Keys.GROUP_CLEAR, false);
        groupVib = b(p, Keys.GROUP_VIB, false);
        sheetClock = clamp(p.getInt(Keys.SHEET_CLOCK, 60), 0, 240);
        recColor = p.getInt(Keys.REC_COLOR, 0xFFFF3B30);
        locColor = p.getInt(Keys.LOC_COLOR, 0xFF0A84FF);
        hotColor = p.getInt(Keys.HOT_COLOR, 0xFF34C759);
        locPill = b(p, Keys.LOC_PILL, Keys.D_LOC_PILL);
        hotPill = b(p, Keys.HOT_PILL, Keys.D_HOT_PILL);
        linesN = clamp(p.getInt(Keys.LINES_N, Keys.D_LINES_N), 2, 10);
        notifGlass = b(p, Keys.NOTIF_GLASS, Keys.D_NOTIF_GLASS);
        frost = b(p, Keys.FROST, Keys.D_FROST);
        frostBlur = clamp(p.getInt(Keys.FROST_BLUR, Keys.D_FROST_BLUR), 0, 100);
        frostRefr = clamp(p.getInt(Keys.FROST_REFR, Keys.D_FROST_REFR), 0, 100);
        frostSat = clamp(p.getInt(Keys.FROST_SAT, Keys.D_FROST_SAT), 0, 100);
        frostVeil = clamp(p.getInt(Keys.FROST_VEIL, Keys.D_FROST_VEIL), 0, 100);
        bloomPower = clamp(p.getInt(Keys.BLOOM_POWER, Keys.D_BLOOM_POWER), 0, 300);
        mvText = clamp(p.getInt(Keys.MV_TEXT, Keys.D_MV_TEXT), 0, 100);
        mvTrack = clamp(p.getInt(Keys.MV_TRACK, Keys.D_MV_TRACK), 0, 100);
        mvPlayed = clamp(p.getInt(Keys.MV_PLAYED, Keys.D_MV_PLAYED), 0, 100);
        cometTail = clamp(p.getInt(Keys.COMET_TAIL, Keys.D_COMET_TAIL), 20, 200);
        cometTailA = clamp(p.getInt(Keys.COMET_TAIL_A, Keys.D_COMET_TAIL_A), 0, 100);
        cometGlow = clamp(p.getInt(Keys.COMET_GLOW, Keys.D_COMET_GLOW), 0, 100);
        cometSize = clamp(p.getInt(Keys.COMET_SIZE, Keys.D_COMET_SIZE), 50, 250);
        cometPulse = clamp(p.getInt(Keys.COMET_PULSE, Keys.D_COMET_PULSE), 0, 100);
        cometSparks = clamp(p.getInt(Keys.COMET_SPARKS, Keys.D_COMET_SPARKS), 0, 10);
        cometColor = p.getInt(Keys.COMET_COLOR, 0xFFFFFFFF) | 0xFF000000;
        cometFade = clamp(p.getInt(Keys.COMET_FADE, Keys.D_COMET_FADE), 0, 100);
        cometThick = clamp(p.getInt(Keys.COMET_THICK, Keys.D_COMET_THICK), 0, 10);
        cometAuto = b(p, Keys.COMET_AUTO, Keys.D_COMET_AUTO);
        albumGlow = b(p, Keys.ALBUM_GLOW, Keys.D_ALBUM_GLOW);
        mediaPure = b(p, Keys.MEDIA_PURE, Keys.D_MEDIA_PURE);
        groupStack = b(p, Keys.GROUP_STACK, Keys.D_GROUP_STACK);
        glowPower = clamp(p.getInt(Keys.GLOW_POWER, Keys.D_GLOW_POWER), 10, 300);
        glowSize = clamp(p.getInt(Keys.GLOW_SIZE, Keys.D_GLOW_SIZE), 40, 200);
        glowDist = clamp(p.getInt(Keys.GLOW_DIST, Keys.D_GLOW_DIST), 0, 250);
        glowX = clamp(p.getInt(Keys.GLOW_X, Keys.D_GLOW_X), -150, 150);
        glowY = clamp(p.getInt(Keys.GLOW_Y, Keys.D_GLOW_Y), -100, 150);
        glowTime = clamp(p.getInt(Keys.GLOW_TIME, Keys.D_GLOW_TIME), 50, 250);
        glowStyle = b(p, Keys.GLOW_SWEEP, Keys.D_GLOW_SWEEP) ? 1 : 0;
        kgHide = b(p, Keys.KG_HIDE, Keys.D_KG_HIDE);
        privOn = b(p, Keys.PRIV_ON, Keys.D_PRIV_ON);
        privUnlocked = b(p, Keys.PRIV_UNLOCKED, Keys.D_PRIV_UNLOCKED);
        privApps = lines(p.getString(Keys.PRIV_APPS, ""));
        privConvs = lines(p.getString(Keys.PRIV_CONVS, ""));
        privContacts = lines(p.getString(Keys.PRIV_CONTACTS, ""));
        gsSpeed = clamp(p.getInt(Keys.GS_SPEED, Keys.D_GS_SPEED), 50, 200);
        gsBounce = clamp(p.getInt(Keys.GS_BOUNCE, Keys.D_GS_BOUNCE), 0, 300);
        barOn = b(p, Keys.BAR_ON, Keys.D_BAR_ON);
        sheetPlayerFree = b(p, Keys.SHEET_PLAYER_FREE, Keys.D_SHEET_PLAYER_FREE);
        barDx = clamp(p.getInt(Keys.BAR_DX, Keys.D_BAR_DX), -80, 80);
        barDy = clamp(p.getInt(Keys.BAR_DY, Keys.D_BAR_DY), -30, 60);
        barW = clamp(p.getInt(Keys.BAR_W, Keys.D_BAR_W), 10, 90);
        barH = clamp(p.getInt(Keys.BAR_H, Keys.D_BAR_H), 2, 14);
        barA = clamp(p.getInt(Keys.BAR_A, Keys.D_BAR_A), 10, 100);
        gsDepth = clamp(p.getInt(Keys.GS_DEPTH, Keys.D_GS_DEPTH), 4, 30);
        gsShadow = clamp(p.getInt(Keys.GS_SHADOW, Keys.D_GS_SHADOW), 0, 100);
        lyricShift = clamp(p.getInt(Keys.LYRIC_SHIFT, Keys.D_LYRIC_SHIFT), -20, 30);
        try {
            System.setProperty("hyper_eternity.lyric_shift", String.valueOf(lyricShift));   // para a extensão de música
        } catch (Throwable ignored) {
        }
        handOver();
        ElasticCard.log("ajustes: heads-up " + (huEnabled ? "on" : "off") + (huStyle == 1 ? " iOS" : " OriginOS") + " · OriginOS vel " + huSpeed + "% bounce " + huBounce + "% · iOS vel " + huIosSpeed + "% bounce " + huIosBounce
                + "% botões " + (huNoActions ? "não" : "sim") + " separada " + (huSeparate ? "on" : "off") + " · gaveta " + (stEnabled ? "on" : "off") + " " + stOffset + "dp · grupos " + (stGroups ? "on" : "off")
                + " · bloqueio " + (kgEnabled ? "on" : "off") + " " + kgOffset + "dp rolar " + (kgScroll ? "on" : "off")
                + " ios " + (kgIos ? kgStart + "%" : "off") + " relógio " + (kgClockFixed ? "fixo" : "normal") + (kgClock ? "+rola" : "") + " desfoque " + (kgBlur ? "on" : "off") + (kgHmc ? " · bloqueio com o HyperMusicCover" : "") + (kgKeep ? " · mantém notificações após desbloquear" : "") + " · hora das conversas " + (convTime ? "no canto" : "original") + (convNoExpand ? ", sem a seta" : ", com a seta") + (convMiui ? " · conversas no visual do HyperOS" : "") + (privOn ? " · privacidade " + privApps.size() + " apps " + privConvs.size() + " conversas" + (privUnlocked ? " (também desbloqueado)" : "") : "") + (groupStack ? " · grupos em pilha (vel " + gsSpeed + "% bounce " + gsBounce + "% fundo " + gsDepth + " dp sombra " + gsShadow + "%)" : "") + " · título da música " + (mediaEdge ? "com as pontas desfocadas" : "original") + (albumGlow ? ", capa gira com brilho" : "") + (mediaPure ? ", fundo igual às notificações" : "") + (albumGlow ? " (brilho " + glowPower + "% tam " + glowSize + "% dist " + glowDist + "% x " + glowX + " y " + glowY + " tempo " + glowTime + "%)" : "") + " · letras " + (lyricShift >= 0 ? "+" : "") + (lyricShift / 10.0) + " s");
    }

    /**
     * Bloqueio com o HyperMusicCover: enquanto o player (a capa) está aberto, o relógio é dele
     * (ClockFollow.setCover solta o relógio fixo). O relógio empurrado fica pausado de vez; o
     * relógio fixo, o empilhamento, a Lista baixa, a rolagem e o desfoque continuam valendo para
     * as notificações que ele abre pela ilha.
     */
    private static void handOver() {
        if (!kgHmc) return;
        kgClock = false;
    }

    private static boolean b(SharedPreferences p, String key, boolean def) {
        return SafeMode.apply(key, p.getBoolean(key, def));
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static java.util.Set<String> lines(String s) {
        java.util.HashSet<String> h = new java.util.HashSet<>();
        if (s == null) return h;
        for (String x : s.split("\n")) if (x.length() > 0) h.add(x);
        return h;
    }
}
