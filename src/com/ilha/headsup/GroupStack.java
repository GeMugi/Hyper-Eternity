package com.ilha.headsup;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;

/**
 * Grupos em pilha. Em vez do grupo fechado do HyperOS (um cartão com uma linha de cada
 * notificação), as notificações do mesmo app ficam empilhadas como cartões: a mais nova
 * inteira na frente e até duas aparecendo por baixo, um pouco mais estreitas. Um toque na
 * pilha abre os cartões como no grupo aberto do HyperOS; a seta do cabeçalho fecha de novo.
 *
 * Como funciona: o grupo passa a ficar sempre "aberto" para o sistema (assim cada notificação
 * é um cartão inteiro, com fundo próprio), e um estado nosso diz se ele está empilhado ou
 * espalhado. Empilhado, as posições dos cartões calculadas pelo HyperOS são trocadas pelas da
 * pilha e o cabeçalho some; a altura do grupo passa a ser a do cartão da frente mais as bordas.
 */
final class GroupStack {
    static int pubLogs;
    private GroupStack() {}

    static final String P = "com.android.systemui.statusbar.notification.";
    static final HashSet fanned = new HashSet();       // grupos espalhados (chave do resumo)
    static final java.util.HashMap collapsing = new java.util.HashMap();   // grupo -> animação de recolher
    static final java.util.WeakHashMap wantClip = new java.util.WeakHashMap(); // cartão -> corte de cima na pilha
    static Handler main;
    static Object expansionManager;
    static Method setExpanded;
    static boolean internal;
    static int logs;

