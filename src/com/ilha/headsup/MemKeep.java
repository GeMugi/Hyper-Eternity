package com.ilha.headsup;

import android.content.SharedPreferences;

import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * "Segurar apps em segundo plano" (no sistema, experimental). O limpador de memória da Xiaomi
 * (ProcessMemoryCleaner, motivo "normal_mem_pressure") fecha apps recentes por precaução: nos
 * registros, com ~1,2 GB de memória disponível e ~5 GB de troca livre. Com a opção ligada, nessa
 * limpeza "normal" os apps com tela aberta (os que estão nos recentes) saem da lista - enquanto
 * houver folga de verdade. Serviços, processos auxiliares e restos do sistema continuam podendo
 * ser fechados; as limpezas de pressão alta/crítica, a de abrir app e o limpador do próprio
 * kernel (lmkd) seguem iguais, então a memória nunca fica sem válvula de escape.
 */
final class MemKeep {
    private MemKeep() {}

    /** Abaixo disto a Xiaomi volta a decidir sozinha. */
    static final long MIN_AVAIL_KB = 800L * 1024, MIN_SWAP_FREE_KB = 1536L * 1024;

    static SharedPreferences prefs;
    static long lastRead;
    static boolean lastOn;
    static int logs;
    static Method mMain, mAct, mSys, mPkg;

    static void log(String m) {
        if (logs++ < 60) XposedBridge.log("[HeadsUpIlha] memória: " + m);
    }

    static boolean on() {
        long now = android.os.SystemClock.uptimeMillis();
        if (lastRead != 0 && now - lastRead < 2000) return lastOn;
        lastRead = now;
        try {
            if (prefs == null) prefs = XposedBridge.remotePrefs(Keys.GROUP);
            lastOn = prefs != null && prefs.getBoolean(Keys.MEM_KEEP, Keys.D_MEM_KEEP);
        } catch (Throwable t) {
            lastOn = false;
        }
        return lastOn;
    }

    static void install(ClassLoader cl) {
        try {
            Class<?> k = Class.forName("com.android.server.am.ProcessMemoryCleaner", false, cl);
            Method scan = k.getDeclaredMethod("scanKillableProcessForImr", int.class);
            XposedBridge.hookMethod(scan, new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (((Integer) p.args[0]) != 0 || !(p.getResult() instanceof List) || !on()) return;
                        filter(p);
                    } catch (Throwable t) {
                        log("filtro: " + t);
                    }
                }
            });
            log("limpador da Xiaomi encontrado");
        } catch (Throwable t) {
            log("limpador da Xiaomi não encontrado nesta versão: " + t);
        }
    }

    static void filter(XC_MethodHook.MethodHookParam p) throws Exception {
        List<?> in = (List<?>) p.getResult();
        if (in.isEmpty()) return;
        long[] m = meminfo();
        if (m[0] < MIN_AVAIL_KB || m[1] < MIN_SWAP_FREE_KB) {
            log("pouca memória (disponível " + m[0] / 1024 + " MB, troca livre " + m[1] / 1024 + " MB): a Xiaomi decide");
            return;
        }
        ArrayList<Object> out = new ArrayList<>(in.size());
        StringBuilder kept = new StringBuilder();
        for (Object o : in) {
            if (protect(o)) {
                if (kept.length() < 300) kept.append(kept.length() == 0 ? "" : ", ").append(pkg(o));
            } else {
                out.add(o);
            }
        }
        if (out.size() == in.size()) return;
        p.setResult(out);
        log("segurados (disponível " + m[0] / 1024 + " MB, troca livre " + m[1] / 1024 + " MB): " + kept);
    }

    /** App do usuário, processo principal, com tela aberta (está nos recentes). */
    static boolean protect(Object proc) {
        try {
            if (mMain == null) {
                Class<?> c = proc.getClass();
                mMain = find(c, "isMainProc");
                mAct = find(c, "hasActivity");
                mSys = find(c, "isSystemApp");
                mPkg = find(c, "getPackageName");
            }
            return Boolean.TRUE.equals(mMain.invoke(proc)) && Boolean.TRUE.equals(mAct.invoke(proc))
                    && !Boolean.TRUE.equals(mSys.invoke(proc));
        } catch (Throwable t) {
            return false;
        }
    }

    static Method find(Class<?> c, String n) throws NoSuchMethodException {
        Method m = c.getMethod(n);
        m.setAccessible(true);
        return m;
    }

    static String pkg(Object proc) {
        try {
            return String.valueOf(mPkg.invoke(proc));
        } catch (Throwable t) {
            return "?";
        }
    }

    /** {MemAvailable, SwapFree} em kB. */
    static long[] meminfo() {
        long avail = Long.MAX_VALUE, swap = Long.MAX_VALUE;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("MemAvailable:")) avail = kb(l);
                else if (l.startsWith("SwapFree:")) swap = kb(l);
            }
        } catch (Throwable ignored) {
            return new long[]{0, 0};   // sem leitura: não mexe
        }
        return new long[]{avail, swap};
    }

    static long kb(String l) {
        String s = l.replaceAll("[^0-9]", "");
        return s.isEmpty() ? 0 : Long.parseLong(s);
    }
}
