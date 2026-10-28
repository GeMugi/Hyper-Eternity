package com.ilha.headsup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import com.ilha.headsup.xp.XC_MethodHook;
import com.ilha.headsup.xp.XposedBridge;
import java.lang.ref.WeakReference;

/**
 * Widgets do bloqueio (estilo iOS), numa fileira acima da lanterna e da câmera: agenda/alarme,
 * bateria, clima e passos. Desenhados aqui, com dados do sistema lidos fora da linha da tela.
 */
final class LockWidgets extends android.widget.FrameLayout {
    static LockWidgets view;
    static WeakReference<ViewGroup> kgRef;
    static Handler bg, main;
    static boolean interactive = true, locked = true, receivers;
    static int logs;

    // dados
    static int battery = -1;
    static boolean charging;
    static String temp, weatherDesc;
    static int tLow = Integer.MIN_VALUE, tHigh = Integer.MIN_VALUE, tNow = Integer.MIN_VALUE;
    static int steps = -1, stepGoal = 8000;
    static String evTitle;
    static long evBegin = -1;
    static boolean evAllDay;
    static long alarmAt = -1;
    static long loadedAt;

    static void install(ClassLoader cl) {
        try {
            Class pv = Class.forName("com.android.systemui.shade.NotificationShadeWindowView", false, cl);
            for (java.lang.reflect.Constructor k : pv.getDeclaredConstructors()) {
                XposedBridge.hookMethod(k, new XC_MethodHook() {
                    protected void afterHookedMethod(MethodHookParam p) {
                        final View root = (View) p.thisObject;
                        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                            public boolean onPreDraw() {
                                tick(root);
                                return true;
                            }
                        });
                    }
                });
            }
            try {
                Class sc = Class.forName("com.android.systemui.statusbar.StatusBarStateControllerImpl", false, cl);
                fState = Refl.field(sc, "mState");
                for (java.lang.reflect.Constructor k : sc.getDeclaredConstructors()) {
                    XposedBridge.hookMethod(k, new XC_MethodHook() {
                        protected void afterHookedMethod(MethodHookParam p) {
                            sbState = p.thisObject;
                        }
                    });
                }
            } catch (Throwable t) {
                ElasticCard.log("widgets do bloqueio: estado do bloqueio indisponível: " + t);
            }
            ElasticCard.log("widgets do bloqueio: prontos");
        } catch (Throwable t) {
            ElasticCard.log("widgets do bloqueio indisponíveis: " + t);
            Caps.fail(Keys.LOCK_WIDGETS, t);
        }
    }

    static long lastPlace;

    static Object sbState;
    static java.lang.reflect.Field fState;
    static WeakReference<View> bouncerRef;

    /** Só no bloqueio (estado KEYGUARD = 1): com a central puxada por cima (SHADE_LOCKED = 2) some. */
    static boolean onKeyguardOnly() {
        try {
            if (sbState == null || fState == null) return true;
            return fState.getInt(sbState) == 1;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Teclado da senha/PIN na tela. */
    static boolean bouncer(ViewGroup root) {
        try {
            View b = bouncerRef == null ? null : bouncerRef.get();
            if (b == null) {
                int id = root.getResources().getIdentifier("keyguard_bouncer_container", "id", "com.android.systemui");
                b = id == 0 ? null : root.findViewById(id);
                if (b == null) return false;
                bouncerRef = new WeakReference<>(b);
            }
            if (!b.isShown() || b.getAlpha() < 0.05f) return false;
            if (b instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) b;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c.getVisibility() == VISIBLE && c.getAlpha() > 0.05f && c.getHeight() > 0) return true;
                }
                return false;
            }
            return b.getHeight() > 0;
        } catch (Throwable t) {
            return false;
        }
    }


    /** A cada quadro da janela da central/bloqueio: põe, posiciona ou tira a fileira. */
    static void tick(View root) {
        try {
            if (!Prefs.lockWidgets) {
                if (view != null && view.getParent() != null) ((ViewGroup) view.getParent()).removeView(view);
                return;
            }
            ViewGroup kg = kgRef == null ? null : kgRef.get();
            if (kg == null || !kg.isAttachedToWindow()) {
                View f = Kg.find(root, "HyperOSKeyguardRootView", 0);
                if (!(f instanceof ViewGroup)) return;
                kg = (ViewGroup) f;
                kgRef = new WeakReference<>(kg);
            }
            Context ctx = kg.getContext();
            if (!receivers) setup(ctx);
            if (view == null) view = new LockWidgets(ctx);
            float d = ctx.getResources().getDisplayMetrics().density;
            // fica na janela (FrameLayout), logo acima do bloqueio: o bloqueio é um ConstraintLayout que
            // exige id em todos os filhos (sem id ele derrubava a Interface do Sistema)
            if (!(root instanceof ViewGroup)) return;
            ViewGroup rg = (ViewGroup) root;
            if (view.getParent() != rg) {
                if (view.getParent() != null) ((ViewGroup) view.getParent()).removeView(view);
                if (view.getId() == View.NO_ID) view.setId(View.generateViewId());
                int idx = rg.getChildCount();
                for (View c = kg; c != null && c.getParent() instanceof View; c = (View) c.getParent()) {
                    if (c.getParent() == rg) { idx = rg.indexOfChild(c) + 1; break; }
                }
                rg.addView(view, Math.min(idx, rg.getChildCount()), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(68 * d)));
                if (logs++ < 3) ElasticCard.log("widgets do bloqueio: fileira acima do bloqueio");
                refresh();
            }
            boolean land = kg.getWidth() > kg.getHeight();
            int vis = locked && interactive && !land && kg.isShown() && onKeyguardOnly() && !bouncer(rg) ? VISIBLE : GONE;
            if (view.getVisibility() != vis) {
                view.setVisibility(vis);
                if (vis == VISIBLE) refresh();
            }
            if (vis != VISIBLE) return;
            // some e aparece junto com o bloqueio (puxar a central, desbloquear)
            float a = 1f;
            for (View c = kg; c != null && c != rg; c = c.getParent() instanceof View ? (View) c.getParent() : null) a *= c.getAlpha();
            if (Math.abs(view.getAlpha() - a) > 0.01f) view.setAlpha(a);
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastPlace > 1000) {
                lastPlace = now;
                int[] kl = new int[2], rl = new int[2];
                kg.getLocationInWindow(kl);
                rg.getLocationInWindow(rl);
                float y = kl[1] - rl[1] + buttonsTop(kg, d) - 12 * d - view.getHeight();
                if (Math.abs(view.getTranslationY() - y) > 1) view.setTranslationY(y);
                view.update();   // bordas da lanterna/câmera podem ter mudado
                restyle(rg, view, d);
            }
        } catch (Throwable t) {
            if (logs++ < 8) ElasticCard.log("widgets do bloqueio: " + t);
        }
    }

    /** Topo dos atalhos de baixo do bloqueio (lanterna/câmera), nas coordenadas do bloqueio. */
    static float rowL = -1, rowR = -1;   // bordas da fileira na janela: da lanterna até a câmera

    static float buttonsTop(ViewGroup kg, float d) {
        float fallback = kg.getHeight() - 150 * d;
        try {
            java.util.ArrayList<View> icons = new java.util.ArrayList<>();
            Object scv = Kg.shortcutCtl == null ? null : Refl.get(Kg.shortcutCtl, "shortcutContainerView");
            if (scv instanceof View && ((View) scv).isShown()) icons(((View) scv), kg.getHeight(), d, icons);
            if (icons.isEmpty()) icons(kg, kg.getHeight(), d, icons);
            if (icons.isEmpty()) { rowL = rowR = -1; return fallback; }
            int[] a = new int[2], b = new int[2];
            kg.getLocationInWindow(b);
            float minX = Float.MAX_VALUE, maxX = -1, cy = 0;
            for (View v : icons) {
                v.getLocationInWindow(a);
                float cx = a[0] + v.getWidth() / 2f;
                minX = Math.min(minX, cx);
                maxX = Math.max(maxX, cx);
                cy = Math.max(cy, a[1] - b[1] + v.getHeight() / 2f);
            }
            float W = kg.getWidth();
            if (maxX - minX < W * 0.3f) { minX = Math.min(minX, W - maxX); maxX = W - minX; }   // só um atalho: simétrico
            rowL = minX - 32 * d;
            rowR = maxX + 32 * d;
            return Math.min(fallback + 60 * d, cy - 34 * d);   // círculo visível do atalho (~30 dp de raio)
        } catch (Throwable t) {
            return fallback;
        }
    }

    static void icons(View v, int h, float d, java.util.ArrayList<View> out) {
        if (v == view || v.getVisibility() != VISIBLE) return;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) icons(g.getChildAt(i), h, d, out);
            return;
        }
        if (!(v instanceof android.widget.ImageView)) return;
        String idn = "";
        try { if (v.getId() != View.NO_ID) idn = v.getResources().getResourceEntryName(v.getId()).toLowerCase(); } catch (Throwable ignored) {}
        if (!(idn.contains("shortcut") || idn.contains("start") || idn.contains("end") || idn.contains("affordance") || idn.contains("camera") || idn.contains("flash"))) return;
        if (v.getWidth() < 20 * d || v.getWidth() > 120 * d) return;
        int[] a = new int[2];
        v.getLocationInWindow(a);
        if (a[1] + v.getHeight() / 2f < h * 0.8f) return;
        out.add(v);
    }

    /** Topo da fileira nas coordenadas de outra view (para a lista do bloqueio parar acima); NaN se não há. */
    static float topIn(View host) {
        try {
            if (view == null || view.getVisibility() != VISIBLE || view.getParent() == null || !view.hasAny()) return Float.NaN;
            int[] a = new int[2], b = new int[2];
            view.getLocationInWindow(a);
            host.getLocationInWindow(b);
            return a[1] - b[1];
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    static void setup(Context ctx) {
        receivers = true;
        try {
            HandlerThread ht = new HandlerThread("hyper-lockwidgets");
            ht.start();
            bg = new Handler(ht.getLooper());
            main = new Handler(android.os.Looper.getMainLooper());
            android.app.KeyguardManager km = ctx.getSystemService(android.app.KeyguardManager.class);
            locked = km == null || km.isKeyguardLocked();
            android.os.PowerManager pm = ctx.getSystemService(android.os.PowerManager.class);
            interactive = pm == null || pm.isInteractive();
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_BATTERY_CHANGED);
            f.addAction(android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
            f.addAction(Intent.ACTION_TIME_TICK);
            ctx.registerReceiver(new BroadcastReceiver() {
                public void onReceive(Context c, Intent i) {
                    String a = i.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(a)) { interactive = false; locked = true; }
                    else if (Intent.ACTION_SCREEN_ON.equals(a)) { interactive = true; refresh(); }
                    else if (Intent.ACTION_USER_PRESENT.equals(a)) locked = false;
                    else if (Intent.ACTION_BATTERY_CHANGED.equals(a)) battery(i);
                    else if (Intent.ACTION_TIME_TICK.equals(a)) { if (interactive && locked && System.currentTimeMillis() - loadedAt > 10 * 60000L) refresh(); else redraw(); }
                    else refresh();
                }
            }, f, Context.RECEIVER_EXPORTED);
            ctx.registerReceiver(new BroadcastReceiver() {
                public void onReceive(Context c, Intent i) {
                    loadWall(c);
                }
            }, new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED), Context.RECEIVER_EXPORTED);
            loadWall(ctx);
            Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) battery(b);
        } catch (Throwable t) {
            ElasticCard.log("widgets do bloqueio: avisos do sistema: " + t);
        }
    }

    static android.graphics.Bitmap wallBlur;
    static int scrW = 1080, scrH = 2400;

    /** Papel de parede do bloqueio, pequeno e desfocado (uma vez; de novo se ele mudar). */
    static void loadWall(final Context ctx) {
        if (bg == null) return;
        bg.post(new Runnable() {
            public void run() {
                try {
                    android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                    scrW = Math.min(dm.widthPixels, dm.heightPixels);
                    scrH = Math.max(dm.widthPixels, dm.heightPixels);
                    android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(ctx);
                    android.graphics.drawable.Drawable dr = null;
                    try { dr = wm.getDrawable(android.app.WallpaperManager.FLAG_LOCK); } catch (Throwable ignored) {}
                    if (dr == null) dr = wm.getDrawable();
                    if (!(dr instanceof android.graphics.drawable.BitmapDrawable)) { wallBlur = null; return; }
                    android.graphics.Bitmap src = ((android.graphics.drawable.BitmapDrawable) dr).getBitmap();
                    int w = Math.max(1, src.getWidth() / 10), h = Math.max(1, src.getHeight() / 10);
                    android.graphics.Bitmap s = android.graphics.Bitmap.createScaledBitmap(src, w, h, true).copy(android.graphics.Bitmap.Config.ARGB_8888, true);
                    Frost.boxBlur(s, 4);
                    Frost.boxBlur(s, 4);
                    wallBlur = s;
                    redraw();
                } catch (Throwable t) {
                    wallBlur = null;
                    if (logs++ < 10) ElasticCard.log("widgets do bloqueio: papel de parede: " + t);
                }
            }
        });
    }

    static void battery(Intent i) {
        int lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        battery = lv < 0 ? -1 : Math.round(lv * 100f / Math.max(1, sc));
        int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
        redraw();
    }

    static void redraw() {
        if (view != null && main != null) main.post(new Runnable() { public void run() { if (view != null) view.update(); } });
    }

    /** Lê clima, passos, agenda e alarme numa linha separada e redesenha. */
    static void refresh() {
        final LockWidgets v = view;
        if (bg == null || v == null) return;
        final Context ctx = v.getContext();
        bg.post(new Runnable() {
            public void run() {
                loadedAt = System.currentTimeMillis();
                try {
                    android.app.AlarmManager am = ctx.getSystemService(android.app.AlarmManager.class);
                    android.app.AlarmManager.AlarmClockInfo ai = am == null ? null : am.getNextAlarmClock();
                    alarmAt = ai == null || ai.getTriggerTime() - System.currentTimeMillis() > 24 * 3600000L ? -1 : ai.getTriggerTime();
                } catch (Throwable t) {
                    alarmAt = -1;
                }
                if (Prefs.lwWeather) weather(ctx);
                if (Prefs.lwSteps) steps(ctx);
                if (Prefs.lwAgenda) agenda(ctx);
                redraw();
            }
        });
    }

    static void weather(Context ctx) {
        for (String type : new String[]{"1", "2"}) {
            Cursor c = null;
            try {
                c = ctx.getContentResolver().query(Uri.parse("content://weather/actualWeatherData/" + type), null, null, null, null);
                if (c == null || !c.moveToFirst()) continue;
                int it = c.getColumnIndex("temperature"), id = c.getColumnIndex("description");
                String t = it < 0 ? null : c.getString(it);
                if (t == null || t.trim().isEmpty()) continue;
                String n = t.replaceAll("[^0-9\\-]", "");
                temp = n.isEmpty() ? t : n + "°";
                tNow = num(n);
                weatherDesc = id < 0 ? null : c.getString(id);
                int il = c.getColumnIndex("tmplows"), ih = c.getColumnIndex("tmphighs");
                tLow = il < 0 ? Integer.MIN_VALUE : num(first(c.getString(il)));
                tHigh = ih < 0 ? Integer.MIN_VALUE : num(first(c.getString(ih)));
                return;
            } catch (Throwable e) {
                if (logs++ < 10) ElasticCard.log("widgets do bloqueio: clima: " + e);
            } finally {
                if (c != null) c.close();
            }
        }
        temp = null;
    }

    static String first(String s) {
        if (s == null) return "";
        String[] p = s.split("[,;| ]");
        return p.length > 0 ? p[0].replaceAll("[^0-9\\-]", "") : "";
    }

    static int num(String s) {
        try { return s == null || s.isEmpty() ? Integer.MIN_VALUE : Integer.parseInt(s); } catch (Throwable t) { return Integer.MIN_VALUE; }
    }

    static void steps(Context ctx) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(Uri.parse("content://com.mi.health.provider.main/activity/steps/brief"), null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int is = c.getColumnIndex("steps"), ig = c.getColumnIndex("goal");
                steps = is < 0 ? -1 : c.getInt(is);
                if (ig >= 0 && c.getInt(ig) > 0) stepGoal = c.getInt(ig);
                return;
            }
        } catch (Throwable e) {
            if (logs++ < 10) ElasticCard.log("widgets do bloqueio: passos: " + e);
        } finally {
            if (c != null) c.close();
        }
        steps = -1;
    }

    static void agenda(Context ctx) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(Uri.parse("content://" + Keys.PKG + ".agenda/next"), null, null, null, null);
            evTitle = null;
            evBegin = -1;
            if (c != null) {
                // primeiro um evento com hora; se só houver eventos de dia inteiro, o primeiro deles
                String at = null;
                long ab = -1;
                while (c.moveToNext()) {
                    boolean all = c.getInt(3) != 0;
                    if (!all) { evTitle = c.getString(0); evBegin = c.getLong(1); evAllDay = false; return; }
                    if (at == null) { at = c.getString(0); ab = c.getLong(1); }
                }
                if (at != null) { evTitle = at; evBegin = ab; evAllDay = true; }
            } else if (logs++ < 10) ElasticCard.log("widgets do bloqueio: agenda sem resposta (permita o acesso à agenda nos ajustes)");
        } catch (Throwable e) {
            if (logs++ < 10) ElasticCard.log("widgets do bloqueio: agenda: " + e);
        } finally {
            if (c != null) c.close();
        }
    }

    // ------------------------------------------------------------------------------------------ desenho

    final float d;
    final Tile[] tiles = new Tile[4];   // 0 bateria (pílula), 1 clima, 2 passos, 3 alarme/agenda

    LockWidgets(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClipChildren(false);
        sysBlur = containerBlur(c, this);
        for (int i = 0; i < 4; i++) {
            tiles[i] = new Tile(c, i);
            tiles[i].setVisibility(GONE);   // só aparecem depois de ter dado
            addView(tiles[i], new android.widget.FrameLayout.LayoutParams(Math.round((i == 0 ? 140 : 64) * d), Math.round(64 * d)));
        }
    }

    public boolean dispatchTouchEvent(MotionEvent e) {
        return false;   // só imagem: o bloqueio continua recebendo os gestos
    }

    static String L(String pt, String en, String es) {
        String l = java.util.Locale.getDefault().getLanguage();
        return "pt".equals(l) ? pt : "es".equals(l) ? es : en;
    }

    static boolean showBattery() { return Prefs.lwBattery && battery >= 0; }
    static boolean showWeather() { return Prefs.lwWeather && temp != null; }
    static boolean showSteps() { return Prefs.lwSteps && steps >= 0; }
    static boolean showAgenda() { return Prefs.lwAgenda && (alarmAt > 0 || (evTitle != null && !evAllDay)); }

    boolean hasAny() { return showBattery() || showWeather() || showSteps() || showAgenda(); }

    /** Quais widgets aparecem e onde (centralizados na fileira). */
    /**
     * Quais widgets aparecem e com que largura: espaço fixo entre eles e a sobra da fileira (lanterna até
     * a câmera) vai para os que crescem (bateria e clima viram pílulas maiores), sem buracos no meio.
     */
    void update() {
        boolean[] on = {showBattery(), showWeather(), showSteps(), showAgenda()};
        float gap = 10 * d, circ = 64 * d, pill = 140 * d;
        int n = 0;
        float base = 0;
        for (int i = 0; i < 4; i++) if (on[i]) { base += i == 0 ? pill : circ; n++; }
        base += Math.max(0, n - 1) * gap;
        int[] me = new int[2];
        getLocationInWindow(me);
        float left, avail;
        if (rowL >= 0 && rowR > rowL) { left = rowL - me[0]; avail = rowR - rowL; }
        else { avail = getWidth() - 32 * d; left = 16 * d; }
        float[] w = {pill, circ, circ, circ};
        float extra = avail - base;
        if (extra > 0) {
            // clima vira pílula se ganhar espaço suficiente; senão tudo vai para a bateria
            boolean bat = on[0], wea = on[1];
            // a bateria cresce só até um limite; o que sobra centraliza a fileira (sem buracos no meio)
            float batMax = 168 * d, used = 0;
            if (bat && wea && extra >= 2 * 56 * d) {
                // bateria e clima viram duas pílulas do mesmo tamanho
                float each = Math.min((w[0] + w[1] + extra) / 2f, 180 * d);
                used = 2 * each - w[0] - w[1];
                w[0] = w[1] = each;
            }
            else if (bat) { float g0 = Math.min(extra, batMax - w[0]); w[0] += g0; used = g0; }
            else if (wea) { w[1] += extra; used = extra; }
            left += (extra - used) / 2f;
        } else if (extra < 0 && on[0]) {
            w[0] = Math.max(110 * d, w[0] + extra);
        }
        float x = left;
        for (int i = 0; i < 4; i++) {
            Tile t = tiles[i];
            int vis = on[i] ? VISIBLE : GONE;
            if (t.getVisibility() != vis) t.setVisibility(vis);
            if (!on[i]) continue;
            ViewGroup.LayoutParams lp = t.getLayoutParams();
            if (lp.width != Math.round(w[i])) { lp.width = Math.round(w[i]); t.setLayoutParams(lp); }
            t.setTranslationX(x);
            t.setTranslationY((getHeight() - 64 * d) / 2f);
            t.invalidate();
            x += w[i] + gap;
        }
    }

    protected void onLayout(boolean ch, int l, int t, int r, int b) {
        super.onLayout(ch, l, t, r, b);
        update();
    }

    /** Um widget: vidro desfocado do HyperOS no formato dele + o conteúdo desenhado por cima. */
    static final class Tile extends View {
        final int kind;
        final float d;
        boolean blur;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint big = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        final android.graphics.Path path = new android.graphics.Path();

        Tile(Context c, int kind) {
            super(c);
            this.kind = kind;
            d = c.getResources().getDisplayMetrics().density;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            blur = sysBlur && elementBlend(c, this);   // mesmo vidro das notificações do bloqueio
            // depois do vidro: o HyperOS troca o contorno pelo cantinho dos cartões de notificação;
            // aqui o widget é redondo (pílula/círculo, como os botões de baixo)
            setOutlineProvider(ROUND);
            setClipToOutline(true);
            fill.setColor(0x33FFFFFF);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeCap(Paint.Cap.ROUND);
            glyph.setColor(0xF2FFFFFF);
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeJoin(Paint.Join.ROUND);
            Typeface bold = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 700, false);
            big.setTypeface(bold);
            big.setColor(0xF7FFFFFF);
            small.setColor(0xCCFFFFFF);
        }

        protected void onDraw(Canvas c) {
            // nunca derruba a Interface do Sistema: dado sumindo no meio do desenho só pula o quadro
            try {
                float w = getWidth(), h = getHeight();
                glass(c, w, h);
                if (kind == 0) { if (battery >= 0) battery(c, w, h); }
                else if (kind == 1) { String t = temp; if (t != null) weather(c, w, h, t); }
                else if (kind == 2) { if (steps >= 0) stepsTile(c, w, h); }
                else if (alarmAt > 0 || evBegin > 0) agendaTile(c, w, h);
            } catch (Throwable t) {
                if (logs++ < 12) ElasticCard.log("widgets do bloqueio: desenho: " + t);
            }
        }

        final int[] at = new int[2];
        final android.graphics.Rect src = new android.graphics.Rect();
        final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);

        /** Vidro: o papel de parede do bloqueio desfocado, no lugar exato do widget, com um véu claro. */
        void glass(Canvas c, float w, float h) {
            if (blur) return;   // o HyperOS desenha o vidro (desfoque + cores das notificações)
            android.graphics.Bitmap wb = wallBlur;
            if (wb != null && !wb.isRecycled()) {
                getLocationOnScreen(at);
                float W = scrW, H = scrH, sc = Math.max(W / wb.getWidth(), H / wb.getHeight());
                float ox = (W - wb.getWidth() * sc) / 2f, oy = (H - wb.getHeight() * sc) / 2f;
                src.set(Math.round((at[0] - ox) / sc), Math.round((at[1] - oy) / sc), Math.round((at[0] + w - ox) / sc), Math.round((at[1] + h - oy) / sc));
                r.set(0, 0, w, h);
                c.drawBitmap(wb, src, r, bmp);
                fill.setColor(0x2EFFFFFF);
            } else fill.setColor(0x38FFFFFF);
            r.set(0, 0, w, h);
            c.drawRect(r, fill);
        }

        void arc(Canvas c, float cx, float cy, float rad, float stroke, float f, int color, float start, float sweep) {
            r.set(cx - rad, cy - rad, cx + rad, cy + rad);
            ring.setStrokeWidth(stroke);
            ring.setColor(0x4DFFFFFF);
            c.drawArc(r, start, sweep, false, ring);
            if (f > 0) {
                ring.setColor(color);
                c.drawArc(r, start, sweep * Math.max(0.03f, Math.min(1f, f)), false, ring);
            }
        }

        /** Pílula: anel com o celular dentro, nome do aparelho e a porcentagem grande. */
        void battery(Canvas c, float w, float h) {
            float cx = h / 2f, cy = h / 2f, rad = 19 * d;
            int col = charging ? 0xFF34C759 : (battery <= 20 ? 0xFFFF453A : 0xF7FFFFFF);
            arc(c, cx, cy, rad, 4 * d, battery / 100f, col, -90, 360);
            // celular
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeWidth(1.8f * d);
            r.set(cx - 5.5f * d, cy - 9 * d, cx + 5.5f * d, cy + 9 * d);
            c.drawRoundRect(r, 2.5f * d, 2.5f * d, glyph);
            glyph.setStyle(Paint.Style.FILL);
            if (charging) {
                path.reset();
                path.moveTo(cx + 1 * d, cy - 6 * d); path.lineTo(cx - 3 * d, cy + 1 * d); path.lineTo(cx, cy + 1 * d);
                path.lineTo(cx - 1 * d, cy + 6 * d); path.lineTo(cx + 3 * d, cy - 1 * d); path.lineTo(cx, cy - 1 * d); path.close();
                c.drawPath(path, glyph);
            }
            float tx = h - 2 * d;
            small.setTextSize(11 * d);
            small.setTextAlign(Paint.Align.LEFT);
            String name = deviceName(getContext());
            c.drawText(TextUtils.ellipsize(name, small, w - tx - 14 * d, TextUtils.TruncateAt.END).toString(), tx, cy - 5 * d, small);
            big.setTextSize(21 * d);
            big.setTextAlign(Paint.Align.LEFT);
            c.drawText(battery + "%", tx, cy + 17 * d, big);
        }

        /** Círculo: temperatura grande, mínima e máxima embaixo, arco com a posição de agora. */
        void weather(Canvas c, float w, float h, String temp) {
            if (w > h * 1.8f) { weatherPill(c, w, h, temp); return; }
            float cx = w / 2f, cy = h / 2f, rad = w / 2f - 5 * d;
            boolean range = tLow != Integer.MIN_VALUE && tHigh != Integer.MIN_VALUE && tHigh > tLow && tNow != Integer.MIN_VALUE;
            if (range) {
                float f = (tNow - tLow) / (float) (tHigh - tLow);
                arc(c, cx, cy, rad, 3 * d, 0, 0, 135, 270);
                double a = Math.toRadians(135 + 270 * Math.max(0f, Math.min(1f, f)));
                glyph.setStyle(Paint.Style.FILL);
                c.drawCircle(cx + (float) Math.cos(a) * rad, cy + (float) Math.sin(a) * rad, 3.2f * d, glyph);
            }
            big.setTextSize(20 * d);
            big.setTextAlign(Paint.Align.CENTER);
            c.drawText(temp, cx + 2 * d, cy + (range ? 4 : 7) * d, big);
            small.setTextAlign(Paint.Align.CENTER);
            small.setTextSize(10 * d);
            if (range) c.drawText(tLow + "°  " + tHigh + "°", cx, cy + 19 * d, small);
            else if (weatherDesc != null && !weatherDesc.isEmpty()) c.drawText(TextUtils.ellipsize(weatherDesc, small, w - 16 * d, TextUtils.TruncateAt.END).toString(), cx, cy + 19 * d, small);
        }

        /** Pílula do clima: anel com a temperatura à esquerda, condição e mínima/máxima ao lado. */
        void weatherPill(Canvas c, float w, float h, String temp) {
            float cx = h / 2f, cy = h / 2f, rad = 19 * d;
            boolean range = tLow != Integer.MIN_VALUE && tHigh != Integer.MIN_VALUE && tHigh > tLow && tNow != Integer.MIN_VALUE;
            arc(c, cx, cy, rad, 3 * d, 0, 0, 135, 270);
            if (range) {
                double a = Math.toRadians(135 + 270 * Math.max(0f, Math.min(1f, (tNow - tLow) / (float) (tHigh - tLow))));
                glyph.setStyle(Paint.Style.FILL);
                c.drawCircle(cx + (float) Math.cos(a) * rad, cy + (float) Math.sin(a) * rad, 3 * d, glyph);
            }
            big.setTextAlign(Paint.Align.CENTER);
            big.setTextSize(15 * d);
            c.drawText(temp, cx + 1 * d, cy + 5 * d, big);
            float tx = h - 2 * d, tw = w - tx - 14 * d;
            small.setTextAlign(Paint.Align.LEFT);
            small.setTextSize(12 * d);
            String desc = weatherDesc != null && !weatherDesc.isEmpty() ? weatherDesc : L("Clima", "Weather", "Clima");
            big.setTextAlign(Paint.Align.LEFT);
            big.setTextSize(15 * d);
            c.drawText(TextUtils.ellipsize(desc, big, tw, TextUtils.TruncateAt.END).toString(), tx, cy - 3 * d, big);
            if (range) c.drawText(tLow + "° ~ " + tHigh + "°", tx, cy + 15 * d, small);
        }

        /** Círculo: tênis em cima, passos embaixo, anel até a meta. */
        void stepsTile(Canvas c, float w, float h) {
            float cx = w / 2f, cy = h / 2f;
            arc(c, cx, cy, w / 2f - 5 * d, 3 * d, steps / (float) Math.max(1, stepGoal), 0xFF34C759, 135, 270);
            // tênis
            glyph.setStyle(Paint.Style.FILL);
            path.reset();
            float sx = cx - 8 * d, sy = cy - 13 * d;
            path.moveTo(sx, sy); path.lineTo(sx + 5 * d, sy); path.lineTo(sx + 7 * d, sy + 3 * d);
            path.lineTo(sx + 14 * d, sy + 5 * d); path.quadTo(sx + 17 * d, sy + 6 * d, sx + 16 * d, sy + 8 * d);
            path.lineTo(sx, sy + 8 * d); path.close();
            c.drawPath(path, glyph);
            big.setTextSize(14 * d);
            big.setTextAlign(Paint.Align.CENTER);
            String s = steps >= 100000 ? (steps / 1000) + "k" : String.valueOf(steps);
            c.drawText(s, cx, cy + 12 * d, big);
        }

        /** Círculo: despertador (ou agenda) em cima e o horário embaixo. */
        void agendaTile(Canvas c, float w, float h) {
            float cx = w / 2f, cy = h / 2f;
            boolean alarm = alarmAt > 0 && (evTitle == null || evAllDay || alarmAt <= evBegin || evBegin < System.currentTimeMillis());
            long at = alarm ? alarmAt : evBegin;
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeWidth(1.8f * d);
            float gy = cy - 9 * d;
            if (alarm) {
                c.drawCircle(cx, gy, 6.5f * d, glyph);
                c.drawLine(cx, gy, cx, gy - 3.5f * d, glyph);
                c.drawLine(cx, gy, cx + 2.5f * d, gy + 1.5f * d, glyph);
                c.drawLine(cx - 7 * d, gy - 5 * d, cx - 4.5f * d, gy - 7 * d, glyph);
                c.drawLine(cx + 7 * d, gy - 5 * d, cx + 4.5f * d, gy - 7 * d, glyph);
            } else {
                r.set(cx - 7 * d, gy - 6 * d, cx + 7 * d, gy + 6 * d);
                c.drawRoundRect(r, 2 * d, 2 * d, glyph);
                c.drawLine(cx - 7 * d, gy - 2 * d, cx + 7 * d, gy - 2 * d, glyph);
            }
            big.setTextSize(15 * d);
            big.setTextAlign(Paint.Align.CENTER);
            java.text.DateFormat tf = android.text.format.DateFormat.getTimeFormat(getContext());
            String ts = tf.format(new java.util.Date(at)).replace(" AM", "").replace(" PM", "").replace(" a.m.", "").replace(" p.m.", "");
            c.drawText(ts, cx, cy + 14 * d, big);
        }
    }

    static boolean sysBlur;
    static int blurLogs;

    static final android.view.ViewOutlineProvider ROUND = new android.view.ViewOutlineProvider() {
        public void getOutline(View v, android.graphics.Outline o) {
            o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Math.min(v.getWidth(), v.getHeight()) / 2f);
        }
    };

    static final java.util.WeakHashMap<View, Long> styled = new java.util.WeakHashMap<>();
    static int styleLogs;
    static int[] blendCols;

    /** Cores do vidro dos cartões de notificação do bloqueio (cor, modo, cor, modo). */
    static int[] blendCols(Context c) {
        if (blendCols != null) return blendCols;
        android.content.res.Resources r = c.getResources();
        String pkg = "com.android.systemui";
        int c1 = r.getIdentifier("notification_element_blend_keyguard_color_1", "color", pkg);
        int c2 = r.getIdentifier("notification_element_blend_keyguard_color_2", "color", pkg);
        int m1 = r.getIdentifier("notification_element_blend_keyguard_mode_1", "integer", pkg);
        int m2 = r.getIdentifier("notification_element_blend_keyguard_mode_2", "integer", pkg);
        if (c1 == 0 || c2 == 0 || m1 == 0 || m2 == 0) return null;
        return blendCols = new int[]{c.getColor(c1), r.getInteger(m1), c.getColor(c2), r.getInteger(m2)};
    }

    static int viewBlurMode(View v) {
        try {
            Object o = View.class.getMethod("getMiViewBlurMode").invoke(v);
            return o instanceof Integer ? (Integer) o : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * A ilha e os botões de baixo do bloqueio (lanterna, câmera, ilha da música, ícones) com o mesmo
     * vidro dos cartões de notificação: troca só as cores da mistura de quem já é vidro no sistema,
     * mantendo o formato (círculo/pílula) de cada um.
     */
    static void restyle(ViewGroup root, View me, float d) {
        try {
            if (!sysBlur || MiBlend.v4()) return;   // HyperOS 4: os botões do plugin já são de vidro próprio
            int[] cols = blendCols(root.getContext());
            if (cols == null) return;
            int[] ml = new int[2];
            me.getLocationInWindow(ml);
            java.util.ArrayList<View> out = new java.util.ArrayList<>();
            band(root, me, ml[1] + me.getHeight() - 4 * d, d, out);
            Class mb = Class.forName("com.miui.systemui.util.MiBlurCompat", false, root.getContext().getClassLoader());
            java.lang.reflect.Method clear = mb.getMethod("clearMiBackgroundBlendColorCompat", View.class);
            java.lang.reflect.Method set = mb.getMethod("setMiBackgroundBlendColors", View.class, int[].class, float.class);
            long now = android.os.SystemClock.uptimeMillis();
            for (View v : out) {
                Long at = styled.get(v);
                if (at != null && now - at < 3000) continue;   // o sistema pode refazer as cores: confere de tempos em tempos
                clear.invoke(null, v);
                set.invoke(null, v, cols, Float.valueOf(1f));
                v.invalidate();
                if (at == null && styleLogs++ < 8) ElasticCard.log("widgets do bloqueio: vidro das notificações em " + v.getClass().getSimpleName()
                        + " " + Kg.idName(v) + " " + v.getWidth() + "x" + v.getHeight());
                styled.put(v, now);
            }
            if (out.isEmpty() && styleLogs < 8) {
                styleLogs++;
                ElasticCard.log("widgets do bloqueio: nenhum vidro do sistema embaixo para igualar");
            }
        } catch (Throwable t) {
            if (styleLogs++ < 8) ElasticCard.log("widgets do bloqueio: igualar vidro: " + t);
        }
    }

    /** Views de vidro do sistema (desfoque próprio) abaixo da fileira de widgets. */
    static void band(View v, View me, float top, float d, java.util.ArrayList<View> out) {
        if (v == me || v.getVisibility() != VISIBLE || v.getAlpha() <= 0.02f) return;
        int[] a = new int[2];
        v.getLocationInWindow(a);
        if (v.getWidth() > 0 && v.getHeight() > 0 && v.getHeight() < 140 * d && a[1] + v.getHeight() / 2f > top && viewBlurMode(v) != 0) {
            out.add(v);
            return;
        }
        if (v instanceof ViewGroup) {
            if (v.getHeight() > 0 && a[1] + v.getHeight() < top) return;   // inteiro acima: nada aqui
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) band(g.getChildAt(i), me, top, d, out);
        }
    }

    /** Recipiente do vidro, como a lista das notificações (o HyperOS desfoca o que está atrás). */
    static boolean containerBlur(Context c, View v) {
        try {
            Class mb = Class.forName("com.miui.systemui.util.MiBlurCompat", false, c.getClassLoader());
            Object open = mb.getMethod("getBackgroundBlurOpened", Context.class).invoke(null, c);
            if (!Boolean.TRUE.equals(open)) {
                if (blurLogs++ < 2) ElasticCard.log("widgets do bloqueio: desfoque do sistema desligado, papel de parede desfocado");
                return false;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class nu = Class.forName("com.android.systemui.statusbar.notification.utils.NotificationUtil", false, c.getClassLoader());
            nu.getMethod("applyContainerViewBlur", Context.class, View.class, int.class, int.class, boolean.class).invoke(null, c, v, 0, 0, false);
            if (blurLogs++ < 2) ElasticCard.log("widgets do bloqueio: vidro das notificações ligado");
            return true;
        } catch (Throwable t) {
            try {
                MiBlend.container(v, Math.round(40 * c.getResources().getDisplayMetrics().density));
                return true;
            } catch (Throwable t2) {
                if (blurLogs++ < 4) ElasticCard.log("widgets do bloqueio: sem vidro do HyperOS (" + t2 + "), papel de parede desfocado");
                return false;
            }
        }
    }

    /** Elemento do vidro: as mesmas cores que os cartões de notificação usam no bloqueio. */
    static boolean elementBlend(Context c, View v) {
        try {
            android.content.res.Resources r = c.getResources();
            String pkg = "com.android.systemui";
            int c1 = r.getIdentifier("notification_element_blend_keyguard_color_1", "color", pkg);
            int c2 = r.getIdentifier("notification_element_blend_keyguard_color_2", "color", pkg);
            int m1 = r.getIdentifier("notification_element_blend_keyguard_mode_1", "integer", pkg);
            int m2 = r.getIdentifier("notification_element_blend_keyguard_mode_2", "integer", pkg);
            if (c1 == 0 || c2 == 0 || m1 == 0 || m2 == 0) return false;
            int[] cols = {c.getColor(c1), r.getInteger(m1), c.getColor(c2), r.getInteger(m2)};
            Class nu = Class.forName("com.android.systemui.statusbar.notification.utils.NotificationUtil", false, c.getClassLoader());
            nu.getMethod("applyElementViewBlend", Context.class, View.class, boolean.class, int[].class, boolean.class).invoke(null, c, v, false, cols, false);
            return true;
        } catch (Throwable t) {
            if (blurLogs++ < 4) ElasticCard.log("widgets do bloqueio: cores do vidro: " + t);
            return false;
        }
    }

    static String devName;

    static String deviceName(Context c) {
        if (devName != null) return devName;
        String n = null;
        try { n = android.provider.Settings.Global.getString(c.getContentResolver(), android.provider.Settings.Global.DEVICE_NAME); } catch (Throwable ignored) {}
        if (n == null || n.isEmpty()) n = android.os.Build.MODEL;
        return devName = n;
    }
}
