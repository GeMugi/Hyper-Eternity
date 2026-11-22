package com.ilha.relogios;

import com.ilha.relogios.xp.XC_MethodHook;
import com.ilha.relogios.xp.XposedBridge;

import java.lang.reflect.Method;

/**
 * Editor do bloqueio do HyperOS 4 (com.miui.aod) num HyperOS 3.
 * 1) Ele só oferece os relógios novos (All in One, redimensionáveis) quando o sistema diz ser o
 *    HyperOS 4: só dentro desse app, a versão lida pelas propriedades passa a ser 4.
 * 2) Os modelos novos vêm com papéis de parede do HyperOS 4 (/product/media/wallpaper/...), que o
 *    HyperOS 3 não tem; sem eles o editor esconde o modelo. Sem o arquivo, usa um papel de parede de
 *    modelo que o aparelho tem (dá para trocar depois no editor).
 */
final class Aod {
    private Aod() {}

    static int logs, varLogs;
    static java.util.List<String> fallbacks;

    static void log(String m) {
        if (logs++ < 30) XposedBridge.log("[Relógios HOS4] " + m);
    }

    /** Os modelos novos só aparecem com o editor ligado; uma vez escolhidos, as fontes valem onde o relógio for desenhado. */
    static boolean active() {
        return Cfg.editor || Cfg.lock || Cfg.curtain;
    }

    static void install(ClassLoader cl) {
        version();
        wallpapers(cl);
        fonts(cl, null);
    }

    static final java.util.Map<String, String> ASSET = new java.util.HashMap<>();
    static {
        ASSET.put("miclock-mi-sans-rcf", "MiSansRCFVF.ttf");
        ASSET.put("miclock-mi-sans-roundedf", "MiSansRoundedFVF.ttf");
        ASSET.put("miclock-mi-sans-squaref", "MiSansSquareFVF.ttf");
        ASSET.put("miclock-mi-serif", "MiSerifFVF.ttf");
        ASSET.put("miclock-mi-haus", "MiHausVF.ttf");
    }

    /**
     * As fontes dos relógios All in One ficam no sistema do HyperOS 4 (/product/fonts/MiSansRCF.ttf…);
     * no HyperOS 3 elas não existem e o relógio caía na fonte padrão. O próprio app traz as mesmas
     * fontes (só os números, com todos os pesos) nos recursos: sem o arquivo do sistema, usa elas.
     * "assets" nulo: as do próprio processo (o app da tela sempre ativa).
     */
    static volatile boolean nativeVar;

    /**
     * O motor de texto do sistema só aplica a variação "por cima" (Paint.setFontVariationOverride)
     * se a chave nativa typeface_redesign_readonly estiver ligada. Ela é uma função minúscula em
     * aconfig_text_flags_c_lib.so que devolve 0 ou 1: lê o arquivo e vê o que ela devolve.
     */
    static boolean nativeVarOn() {
        LibPatch p = LibPatch.byId("fonte_variavel");
        return p != null && p.scan(LibModule.read(p.path)).state == LibPatch.Scan.ON;
    }

    static final android.util.LruCache<String, android.graphics.Typeface> varCache = new android.util.LruCache<String, android.graphics.Typeface>(400);
    static final java.util.WeakHashMap<android.graphics.Typeface, android.graphics.Typeface> varRoot = new java.util.WeakHashMap<android.graphics.Typeface, android.graphics.Typeface>();

