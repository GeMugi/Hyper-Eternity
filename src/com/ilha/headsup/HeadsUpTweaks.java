package com.ilha.headsup;

import android.app.Notification;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
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
            // "Sem botões": a versão heads-up é a recolhida, mas o sistema ainda põe nela a fileira dos
            // botões do sistema (bolha/janela flutuante e adiar). Numa conversa isso deixava uma faixa
            // vazia com só o ícone da bolha embaixo. Na heads-up recolhida, o sistema não põe essa fileira.
            XC_MethodHook noSys = new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!Prefs.huNoActions || p.args.length == 0 || p.args[0] == null) return;
                        Object hu = fHuChild.get(p.thisObject);
                        if (p.args[0] != hu) return;
                        Object e = fNcvEntry.get(p.thisObject);
                        Object sbn = e != null ? fEntrySbn.get(e) : null;
                        Notification n = sbn instanceof StatusBarNotification ? ((StatusBarNotification) sbn).getNotification() : null;
                        if (n == null || keepActions(n)) return;
                        p.setResult(null);
                        if (sysLogs++ < 3) ElasticCard.log("heads-up sem botões: sem a fileira do sistema (bolha/adiar) na heads-up recolhida");
                    } catch (Throwable ignored) {
                    }
                }
            };
            int sys = 0;
            for (Method m : ncv.getDeclaredMethods()) {
                String nm = m.getName();
                if ((nm.equals("applySystemActions") || nm.equals("applyBubbleAction") || nm.equals("applySnoozeAction"))
                        && m.getParameterTypes().length >= 1 && View.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    XposedBridge.hookMethod(m, noSys);
                    sys++;
                }
            }
            ElasticCard.log("heads-up sem botões: fileira do sistema vigiada em " + sys + " pontos");
            // HyperOS 4: a barrinha de baixo da heads-up (arrastar para abrir em janela) fica invisível,
            // mas continua lá para o gesto
            try {
                Class inj = Class.forName("com.android.systemui.statusbar.notification.row.ExpandableNotificationRowInjector", false, ncv.getClassLoader());
                final Method getBar = inj.getDeclaredMethod("getMiniBar");
                XC_MethodHook bar = new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            View b = (View) getBar.invoke(p.thisObject);
                            if (b == null) return;
                            float a = Prefs.huNoActions ? 0f : 1f;
                            if (b.getAlpha() != a) {
                                b.setAlpha(a);
                                if (a == 0f && barLogs++ < 2) ElasticCard.log("heads-up sem botões: barrinha da janela flutuante invisível (continua funcionando)");
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                };
                int nb = 0;
                for (Method m : inj.getDeclaredMethods()) {
                    String nm = m.getName();
                    if (nm.equals("updateMiniBarColor") || nm.equals("updateMiniBarTranslation") || nm.equals("updateBackgroundForShadowAndMiniBar")) {
                        XposedBridge.hookMethod(m, bar);
                        nb++;
                    }
                }
                ElasticCard.log("heads-up sem botões: barrinha vigiada em " + nb + " pontos");
            } catch (Throwable t) {
                // HyperOS 3: sem a barrinha
            }
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

    /**
     * "Sem botões" agora é só no modelo (installNoActions): a versão heads-up da notificação é montada
     * pelo próprio Android como a recolhida, sem botões. Nada é escondido nem medido de novo na view;
     * a altura é a que o sistema calcula. Aqui fica só a seta da heads-up do HyperOS 4 (opção própria).
     */
    static void enforce(View content) throws Throwable {
        Object row = fNcvRow != null ? fNcvRow.get(content) : null;
        if (row == null) return;
        if (!GroupStack.adapters) { legacyHide(content, row); return; }   // HyperOS 3: como antes
        View hu = (View) fHuChild.get(content);
        View co = fContracted != null ? (View) fContracted.get(content) : null;
        boolean pinned = mIsPinned != null && Boolean.TRUE.equals(mIsPinned.invoke(row));
        if (hu != null) center(hu, row);
        if (co != null && pinned) center(co, row);
        if (co != null && !pinned && co.getTag(TAG_CENTER) != null) place(co, row);   // voltou para a lista: seta de volta
        // views de antes (versões antigas escondiam botões na view): devolve tudo como era
        if (!hiddenByUs.isEmpty() || !marginByUs.isEmpty()) restoreOld();
    }

    /** HyperOS 3: os botões da heads-up recolhida escondidos na view (o jeito que funcionava lá). */
    static void legacyHide(View content, Object row) throws Throwable {
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
        boolean collapsedHun = Prefs.huNoActions && !keep && !userOpened;
        boolean hide = collapsedHun && pinned;
        applyHide(hu, collapsedHun);
        applyHide(co, hide);
        applyHide(ex, hide);
    }

    static int dumpLogs, sysLogs, barLogs;
    static final java.util.HashSet<String> dumped = new java.util.HashSet<>();

    static void restoreOld() {
        for (java.util.Map.Entry<View, Integer> e : new java.util.ArrayList<>(hiddenByUs.entrySet())) e.getKey().setVisibility(e.getValue());
        hiddenByUs.clear();
        for (java.util.Map.Entry<View, Integer> e : new java.util.ArrayList<>(marginByUs.entrySet())) {
            View m = e.getKey();
            if (m.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) m.getLayoutParams();
                lp.bottomMargin = e.getValue();
                m.setLayoutParams(lp);
            }
        }
        marginByUs.clear();
    }


    static final int TAG_CENTER = 0x7e1e0021;
    static int centerLogs;

    static final int TAG_HID = 0x7e1e0022;
    static int pinHookLogs;
    static boolean pinHooked;

    /** Liga uma vez por view: a cada layout, esconde a seta e centraliza o conteúdo na pílula. */
    static void center(final View v, final Object row) {
        hookPin(row.getClass());
        if (v.getTag(TAG_CENTER) != null) return;
        View.OnLayoutChangeListener l = new View.OnLayoutChangeListener() {
            public void onLayoutChange(View x, int l1, int t1, int r1, int b1, int ol, int ot, int or, int ob) {
                place(x, row);
            }
        };
        v.setTag(TAG_CENTER, row);
        v.addOnLayoutChangeListener(l);
        v.requestLayout();
    }

    /** Ao soltar/fixar a heads-up, ajusta na hora (sem esperar um novo layout). */
    static void hookPin(Class rc) {
        if (pinHooked) return;
        pinHooked = true;
        int n = 0;
        for (Class c = rc; c != null && c != View.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                String nm = m.getName();
                if (!(nm.startsWith("setPinned") || nm.equals("setHeadsUp") || nm.equals("setHeadsUpAnimatingAway"))) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam p) {
                            try { recheck(p.thisObject); } catch (Throwable ignored) {}
                        }
                    });
                    n++;
                } catch (Throwable ignored) {}
            }
        }
        ElasticCard.log("heads-up (HyperOS 4): " + n + " pontos de fixar/soltar vigiados");
    }

    static void recheck(Object row) {
        Object pl = Refl.call(row, "getPrivateLayout");
        if (!(pl instanceof View)) return;
        try {
            View hu = fHuChild != null ? (View) fHuChild.get(pl) : null;
            View co = fContracted != null ? (View) fContracted.get(pl) : null;
            if (hu != null && hu.getTag(TAG_CENTER) != null) place(hu, row);
            if (co != null && co.getTag(TAG_CENTER) != null) place(co, row);
        } catch (Throwable ignored) {}
    }

    static boolean pinnedNow(Object row) {
        try {
            return mIsPinned != null && Boolean.TRUE.equals(mIsPinned.invoke(row));
        } catch (Throwable t) {
            return false;
        }
    }

    static int intOf(Object o) {
        return o instanceof Integer ? ((Integer) o).intValue() : -1;
    }

    static void place(View x, Object row) {
        try {
            // sem deslocar o conteúdo: o layout é o do próprio HyperOS (com a margem certa de sem botões)
            if (x.getTranslationY() != 0f) x.setTranslationY(0f);
            if (!pinnedNow(row) || !Prefs.huNoActions) {
                showExpand(x);
                return;
            }
            hideExpand(x);
            if (centerLogs++ < 3) ElasticCard.log("heads-up (HyperOS 4): seta transparente (continua tocável), layout do sistema");
        } catch (Throwable t) {
            if (centerLogs++ < 6) ElasticCard.log("heads-up seta: " + t);
        }
    }

    static boolean isExpand(View v) {
        if (v.getId() == View.NO_ID) return false;
        try {
            String n = v.getResources().getResourceEntryName(v.getId()).toLowerCase();
            return n.contains("expand_button") || n.contains("expandbutton") || n.equals("expand_btn") || n.contains("expand_btn");
        } catch (Throwable t) {
            return false;
        }
    }

    static void hideExpand(final View v) {
        if (isExpand(v)) {
            // transparente, mas ainda tocável: o toque no lugar dele continua expandindo a heads-up
            if (v.getVisibility() == View.INVISIBLE && v.getTag(TAG_HID) == null) return;
            if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
            if (v.getAlpha() != 0f) {
                v.setAlpha(0f);
                // a hora no canto (conversas) se reposiciona sem a seta
                v.post(new Runnable() { public void run() { try { v.getParent().requestLayout(); } catch (Throwable ignored) {} } });
            }
            v.setTag(TAG_HID, Boolean.TRUE);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) hideExpand(g.getChildAt(i));
        }
    }

    static void showExpand(View v) {
        if (v.getTag(TAG_HID) != null) {
            v.setTag(TAG_HID, null);
            if (v.getAlpha() == 0f) v.setAlpha(1f);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) showExpand(g.getChildAt(i));
        }
    }

    /** Topo e fim do que aparece (textos, ícones), sem a seta, nas coordenadas da view de cima. */
    static void bounds(View top, View v, int[] box) {
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() < 0.05f || isExpand(v)) return;
        if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) bounds(top, g.getChildAt(i), box);
            return;
        }
        if (v.getHeight() <= 0 || v.getWidth() <= 0 || v == top) return;
        int y = 0;
        for (View c = v; c != null && c != top; c = c.getParent() instanceof View ? (View) c.getParent() : null) y += c.getTop() + Math.round(c.getTranslationY());
        int t = y, b = y + v.getHeight();
        if (v instanceof TextView) {   // texto: só a parte das letras (sem o espaço extra da linha)
            TextView tv = (TextView) v;
            if (tv.getText() == null || tv.getText().length() == 0) return;
            t = y + tv.getTotalPaddingTop();
            b = y + v.getHeight() - tv.getTotalPaddingBottom();
        }
        box[0] = Math.min(box[0], t);
        box[1] = Math.max(box[1], b);
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
                // HyperOS 4: o espaço que o próprio sistema deixa numa notificação sem botões (a margem de
                // conteúdo); zerar deixava menos espaço embaixo que em cima (conteúdo fora do centro)
                int want = GroupStack.adapters ? noActionMargin(m) : 0;
                if (hide && n > 0 && lp.bottomMargin != want) {
                    if (orig == null) marginByUs.put(m, Integer.valueOf(lp.bottomMargin));
                    if (marginLogs++ < 3) ElasticCard.log("heads-up sem botões: margem de baixo " + lp.bottomMargin + " -> " + want + "px");
                    lp.bottomMargin = want;
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

    static int marginLogs, noActMargin = -1;

    /** A margem de baixo que o Android usa no conteúdo de uma notificação sem botões. */
    static int noActionMargin(View m) {
        if (noActMargin >= 0) return noActMargin;
        int id = android.content.res.Resources.getSystem().getIdentifier("notification_content_margin", "dimen", "android");
        noActMargin = id != 0 ? android.content.res.Resources.getSystem().getDimensionPixelSize(id) : Math.round(16 * m.getResources().getDisplayMetrics().density);
        return noActMargin;
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
                        // a heads-up é a própria notificação recolhida (sem a fileira de botões), com as
                        // alturas e margens que o Android calcula para ela: nada a corrigir depois
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
            Caps.fail(Keys.HU_NO_ACTIONS, t);
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
            Caps.fail(Keys.HU_SEPARATE, t);
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
