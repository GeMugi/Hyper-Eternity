package com.ilha.headsup;

import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/** Empresta do bloqueio do HyperOS o estilo do relógio (fonte, textura) e os ícones de lanterna/câmera. */
final class Kg {
    private Kg() {}

    static TextView clock, dateV;
    static Drawable torch, camera;
    static boolean scanned, dumped;

    static void scan(View root) {
        if (scanned || root == null) return;
        View kg = find(root, "HyperOSKeyguardRootView", 0);
        if (kg == null) return;
        scanned = true;
        walk(kg, 0);
        // botões de baixo do bloqueio (KeyguardBottomAreaView: start = lanterna, end = câmera)
        View sb = ShadeSheet.findId(root, "start_button", 0), eb = ShadeSheet.findId(root, "end_button", 0);
        if (sb instanceof ImageView && ((ImageView) sb).getDrawable() != null) torch = copy(((ImageView) sb).getDrawable());
        if (eb instanceof ImageView && ((ImageView) eb).getDrawable() != null) camera = copy(((ImageView) eb).getDrawable());
        ElasticCard.log("cortina: bloqueio emprestado relógio=" + (clock == null ? "-" : clock.getClass().getName() + " " + clock.getTextSize() + "px shader=" + clock.getPaint().getShader())
                + " lanterna=" + (torch != null) + " câmera=" + (camera != null));
    }