    static final java.util.Map<ClassLoader, Boolean> fontLoaders = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<ClassLoader, Boolean>());

    /**
     * A tela sempre ativa é um plugin carregado dentro do SystemUI, com um carregador de classes só
     * dele (não o do app, nem o que a extensão abre). Nele o relógio All in One da tela sempre ativa
     * já é montado pelo próprio HOS4, mas sem os nossos ajustes: as fontes caíam na padrão (o método
     * do Android do HOS4 Font.setFontPathMatched não existe aqui) e o tamanho livre ficava desligado.
     * Assim que esse carregador nasce, recebe os mesmos ajustes que o relógio do bloqueio.
     */
    static void plugin() {
        try {
            XC_MethodHook h = new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!(p.args.length > 0 && p.args[0] instanceof String) || !((String) p.args[0]).contains("com.miui.aod")) return;
                    final ClassLoader cl = (ClassLoader) p.thisObject;
                    try {
                        Class.forName("com.miui.clock.utils.FontUtils", false, cl);
                    } catch (Throwable t) {
                        return;
                    }
                    if (fontLoaders.containsKey(cl)) return;
                    android.content.res.AssetManager am = null;
                    try {
                        android.app.Application app = (android.app.Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
                        am = app.createPackageContext("com.miui.aod", 0).getAssets();
                    } catch (Throwable t) { }
                    log("tela sempre ativa (plugin no SystemUI): ajustes do relógio All in One aplicados");
                    fonts(cl, am);
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                        public void run() {
                            StringBuilder b = new StringBuilder();
                            Clocks.flip(b, "com.miui.clock.utils.DeviceConfig", cl, "COMPUTILITY_V2_LEVEL", 4);
                            log("tela sempre ativa: nível do aparelho " + b);
                        }
                    });
                }
            };
            int n = 0;
            for (java.lang.reflect.Constructor<?> c : dalvik.system.PathClassLoader.class.getDeclaredConstructors()) {
                XposedBridge.hookMethod(c, h);
                n++;
            }
            for (java.lang.reflect.Constructor<?> c : dalvik.system.DexClassLoader.class.getDeclaredConstructors()) {
                XposedBridge.hookMethod(c, h);
                n++;
            }
            log("tela sempre ativa: aguardando o plugin (" + n + " ganchos)");
        } catch (Throwable t) {
            log("tela sempre ativa: " + t);
        }
    }

    static void fonts(ClassLoader cl, final android.content.res.AssetManager assets) {
        if (cl != null) {
            if (fontLoaders.containsKey(cl)) return;
            fontLoaders.put(cl, Boolean.TRUE);
        }
        try {
            Class<?> fu = Class.forName("com.miui.clock.utils.FontUtils", false, cl);
            XposedBridge.hookMethod(fu.getDeclaredMethod("createTypefaceWithBypassCache", String.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!active()) return;
                    String key = (String) p.args[0];
                    String file = ASSET.get(key);
                    if (file == null) return;
                    try {
                        Class<?> f = Class.forName("com.miui.clock.utils.FontUtils", false, p.method.getDeclaringClass().getClassLoader());
                        Object sys = f.getMethod("findSystemFontFile", String.class).invoke(null, key);
                        android.graphics.fonts.Font font;
                        if (sys instanceof java.io.File) {
                            // fonte do HOS4 no sistema: monta sem o ajuste interno que só o framework do HOS4 tem
                            font = new android.graphics.fonts.Font.Builder((java.io.File) sys).build();
                        } else {
                            android.content.res.AssetManager am = assets;
                            if (am == null) {
                                android.app.Application app = (android.app.Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
                                am = app.getAssets();
                            }
                            font = new android.graphics.fonts.Font.Builder(am, file).build();
                        }
                        android.graphics.fonts.FontFamily.Builder fb = new android.graphics.fonts.FontFamily.Builder(font);
                        android.graphics.fonts.FontFamily fam;
                        try {
                            fam = (android.graphics.fonts.FontFamily) fb.getClass().getMethod("buildVariableFamily").invoke(fb);
                        } catch (Throwable e) {
                            fam = fb.build();
                        }
                        if (fam == null) fam = new android.graphics.fonts.FontFamily.Builder(font).build();
                        p.setResult(new android.graphics.Typeface.CustomFallbackBuilder(fam).build());
                        log("fonte " + key + ": " + (sys != null ? "do sistema (" + sys + ", eixos " + axes((java.io.File) sys) + ")" : "dos recursos do app (" + file + ")"));
                    } catch (Throwable t) {
                        log("fonte " + key + ": " + t);
                    }
                }
            });
            // espessura, largura e altura da fonte: o HOS4 aplica por um método do Android dele
            // (Paint.setFontVariationOverride) que o HOS3 não tem; sem ele nada mudava (nem negrito/fino,
            // nem o encaixe na caixa). Aqui vai pelo método público.
            try {
                Class<?> tm = Class.forName("com.miui.clock.allInOne.TextMeasureUtils", false, cl);
                final boolean has = hasOverride();
                nativeVar = has && nativeVarOn();
                if (nativeVar) {
                    // sistema com a variação de fonte nativa ligada (chave typeface_redesign_readonly):
                    // a biblioteca do HOS4 usa o caminho rápido dela, sem a nossa troca
                    log("variação da fonte: nativa do sistema (caminho rápido do HOS4)");
                } else
                XposedBridge.hookMethod(tm.getDeclaredMethod("applyFontVariation", android.graphics.Paint.class, float.class, float.class, float.class), new XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (!active()) return;
                        android.graphics.Paint paint = (android.graphics.Paint) p.args[0];
                        // Cada variação nova cria uma fonte nova, e o sistema refaz todos os contornos dos
                        // números para ela (caro: o encaixe do relógio testa várias por redesenho). As
                        // variações são arredondadas e as fontes prontas ficam guardadas: voltar a um
                        // tamanho já usado (com/sem notificações) não refaz nada.
                        float wg = Math.round(((Number) p.args[1]).floatValue());
                        float wd = Math.round(((Number) p.args[2]).floatValue() * 2f) / 2f;
                        float hg = Math.round(((Number) p.args[3]).floatValue() * 2f) / 2f;
                        String v = "'wght' " + wg + ", 'wdth' " + wd + ", 'HGHT' " + hg;
                        android.graphics.Typeface cur = paint.getTypeface();
                        android.graphics.Typeface root = cur == null ? null : varRoot.get(cur);
                        if (root == null) root = cur;
                        String key = root == null ? null : System.identityHashCode(root) + "|" + v;
                        android.graphics.Typeface t = key == null ? null : varCache.get(key);
                        if (t != null) {
                            if (cur != t) paint.setTypeface(t);
                        } else {
                            if (cur != root) paint.setTypeface(root);
                            boolean ok = paint.setFontVariationSettings(v);
                            if (!ok && varLogs++ < 5) log("variação recusada pela fonte (" + v + ")");
                            t = paint.getTypeface();
                            if (ok && key != null && t != null && t != root) {
                                varCache.put(key, t);
                                varRoot.put(t, root);
                            }
                        }
                        p.setResult(null);
                    }
                });
                // mesmo com o método da Xiaomi presente (o HOS3 tem um parecido), ele só vale para fontes
                // registradas pelo sistema do HOS4: vai sempre pelo método público
                if (!nativeVar) log("variação da fonte: pelo método público" + (has ? " (o método da Xiaomi existe, mas a chave nativa do sistema está desligada)" : ""));
            } catch (Throwable t) {
                log("variação da fonte: " + t);
            }
            log("fontes dos relógios novos preparadas");
        } catch (Throwable t) {
            log("fontes: " + t);
        }
    }

    static void version() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            XC_MethodHook h = new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!Cfg.editor || !(p.args[0] instanceof String)) return;
                    String k = (String) p.args[0];
                    Object r = p.getResult();
                    if (k.equals("ro.mi.os.version.code")) {
                        if (r instanceof Integer && (Integer) r < 4) p.setResult(4);
                        else if (r instanceof String && (((String) r).isEmpty() || ((String) r).compareTo("4") < 0)) p.setResult("4");
                    } else if (k.equals("persist.sys.computilityV2.devicelevel")) {
                        // o redimensionar (fonte variável) só liga em aparelhos de nível alto
                        if (r instanceof Integer && (Integer) r < 4) p.setResult(4);
                        else if (r instanceof String && (((String) r).isEmpty() || ((String) r).compareTo("4") < 0)) p.setResult("4");
                    } else if (k.equals("ro.mi.os.version.name")) {
                        if (r instanceof String && ((String) r).startsWith("OS3")) p.setResult("OS4" + ((String) r).substring(3));
                    }
                }
            };
            int n = 0;
            for (Method m : sp.getDeclaredMethods()) {
                if ((m.getName().equals("get") || m.getName().equals("getInt")) && m.getParameterTypes().length >= 1
                        && m.getParameterTypes()[0] == String.class) {
                    XposedBridge.hookMethod(m, h);
                    n++;
                }
            }
            log("editor preparado (" + n + " ganchos, processo " + android.app.Application.getProcessName() + ")");
        } catch (Throwable t) {
            log("versão: " + t);
        }
    }

    static void wallpapers(ClassLoader cl) {
        try {
            Class<?> c = Class.forName("com.miui.keyguard.editor.data.template.TemplateFilePathGenerator$Companion", false, cl);
            Method m = c.getDeclaredMethod("getPresetWallpaperAbsolutePath", String.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!Cfg.editor) return;
                    Object r = p.getResult();
                    if (!(r instanceof String) || new java.io.File("/" + r).exists() || new java.io.File((String) r).exists()) return;
                    java.util.List<String> f = fallbacks();
                    if (f.isEmpty()) return;
                    String rel = String.valueOf(p.args[0]);
                    String pick = f.get(Math.abs(rel.hashCode()) % f.size());
                    p.setResult(pick);
                    log("papel de parede " + rel + " ausente, usando " + pick);
                }
            });
            log("papéis de parede dos modelos com substituto (" + fallbacks().size() + " disponíveis)");
        } catch (Throwable t) {
            log("papéis de parede: " + t);
        }
    }

    /** Eixos de variação da fonte (tabela fvar), para conferir se ela tem largura e altura. */
    static String axes(java.io.File f) {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            r.seek(4);
            int n = r.readUnsignedShort();
            r.seek(12);
            long off = -1;
            for (int i = 0; i < n; i++) {
                byte[] tag = new byte[4];
                r.readFully(tag);
                r.readInt();
                long o = r.readInt() & 0xffffffffL;
                r.readInt();
                if (new String(tag, "ISO-8859-1").equals("fvar")) off = o;
            }
            if (off < 0) return "nenhum";
            r.seek(off + 4);
            int axesOff = r.readUnsignedShort();
            r.readUnsignedShort();
            int count = r.readUnsignedShort();
            int size = r.readUnsignedShort();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < count; i++) {
                r.seek(off + axesOff + (long) i * size);
                byte[] tag = new byte[4];
                r.readFully(tag);
                float mn = r.readInt() / 65536f, df = r.readInt() / 65536f, mx = r.readInt() / 65536f;
                b.append(new String(tag, "ISO-8859-1")).append(' ').append(mn).append('-').append(mx).append(' ');
            }
            return b.toString().trim();
        } catch (Throwable t) {
            return "? " + t;
        }
    }

    static boolean hasOverride() {
        for (Method m : android.graphics.Paint.class.getDeclaredMethods()) {
            String n = m.getName();
            if (n.equals("setFontVariationOverride") || n.equals("setCustomVariationOverride")) return true;
        }
        return false;
    }

    static java.util.List<String> fallbacks() {
        if (fallbacks != null) return fallbacks;
        java.util.ArrayList<String> l = new java.util.ArrayList<>();
        collect(new java.io.File("/product/media/wallpaper/sysui_editor_wallpaper_group"), l, 0);
        if (l.isEmpty()) collect(new java.io.File("/product/media/wallpaper"), l, 0);
        java.util.Collections.sort(l);
        return fallbacks = l;
    }

    static void collect(java.io.File d, java.util.List<String> out, int depth) {
        java.io.File[] fs = d.listFiles();
        if (fs == null || depth > 3) return;
        for (java.io.File f : fs) {
            if (f.isDirectory()) collect(f, out, depth + 1);
            else {
                String n = f.getName().toLowerCase();
                if ((n.endsWith(".jpg") || n.endsWith(".png") || n.endsWith(".webp")) && !n.contains("thumb"))
                    out.add(f.getAbsolutePath().substring(1));   // relativo à raiz, como o editor usa
            }
        }
    }
}
