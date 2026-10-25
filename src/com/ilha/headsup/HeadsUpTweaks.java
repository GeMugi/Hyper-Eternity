package com.ilha.headsup;

import android.app.Notification;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Ajustes do conteúdo da heads-up:
 *  - sem botões: a heads-up usa a versão compacta do cartão (título e texto), sem as ações;
 *    ligações e alarmes mantêm os botões;
 *  - uma por notificação: quando o HyperOS ia mostrar o grupo inteiro do app (resumo com várias
 *    linhas), a heads-up vai para a notificação mais nova do grupo, sozinha.
 */
final class HeadsUpTweaks {
    private HeadsUpTweaks() {}

    static Field fBuilderN;
    static boolean loggedNoActions, loggedSeparate, loggedErr;

    // pipeline
    static Method mHandle, mIsBinding, mGetParent, mIsHeadsUpEntry;
    static Constructor cPosted;
    static Field fEntry, fEver, fAgain, fAdded, fPinnedByUser, fIsHu, fBinding, fSbn, fChildren, fHum, fGroupSummary;
    static boolean busy;

    static void install(ClassLoader cl) {
        installNoActions();
        installHideActions(cl);
        installSeparate(cl);
    }

    // ------------------------------------------------ sem botões (na view da heads-up)

    static Field fHuChild, fContracted, fExpanded, fNcvEntry, fEntrySbn, fNcvRow, fVisibleType;
    static Field fWhenPinned, fUserExpanded, fUserLocked;
    static Method mIsPinned;
    static int[] actionIds = new int[0];
    static int idMargin;
    static boolean idsResolved;
    static final java.util.WeakHashMap<View, Integer> hiddenByUs = new java.util.WeakHashMap<View, Integer>();
    static final java.util.WeakHashMap<View, Integer> marginByUs = new java.util.WeakHashMap<View, Integer>();
    static int diagLogs;
    static boolean loggedHide;

