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
            ModalFull.install(cl);
            Lines4.install(cl);
            RecPill.install(cl);
            ShadeSheet.install(cl);
        GroupStack.install(cl);
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
