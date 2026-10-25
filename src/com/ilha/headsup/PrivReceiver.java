package com.ilha.headsup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Recebe da Interface do Sistema as conversas vistas, para a lista de privacidade. */
public final class PrivReceiver extends BroadcastReceiver {
    public void onReceive(Context c, Intent i) {
        String pkg = i.getStringExtra("pkg"), id = i.getStringExtra("id"), label = i.getStringExtra("label");
        if (pkg == null || id == null || label == null || id.indexOf('\n') >= 0 || label.indexOf('\n') >= 0) return;
        SharedPreferences p = c.getSharedPreferences(Keys.FILE, Context.MODE_PRIVATE);
        String cur = p.getString(Keys.PRIV_SEEN, "");
        String head = pkg + "|" + id + "|";
        StringBuilder out = new StringBuilder(head).append(label.replace("|", "/"));
        int n = 1;
        for (String line : cur.split("\n")) {
            if (line.length() == 0 || line.startsWith(head)) continue;
            if (n++ >= 300) break;
            out.append('\n').append(line);
        }
        RemoteSync.attach(c.getApplicationContext());
        p.edit().putString(Keys.PRIV_SEEN, out.toString()).apply();
    }
}
