package com.ilha.headsup;

import android.app.Notification;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Parcelable;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * No system_server: cada mensagem nova de uma conversa vira um cartão novo, como no iOS.
 *
 * Apps de conversa (WhatsApp, Telegram...) atualizam sempre a mesma notificação da conversa,
 * acrescentando a mensagem nova na lista. Aqui, quando a atualização traz uma mensagem recebida
 * mais nova, ela é postada com outro id: o cartão anterior fica como estava e o novo mostra só
 * as mensagens novas. Atualizações sem mensagem recebida nova (resposta enviada, lida...) vão
 * para o cartão mais recente. Quando o app apaga a conversa, todos os cartões dela somem juntos.
 */
final class ConvSplit {
    private ConvSplit() {}

    static final class St {
        int cur;
        long start = Long.MIN_VALUE, last;
        final ArrayList<Integer> ids = new ArrayList<>();
    }

    static final HashMap<String, St> states = new HashMap<>();
    static final ThreadLocal<Boolean> inside = new ThreadLocal<>();
    static final HashMap<String, Runnable> pending = new HashMap<>();
    static android.os.Handler h;

    static android.os.Handler handler() {
        if (h == null) h = new android.os.Handler(android.os.Looper.getMainLooper());
        return h;
    }
    static SharedPreferences prefs;
    static int logs, seq, ig;

    static boolean on() {
        try {
            if (prefs == null) prefs = XposedBridge.remotePrefs(Keys.GROUP);
            return prefs != null && prefs.getBoolean(Keys.CONV_SPLIT, Keys.D_CONV_SPLIT);
        } catch (Throwable t) {
            return false;
        }
    }

    static void log(String s) {
        if (logs++ < 120) XposedBridge.log("[HeadsUpIlha] cartão por mensagem: " + s);
    }

