package com.ilha.headsup;

import android.content.Context;
import android.view.View;

/**
 * Ponte com a extensão Hyper Eternity Relógios (outro módulo do LSPosed, no mesmo processo da
 * Interface do Sistema, com outro carregador de classes). As duas trocam só objetos do próprio
 * Android/Java guardados em System.getProperties(), que os dois carregadores enxergam.
 */
final class Ext {
    private Ext() {}

    static final String PKG = "com.ilha.headsup.relogios";
    static final String BUILD = "hyper.eternity.relogios.build";   // Function<Context, View>: relógio parado (cortina)
    static final String FLOOR = "hyper.eternity.relogios.floor";   // DoubleSupplier: base do relógio dela (NaN se não está à mostra)
    static final String KGTOP = "hyper.eternity.kg.top";           // DoubleSupplier: topo da lista do bloqueio (deste módulo)

    /** Publica o topo da lista do bloqueio, que o relógio da extensão usa para encolher e subir. */
    static void publish() {
        System.getProperties().put(KGTOP, new java.util.function.DoubleSupplier() {
            public double getAsDouble() {
                if (!StackHooks.onKeyguard) return Double.NaN;
                float best = Float.NaN, y = StackHooks.hostY;
                float lt = StackHooks.listTop, mt = StackHooks.mediaTop, zt = StackHooks.zenTop;
                if (!Float.isNaN(lt)) best = lt + y;
                if (!Float.isNaN(mt) && (Float.isNaN(best) || mt + y < best)) best = mt + y;
                if (!Float.isNaN(zt) && (Float.isNaN(best) || zt + y < best)) best = zt + y;
                return best;
            }
        });
    }

    /** Base do relógio da extensão na tela; NaN quando ele não está à mostra (o nosso relógio age). */
    static float clockFloor() {
        Object o = System.getProperties().get(FLOOR);
        if (!(o instanceof java.util.function.DoubleSupplier)) return Float.NaN;
        try {
            return (float) ((java.util.function.DoubleSupplier) o).getAsDouble();
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /** Relógio All in One parado para a cortina, montado pela extensão; null sem ela (ou desligado lá). */
    @SuppressWarnings("unchecked")
    static View curtainClock(Context c) {
        Object o = System.getProperties().get(BUILD);
        if (!(o instanceof java.util.function.Function)) return null;
        try {
            return ((java.util.function.Function<Context, View>) o).apply(c);
        } catch (Throwable t) {
            return null;
        }
    }
}
