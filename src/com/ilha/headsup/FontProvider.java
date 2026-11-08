package com.ilha.headsup;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

/**
 * Fontes próprias das letras Neon: o app guarda o arquivo escolhido e entrega só para a Interface
 * do Sistema (ela não consegue ler os arquivos do app). content://&lt;pacote&gt;.lyricfonts/&lt;posição&gt;
 */
public class FontProvider extends ContentProvider {
    public static java.io.File file(android.content.Context c, int slot) {
        java.io.File d = new java.io.File(c.getFilesDir(), "lyric_fonts");
        d.mkdirs();
        return new java.io.File(d, slot + ".ttf");
    }

    public boolean onCreate() {
        return true;
    }

    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        String caller = getCallingPackage();
        if (caller != null && !"com.android.systemui".equals(caller) && !getContext().getPackageName().equals(caller)) throw new java.io.FileNotFoundException("só a Interface do Sistema");
        int slot;
        try {
            slot = Integer.parseInt(uri.getLastPathSegment());
        } catch (Throwable t) {
            throw new java.io.FileNotFoundException(String.valueOf(uri));
        }
        java.io.File f = file(getContext(), slot);
        if (!f.isFile()) throw new java.io.FileNotFoundException("sem fonte " + slot);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    public String getType(Uri uri) {
        return "font/ttf";
    }

    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
