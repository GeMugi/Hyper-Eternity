package com.ilha.headsup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.Map;

/**
 * Ponte para o app de ajustes com visual miuix (com.ilha.headsup.miuix): ele lê e muda os
 * ajustes daqui, e o RemoteSync leva tudo ao LSPosed como se fosse a tela de ajustes normal.
 * Só esse pacote pode chamar.
 */
public final class BridgeProvider extends ContentProvider {
    static final String MIUIX = "com.ilha.headsup.miuix";

    @Override
    public boolean onCreate() {
        return true;
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(Keys.FILE, Context.MODE_PRIVATE);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        String caller = getCallingPackage();
        if (!MIUIX.equals(caller) && !getContext().getPackageName().equals(caller)) throw new SecurityException("só o app miuix do Hyper Eternity");
        Context c = getContext();
        RemoteSync.attach(c);
        Bundle out = new Bundle();
        SharedPreferences sp = prefs();
        if ("status".equals(method) || "getAll".equals(method)) {
            out.putString("__version", Keys.VERSION);
            out.putBoolean("__connected", RemoteSync.connected());
            out.putBoolean("__synced", RemoteSync.synced());
            boolean music = false;
            try {
                c.getPackageManager().getPackageInfo("com.ilha.headsup.music", 0);
                music = true;
            } catch (Throwable ignored) {
            }
            out.putBoolean("__music", music);
            if ("getAll".equals(method)) {
                for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof Boolean) out.putBoolean(e.getKey(), (Boolean) v);
                    else if (v instanceof Integer) out.putInt(e.getKey(), (Integer) v);
                    else if (v instanceof Long) out.putLong(e.getKey(), (Long) v);
                    else if (v instanceof Float) out.putFloat(e.getKey(), (Float) v);
                    else if (v instanceof String) out.putString(e.getKey(), (String) v);
                }
            }
        } else if ("put".equals(method) && extras != null && arg != null) {
            SharedPreferences.Editor ed = sp.edit();
            Object v = extras.get("v");
            if (v instanceof Boolean) ed.putBoolean(arg, (Boolean) v);
            else if (v instanceof Integer) ed.putInt(arg, (Integer) v);
            else if (v instanceof String) ed.putString(arg, (String) v);
            else if (v == null) ed.remove(arg);
            ed.apply();
        } else if ("reset".equals(method)) {
            sp.edit().clear().apply();
        } else if ("test".equals(method)) {
            out.putBoolean("ok", testHeadsUp(c, sp));
        } else if ("restart".equals(method)) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        Runtime.getRuntime().exec(new String[]{"su", "-c", "killall com.android.systemui"}).waitFor();
                    } catch (Throwable ignored) {
                    }
                }
            }).start();
        }
        return out;
    }

    /** O mesmo teste da tela de ajustes: notificação de exemplo em 1,5 s. */
    static boolean testHeadsUp(Context c, SharedPreferences prefs) {
        final NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || !nm.areNotificationsEnabled()) return false;
        NotificationChannel ch = new NotificationChannel("teste", "Teste do heads-up", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Notificações de teste da animação");
        nm.createNotificationChannel(ch);
        int icon = c.getResources().getIdentifier("ic_stat", "drawable", c.getPackageName());
        boolean ios = prefs.getInt(Keys.HU_STYLE, Keys.D_HU_STYLE) == 1;
        int v = ios ? prefs.getInt(Keys.HU_IOS_SPEED, Keys.D_HU_IOS_SPEED) : prefs.getInt(Keys.HU_SPEED, Keys.D_HU_SPEED);
        int b = ios ? prefs.getInt(Keys.HU_IOS_BOUNCE, Keys.D_HU_IOS_BOUNCE) : prefs.getInt(Keys.HU_BOUNCE, Keys.D_HU_BOUNCE);
        Intent i = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (i == null) i = new Intent();
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(c, 1, i,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        final Notification n = new Notification.Builder(c, "teste")
                .setSmallIcon(icon != 0 ? icon : android.R.drawable.ic_dialog_info)
                .setContentTitle("Hyper Eternity")
                .setContentText("Teste " + (ios ? "iOS" : "OriginOS") + ": velocidade " + v + "% · bounce " + b + "%")
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .addAction(new Notification.Action.Builder(null, "Marcar como lido", pi).build())
                .addAction(new Notification.Action.Builder(null, "Responder", pi).build())
                .build();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                nm.notify(1001, n);
            }
        }, 1500);
        return true;
    }

    @Override
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override
    public String getType(Uri u) {
        return null;
    }

    @Override
    public Uri insert(Uri u, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri u, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri u, ContentValues v, String s, String[] a) {
        return 0;
    }
}
