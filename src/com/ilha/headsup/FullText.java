package com.ilha.headsup;

import android.content.SharedPreferences;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;

/**
 * O Android corta o texto das notificações em ~1024 caracteres (Notification.safeCharSequence),
 * no app que posta, no sistema e no SystemUI. Com "Mensagem inteira ao segurar" ligada, o texto
 * passa inteiro, para o menu de segurar mostrar a mensagem toda.
 */
final class FullText {
    private FullText() {}

    static SharedPreferences prefs;
    static long lastRead;
    static boolean lastOn, done;

    static boolean on() {
        long now = android.os.SystemClock.uptimeMillis();
        if (lastRead != 0 && now - lastRead < 2000) return lastOn;
        lastRead = now;
        try {
            if (prefs == null) prefs = XposedBridge.remotePrefs(Keys.GROUP);
            lastOn = prefs != null && prefs.getBoolean(Keys.MODAL_FULL, Keys.D_MODAL_FULL);
        } catch (Throwable t) {
            lastOn = false;
        }
        return lastOn;
    }

    static synchronized void install(String where) {
        if (done) return;
        done = true;
        int n = 0;
        try {
            for (java.lang.reflect.Method m : android.app.Notification.class.getDeclaredMethods()) {
                if (!m.getName().equals("safeCharSequence")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object in = p.args[0], out = p.getResult();
                            if (!(in instanceof CharSequence) || !(out instanceof CharSequence)) return;
                            if (((CharSequence) out).length() >= ((CharSequence) in).length() || !on()) return;
                            p.setResult(in instanceof String ? in : in.toString());
                        } catch (Throwable ignored) {
                        }
                    }
                });
                n++;
            }
        } catch (Throwable t) {
            XposedBridge.log("[HeadsUpIlha] texto inteiro (" + where + "): erro " + t);
        }
        XposedBridge.log("[HeadsUpIlha] texto inteiro (" + where + "): " + (n > 0 ? "pronto" : "não encontrado"));
    }
}
