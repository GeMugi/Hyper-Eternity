package com.ilha.headsup;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lado do app: copia os ajustes (arquivo "ajustes") para as preferências remotas do LSPosed, de
 * onde a Interface do Sistema lê. O LSPosed entrega o serviço dele ao app pelo provedor
 * &lt;pacote&gt;.XposedService (ServiceProvider) toda vez que o processo do app abre.
 *
 * Protocolo (io.github.libxposed.service.IXposedService, API 102):
 * updateRemotePreferences(String grupo, Bundle{clear, delete, put}) = transação 22.
 */
public final class RemoteSync {
    private RemoteSync() {}

    private static final String TAG = "HeadsUpIlha";
    private static final String DESCRIPTOR = "io.github.libxposed.service.IXposedService";
    private static final int TX_UPDATE_REMOTE_PREFS = 22;

    private static volatile IBinder binder;
    private static volatile boolean lastOk;
    private static Context app;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ArrayList<Runnable> watchers = new ArrayList<Runnable>();

    // o SharedPreferences guarda os ouvintes por referência fraca: este fica preso aqui
    private static final SharedPreferences.OnSharedPreferenceChangeListener CHANGED =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                    push();
                }
            };
    private static boolean listening;

    /** Liga a sincronização: chamar com o contexto do app ao abrir a tela de ajustes. */
    public static synchronized void attach(Context c) {
        if (app == null) app = c.getApplicationContext();
        if (!listening) {
            listening = true;
            prefs().registerOnSharedPreferenceChangeListener(CHANGED);
        }
        push();
    }

    static SharedPreferences prefs() {
        return app.getSharedPreferences(Keys.FILE, Context.MODE_PRIVATE);
    }

    /** O LSPosed entregou o serviço (chamado pelo ServiceProvider). */
    static void onBinder(Context c, IBinder b) {
        synchronized (RemoteSync.class) {
            if (app == null && c != null) app = c.getApplicationContext();
        }
        binder = b;
        try {
            b.linkToDeath(new IBinder.DeathRecipient() {
                public void binderDied() {
                    binder = null;
                    lastOk = false;
                    notifyWatchers();
                }
            }, 0);
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "serviço do LSPosed recebido");
        push();
    }

    public static boolean connected() {
        return binder != null;
    }

    public static boolean synced() {
        return binder != null && lastOk;
    }

    public static void watch(Runnable r) {
        synchronized (watchers) {
            if (!watchers.contains(r)) watchers.add(r);
        }
    }

    public static void unwatch(Runnable r) {
        synchronized (watchers) {
            watchers.remove(r);
        }
    }

    private static void notifyWatchers() {
        final ArrayList<Runnable> copy;
        synchronized (watchers) {
            copy = new ArrayList<Runnable>(watchers);
        }
        main.post(new Runnable() {
            public void run() {
                for (int i = 0; i < copy.size(); i++) copy.get(i).run();
            }
        });
    }

    /** Envia todos os ajustes (substituindo o que havia no grupo), fora da linha principal. */
    static void push() {
        worker.execute(new Runnable() {
            public void run() {
                IBinder b = binder;
                Context c = app;
                if (b == null || c == null) return;
                HashMap<String, Object> put = new HashMap<String, Object>();
                for (Map.Entry<String, ?> e : prefs().getAll().entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Float || v instanceof String) {
                        put.put(e.getKey(), v);
                    }
                }
                boolean ok = update(b, Keys.GROUP, put);
                if (ok != lastOk) {
                    lastOk = ok;
                }
                notifyWatchers();
            }
        });
    }

    private static boolean update(IBinder b, String group, HashMap<String, Object> put) {
        Bundle diff = new Bundle();
        diff.putBoolean("clear", true);
        diff.putSerializable("delete", new HashSet<String>());
        diff.putSerializable("put", put);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(group);
            data.writeInt(1);
            diff.writeToParcel(data, 0);
            b.transact(TX_UPDATE_REMOTE_PREFS, data, reply, 0);
            reply.readException();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "não consegui gravar os ajustes no LSPosed", t);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
