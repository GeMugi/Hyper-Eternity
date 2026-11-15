package com.ilha.headsup;

import android.os.Build;
import java.lang.reflect.Method;

/** Instala os ganchos do Heads-up Ilha na Interface do Sistema (chamado pela ModernMain). */
public final class Main {
    private Main() {}

    private static boolean loaded;

    static synchronized void load(ClassLoader cl) {
        if (loaded) return;
        loaded = true;
        Prefs.load();   // busca os ajustes já, numa linha separada
        try {
            ElasticCard.log("Hyper Eternity " + Keys.VERSION + " carregado (API moderna) · Android " + Build.VERSION.RELEASE
                    + " (API " + Build.VERSION.SDK_INT + ") · " + prop("ro.mi.os.version.name")
                    + " " + prop("ro.mi.os.version.incremental"));
            SafeMode.boot();
            ElasticCard.install(cl);
            StackHooks.install(cl);
            IslandOpen.install();   // abrir/fechar a ilha como no HyperOS 4 (opção, desligada por padrão)
            HeadsUpTweaks.install(cl);
            ClockFollow.install(cl);
            StatusClock.install(cl);
            KeepLock.install(cl);
            ConvTime.install(cl);
            ConvMiui.install(cl);
            EdgeBlur.install(cl);
            MediaCut.install(cl);
            AlbumGlow.install(cl);
            PureBlur.install(cl);
            Privacy.install(cl);
            HighEnd.install(cl);
            Ext.publish();   // relógios do HyperOS 4: ficam na extensão Hyper Eternity Relógios
            ModalFull.install(cl);
            Lines4.install(cl);
            RecPill.install(cl);
            ShadeSheet.install(cl);
            IslandHide.install();
            IosPlayer.install();
            Explicit.install(cl);
            HmcFix.install(cl);
            OriginPlayer.install(cl);
            LyricVideo.install(cl);
            ClockDots.install(cl);
            LockWidgets.install(cl);
            // TESTE (remover antes do lançamento): diagnóstico do desfoque com heads-up
            try {
                // diagnóstico do desfoque desligado: enchia o registro
                // Class.forName("com.ilha.headsup.BlurProbe").getMethod("install", ClassLoader.class).invoke(null, cl);
            } catch (RuntimeException ignored) {
            }
        GroupStack.install(cl);
            if (!MiBlend.v4()) Caps.off(Keys.HU_HIDE_ARROW, "só no HyperOS 4");
            Caps.schedule();   // avisa o app mesmo sem nada faltando (lista em dia)
        } catch (Throwable t) {
            ElasticCard.log("ERRO no carregamento: " + t);
        }
    }

    static String prop(String key) {
        try {
            Class c = Class.forName("android.os.SystemProperties");
            Method m = c.getMethod("get", String.class);
            Object v = m.invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
