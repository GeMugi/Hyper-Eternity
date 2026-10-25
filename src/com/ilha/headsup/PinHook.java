package com.ilha.headsup;

import android.view.View;
import com.ilha.headsup.xp.XC_MethodHook;

/** setPinnedStatus: quando a notificação passa a ficar fixada no topo, agenda a entrada. */
public final class PinHook extends XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam p) {
        try {
            p.setObjectExtra("ilha_pinned", Refl.call(p.thisObject, "isPinned"));
        } catch (Throwable ignored) {
        }
    }

    protected void afterHookedMethod(MethodHookParam p) {
        try {
            View view = (View) p.thisObject;
            Object before = p.getObjectExtra("ilha_pinned");
            Object after = Refl.call(view, "isPinned");
            if (!Boolean.TRUE.equals(after)) {
                ElasticCard.stop(view);
            } else if (!Boolean.TRUE.equals(before)) {
                ElasticCard.schedule(view);
            }
        } catch (Throwable t) {
            ElasticCard.log("ERRO no pin: " + t);
        }
    }
}
