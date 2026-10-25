package com.ilha.headsup;

import android.app.Notification;
import android.app.Person;
import android.os.Bundle;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Conversas no visual do HyperOS (opcional, desligado por padrão).
 *
 * No HyperOS 3 a Interface do Sistema só remonta no modelo dela (miui_notification_template_*)
 * as notificações sem estilo ou com BigText, BigPicture, Inbox e Call: em
 * NotificationRowContentBinderImpl$Companion.beginInflationAsync, depois de
 * NotificationSettingsHelper.showMiuiStyle(), ela olha Notification.Builder.getStyle(). As de
 * conversa (MessagingStyle) ficam no modelo do Android (ConversationLayout).
 *
 * Com o ajuste ligado, durante essa montagem o Builder fica sem estilo (só o campo mStyle, sem
 * mexer nos extras da notificação), então a conversa vai pelo caminho do HyperOS: ícone do app,
 * título e texto, foto (largeIcon) à direita. Se o app não deu título ou texto, eles vêm da última
 * mensagem. No fim da montagem o estilo volta para o Builder.
 */
final class ConvMiui {
    private ConvMiui() {}

    static Field fStyle;
    static int logs;

    static void install(ClassLoader cl) {
        try {
            Class comp = Class.forName("com.android.systemui.statusbar.notification.row.NotificationRowContentBinderImpl$Companion", false, cl);
            Method begin = Refl.anyMethod(comp, "access$beginInflationAsync");
            if (begin == null) throw new NoSuchMethodException("beginInflationAsync");
            fStyle = Refl.field(Notification.Builder.class, "mStyle");
            XposedBridge.hookMethod(begin, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Prefs.refresh();
                        if (!Prefs.convMiui) return;
                        Notification.Builder b = builderOf(p.args);
                        if (b == null) return;
                        Notification.Style s = b.getStyle();
                        if (!(s instanceof Notification.MessagingStyle)) return;
                        fillText(b, (Notification.MessagingStyle) s);
                        avatar(p.args, b, (Notification.MessagingStyle) s);
                        if (!setStyle(b, null)) return;
                        p.setObjectExtra("ilha.style", s);
                        if (logs++ < 3) ElasticCard.log("conversas no visual do HyperOS: montando uma conversa no modelo do HyperOS");
                    } catch (Throwable t) {
                        if (logs++ < 6) ElasticCard.log("conversas no visual do HyperOS: erro " + t);
                    }
                }

                protected void afterHookedMethod(MethodHookParam p) {
                    Object s = p.getObjectExtra("ilha.style");
                    if (s == null) return;
                    try {
                        setStyle(builderOf(p.args), (Notification.Style) s);
                    } catch (Throwable t) {
                    }
                }
            });
            ElasticCard.log("conversas no visual do HyperOS: instalado (" + (Prefs.convMiui ? "ligado" : "desligado") + ")");
        } catch (Throwable t) {
            ElasticCard.log("conversas no visual do HyperOS: indisponível " + t);
        }
    }

    static Notification.Builder builderOf(Object[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Notification.Builder) return (Notification.Builder) args[i];
        }
        return null;
    }

    /** Troca só o campo mStyle (setStyle() mexeria no EXTRA_TEMPLATE da notificação). */
    static boolean setStyle(Notification.Builder b, Notification.Style s) {
        if (b == null) return false;
        try {
            if (fStyle != null) {
                fStyle.set(b, s);
                return true;
            }
        } catch (Throwable t) {
        }
        return false;
    }

    // ------------------------------------------------------------------ foto da conversa

    static int avatarLogs;

    /**
     * O modelo do HyperOS já tem um jeito de mensagem: quando a notificação traz
     * extras "hyperOs.category" (qualquer texto) e "hyperOs.msgProfile" (Bitmap), o
     * handleAppIcon do NotificationRowContentBinderInjectorImpl põe essa foto no lugar do ícone
     * do app (app_icon) e o ícone do app vira o selo pequeno (app_im_icon); a foto da direita
     * não aparece (getNotificationTypeForIm = 0). Aqui a foto vem, nesta ordem: do atalho da
     * conversa (como o Android faz no modelo de conversa), do largeIcon, da foto de quem mandou a
     * última mensagem. Sem foto, nada muda (fica o ícone do app).
     */
    static void avatar(Object[] args, Notification.Builder b, Notification.MessagingStyle ms) {
        try {
            Object entry = null, processor = null;
            android.content.Context ctx = null;
            for (int i = 0; args != null && i < args.length; i++) {
                Object a = args[i];
                if (a == null) continue;
                String cn = a.getClass().getName();
                if (entry == null && cn.endsWith(".NotificationEntry")) entry = a;
                else if (processor == null && cn.endsWith(".ConversationNotificationProcessor")) processor = a;
                else if (ctx == null && a instanceof android.content.Context) ctx = (android.content.Context) a;
            }
            Notification n = null;
            Field fN = Refl.field(Notification.Builder.class, "mN");
            if (fN != null) n = (Notification) fN.get(b);
            Notification sbnN = null;
            if (entry != null) {
                Object sbn = Refl.get(entry, "mSbn");
                if (sbn instanceof android.service.notification.StatusBarNotification) {
                    sbnN = ((android.service.notification.StatusBarNotification) sbn).getNotification();
                }
            }
            if (n == null) n = sbnN;
            if (n == null || ctx == null) return;
            int size = Math.round(48 * ctx.getResources().getDisplayMetrics().density);
            String from = null;
            android.graphics.drawable.Drawable d = null;

            // 1) atalho da conversa (Telegram, WhatsApp e outros publicam um por conversa)
            String why = "";
            if (entry != null) {
                try {
                    Object ranking = Refl.get(entry, "mRanking");
                    android.content.pm.ShortcutInfo si = ranking instanceof android.service.notification.NotificationListenerService.Ranking
                            ? ((android.service.notification.NotificationListenerService.Ranking) ranking).getConversationShortcutInfo() : null;
                    if (si == null) why += " sem atalho no ranking;";
                    // o LauncherApps do processador de conversas; se não veio, o do sistema
                    Object la = processor != null ? Refl.get(processor, "launcherApps") : null;
                    if (!(la instanceof android.content.pm.LauncherApps)) la = ctx.getSystemService(android.content.Context.LAUNCHER_APPS_SERVICE);
                    if (si != null && la instanceof android.content.pm.LauncherApps) {
                        d = ((android.content.pm.LauncherApps) la).getShortcutIconDrawable(si, ctx.getResources().getDisplayMetrics().densityDpi);
                        if (d != null) from = "atalho da conversa";
                        else why += " atalho sem ícone;";
                    }
                } catch (Throwable t) {
                    why += " atalho: " + t + ";";
                }
            }
            // 1b) atalho pelo id que vem na notificação (quando o HyperOS ainda não ligou a conversa)
            if (d == null && n.getShortcutId() != null) {
                try {
                    android.content.pm.LauncherApps la = (android.content.pm.LauncherApps) ctx.getSystemService(android.content.Context.LAUNCHER_APPS_SERVICE);
                    String pkg = null;
                    android.os.UserHandle user = android.os.Process.myUserHandle();
                    if (entry != null) {
                        Object sbn = Refl.get(entry, "mSbn");
                        if (sbn instanceof android.service.notification.StatusBarNotification) {
                            pkg = ((android.service.notification.StatusBarNotification) sbn).getPackageName();
                            user = ((android.service.notification.StatusBarNotification) sbn).getUser();
                        }
                    }
                    if (la != null && pkg != null) {
                        android.content.pm.LauncherApps.ShortcutQuery q = new android.content.pm.LauncherApps.ShortcutQuery();
                        q.setPackage(pkg);
                        q.setShortcutIds(java.util.Collections.singletonList(n.getShortcutId()));
                        q.setQueryFlags(android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                                | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST | android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED);
                        List<android.content.pm.ShortcutInfo> l = la.getShortcuts(q, user);
                        if (l != null && !l.isEmpty()) {
                            d = la.getShortcutIconDrawable(l.get(0), ctx.getResources().getDisplayMetrics().densityDpi);
                            if (d != null) from = "atalho pelo id";
                            else why += " atalho pelo id sem ícone;";
                        } else why += " atalho pelo id não encontrado;";
                    }
                } catch (Throwable t) {
                    why += " atalho pelo id: " + t + ";";
                }
            }
            // 1c) ícone da conversa que o app manda junto (fotos de grupo)
            if (d == null) {
                try {
                    android.graphics.drawable.Icon ci = ms.getShortcutIcon();
                    if (ci == null && n.extras != null) {
                        Object o = n.extras.get(Notification.EXTRA_CONVERSATION_ICON);
                        if (o instanceof android.graphics.drawable.Icon) ci = (android.graphics.drawable.Icon) o;
                    }
                    if (ci != null) {
                        d = ci.loadDrawable(ctx);
                        if (d != null) from = "ícone da conversa";
                    }
                } catch (Throwable t) {
                }
            }
            // 2) largeIcon
            if (d == null) {
                try {
                    android.graphics.drawable.Icon li = n.getLargeIcon();
                    if (li != null) {
                        d = li.loadDrawable(ctx);
                        if (d != null) from = "largeIcon";
                    } else if (n.largeIcon != null) {
                        d = new android.graphics.drawable.BitmapDrawable(ctx.getResources(), n.largeIcon);
                        from = "largeIcon";
                    }
                } catch (Throwable t) {
                }
            }
            // 3) quem mandou a última mensagem (em conversa de duas pessoas)
            // 3) foto de quem mandou (como o modelo de conversa do Android faz quando o app não
            //    manda ícone da conversa); em grupos, a de quem mandou a última mensagem
            if (d == null) {
                try {
                    java.util.ArrayList<Notification.MessagingStyle.Message> all = new java.util.ArrayList<>(ms.getMessages());
                    all.addAll(0, ms.getHistoricMessages());
                    for (int i = all.size() - 1; i >= 0 && d == null; i--) {
                        Person p = all.get(i).getSenderPerson();
                        if (p != null && p.getIcon() != null) {
                            d = p.getIcon().loadDrawable(ctx);
                            if (d != null) from = ms.isGroupConversation() ? "foto de quem mandou (grupo)" : "foto de quem mandou";
                        }
                    }
                    if (d == null) why += " mensagens sem foto (" + all.size() + ");";
                } catch (Throwable t) {
                    why += " mensagens: " + t + ";";
                }
            }
            if (d == null) {
                if (avatarLogs++ < 20) com.ilha.headsup.xp.XposedBridge.log("[HeadsUpIlha] conversas no visual do HyperOS: sem foto, fica o ícone do app (atalho=" + n.getShortcutId() + ";" + why + ")");
                return;
            }
            android.graphics.Bitmap bmp = circle(d, size);
            putIm(n, bmp);
            if (sbnN != null && sbnN != n) putIm(sbnN, bmp);
            if (avatarLogs++ < 4) ElasticCard.log("conversas no visual do HyperOS: foto da conversa (" + from + ")");
        } catch (Throwable t) {
            if (avatarLogs++ < 4) ElasticCard.log("conversas no visual do HyperOS: erro na foto " + t);
        }
    }

    static void putIm(Notification n, android.graphics.Bitmap bmp) {
        if (n.extras == null) return;
        n.extras.putCharSequence("hyperOs.category", "msg");
        n.extras.putParcelable("hyperOs.msgProfile", bmp);
    }

    /** Desenha a foto num quadrado e recorta em círculo. */
    static android.graphics.Bitmap circle(android.graphics.drawable.Drawable d, int size) {
        android.graphics.Bitmap src = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(src);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas oc = new android.graphics.Canvas(out);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
        oc.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        src.recycle();
        return out;
    }

    /** Título e texto para o modelo do HyperOS, quando o app só mandou as mensagens. */
    static void fillText(Notification.Builder b, Notification.MessagingStyle ms) {
        Bundle ex = b.getExtras();
        Notification n;
        try {
            Field fN = Refl.field(Notification.Builder.class, "mN");
            n = fN != null ? (Notification) fN.get(b) : null;
        } catch (Throwable t) {
            n = null;
        }
        Bundle extras = n != null ? n.extras : ex;
        if (extras == null) return;
        List<Notification.MessagingStyle.Message> msgs = ms.getMessages();
        Notification.MessagingStyle.Message last = msgs != null && !msgs.isEmpty() ? msgs.get(msgs.size() - 1) : null;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        if (title == null || title.length() == 0) {
            CharSequence t = ms.getConversationTitle();
            if ((t == null || t.length() == 0) && last != null) {
                Person p = last.getSenderPerson();
                t = p != null ? p.getName() : null;
            }
            if (t != null && t.length() > 0) b.setContentTitle(t);
        }
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        if ((text == null || text.length() == 0) && last != null && last.getText() != null) {
            CharSequence body = last.getText();
            Person p = last.getSenderPerson();
            if (ms.isGroupConversation() && p != null && p.getName() != null && p.getName().length() > 0) {
                body = p.getName() + ": " + body;
            }
            b.setContentText(body);
        }
    }
}
