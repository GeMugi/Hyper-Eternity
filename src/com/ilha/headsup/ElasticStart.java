package com.ilha.headsup;

import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;

public final class ElasticStart implements ViewTreeObserver.OnPreDrawListener {
    final ViewTreeObserver observer;
    final WeakReference row;

    public ElasticStart(View v) {
        this.row = new WeakReference(v);
        this.observer = v.getViewTreeObserver();
    }

    public boolean onPreDraw() {
        try {
            if (observer.isAlive()) observer.removeOnPreDrawListener(this);
            View v = (View) row.get();
            if (v == null) return true;
            if (ElasticCard.pending.get(v) != this) return true;
            ElasticCard.pending.remove(v);
            if (!v.isAttachedToWindow()) return true;
            if (!Boolean.TRUE.equals(Refl.call(v, "isPinned"))) return true;
            ElasticCard.start(v);
        } catch (Throwable t) {
            ElasticCard.log("ERRO no preDraw: " + t);
        }
        return true;
    }
}
