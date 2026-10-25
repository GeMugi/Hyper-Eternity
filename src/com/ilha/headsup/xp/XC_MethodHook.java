package com.ilha.headsup.xp;

import java.lang.reflect.Member;

/**
 * Camada de compatibilidade: o mesmo formato de gancho do Xposed antigo (antes/depois), rodando
 * sobre a API moderna (libxposed 102), que é a usada pelo HyperMusicCover no mesmo APK.
 */
public abstract class XC_MethodHook {
    public XC_MethodHook() {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        Object result;
        Throwable throwable;
        boolean returnEarly;
        private java.util.HashMap<String, Object> extra;

        public void setObjectExtra(String key, Object o) {
            if (extra == null) extra = new java.util.HashMap<String, Object>(4);
            extra.put(key, o);
        }

        public Object getObjectExtra(String key) {
            return extra == null ? null : extra.get(key);
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object r) {
            result = r;
            throwable = null;
            returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable t) {
            throwable = t;
            result = null;
            returnEarly = true;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }
}
