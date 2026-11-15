package com.ilha.headsup;

/**
 * Medidor leve do tempo gasto pelo módulo no SystemUI. A cada 5 s com atividade, registra por
 * trecho: quantas vezes rodou, tempo médio e o pior caso. Só soma dois números por chamada.
 */
final class Perf {
    private Perf() {}

    static final String[] NAMES = {"lista (empilhamento)", "relógio HOS4: cálculo", "relógio HOS4: redesenho", "grupos em pilha", "cortina"};
    static final int LIST = 0, CLOCK = 1, CLOCK_DRAW = 2, GROUPS = 3, SHEET = 4;
    static final long[] sum = new long[NAMES.length], max = new long[NAMES.length];
    static final int[] cnt = new int[NAMES.length];
    static long lastLog;
    static int logs;

    static long begin() {
        return System.nanoTime();
    }

    static void end(int id, long t0) {
        if (!Keys.TEST) return;
        long now = System.nanoTime(), d = now - t0;
        sum[id] += d;
        cnt[id]++;
        if (d > max[id]) max[id] = d;
        long ms = now / 1000000L;
        if (ms - lastLog < 5000L) return;
        lastLog = ms;
        long total = 0;
        for (int i = 0; i < sum.length; i++) total += sum[i];
        if (total > 60000000L && logs < 400) {   // mais de 60 ms somados nos últimos 5 s
            logs++;
            StringBuilder b = new StringBuilder("tempo do módulo (5 s):");
            for (int i = 0; i < sum.length; i++) {
                if (cnt[i] == 0) continue;
                b.append(' ').append(NAMES[i]).append(' ').append(cnt[i]).append("x média ")
                        .append(String.format(java.util.Locale.US, "%.2f", sum[i] / 1e6 / cnt[i])).append(" ms pior ")
                        .append(String.format(java.util.Locale.US, "%.1f", max[i] / 1e6)).append(" ms;");
            }
            ElasticCard.log(b.toString());
        }
        for (int i = 0; i < sum.length; i++) { sum[i] = 0; max[i] = 0; cnt[i] = 0; }
    }
}
