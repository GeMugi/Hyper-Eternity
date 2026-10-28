package com.ilha.headsup;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Notificações de conversa (Telegram, WhatsApp e outros, no modelo ConversationLayout).
 *
 * No HyperOS 3 esse modelo mostra a hora colada no título ("Nome • 2h") e um botão redondo de
 * expandir, enquanto os outros cartões mostram o horário no canto direito e não têm botão.
 * Depois que a Interface do Sistema monta o cartão (onContentUpdated dos wrappers de conversa):
 *  - hora: "agora", "5 min"... até 1 hora; a partir daí o horário (21:19), no formato dos outros
 *    cartões. A troca é feita no DateTimeView.update(), que o próprio Android chama a cada minuto,
 *    só nas horas marcadas por nós;
 *  - ela sai do cabeçalho e vai para o canto superior direito do cartão, na linha do título, com
 *    a mesma borda direita da seta (a mesma dos outros cartões), tamanho e cor dos outros
 *    cartões; o "•" antes dela some e o título termina antes dela;
 *  - seta: escondida (ajuste próprio). Expandir continua sendo puxar a notificação para baixo.
 *    Com a seta à mostra, ela desce o necessário para não encostar na hora (modelo do HyperOS) ou
 *    a hora fica à esquerda dela.
 * Tudo é refeito a cada layout do cartão, então vale também quando a notificação atualiza.
 */
final class ConvTime {
    private ConvTime() {}

    static final long HOUR = 60L * 60L * 1000L;

    /** Estado guardado no próprio cartão (some junto com ele). Chaves fora dos pacotes 0x01 e 0x7f. */
    static final int TAG_KEY = 0x7e1a7001;
    /** Marca nas DateTimeView cujas horas seguem a regra "relativa até 1 hora". */
    static final int TAG_TIME = 0x7e1a7002;

    static int idTime, idDivider, idTitle;
    static boolean idsResolved;
    static int logs, dumps, errs;
    static Field fRel, fMiui, fMillis;
    static Method mUpdate;

    static final class State implements View.OnLayoutChangeListener {
        ViewGroup root;
        View time, header, icon, box, mover;
        ViewGroup origParent;
        int origIndex;
        ViewGroup.LayoutParams origLp;
        int origTitleMax = Integer.MIN_VALUE;
        float origTextSize;
        ColorStateList origColors;
        boolean miuiWrapper, timeOn, hideOn, moved, hidBox;
        /** Distância da hora até a borda direita nos cartões do HyperOS (margem do conteúdo + da hora). */
        int resEnd = -1;

