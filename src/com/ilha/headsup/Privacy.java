package com.ilha.headsup;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewTreeObserver;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.reflect.Method;
import java.util.HashSet;

/**
 * Privacidade por app ou conversa: o conteúdo das notificações escolhidas (heads-up, gaveta e
 * bloqueio) fica desfocado, com "Toque para ver" por cima. Ele aparece quando:
 *  - você toca na notificação (o primeiro toque só mostra, sem abrir o app);
 *  - o celular reconhece você (rosto) ou está desbloqueado, a não ser que a opção "também
 *    desbloqueado" esteja ligada.
 * Ao bloquear de novo, o que foi mostrado volta a ficar escondido.
 *
 * As conversas vistas (nome e id do atalho da conversa) são mandadas para o app, que mostra a
 * lista para escolher.
 */
final class Privacy {
    private Privacy() {}

    static final int TAG = 0x7e1a7031;
    static final HashSet<String> revealed = new HashSet<>();
    static final HashSet<String> sent = new HashSet<>();
    static Object ksc;   // KeyguardStateControllerImpl
    static boolean lastOwner;
    static Method mEntry;
    static int logs, dlogs;

    static void install(ClassLoader cl) {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            Class dpc = Class.forName("com.android.systemui.statusbar.notification.DynamicPrivacyController", false, cl);
            XC_MethodHook cap = new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    ksc = Refl.get(p.thisObject, "mKeyguardStateController");
                }
            };
            for (java.lang.reflect.Constructor k : dpc.getDeclaredConstructors()) XposedBridge.hookMethod(k, cap);
        } catch (Throwable t) {
            ElasticCard.log("privacidade: estado do bloqueio indisponível " + t);
        }
        try {
            Class row = Class.forName("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", false, cl);
            XposedBridge.hookMethod(row.getDeclaredMethod("onNotificationUpdated"), new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        attach((View) p.thisObject);
                    } catch (Throwable t) {
                        err(t);
                    }
                    try {
                        RecPill.row((View) p.thisObject);
                    } catch (Throwable ignored) {
                    }
                    try {
                        Lines4.row((View) p.thisObject);
                    } catch (Throwable ignored) {
                    }
                    try {
                        Vibrant.row((View) p.thisObject);
                        GlassEdge.row((View) p.thisObject);
                    } catch (Throwable ignored) {
                    }
                }
            });
            Class clicker = Class.forName("com.android.systemui.statusbar.notification.NotificationClicker", false, cl);
            XposedBridge.hookMethod(clicker.getDeclaredMethod("onClick", View.class), new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        View v = (View) p.args[0];
                        Guard g = v == null ? null : (Guard) v.getTag(TAG);
                        if (g == null || !g.hidden) return;
                        // primeiro toque: só mostra o conteúdo
                        if (g.key != null) revealed.add(g.key);
                        g.apply(false);
                        p.setResult(null);
                    } catch (Throwable t) {
                        err(t);
                    }
                }
            });
            ElasticCard.log("privacidade: instalada");
        } catch (Throwable t) {
            ElasticCard.log("privacidade: indisponível " + t);
            Caps.fail(Keys.PRIV_ON, t);
        }
    }

    static void err(Throwable t) {
        if (logs++ < 8) ElasticCard.log("privacidade: erro " + t);
    }

    /** Você está presente: rosto reconhecido no bloqueio ou celular desbloqueado. */
    static boolean owner() {
        Object k = ksc;
        if (k == null) return false;
        return Boolean.TRUE.equals(Refl.get(k, "mCanDismissLockScreen"));
    }

    /** O desfoque que o conteúdo deve ter fora das animações (o da privacidade, se escondida). */
    static RenderEffect base(Object row, View content) {
        try {
            if (!(row instanceof View)) return null;
            Object g = ((View) row).getTag(TAG);
            if (!(g instanceof Guard) || !((Guard) g).hidden) return null;
            if (content != Refl.get(row, "mPrivateLayout")) return null;
            float d = ((View) row).getResources().getDisplayMetrics().density;
            return RenderEffect.createBlurEffect(14f * d, 14f * d, Shader.TileMode.CLAMP);
        } catch (Throwable t) {
            return null;
        }
    }

    static void attach(View row) {
        if (!(row.getTag(TAG) instanceof Guard)) {
            Guard g = new Guard(row);
            row.setTag(TAG, g);
            row.addOnAttachStateChangeListener(g);
            if (row.isAttachedToWindow()) g.onViewAttachedToWindow(row);
        }
        ((Guard) row.getTag(TAG)).update();
    }

    static StatusBarNotification sbn(View row) {
        try {
            if (mEntry == null) {
                mEntry = Refl.method(row.getClass(), "getEntryLegacy");
                if (mEntry == null) mEntry = Refl.method(row.getClass(), "getEntry");
            }
            Object e = mEntry.invoke(row);
            Object s = Refl.call(e, "getSbn");
            if (!(s instanceof StatusBarNotification)) s = Refl.get(e, "mSbn");
            if (!(s instanceof StatusBarNotification)) s = Refl.get(e, "sbn");
            return s instanceof StatusBarNotification ? (StatusBarNotification) s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static String convId(Notification n) {
        String id = n.getShortcutId();
        if (id != null && id.length() > 0) return id;
        CharSequence t = n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        if (t == null) t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        return t == null ? null : clean(t.toString());
    }

    static String convLabel(Notification n) {
        CharSequence t = n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        if (t == null) t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        return t == null ? "" : clean(t.toString());
    }

    /** Tira o "(4 messages)" / "(3 mensagens)" que alguns apps põem no nome do grupo. */
    static String clean(String s) {
        return s.replaceAll("\\s*\\(\\d+[^)]*\\)\\s*$", "").replaceAll(":.*$", "").trim();
    }

    static boolean messaging(Notification n) {
        String tpl = n.extras.getString(Notification.EXTRA_TEMPLATE);
        return (tpl != null && tpl.contains("MessagingStyle")) || (n.getShortcutId() != null);
    }

    /** Manda para o app a conversa vista, para ela aparecer na lista de escolha. */
    static void report(View row, String pkg, String id, String label) {
        String k = pkg + "|" + id;
        if (!sent.add(k) || label.length() == 0) return;
        try {
            Context c = row.getContext();
            Intent i = new Intent("com.ilha.headsup.CONV_SEEN");
            i.setPackage(Keys.PKG);
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            i.putExtra("pkg", pkg);
            i.putExtra("id", id);
            i.putExtra("label", label);
            c.sendBroadcast(i);
        } catch (Throwable t) {
            err(t);
        }
    }

    static final class Guard implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        final View row;
        String key, pkg, conv;
        final HashSet<String> names = new HashSet<>();
        boolean hidden, target;
        ViewTreeObserver vto;
        Hint hint;

        Guard(View row) {
            this.row = row;
        }

        void update() {
            StatusBarNotification s = sbn(row);
            if (s == null) return;
            String k = s.getKey();
            if (key != null && !key.equals(k)) revealed.remove(key);
            key = k;
            pkg = s.getPackageName();
            Notification n = s.getNotification();
            conv = null;
            names.clear();
            if (n != null && n.extras != null) {
                String tag = s.getTag();
                // Instagram: a conversa vem no tag ("direct|...;thread_id:NNN")
                if (tag != null && tag.contains("thread_id:")) {
                    conv = "thread:" + tag.substring(tag.indexOf("thread_id:") + 10);
                    report(row, pkg, conv, convLabel(n));
                } else if (messaging(n) || Notification.CATEGORY_MESSAGE.equals(n.category)) {
                    conv = convId(n);
                    if (conv != null) report(row, pkg, conv, convLabel(n));
                }
                // nomes para comparar com os contatos: a conversa e quem mandou a última mensagem
                String lab = convLabel(n);
                if (lab.length() > 0) names.add(lab.toLowerCase());
                try {
                    android.os.Parcelable[] ms = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
                    if (ms != null && ms.length > 0 && ms[ms.length - 1] instanceof android.os.Bundle) {
                        CharSequence who = ((android.os.Bundle) ms[ms.length - 1]).getCharSequence("sender");
                        if (who != null) names.add(clean(who.toString()).toLowerCase());
                    }
                } catch (Throwable ignored) {
                }
            }
            if (hidden) apply(true);   // o conteúdo pode ter sido refeito: desfoca de novo
            else evaluate();
        }

        boolean isTarget() {
            Prefs.refresh();
            if (!Prefs.privOn || pkg == null) return false;
            if (Prefs.privApps.contains(pkg)) return true;
            if (conv != null && Prefs.privConvs.contains(pkg + "|" + conv)) return true;
            if (!Prefs.privContacts.isEmpty()) {
                for (String nm : names) if (Prefs.privContacts.contains(nm)) return true;
            }
            // grupos: o cartão dentro do grupo segue o grupo, e o grupo esconde se alguma
            // notificação dele for escondida
            try {
                Object parent = Refl.call(row, "getNotificationParent");
                if (parent instanceof View && parent != row) {
                    Object g = ((View) parent).getTag(TAG);
                    if (g instanceof Guard && ((Guard) g).selfTarget()) return true;
                }
                if (Boolean.TRUE.equals(Refl.get(row, "mIsSummaryWithChildren"))) {
                    Object c = Refl.get(row, "mChildrenContainer");
                    Object kids = c == null ? null : Refl.get(c, "mAttachedChildren");
                    if (kids instanceof java.util.List) {
                        for (Object k : (java.util.List) kids) {
                            Object g = k instanceof View ? ((View) k).getTag(TAG) : null;
                            if (g instanceof Guard && ((Guard) g).selfTarget()) return true;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        /** Só pelas regras dela mesma (app, conversa ou contato), sem olhar o grupo. */
        boolean selfTarget() {
            if (!Prefs.privOn || pkg == null) return false;
            if (Prefs.privApps.contains(pkg)) return true;
            if (conv != null && Prefs.privConvs.contains(pkg + "|" + conv)) return true;
            if (!Prefs.privContacts.isEmpty()) {
                for (String nm : names) if (Prefs.privContacts.contains(nm)) return true;
            }
            return false;
        }

        void evaluate() {
            boolean own = owner();
            if (lastOwner && !own) revealed.clear();   // bloqueou de novo: esconde tudo de novo
            lastOwner = own;
            boolean t0 = target;
            target = isTarget();
            if (target != t0 && dlogs++ < 25)
                XposedBridge.log("[HeadsUpIlha] privacidade: " + pkg + " conversa=" + conv + " nomes=" + names + " alvo=" + target
                        + " grupo=" + Boolean.TRUE.equals(Refl.get(row, "mIsSummaryWithChildren")) + " filho=" + Boolean.TRUE.equals(Refl.call(row, "isChildInGroup")));
            boolean hide = target && !revealed.contains(key) && (Prefs.privUnlocked || !own);
            if (hide != hidden) apply(hide);
        }

        void apply(boolean hide) {
            hidden = hide;
            Object pl = Refl.get(row, "mPrivateLayout");
            if (pl instanceof View) {
                float d = row.getResources().getDisplayMetrics().density;
                ((View) pl).setRenderEffect(hide ? RenderEffect.createBlurEffect(14f * d, 14f * d, Shader.TileMode.CLAMP) : null);
            }
            if (hide && hint == null) {
                hint = new Hint(row);
                row.getOverlay().add(hint);
            }
            if (hint != null) {
                hint.on = hide;
                hint.setBounds(0, 0, row.getWidth(), row.getHeight());
                hint.invalidateSelf();
            }
            row.invalidate();
            if (logs++ < 6) ElasticCard.log("privacidade: " + (hide ? "escondida " : "mostrada ") + pkg);
        }

        public boolean onPreDraw() {
            if (!hidden && !Prefs.privOn) return true;   // desligada: nada a fazer por quadro
            try {
                evaluate();
                if (hint != null && hint.on) {
                    int h = row.getHeight();
                    Object ah = Refl.call(row, "getActualHeight");
                    if (ah instanceof Integer && ((Integer) ah).intValue() > 0) h = ((Integer) ah).intValue();
                    if (hint.getBounds().width() != row.getWidth() || hint.getBounds().height() != h) hint.setBounds(0, 0, row.getWidth(), h);
                }
            } catch (Throwable t) {
                err(t);
            }
            return true;
        }

        public void onViewAttachedToWindow(View v) {
            if (vto != null && vto.isAlive()) vto.removeOnPreDrawListener(this);
            vto = v.getViewTreeObserver();
            vto.addOnPreDrawListener(this);
        }

        public void onViewDetachedFromWindow(View v) {
            if (vto != null && vto.isAlive()) vto.removeOnPreDrawListener(this);
            vto = null;
        }
    }

    /** "Toque para ver" no meio do cartão escondido. */
    static final class Hint extends android.graphics.drawable.Drawable {
        final View row;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean on;

        Hint(View row) {
            this.row = row;
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        }

        public void draw(Canvas c) {
            if (true) return;   // só o borrado, sem texto por cima
            float d = row.getResources().getDisplayMetrics().density;
            boolean dark = (row.getResources().getConfiguration().uiMode & 0x30) == 0x20;
            p.setColor(dark ? 0xEBFFFFFF : 0xE6000000);
            p.setTextSize(14f * d);
            android.graphics.Rect b = getBounds();
            float cy = b.exactCenterY() + 5f * d;
            c.drawText("Toque para ver", b.exactCenterX(), cy, p);
        }

        public void setAlpha(int a) {
        }

        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
