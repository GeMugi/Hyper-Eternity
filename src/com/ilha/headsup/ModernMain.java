package com.ilha.headsup;

import com.ilha.headsup.xp.XposedBridge;
import io.github.libxposed.api.XposedModule;

/**
 * Entrada do módulo na API moderna do LSPosed (a mesma do HyperMusicCover, que divide este APK).
 * Fica em META-INF/xposed/java_init.list ao lado de com.os4.musiccover.Main; as duas recebem os
 * mesmos pacotes e cada uma cuida do que é seu. Esta só age na Interface do Sistema.
 */
public class ModernMain extends XposedModule {
    public ModernMain() {
        super();
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        XposedBridge.init(this);
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        XposedBridge.init(this);
        if (!claim("system")) return;
        try {
            GroupAuto.install(param.getClassLoader());
            ConvSplit.install(param.getClassLoader());
            FullText.install("sistema");
        } catch (Throwable t) {
            XposedBridge.log("[HeadsUpIlha] ERRO no sistema: " + t);
        }
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        XposedBridge.init(this);
        try {
            String pk = param.getPackageName();
            if (!"com.android.systemui".equals(pk)) return;
            if (!claim("systemui")) return;
            FullText.install("SystemUI");
            Main.load(param.getDefaultClassLoader());
        } catch (Throwable t) {
            XposedBridge.log("[HeadsUpIlha] ERRO na entrada: " + t);
        }
    }

    /**
     * O Hyper Eternity e o Hyper Eternity miuix têm os mesmos hooks. Se os dois estiverem ativos,
     * só o primeiro que carregar no processo age (a propriedade do System é a mesma para os dois).
     */
    private static boolean claim(String where) {
        String k = "hyper.eternity.owner." + where;
        synchronized (System.class) {
            String v = System.getProperty(k);
            if (v != null && !v.equals(Keys.PKG)) {
                XposedBridge.log("[HeadsUpIlha] " + Keys.PKG + " ignorado em " + where + ": " + v + " já está ativo");
                return false;
            }
            System.setProperty(k, Keys.PKG);
            return true;
        }
    }
}