    static void install(ClassLoader cl) {
        int ok = 0;
        try {
            main = new Handler(Looper.getMainLooper());
            Class gem = Class.forName(P + "collection.render.GroupExpansionManagerImpl", false, cl);
            Class entryCls = Class.forName(P + "collection.NotificationEntry", false, cl);
            try {
                setExpanded = gem.getDeclaredMethod("setGroupExpanded", entryCls, boolean.class);
            } catch (NoSuchMethodException e) {
                // HyperOS 4: o grupo é identificado pelo EntryAdapter
                entryCls = Class.forName(P + "collection.EntryAdapter", false, cl);
                setExpanded = gem.getDeclaredMethod("setGroupExpanded", entryCls, boolean.class);
                adapters = true;
                ElasticCard.log("grupos em pilha: expansão pelo EntryAdapter (HyperOS 4)");
            }
            setExpanded.setAccessible(true);
            Method isExpanded = gem.getDeclaredMethod("isGroupExpanded", entryCls);

            // todo grupo fica aberto para o sistema
            pubLogs = 0;
            XposedBridge.hookMethod(isExpanded, new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!on() || Boolean.TRUE.equals(p.getResult())) return;
                        expansionManager = p.thisObject;
                        final Object entry = p.args[0];
                        if (entry == null) return;
                        Object sum = summaryOf(p.thisObject, entry);
                        if (sum == null || !attached(sum)) return;
                        // conteúdo oculto no bloqueio: o HyperOS mostra só o cartão público do
                        // resumo; aberto, esse cartão ficava sem fundo (transparente)
                        Object srow = rowOf(sum);
                        if (srow != null && Boolean.TRUE.equals(Refl.call(srow, "shouldShowPublic"))) {
                            if (pubLogs++ < 6) ElasticCard.log("grupos em pilha: grupo com conteúdo oculto no bloqueio fica como o HyperOS (" + key(sum) + ")");
                            return;
                        }
                        p.setResult(Boolean.TRUE);
                        main.post(new Runnable() {
                            public void run() {
                                openForReal(entry);
                            }
                        });
                    } catch (Throwable t) {
                        err(t);
                    }
                }
            });
            // abrir/fechar pelo usuário (seta do cabeçalho) e o fechar geral ao recolher a gaveta:
            // vira espalhar/empilhar, e o grupo continua aberto para o sistema
            XposedBridge.hookMethod(setExpanded, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        expansionManager = p.thisObject;
                        if (internal || !on()) return;
                        Object entry = p.args[0];
                        Object summary = summaryOf(p.thisObject, entry);
                        // sem resumo ou fora da lista: deixa o HyperOS cuidar (nunca põe nulo no conjunto)
                        if (summary == null || !attached(summary)) return;
                        String key = key(summary);
                        boolean open = Boolean.TRUE.equals(p.args[1]);
                        if (!open && fanned.contains(key)) {
                            // HyperOS 4: o toque no 1º cartão do grupo aberto chegava como "recolher"
                            // (área do cabeçalho do grupo). Se o dedo estava no cartão, abre o cartão.
                            final View f = firstChildOf(rowOf(summary));
                            if (f != null && tapOn(f)) {
                                downAt = 0;
                                p.setResult(null);
                                f.post(new Runnable() {
                                    public void run() {
                                        openingFirst = true;
                                        try { f.performClick(); } finally { openingFirst = false; }
                                    }
                                });
                                if (firstLogs++ < 4) ElasticCard.log("grupos em pilha: recolher trocado por abrir o 1º cartão (toque nele)");
                                return;
                            }
                        }
                        boolean changed = open ? fanned.add(key) : fanned.remove(key);
                        p.args[0] = summary;
                        p.args[1] = Boolean.TRUE;
                        if (changed && !open && Collapse.start(summary, key)) return;   // recolher animado
                        if (changed && open && Expand.start(summary, key)) return;      // abrir animado
                        if (changed) relayout(summary);
                    } catch (Throwable t) {
                        err(t);
                    }
                }
            });
            // recolher a gaveta: só volta tudo a empilhar (o fechar geral do HyperOS fica de fora)
            XposedBridge.hookMethod(gem.getDeclaredMethod("collapseGroups"), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!on()) return;
                        if (!fanned.isEmpty() && tapLogs++ < 20) XposedBridge.log("[HeadsUpIlha] grupos em pilha: recolher geral (todas voltam a empilhar)");
                        fanned.clear();
                        p.setResult(null);
                    } catch (Throwable t) {
                        err(t);
                    }
                }
            });
            ok++;
        } catch (Throwable t) {
            ElasticCard.log("grupos em pilha: expansão de grupo não encontrada " + t);
        }
        try {
            final Class nssl = Class.forName(P + "stack.NotificationStackScrollLayout", false, cl);
            XposedBridge.hookMethod(nssl.getMethod("dispatchTouchEvent", android.view.MotionEvent.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (!nssl.isInstance(p.thisObject)) return;
                    android.view.MotionEvent e = (android.view.MotionEvent) p.args[0];
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                        downX = e.getRawX();
                        downY = e.getRawY();
                        downAt = android.os.SystemClock.uptimeMillis();
                    }
                }
            });
        } catch (Throwable t) {
            ElasticCard.log("grupos em pilha: toques da lista não monitorados " + t);
        }
        try {
            Class clicker = Class.forName(P + "NotificationClicker", false, cl);
            XposedBridge.hookMethod(clicker.getDeclaredMethod("onClick", View.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!on()) return;
                        View v = (View) p.args[0];
                        if (tapLogs++ < 10) XposedBridge.log("[HeadsUpIlha] grupos em pilha: toque em " + key(Refl.call(v, "getEntry")) + " filho=" + Refl.call(v, "isChildInGroup")
                                + " grupo=" + Refl.get(v, "mIsSummaryWithChildren"));
                        // HyperOS 4: o toque pode chegar no resumo do grupo em vez do cartão
                        if (Boolean.TRUE.equals(Refl.get(v, "mIsSummaryWithChildren")) && stacked(v)) {
                            Object entry = Refl.call(v, "getEntryLegacy");
                            if (entry == null) entry = Refl.call(v, "getEntry");
                            fanned.add(key(entry));
                            if (!Expand.start(entry, key(entry))) relayout(entry);
                            p.setResult(null);
                            return;
                        }
                        if (!Boolean.TRUE.equals(Refl.call(v, "isChildInGroup"))) return;
                        if (android.os.SystemClock.uptimeMillis() - lastTapAct < 350) {
                            p.setResult(null);
                            return;
                        }
                        if (collapseFromFirst(v)) {
                            lastTapAct = android.os.SystemClock.uptimeMillis();
                            p.setResult(null);
                            return;
                        }
                        Object parent = Refl.call(v, "getNotificationParent");
                        // conversa empilhada dentro do grupo aberto: o toque espalha só ela
                        String sk = subKeyOf.get(v);
                        if (parent != null && sk != null && !stacked(parent)) {
                            subOpen.add(sk);
                            Object c = Refl.get(parent, "mChildrenContainer");
                            if (c instanceof View) ((View) c).requestLayout();
                            Refl.heightChanged(parent, Boolean.TRUE);
                            p.setResult(null);
                            return;
                        }
                        if (parent == null || !stacked(parent)) return;
                        Object entry = Refl.call(parent, "getEntryLegacy");
                        if (entry == null) entry = Refl.call(parent, "getEntry");
                        fanned.add(key(entry));
                        if (!Expand.start(entry, key(entry))) relayout(entry);
                        p.setResult(null);                 // o toque espalha a pilha, não abre o app
                    } catch (Throwable t) {
                        err(t);
                    }
                }
            });
            ok++;
        } catch (Throwable t) {
            ElasticCard.log("grupos em pilha: toque não encontrado " + t);
        }
        try {
            Class ssa = Class.forName(P + "stack.StackScrollAlgorithm", false, cl);
            Class st = Class.forName(P + "stack.StackScrollAlgorithm$StackScrollAlgorithmState", false, cl);
            Method gcs = null;
            try {
                gcs = ssa.getDeclaredMethod("getNotificationChildrenStates", st);
            } catch (Throwable ignored) {
            }
            if (gcs == null) {
                // HyperOS 4: cada grupo calcula a posição dos filhos em
                // NotificationChildrenContainer.updateState(ExpandableViewState)
                Class ct = Class.forName(P + "stack.NotificationChildrenContainer", false, cl);
                Class evs = Class.forName(P + "stack.ExpandableViewState", false, cl);
                XposedBridge.hookMethod(ct.getDeclaredMethod("updateState", evs), new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (!on()) return;
                            Object row = Refl.get(p.thisObject, "mContainingNotification");
                            if (row != null) layoutRow(row);
                        } catch (Throwable t) {
                            err(t);
                        }
                    }
                });
                ElasticCard.log("grupos em pilha: modo HyperOS 4");
            } else
            XposedBridge.hookMethod(gcs, new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!on()) return;
                        List vis = (List) Refl.get(p.args[0], "visibleChildren");
                        if (vis == null) return;
                        for (int i = 0; i < vis.size(); i++) layoutRow(vis.get(i));
                    } catch (Throwable t) {
                        err(t);
                    }
                }
            });
            // o corte de cima dos cartões de trás é refeito por outras partes do HyperOS (sombra da
            // prateleira, animações); enquanto o grupo está empilhado, vale o corte da pilha
            Class rowCls = Class.forName(P + "row.ExpandableNotificationRow", false, cl);
            // HyperOS 4: tocar num cartão do grupo em pilha abre o próprio cartão (expande) em vez
            // de passar pelo clique; aqui esse "expandir" vira espalhar a pilha
            if (adapters) {
                XC_MethodHook userExp = new XC_MethodHook() {
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (!on() || !Boolean.TRUE.equals(p.args[0])) return;
                            Object v = p.thisObject;
                            Object parent = Boolean.TRUE.equals(Refl.get(v, "mIsSummaryWithChildren")) ? v : Refl.call(v, "getNotificationParent");
                            if (parent == null || parent == v && !stacked(parent)) return;
                            // as duas versões do método chamam uma à outra: um toque = uma ação
                            long now = android.os.SystemClock.uptimeMillis();
                            if (now - lastTapAct < 350) {
                                p.setResult(null);
                                return;
                            }
                            if (tapLogs++ < 10) XposedBridge.log("[HeadsUpIlha] grupos em pilha: expandir pedido em " + key(Refl.call(v, "getEntry")) + " pilha=" + (parent != null && stacked(parent)));
                            if (parent != v && !stacked(parent) && collapseFromFirst(v)) {
                                lastTapAct = now;
                                p.setResult(null);
                                return;
                            }
                            if (!stacked(parent)) return;
                            Object entry = Refl.call(parent, "getEntry");
                            fanned.add(key(entry));
                            if (!Expand.start(entry, key(entry))) relayout(entry);
                            lastTapAct = now;
                            p.setResult(null);
                        } catch (Throwable t) {
                            err(t);
                        }
                    }
                };
                for (java.lang.reflect.Method um : rowCls.getDeclaredMethods())
                    if (um.getName().equals("setUserExpanded") && um.getParameterTypes().length >= 1 && um.getParameterTypes()[0] == boolean.class)
                        XposedBridge.hookMethod(um, userExp);
            }
            XposedBridge.hookMethod(rowCls.getDeclaredMethod("setClipTopAmount", int.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Object want = wantClip.get(p.thisObject);
                        if (want == null || !on()) return;
                        if (mParent == null) mParent = Refl.method(p.thisObject.getClass(), "getNotificationParent");
                        Object parent = mParent.invoke(p.thisObject);
                        if (parent == null || (!stacked(parent) && Expand.of(parent) == null && !subGroups.containsKey(parent))) {
                            wantClip.remove(p.thisObject);
                            return;
                        }
                        p.args[0] = want;
                    } catch (Throwable t) {
                        err(t);
                    }
                }
            });
            // o cartão da frente fica com a altura do mais alto da pilha: o conteúdo dele desce
            // até o meio do cartão em vez de ficar colado no topo com um vazio embaixo
            // HyperOS 4: ao terminar de abrir/fechar, o HyperOS refaz o fundo do grupo e o cartão do
            // resumo voltava a aparecer atrás da pilha (cartão maior por trás da frente)
            if (adapters) {
                try {
                    XposedBridge.hookMethod(rowCls.getDeclaredMethod("updateBackgroundForGroupState"), new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                if (on()) headerCard(p.thisObject);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                } catch (Throwable t) {
                    ElasticCard.log("grupos em pilha: fundo do grupo (HyperOS 4) não vigiado: " + t);
                }
                // HyperOS 4: com o grupo fechado, cada aplicação de estado mostra o cartão do resumo
                // (o "cartão do grupo" do sistema) e apaga o fundo do 1º cartão. Na pilha isso dava
                // um cartão maior atrás e o da frente sem vidro (Mensagens, operadora, promoções).
                try {
                    Class nvs = Class.forName(rowCls.getName() + "$NotificationViewState", false, rowCls.getClassLoader());
                    XC_MethodHook after = new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Object v = p.args[0];
                                if (v == null || !rowCls.isInstance(v) || !Boolean.TRUE.equals(Refl.get(v, "mIsSummaryWithChildren")) || !on()) return;
                                headerCard(v);
                                frontCard(v);
                            } catch (Throwable ignored) {
                            }
                        }
                    };
                    int n = 0;
                    for (Method m : nvs.getDeclaredMethods()) {
                        if ((m.getName().equals("applyToView") || m.getName().equals("animateTo")) && m.getParameterTypes().length >= 1) {
                            XposedBridge.hookMethod(m, after);
                            n++;
                        }
                    }
                    ElasticCard.log("grupos em pilha: estado do grupo (HyperOS 4) vigiado em " + n + " pontos");
                    // heads-up de um grupo: o efeito de vidro da heads-up refaz o cartão do resumo
                    // (ficava um fundo grande e claro atrás da pilha fixada no topo)
                    try {
                        Class eff = Class.forName("com.android.systemui.statusbar.notification.style.vieweffect.HeadsUpNotificationBlurEffect", false, rowCls.getClassLoader());
                        XC_MethodHook hun = new XC_MethodHook() {
                            protected void afterHookedMethod(MethodHookParam p) {
                                try {
                                    Object v = p.args.length > 0 ? p.args[0] : null;
                                    if (v == null || !rowCls.isInstance(v) || !Boolean.TRUE.equals(Refl.get(v, "mIsSummaryWithChildren")) || !on()) return;
                                    headerCard(v);
                                    frontCard(v);
                                    if (hunLogs++ < 2) ElasticCard.log("grupos em pilha: cartão do resumo escondido na heads-up do grupo");
                                } catch (Throwable ignored) {
                                }
                            }
                        };
                        for (Method m : eff.getDeclaredMethods()) if (m.getName().equals("apply")) XposedBridge.hookMethod(m, hun);
                    } catch (Throwable ignored) {
                    }
                } catch (Throwable t) {
                    ElasticCard.log("grupos em pilha: estado do grupo (HyperOS 4) não vigiado: " + t);
                }
            }
            XposedBridge.hookMethod(rowCls.getDeclaredMethod("setActualHeight", int.class, boolean.class), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        center((View) p.thisObject, ((Integer) p.args[0]).intValue());
                        if (adapters && on()) headerCard(p.thisObject);
                        if (adapters) summaryContent(p.thisObject, on() && Boolean.TRUE.equals(Refl.get(p.thisObject, "mIsSummaryWithChildren")));
                    } catch (Throwable t) {
                        err(t);
                    }
                }
            });
            // a animação de grupo do próprio HyperOS (fundos dos cartões escondidos e reaparecendo)
            // corria junto com a nossa e fazia os cartões "cortarem e voltarem": com a pilha
            // ligada, o grupo nunca entra no modo "expansão mudando" do HyperOS
            XC_MethodHook noChanging = new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length == 1 && Boolean.TRUE.equals(p.args[0]) && on()
                                && Boolean.TRUE.equals(Refl.get(p.thisObject, "mIsSummaryWithChildren"))) p.args[0] = Boolean.FALSE;
                    } catch (Throwable t) {
                        err(t);
                    }
                }

                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length == 0 && Boolean.TRUE.equals(p.getResult()) && on()
                                && Boolean.TRUE.equals(Refl.get(p.thisObject, "mIsSummaryWithChildren"))) p.setResult(Boolean.FALSE);
                    } catch (Throwable t) {
                        err(t);
                    }
                }
            };
            XposedBridge.hookMethod(rowCls.getDeclaredMethod("setGroupExpansionChanging", boolean.class), noChanging);
            XposedBridge.hookMethod(rowCls.getDeclaredMethod("isGroupExpansionChanging"), noChanging);
            Class cont = Class.forName(P + "stack.NotificationChildrenContainer", false, cl);
            if (adapters) {
                XC_MethodHook rehide = new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (on()) hideHeaders(p.thisObject);
                        } catch (Throwable ignored) {
                        }
                    }
                };
                for (java.lang.reflect.Method hm : cont.getDeclaredMethods()) {
                    String n = hm.getName();
                    if (n.equals("updateHeaderVisibility") || n.equals("resetHeaderVisibilityIfNeeded") || n.equals("setGroupHeader")
                            || n.equals("setLowPriorityGroupHeader") || n.equals("recreateLowPriorityHeader") || n.equals("updateGroupHeaderExpandState")
                            || n.equals("updateHeaderForExpansion") || n.equals("onLayout"))
                        XposedBridge.hookMethod(hm, rehide);
                }
            }
            XposedBridge.hookMethod(cont.getDeclaredMethod("getIntrinsicHeight"), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!on()) return;
                        Object row = Refl.get(p.thisObject, "mContainingNotification");
                        if (row == null) return;
                        Expand ex = Expand.of(row);
                        if (ex != null && !stacked(row)) {
                            // abrindo: a altura cresce junto com os cartões
                            int full = ((Integer) p.getResult()).intValue();
                            Integer dl = subDelta.get(p.thisObject);
                            if (dl != null) full += dl.intValue();
                            Integer gp = adapters ? gapOf.get(p.thisObject) : null;
                            if (gp != null) full += gp.intValue();
                            int st = stackBase(p.thisObject);
                            if (st > 0) p.setResult(Integer.valueOf(Math.round(st + (full - st) * ex.overall())));
                            return;
                        }
                        if (!stacked(row)) {
                            // grupo aberto com conversas empilhadas: fica mais baixo
                            Integer dl = subDelta.get(p.thisObject);
                            int add = dl == null ? 0 : dl.intValue();
                            Integer gp = adapters && Collapse.of(row) == null ? gapOf.get(p.thisObject) : null;
                            if (gp != null) add += gp.intValue();
                            if (add != 0) p.setResult(Integer.valueOf(((Integer) p.getResult()).intValue() + add));
                            return;
                        }
                        int h = stackHeight(p.thisObject);
                        if (h > 0) p.setResult(Integer.valueOf(h));
                    } catch (Throwable t) {
                        err(t);
                    }
                }
            });
            ok++;
        } catch (Throwable t) {
            ElasticCard.log("grupos em pilha: posições do grupo não encontradas " + t);
        }
        ElasticCard.log("grupos em pilha: " + (ok == 3 ? "instalado" : "instalado em parte (" + ok + "/3)"));
    }

    static boolean on() {
        Prefs.refresh();
        return Prefs.groupStack;
    }

    static void err(Throwable t) {
        if (logs++ < 8) ElasticCard.log("grupos em pilha: erro " + t);
    }

    static boolean adapters;

    /**
     * HyperOS 4: o fundo de cada cartão dentro do grupo só aparece quando o sistema abre o grupo
     * de verdade. Com a pilha, o grupo está "aberto" sem o evento de abrir, e os cartões ficavam
     * sem fundo, com os textos de trás aparecendo através do da frente. Liga o fundo de cada um.
     */
    static void childBackgrounds(Object row) {
        try {
            // o cabeçalho do grupo fica sempre escondido (empilhado ou aberto); senão o ícone do app
            // dele aparece piscando por cima do ícone do cartão da frente
            Object c0 = Refl.get(row, "mChildrenContainer");
            if (c0 != null && Boolean.TRUE.equals(Refl.get(row, "mIsSummaryWithChildren"))) {
                // todos os tipos de cabeçalho que o HyperOS 4 usa (o de conversa, o comum, o
                // minimizado, o de "pacote" e o número de excedentes), não só o do WhatsApp
                hideHeaders(c0);
                Object dv = Refl.get(c0, "mDividers");
                if (dv instanceof List) for (Object o : (List) dv) if (o instanceof View && ((View) o).getAlpha() != 0f) ((View) o).setAlpha(0f);
                for (String sn : new String[]{"mHeaderViewState", "mTopLineViewState", "mExpandButtonViewState", "mGroupOverFlowState"}) {
                    Object hs = Refl.get(c0, sn);
                    if (hs != null) call(hs, "setAlpha", 0f);
                }
            }
            summaryContent(row, true);
            // o fundo único do grupo (vidro em volta de todos os cartões) sai: cada cartão já tem
            // o seu, como no HyperOS 3; senão um cartão grande cobre o grupo inteiro
            headerCard(row);
            Object c = Refl.get(row, "mChildrenContainer");
            Object kids = c == null ? null : Refl.get(c, "mAttachedChildren");
            if (!(kids instanceof List)) return;
            childIcons(c, (List) kids);
            for (Object k : (List) kids) {
                java.lang.reflect.Field f = Refl.field(k.getClass(), "mShowNoBackground");
                if (f == null || !f.getBoolean(k)) continue;
                f.setBoolean(k, false);
                Object bg = Refl.get(k, "mBackgroundNormal");
                if (bg instanceof View) ((View) bg).setVisibility(View.VISIBLE);
                if (bgLogs++ < 3) ElasticCard.log("grupos em pilha: fundo dos cartões do grupo ligado (HyperOS 4)");
            }
        } catch (Throwable t) {
            err(t);
        }
    }

    static int bgLogs, tapLogs, iconLogs;

    /**
     * HyperOS 4: num grupo, o sistema apaga o ícone de cada cartão (alpha 0) e desenha um ícone só do
     * grupo, no lado esquerdo do container (iconArea), com a imagem do resumo. Com a pilha, cada cartão
     * aparece sozinho: o ícone do grupo sumia junto com o cabeçalho e, no Chrome/WhatsApp, ficava
     * com a imagem antiga. Cada cartão volta a mostrar o próprio ícone e o do grupo sai.
     */
    static void childIcons(Object container, List kids) {
        try {
            Object inj = Refl.get(container, "mInjector");
            if (inj != null) {
                Object ia = Refl.get(inj, "iconArea");
                if (ia instanceof View && ((View) ia).getAlpha() != 0f) ((View) ia).setAlpha(0f);
            }
            for (Object k : kids) {
                Object pl = Refl.call(k, "getPrivateLayout");
                if (pl == null) continue;
                for (String g : new String[]{"getContractedChild", "getExpandedChild", "getHeadsUpChild"}) {
                    Object ch = Refl.call(pl, g);
                    if (!(ch instanceof View)) continue;
                    View ic = ((View) ch).findViewById(android.R.id.icon);
                    // o agrupamento do HyperOS 4 deixa o ícone igual ao do resumo como GONE (não só transparente)
                    if (ic != null && (ic.getAlpha() != 1f || ic.getVisibility() != View.VISIBLE)) {
                        ic.setAlpha(1f);
                        ic.setVisibility(View.VISIBLE);
                        if (iconLogs++ < 3) ElasticCard.log("grupos em pilha: ícone do cartão religado (HyperOS 4)");
                    }
                }
            }
        } catch (Throwable t) {
            err(t);
        }
    }

    /** Some com os cabeçalhos do grupo (invisíveis, não só transparentes: o HyperOS refaz a transparência). */
    static final java.util.WeakHashMap<Object, Boolean> hiddenContent = new java.util.WeakHashMap<>();

    /**
     * HyperOS 4: ao abrir/recolher, o grupo desenha por alguns quadros o conteúdo do próprio resumo
     * (o cartão com o ícone do app) por cima dos cartões; vale para todos os apps. Num grupo com
     * filhos esse conteúdo nunca deve aparecer.
     */
    static void summaryContent(Object row, boolean hide) {
        try {
            if (!hide && hiddenContent.remove(row) == null) return;
            for (String n : new String[]{"mPrivateLayout", "mPublicLayout"}) {
                Object l = Refl.get(row, n);
                if (!(l instanceof View)) continue;
                View v = (View) l;
                float a = hide ? 0f : 1f;
                if (v.getAlpha() != a) v.setAlpha(a);
            }
            if (hide) hiddenContent.put(row, Boolean.TRUE);
        } catch (Throwable t) {
            err(t);
        }
    }

    static void hideHeaders(Object c0) {
        for (String hn : new String[]{"mGroupHeader", "mMinimizedGroupHeader", "mCurrentHeader", "mBundleHeaderView", "mOverflowNumber"}) {
            Object hv = Refl.get(c0, hn);
            if (!(hv instanceof View)) continue;
            View h = (View) hv;
            if (h.getAlpha() != 0f) h.setAlpha(0f);
            if (h.getVisibility() == View.VISIBLE) h.setVisibility(View.INVISIBLE);
        }
    }
    static long lastTapAct;
    static int sumLogs;
    static int firstLogs;

    static float downX, downY;
    static long downAt;
    static boolean openingFirst;

    static View firstChildOf(Object row) {
        try {
            Object c = row == null ? null : Refl.get(row, "mChildrenContainer");
            Object kids = c == null ? null : Refl.get(c, "mAttachedChildren");
            if (kids instanceof List && !((List) kids).isEmpty() && ((List) kids).get(0) instanceof View) return (View) ((List) kids).get(0);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** O último toque caiu na ponta direita do cartão (onde fica a seta ⌄)? */
    static boolean tapOnArrow(View v) {
        if (android.os.SystemClock.uptimeMillis() - downAt > 800 || !v.isShown()) return false;
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        float d = v.getResources().getDisplayMetrics().density;
        int h = v.getHeight();
        try { Object ah = Refl.call(v, "getActualHeight"); if (ah instanceof Integer && ((Integer) ah).intValue() > 0) h = ((Integer) ah).intValue(); } catch (Throwable ignored) {}
        return downX >= l[0] + v.getWidth() - 64 * d && downX <= l[0] + v.getWidth() && downY >= l[1] && downY <= l[1] + h;
    }

    /** O último toque (até 0,8 s atrás) caiu dentro deste cartão, na tela? */
    static boolean tapOn(View v) {
        if (android.os.SystemClock.uptimeMillis() - downAt > 800 || !v.isShown()) return false;
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        int h = v.getHeight();
        try { Object ah = Refl.call(v, "getActualHeight"); if (ah instanceof Integer && ((Integer) ah).intValue() > 0) h = ((Integer) ah).intValue(); } catch (Throwable ignored) {}
        return downX >= l[0] && downX <= l[0] + v.getWidth() && downY >= l[1] && downY <= l[1] + h;
    }

    /** Cabeçalho "App (n) ⌄" do HyperOS 4 visível no grupo (quem recolhe é a seta dele). */
    static boolean headerShown(Object row) {
        try {
            Object c = Refl.get(row, "mChildrenContainer");
            Object inj = c == null ? null : Refl.get(c, "mInjector");
            Object gh = inj == null ? null : Refl.get(inj, "mMiuiGroupHeader");
            if (gh instanceof View && visibleHeader((View) gh)) return true;
            return row instanceof View && findHeader((View) row, 0);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean visibleHeader(View h) {
        return h.isShown() && h.getAlpha() > 0.05f && h.getHeight() > 0;
    }

    static boolean findHeader(View v, int depth) {
        if (v.getClass().getSimpleName().equals("NotificationGroupHeader") && visibleHeader(v)) return true;
        if (!(v instanceof android.view.ViewGroup) || depth > 4) return false;
        android.view.ViewGroup g = (android.view.ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View ch = g.getChildAt(i);
            if (ch.getClass().getSimpleName().contains("ExpandableNotificationRow")) continue;   // não desce nos cartões
            if (findHeader(ch, depth + 1)) return true;
        }
        return false;
    }

    /** HyperOS 4 (sem cabeçalho): tocar no primeiro cartão do grupo aberto volta a empilhar. */
    static boolean collapseFromFirst(Object child) {
        try {
            if (!adapters || openingFirst) return false;
            Object parent = Refl.call(child, "getNotificationParent");
            if (parent == null || stacked(parent)) return false;
            if (headerShown(parent)) {
                if (firstLogs++ < 4) ElasticCard.log("grupos em pilha: cabeçalho do grupo visível, 1º cartão abre normalmente");
                return false;
            }
            Object c = Refl.get(parent, "mChildrenContainer");
            Object kids = c == null ? null : Refl.get(c, "mAttachedChildren");
            if (!(kids instanceof List) || ((List) kids).isEmpty() || ((List) kids).get(0) != child) return false;
            // só a seta (⌄) do 1º cartão recolhe o grupo; tocar no resto do cartão abre a notificação
            if (child instanceof View && !tapOnArrow((View) child)) {
                if (firstLogs++ < 4) ElasticCard.log("grupos em pilha: toque no 1º cartão abre a notificação (só a seta recolhe)");
                return false;
            }
            Object entry = Refl.call(parent, "getEntry");
            String k = key(entry);
            fanned.remove(k);
            if (!Collapse.start(entry, k)) relayout(entry);
            return true;
        } catch (Throwable t) {
            err(t);
            return false;
        }
    }

    static final java.util.WeakHashMap<Object, Integer> gapOf = new java.util.WeakHashMap<>();

    /** HyperOS 4: o grupo aberto põe os cartões quase colados; aqui ganham um respiro entre eles. */
    static void gaps(Object row, float f) {
        try {
            Object c = Refl.get(row, "mChildrenContainer");
            Object kids = c == null ? null : Refl.get(c, "mAttachedChildren");
            if (!(kids instanceof List) || ((List) kids).size() < 2) return;
            List k = (List) kids;
            float d = ((View) row).getResources().getDisplayMetrics().density;
            float g = 8f * d;
            Method getVs = Refl.method(k.get(0).getClass(), "getViewState");
            // o cabeçalho do grupo ("App • agora") some e os cartões sobem para o lugar dele
            int hh = 0;
            Object hv = Refl.get(c, "mGroupHeader");
            if (hv instanceof View) {
                hh = ((View) hv).getHeight();
                if (((View) hv).getAlpha() != 0f) ((View) hv).setAlpha(0f);
            }
            Object hs = Refl.get(c, "mHeaderViewState");
            if (hs != null) call(hs, "setAlpha", 0f);
            if (hh <= 0) {
                Object v = Refl.get(c, "mHeaderHeight");
                if (v instanceof Integer) hh = ((Integer) v).intValue();
            }
            // posições refeitas com a altura real de cada cartão: um cartão aberto (expandido) dentro
            // do grupo empurra os de baixo, em vez de ficar por baixo do seguinte
            float prev = Float.NaN, off = -hh, bottom = Float.NaN, origBottom = 0f, newBottom = 0f;
            for (int i = 0; i < k.size(); i++) {
                Object vs = getVs.invoke(k.get(i));
                if (vs == null) continue;
                float y = ((Float) Refl.get(vs, "mYTranslation")).floatValue();
                int sh = ((Integer) Refl.get(vs, "height")).intValue();
                int ih = sh;
                try {
                    ih = Math.max(sh, ((Integer) Refl.method(k.get(i).getClass(), "getIntrinsicHeight").invoke(k.get(i))).intValue());
                } catch (Throwable ignored) {
                }
                boolean leader = Float.isNaN(prev) || y > prev + 1f;   // cartão seguinte (não a mesma sub-pilha)
                prev = y;
                if (leader && !Float.isNaN(bottom)) off = Math.max(off + g, bottom + g - y);
                float ny = y + off;
                bottom = Float.isNaN(bottom) ? ny + ih : Math.max(bottom, ny + ih);
                origBottom = Math.max(origBottom, y + sh);
                newBottom = Math.max(newBottom, ny + ih);
                if (off != 0f) call(vs, "setYTranslation", y + off * f);
            }
            gapOf.put(c, Integer.valueOf(Math.round((newBottom - origBottom) * f)));
        } catch (Throwable t) {
            err(t);
        }
    }

    /**
     * Fundo do resumo do grupo no HyperOS 4. Empilhado: some (cada cartão tem o seu). Aberto:
     * fica só atrás do cabeçalho do grupo ("App • agora"), que vira um cartão próprio.
     */
    static void headerCard(Object row) {
        try {
            if (!adapters || !Boolean.TRUE.equals(Refl.get(row, "mIsSummaryWithChildren"))) return;
            Object sbg = Refl.get(row, "mBackgroundNormal");
            if (!(sbg instanceof View)) return;
            View bg = (View) sbg;
            if (true) {   // cabeçalho do grupo fica sem cartão, como o "App (n)" do HyperOS 3
                java.lang.reflect.Field sf0 = Refl.field(row.getClass(), "mShowNoBackground");
                if (sf0 != null && !sf0.getBoolean(row)) sf0.setBoolean(row, true);
                if (bg.getVisibility() != View.GONE) {
                    bg.setVisibility(View.GONE);
                    if (sumLogs++ < 4) ElasticCard.log("grupos em pilha: cartão do resumo escondido de novo (" + key(Refl.call(row, "getEntry")) + ")");
                }
                return;
            }
            Object c = Refl.get(row, "mChildrenContainer");
            int hh = 0;
            Object hv = c == null ? null : Refl.get(c, "mGroupHeader");
            if (hv instanceof View) hh = ((View) hv).getHeight();
            if (hh <= 0 && c != null) {
                Object v = Refl.get(c, "mHeaderHeight");
                if (v instanceof Integer) hh = ((Integer) v).intValue();
            }
            if (hh <= 0) return;
            java.lang.reflect.Field sf = Refl.field(row.getClass(), "mShowNoBackground");
            if (sf != null && sf.getBoolean(row)) sf.setBoolean(row, false);
            if (bg.getVisibility() != View.VISIBLE) bg.setVisibility(View.VISIBLE);
            Method sa = Refl.anyMethod(bg.getClass(), "setActualHeight");
            if (sa != null && sa.getParameterTypes().length == 1) sa.invoke(bg, Integer.valueOf(hh));
        } catch (Throwable t) {
            err(t);
        }
    }

    static int hunLogs;

    static int frontLogs;

    /** O 1º cartão da pilha com o vidro dele (o HyperOS 4 apaga com o grupo fechado). */
    static void frontCard(Object row) {
        try {
            Object c = Refl.get(row, "mChildrenContainer");
            Object kids = c == null ? null : Refl.get(c, "mAttachedChildren");
            if (!(kids instanceof List) || ((List) kids).isEmpty()) return;
            Object k = ((List) kids).get(0);
            java.lang.reflect.Field f = Refl.field(k.getClass(), "mShowNoBackground");
            if (f != null && f.getBoolean(k)) f.setBoolean(k, false);
            Object bg = Refl.get(k, "mBackgroundNormal");
            if (!(bg instanceof View)) return;
            View b = (View) bg;
            if (b.getVisibility() != View.VISIBLE) b.setVisibility(View.VISIBLE);
            if (b.getAlpha() < 1f) {
                b.setAlpha(1f);
                if (frontLogs++ < 3) ElasticCard.log("grupos em pilha: vidro do 1º cartão devolvido (" + key(Refl.call(row, "getEntry")) + ")");
            }
        } catch (Throwable t) {
            err(t);
        }
    }

    static Object rowOf(Object entry) {
        if (entry == null) return null;
        Object r = Refl.get(entry, "row");
        if (r == null) r = Refl.call(entry, "getRow");
        return r;
    }

    static boolean attached(Object entry) {
        if (adapters) return Refl.call(entry, "getParent") != null;
        Object as = Refl.get(entry, "mAttachState");
        return as != null && Refl.get(as, "parent") != null;
    }

    static Object summaryOf(Object gem, Object entry) {
        if (adapters) {
            try {
                Object row = Refl.call(entry, "getRow");
                if (row == null) return null;
                if (Boolean.TRUE.equals(Refl.get(row, "mIsSummaryWithChildren"))) return entry;
                Object parent = Refl.call(row, "getNotificationParent");
                return parent == null ? null : Refl.call(parent, "getEntryAdapter");
            } catch (Throwable t) {
                return null;
            }
        }
        try {
            Object gmm = Refl.get(gem, "mGroupMembershipManager");
            Method m = Refl.anyMethod(gmm.getClass(), "getGroupSummary");
            return m.invoke(gmm, entry);
        } catch (Throwable t) {
            return null;
        }
    }

    static String key(Object entry) {
        try {
            Object k = Refl.call(entry, "getKey");
            if (k == null) k = Refl.get(entry, "key");   // HyperOS 4: NotificationEntry só tem o campo
            if (k == null) {
                Object sbn = Refl.get(entry, "mSbn");
                if (sbn instanceof android.service.notification.StatusBarNotification) k = ((android.service.notification.StatusBarNotification) sbn).getKey();
            }
            return k == null ? String.valueOf(entry) : k.toString();
        } catch (Throwable t) {
            return String.valueOf(entry);
        }
    }

    static void openForReal(Object entry) {
        Object gem = expansionManager;
        if (gem == null || entry == null) return;
        Object summary = summaryOf(gem, entry);
        if (summary == null || !attached(summary)) return;
        try {
            internal = true;
            setExpanded.invoke(gem, summary, Boolean.TRUE);
        } catch (Throwable t) {
            err(t);
        } finally {
            internal = false;
        }
    }

    /** Grupo empilhado: tem filhos e não está espalhado. */
    static java.lang.reflect.Field fSummary;
    static Method mEntry, mKey, mParent;

    static final java.util.WeakHashMap<View, Boolean> shifted = new java.util.WeakHashMap<>();

    /** Frente de uma pilha de conversa: o conteúdo desce até o meio da altura da pilha. */
    static void centerSub(View row, int h0) throws Throwable {
        int ih = ((Integer) Refl.method(row.getClass(), "getIntrinsicHeight").invoke(row)).intValue();
        Object pl = Refl.get(row, "mPrivateLayout");
        int ch = pl instanceof View ? ((View) pl).getHeight() : ih;
        if (ch <= 0) ch = ih;
        float dy = h0 > ch ? Math.round((h0 - ch) / 2f) : 0f;
        Object a = pl, b = Refl.get(row, "mPublicLayout");
        if (a instanceof View) ((View) a).setTranslationY(dy);
        if (b instanceof View) ((View) b).setTranslationY(dy);
        if (dy == 0f) shifted.remove(row);
        else shifted.put(row, Boolean.TRUE);
    }

    static void center(View row, int actualH) throws Throwable {
        float dy = 0f;
        if (on()) {
            if (mParent == null) mParent = Refl.method(row.getClass(), "getNotificationParent");
            Object parent = mParent.invoke(row);
            if (parent != null && (stacked(parent) || Expand.of(parent) != null || Collapse.of(parent) != null || subKeyOf.containsKey(row))) {
                List kids = (List) Refl.get(Refl.get(parent, "mChildrenContainer"), "mAttachedChildren");
                boolean subFront = subKeyOf.containsKey(row) && !subBack.containsKey(row) && !stacked(parent);
                if (kids != null && !kids.isEmpty() && (kids.get(0) == row || subFront)) {
                    int ih = ((Integer) Refl.method(row.getClass(), "getIntrinsicHeight").invoke(row)).intValue();
                    if (actualH > ih) dy = Math.round((actualH - ih) / 2f);
                }
            }
        }
        if (dy == 0f && subKeyOf.containsKey(row) && !subBack.containsKey(row)) return;   // centralizado pela pilha da conversa
        if (dy == 0f && shifted.get(row) == null) return;
        Object a = Refl.get(row, "mPrivateLayout"), b = Refl.get(row, "mPublicLayout");
        if (a instanceof View) ((View) a).setTranslationY(dy);
        if (b instanceof View) ((View) b).setTranslationY(dy);
        if (dy == 0f) shifted.remove(row);
        else shifted.put(row, Boolean.TRUE);
    }

    // ---------------------------------------------------------------- conversas dentro do grupo aberto

    static final HashSet<String> subOpen = new HashSet<>();
    static int subLogs;
    static final java.util.WeakHashMap<Object, String> subKeyOf = new java.util.WeakHashMap<>();
    static final java.util.WeakHashMap<Object, Boolean> subGroups = new java.util.WeakHashMap<>();
    static final java.util.WeakHashMap<Object, Integer> subDelta = new java.util.WeakHashMap<>();
    static final java.util.WeakHashMap<Object, Integer> subBack = new java.util.WeakHashMap<>();

    static String convOf(Object child) {
        android.service.notification.StatusBarNotification sb = Privacy.sbn((View) child);
        if (sb == null || sb.getNotification() == null || sb.getNotification().extras == null) return null;
        android.app.Notification n = sb.getNotification();
        String tag = sb.getTag();
        if (tag != null && tag.contains("thread_id:")) return "t:" + tag.substring(tag.indexOf("thread_id:") + 10);
        String id = n.getShortcutId();
        if (id != null && id.length() > 0) return "s:" + id;
        // só conversas de verdade: notificações comuns com o mesmo título (ex.: "Xposed module
        // updated") viravam sub-pilhas dentro do grupo aberto
        String tpl = n.extras.getString(android.app.Notification.EXTRA_TEMPLATE, "");
        if (!tpl.contains("MessagingStyle") && n.extras.get(android.app.Notification.EXTRA_MESSAGES) == null) return null;
        String l = Privacy.convLabel(n);
        return l.length() == 0 ? null : "n:" + l;
    }

    static void closeSubs(Object row) {
        try {
            Object e = mEntry != null ? mEntry.invoke(row) : null;
            if (e == null) return;
            String gk = key(e) + "|";
            java.util.Iterator<String> it = subOpen.iterator();
            while (it.hasNext()) if (it.next().startsWith(gk)) it.remove();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Grupo aberto: notificações seguidas da mesma conversa (mesma pessoa ou grupo) viram uma
     * pilha pequena dentro do grupo, como a pilha do app. Um toque nela espalha só aquela conversa.
     */
    static void layoutSub(Object row) throws Throwable {
        Object container = Refl.get(row, "mChildrenContainer");
        List kids = (List) Refl.get(container, "mAttachedChildren");
        subGroups.remove(row);
        if (kids == null || kids.size() < 2) {
            subDelta.remove(container);
            return;
        }
        if (mEntry == null) stacked(row);
        Object e = mEntry.invoke(row);
        String gk = (e == null ? "" : key(e)) + "|";
        int n = kids.size();
        String[] ks = new String[n];
        for (int i = 0; i < n; i++) {
            ks[i] = convOf(kids.get(i));
            subKeyOf.remove(kids.get(i));
            subBack.remove(kids.get(i));
        }
        Method getVs = Refl.method(kids.get(0).getClass(), "getViewState");
        float peek = peek(container);
        float delta = 0f;
        boolean any = false;
        int i = 0;
        while (i < n) {
            int L = 1;
            while (ks[i] != null && i + L < n && ks[i].equals(ks[i + L])) L++;
            String sk = ks[i] == null ? null : gk + ks[i];
            Object vs0 = getVs.invoke(kids.get(i));
            float y0 = ((Float) Refl.get(vs0, "mYTranslation")).floatValue() + delta;
            // o grupo inteiro é uma conversa só: a pilha do app já faz esse papel; aberto, mostra tudo
            if (L >= 2 && L < n && !subOpen.contains(sk)) {
                any = true;
                // todos com a altura do mais alto da conversa: um cartão de trás mais alto
                // mostrava o texto dele na faixa abaixo do da frente
                int h0 = ((Integer) Refl.get(vs0, "height")).intValue();
                for (int j = 1; j < L; j++) h0 = Math.max(h0, ((Integer) Refl.get(getVs.invoke(kids.get(i + j)), "height")).intValue());
                Refl.field(vs0.getClass(), "height").setInt(vs0, h0);
                try {
                    centerSub((View) kids.get(i), h0);
                } catch (Throwable ignored) {
                }
                call(vs0, "setYTranslation", y0);
                subKeyOf.put(kids.get(i), sk);
                Object last = getVs.invoke(kids.get(i + L - 1));
                float oldBottom = ((Float) Refl.get(last, "mYTranslation")).floatValue() + ((Integer) Refl.get(last, "height")).intValue() + delta;
                for (int j = 1; j < L; j++) {
                    Object vs = getVs.invoke(kids.get(i + j));
                    int idx = Math.min(j, 2);
                    Refl.field(vs.getClass(), "height").setInt(vs, h0);
                    call(vs, "setYTranslation", y0 + peek * idx);
                    call(vs, "setScaleX", 1f - 0.05f * idx);
                    call(vs, "setZTranslation", -0.3f * idx);
                    call(vs, "setAlpha", j <= 2 ? 1f : 0f);
                    int clip = Math.max(0, h0 - Math.round(peek));
                    java.lang.reflect.Field ct = Refl.field(vs.getClass(), "clipTopAmount");
                    if (ct != null) ct.setInt(vs, clip);
                    wantClip.put(kids.get(i + j), Integer.valueOf(clip));
                    subKeyOf.put(kids.get(i + j), sk);
                    subBack.put(kids.get(i + j), Integer.valueOf(clip));
                }
                float newBottom = y0 + h0 + peek * Math.min(L - 1, 2);
                delta += newBottom - oldBottom;
                i += L;
            } else {
                for (int j = 0; j < L; j++) {
                    Object vs = getVs.invoke(kids.get(i + j));
                    if (delta != 0f) call(vs, "setYTranslation", ((Float) Refl.get(vs, "mYTranslation")).floatValue() + delta);
                    wantClip.remove(kids.get(i + j));
                }
                i += L;
            }
        }
        if (any) {
            subGroups.put(row, Boolean.TRUE);
            Shade.touch(container);
        }
        if (subLogs++ < 12) ElasticCard.log("conversas no grupo aberto: " + java.util.Arrays.toString(ks) + (any ? " · empilhadas" : " · nenhuma seguida igual"));
        Integer old = subDelta.get(container);
        int nd = Math.round(delta);
        subDelta.put(container, Integer.valueOf(nd));
        if (old == null || old.intValue() != nd) {
            final View c = (View) container;
            main.post(new Runnable() {
                public void run() {
                    c.requestLayout();
                }
            });
        }
    }

    static void layoutRow(Object row) throws Throwable {
        if (adapters) childBackgrounds(row);
        if (adapters) headerCard(row);   // o resumo nunca mostra cartão próprio atrás da pilha

                            if (!Boolean.TRUE.equals(Refl.get(row, "mIsSummaryWithChildren"))) return;
                            // conteúdo oculto no bloqueio: o grupo fica fechado do jeito do HyperOS
                            // (ele mostra a prévia do grupo em cima); empilhar por baixo dela fazia
                            // a prévia aparecer acima do cartão da frente
                            if (Boolean.TRUE.equals(Refl.call(row, "shouldShowPublic"))) return;
                            try {
                                Lines4.group(row, stacked(row));
                            } catch (Throwable ignored) {
                            }
                            if (stacked(row)) {
                                closeSubs(row);
                                layoutStack(row);
                            } else if (Expand.of(row) != null) {
                                // abrindo: os cartões já vão para o lugar final, com as conversas empilhadas
                                try {
                                    layoutSub(row);
                                } catch (Throwable ignored) {
                                }
                                layoutExpand(row, Expand.of(row));
                            }
                            else if (Collapse.of(row) == null) {
                                try {
                                    layoutSub(row);
                                } catch (Throwable t) {
                                    if (subLogs++ < 12) ElasticCard.log("conversas no grupo aberto: erro " + t);
                                }
                            }
                            if (adapters && !stacked(row) && Collapse.of(row) == null) {
                                Expand ex = Expand.of(row);
                                gaps(row, ex == null ? 1f : Math.max(0f, Math.min(1f, ex.overall())));
                            }
                            }

    static boolean stacked(Object row) {
        try {
            if (fSummary == null) {
                fSummary = Refl.field(row.getClass(), "mIsSummaryWithChildren");
                mEntry = Refl.method(row.getClass(), "getEntryLegacy");
                if (mEntry == null) mEntry = Refl.method(row.getClass(), "getEntry");
            }
            if (!fSummary.getBoolean(row)) return false;
            Object entry = mEntry.invoke(row);
            return entry != null && !fanned.contains(key(entry));
        } catch (Throwable t) {
            return false;
        }
    }

    static void relayout(Object entry) {
        final Object row = rowOf(entry);
        if (!(row instanceof View)) return;
        main.post(new Runnable() {
            public void run() {
                try {
                    Object c = Refl.get(row, "mChildrenContainer");
                    if (c instanceof View) ((View) c).requestLayout();
                    Refl.heightChanged(row, Boolean.TRUE);
                } catch (Throwable t) {
                    err(t);
                }
            }
        });
    }

    static final java.util.WeakHashMap tops = new java.util.WeakHashMap();   // grupo -> altura do cabeçalho

    /** Espaço do cabeçalho acima da pilha: nenhum (o primeiro cartão desce ao abrir, como no HyperOS). */
    static float top(Object container) {
        return 0f;
    }

    static float peek(Object container) {
        return Prefs.gsDepth * ((View) container).getResources().getDisplayMetrics().density;
    }

    /** Curva com bounce ajustável: 100% é o pulo original, 0% chega sem passar do ponto. */
    static android.view.animation.PathInterpolator ease(float x1, float y1, float x2) {
        return new android.view.animation.PathInterpolator(x1, y1, x2, 1f + 0.12f * Prefs.gsBounce / 100f);
    }

    /** Duração ajustável: 200% de velocidade é metade do tempo. */
    static long dur(long base) {
        return Math.max(60L, base * 100L / Math.max(25, Prefs.gsSpeed));
    }

    /** Altura (sombra) do cartão na pilha: o da frente fica mais alto e projeta sombra no de trás. */
    static float z(Object container, int i, float f) {
        if (i >= 3) return 0f;
        int idx = Math.min(i, 2);
        float d = ((View) container).getResources().getDisplayMetrics().density;
        return (3 - idx) * 0.3f * f;
    }

    /**
     * Altura de todos os cartões na pilha: a do mais alto entre os três que aparecem. Assim a
     * pilha fica uniforme (o da frente não é menor que os de trás e nenhum passa por cima).
     */
    static int tallest(List kids) throws Throwable {
        Method ih = Refl.method(kids.get(0).getClass(), "getIntrinsicHeight");
        int m = 0;
        for (int i = 0; i < Math.min(3, kids.size()); i++) m = Math.max(m, ((Integer) ih.invoke(kids.get(i))).intValue());
        return m;
    }

    static int tallestVs(List kids, Method getVs) throws Throwable {
        int m = 0;
        for (int i = 0; i < Math.min(3, kids.size()); i++) m = Math.max(m, ((Integer) Refl.get(getVs.invoke(kids.get(i)), "height")).intValue());
        return m;
    }

    /** Altura da pilha parada (sem animação). */
    static int stackBase(Object container) {
        List kids = (List) Refl.get(container, "mAttachedChildren");
        if (kids == null || kids.isEmpty()) return -1;
        try {
            int h0 = tallest(kids);
            return Math.round(top(container)) + h0 + Math.round(peek(container) * Math.min(kids.size() - 1, 2));
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Abrindo: o primeiro cartão desce primeiro (o cabeçalho com o nome do app, a contagem e a
     * seta aparece no lugar dele) e os outros saem de trás, um depois do outro, do primeiro para
     * o último. Os destinos são os do grupo aberto do HyperOS, calculados logo antes.
     */
    static void layoutExpand(Object row, Expand ex) throws Throwable {
        Object container = Refl.get(row, "mChildrenContainer");
        List kids = (List) Refl.get(container, "mAttachedChildren");
        if (kids == null || kids.isEmpty()) return;
        float peek = peek(container);
        Method getVs = Refl.method(kids.get(0).getClass(), "getViewState");
        int h0 = tallestVs(kids, getVs);
        float frontY = 0f, frontTarget = 0f;
        for (int i = 0; i < kids.size(); i++) {
            Object vs = getVs.invoke(kids.get(i));
            int idx = Math.min(i, 2);
            int h = ((Integer) Refl.get(vs, "height")).intValue();
            float target = ((Float) Refl.get(vs, "mYTranslation")).floatValue();
            float from = top(container) + (i == 0 ? 0f : peek * idx);
            Integer sb = subBack.get(kids.get(i));
            if (sb != null) {
                // atrás na pilha de uma conversa: anda junto com o cartão da frente dela, já
                // empilhado (não sai do topo do grupo atrasado, como cartão solto)
                call(vs, "setYTranslation", frontY + (target - frontTarget));
                java.lang.reflect.Field ct = Refl.field(vs.getClass(), "clipTopAmount");
                int c = Math.min(h, sb.intValue());
                if (ct != null) ct.setInt(vs, c);
                wantClip.put(kids.get(i), Integer.valueOf(c));
                // aplica direto na view: sem isso o HyperOS animava a troca (o cartão de trás
                // só aparecia depois, e a conversa parecia um cartão único ao abrir o grupo)
                try {
                    View kv = (View) kids.get(i);
                    Object al = Refl.get(vs, "alpha");
                    if (al instanceof Float) kv.setAlpha(((Float) al).floatValue());
                    kv.setTranslationY(((Float) Refl.get(vs, "mYTranslation")).floatValue());
                    Object sx = Refl.get(vs, "scaleX");
                    if (sx instanceof Float) kv.setScaleX(((Float) sx).floatValue());
                    Method sc = Refl.anyMethod(kv.getClass(), "setClipTopAmount");
                    if (sc != null) sc.invoke(kv, Integer.valueOf(c));
                } catch (Throwable ignored) {
                }
                continue;
            }
            float p = ex.progress(i);
            h = Math.max(1, Math.round(h0 + (h - h0) * Math.max(0f, p)));
            Refl.field(vs.getClass(), "height").setInt(vs, h);
            float y = from + (target - from) * p;
            call(vs, "setYTranslation", y);
            frontY = y;
            frontTarget = target;
            call(vs, "setScaleX", 1f - 0.05f * idx * (1f - p));
            call(vs, "setZTranslation", z(container, i, 1f - Math.max(0f, Math.min(1f, p))));
            if (i >= 3) call(vs, "setAlpha", Math.max(0f, Math.min(1f, p)));
            int clip = 0;
            if (i > 0) {
                Object pv = getVs.invoke(kids.get(i - 1));
                float prevBottom = ((Float) Refl.get(pv, "mYTranslation")).floatValue() + ((Integer) Refl.get(pv, "height")).intValue();
                clip = Math.round(Math.max(0f, Math.min(h, prevBottom - y)));
            }
            java.lang.reflect.Field ct = Refl.field(vs.getClass(), "clipTopAmount");
            if (ct != null) ct.setInt(vs, clip);
            wantClip.put(kids.get(i), Integer.valueOf(clip));
        }
        float ha = Math.min(1f, ex.progress(0));
        fade(Refl.get(container, "mHeaderViewState"), ha);
        Object inj = Refl.get(container, "mInjector");
        fade(Refl.get(inj, "mGroupHeaderViewState"), ha);
        fade(Refl.get(inj, "mAppIconViewState"), 0f);
        Shade.set(container, 1f - Math.max(0f, Math.min(1f, ex.progress(1))));
    }

    /** Abrir animado. */
    static final class Expand implements android.animation.ValueAnimator.AnimatorUpdateListener {
        static final java.util.HashMap running = new java.util.HashMap();
        final android.view.animation.PathInterpolator EASE = ease(0.25f, 0.9f, 0.2f);   // suave, com um pequeno bounce
        final long DUR = dur(380L);
        final Object row;
        final String key;
        final int n;
        final long stagger;
        android.animation.ValueAnimator va;
        long t;

        Expand(Object row, String key, int n) {
            this.row = row;
            this.key = key;
            this.n = n;
            this.stagger = n > 1 ? Math.min(45L, 180L / (n - 1)) : 0L;
        }

        static Expand of(Object row) {
            if (row == null || running.isEmpty()) return null;
            for (Object e : running.values()) if (((Expand) e).row == row) return (Expand) e;
            return null;
        }

        static boolean start(Object summary, String key) {
            try {
                Object row = rowOf(summary);
                if (!(row instanceof View) || !((View) row).isShown()) return false;
                Object container = Refl.get(row, "mChildrenContainer");
                List kids = (List) Refl.get(container, "mAttachedChildren");
                if (kids == null || kids.size() < 2) return false;
                Object old = collapsing.remove(key);
                if (old != null && ((Collapse) old).va != null) ((Collapse) old).va.cancel();
                Object prev = running.remove(key);
                if (prev != null && ((Expand) prev).va != null) ((Expand) prev).va.cancel();
                final Expand e = new Expand(row, key, kids.size());
                running.put(key, e);
                e.va = android.animation.ValueAnimator.ofFloat(0f, 1f);
                e.va.setDuration(e.DUR + e.stagger * (kids.size() - 1));
                e.va.setInterpolator(new android.view.animation.LinearInterpolator());
                e.va.addUpdateListener(e);
                e.va.addListener(new android.animation.AnimatorListenerAdapter() {
                    public void onAnimationEnd(android.animation.Animator a) {
                        if (running.get(e.key) == e) running.remove(e.key);
                        e.push();
                    }
                });
                e.push();
                e.va.start();
                return true;
            } catch (Throwable t) {
                err(t);
                return false;
            }
        }

        public void onAnimationUpdate(android.animation.ValueAnimator a) {
            t = a.getCurrentPlayTime();
            push();
        }

        void push() {
            try {
                Refl.heightChanged(row, Boolean.FALSE);
            } catch (Throwable x) {
                err(x);
            }
        }

        float progress(int i) {
            float q = (t - stagger * i) / (float) DUR;
            return EASE.getInterpolation(q < 0f ? 0f : q > 1f ? 1f : q);
        }

        float overall() {
            return progress(n - 1);
        }
    }

    static int stackHeight(Object container) {
        List kids = (List) Refl.get(container, "mAttachedChildren");
        if (kids == null || kids.isEmpty()) return -1;
        Object first = kids.get(0);
        int h0;
        try {
            h0 = tallest(kids);
        } catch (Throwable t) {
            return -1;
        }
        Collapse c = Collapse.of(Refl.get(container, "mContainingNotification"));
        if (c != null) return c.height(container, kids);
        int more = Math.min(kids.size() - 1, 2);
        return Math.round(top(container)) + h0 + Math.round(peek(container) * more);
    }

    static void layoutStack(Object row) throws Throwable {
        Object container = Refl.get(row, "mChildrenContainer");
        List kids = (List) Refl.get(container, "mAttachedChildren");
        if (kids == null || kids.isEmpty()) return;
        float peek = peek(container);
        Collapse anim = Collapse.of(row);
        if (anim == null) {
            // a pilha fica onde o primeiro cartão fica no grupo aberto: o espaço do cabeçalho
            // (nome do app, contagem e seta) já está reservado acima dela, e abrir/fechar não
            // mexe no primeiro cartão, só mostra ou esconde o cabeçalho
            Method gv = Refl.method(kids.get(0).getClass(), "getViewState");
            float y0 = ((Float) Refl.get(gv.invoke(kids.get(0)), "mYTranslation")).floatValue();
            // (reservar o espaço do cabeçalho acima da pilha ficou estranho: desligado)
        }
        Method getVs = Refl.method(kids.get(0).getClass(), "getViewState");
        Object vs0 = getVs.invoke(kids.get(0));
        int h0 = tallestVs(kids, getVs);
        for (int i = 0; i < kids.size(); i++) {
            Object vs = getVs.invoke(kids.get(i));
            int idx = Math.min(i, 2);
            int h = ((Integer) Refl.get(vs, "height")).intValue();
            // na pilha todos os de trás ficam com a altura do da frente: um cartão de trás
            // mais alto aparecia por cima do da frente (as bordas do vidro passavam o recorte)
            int hn = h;
            h = h0;
            float y = top(container) + (i == 0 ? 0f : peek * idx);
            float sx = 1f - 0.05f * idx;
            float alpha = i < 3 ? 1f : 0f;
            int clip = i == 0 ? 0 : Math.max(0, h - Math.round(peek));
            if (anim != null) {
                // recolhendo: cada cartão desliza da posição aberta para trás do anterior, do
                // último para o primeiro; o que passa para trás é cortado na borda do anterior
                float p = anim.progress(i, kids.size());
                y = anim.lerp(anim.y(i), y, p);
                h = Math.max(1, Math.round(anim.lerp(hn, h0, Math.min(1f, p))));
                sx = 1f + (sx - 1f) * p;
                if (i >= 3) alpha = Math.max(0f, Math.min(1f, 1f - p));
                if (i > 0) {
                    Object pv = getVs.invoke(kids.get(i - 1));
                    float prevBottom = ((Float) Refl.get(pv, "mYTranslation")).floatValue() + ((Integer) Refl.get(pv, "height")).intValue();
                    clip = Math.round(Math.max(0f, Math.min(h, prevBottom - y)));
                }
                // estava atrás na pilha da conversa: começa cortado como lá, e não inteiro
                Integer sb = subBack.get(kids.get(i));
                if (sb != null) clip = Math.max(clip, Math.round(Math.min(h, sb.intValue()) * (1f - Math.min(1f, p))));
            }
            Refl.field(vs.getClass(), "height").setInt(vs, h);
            call(vs, "setYTranslation", y);
            call(vs, "setScaleX", sx);
            call(vs, "setZTranslation", z(container, i, anim == null ? 1f : Math.max(0f, Math.min(1f, anim.progress(i, kids.size())))));
            // ao empilhar, os de trás somem enquanto deslizam para baixo do da frente e só
            // voltam (como faixas) quando a pilha assentou: nada de texto cruzando o vidro
            call(vs, "setAlpha", alpha);
            Refl.field(vs.getClass(), "hidden").setBoolean(vs, false);
            // os cartões são de vidro (translúcidos): o de trás não pode aparecer através do da
            // frente. Dele só fica a faixa de baixo que sobra abaixo do cartão anterior.
            java.lang.reflect.Field ct = Refl.field(vs.getClass(), "clipTopAmount");
            if (ct != null) ct.setInt(vs, clip);
            wantClip.put(kids.get(i), Integer.valueOf(clip));
        }
        // sem cabeçalho na pilha (recolhendo, ele some junto com o primeiro cartão)
        float ha = anim != null ? Math.max(0f, 1f - anim.progress(0, kids.size())) : 0f;
        fade(Refl.get(container, "mHeaderViewState"), ha);
        fade(Refl.get(container, "mGroupOverFlowState"), 0f);
        Object inj = Refl.get(container, "mInjector");
        fade(Refl.get(inj, "mGroupHeaderViewState"), ha);
        fade(Refl.get(inj, "mAppIconViewState"), 0f);    // o ícone do app nunca aparece na pilha
        Shade.set(container, anim == null ? 1f : Math.max(0f, Math.min(1f, anim.progress(1, kids.size()))));
        if (logs++ < 3) ElasticCard.log("grupos em pilha: " + kids.size() + " cartões empilhados");
    }

    static void fade(Object vs, float a) {
        if (vs != null) call(vs, "setAlpha", a);
    }

    /** Recolher animado: cada cartão volta para trás do anterior, do último para o primeiro. */
    static final class Collapse implements android.animation.ValueAnimator.AnimatorUpdateListener {
        final android.view.animation.PathInterpolator EASE = ease(0.3f, 0f, 0.15f);   // suave, com um pequeno bounce
        final long DUR = dur(330L);
        final Object row;
        final String key;
        final float[] ye;
        final long stagger;
        android.animation.ValueAnimator va;
        long t;

        Collapse(Object row, String key, float[] ye) {
            this.row = row;
            this.key = key;
            this.ye = ye;
            int n = ye.length;
            this.stagger = n > 1 ? Math.min(45L, 180L / (n - 1)) : 0L;
        }

        static Collapse of(Object row) {
            if (row == null || collapsing.isEmpty()) return null;
            for (Object c : collapsing.values()) if (((Collapse) c).row == row) return (Collapse) c;
            return null;
        }

        static boolean start(Object summary, String key) {
            try {
                Object row = rowOf(summary);
                if (!(row instanceof View) || !((View) row).isShown()) return false;
                Object container = Refl.get(row, "mChildrenContainer");
                List kids = (List) Refl.get(container, "mAttachedChildren");
                if (kids == null || kids.size() < 2) return false;
                Method getVs = Refl.method(kids.get(0).getClass(), "getViewState");
                float[] ye = new float[kids.size()];
                for (int i = 0; i < ye.length; i++) {
                    ye[i] = ((Float) Refl.get(getVs.invoke(kids.get(i)), "mYTranslation")).floatValue();
                }
                Object old = collapsing.remove(key);
                if (old != null && ((Collapse) old).va != null) ((Collapse) old).va.cancel();
                Collapse c = new Collapse(row, key, ye);
                collapsing.put(key, c);
                c.va = android.animation.ValueAnimator.ofFloat(0f, 1f);
                c.va.setDuration(c.DUR + c.stagger * (ye.length - 1));
                c.va.setInterpolator(new android.view.animation.LinearInterpolator());
                c.va.addUpdateListener(c);
                final Collapse cc = c;
                c.va.addListener(new android.animation.AnimatorListenerAdapter() {
                    public void onAnimationEnd(android.animation.Animator a) {
                        if (collapsing.get(cc.key) == cc) collapsing.remove(cc.key);
                        cc.push();
                    }
                });
                c.va.start();
                return true;
            } catch (Throwable t) {
                err(t);
                return false;
            }
        }

        public void onAnimationUpdate(android.animation.ValueAnimator a) {
            t = a.getCurrentPlayTime();
            push();
        }

        void push() {
            try {
                Refl.heightChanged(row, Boolean.FALSE);
            } catch (Throwable e) {
                err(e);
            }
        }

        float y(int i) {
            return i < ye.length ? ye[i] : ye[ye.length - 1];
        }

        /** Progresso do cartão i: o último começa primeiro, o primeiro por último. */
        float progress(int i, int n) {
            long delay = stagger * Math.max(0, n - 1 - i);
            float q = (t - delay) / (float) DUR;
            return EASE.getInterpolation(q < 0f ? 0f : q > 1f ? 1f : q);
        }

        float lerp(float a, float b, float p) {
            return a + (b - a) * p;
        }

        /** Altura do grupo neste quadro: até a borda de baixo do cartão mais baixo. */
        int height(Object container, List kids) {
            try {
                float peek = peek(container);
                Method ih = Refl.method(kids.get(0).getClass(), "getIntrinsicHeight");
                int h0 = tallest(kids);
                float bottom = 0f;
                for (int i = 0; i < kids.size(); i++) {
                    int h = ((Integer) ih.invoke(kids.get(i))).intValue();
                    int idx = Math.min(i, 2);
                    float ys = top(container) + (i == 0 ? 0f : peek * idx);
                    float y = lerp(y(i), ys, progress(i, kids.size()));
                    h = Math.round(lerp(h, h0, Math.min(1f, progress(i, kids.size()))));
                    if (i >= 3 && progress(i, kids.size()) >= 1f) continue;
                    bottom = Math.max(bottom, y + h);
                }
                return Math.round(bottom);
            } catch (Throwable e) {
                return -1;
            }
        }
    }

    static void call(Object o, String name, float v) {
        try {
            Method m = Refl.anyMethod(o.getClass(), name);
            // HyperOS 4: alguns setters ganharam um primeiro parâmetro (String, motivo)
            if (m.getParameterTypes().length == 2) m.invoke(o, "HyperEternity", Float.valueOf(v));
            else m.invoke(o, Float.valueOf(v));
        } catch (Throwable t) {
            err(t);
        }
    }

    /**
     * Sombra entre os cartões da pilha, desenhada por nós no overlay do grupo: uma faixa em
     * degradê logo abaixo da borda de baixo de cada cartão, caindo sobre a faixa do cartão de
     * trás. A elevação do sistema (setZ) não servia: o HyperOS liga e desliga a sombra dos
     * cartões de vidro por conta própria, então ela aparecia e sumia.
     */
    static final class Shade extends android.graphics.drawable.Drawable {
        static final java.util.WeakHashMap<Object, Shade> all = new java.util.WeakHashMap<>();
        static Method actual;
        final View host;
        final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.RectF r = new android.graphics.RectF(), fr = new android.graphics.RectF(), br = new android.graphics.RectF();
        final android.graphics.Path path = new android.graphics.Path();
        final android.graphics.Outline ol = new android.graphics.Outline();
        final android.graphics.Rect or = new android.graphics.Rect(), tmp = new android.graphics.Rect();
        android.graphics.MaskFilter mask;
        float maskR = -1f, f;

        /** Contorno do cartão no grupo (com a escala lateral) e o raio dos cantos. */
        float shape(View v, android.graphics.RectF out, float d) throws Throwable {
            float rad = 24f * d;
            or.set(0, 0, v.getWidth(), ((Integer) actual.invoke(v)).intValue());
            try {
                ol.setEmpty();
                if (v.getOutlineProvider() != null) v.getOutlineProvider().getOutline(v, ol);
                if (ol.getRect(tmp) && !tmp.isEmpty()) or.set(tmp);
                if (ol.getRadius() > 0f) rad = ol.getRadius();
            } catch (Throwable ignored) {
            }
            float cx = v.getX() + v.getWidth() / 2f, sx = v.getScaleX();
            out.set(cx + (v.getX() + or.left - cx) * sx, v.getY() + or.top, cx + (v.getX() + or.right - cx) * sx, v.getY() + or.bottom);
            return Math.min(rad, Math.min(out.width(), out.height()) / 2f);
        }

        Shade(View host) {
            this.host = host;
        }

        /** Garante a sombra no grupo aberto (para as pilhas de conversa). */
        static void touch(Object container) {
            try {
                if (!(container instanceof android.view.ViewGroup)) return;
                Shade sh = all.get(container);
                if (sh == null) {
                    sh = new Shade((View) container);
                    all.put(container, sh);
                    ((View) container).getOverlay().add(sh);
                }
                sh.setBounds(0, 0, Math.max(1, ((View) container).getWidth()), Math.max(1, ((View) container).getHeight()));
                sh.invalidateSelf();
            } catch (Throwable t) {
                err(t);
            }
        }

        static void set(Object container, float f) {
            try {
                if (!(container instanceof android.view.ViewGroup)) return;
                Shade sh = all.get(container);
                if (sh == null) {
                    if (f <= 0f) return;
                    sh = new Shade((View) container);
                    all.put(container, sh);
                    ((View) container).getOverlay().add(sh);
                }
                if (f != sh.f || f > 0f) {
                    sh.f = f;
                    sh.setBounds(0, 0, Math.max(1, ((View) container).getWidth()), Math.max(1, ((View) container).getHeight()));
                    sh.invalidateSelf();
                    ((View) container).invalidate();
                }
            } catch (Throwable t) {
                err(t);
            }
        }

        public void draw(android.graphics.Canvas c) {
            try {
                if (!Prefs.groupStack || Prefs.gsShadow <= 0) return;
                List kids = (List) Refl.get(host, "mAttachedChildren");
                if (kids == null || kids.size() < 2) return;
                // grupo aberto: a sombra fica só nas pilhas de conversa dentro dele
                boolean subs = false;
                for (Object k : kids) if (subBack.containsKey(k)) subs = true;
                if (f <= 0f && !subs) return;
                if (actual == null) actual = Refl.method(kids.get(0).getClass(), "getActualHeight");
                float d = host.getResources().getDisplayMetrics().density;
                float peek = peek(host);
                int a = Math.round(255f * 0.32f * Math.max(f, subs ? 1f : 0f) * Prefs.gsShadow / 100f);
                if (a <= 0) return;
                int n = Math.min(kids.size(), 3);
                float blur = Math.max(3f * d, peek * 0.6f);
                if (mask == null || maskR != blur) {
                    mask = new android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL);
                    maskR = blur;
                }
                paint.setShader(null);
                paint.setMaskFilter(mask);
                paint.setColor(android.graphics.Color.argb(Math.min(255, Math.round(a * 1.4f)), 0, 0, 0));
                int last = f > 0f ? n - 1 : kids.size() - 1;
                for (int i = 0; i < last; i++) {
                    View v = (View) kids.get(i), w = (View) kids.get(i + 1);
                    if (f <= 0f && !subBack.containsKey(w)) continue;
                    if (v.getVisibility() != View.VISIBLE || w.getVisibility() != View.VISIBLE || w.getAlpha() <= 0f) continue;
                    float rf = shape(v, fr, d), rb = shape(w, br, d);
                    if (br.bottom - fr.bottom <= 0.5f) continue;
                    // a sombra tem o mesmo contorno arredondado do cartão da frente, desce um
                    // pouco e é desfocada; só aparece dentro do cartão de trás e fora do da frente
                    int sc = c.save();
                    path.reset();
                    path.addRoundRect(br, rb, rb, android.graphics.Path.Direction.CW);
                    c.clipPath(path);
                    path.reset();
                    path.addRoundRect(fr, rf, rf, android.graphics.Path.Direction.CW);
                    c.clipOutPath(path);
                    float dy = Math.min(peek * 0.35f, 4f * d);
                    r.set(fr.left + 4f * d, fr.top + dy, fr.right - 4f * d, fr.bottom + dy);
                    c.drawRoundRect(r, rf, rf, paint);
                    c.restoreToCount(sc);
                }
            } catch (Throwable t) {
                err(t);
            }
        }

        public void setAlpha(int alpha) {
        }

        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