    static View find(View v, String suffix, int depth) {
        if (v.getClass().getName().endsWith(suffix)) return v;
        if (depth > 4 || !(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View r = find(g.getChildAt(i), suffix, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    static void walk(View v, int depth) {
        String id = "";
        try {
            if (v.getId() != View.NO_ID) id = v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable ignored) {
        }
        String cn = v.getClass().getName();
        if (!dumped && depth <= 9) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < depth; i++) sb.append(' ');
            String extra = v instanceof TextView ? " txt=" + ((TextView) v).getTextSize() : "";
            ElasticCard.log("cortina bloqueio: " + sb + cn.substring(cn.lastIndexOf('.') + 1) + " id=" + id + " " + v.getWidth() + "x" + v.getHeight() + extra);
        }
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            String s = String.valueOf(t.getText());
            if (s.matches(".*\\d{1,2}[:.]\\d{2}.*") && (clock == null || t.getTextSize() > clock.getTextSize())) clock = t;
            else if (id.contains("date") && dateV == null) dateV = t;
        }
        String low = (id + " " + cn).toLowerCase();
        if (v instanceof ImageView && ((ImageView) v).getDrawable() != null) {
            Drawable dr = ((ImageView) v).getDrawable();
            if (torch == null && (low.contains("flash") || low.contains("torch") || low.contains("left"))) torch = copy(dr);
            else if (camera == null && (low.contains("camera") || low.contains("right"))) camera = copy(dr);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), depth + 1);
        }
        if (depth == 0) dumped = true;
    }

    static Drawable copy(Drawable d) {
        try {
            Drawable.ConstantState cs = d.getConstantState();
            return cs != null ? cs.newDrawable().mutate() : d;
        } catch (Throwable t) {
            return d;
        }
    }

    static void styleClock(Paint time, Paint date, View root) {
        scan(root);
        if (clock != null) {
            Paint kp = clock.getPaint();
            time.setTypeface(kp.getTypeface());
            time.setLetterSpacing(kp.getLetterSpacing());
            try {
                time.setFontVariationSettings(kp.getFontVariationSettings());
            } catch (Throwable ignored) {
            }
            time.setShader(kp.getShader());
            time.setColor(clock.getCurrentTextColor());
        }
        if (dateV != null) date.setTypeface(dateV.getPaint().getTypeface());
    }

    /** A biblioteca dos relógios do HyperOS fica num carregador próprio: pega o do relógio do bloqueio. */
    static ClassLoader clockCl(ClassLoader fallback) {
        if (clock != null) return clock.getClass().getClassLoader();
        return fallback;
    }

    // ------------------------------------------------------- relógio com o material do bloqueio
    static android.widget.FrameLayout box;
    static TextView gTime, gDate;
    static int[] memberArgs;   // cor, alpha, labAlpha, origem, sobre (capturados do relógio do bloqueio)
    static boolean memberDark;
    static int passRadius = -1;
    static boolean hooked, glassLogged;

    /** Grava os parâmetros que o próprio relógio do bloqueio usa no material (cor, desfoque). */
    static void hookBlur(ClassLoader cl) {
        if (hooked) return;
        try {
            Class.forName("com.miui.clock.utils.MiuiBlurUtils", false, cl);
        } catch (Throwable t) {
            return;   // ainda sem o carregador certo: tenta de novo depois
        }
        hooked = true;
        try {
            Class u = Class.forName("com.miui.clock.utils.MiuiBlurUtils", false, cl);
            com.ilha.headsup.xp.XposedBridge.hookMethod(u.getDeclaredMethod("setMemberBlendColors", View.class, boolean.class, int.class, int.class, int.class, int.class, int.class), new com.ilha.headsup.xp.XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    Object v = p.args[0];
                    if (v == null || v == gTime || v == gDate) return;
                    if (!v.getClass().getName().endsWith("MiuiTextGlassView")) return;
                    memberDark = (Boolean) p.args[1];
                    memberArgs = new int[]{(Integer) p.args[2], (Integer) p.args[3], (Integer) p.args[4], (Integer) p.args[5], (Integer) p.args[6]};
                }
            });
            com.ilha.headsup.xp.XposedBridge.hookMethod(u.getDeclaredMethod("setContainerPassBlur", View.class, int.class, boolean.class), new com.ilha.headsup.xp.XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args[0] != box) passRadius = (Integer) p.args[1];
                }
            });
        } catch (Throwable t) {
            ElasticCard.log("cortina: material do relógio indisponível: " + t);
        }
    }

    /** Relógio em views de verdade, com o mesmo material de vidro do bloqueio. false = desenhar à mão. */
    static boolean glassClock(View deco, String ds, String ts, float top, float ht, float dateSize, int width) {
        try {
            if (!(deco.getParent() instanceof ViewGroup)) return false;
            ViewGroup root = (ViewGroup) deco.getParent();
            scan(root);
            ClassLoader cl = clockCl(root.getClass().getClassLoader());
            hookBlur(cl);
            if (box == null) {
                android.content.Context ctx = root.getContext();
                box = new android.widget.FrameLayout(ctx);
                try {
                    Class gc = Class.forName("com.miui.clock.MiuiTextGlassView", false, cl);
                    gTime = (TextView) gc.getConstructor(android.content.Context.class).newInstance(ctx);
                } catch (Throwable t) {
                    gTime = new TextView(ctx);   // sem a classe do HyperOS: texto comum, mesmo material
                }
                gDate = new TextView(ctx);
                gTime.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                gDate.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                gTime.setIncludeFontPadding(false);
                box.addView(gDate, new android.widget.FrameLayout.LayoutParams(-1, -2));
                box.addView(gTime, new android.widget.FrameLayout.LayoutParams(-1, -2));
            }
            if (box.getParent() != root) {
                if (box.getParent() != null) ((ViewGroup) box.getParent()).removeView(box);
                root.addView(box, root.indexOfChild(deco), new ViewGroup.LayoutParams(-1, -2));
                applyGlass();
            }
            float d = deco.getResources().getDisplayMetrics().density;
            if (clock != null) {
                gTime.setTypeface(clock.getTypeface());
                gTime.setLetterSpacing(clock.getLetterSpacing());
                try {
                    gTime.setFontVariationSettings(clock.getFontVariationSettings());
                } catch (Throwable ignored) {
                }
                gTime.setTextColor(clock.getCurrentTextColor());
            } else gTime.setTextColor(0xF2FFFFFF);
            if (dateV != null) gDate.setTypeface(dateV.getTypeface());
            gDate.setTextColor(0xEEFFFFFF);
            gDate.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dateSize);
            gTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ht * 1.08f);
            if (!ts.contentEquals(gTime.getText())) gTime.setText(ts);
            if (!ds.contentEquals(gDate.getText())) gDate.setText(ds);
            gTime.setTranslationY(dateSize * 1.15f);
            box.setTranslationY(top);
            ViewGroup.LayoutParams blp = box.getLayoutParams();
            int bw = width > 0 ? width : ViewGroup.LayoutParams.MATCH_PARENT;   // paisagem: só a coluna da esquerda
            if (blp != null && blp.width != bw) {
                blp.width = bw;
                box.setLayoutParams(blp);
            }
            return true;
        } catch (Throwable t) {
            if (!glassLogged) {
                glassLogged = true;
                ElasticCard.log("cortina: relógio de vidro indisponível: " + t);
            }
            return false;
        }
    }

    static void applyGlass() {
        try {
            MiBlend.container(box, passRadius >= 0 ? passRadius : 0);
            MiBlend.member(gTime);
            MiBlend.member(gDate);
            ElasticCard.log("cortina: relógio com material do bloqueio (" + (memberArgs != null ? "cores copiadas" : "padrão") + ")");
        } catch (Throwable t) {
            ElasticCard.log("cortina: material do relógio falhou: " + t);
        }
    }

    // ------------------------------------------------------- "Notificações" + limpar, com vibrance
    static android.widget.FrameLayout hbox;
    static TextView hTitle;
    static ImageView hClear;
    static String titleLang;

    /** Título no idioma do sistema: o texto do próprio HyperOS, ou uma tradução nossa. */
    static String notifTitle(android.content.Context ctx) {
        java.util.Locale loc = ctx.getResources().getConfiguration().getLocales().get(0);
        String lang = loc.getLanguage();
        try {
            int id = ctx.getResources().getIdentifier("notification_section_header_alerting", "string", "com.android.systemui");
            if (id != 0) {
                String s = ctx.getString(id);
                if (s != null && s.length() > 0 && (lang.equals("en") || !s.equals("Notifications"))) return s;
            }
        } catch (Throwable ignored) {
        }
        switch (lang) {
            case "pt": return "Notificações";
            case "es": return "Notificaciones";
            case "fr": return "Notifications";
            case "it": return "Notifiche";
            case "de": return "Mitteilungen";
            case "ru": return "Уведомления";
            case "tr": return "Bildirimler";
            case "id": return "Notifikasi";
            case "vi": return "Thông báo";
            case "ja": return "通知";
            case "ko": return "알림";
            case "zh": return "通知";
            case "ar": return "الإشعارات";
            case "hi": return "सूचनाएं";
            case "pl": return "Powiadomienia";
            case "uk": return "Сповіщення";
            default: return "Notifications";
        }
    }

    static boolean glassHeader(View deco, float gy, float alpha, boolean showClear, boolean pressed) {
        try {
            if (!(deco.getParent() instanceof ViewGroup)) return false;
            ViewGroup root = (ViewGroup) deco.getParent();
            android.content.Context ctx = root.getContext();
            float d = ctx.getResources().getDisplayMetrics().density;
            if (hbox == null) {
                hbox = new android.widget.FrameLayout(ctx);
                hTitle = new TextView(ctx);
                hTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 19 * d);
                hTitle.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL), 650, false));
                hTitle.setTextColor(0xF2FFFFFF);
                hTitle.setIncludeFontPadding(false);
                android.widget.FrameLayout.LayoutParams tl = new android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
                tl.setMarginStart(Math.round(20 * d));
                hbox.addView(hTitle, tl);
                hClear = new ImageView(ctx);
                hClear.setImageDrawable(new GroupClear.XDrawable(d));
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                bg.setColor(0x40FFFFFF);
                hClear.setBackground(bg);
                int p = Math.round(8 * d);
                hClear.setPadding(p, p, p, p);
                android.widget.FrameLayout.LayoutParams cl = new android.widget.FrameLayout.LayoutParams(Math.round(30 * d), Math.round(30 * d), android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
                cl.setMarginEnd(Math.round(21 * d));
                hbox.addView(hClear, cl);
            }
            if (hbox.getParent() != root) {
                if (hbox.getParent() != null) ((ViewGroup) hbox.getParent()).removeView(hbox);
                root.addView(hbox, root.indexOfChild(deco), new ViewGroup.LayoutParams(-1, Math.round(40 * d)));
                try {
                    MiBlend.container(hbox, 0);
                    MiBlend.member(hTitle);
                    MiBlend.member(hClear);
                    ElasticCard.log("cortina: título com vibrance");
                } catch (Throwable t) {
                    ElasticCard.log("cortina: vibrance do título indisponível: " + t);
                }
            }
            String lang = ctx.getResources().getConfiguration().getLocales().get(0).toLanguageTag();
            if (!lang.equals(titleLang)) {
                titleLang = lang;
                hTitle.setText(notifTitle(ctx));
            }
            hClear.setVisibility(showClear ? View.VISIBLE : View.INVISIBLE);
            hClear.setScaleX(pressed ? 0.9f : 1f);
            hClear.setScaleY(pressed ? 0.9f : 1f);
            hbox.setAlpha(alpha);
            hbox.setTranslationY(deco.getTop() + gy - 20 * d);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static void removeGlass() {
        if (box != null && box.getParent() instanceof ViewGroup) ((ViewGroup) box.getParent()).removeView(box);
        if (hbox != null && hbox.getParent() instanceof ViewGroup) ((ViewGroup) hbox.getParent()).removeView(hbox);
    }

    // ------------------------------------------------------- botões do bloqueio copiados
    static android.graphics.Bitmap snapL, snapR;
    static android.graphics.Rect rectL, rectR;
    static View bestL, bestR;

    /** Com o bloqueio na tela: acha os dois botões de baixo (esquerda e direita) e guarda a imagem deles. */
    static void snapBottom(View root) {
        try {
            bestL = bestR = null;
            int w = root.getWidth(), h = root.getHeight();
            if (w <= 0) return;
            float d = root.getResources().getDisplayMetrics().density;
            look(root, w, h, d);
            if (bestL == null || bestR == null) return;
            rectL = rectOf(bestL);
            rectR = rectOf(bestR);
            snapL = shot(bestL);
            snapR = shot(bestR);
            ElasticCard.log("cortina: botões do bloqueio copiados (" + bestL.getClass().getSimpleName() + " " + rectL + ", " + bestR.getClass().getSimpleName() + " " + rectR + ")");
        } catch (Throwable t) {
            ElasticCard.log("cortina: copiar botões falhou: " + t);
        }
    }

    static void look(View v, int w, int h, float d) {
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() <= 0.05f) return;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) look(g.getChildAt(i), w, h, d);
            if (g.getChildCount() > 0) return;
        }
        if (!(v instanceof ImageView) || v.getClass().getName().contains("Emergency")) return;
        int vw = v.getWidth(), vh = v.getHeight();
        if (vw < 20 * d || vh < 20 * d || vw > 110 * d || vh > 110 * d) return;
        android.graphics.Rect r = rectOf(v);
        if (r.centerY() < h * 0.82f) return;
        if (r.centerX() < w * 0.3f) {
            if (bestL == null || vw * vh > bestL.getWidth() * bestL.getHeight()) bestL = v;
        } else if (r.centerX() > w * 0.7f) {
            if (bestR == null || vw * vh > bestR.getWidth() * bestR.getHeight()) bestR = v;
        }
    }

    static android.graphics.Rect rectOf(View v) {
        int[] l = new int[2];
        v.getLocationInWindow(l);
        return new android.graphics.Rect(l[0], l[1], l[0] + v.getWidth(), l[1] + v.getHeight());
    }

    static android.graphics.Bitmap shot(View v) {
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(v.getWidth(), v.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        v.draw(c);
        return b;
    }

    static Drawable icon(View root, boolean isTorch) {
        scan(root);
        return isTorch ? torch : camera;
    }
}
