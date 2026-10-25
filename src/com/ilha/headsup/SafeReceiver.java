package com.ilha.headsup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Recebe da Interface do Sistema a opção que o modo de segurança desligou e desliga nos ajustes. */
public final class SafeReceiver extends BroadcastReceiver {
    public void onReceive(Context c, Intent i) {
        String key = i.getStringExtra("key");
        if (key == null || key.length() == 0 || key.startsWith("safe_")) return;
        SharedPreferences p = c.getSharedPreferences(Keys.FILE, Context.MODE_PRIVATE);
        if (!p.contains(key) && !Keys.isOption(key)) return;
        RemoteSync.attach(c.getApplicationContext());
        p.edit().putBoolean(key, false).remove(Keys.SAFE_LAST).remove(Keys.SAFE_AT)
                .putString(Keys.SAFE_MSG, key).putLong(Keys.SAFE_MSG_AT, System.currentTimeMillis()).apply();
    }
}
