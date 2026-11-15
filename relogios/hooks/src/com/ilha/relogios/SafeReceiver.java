package com.ilha.relogios;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Recebe da Interface do Sistema a opção que a proteção desligou e desliga nos ajustes. */
public final class SafeReceiver extends BroadcastReceiver {
    public static final String SAFE_MSG = "safe_msg", SAFE_MSG_AT = "safe_msg_at";

    public void onReceive(Context c, Intent i) {
        String key = i.getStringExtra("key");
        if (key == null || key.length() == 0) return;
        boolean known = false;
        for (String k : Keys.ALL) known |= k.equals(key);
        if (!known) return;
        SharedPreferences p = c.getSharedPreferences(Keys.FILE, Context.MODE_PRIVATE);
        RemoteSync.attach(c.getApplicationContext());
        p.edit().putBoolean(key, false).remove(Keys.SAFE_LAST).remove(Keys.SAFE_AT)
                .putString(SAFE_MSG, key).putLong(SAFE_MSG_AT, System.currentTimeMillis()).apply();
    }
}
