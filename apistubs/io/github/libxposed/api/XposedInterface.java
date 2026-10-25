package io.github.libxposed.api;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import java.lang.reflect.Executable;
import java.util.List;

/** Só para compilar: a implementação real vem do framework (libxposed API 102). */
public interface XposedInterface {
    HookBuilder hook(Executable origin);
    void log(int priority, String tag, String msg);
    SharedPreferences getRemotePreferences(String group);
    ApplicationInfo getModuleApplicationInfo();
    boolean deoptimize(Executable executable);

    interface Chain {
        Object proceed() throws Throwable;
        Object proceed(Object[] args) throws Throwable;
        Object getThisObject();
        List<Object> getArgs();
        Executable getExecutable();
    }

    interface Hooker {
        Object intercept(Chain chain) throws Throwable;
    }

    interface HookBuilder {
        HookHandle intercept(Hooker hooker);
    }

    interface HookHandle {
    }
}
