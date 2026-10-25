package com.ilha.headsup;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

/**
 * Provedor &lt;pacote&gt;.XposedService: o LSPosed chama "SendBinder" aqui quando o app abre.
 * Guardamos o serviço para os ajustes do Hyper Eternity.
 */
public final class ServiceProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        CrashLog.install(getContext());
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("SendBinder".equals(method) && extras != null) {
            try {
                IBinder b = extras.getBinder("binder");
                if (b != null) RemoteSync.onBinder(getContext(), b);
            } catch (Throwable ignored) {
            }
        }
        return new Bundle();
    }

    @Override
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override
    public String getType(Uri u) {
        return null;
    }

    @Override
    public Uri insert(Uri u, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri u, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri u, ContentValues v, String s, String[] a) {
        return 0;
    }
}
