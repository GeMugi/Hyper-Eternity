package io.github.libxposed.api;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import java.lang.reflect.Executable;

/** Só para compilar: a classe real (do framework) repassa tudo à implementação do LSPosed. */
public abstract class XposedModule implements XposedInterface, XposedModuleInterface {
    public XposedModule() {
    }

    public HookBuilder hook(Executable origin) { throw new RuntimeException("stub"); }
    public void log(int priority, String tag, String msg) { throw new RuntimeException("stub"); }
    public SharedPreferences getRemotePreferences(String group) { throw new RuntimeException("stub"); }
    public ApplicationInfo getModuleApplicationInfo() { throw new RuntimeException("stub"); }
    public boolean deoptimize(Executable executable) { throw new RuntimeException("stub"); }

    public void onModuleLoaded(ModuleLoadedParam param) {
    }

    public void onPackageLoaded(PackageLoadedParam param) {
    }
}
