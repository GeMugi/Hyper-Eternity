package com.ilha.headsup;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;

public final class ElasticFrame extends AnimatorListenerAdapter implements ValueAnimator.AnimatorUpdateListener {
    final ElasticState state;
    boolean canceled;

    public ElasticFrame(ElasticState s) {
        this.state = s;
    }

    public void onAnimationCancel(Animator a) {
        canceled = true;
        state.finish();
    }

    public void onAnimationEnd(Animator a) {
        if (canceled || state.mode != ElasticState.IOS_OUT || state.finished) {
            state.finish();
            return;
        }
        // saída iOS: fica escondida até o HyperOS terminar de tirar a heads-up (ou um tempo limite)
        state.tick(1f);
        if (state.released || !state.holding) {
            state.finish();
            return;
        }
        View r = (View) state.row.get();
        if (r == null) {
            state.finish();
            return;
        }
        r.postDelayed(new Runnable() {
            public void run() {
                state.finish();
            }
        }, ElasticState.IOS_HOLD_MS);
    }

    public void onAnimationUpdate(ValueAnimator a) {
        Object v = a.getAnimatedValue();
        state.tick(v instanceof Float ? ((Float) v).floatValue() : 1f);
    }
}
