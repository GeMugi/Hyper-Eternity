package com.ilha.headsup;

import android.content.SharedPreferences;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.reflect.Constructor;

/**
 * No system_server: com "Grupos em pilha" ligado, o Android agrupa sozinho a partir de 2
 * notificações soltas do mesmo app (o padrão é só a partir de 4). Assim apps que não agrupam as
 * próprias notificações, como o Instagram, também viram pilha. Vale depois de reiniciar o celular.
 */
final class GroupAuto {
    private GroupAuto() {}

    static volatile Object first, helper;
    static final java.util.HashMap<java.lang.reflect.Field, Integer> limits = new java.util.HashMap<>();
    static SharedPreferences prefs;
    static long lastRead;
    static boolean lastOn, applied;

    /** A opção está ligada? (relê os ajustes no máximo a cada 2 s) */
    static boolean on() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastRead < 2000 && lastRead != 0) return lastOn;
        lastRead = now;
        try {
            if (prefs == null) prefs = XposedBridge.remotePrefs(Keys.GROUP);
            lastOn = prefs != null && (prefs.getBoolean(Keys.GROUP_STACK, Keys.D_GROUP_STACK) || prefs.getBoolean(Keys.CONV_SPLIT, Keys.D_CONV_SPLIT));
        } catch (Throwable t) {
            lastOn = false;
        }
        return lastOn;
    }

    /** Põe os limites em 2 (ligado) ou volta aos do sistema (desligado). */
    static void sync() {
        boolean o = on();
        if (helper == null || (applied && o == lastApplied)) return;
        synchronized (limits) {
            for (java.util.Map.Entry<java.lang.reflect.Field, Integer> e : limits.entrySet()) {
                try {
                    e.getKey().setInt(helper, o ? 2 : e.getValue().intValue());
                } catch (Throwable ignored) {
                }
            }
        }
        applied = true;
        lastApplied = o;
        XposedBridge.log("[HeadsUpIlha] agrupar a partir de 2: " + (o ? "ligado" : "desligado (limites do sistema)"));
    }

    static boolean lastApplied;

    static boolean isConversation(Object[] args) {
        try {
            for (Object a : args) {
                if (a == null) continue;
                Object sbn = a instanceof android.service.notification.StatusBarNotification ? a : null;
                if (sbn == null) {
                    java.lang.reflect.Method m = Refl.method(a.getClass(), "getSbn");
                    if (m != null) sbn = m.invoke(a);
                }
                android.app.Notification n = null;
                if (sbn instanceof android.service.notification.StatusBarNotification) n = ((android.service.notification.StatusBarNotification) sbn).getNotification();
                else if (a instanceof android.app.Notification) n = (android.app.Notification) a;
                if (n != null && n.getShortcutId() != null) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static void install(ClassLoader cl) {
        try {
            Class c = Class.forName("com.android.server.notification.GroupHelper", false, cl);
            int n = 0;
            // os ganchos ficam sempre instalados; ligar/desligar a opção vale na hora (sem
            // reiniciar o celular): os limites são trocados direto no GroupHelper que já existe
            for (Constructor k : c.getDeclaredConstructors()) {
                XposedBridge.hookMethod(k, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            helper = param.thisObject;
                            StringBuilder was = new StringBuilder();
                            for (java.lang.reflect.Field fl : helper.getClass().getDeclaredFields()) {
                                if (fl.getType() != int.class || java.lang.reflect.Modifier.isStatic(fl.getModifiers())) continue;
                                fl.setAccessible(true);
                                int v = fl.getInt(helper);
                                if (v > 2 && v < 20) {
                                    limits.put(fl, Integer.valueOf(v));
                                    was.append(was.length() == 0 ? "" : ", ").append(fl.getName()).append('=').append(v);
                                }
                            }
                            XposedBridge.log("[HeadsUpIlha] agrupar: limites do sistema " + was);
                            sync();
                        } catch (Throwable e) {
                            XposedBridge.log("[HeadsUpIlha] agrupar a partir de 2: erro " + e);
                        }
                    }
                });
                n++;
            }
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("onNotificationPosted")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam param) {
                        sync();
                    }
                });
            }
            XposedBridge.log("[HeadsUpIlha] agrupar a partir de 2: " + n + " construtores");
            // Android 16: o agrupamento automático deixa as conversas de fora (só agrupa se a
            // flag notification_force_group_conversations estiver ligada). Telegram e Instagram
            // são conversas, então ficavam soltos: liga a flag aqui.
            int f = 0;
            for (String cn : new String[]{"com.android.server.notification.Flags",
                    "com.android.server.notification.FeatureFlagsImpl",
                    "com.android.server.notification.CustomFeatureFlags"}) {
                try {
                    Class fc = Class.forName(cn, false, cl);
                    for (java.lang.reflect.Method m : fc.getDeclaredMethods()) {
                        if (!m.getName().equals("notificationForceGroupConversations") || m.getReturnType() != boolean.class) continue;
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (on()) param.setResult(Boolean.TRUE);
                            }
                        });
                        f++;
                    }
                } catch (Throwable ignored) {
                }
            }
            XposedBridge.log("[HeadsUpIlha] agrupar conversas também: " + (f > 0 ? "ligado (" + f + ")" : "flag não encontrada"));
            // Android 16 agrupa por "seção" (alertando, silenciosa, conversas...): notificações do
            // mesmo app em seções diferentes viravam pilhas separadas (ou ficavam soltas). Junta
            // todas as seções numa só para o agrupamento automático.
            try {
                int hk = 0;
                StringBuilder sig = new StringBuilder();
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals("getSection") || m.getReturnType() == void.class) continue;
                    sig.append(m.toString()).append("; ");
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object r = param.getResult();
                                if (r == null || !on()) return;
                                // conversas (com atalho) ficam na seção delas: juntá-las com as
                                // outras fazia o Android perder a foto do grupo/contato do atalho
                                if (isConversation(param.args)) return;
                                if (first == null) {
                                    Object list = null;
                                    for (java.lang.reflect.Field fl : ((java.lang.reflect.Method) param.method).getDeclaringClass().getDeclaredFields()) {
                                        if (java.util.List.class.isAssignableFrom(fl.getType()) && fl.getName().contains("SECTIONS")) {
                                            fl.setAccessible(true);
                                            list = fl.get(null);
                                        }
                                    }
                                    if (list instanceof java.util.List && !((java.util.List) list).isEmpty()) {
                                        for (Object o : (java.util.List) list) if (String.valueOf(o).toLowerCase().contains("alert")) first = o;
                                        if (first == null) first = ((java.util.List) list).get(0);
                                        XposedBridge.log("[HeadsUpIlha] agrupar: seções juntas em " + first + " (de " + list + ")");
                                    }
                                }
                                if (first != null && r.getClass() == first.getClass()) param.setResult(first);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    hk++;
                }
                XposedBridge.log("[HeadsUpIlha] agrupar: seções " + (hk > 0 ? "juntas (" + sig + ")" : "não encontradas"));
            } catch (Throwable t) {
                XposedBridge.log("[HeadsUpIlha] agrupar: seções erro " + t);
            }
        } catch (Throwable t) {
            XposedBridge.log("[HeadsUpIlha] agrupar a partir de 2 indisponível: " + t);
        }
    }
}