    static void install(ClassLoader cl) {
        try {
            Class c = Class.forName("com.android.server.notification.NotificationManagerService", false, cl);
            Method enq = null, can = null;
            for (Method m : c.getDeclaredMethods()) {
                if ("enqueueNotificationInternal".equals(m.getName()) && idx(m.getParameterTypes(), Notification.class) > 1
                        && (enq == null || m.getParameterTypes().length > enq.getParameterTypes().length)) enq = m;
                if ("cancelNotificationInternal".equals(m.getName()) && strIdx(m.getParameterTypes(), 3) >= 0
                        && (can == null || m.getParameterTypes().length > can.getParameterTypes().length)) can = m;
            }
            if (enq == null || can == null) {
                log("não encontrei o serviço de notificações (" + enq + ", " + can + ")");
                return;
            }
            final int ni = idx(enq.getParameterTypes(), Notification.class);
            final int eTag = ni - 2, eId = ni - 1;
            final int cTag = strIdx(can.getParameterTypes(), 3), cId = cTag + 1;
            if (enq.getParameterTypes()[eId] != int.class || enq.getParameterTypes()[eTag] != String.class
                    || can.getParameterTypes()[cId] != int.class) {
                log("formato inesperado do serviço de notificações");
                return;
            }
            XposedBridge.hookMethod(enq, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Notification n = (Notification) p.args[ni];
                        if (n != null && String.valueOf(p.args[0]).contains("instagram") && ig++ < 12) {
                            XposedBridge.log("[HeadsUpIlha] instagram: grupo=" + n.getGroup() + " resumo=" + ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0)
                                    + " id=" + p.args[eId] + " tag=" + p.args[eTag] + " canal=" + n.getChannelId());
                        }
                        String pk = String.valueOf(p.args[0]);
                        if (n != null && diag(pk)) {
                            XposedBridge.log("[HeadsUpIlha] diagnóstico: pacote=" + pk + " grupo=" + n.getGroup() + " resumo=" + ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0)
                                    + " id=" + p.args[eId] + " tag=" + p.args[eTag] + " modelo=" + (n.extras == null ? null : n.extras.getString(Notification.EXTRA_TEMPLATE))
                                    + " atalho=" + n.getShortcutId() + " resumo agora=" + (n.getGroup() == null ? "-" : String.valueOf(hasSummary(p.thisObject, pk, n.getGroup()))));
                        }
                        if (ungroup(p.thisObject, String.valueOf(p.args[0]), n)) {
                            p.setResult(null);   // resumo de grupo de uma notificação só: não posta
                            return;
                        }
                        senderOnly(n);
                        shortcutPhoto(String.valueOf(p.args[0]), ni + 1 < p.args.length && p.args[ni + 1] instanceof Integer ? ((Integer) p.args[ni + 1]).intValue() : 0, n);
                        avatar(String.valueOf(p.args[0]), n);
                        if (!on()) return;
                        if (n == null || n.extras == null) return;
                        String tpl = n.extras.getString(Notification.EXTRA_TEMPLATE);
                        if (tpl == null || !tpl.contains("MessagingStyle")) return;
                        Parcelable[] msgs = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
                        if (msgs == null || msgs.length == 0) return;
                        long newest = Long.MIN_VALUE;
                        boolean incoming = false;
                        for (Parcelable m : msgs) {
                            if (!(m instanceof Bundle)) continue;
                            long t = ((Bundle) m).getLong("time");
                            if (t >= newest) {
                                newest = t;
                                incoming = ((Bundle) m).get("sender") != null || ((Bundle) m).get("sender_person") != null;
                            }
                        }
                        String key = p.args[0] + "|" + p.args[eTag] + "|" + p.args[eId];
                        synchronized (states) {
                            Runnable pc = pending.remove(key);
                            if (pc != null) {
                                handler().removeCallbacks(pc);
                                St old = states.get(key);
                                if (old != null && newest > old.last && incoming) {
                                    // apagou e postou de novo com mensagem nova: os cartões ficam
                                    log("conversa apagada e postada de novo com mensagem nova: mantendo os cartões");
                                } else {
                                    // apagou porque a conversa foi lida (e postou algo sem mensagem nova):
                                    // apaga todos os cartões agora e recomeça
                                    pc.run();
                                    log("conversa lida: cartões apagados");
                                }
                            }
                            St st = states.get(key);
                            if (st == null) {
                                st = new St();
                                st.cur = ((Integer) p.args[eId]).intValue();
                                st.last = newest;
                                states.put(key, st);
                                return;
                            }
                            if (newest > st.last && incoming && st.ids.size() < 40) {
                                int nid = ((Integer) p.args[eId]).intValue() * 31 + 0x1c4a0000 + (++seq);
                                st.ids.add(Integer.valueOf(nid));
                                st.cur = nid;
                                st.start = st.last;
                                n.flags &= ~Notification.FLAG_ONLY_ALERT_ONCE;
                                log("mensagem nova em cartão próprio (" + p.args[0] + ")");
                            }
                            else log("atualização sem mensagem recebida nova (" + (newest > st.last ? "mensagem enviada" : "mesma mensagem") + ")");
                            if (newest > st.last) st.last = newest;
                            p.args[eId] = Integer.valueOf(st.cur);
                            if (st.start != Long.MIN_VALUE) {
                                ArrayList<Parcelable> keep = new ArrayList<>();
                                for (Parcelable m : msgs) {
                                    if (m instanceof Bundle && ((Bundle) m).getLong("time") > st.start) keep.add(m);
                                }
                                if (!keep.isEmpty() && keep.size() < msgs.length) {
                                    n.extras.putParcelableArray(Notification.EXTRA_MESSAGES, keep.toArray(new Parcelable[0]));
                                    n.extras.remove(Notification.EXTRA_HISTORIC_MESSAGES);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        log("erro " + t);
                    }
                }
            });
            XposedBridge.hookMethod(can, new XC_MethodHook() {
                protected void beforeHookedMethod(final MethodHookParam p) {
                    if (Boolean.TRUE.equals(inside.get())) return;
                    try {
                        final String key = p.args[0] + "|" + p.args[cTag] + "|" + p.args[cId];
                        synchronized (states) {
                            if (!states.containsKey(key)) return;
                        }
                        // alguns apps apagam a conversa e postam de novo a cada mensagem: o apagar
                        // espera um pouco; se a conversa voltar logo, nada some
                        final Object[] a = p.args.clone();
                        final Object self = p.thisObject;
                        final Method m = (Method) p.method;
                        m.setAccessible(true);
                        Runnable r = new Runnable() {
                            public void run() {
                                St st;
                                synchronized (states) {
                                    pending.remove(key);
                                    st = states.remove(key);
                                }
                                inside.set(Boolean.TRUE);
                                try {
                                    m.invoke(self, a);
                                    if (st != null) {
                                        for (Integer id : st.ids) {
                                            Object[] b = a.clone();
                                            b[cId] = id;
                                            m.invoke(self, b);
                                        }
                                    }
                                    log("conversa apagada com " + (st == null ? 1 : st.ids.size() + 1) + " cartão(ões)");
                                } catch (Throwable t) {
                                    log("erro ao apagar " + t);
                                } finally {
                                    inside.set(null);
                                }
                            }
                        };
                        synchronized (states) {
                            Runnable old = pending.put(key, r);
                            if (old != null) handler().removeCallbacks(old);
                        }
                        handler().postDelayed(r, 1500L);
                        log("app apagou a conversa (" + p.args[0] + "): esperando 1,5 s");
                        p.setResult(null);
                    } catch (Throwable t) {
                        log("erro ao apagar " + t);
                    }
                }
            });
            log("instalado");
        } catch (Throwable t) {
            log("indisponível " + t);
        }
    }

    static int tg;
    static final HashMap<String, Integer> diagCount = new HashMap<>();

    /** Registra as primeiras notificações de cada app (até 6 por app desde que o celular ligou). */
    static boolean diag(String pkg) {
        synchronized (diagCount) {
            Integer c = diagCount.get(pkg);
            int k = c == null ? 0 : c.intValue();
            if (k >= 6 || diagCount.size() > 300) return false;
            diagCount.put(pkg, Integer.valueOf(k + 1));
            return true;
        }
    }
    static final java.util.List<String> NO_SUMMARY = java.util.Arrays.asList("com.instagram.android", "com.instagram.barcelona", "com.instagram.lite", "com.pinterest");
    static final java.util.HashSet<String> summaryPkgs = new java.util.HashSet<>();
    static final HashMap<String, Integer> seen = new HashMap<>();
    static java.lang.reflect.Field groupKey;

    /**
     * Apps que põem as notificações num grupo e nunca mandam o resumo do grupo (o Instagram faz
     * assim, um grupo por conversa): sem resumo o grupo não aparece como grupo, e o Android também
     * não junta, porque elas "já têm grupo". Com "Grupos em pilha" ligado, a partir da segunda
     * notificação de um app que nunca mandou resumo, o grupo dela é tirado; aí o Android junta
     * as soltas do app (a partir de 2) e elas viram pilha.
     */
    /**
     * Apps que põem cada tipo de notificação num grupo próprio, com resumo, mesmo com uma
     * notificação só (o Duolingo faz assim): cada uma ficava sozinha. Aqui o grupo delas é
     * tirado e o resumo não é postado; o Android junta as soltas do app (a partir de 2) numa pilha.
     */
    static final java.util.List<String> ONE_PER_GROUP = java.util.Arrays.asList("com.duolingo");

    // ---- aprendizado automático: apps com um grupo por notificação ----
    static final HashMap<String, HashMap<String, java.util.HashSet<String>>> groupsOf = new HashMap<>();
    static final java.util.HashSet<String> realGroupers = new java.util.HashSet<>();
    static java.util.HashSet<String> learnedPkgs;
    static final java.io.File LEARN_FILE = new java.io.File("/data/system/hyper_eternity_grupos.txt");

    static synchronized boolean learned(String pkg) {
        if (learnedPkgs == null) {
            learnedPkgs = new java.util.HashSet<>();
            try {
                if (LEARN_FILE.exists()) {
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(LEARN_FILE));
                    String l;
                    while ((l = br.readLine()) != null) if (l.trim().length() > 0) learnedPkgs.add(l.trim());
                    br.close();
                    XposedBridge.log("[HeadsUpIlha] apps com um grupo por notificação (aprendidos): " + learnedPkgs);
                }
            } catch (Throwable ignored) {
            }
        }
        return learnedPkgs.contains(pkg);
    }

    /**
     * Observa os grupos de cada app. Um app que já usou 2 grupos diferentes, cada um com uma
     * notificação só, e nunca pôs 2 notificações no mesmo grupo, é marcado: dali em diante as
     * notificações dele ficam soltas para o Android juntar numa pilha. Fica salvo entre reinícios.
     */
    static synchronized void learn(String pkg, String group, Notification n) {
        if (realGroupers.contains(pkg) || learned(pkg) || ONE_PER_GROUP.contains(pkg) || NO_SUMMARY.contains(pkg)) return;
        // conversas ficam de fora (já são tratadas pelo próprio app/sistema)
        if (n.getShortcutId() != null && n.extras != null && String.valueOf(n.extras.getString(Notification.EXTRA_TEMPLATE)).contains("Messaging")) return;
        HashMap<String, java.util.HashSet<String>> g = groupsOf.get(pkg);
        if (g == null) {
            g = new HashMap<>();
            groupsOf.put(pkg, g);
        }
        java.util.HashSet<String> ids = g.get(group);
        if (ids == null) {
            ids = new java.util.HashSet<>();
            g.put(group, ids);
        }
        ids.add(String.valueOf(n.when) + "|" + n.getChannelId() + "|" + String.valueOf(n.extras == null ? "" : n.extras.getCharSequence(Notification.EXTRA_TITLE)));
        if (ids.size() > 1) {
            realGroupers.add(pkg);
            groupsOf.remove(pkg);
            return;
        }
        if (g.size() >= 2) {
            learnedPkgs.add(pkg);
            groupsOf.remove(pkg);
            try {
                java.io.FileWriter w = new java.io.FileWriter(LEARN_FILE, true);
                w.write(pkg + "\n");
                w.close();
            } catch (Throwable ignored) {
            }
            XposedBridge.log("[HeadsUpIlha] aprendido: " + pkg + " usa um grupo por notificação; passa a empilhar");
        }
    }

    static int avLogs, scLogs;

    /**
     * Foto do grupo/contato vinda do atalho da conversa, lida aqui no sistema (que pode ler a foto
     * de qualquer atalho). A Interface do Sistema às vezes não consegue lê-la e mostra o ícone do
     * app. A foto vai para o "ícone da conversa" da notificação, que todo cartão de conversa usa.
     */
    static void shortcutPhoto(String pkg, int userId, Notification n) {
        try {
            if (n == null || n.extras == null || n.getShortcutId() == null) return;
            if (n.extras.get(Notification.EXTRA_CONVERSATION_ICON) != null) return;
            String tpl = n.extras.getString(Notification.EXTRA_TEMPLATE);
            if (tpl == null || !tpl.contains("MessagingStyle")) return;
            Class at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            android.content.Context ctx = (android.content.Context) at.getMethod("getSystemContext").invoke(thread);
            android.content.pm.LauncherApps la = (android.content.pm.LauncherApps) ctx.getSystemService(android.content.Context.LAUNCHER_APPS_SERVICE);
            if (la == null) return;
            android.content.pm.LauncherApps.ShortcutQuery q = new android.content.pm.LauncherApps.ShortcutQuery();
            q.setPackage(pkg);
            q.setShortcutIds(java.util.Collections.singletonList(n.getShortcutId()));
            q.setQueryFlags(android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                    | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED);
            android.os.UserHandle user = (android.os.UserHandle) android.os.UserHandle.class.getMethod("of", int.class).invoke(null, Integer.valueOf(Math.max(0, userId)));
            long id = android.os.Binder.clearCallingIdentity();
            java.util.List<android.content.pm.ShortcutInfo> l;
            android.graphics.drawable.Drawable d = null;
            try {
                l = la.getShortcuts(q, user);
                if (l != null && !l.isEmpty()) d = la.getShortcutIconDrawable(l.get(0), ctx.getResources().getDisplayMetrics().densityDpi);
            } finally {
                android.os.Binder.restoreCallingIdentity(id);
            }
            if (d == null) {
                if (scLogs++ < 10) XposedBridge.log("[HeadsUpIlha] foto do atalho (sistema): " + (l == null || l.isEmpty() ? "atalho não encontrado" : "atalho sem foto") + " (" + pkg + ")");
                return;
            }
            int sz = 192;
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(sz, sz, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            d.setBounds(0, 0, sz, sz);
            d.draw(c);
            n.extras.putParcelable(Notification.EXTRA_CONVERSATION_ICON, android.graphics.drawable.Icon.createWithBitmap(b));
            if (scLogs++ < 10) XposedBridge.log("[HeadsUpIlha] foto do atalho (sistema): colocada (" + pkg + ")");
        } catch (Throwable t) {
            if (scLogs++ < 10) XposedBridge.log("[HeadsUpIlha] foto do atalho (sistema): erro " + t);
        }
    }

    /**
     * Conversa sem foto: o Telegram às vezes manda a notificação sem a imagem grande, e o HyperOS
     * mostra o ícone do app. Aqui a foto vem do ícone da conversa (grupo) ou de quem mandou a
     * última mensagem (contato), que costumam vir dentro da própria notificação.
     */
    static void avatar(String pkg, Notification n) {
        try {
            if (n == null || n.extras == null || n.getLargeIcon() != null) return;
            String tpl = n.extras.getString(Notification.EXTRA_TEMPLATE);
            if (tpl == null || !tpl.contains("MessagingStyle")) return;
            android.graphics.drawable.Icon ic = null;
            Object ci = n.extras.get(Notification.EXTRA_CONVERSATION_ICON);
            if (ci instanceof android.graphics.drawable.Icon) ic = (android.graphics.drawable.Icon) ci;
            boolean group = n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION);
            if (ic == null && !group) {
                Parcelable[] ms = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
                for (int i = ms == null ? -1 : ms.length - 1; i >= 0 && ic == null; i--) {
                    if (!(ms[i] instanceof Bundle)) continue;
                    Object sp = ((Bundle) ms[i]).get("sender_person");
                    if (sp instanceof android.app.Person) ic = ((android.app.Person) sp).getIcon();
                }
            }
            if (ic == null) return;
            java.lang.reflect.Field f = Notification.class.getDeclaredField("mLargeIcon");
            f.setAccessible(true);
            f.set(n, ic);
            if (avLogs++ < 10) XposedBridge.log("[HeadsUpIlha] foto da conversa colocada (" + pkg + (group ? ", grupo" : ", contato") + ")");
        } catch (Throwable t) {
            if (avLogs++ < 10) log("foto da conversa: erro " + t);
        }
    }

    static java.util.HashSet<String> noSum;
    static final java.io.File NOSUM_FILE = new java.io.File("/data/system/hyper_eternity_sem_resumo.txt");

    static synchronized boolean learned(java.util.HashSet<String> set, java.io.File f, String pkg) {
        if (set == null) {
            set = new java.util.HashSet<>();
            try {
                if (f.exists()) {
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
                    String l;
                    while ((l = br.readLine()) != null) if (l.trim().length() > 0) set.add(l.trim());
                    br.close();
                }
            } catch (Throwable ignored) {
            }
            if (f == NOSUM_FILE) noSum = set;
        }
        return set.contains(pkg);
    }

    static synchronized void remember(java.util.HashSet<String> set, java.io.File f, String pkg) {
        if (learned(set, f, pkg)) return;
        if (f == NOSUM_FILE) set = noSum;
        set.add(pkg);
        try {
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(pkg + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
        XposedBridge.log("[HeadsUpIlha] aprendido: " + pkg + " agrupa sem resumo; a primeira notificação também vai se juntar");
    }

    static boolean ungroup(Object nms, String pkg, Notification n) {
        try {
            if (prefs == null) prefs = XposedBridge.remotePrefs(Keys.GROUP);
            if (n == null || prefs == null || !(prefs.getBoolean(Keys.GROUP_STACK, Keys.D_GROUP_STACK) || prefs.getBoolean(Keys.CONV_SPLIT, Keys.D_CONV_SPLIT))) return false;
            if (n.getGroup() != null && (n.flags & Notification.FLAG_GROUP_SUMMARY) == 0) learn(pkg, n.getGroup(), n);
            if ((ONE_PER_GROUP.contains(pkg) || learned(pkg)) && n.getGroup() != null) {
                if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return true;
                if (groupKey == null) {
                    groupKey = Notification.class.getDeclaredField("mGroupKey");
                    groupKey.setAccessible(true);
                }
                groupKey.set(n, null);
                if (logs++ < 120) XposedBridge.log("[HeadsUpIlha] grupo de uma notificação desfeito para juntar (" + pkg + ")");
                return false;
            }
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                synchronized (summaryPkgs) {
                    summaryPkgs.add(pkg);
                }
                return false;
            }
            if (n.getGroup() == null) return false;
            synchronized (summaryPkgs) {
                // apps que mandam resumo (WhatsApp, Telegram...) ficam como estão
                if (summaryPkgs.contains(pkg)) return false;
                Integer c = seen.get(pkg);
                int k = c == null ? 1 : c.intValue() + 1;
                seen.put(pkg, Integer.valueOf(k));
                // a primeira passa (apps que mandam o resumo logo depois se revelam), menos nos que
                // nunca mandam resumo: lá a primeira ficaria presa no grupo e nunca se juntaria
                if (k < 2 && !NO_SUMMARY.contains(pkg) && !learned(noSum, NOSUM_FILE, pkg)) return false;
                // app que agrupa e nunca mandou resumo: fica anotado (vale depois de reiniciar,
                // e aí já a primeira notificação dele se junta às outras)
                if (k >= 2) remember(noSum, NOSUM_FILE, pkg);
            }
            if (groupKey == null) {
                groupKey = Notification.class.getDeclaredField("mGroupKey");
                groupKey.setAccessible(true);
            }
            groupKey.set(n, null);
            if (logs++ < 120) XposedBridge.log("[HeadsUpIlha] grupo sem resumo desfeito para juntar (" + pkg + ")");
        } catch (Throwable t) {
            log("erro ao desfazer grupo " + t);
        }
        return false;
    }

    /** Existe agora um resumo ativo para o grupo (pkg, group)? Na dúvida, sim. */
    static boolean hasSummary(Object nms, String pkg, String group) {
        try {
            Object map = Refl.get(nms, "mSummaryByGroupKey");
            if (!(map instanceof java.util.Map)) return true;
            String tail = "|" + pkg + "|g:" + group;
            synchronized (map) {
                for (Object k : ((java.util.Map) map).keySet()) if (String.valueOf(k).endsWith(tail)) return true;
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Conversa em grupo: tira o nome do grupo do título, fica só quem mandou e a mensagem. */
    static void senderOnly(Notification n) {
        try {
            if (n == null || n.extras == null) return;
            if (prefs == null) prefs = XposedBridge.remotePrefs(Keys.GROUP);
            if (prefs == null || !prefs.getBoolean(Keys.CONV_SENDER, Keys.D_CONV_SENDER)) return;
            String tpl = n.extras.getString(Notification.EXTRA_TEMPLATE);
            if (tpl == null || !tpl.contains("MessagingStyle")) return;
            if (!n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
                    && n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) == null) return;
            // A conversa continua sendo "de grupo" para o Android (senão o modelo de conversa
            // fica sem foto e embaralha os nomes); só o título passa a ser quem mandou, e a foto
            // dessa pessoa vira a foto da conversa.
            Parcelable[] msgs = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            CharSequence who = null;
            android.graphics.drawable.Icon face = null;
            if (msgs != null) for (Parcelable m : msgs) {
                if (!(m instanceof Bundle)) continue;
                CharSequence s = ((Bundle) m).getCharSequence("sender");
                Object p = ((Bundle) m).get("sender_person");
                if (p instanceof android.app.Person) {
                    if (((android.app.Person) p).getName() != null) s = ((android.app.Person) p).getName();
                    if (s != null) face = ((android.app.Person) p).getIcon();
                }
                if (s != null) who = s;
            }
            if (who == null) return;
            n.extras.putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, who);
            n.extras.putCharSequence(Notification.EXTRA_TITLE, who);
            if (face != null && n.extras.get(Notification.EXTRA_CONVERSATION_ICON) == null)
                n.extras.putParcelable(Notification.EXTRA_CONVERSATION_ICON, face);
        } catch (Throwable t) {
            log("erro no nome de quem mandou " + t);
        }
    }

    static int idx(Class[] t, Class c) {
        for (int i = 0; i < t.length; i++) if (t[i] == c) return i;
        return -1;
    }

    /** Posição do n-ésimo parâmetro String (1 = primeiro). */
    static int strIdx(Class[] t, int nth) {
        int k = 0;
        for (int i = 0; i < t.length; i++) if (t[i] == String.class && ++k == nth) return i;
        return -1;
    }
}
