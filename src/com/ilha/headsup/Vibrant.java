package com.ilha.headsup;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import java.util.ArrayList;

/** Hora das notificações com o efeito "vibrancy" do iOS (o mesmo dos tempos do player). */
final class Vibrant {
    private Vibrant() {}

    static final int TAG = 0x7e1a7012;

    static void row(final View row) {
        Prefs.refresh();
        Object t = row.getTag(TAG);
        if (t == null) {
            if (!Prefs.notifVib && !Prefs.bodyVib) return;
            L l = new L(row);
            row.setTag(TAG, l);
            row.getViewTreeObserver().addOnPreDrawListener(l);
            t = l;
        }
        ((L) t).scan();
    }

    static ArrayList<TextView> body;

    /** Conteúdo da notificação (texto, texto grande, linhas da caixa, mensagens), nunca o título. */
    static boolean isBody(String id, String cn) {
        if (id == null) id = "";
        if (id.contains("title") || id.contains("app_name") || id.contains("header") || id.contains("sender")) return false;
        return id.equals("text") || id.equals("big_text") || id.startsWith("inbox_text") || id.equals("message_text")
                || id.equals("notification_text") || id.equals("conversation_text") || cn.endsWith("ImageFloatingTextView")
                || cn.endsWith("MessagingTextMessage");
    }

    static void find(View v, ArrayList<TextView> out) {
        if (v instanceof TextView) {
            String cn = v.getClass().getName();
            String id = null;
            try {
                if (v.getId() != View.NO_ID) id = v.getResources().getResourceEntryName(v.getId());
            } catch (Throwable ignored) {
            }
            if (cn.endsWith("DateTimeView") || "time".equals(id) || "time_divider".equals(id)) out.add((TextView) v);
            else if (body != null && isBody(id, cn)) body.add((TextView) v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) find(g.getChildAt(i), out);
        }
    }

    static final class L implements ViewTreeObserver.OnPreDrawListener {
        final View row;
        ArrayList<TextView> views = new ArrayList<>(), texts = new ArrayList<>();
        int lastKey = Integer.MIN_VALUE;

        L(View row) {
            this.row = row;
        }

        void scan() {
            ArrayList<TextView> n = new ArrayList<>(), bt = new ArrayList<>();
            body = bt;
            find(row, n);
            body = null;
            views = n;
            texts = bt;
            lastKey = Integer.MIN_VALUE;
        }

        public boolean onPreDraw() {
            try {
                MediaCut.refresh();
                boolean night = MediaCut.night(row);
                int key = (night ? 1 : 0) | (Prefs.mvText << 1) | (Prefs.notifVib ? 1 << 9 : 0) | (views.size() << 10)
                        | (Prefs.bodyVib ? 1 << 18 : 0) | (Prefs.bodyVibN << 19) | (texts.size() << 26);
                if (key == lastKey) return true;
                lastKey = key;
                for (TextView tv : texts) {
                    if (Prefs.bodyVib) {
                        tv.setTextColor(MediaCut.color(night, Prefs.bodyVibN));
                        tv.getPaint().setBlendMode(MediaCut.mode(night));
                    } else {
                        tv.getPaint().setBlendMode(null);
                    }
                    tv.invalidate();
                }
                for (TextView tv : views) {
                    if (Prefs.notifVib) {
                        tv.setAlpha(1f);
                        tv.setTextColor(MediaCut.color(night, Prefs.mvText));
                        tv.getPaint().setBlendMode(MediaCut.mode(night));
                    } else {
                        tv.getPaint().setBlendMode(null);
                    }
                    tv.invalidate();
                }
            } catch (Throwable ignored) {
            }
            return true;
        }
    }
}
