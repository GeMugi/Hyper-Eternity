package com.ilha.headsup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Reflexão própria, sem depender das classes antigas do v8. */
final class Refl {
    private Refl() {}

    // cache: procurar campos e métodos por reflexão a cada quadro (getDeclaredMethods e exceções
    // de NoSuchField subindo as superclasses) custava caro nas animações
    private static final Object NONE = new Object();
    private static final java.util.concurrent.ConcurrentHashMap<Class, java.util.concurrent.ConcurrentHashMap<String, Object>> FIELDS = new java.util.concurrent.ConcurrentHashMap<>(),
            METHODS = new java.util.concurrent.ConcurrentHashMap<>(), ANY = new java.util.concurrent.ConcurrentHashMap<>();

    private static java.util.concurrent.ConcurrentHashMap<String, Object> map(java.util.concurrent.ConcurrentHashMap<Class, java.util.concurrent.ConcurrentHashMap<String, Object>> m, Class c) {
        java.util.concurrent.ConcurrentHashMap<String, Object> r = m.get(c);
        if (r == null) {
            r = new java.util.concurrent.ConcurrentHashMap<>();
            java.util.concurrent.ConcurrentHashMap<String, Object> o = m.putIfAbsent(c, r);
            if (o != null) r = o;
        }
        return r;
    }

    static Field field(Class c, String name) {
        if (c == null) return null;
        java.util.concurrent.ConcurrentHashMap<String, Object> m = map(FIELDS, c);
        Object v = m.get(name);
        if (v == null) {
            Field f = field0(c, name);
            m.put(name, f != null ? f : NONE);
            return f;
        }
        return v == NONE ? null : (Field) v;
    }

    static Method method(Class c, String name) {
        if (c == null) return null;
        java.util.concurrent.ConcurrentHashMap<String, Object> m = map(METHODS, c);
        Object v = m.get(name);
        if (v == null) {
            Method f = method0(c, name);
            m.put(name, f != null ? f : NONE);
            return f;
        }
        return v == NONE ? null : (Method) v;
    }

    static Method anyMethod(Class c, String name) {
        if (c == null) return null;
        java.util.concurrent.ConcurrentHashMap<String, Object> m = map(ANY, c);
        Object v = m.get(name);
        if (v == null) {
            Method f = anyMethod0(c, name);
            m.put(name, f != null ? f : NONE);
            return f;
        }
        return v == NONE ? null : (Method) v;
    }

    private static Field field0(Class c, String name) {
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable t) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static Method method0(Class c, String name) {
        Class k = c;
        while (k != null) {
            Method[] ms = k.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                if (ms[i].getName().equals(name) && ms[i].getParameterTypes().length == 0) {
                    ms[i].setAccessible(true);
                    return ms[i];
                }
            }
            k = k.getSuperclass();
        }
        return null;
    }

    private static Method anyMethod0(Class c, String name) {
        Class k = c;
        while (k != null) {
            Method[] ms = k.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                if (ms[i].getName().equals(name)) {
                    ms[i].setAccessible(true);
                    return ms[i];
                }
            }
            k = k.getSuperclass();
        }
        return null;
    }

    static Object get(Object o, String name) {
        if (o == null) return null;
        try {
            Field f = field(o.getClass(), name);
            return f != null ? f.get(o) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static Object call(Object o, String name) {
        if (o == null) return null;
        try {
            Method m = method(o.getClass(), name);
            return m != null ? m.invoke(o, new Object[0]) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static int height(Object v) {
        Object h = call(v, "getActualHeight");
        if (h instanceof Integer && ((Integer) h).intValue() > 0) return ((Integer) h).intValue();
        return ((android.view.View) v).getHeight();
    }

    /** notifyHeightChanged(boolean) no HyperOS 3, (String, boolean) no HyperOS 4. */
    static void heightChanged(Object row, Boolean b) {
        if (row == null) return;
        for (Class k = row.getClass(); k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals("notifyHeightChanged")) continue;
                try {
                    m.setAccessible(true);
                    Class[] t = m.getParameterTypes();
                    if (t.length == 1) m.invoke(row, b);
                    else if (t.length == 2) m.invoke(row, "HyperEternity", b);
                    else continue;
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
