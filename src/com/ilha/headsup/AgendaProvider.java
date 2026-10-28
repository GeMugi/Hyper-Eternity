package com.ilha.headsup;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.CalendarContract;

/**
 * Próximos eventos da agenda para o widget do bloqueio. A Interface do Sistema não tem acesso à
 * agenda; este app tem (permissão pedida nos ajustes) e entrega só para ela.
 */
public class AgendaProvider extends ContentProvider {
    public boolean onCreate() {
        return true;
    }

    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            if (!"com.android.systemui".equals(getCallingPackage())) return null;   // só a Interface do Sistema
            if (getContext().checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null;
            long now = System.currentTimeMillis();
            Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(b, now - 12L * 3600000L);
            ContentUris.appendId(b, now + 3L * 24 * 3600000L);
            MatrixCursor out = new MatrixCursor(new String[]{"title", "begin", "end", "allday"});
            Cursor c = getContext().getContentResolver().query(b.build(),
                    new String[]{CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY},
                    CalendarContract.Instances.VISIBLE + "=1", null, CalendarContract.Instances.BEGIN + " ASC");
            if (c == null) return out;
            try {
                int n = 0;
                while (c.moveToNext() && n < 4) {
                    long end = c.getLong(2);
                    if (end <= now) continue;
                    out.addRow(new Object[]{c.getString(0), c.getLong(1), end, c.getInt(3)});
                    n++;
                }
            } finally {
                c.close();
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    public String getType(Uri uri) {
        return null;
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
