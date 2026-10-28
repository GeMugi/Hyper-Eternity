package com.ilha.headsup.xp;

import android.content.SharedPreferences;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.util.List;

/** Camada de compatibilidade: hookMethod/log do Xposed antigo sobre a API moderna. */
public final class XposedBridge {
    private XposedBridge() {}

    static volatile XposedInterface api;
    private static int errors;

    public static void init(XposedInterface a) {
        if (a != null) api = a;
    }

    public static boolean ready() {
        return api != null;
    }

    public static Object hookMethod(final Member m, final XC_MethodHook cb) {
        XposedInterface a = api;
        if (a == null) throw new IllegalStateException("framework ainda não conectado");
        if (!(m instanceof Executable)) throw new IllegalArgumentException("não é método nem construtor: " + m);
        return a.hook((Executable) m).intercept(new XposedInterface.Hooker() {
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                return run(chain, m, cb);
            }
        });
    }

    static Object run(XposedInterface.Chain chain, Member m, XC_MethodHook cb) throws Throwable {
        XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam();
        p.method = m;
        p.thisObject = chain.getThisObject();
        List<Object> list = chain.getArgs();
        p.args = list == null ? new Object[0] : list.toArray();
        try {
            cb.beforeHookedMethod(p);
        } catch (Throwable t) {
            report(t);
            p.result = null;
            p.throwable = null;
            p.returnEarly = false;
        }
        if (!p.returnEarly) {
            try {
                p.result = chain.proceed(p.args);
            } catch (Throwable t) {
                p.throwable = t;
            }
        }
        Object keepResult = p.result;
        Throwable keepThrowable = p.throwable;
        try {
            cb.afterHookedMethod(p);
        } catch (Throwable t) {
            report(t);
            p.result = keepResult;
            p.throwable = keepThrowable;
        }
        if (p.throwable != null) throw p.throwable;
        return p.result;
    }

    private static void report(Throwable t) {
        if (errors++ < 20) log("[HeadsUpIlha] erro num gancho: " + Log.getStackTraceString(t));
    }

    public static void log(String msg) {
        XposedInterface a = api;
        if (a != null) {
            try {
                a.log(Log.WARN, "HeadsUpIlha", msg);
                return;
            } catch (Throwable ignored) {
            }
        }
        Log.w("HeadsUpIlha", msg);
    }

    /** Ajustes guardados pelo LSPosed (escritos pelo app, lidos aqui na Interface do Sistema). */
    public static SharedPreferences remotePrefs(String group) {
        XposedInterface a = api;
        return a == null ? null : a.getRemotePreferences(group);
    }
}
