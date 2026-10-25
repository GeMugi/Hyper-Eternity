package com.ilha.headsup;

import java.lang.reflect.Method;

/**
 * Setters do ViewState que mudaram no HyperOS 4: setYTranslation, setAlpha… ganharam um primeiro
 * parâmetro (String, o motivo). Acha a versão que existir e chama do jeito certo.
 */
final class VS {
    private VS() {}

    static Method find(Class c, String name) throws NoSuchMethodException {
        try {
            return c.getMethod(name, float.class);
        } catch (NoSuchMethodException e) {
            return c.getMethod(name, String.class, float.class);
        }
    }

    static Object inv(Method m, Object o, float v) throws Exception {
        if (m.getParameterTypes().length == 2) return m.invoke(o, "HyperEternity", Float.valueOf(v));
        return m.invoke(o, Float.valueOf(v));
    }
}