    /**
     * Enquanto a notificação está fixada no topo (heads-up) e recolhida, as views dela ficam sem a
     * fila de botões e sem as respostas rápidas — tanto nos modelos do Android quanto nos do
     * HyperOS (que usam ids próprios da Interface do Sistema). Quando você expande a heads-up
     * (puxando para baixo ou tocando na seta), os botões voltam.
     */
    static void installHideActions(ClassLoader cl) {
        try {
            Class ncv = Class.forName("com.android.systemui.statusbar.notification.row.NotificationContentView", false, cl);
            Class row = Class.forName("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", false, cl);
            fHuChild = Refl.field(ncv, "mHeadsUpChild");
            fContracted = Refl.field(ncv, "mContractedChild");
            fExpanded = Refl.field(ncv, "mExpandedChild");
            fNcvEntry = Refl.field(ncv, "mNotificationEntry");
            fNcvRow = Refl.field(ncv, "mContainingNotification");
            fVisibleType = Refl.field(ncv, "mVisibleType");
            fWhenPinned = Refl.field(row, "mExpandedWhenPinned");
            fUserExpanded = Refl.field(row, "mUserExpanded");
            fUserLocked = Refl.field(row, "mUserLocked");
            mIsPinned = Refl.method(row, "isPinned");
            Class entry = Class.forName("com.android.systemui.statusbar.notification.collection.NotificationEntry", false, cl);
            fEntrySbn = Refl.field(entry, "mSbn");
            XposedBridge.hookMethod(ncv.getDeclaredMethod("onMeasure", int.class, int.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        enforce((View) p.thisObject);
                    } catch (Throwable t) {
                        if (diagLogs < 6) {
                            diagLogs++;
                            ElasticCard.log("heads-up sem botões (view) falhou: " + t);
                        }
                    }
                }
            });
            ElasticCard.log("heads-up sem botões: proteção na view instalada");
        } catch (Throwable t) {
            ElasticCard.log("heads-up sem botões (view) indisponível: " + t);
        }
    }

    static void resolveIds(View v) {
        if (idsResolved) return;
        idsResolved = true;
        android.content.res.Resources sys = android.content.res.Resources.getSystem();
        android.content.res.Resources ui = v.getResources();
        int[] ids = new int[] {
                sys.getIdentifier("actions_container", "id", "android"),
                sys.getIdentifier("smart_reply_container", "id", "android"),
                ui.getIdentifier("actions_container", "id", "com.android.systemui"),
                ui.getIdentifier("actions", "id", "com.android.systemui"),
        };
        int n = 0;
        for (int i = 0; i < ids.length; i++) if (ids[i] != 0) n++;
        actionIds = new int[n];
        n = 0;
        for (int i = 0; i < ids.length; i++) if (ids[i] != 0) actionIds[n++] = ids[i];
        idMargin = sys.getIdentifier("notification_action_list_margin_target", "id", "android");
    }

    static boolean flag(Field f, Object o) {
        try {
            return f != null && o != null && f.getBoolean(o);
        } catch (Throwable t) {
            return false;
        }
    }

    static void enforce(View content) throws Throwable {
        Object row = fNcvRow != null ? fNcvRow.get(content) : null;
        if (row == null) return;
        resolveIds(content);
        View hu = (View) fHuChild.get(content);
        View co = fContracted != null ? (View) fContracted.get(content) : null;
        View ex = fExpanded != null ? (View) fExpanded.get(content) : null;
        Prefs.refresh();
        boolean pinned = mIsPinned != null && Boolean.TRUE.equals(mIsPinned.invoke(row));
        boolean userOpened = flag(fWhenPinned, row) || flag(fUserExpanded, row) || flag(fUserLocked, row);
        boolean keep = false;
        if (Prefs.huNoActions) {
            Object e = fNcvEntry.get(content);
            Object sbn = e != null ? fEntrySbn.get(e) : null;
            Notification n = sbn instanceof StatusBarNotification ? ((StatusBarNotification) sbn).getNotification() : null;
            keep = n != null && keepActions(n);
        }
        // recolhida = sem botões; a versão "heads-up" só aparece fixada, então fica sempre sem eles
        boolean collapsedHun = Prefs.huNoActions && !keep && !userOpened;
        boolean hide = collapsedHun && pinned;
        int found = 0;
        found += applyHide(hu, collapsedHun);
        found += applyHide(co, hide);
        found += applyHide(ex, hide);
        if (pinned && diagLogs < 4) {
            diagLogs++;
            int vt = fVisibleType != null ? fVisibleType.getInt(content) : -9;
            ElasticCard.log("heads-up medida: mostrando " + typeName(vt) + " · heads-up=" + describe(hu) + " recolhida=" + describe(co)
                    + " expandida=" + describe(ex) + " · aberta pelo usuário=" + userOpened + " · escondidos=" + found);
        }
        if (hide && found > 0 && !loggedHide) {
            loggedHide = true;
            ElasticCard.log("heads-up: botões escondidos enquanto recolhida (aparecem ao expandir)");
        }
    }

    static String typeName(int t) {
        return t == 0 ? "recolhida" : t == 1 ? "expandida" : t == 2 ? "heads-up" : t == 3 ? "linha única" : String.valueOf(t);
    }

    /** Resumo para o log: existe? tem botões? */
    static String describe(View v) {
        if (v == null) return "não";
        int n = 0;
        for (int i = 0; i < actionIds.length; i++) if (v.findViewById(actionIds[i]) != null) n++;
        return n > 0 ? "com botões" : "sem botões";
    }

    /** Esconde (ou devolve) os botões de uma das versões; retorna quantas partes estão escondidas por nós. */
    static int applyHide(View v, boolean hide) {
        if (v == null) return 0;
        int n = 0;
        for (int i = 0; i < actionIds.length; i++) n += toggle(v, actionIds[i], hide);
        if (idMargin != 0) {
            View m = v.findViewById(idMargin);
            if (m != null && m.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) m.getLayoutParams();
                Integer orig = marginByUs.get(m);
                // o espaço reservado embaixo para os botões some junto com eles
                if (hide && n > 0 && lp.bottomMargin != 0) {
                    if (orig == null) marginByUs.put(m, Integer.valueOf(lp.bottomMargin));
                    lp.bottomMargin = 0;
                    m.setLayoutParams(lp);
                } else if (!hide && orig != null) {
                    lp.bottomMargin = orig.intValue();
                    marginByUs.remove(m);
                    m.setLayoutParams(lp);
                }
            }
        }
        return n;
    }

    /** Esconde (ou devolve) uma parte; retorna 1 se ela existe e está escondida por nós. */
    static int toggle(View root, int id, boolean hide) {
        if (id == 0) return 0;
        View v = root.findViewById(id);
        if (v == null) return 0;
        if (hide) {
            if (v.getVisibility() != View.GONE) {
                hiddenByUs.put(v, Integer.valueOf(v.getVisibility()));
                v.setVisibility(View.GONE);
            }
            return hiddenByUs.containsKey(v) ? 1 : 0;
        }
        Integer was = hiddenByUs.remove(v);
        if (was != null) v.setVisibility(was.intValue());
        return 0;
    }

    // ---------------------------------------------------------------- sem botões

    static void installNoActions() {
        try {
            fBuilderN = Refl.field(Notification.Builder.class, "mN");
            XC_MethodHook noActions = new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    Prefs.refresh();
                    if (!Prefs.huNoActions || p.getResult() == null) return;
                    try {
                        Notification.Builder b = (Notification.Builder) p.thisObject;
                        Notification n = fBuilderN != null ? (Notification) fBuilderN.get(b) : null;
                        if (n == null || keepActions(n)) return;
                        RemoteViews compact = b.createContentView();
                        if (compact == null) return;
                        p.setResult(compact);
                        if (!loggedNoActions) {
                            loggedNoActions = true;
                            ElasticCard.log("heads-up sem botões ativo");
                        }
                    } catch (Throwable t) {
                        if (!loggedErr) {
                            loggedErr = true;
                            ElasticCard.log("heads-up sem botões falhou: " + t);
                        }
                    }
                }
            };
            // todas as versões (com e sem o parâmetro de altura), caso a pública seja só um atalho
            int hooked = 0;
            Method[] ms = Notification.Builder.class.getDeclaredMethods();
            for (int i = 0; i < ms.length; i++) {
                if (!ms[i].getName().equals("createHeadsUpContentView")) continue;
                if (!RemoteViews.class.equals(ms[i].getReturnType())) continue;
                XposedBridge.hookMethod(ms[i], noActions);
                hooked++;
            }
            ElasticCard.log("heads-up sem botões: " + hooked + " versão(ões) do modelo interceptadas");
        } catch (Throwable t) {
            ElasticCard.log("heads-up sem botões indisponível: " + t);
        }
    }

    /** Ligações e alarmes precisam dos botões (atender, recusar, adiar...). */
    static boolean keepActions(Notification n) {
        String cat = n.category;
        if (Notification.CATEGORY_CALL.equals(cat) || Notification.CATEGORY_ALARM.equals(cat)) return true;
        Object tpl = n.extras != null ? n.extras.get(Notification.EXTRA_TEMPLATE) : null;
        return tpl != null && String.valueOf(tpl).contains("CallStyle");
    }

    // ---------------------------------------------------------------- uma por notificação

    static void installSeparate(ClassLoader cl) {
        try {
            String P = "com.android.systemui.statusbar.notification.collection.";
            Class coord = Class.forName(P + "coordinator.HeadsUpCoordinator", false, cl);
            Class posted = Class.forName(P + "coordinator.HeadsUpCoordinator$PostedEntry", false, cl);
            Class mutator = Class.forName(P + "coordinator.HunMutatorImpl", false, cl);
            Class entry = Class.forName(P + "NotificationEntry", false, cl);
            Class group = Class.forName(P + "GroupEntry", false, cl);
            mHandle = coord.getMethod("handlePostedEntry", posted, mutator, String.class);
            mIsBinding = Refl.anyMethod(coord, "isEntryBinding");
            mGetParent = Refl.method(entry, "getParent");
            cPosted = posted.getConstructor(entry, boolean.class, boolean.class, boolean.class, boolean.class,
                    boolean.class, boolean.class, boolean.class);
            fEntry = Refl.field(posted, "entry");
            fEver = Refl.field(posted, "shouldHeadsUpEver");
            fAgain = Refl.field(posted, "shouldHeadsUpAgain");
            fAdded = Refl.field(posted, "wasAdded");
            fPinnedByUser = Refl.field(posted, "isPinnedByUser");
            fIsHu = Refl.field(posted, "isHeadsUpEntry");
            fBinding = Refl.field(posted, "isBinding");
            fSbn = Refl.field(entry, "mSbn");
            fChildren = Refl.field(group, "mChildren");
            fGroupSummary = Refl.field(group, "mSummary");
            fHum = Refl.field(coord, "mHeadsUpManager");
            XposedBridge.hookMethod(mHandle, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    separate(p);
                }
            });
            ElasticCard.log("heads-up separada instalada");
        } catch (Throwable t) {
            ElasticCard.log("heads-up separada indisponível: " + t);
        }
    }

    /** Resumo de grupo que pediu heads-up antes de a mensagem nova chegar: espera por ela. */
    static final class Pending {
        long when, deadline;
    }

    static final java.util.HashMap<String, Pending> pending = new java.util.HashMap<String, Pending>();

    /**
     * Quando cada notificação mudou de conteúdo pela última vez (chegada real da mensagem).
     * A hora de postagem não serve (o Telegram reposta todas a cada mensagem) e a hora da mensagem
     * também não (uma mensagem atrasada tem hora antiga). Então o módulo guarda uma "assinatura"
     * do conteúdo de cada notificação e anota quando ela muda.
     */
    static final java.util.HashMap<String, long[]> seen = new java.util.HashMap<String, long[]>();
    static final long RECENT_MS = 4000L;

    static long signature(Notification n) {
        long h = 17L;
        if (n == null) return h;
        h = h * 31L + n.when;
        android.os.Bundle x = n.extras;
        if (x != null) {
            CharSequence t = x.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence tx = x.getCharSequence(Notification.EXTRA_TEXT);
            h = h * 31L + (t == null ? 0 : t.toString().hashCode());
            h = h * 31L + (tx == null ? 0 : tx.toString().hashCode());
            try {
                android.os.Parcelable[] msgs = x.getParcelableArray(Notification.EXTRA_MESSAGES);
                if (msgs != null) {
                    h = h * 31L + msgs.length;
                    if (msgs.length > 0 && msgs[msgs.length - 1] instanceof android.os.Bundle) {
                        android.os.Bundle m = (android.os.Bundle) msgs[msgs.length - 1];
                        h = h * 31L + m.getLong("time", 0L);
                        CharSequence mt = m.getCharSequence("text");
                        h = h * 31L + (mt == null ? 0 : mt.toString().hashCode());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return h;
    }

    /** Quando o conteúdo desta notificação mudou pela última vez (0 se o módulo não sabe). */
    static long changedAt(StatusBarNotification s) {
        long[] v = s != null ? seen.get(s.getKey()) : null;
        return v != null ? v[1] : 0L;
    }

    /** Atualiza a assinatura; devolve {quando mudou, 1 se mudou agora}. */
    static long[] touch(StatusBarNotification s, long now) {
        String key = s.getKey();
        long sig = signature(s.getNotification());
        long[] v = seen.get(key);
        if (v == null) {
            // primeira vez que o módulo vê esta notificação: usa a hora da mensagem como estimativa
            long est = Math.min(now, msgTime(s.getNotification(), s));
            v = new long[] {sig, est};
            if (seen.size() > 400) seen.clear();
            seen.put(key, v);
            return new long[] {est, now - est < RECENT_MS ? 1L : 0L};
        }
        if (v[0] != sig) {
            v[0] = sig;
            v[1] = now;
            return new long[] {now, 1L};
        }
        return new long[] {v[1], 0L};
    }

    static void separate(XC_MethodHook.MethodHookParam p) {
        if (busy) return;
        Prefs.refresh();
        if (!Prefs.huSeparate) return;
        try {
            Object pe = p.args[0];
            Object e = fEntry.get(pe);
            StatusBarNotification sbn = (StatusBarNotification) fSbn.get(e);
            if (sbn == null || sbn.getNotification() == null) return;
            Notification n = sbn.getNotification();
            long now = System.currentTimeMillis();
            long[] mine = touch(sbn, now);
            String gk = sbn.getGroupKey();
            if (!n.isGroupSummary()) {
                String reason = p.args[2] instanceof String ? (String) p.args[2] : "";
                if (reason.contains("transfer-target") && fEver.getBoolean(pe)) {
                    // o HyperOS escolheu um filho do grupo pela ordem dele: confere se é o que acabou de chegar
                    fixTransfer(p, pe, e, sbn, now);
                    return;
                }
                // é a mensagem nova que o resumo do grupo estava esperando? então ela vira a heads-up
                Pending pd = gk != null ? pending.get(gk) : null;
                if (pd == null) return;
                if (now > pd.deadline) {
                    pending.remove(gk);
                    return;
                }
                if (mine[1] == 1L) {
                    pending.remove(gk);
                    fEver.setBoolean(pe, true);
                    fAgain.setBoolean(pe, true);
                    logSep("heads-up do grupo " + sbn.getPackageName() + " foi para a mensagem que acabou de chegar");
                }
                return;
            }
            if (!fEver.getBoolean(pe)) return;
            boolean isHu = fIsHu.getBoolean(pe), binding = fBinding.getBoolean(pe);
            boolean wants = fAdded.getBoolean(pe) || fAgain.getBoolean(pe) || isHu || binding;
            if (!wants) return;
            Object child = newestChild(e, now);
            StatusBarNotification cs = child != null ? (StatusBarNotification) fSbn.get(child) : null;
            long changed = cs != null ? touch(cs, now)[0] : 0L;
            if (child == null || cs == null || now - changed > RECENT_MS) {
                // nenhuma conversa do grupo mudou agora: a mensagem nova ainda vai chegar como
                // notificação própria. Em vez de mostrar uma antiga, espera por ela (até 2,5 s)
                Pending pd = new Pending();
                pd.when = now;
                pd.deadline = now + 2500L;
                if (gk != null) pending.put(gk, pd);
                fEver.setBoolean(pe, false);
                logSep("heads-up do grupo " + sbn.getPackageName() + ": esperando a mensagem nova chegar");
                return;
            }
            transferTo(p.thisObject, p.args[1], child, cs, fPinnedByUser.getBoolean(pe));
            if (gk != null) pending.remove(gk);
            // o resumo do grupo não vira heads-up (se já era, sai)
            fEver.setBoolean(pe, false);
            logSep("heads-up do grupo " + sbn.getPackageName() + " foi para a conversa que mudou há " + (now - changed) + " ms");
        } catch (Throwable t) {
            busy = false;
            if (!loggedErr) {
                loggedErr = true;
                ElasticCard.log("heads-up separada falhou: " + t);
            }
        }
    }

    /** Mostra a heads-up de um filho do grupo, do jeito que o próprio HyperOS faz na transferência. */
    static void transferTo(Object coord, Object mutator, Object child, StatusBarNotification cs, boolean pinnedByUser) throws Throwable {
        boolean childHu = false;
        try {
            Object hum = fHum.get(coord);
            Method m = hum != null ? Refl.anyMethod(hum.getClass(), "isHeadsUpEntry") : null;
            childHu = m != null && Boolean.TRUE.equals(m.invoke(hum, cs.getKey()));
        } catch (Throwable ignored) {
        }
        boolean childBinding = false;
        try {
            childBinding = mIsBinding != null && Boolean.TRUE.equals(mIsBinding.invoke(coord, child));
        } catch (Throwable ignored) {
        }
        Object childPe = cPosted.newInstance(child, Boolean.FALSE, Boolean.FALSE, Boolean.TRUE, Boolean.TRUE,
                Boolean.valueOf(pinnedByUser), Boolean.valueOf(childHu), Boolean.valueOf(childBinding));
        busy = true;
        try {
            mHandle.invoke(coord, childPe, mutator, "ilha-heads-up-separada");
        } finally {
            busy = false;
        }
    }

    /**
     * O HyperOS passou a heads-up do resumo para um filho escolhido pela ordem dele (pode ser uma
     * mensagem antiga). Vai para a conversa que acabou de mudar; se nenhuma mudou ainda, espera.
     */
    static void fixTransfer(XC_MethodHook.MethodHookParam p, Object pe, Object e, StatusBarNotification sbn, long now) throws Throwable {
        Object parent = mGetParent != null ? mGetParent.invoke(e) : null;
        if (parent == null || fGroupSummary == null || !fGroupSummary.getDeclaringClass().isInstance(parent)) return;
        Object summary = fGroupSummary.get(parent);
        Object best = summary != null ? newestChild(summary, now) : null;
        StatusBarNotification bs = best != null ? (StatusBarNotification) fSbn.get(best) : null;
        long changed = bs != null ? touch(bs, now)[0] : 0L;
        String gk = sbn.getGroupKey();
        if (bs != null && now - changed <= RECENT_MS) {
            if (best == e) return;                       // já é a que acabou de chegar
            fEver.setBoolean(pe, false);                 // a antiga não aparece
            transferTo(p.thisObject, p.args[1], best, bs, fPinnedByUser.getBoolean(pe));
            logSep("heads-up do grupo " + sbn.getPackageName() + " corrigida para a conversa que acabou de mudar");
            return;
        }
        // nenhuma conversa do grupo mudou agora: espera a nova chegar
        fEver.setBoolean(pe, false);
        if (gk != null) {
            Pending pd = new Pending();
            pd.when = now;
            pd.deadline = now + 2500L;
            pending.put(gk, pd);
        }
        logSep("heads-up do grupo " + sbn.getPackageName() + ": transferência para conversa antiga evitada, esperando a nova");
    }

    static int sepLogs;

    static void logSep(String msg) {
        if (sepLogs < 12) {
            sepLogs++;
            ElasticCard.log(msg);
        }
    }

    /**
     * Hora da mensagem da notificação: a da última mensagem (estilo conversa) ou o "when".
     * A hora de postagem não serve: apps como o Telegram repostam as notificações antigas a
     * cada mensagem nova.
     */
    static long msgTime(Notification n, StatusBarNotification s) {
        long t = n != null ? n.when : 0L;
        try {
            android.os.Parcelable[] msgs = n != null && n.extras != null
                    ? n.extras.getParcelableArray(Notification.EXTRA_MESSAGES) : null;
            if (msgs != null) {
                for (int i = 0; i < msgs.length; i++) {
                    if (msgs[i] instanceof android.os.Bundle) {
                        long mt = ((android.os.Bundle) msgs[i]).getLong("time", 0L);
                        if (mt > t) t = mt;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return t > 0L ? t : (s != null ? s.getPostTime() : 0L);
    }

    /** A conversa do grupo que mudou por último (empate: a mensagem mais nova). */
    static Object newestChild(Object summary, long now) {
        try {
            Object parent = mGetParent != null ? mGetParent.invoke(summary) : null;
            if (parent == null || fChildren == null || !fChildren.getDeclaringClass().isInstance(parent)) return null;
            List kids = (List) fChildren.get(parent);
            if (kids == null || kids.isEmpty()) return null;
            Object best = null;
            long bestChange = Long.MIN_VALUE, bestTime = Long.MIN_VALUE;
            for (int i = 0; i < kids.size(); i++) {
                Object k = kids.get(i);
                StatusBarNotification s = (StatusBarNotification) fSbn.get(k);
                if (s == null) continue;
                long c = touch(s, now)[0];
                long t = msgTime(s.getNotification(), s);
                if (c > bestChange || (c == bestChange && t > bestTime)) {
                    bestChange = c;
                    bestTime = t;
                    best = k;
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }
}
