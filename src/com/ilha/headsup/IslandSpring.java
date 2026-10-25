package com.ilha.headsup;

/**
 * Resposta ao degrau de uma mola amortecida (0 -> 1), em função do tempo real.
 * zeta < 1 = sub-amortecida (passa do alvo e volta), omega = rigidez (rad/s).
 */
public final class IslandSpring {
    private IslandSpring() {}

    public static float step(float t, float zeta, float omega) {
        if (t <= 0f) return 0f;
        double wd = omega * Math.sqrt(1.0 - zeta * zeta);
        double env = Math.exp(-zeta * omega * t);
        return (float) (1.0 - env * (Math.cos(wd * t) + (zeta * omega / wd) * Math.sin(wd * t)));
    }

    public static float smooth(float x) {
        if (x <= 0f) return 0f;
        if (x >= 1f) return 1f;
        return x * x * (3f - 2f * x);
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
