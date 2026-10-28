package com.ilha.headsup;

import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Manter as notificações na tela de bloqueio depois de desbloquear.
 *
 * No HyperOS 3, ao desbloquear, as notificações que podem ser apagadas ganham a marca
 * ExpandedNotification.mHasShownAfterUnlock (KeyguardCoordinator, quando o estado vira "gaveta" com
 * o bloqueio fora). No próximo bloqueio, NotificationFilterController.forceHideOnKeyguard esconde
 * as marcadas: só as que chegarem depois aparecem. Aqui, quando o único motivo para esconder é essa
 * marca, a resposta vira "não esconde" - o resto da regra (canShowOnKeyguard) continua valendo.
 */
final class KeepLock {
    private KeepLock() {}

    static Field fSbn, fShown;
    static Method mCanShow;
    static int logs;

    static void install(ClassLoader cl) {
        try {
            Class comp = Class.forName("com.android.systemui.statusbar.notification.policy.NotificationFilterController$Companion", false, cl);
            Method hide = null;
            Method[] ms = comp.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                if ("forceHideOnKeyguard".equals(ms[i].getName())) hide = ms[i];
            }
            if (hide == null) throw new NoSuchMethodException("forceHideOnKeyguard");
            Class entry = Class.forName("com.android.systemui.statusbar.notification.collection.NotificationEntry", false, cl);
            fSbn = entry.getDeclaredField("mSbn");
            fSbn.setAccessible(true);
            Class en = Class.forName("com.android.systemui.statusbar.notification.ExpandedNotification", false, cl);
            fShown = en.getDeclaredField("mHasShownAfterUnlock");
            fShown.setAccessible(true);
            mCanShow = en.getDeclaredMethod("canShowOnKeyguard");
            mCanShow.setAccessible(true);
            XposedBridge.hookMethod(hide, new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!Boolean.TRUE.equals(p.getResult())) return;
                    Prefs.refresh();
                    if (!Prefs.kgKeep) return;
                    try {
                        Object sbn = fSbn.get(p.args[0]);
                        if (sbn == null || !fShown.getBoolean(sbn)) return;   // escondida por outro motivo
                        if (Boolean.TRUE.equals(mCanShow.invoke(sbn))) {
                            p.setResult(Boolean.FALSE);
                            if (logs++ < 3) ElasticCard.log("bloqueio: notificação já vista mantida na tela de bloqueio");
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            // o filtro do Android "mostrar só notificações novas no bloqueio" (se estiver ligado)
            try {
                Class unseen = Class.forName("com.android.systemui.statusbar.notification.collection.coordinator.OriginalUnseenKeyguardCoordinator$unseenNotifFilter$1", false, cl);
                Method m = null;
                Method[] us = unseen.getDeclaredMethods();
                for (int i = 0; i < us.length; i++) {
                    if ("shouldFilterOut".equals(us[i].getName())) m = us[i];
                }
                if (m != null) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam p) {
                            if (!Boolean.TRUE.equals(p.getResult())) return;
                            Prefs.refresh();
                            if (Prefs.kgKeep) p.setResult(Boolean.FALSE);
                        }
                    });
                }
            } catch (Throwable ignored) {
            }
            ElasticCard.log("manter notificações no bloqueio: instalado");
        } catch (Throwable t) {
            ElasticCard.log("manter notificações no bloqueio indisponível: " + t);
            Caps.fail(Keys.KG_KEEP, t);
        }
    }
}
