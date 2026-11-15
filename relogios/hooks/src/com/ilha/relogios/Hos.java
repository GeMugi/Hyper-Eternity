package com.ilha.relogios;

/**
 * Trava do HyperOS 4: tudo o que a extensão traz (relógios All in One, editor, fontes, vidro,
 * efeitos e o patch da fonte variável) já existe nativo no HyperOS 4. Lá, ela não faz nada.
 */
public final class Hos {
    private Hos() {}

    private static volatile Boolean cached;
    public static volatile String why = "";

    public static String prop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class).invoke(null, key);
            if (v != null) return v.toString();
        } catch (Throwable ignored) {
        }
        Process p = null;
        try {
            p = new ProcessBuilder("getprop", key).redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String s = r.readLine();
            p.waitFor();
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** O sistema é HyperOS 4 (ou mais novo)? */
    public static boolean isHos4() {
        Boolean c = cached;
        if (c != null) return c.booleanValue();
        boolean r = false;
        String code = prop("ro.mi.os.version.code"), name = prop("ro.mi.os.version.name");
        try {
            if (code.length() > 0 && Integer.parseInt(code.trim()) >= 4) { r = true; why = "ro.mi.os.version.code=" + code; }
        } catch (Throwable ignored) {
        }
        if (!r && name.length() > 2 && name.startsWith("OS")) {
            try {
                int major = Integer.parseInt(name.substring(2).split("\\.")[0]);
                if (major >= 4) { r = true; why = "ro.mi.os.version.name=" + name; }
            } catch (Throwable ignored) {
            }
        }
        if (!r) why = "HyperOS " + (name.length() > 0 ? name : "?") + " (código " + (code.length() > 0 ? code : "?") + ")";
        cached = Boolean.valueOf(r);
        return r;
    }
}
