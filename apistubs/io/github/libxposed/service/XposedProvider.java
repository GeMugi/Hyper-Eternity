package io.github.libxposed.service;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Só para compilar: a classe real está no classes.dex do HyperMusicCover. */
public final class XposedProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    public Bundle call(String method, String arg, Bundle extras) { return null; }
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    public String getType(Uri u) { return null; }
    public Uri insert(Uri u, ContentValues v) { return null; }
    public int delete(Uri u, String s, String[] a) { return 0; }
    public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
