package com.ilha.headsup;

/** Chaves e valores padrão dos ajustes, compartilhados entre o app e a Interface do Sistema. */
public final class Keys {
    private Keys() {}

    public static final String PKG = "com.ilha.headsup";
    public static final String FILE = "ajustes";
    /** Grupo das preferências remotas do LSPosed (o app escreve, a Interface do Sistema lê). */
    public static final String GROUP = "ilha";
    public static final String VERSION = "7.9.55";

    public static final String HU_ENABLED = "hu_enabled";
    public static final String HU_STYLE = "hu_style";        // 0 = OriginOS (sai da ilha), 1 = iOS (sai do ícone do app)
    public static final String HU_SPEED = "hu_speed";        // % (50..200)
    public static final String HU_BOUNCE = "hu_bounce";      // % (0..200)
    public static final String HU_IOS_SPEED = "hu_ios_speed";    // % (50..200), animação iOS
    public static final String HU_IOS_BOUNCE = "hu_ios_bounce";  // % (0..200), animação iOS
    public static final String HU_NO_ACTIONS = "hu_no_actions";  // heads-up sem botões
    public static final String HU_SEPARATE = "hu_separate";      // uma heads-up por notificação

    public static final String ST_ENABLED = "st_enabled";
    public static final String ST_OFFSET = "st_offset";      // dp acima do fim da gaveta
    public static final String ST_GROUPS = "st_groups";

    public static final String KG_ENABLED = "kg_enabled";
    public static final String KG_SCROLL = "kg_scroll";
    public static final String KG_OFFSET = "kg_offset";      // dp acima do fim da tela de bloqueio
    public static final String KG_IOS = "kg_ios";            // lista baixa, estilo iOS
    public static final String KG_START = "kg_start";        // % da altura da tela onde a lista começa
    public static final String KG_CLOCK = "kg_clock";        // relógio acompanha a rolagem
    public static final String KG_CLOCK_FIXED = "kg_clock_fixed"; // relógio fica como sem notificações
    public static final String KG_BLUR = "kg_blur";          // desfoque quando passam do topo
    public static final String KG_HMC = "kg_hmc";            // o HyperMusicCover cuida do bloqueio
    public static final String KG_KEEP = "kg_keep";          // notificações continuam no bloqueio após desbloquear
    public static final String CONV_TIME = "conv_time";      // hora das conversas no canto, relativa até 1 hora
    public static final String CONV_NO_EXPAND = "conv_no_expand"; // conversas sem o botão redondo de expandir
    public static final String KG_HIDE = "kg_hide";          // deslizar para baixo esconde as notificações do bloqueio
    public static final String PRIV_ON = "priv_on";              // privacidade por app/conversa
    public static final String PRIV_UNLOCKED = "priv_unlocked";  // esconder também com o celular desbloqueado
    public static final String PRIV_APPS = "priv_apps";          // pacotes, um por linha
    public static final String PRIV_CONVS = "priv_convs";        // "pacote|conversa", um por linha
    public static final String PRIV_CONTACTS = "priv_contacts";  // nomes de contatos, um por linha (minúsculas)
    public static final String PRIV_SEEN = "priv_seen";          // conversas vistas: "pacote|id|nome"
    public static final String CONV_SENDER = "conv_sender";  // grupos: só o nome de quem mandou
    public static final String CONV_SPLIT = "conv_split";    // cada mensagem num cartão novo (no sistema)
    public static final String CONV_MIUI = "conv_miui";      // conversas montadas no modelo do HyperOS
    public static final String MEDIA_CUT = "media_cut";
    public static final String MEDIA_VIB_PLUS = "media_vib_plus", MV_EXTRA = "mv_extra";   // vibrance no artista e nos botões de pular
    public static final String COMET_BAR = "comet_bar";      // cometa na ponta da barra do player
    public static final String NOTIF_VIB = "notif_vib", BODY_VIB = "body_vib", BODY_VIB_N = "body_vib_n", GLASS_EDGE = "glass_edge", GLASS_EDGE_N = "glass_edge_n";
    public static final String NOTIF_GLASS = "notif_glass";
    public static final String FROST = "frost", FROST_BLUR = "frost_blur", FROST_REFR = "frost_refr", FROST_SAT = "frost_sat", FROST_VEIL = "frost_veil";
    public static final String NOTIF_BLOOM = "notif_bloom", BLOOM_POWER = "bloom_power";      // hora das notificações com vibrancy
    public static final String MV_TEXT = "mv_text", MV_TRACK = "mv_track", MV_PLAYED = "mv_played";      // barra e tempos do player recortam o fundo
    public static final String MEDIA_EDGE = "media_edge";    // título do player da gaveta com as pontas desfocadas
    public static final String MEDIA_PURE = "media_pure_blur";  // fundo do player só com desfoque, sem cor
    public static final String GS_SPEED = "gs_speed";        // velocidade de abrir/fechar a pilha, %
    public static final String GS_BOUNCE = "gs_bounce";      // bounce da pilha, %
    public static final String GS_DEPTH = "gs_depth";        // quanto os cartões de trás aparecem, dp
    public static final String GS_SHADOW = "gs_shadow";      // sombra entre os cartões, %
    public static final String GROUP_STACK = "group_stack";  // notificações do mesmo app em pilha
    public static final String ALBUM_GLOW = "album_glow";
    public static final String GLOW_POWER = "glow_power";    // intensidade do brilho da capa, %
    public static final String GLOW_SIZE = "glow_size";      // tamanho, %
    public static final String GLOW_DIST = "glow_dist";      // quanto desce, %
    public static final String GLOW_X = "glow_x";            // deslocamento lateral, % da capa
    public static final String GLOW_Y = "glow_y";            // deslocamento vertical, % da capa
    public static final String GLOW_TIME = "glow_time";      // duração, %    // capa gira e solta um brilho ao trocar de música
    public static final String LYRIC_SHIFT = "lyric_shift";  // letras do bloqueio adiantadas, em décimos de segundo

