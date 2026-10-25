package io.github.libxposed.api;

import android.content.pm.ApplicationInfo;

/** Só para compilar. */
public interface XposedModuleInterface {
    interface ModuleLoadedParam {
    }

    interface PackageLoadedParam {
        String getPackageName();
        ClassLoader getDefaultClassLoader();
    }

    interface SystemServerStartingParam {
        ClassLoader getClassLoader();
    }

    void onModuleLoaded(ModuleLoadedParam param);

    default void onSystemServerStarting(SystemServerStartingParam param) {
    }

    void onPackageLoaded(PackageLoadedParam param);
}
