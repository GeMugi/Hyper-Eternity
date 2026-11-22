package com.ilha.relogios;

/** Chaves dos ajustes da extensão (o app grava, a Interface do Sistema e o editor leem). */
public final class Keys {
    private Keys() {}

    public static final String PKG = "com.ilha.headsup.relogios";
    public static final String FILE = "ajustes";
    /** Grupo das preferências remotas do LSPosed. */
    public static final String GROUP = "relogios";
    public static final String VERSION = "1.6";

    /** Editor do bloqueio oferece os modelos do HyperOS 4 (All in One). */
    public static final String EDITOR = "editor";
    /** Relógio All in One no bloqueio. */
    public static final String LOCK = "lock";
    /** Relógio All in One na cortina do Hyper Eternity. */
    public static final String CURTAIN = "curtain";
    /** O relógio encolhe e sobe com as notificações, como no HyperOS 4. */
    public static final String FOLLOW = "follow";
    /** Números com vidro (no editor) viram vidro fosco em vez de cor sólida. */
    public static final String FROST = "frost";

    /** Efeito dos números (0 nenhum, 1 papel de parede, 2 duas cores, 3 holográfico, 4 granulado, 5 RGB). */
    public static final String FX = "fx";
    /** Modo de mistura com o fundo desfocado (0 = o do editor). */
    public static final String FX_BLEND = "fx_blend";

    /** Balanço do papel de parede no bloqueio: liga, área (0 imagem inteira, 1 pintada), força (10–100) e a área pintada (grade 36x80 em base64). */
    public static final String WOB = "wob", WOB_AREA = "wob_area", WOB_FORCE = "wob_force", WOB_MASK = "wob_mask";
    public static final String GLOW = "glow", GLOW_FORCE = "glow_force", GLOW_AREA = "glow_area", SUBJ_MASK = "subj_mask";

    public static final String[] ALL = {EDITOR, LOCK, CURTAIN, FOLLOW, FROST, WOB, GLOW};

    /** Última opção ligada e quando (para a proteção contra quedas em sequência). */
    public static final String SAFE_LAST = "safe_last", SAFE_AT = "safe_at";
    public static final String SAFE_ACTION = "com.ilha.headsup.relogios.SAFE_OFF";

    /** Pontes com o Hyper Eternity (mesmo processo, carregadores diferentes): objetos em System.getProperties(). */
    public static final String BRIDGE_BUILD = "hyper.eternity.relogios.build";   // java.util.function.Function<Context, View>
    public static final String BRIDGE_FLOOR = "hyper.eternity.relogios.floor";   // java.util.function.DoubleSupplier
    public static final String BRIDGE_KGTOP = "hyper.eternity.kg.top";           // java.util.function.DoubleSupplier (do Hyper Eternity)
}
