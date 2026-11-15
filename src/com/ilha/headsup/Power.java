package com.ilha.headsup;

import android.content.Context;
import android.os.PowerManager;

/** Tela ligada? Para os vigias periódicos dormirem com a tela apagada. */
final class Power {
    private Power() {}

    static PowerManager pm;

    static boolean interactive() {
        try {
            if (pm == null) {
                Context c = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
                if (c == null) return true;
                pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            }
            return pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }
}