    public static final boolean D_HU_ENABLED = false;
    public static final int D_HU_STYLE = 0;
    public static final int D_HU_SPEED = 100;
    public static final int D_HU_BOUNCE = 100;
    public static final int D_HU_IOS_SPEED = 100;
    public static final int D_HU_IOS_BOUNCE = 100;
    public static final boolean D_HU_NO_ACTIONS = false;
    public static final boolean D_HU_SEPARATE = false;
    public static final boolean D_ST_ENABLED = false;
    public static final int D_ST_OFFSET = 72;
    public static final boolean D_ST_GROUPS = false;
    public static final boolean D_KG_ENABLED = false;
    public static final boolean D_KG_SCROLL = false;
    public static final int D_KG_OFFSET = 150;
    public static final boolean D_KG_IOS = false;
    public static final int D_KG_START = 58;
    public static final boolean D_KG_CLOCK = false;
    public static final boolean D_KG_CLOCK_FIXED = false;
    public static final boolean D_KG_BLUR = false;
    public static final boolean D_KG_HMC = false;
    public static final boolean D_KG_KEEP = false;
    public static final boolean D_CONV_TIME = false;
    public static final boolean D_CONV_NO_EXPAND = false;
    public static final boolean D_CONV_MIUI = false;
    public static final boolean D_CONV_SPLIT = false;
    public static final boolean D_CONV_SENDER = false;
    public static final boolean D_PRIV_ON = false;
    public static final boolean D_PRIV_UNLOCKED = false;
    public static final boolean D_KG_HIDE = false;
    public static final boolean D_MEDIA_EDGE = false;
    public static final boolean D_MEDIA_CUT = false;
    public static final boolean D_COMET_BAR = false;
    public static final boolean D_MEDIA_VIB_PLUS = false;
    public static final int D_MV_EXTRA = 70;
    public static final boolean D_NOTIF_VIB = false;
    public static final boolean D_NOTIF_BLOOM = false;
    public static final String MODAL_FULL = "modal_full", LINES4 = "lines4";
    public static final boolean D_LINES4 = false;
    public static final String LINES_N = "lines_n", REC_PILL = "rec_pill", LOC_PILL = "loc_pill", HOT_PILL = "hot_pill";
    public static final String PILL_VIB = "pill_vib";
    public static final String SHADE_SHEET = "shade_sheet", SHEET_BLUR = "sheet_blur", SHEET_ROUND = "sheet_round", SHEET_CLOCK = "sheet_clock", SHEET_LOCKCLOCK = "sheet_lockclock", GROUP_CLEAR = "group_clear", GROUP_VIB = "group_vib";
    public static final String REC_COLOR = "rec_color", LOC_COLOR = "loc_color", HOT_COLOR = "hot_color";
    public static final boolean D_REC_PILL = false, D_LOC_PILL = false, D_HOT_PILL = false;
    public static final int D_LINES_N = 4;
    public static final boolean D_MODAL_FULL = false;
    public static final boolean D_NOTIF_GLASS = false;
    public static final boolean D_FROST = false;
    public static final int D_FROST_BLUR = 60, D_FROST_REFR = 50, D_FROST_SAT = 40, D_FROST_VEIL = 30;
    public static final int D_BLOOM_POWER = 100;
    public static final int D_MV_TEXT = 45, D_MV_TRACK = 22, D_MV_PLAYED = 85;
    // cometa: rastro (dp e %), brilho (%), tamanho da cabeça (%), pulso (%), faíscas (quantas) e cor
    public static final String COMET_TAIL = "comet_tail", COMET_TAIL_A = "comet_tail_a", COMET_GLOW = "comet_glow", COMET_SIZE = "comet_size",
            COMET_PULSE = "comet_pulse", COMET_SPARKS = "comet_sparks", COMET_COLOR = "comet_color", COMET_FADE = "comet_fade", COMET_THICK = "comet_thick", COMET_AUTO = "comet_auto";
    public static final int D_COMET_TAIL = 60, D_COMET_TAIL_A = 80, D_COMET_GLOW = 60, D_COMET_SIZE = 100, D_COMET_PULSE = 0, D_COMET_SPARKS = 0, D_COMET_FADE = 15, D_COMET_THICK = 0;
    public static final boolean D_COMET_AUTO = true;
    public static final int D_LYRIC_SHIFT = 0;
    public static final boolean D_ALBUM_GLOW = false;
    public static final boolean D_GROUP_STACK = false;
    public static final int D_GS_SPEED = 100;
    public static final int D_GS_BOUNCE = 100;
    public static final int D_GS_DEPTH = 12;
    public static final int D_GS_SHADOW = 40;
    public static final boolean D_MEDIA_PURE = false;
    public static final int D_GLOW_POWER = 80;
    public static final int D_GLOW_SIZE = 100;
    public static final int D_GLOW_DIST = 100;
    public static final int D_GLOW_X = 0;
    public static final int D_GLOW_Y = 0;
    public static final int D_GLOW_TIME = 100;
    public static final String GLOW_SWEEP = "glow_sweep";   // brilho em faixa de luz (ColorOS 17) em vez da mancha da capa
    public static final boolean D_GLOW_SWEEP = true;

    // modo de segurança
    public static final String SAFE_LAST = "safe_last";      // última opção ligada
    public static final String SAFE_AT = "safe_at";          // quando
    public static final String SAFE_MSG = "safe_msg";        // opção que o modo de segurança desligou
    public static final String SAFE_MSG_AT = "safe_msg_at";

    /** Chaves de liga/desliga (as que o modo de segurança pode desligar). */
    public static boolean isOption(String key) {
        try {
            for (java.lang.reflect.Field f : Keys.class.getFields()) {
                if (f.getType() == String.class && !f.getName().startsWith("SAFE") && key.equals(f.get(null))) {
                    java.lang.reflect.Field d = Keys.class.getField("D_" + f.getName());
                    return d.getType() == boolean.class;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
