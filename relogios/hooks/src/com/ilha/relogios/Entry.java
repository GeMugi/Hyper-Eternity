package com.ilha.relogios;

import android.os.Build;
import com.ilha.relogios.xp.XposedBridge;
import io.github.libxposed.api.XposedModule;

/**
 * Entrada da extensão no LSPosed (API moderna). Age em dois lugares:
 *  - no app da tela sempre ativa (com.miui.aod) e no editor do bloqueio, que roda num processo dele:
 *    os modelos do HyperOS 4 aparecem no editor e as fontes deles funcionam;
 *  - na Interface do Sistema: o relógio All in One no bloqueio e, pela ponte, na cortina do Hyper Eternity.
 */
public class Entry extends XposedModule {
    public Entry() {
        super();
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        XposedBridge.init(this);
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        XposedBridge.init(this);
        try {
            String pk = param.getPackageName();
            String proc = android.app.Application.getProcessName();
            if (!"com.miui.aod".equals(pk) && !"com.android.systemui".equals(pk)) return;
            // trava: no HyperOS 4 tudo isto já é nativo; mexer só traria conflito
            if (Hos.isHos4() && "com.android.systemui".equals(pk) && (proc == null || proc.equals(pk)))
                XposedBridge.log("[Relógios HOS4] HyperOS 4 detectado (" + Hos.why + "): a extensão continua ativa (sem trava)");
            if ("com.miui.aod".equals(pk)) {
                if (proc == null || !(proc.equals(pk) || proc.equals(pk + ":keyguardeditor"))) return;
                Cfg.loadNow();   // o editor decide os modelos na abertura: os ajustes precisam estar aqui antes
                Aod.install(param.getDefaultClassLoader());
                return;
            }
            if (!"com.android.systemui".equals(pk) || (proc != null && !proc.equals(pk))) return;
            if (!claim()) return;
            XposedBridge.log("[Relógios HOS4] extensão " + Keys.VERSION + " carregada · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            Safe.boot();
            Cfg.loadLater();
            Clocks.install(param.getDefaultClassLoader());
            // avisa o app se a proteção desligou alguma opção (o contexto do app só existe depois)
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                public void run() { Safe.notifyApp(); }
            }, 30000L);
        } catch (Throwable t) {
            XposedBridge.log("[Relógios HOS4] ERRO na entrada: " + t);
        }
    }

    /** Só uma cópia da extensão age por processo. */
    private static boolean claim() {
        String k = "hyper.eternity.owner.relogios";
        synchronized (System.class) {
            String v = System.getProperty(k);
            if (v != null && !v.equals(Keys.PKG)) return false;
            System.setProperty(k, Keys.PKG);
            return true;
        }
    }
}
