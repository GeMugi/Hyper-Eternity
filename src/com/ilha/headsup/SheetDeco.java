package com.ilha.headsup;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * Enfeites da central em folha, no estilo da tela de bloqueio do iOS: data e relógio grande no lugar
 * do cabeçalho do HyperOS, lanterna e câmera embaixo e a barrinha de puxar.
 */
final class SheetDeco extends View {
    final Paint date = new Paint(Paint.ANTI_ALIAS_FLAG), time = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint btn = new Paint(Paint.ANTI_ALIAS_FLAG), ico = new Paint(Paint.ANTI_ALIAS_FLAG), bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    final float d;
    View header;
    final int[] a = new int[2], b = new int[2];
    static boolean torch;
    int pressed = -1;
    final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    {
        title.setColor(0xF2FFFFFF);
        title.setTypeface(Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 650, false));
    }
    float clrX = -1, clrY, hdrA = 1f;
    final Paint vbg = new Paint(Paint.ANTI_ALIAS_FLAG), vx = new Paint(Paint.ANTI_ALIAS_FLAG);
    {
        vx.setStyle(Paint.Style.STROKE);
        vx.setStrokeCap(Paint.Cap.ROUND);
        vx.setStrokeWidth(2.2f * getResources().getDisplayMetrics().density);
    }

    SheetDeco(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        date.setColor(0xEEFFFFFF);
        date.setTextAlign(Paint.Align.CENTER);
        date.setTextSize(17 * d);
        date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        time.setColor(0xF2FFFFFF);
        time.setTextAlign(Paint.Align.CENTER);
        time.setTypeface(Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 600, false));
        btn.setColor(0x40FFFFFF);
        ico.setColor(0xFFFFFFFF);
        ico.setStyle(Paint.Style.STROKE);
        ico.setStrokeWidth(2.2f * d);
        ico.setStrokeCap(Paint.Cap.ROUND);
        ico.setStrokeJoin(Paint.Join.ROUND);
        bar.setColor(0xCCFFFFFF);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    float hx, hTop, hBot;

    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        // cabeçalho
        float top = 60 * d, bot = 170 * d;
        if (header != null && header.isAttachedToWindow() && header.getHeight() > 0) {
            header.getLocationInWindow(a);
            getLocationInWindow(b);
            top = a[1] - b[1];
            bot = top + header.getHeight();
        }
        boolean land = w > h;   // paisagem: relógio à esquerda, notificações à direita
        float off = land ? 0f : Prefs.sheetClock * d;   // posição do relógio (a lista desce junto)
        float lc = land ? -1 : CurtainClock.place(this, top, bot, d);   // relógio do bloqueio (estático)
        if (land) CurtainClock.remove();
        if (lc >= 0) off = lc;
        top += off;
        bot += off;
        float colL = land ? w * ShadeSheet.LAND_SPLIT : 0f;   // começo da coluna das notificações
        java.util.Calendar cal = java.util.Calendar.getInstance();
        java.util.Locale loc = java.util.Locale.getDefault();
        String ds = android.text.format.DateFormat.format(android.text.format.DateFormat.getBestDateTimePattern(loc, "EEEdMMM"), cal).toString();
        String ts = android.text.format.DateFormat.format(android.text.format.DateFormat.is24HourFormat(getContext()) ? "H:mm" : "h:mm", cal).toString();
        float ht = land ? Math.min(110 * d, h * 0.28f) : Math.max(56 * d, Math.min(96 * d, (bot - top) * 0.62f));
        float ctop = land ? h * 0.30f - ht * 0.6f : top;
        float ccx = land ? colL / 2f : w / 2f;
        time.setTextSize(ht);
        Kg.styleClock(time, date, getRootView());
        if (lc >= 0) Kg.removeGlass();
        else if (!Kg.glassClock(this, ds, ts, ctop, ht, date.getTextSize(), land ? Math.round(colL) : -1)) {
            c.drawText(ds, ccx, ctop + 22 * d, date);
            c.drawText(ts, ccx, ctop + 22 * d + 8 * d + ht * 0.78f, time);
        }
        // botões: cópia exata dos do bloqueio (mesma imagem e mesmo lugar); sem cópia, desenho próprio
        float r = 25 * d, cy = h - 64 * d;
        float lx = 52 * d + r - 10 * d, rx = w - lx;
        getLocationInWindow(b);
        // posição do bloqueio na cortina SEM o deslocamento dela: os botões descem junto com a folha
        float sheetTy = getParent() instanceof View ? ((View) getParent()).getTranslationY() : 0f;
        int bx = b[0], by = Math.round(b[1] - sheetTy);
        if (land) {   // a posição copiada é do bloqueio em retrato: em paisagem, nos cantos de baixo
            lx = 64 * d;
            rx = w - 64 * d;
            cy = h - 64 * d;
        } else {
            if (Kg.snapL != null) { lx = Kg.rectL.centerX() - bx; cy = Kg.rectL.centerY() - by; }
            if (Kg.snapR != null) rx = Kg.rectR.centerX() - bx;
        }
        btnL = lx; btnR = rx; btnY = cy;
        ShadeSheet.buttonBlur(this, lx, rx, cy, r * 1.12f);
        // fundo de vidro claro sempre (como o "x" do cabeçalho); aceso/pressionado fica mais claro
        btn.setColor(torch ? 0xF2FFFFFF : (pressed == 0 ? 0x4DFFFFFF : 0x2EFFFFFF));
        c.drawCircle(lx, cy, r * 1.12f, btn);
        btn.setColor(pressed == 1 ? 0x4DFFFFFF : 0x2EFFFFFF);
        c.drawCircle(rx, cy, r * 1.12f, btn);
        // ícones com a mesma vibrance do player: cada ícone vira cinza e é misturado (soma luz/multiplica)
        // com o círculo de vidro por trás; com a lanterna acesa o ícone fica escuro, sem mistura
        boolean nightI = true /* sempre claro: fica sobre o papel de parede desfocado, como o bloqueio */;
        int vcol = MediaCut.color(nightI, Prefs.mvText);
        Paint layer = new Paint();
        layer.setBlendMode(MediaCut.mode(nightI));
        float ir = r * 1.12f;
        // HyperOS 4: os ícones dos atalhos do bloqueio vêm dos recursos (seguem o sistema e o tema);
        // a cópia da tela fica só para o HyperOS 3
        boolean aff = !Kg.pluginSnap && (Kg.composeAff || Kg.affTorchOff != 0 || Kg.affTorchOn != 0 || Kg.affCamera != 0);
        if (!aff && Kg.snapL != null && Kg.snapR != null) {
            Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
            bp.setColorFilter(new android.graphics.PorterDuffColorFilter(torch ? 0xFF222222 : vcol, android.graphics.PorterDuff.Mode.SRC_IN));
            if (!torch) c.saveLayer(lx - ir, cy - ir, lx + ir, cy + ir, layer);
            c.drawBitmap(Kg.snapL, lx - Kg.snapL.getWidth() / 2f, cy - Kg.snapL.getHeight() / 2f, bp);
            if (!torch) c.restore();
            bp.setColorFilter(new android.graphics.PorterDuffColorFilter(vcol, android.graphics.PorterDuff.Mode.SRC_IN));
            c.saveLayer(rx - ir, cy - ir, rx + ir, cy + ir, layer);
            c.drawBitmap(Kg.snapR, rx - Kg.snapR.getWidth() / 2f, cy - Kg.snapR.getHeight() / 2f, bp);
            c.restore();
        } else {
        android.graphics.drawable.Drawable kfl = Kg.icon(getRootView(), true), kcm = Kg.icon(getRootView(), false);
        android.graphics.drawable.Drawable fl = kfl != null ? kfl : icon(torch ? new String[]{"ic_qs_flashlight_enabled", "qs_flashlight_icon_on", "ic_cc_qs_flashlight_on"}
                : new String[]{"ic_qs_flashlight_disabled", "qs_flashlight_icon_off", "ic_cc_qs_flashlight_off"});
        android.graphics.drawable.Drawable cm = kcm != null ? kcm : icon(new String[]{"keyguard_bottom_camera_img", "ic_camera", "camera_icon"});
        int is = Math.round(26 * d);
        if (fl != null) {
            fl.setBounds(Math.round(lx - is / 2f), Math.round(cy - is / 2f), Math.round(lx + is / 2f), Math.round(cy + is / 2f));
            fl.setTint(torch ? 0xFF222222 : vcol);
            if (!torch) c.saveLayer(lx - ir, cy - ir, lx + ir, cy + ir, layer);
            fl.draw(c);
            if (!torch) c.restore();
        } else {
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(lx - 6 * d, cy - 11 * d); p.lineTo(lx + 6 * d, cy - 11 * d); p.lineTo(lx + 6 * d, cy - 6 * d);
            p.lineTo(lx + 3 * d, cy - 1 * d); p.lineTo(lx + 3 * d, cy + 12 * d); p.lineTo(lx - 3 * d, cy + 12 * d);
            p.lineTo(lx - 3 * d, cy - 1 * d); p.lineTo(lx - 6 * d, cy - 6 * d); p.close();
            c.drawPath(p, ico);
        }
        ico.setColor(0xFFFFFFFF);
        if (cm != null) {
            cm.setBounds(Math.round(rx - is / 2f), Math.round(cy - is / 2f), Math.round(rx + is / 2f), Math.round(cy + is / 2f));
            cm.setTint(vcol);
            c.saveLayer(rx - ir, cy - ir, rx + ir, cy + ir, layer);
            cm.draw(c);
            c.restore();
        } else {
            c.drawRoundRect(rx - 11 * d, cy - 7 * d, rx + 11 * d, cy + 9 * d, 3 * d, 3 * d, ico);
            c.drawCircle(rx, cy + 1 * d, 4.5f * d, ico);
            c.drawLine(rx - 4 * d, cy - 10 * d, rx + 4 * d, cy - 10 * d, ico);
        }
        }
        // "Notificações" + limpar tudo (x) antes da lista, como no iOS; some sem notificações
        // "Notificações" + limpar tudo (x) no topo da lista; somem suaves quando não há notificações
        boolean any = ShadeSheet.hasNotifications();
        float target = any ? 1f : 0f;
        if (hdrA != target) {
            hdrA = target > hdrA ? Math.min(1f, hdrA + 0.08f) : Math.max(0f, hdrA - 0.08f);
            postInvalidateOnAnimation();
        }
        float gy = bot + 26 * d;
        // "Notificações" e o X só aparecem depois que a lista começa a rolar (somem de novo no topo)
        float sa = (land || lc < 0) ? 1f : Math.min(1f, ShadeSheet.scrollY() / (40 * d));   // sem o relógio do bloqueio: fixo
        float ha = hdrA * sa;
        // player de música na lista: "Notificações" e o X logo abaixo dele (posição real do player na tela)
        View pv = ShadeSheet.playerView;
        if (!land && pv != null && pv.getVisibility() != View.GONE && ShadeSheet.titleExtra > 0) {
            int[] pl = new int[2], dl = new int[2];
            pv.getLocationInWindow(pl);
            getLocationInWindow(dl);
            gy = pl[1] - dl[1] + pv.getHeight() * pv.getScaleY() + 24 * d;
        }
        // mesma vibrance dos tempos do player: cinza que soma luz (escuro) / multiplica (claro)
        boolean night = true /* sempre claro: fica sobre o papel de parede desfocado, como o bloqueio */;
        android.graphics.BlendMode bm = MediaCut.mode(night);
        if (ha > 0f) {
            title.setTextSize(19 * d);
            title.setColor(MediaCut.color(night, Prefs.mvText));
            title.setBlendMode(bm);
            title.setAlpha(Math.round(255 * ha));
            c.drawText(ShadeSheet.titleText(getContext()), colL + 20 * d, gy + 7 * d, title);
        }
        if (ShadeSheet.dismissView != null && ha > 0.3f) {
            clrX = (land ? w - ShadeSheet.LAND_RIGHT * d : w) - 36 * d;
            clrY = gy;
            // círculo de vidro claro (sem mistura: atrás dele é o desfoque da janela, a soma de luz o deixava branco)
            vbg.setBlendMode(null);
            vbg.setColor(pressed == 2 ? 0x4DFFFFFF : 0x2EFFFFFF);
            vbg.setAlpha(Math.round(vbg.getAlpha() * ha));
            c.drawCircle(clrX, clrY, 15 * d, vbg);
            vx.setColor(MediaCut.color(night, Prefs.mvText));
            vx.setBlendMode(bm);
            vx.setAlpha(Math.round(255 * ha));
            float k = 5 * d;
            c.drawLine(clrX - k, clrY - k, clrX + k, clrY + k, vx);
            c.drawLine(clrX - k, clrY + k, clrX + k, clrY - k, vx);
        } else clrX = -1;
        getLocationInWindow(b);
        ShadeSheet.moveHeader(this);
        // barrinha
        c.drawRoundRect(w / 2f - 66 * d, h - 12 * d, w / 2f + 66 * d, h - 7 * d, 3 * d, 3 * d, bar);
        postInvalidateDelayed(1000);
    }

    final java.util.HashMap<String, android.graphics.drawable.Drawable> icons = new java.util.HashMap<>();

    android.graphics.drawable.Drawable icon(String[] names) {
        for (String n : names) {
            if (icons.containsKey(n)) {
                android.graphics.drawable.Drawable dr = icons.get(n);
                if (dr != null) return dr;
                continue;
            }
            android.graphics.drawable.Drawable dr = null;
            try {
                int id = getResources().getIdentifier(n, "drawable", "com.android.systemui");
                if (id != 0) dr = getResources().getDrawable(id, null).mutate();
            } catch (Throwable ignored) {
            }
            icons.put(n, dr);
            if (dr != null) return dr;
        }
        return null;
    }

    float btnL, btnR, btnY;

    int hit(float x, float y) {
        float r = 34 * d, cy = btnY, lx = btnL, rx = btnR;
        if (Math.hypot(x - lx, y - cy) < r) return 0;
        if (Math.hypot(x - rx, y - cy) < r) return 1;
        if (clrX > 0 && Math.hypot(x - clrX, y - clrY) < 26 * d) return 2;
        return -1;
    }

    public boolean dispatchTouchEvent(MotionEvent e) {
        int k = hit(e.getX(), e.getY());
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (k < 0) return false;
            pressed = k;
            invalidate();
            return true;
        }
        if (pressed < 0) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            int was = pressed;
            pressed = -1;
            invalidate();
            if (k == was) act(k);
        } else if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            pressed = -1;
            invalidate();
        }
        return true;
    }

    void act(int k) {
        try {
            performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            if (k == 2) {
                ShadeSheet.clearAll();
                return;
            }
            if (k == 0) {
                android.hardware.camera2.CameraManager cm = (android.hardware.camera2.CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
                for (String id : cm.getCameraIdList()) {
                    Boolean f = cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (Boolean.TRUE.equals(f)) {
                        torch = !torch;
                        cm.setTorchMode(id, torch);
                        break;
                    }
                }
                invalidate();
            } else {
                android.content.Intent i = new android.content.Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ShadeSheet.collapse();
                getContext().startActivity(i);
            }
        } catch (Throwable t) {
            ElasticCard.log("folha botão: " + t);
        }
    }

    static View findHeader(View v, int depth) {
        if (v.getClass().getName().endsWith("MiuiNotificationHeaderView")) return v;
        if (depth > 8 || !(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View r = findHeader(g.getChildAt(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }
}