        public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            try {
                place(this);
            } catch (Throwable e) {
                if (errs++ < 5) ElasticCard.log("conversas: erro ao posicionar " + e);
            }
        }
    }

    static State stateOf(View root) {
        Object o = root.getTag(TAG_KEY);
        return o instanceof State ? (State) o : null;
    }

    // ------------------------------------------------------------------ instalação

    static void install(ClassLoader cl) {
        hookDateTime();
        guardGrouping(cl);
        int ok = 0;
        ok += hookWrapper(cl, "com.android.systemui.statusbar.notification.row.wrapper.MiuiNotificationConversationTemplateViewWrapper", true);
        ok += hookWrapper(cl, "com.android.systemui.statusbar.notification.row.wrapper.NotificationConversationTemplateViewWrapper", false);
        ElasticCard.log("conversas: " + (ok > 0 ? "instalado (" + ok + " modelos)" : "não encontrei os modelos de conversa")
                + " · formato da hora " + (mUpdate != null && fRel != null && fMillis != null ? "ok" : "indisponível")
                + (fMiui != null ? " (com o formato do HyperOS)" : ""));
    }

    static void hookDateTime() {
        try {
            Class dtv = Class.forName("android.widget.DateTimeView");
            fRel = Refl.field(dtv, "mShowRelativeTime");
            fMiui = Refl.field(dtv, "mShowMiuiRelativeTime");
            fMillis = Refl.field(dtv, "mTimeMillis");
            mUpdate = Refl.method(dtv, "update");
            if (mUpdate == null || fRel == null || fMillis == null) return;
            XposedBridge.hookMethod(mUpdate, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        View v = (View) p.thisObject;
                        if (v.getTag(TAG_TIME) != null) choose(v);
                    } catch (Throwable t) {
                    }
                }
            });
        } catch (Throwable t) {
            ElasticCard.log("conversas: DateTimeView indisponível " + t);
        }
    }

    /** Até 1 hora: relativa ("agora", "5 min"); depois, o horário no formato do HyperOS. */
    static void choose(View v) throws Exception {
        long when = fMillis.getLong(v);
        long age = System.currentTimeMillis() - when;
        boolean rel = when > 0 && age > -60000L && age < HOUR;
        fRel.setBoolean(v, rel);
        if (fMiui != null) fMiui.setBoolean(v, !rel);
    }

    static int hookWrapper(ClassLoader cl, String name, final boolean miui) {
        try {
            final Class c = Class.forName(name, false, cl);
            Method content = null;
            Method[] ms = c.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                if ("onContentUpdated".equals(ms[i].getName()) && ms[i].getParameterTypes().length == 1) content = ms[i];
            }
            if (content == null) return 0;
            XposedBridge.hookMethod(content, new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        apply(p.thisObject, miui);
                    } catch (Throwable t) {
                        if (errs++ < 5) ElasticCard.log("conversas: erro " + t);
                    }
                }
            });
            // a seta volta a aparecer quando o Android recalcula se o cartão expande
            Method exp = Refl.anyMethod(c, "updateExpandability");
            if (exp != null) {
                XposedBridge.hookMethod(exp, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (!c.isInstance(p.thisObject)) return;
                            View v = (View) Refl.get(p.thisObject, "mView");
                            State st = v != null ? stateOf(v) : null;
                            if (st != null && st.hideOn) hideBox(st);
                        } catch (Throwable t) {
                        }
                    }
                });
            }
            return 1;
        } catch (Throwable t) {
            ElasticCard.log("conversas: " + name.substring(name.lastIndexOf('.') + 1) + " indisponível (" + t + ")");
            return 0;
        }
    }

    static void resolveIds() {
        if (idsResolved) return;
        idsResolved = true;
        Resources r = Resources.getSystem();
        idTime = r.getIdentifier("time", "id", "android");
        idDivider = r.getIdentifier("time_divider", "id", "android");
        idTitle = r.getIdentifier("conversation_text", "id", "android");
    }

    static View viewField(Object o, String name) {
        Object v = Refl.get(o, name);
        return v instanceof View ? (View) v : null;
    }

    // ------------------------------------------------------------------ aplicar

    static void apply(Object w, boolean miui) {
        View v = viewField(w, "mView");
        if (!(v instanceof ViewGroup)) return;
        Prefs.refresh();
        resolveIds();
        final ViewGroup root = (ViewGroup) v;
        boolean wantTime = Prefs.convTime, wantHide = Prefs.convNoExpand;
        View time = idTime != 0 ? root.findViewById(idTime) : null;
        boolean timeOn = wantTime && time != null;
        State st = stateOf(root);
        if (st != null && (st.timeOn != timeOn || st.hideOn != wantHide || (timeOn && st.time != time))) {
            restore(st);
            st = null;
        }
        if (!timeOn && !wantHide) return;
        if (st == null) {
            st = new State();
            st.root = root;
            st.time = time;
            st.miuiWrapper = miui;
            st.timeOn = timeOn;
            st.hideOn = wantHide;
            root.setTag(TAG_KEY, st);
        }
        // a seta: ícone (para a borda direita), caixa (o que esconde) e o que desce (com a seta à mostra)
        st.icon = viewField(w, miui ? "expandButton" : "expandBtn");
        st.box = viewField(w, miui ? "expandButtonContainer" : "expandBtnContainer");
        st.mover = miui ? viewField(w, "expandButtonInnerContainer") : null;
        View title = idTitle != 0 ? root.findViewById(idTitle) : null;
        st.header = title != null && title.getParent() instanceof View ? (View) title.getParent() : null;

        if (st.hideOn) hideBox(st);

        if (st.timeOn) {
            Context ctx = (Context) Refl.get(w, "mContext");
            Context c = ctx != null ? ctx : root.getContext();
            st.resEnd = miuiEnd(c);
            styleTime(st, c);
            if (root instanceof FrameLayout && root.getLayoutDirection() != View.LAYOUT_DIRECTION_RTL) {
                moveTime(st);
            } else if (logs++ < 3) {
                // sem mover, a hora continua depois do título, com o "•"
                ElasticCard.log("conversas: só o formato da hora (" + root.getClass().getName() + ")");
            }
        }
        root.removeOnLayoutChangeListener(st);
        root.addOnLayoutChangeListener(st);

        if (dumps < 2) {
            dumps++;
            root.post(new Runnable() {
                public void run() {
                    dump(root);
                }
            });
        }
    }

    static void hideBox(State st) {
        View box = st.box != null ? st.box : st.icon;
        if (box != null && box.getVisibility() == View.VISIBLE) {
            box.setVisibility(View.INVISIBLE);
            st.hidBox = true;
        }
        if (st.mover != null && st.mover.getTranslationY() != 0) st.mover.setTranslationY(0);
    }

    /**
     * Nos modelos do HyperOS a hora termina a miui_notification_content_margin_end (coluna) +
     * notification_time_line_1_margin_end (hora) da borda direita do cartão: 12 + 4 dp no vili.
     */
    static int miuiEnd(Context ctx) {
        try {
            Resources r = ctx.getResources();
            int a = r.getIdentifier("miui_notification_content_margin_end", "dimen", "com.android.systemui");
            int b = r.getIdentifier("notification_time_line_1_margin_end", "dimen", "com.android.systemui");
            if (a != 0 && b != 0) return r.getDimensionPixelSize(a) + r.getDimensionPixelSize(b);
        } catch (Throwable t) {
        }
        return Math.round(16 * ctx.getResources().getDisplayMetrics().density);
    }

    /** Formato (marca + regra de 1 hora), tamanho e cor da hora dos outros cartões. */
    static void styleTime(State st, Context ctx) {
        View time = st.time;
        if (time instanceof TextView) {
            TextView tv = (TextView) time;
            if (st.origTextSize == 0) {
                st.origTextSize = tv.getTextSize();
                st.origColors = tv.getTextColors();
            }
            try {
                Resources r = ctx.getResources();
                int size = r.getIdentifier("notification_subtext_size", "dimen", "com.android.systemui");
                if (size != 0) tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, r.getDimension(size));
                int color = r.getIdentifier("notification_time_color", "color", "com.android.systemui");
                if (color != 0) tv.setTextColor(ctx.getColor(color));
            } catch (Throwable t) {
            }
        }
        time.setTag(TAG_TIME, Boolean.TRUE);
        try {
            if (fRel != null && fMillis != null) choose(time);
            if (mUpdate != null) mUpdate.invoke(time);
        } catch (Throwable t) {
            if (errs++ < 5) ElasticCard.log("conversas: formato da hora " + t);
        }
    }

    static void moveTime(State st) {
        View time = st.time;
        ViewGroup root = st.root;
        if (idDivider != 0) {
            View d = root.findViewById(idDivider);
            if (d != null) d.setVisibility(View.GONE);
        }
        if (time.getParent() == root) return;
        ViewParent pp = time.getParent();
        if (!(pp instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) pp;
        st.origParent = parent;
        st.origIndex = parent.indexOfChild(time);
        st.origLp = time.getLayoutParams();
        parent.removeView(time);
        root.addView(time, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
        st.moved = true;
        if (logs++ < 3) ElasticCard.log("conversas: hora levada para o canto (" + (st.miuiWrapper ? "modelo HyperOS" : "modelo Android") + ")"
                + (st.hideOn ? ", seta escondida" : ""));
    }

    // ------------------------------------------------------------------ posicionar (a cada layout)

    static int guardLogs;

    /**
     * A hora da conversa sai da linha do topo (vai para o canto). Ao tirar um cartão de um grupo, o
     * HyperOS 4 procura a hora na linha do topo e, sem achar, derrubava a Interface do Sistema.
     * Sem a hora ali, essa arrumação da linha do topo (só visual) é pulada.
     */
    static void guardGrouping(ClassLoader cl) {
        try {
            Class gu = Class.forName("com.android.systemui.statusbar.NotificationGroupingUtil", false, cl);
            final int tid = android.content.res.Resources.getSystem().getIdentifier("time", "id", "android");
            int n = 0;
            for (java.lang.reflect.Method m : gu.getDeclaredMethods()) {
                if (!m.getName().equals("sanitizeTopLine")) continue;
                com.ilha.headsup.xp.XposedBridge.hookMethod(m, new com.ilha.headsup.xp.XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            Object g = p.args.length > 0 ? p.args[0] : null;
                            if (g instanceof ViewGroup && tid != 0 && ((ViewGroup) g).findViewById(tid) == null) {
                                p.setResult(null);
                                if (guardLogs++ < 3) ElasticCard.log("conversas: linha do topo sem a hora (está no canto), arrumação pulada");
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                n++;
            }
            ElasticCard.log("conversas: proteção da linha do topo (" + n + ")");
        } catch (Throwable t) {
            ElasticCard.log("conversas: proteção da linha do topo indisponível: " + t);
        }
    }

    /** A seta está de fato à vista? (no HyperOS 4 a heads-up "sem botões" deixa a seta transparente) */
    static boolean seen(View v, View root) {
        for (View c = v; c != null && c != root; c = c.getParent() instanceof View ? (View) c.getParent() : null)
            if (c.getAlpha() < 0.05f) return false;
        return true;
    }

    static void place(State st) {
        ViewGroup root = st.root;
        if (st.hideOn) hideBox(st);
        View time = st.time;
        if (!st.moved || time == null || time.getParent() != root || root.getWidth() == 0) return;
        if (idDivider != 0) {
            View d = root.findViewById(idDivider);
            if (d != null && d.getVisibility() != View.GONE) d.setVisibility(View.GONE);
        }
        float density = root.getResources().getDisplayMetrics().density;
        int gap = Math.round(6 * density);
        int w = root.getWidth(), h = root.getHeight();
        int timeW = time.getMeasuredWidth(), timeH = time.getMeasuredHeight();
        View title = idTitle != 0 ? root.findViewById(idTitle) : null;

        // altura: mesma linha de base do título
        int top;
        if (title instanceof TextView && time instanceof TextView && title.getVisibility() == View.VISIBLE
                && title.getHeight() > 0 && relTop(title, root) != Integer.MIN_VALUE) {
            top = relTop(title, root) + ((TextView) title).getBaseline() - ((TextView) time).getBaseline();
        } else {
            top = root.getPaddingTop() + Math.round(16 * density);
        }
        if (top < 0) top = 0;

        // borda direita: a mesma distância dos cartões do HyperOS
        int end = st.resEnd >= 0 ? st.resEnd : Math.round(16 * density);
        float shift = 0;
        // com a seta à mostra: ela desce para baixo da hora (modelo do HyperOS) ou a hora fica à esquerda dela
        View icon = st.icon;
        if (!st.hideOn && icon != null && icon.getVisibility() == View.VISIBLE && icon.getWidth() > 0 && seen(icon, root)
                && (st.box == null || st.box.getVisibility() == View.VISIBLE) && relTop(icon, root) != Integer.MIN_VALUE) {
            int cl = relLeft(icon, root), ct = relTop(icon, root);
            int cb = ct + icon.getHeight();
            boolean overlapX = cl < w - end && cl + icon.getWidth() > w - end - timeW;
            int need = top + timeH + Math.round(2 * density) - ct;
            if (overlapX && need > 0 && cb > top) {
                int room = h - root.getPaddingBottom() - cb;
                if (st.mover != null && need <= room + Math.round(4 * density)) {
                    shift = need;
                } else {
                    end = Math.max(end, w - cl + gap);
                }
            }
        }
        if (!st.hideOn && st.mover != null && st.mover.getTranslationY() != shift) st.mover.setTranslationY(shift);

        // a FrameLayout posiciona pelas margens a partir do padding dela
        int topMargin = top - root.getPaddingTop();
        int endMargin = end - root.getPaddingRight();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) time.getLayoutParams();
        if (lp.topMargin != topMargin || lp.getMarginEnd() != endMargin || lp.gravity != (Gravity.TOP | Gravity.END)) {
            lp.topMargin = topMargin;
            lp.setMarginEnd(endMargin);
            lp.rightMargin = endMargin;
            lp.gravity = Gravity.TOP | Gravity.END;
            time.setLayoutParams(lp);
        }

        // o título termina antes da hora: até a hora, menos o que vem depois dele no cabeçalho
        // (nome do app, ícones). A posição do título não depende da largura dele: a conta não realimenta.
        if (title instanceof TextView && st.header instanceof ViewGroup) {
            TextView tv = (TextView) title;
            if (st.origTitleMax == Integer.MIN_VALUE) st.origTitleMax = tv.getMaxWidth();
            int want = st.origTitleMax;
            int tl = relLeft(title, root);
            if (tl != Integer.MIN_VALUE && time.getVisibility() == View.VISIBLE && timeW > 0) {
                int limit = w - end - timeW - gap;
                int after = 0;
                ViewGroup hg = (ViewGroup) st.header;
                boolean past = false;
                for (int i = 0; i < hg.getChildCount(); i++) {
                    View c = hg.getChildAt(i);
                    if (c == title) {
                        past = true;
                        continue;
                    }
                    if (!past || c.getVisibility() == View.GONE) continue;
                    after += c.getWidth();
                    if (c.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams ml = (ViewGroup.MarginLayoutParams) c.getLayoutParams();
                        after += ml.leftMargin + ml.rightMargin;
                    }
                }
                int max = Math.max(Math.round(48 * density), limit - tl - after);
                if (st.origTitleMax > 0) max = Math.min(max, st.origTitleMax);
                want = max;
            }
            if (tv.getMaxWidth() != want) tv.setMaxWidth(want < 0 ? Integer.MAX_VALUE : want);
        }
    }

    // ------------------------------------------------------------------ desfazer

    static void restore(State st) {
        ViewGroup root = st.root;
        try {
            root.removeOnLayoutChangeListener(st);
            View time = st.time;
            if (time != null) {
                if (st.moved && st.origParent != null && time.getParent() == root) {
                    root.removeView(time);
                    int idx = Math.min(st.origIndex, st.origParent.getChildCount());
                    st.origParent.addView(time, idx, st.origLp);
                }
                if (time instanceof TextView && st.origTextSize > 0) {
                    ((TextView) time).setTextSize(TypedValue.COMPLEX_UNIT_PX, st.origTextSize);
                    if (st.origColors != null) ((TextView) time).setTextColor(st.origColors);
                }
                if (time.getTag(TAG_TIME) != null) {
                    time.setTag(TAG_TIME, null);
                    if (fRel != null) fRel.setBoolean(time, true);
                    if (fMiui != null) fMiui.setBoolean(time, false);
                    if (mUpdate != null) mUpdate.invoke(time);
                }
                if (st.moved && idDivider != 0) {
                    View d = root.findViewById(idDivider);
                    if (d != null) d.setVisibility(time.getVisibility());
                }
            }
            if (st.mover != null) st.mover.setTranslationY(0);
            View title = idTitle != 0 ? root.findViewById(idTitle) : null;
            if (title instanceof TextView && st.origTitleMax != Integer.MIN_VALUE) {
                ((TextView) title).setMaxWidth(st.origTitleMax < 0 ? Integer.MAX_VALUE : st.origTitleMax);
            }
            View box = st.box != null ? st.box : st.icon;
            if (st.hidBox && box != null && box.getVisibility() == View.INVISIBLE) box.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            if (errs++ < 5) ElasticCard.log("conversas: erro ao desfazer " + t);
        }
        root.setTag(TAG_KEY, null);
    }

    // ------------------------------------------------------------------ utilidades

    static int relLeft(View v, View root) {
        int x = 0;
        View c = v;
        while (c != root) {
            x += c.getLeft();
            ViewParent p = c.getParent();
            if (!(p instanceof View)) return Integer.MIN_VALUE;
            c = (View) p;
        }
        return x;
    }

    static int relTop(View v, View root) {
        int y = 0;
        View c = v;
        while (c != root) {
            y += c.getTop();
            ViewParent p = c.getParent();
            if (!(p instanceof View)) return Integer.MIN_VALUE;
            c = (View) p;
        }
        return y;
    }

    /** Registra no log do LSPosed como o cartão de conversa é montado (sem o texto das mensagens). */
    static void dump(ViewGroup root) {
        StringBuilder sb = new StringBuilder("conversas: estrutura do cartão ").append(root.getWidth()).append('x').append(root.getHeight());
        dumpInto(sb, root, root, 0);
        String s = sb.toString();
        for (int i = 0; i < s.length(); i += 3000) ElasticCard.log(s.substring(i, Math.min(s.length(), i + 3000)));
    }

    static void dumpInto(StringBuilder sb, View v, ViewGroup root, int depth) {
        if (depth > 7) return;
        String name;
        try {
            name = v.getId() != View.NO_ID ? v.getResources().getResourceEntryName(v.getId()) : "-";
        } catch (Throwable t) {
            name = "#" + Integer.toHexString(v.getId());
        }
        sb.append("\n");
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName()).append(' ').append(name)
                .append(" [").append(relLeft(v, root)).append(',').append(relTop(v, root)).append(' ')
                .append(v.getWidth()).append('x').append(v.getHeight()).append(']')
                .append(v.getVisibility() == View.VISIBLE ? "" : (v.getVisibility() == View.GONE ? " gone" : " invisível"));
        if (v.getTranslationY() != 0) sb.append(" ty=").append(v.getTranslationY());
        if (v instanceof TextView && depth > 0) {
            CharSequence t = ((TextView) v).getText();
            sb.append(" texto=").append(t == null ? 0 : t.length()).append(" ").append(Math.round(((TextView) v).getTextSize())).append("px");
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dumpInto(sb, g.getChildAt(i), root, depth + 1);
        }
    }
}
