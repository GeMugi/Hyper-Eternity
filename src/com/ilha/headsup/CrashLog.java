package com.ilha.headsup;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Se o app fechar com erro, guarda o motivo num arquivo; a tela de ajustes mostra na próxima vez.
 * Instalado pelo ServiceProvider, que é criado antes de tudo quando o processo do app abre.
 */
final class CrashLog {
    private CrashLog() {}

    private static boolean installed;
    private static File file;

    static synchronized void install(Context c) {
        if (installed || c == null) return;
        installed = true;
        file = new File(c.getFilesDir(), "ultimo-erro.txt");
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                save("Thread " + t.getName(), e);
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    static void save(String where, Throwable e) {
        try {
            if (file == null) return;
            String head = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(new Date())
                    + " · Hyper Eternity " + Keys.VERSION + " · Android " + Build.VERSION.RELEASE + " · " + where + "\n";
            FileOutputStream out = new FileOutputStream(file, false);
            out.write((head + Log.getStackTraceString(e)).getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    /** Texto do último erro guardado, ou null. */
    static String read(Context c) {
        try {
            File f = new File(c.getFilesDir(), "ultimo-erro.txt");
            if (!f.isFile() || f.length() == 0) return null;
            byte[] b = new byte[(int) Math.min(f.length(), 64 * 1024)];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(b);
            in.close();
            return n > 0 ? new String(b, 0, n, "UTF-8") : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static void clear(Context c) {
        try {
            new File(c.getFilesDir(), "ultimo-erro.txt").delete();
        } catch (Throwable ignored) {
        }
    }
}
