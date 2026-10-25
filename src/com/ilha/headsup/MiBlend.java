package com.ilha.headsup;

import android.view.View;
import java.lang.reflect.Method;
import java.util.ArrayList;

/** O material de vidro do HyperOS (o mesmo do relógio do bloqueio) chamado direto nos métodos da View. */
final class MiBlend {
    private MiBlend() {}

    static Method m(String n, Class... p) throws Throwable {
        return View.class.getMethod(n, p);
    }

    /** Recipiente: deixa o desfoque da janela passar por ele (base do vidro). */
    static void container(View v, int radius) throws Throwable {
        try {
            m("setPassWindowBlurEnabled", boolean.class).invoke(v, Boolean.TRUE);
        } catch (Throwable ignored) {
        }
        m("setMiBackgroundBlurMode", int.class).invoke(v, Integer.valueOf(1));
        m("setMiBackgroundBlurRadius", int.class).invoke(v, Integer.valueOf(radius));
        try {
            m("setPassTextureScale", float.class).invoke(v, Float.valueOf(0f));
        } catch (Throwable ignored) {
        }
        try {
            m("disableMiBackgroundContainBelow", boolean.class).invoke(v, Boolean.TRUE);
        } catch (Throwable ignored) {
        }
    }

    /** Vibrance dos textos da central do HyperOS (cinza que mistura com o fundo + brilho por cima). */
    static void vibrant(View v) throws Throwable {
        m("setMiViewBlurMode", int.class).invoke(v, Integer.valueOf(3));
        ArrayList<android.graphics.Point> l = new ArrayList<>();
        l.add(new android.graphics.Point(0xCCB0B0B0, 100));
        l.add(new android.graphics.Point(0x99FFFFFF, 3));
        m("setMiBackgroundBlendColors", ArrayList.class).invoke(v, l);
    }

    /** Membro (texto/ícone): mistura de cor com o que está atrás, com as cores do relógio do bloqueio. */
    static void member(View v) throws Throwable {
        int[] a = Kg.memberArgs != null ? Kg.memberArgs : new int[]{0xFFC8C8C8, 255, 255, 0xFFFFFFFF, 0};
        int c = a[0];
        int col = android.graphics.Color.argb(a[1], android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c));
        int lab = android.graphics.Color.argb(a[2], 0, 0, 0);
        m("setMiViewBlurMode", int.class).invoke(v, Integer.valueOf(3));
        ArrayList<android.graphics.Point> l = new ArrayList<>();
        l.add(new android.graphics.Point(col, 101));
        l.add(new android.graphics.Point(lab, Kg.memberDark ? 105 : 103));
        if (a[3] != 0) l.add(new android.graphics.Point(a[3], 1000));
        if (a[4] != 0) l.add(new android.graphics.Point(a[4], 3));
        m("setMiBackgroundBlendColors", ArrayList.class).invoke(v, l);
    }
}
