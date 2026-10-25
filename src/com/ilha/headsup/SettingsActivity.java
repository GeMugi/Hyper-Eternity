package com.ilha.headsup;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Tela de ajustes do módulo: heads-up, empilhamento na gaveta, no bloqueio e em grupos. */
public class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    private SharedPreferences ui;
    private boolean night;
    private int tab;
    private LinearLayout content;
    private TextView[] tabItems;
    private TextView status;
    private View warn;
    private long openedAt;
    private final Runnable statusPaint = new Runnable() {
        public void run() {
            paintStatus();
        }
    };
    private int cBg, cCard, cText, cSub, cAccent, cLine;
    // vidro fosco: papel de parede desfocado atrás da janela, cartões translúcidos por cima
    private boolean glass, blurOn;
    private int cStroke, cBarBg, cTrack;
    private View rootView, topBar;
    private TextView bigTitle;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);   // o título grande é nosso
        } catch (Throwable ignored) {
        }
        prefs = getSharedPreferences(Keys.FILE, Context.MODE_PRIVATE);
        ui = getSharedPreferences("tela", Context.MODE_PRIVATE);
        Tr.setup(ui.getInt("lang", 0));
        tab = ui.getInt("aba", 0);
        // aberto pelo app miuix numa página específica (ex.: Privacidade)
        if (getIntent() != null && getIntent().hasExtra("page")) {
            tab = getIntent().getIntExtra("page", tab);
            ui.edit().putInt("nav", 1).apply();
        }
        RemoteSync.attach(this);
        openedAt = android.os.SystemClock.uptimeMillis();
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int theme = ui.getInt("tema", 0);
        if (theme == 1) night = false;
        else if (theme == 2) night = true;
        nav = ui.getInt("nav", 0);
        glass = false;   // visual do HyperMusicCover: fundo liso do miuix
        paintColors();
        cAccent = accent();
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(0);
        if (!night) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            try {
                int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                getWindow().getInsetsController().setSystemBarsAppearance(light, light);
            } catch (Throwable ignored) {
            }
        }
        View root;
        try {
            CrashLog.install(this);
            root = build();
        } catch (Throwable t) {
            // em vez de fechar, mostra o erro na tela
            CrashLog.save("montando a tela de ajustes", t);
            root = errorScreen(android.util.Log.getStackTraceString(t));
        }
        setContentView(root);
    }

    /**
     * Vidro fosco no estilo do HyperOS: a janela mostra o papel de parede e o Android desfoca o que
     * fica atrás dela (desfoque entre janelas, Android 12+). Se o desfoque estiver desligado no
     * aparelho (economia de bateria, por exemplo), o fundo fica mais opaco para continuar legível.
     */
    private boolean setupGlass() {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            android.view.Window w = getWindow();
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            w.setFormat(android.graphics.PixelFormat.TRANSLUCENT);
            w.setBackgroundBlurRadius(dp(56));
            blurOn = getWindowManager().isCrossWindowBlurEnabled();
            getWindowManager().addCrossWindowBlurEnabledListener(getMainExecutor(), new java.util.function.Consumer<Boolean>() {
                public void accept(Boolean on) {
                    blurOn = Boolean.TRUE.equals(on);
                    paintColors();
                    if (rootView != null && (tab >= 0 || nav != 4)) rootView.setBackgroundColor(cBg);
                    if (topBar != null && (tab >= 0 || nav != 4)) topBar.setBackgroundColor(cBarBg);
                }
            });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void paintColors() {
        if (glass) {
            // fundo: véu sobre o papel de parede desfocado (mais forte se não houver desfoque)
            cBg = night ? (blurOn ? 0x8C0B0C10 : 0xE60B0C10) : (blurOn ? 0x99F4F5F8 : 0xEBF4F5F8);
            cBarBg = night ? (blurOn ? 0xD90B0C10 : 0xF20B0C10) : (blurOn ? 0xE0F4F5F8 : 0xF5F4F5F8);
            cCard = night ? 0x1FFFFFFF : 0xB3FFFFFF;
            cStroke = night ? 0x1AFFFFFF : 0x80FFFFFF;
            cText = night ? 0xF2FFFFFF : 0xF0101114;
            cSub = night ? 0x99FFFFFF : 0x8C101114;
            cLine = night ? 0x14FFFFFF : 0x12000000;
            cTrack = night ? 0x26FFFFFF : 0x1A000000;
        } else {
            cBg = night ? 0xFF000000 : 0xFFF7F7F7;
            cBarBg = cBg;
            cCard = night ? 0xFF242424 : 0xFFFFFFFF;
            cStroke = 0;
            cText = night ? 0xFFF2F2F2 : 0xFF000000;
            cSub = night ? 0xFF8C8C8C : 0xFF8F8F8F;
            cLine = night ? 0x1AFFFFFF : 0x0F000000;
            cTrack = night ? 0xFF474747 : 0xFFE6E6E6;
        }
    }

    /** Cartão de vidro: preenchimento translúcido com um fio claro na borda. */
    private GradientDrawable glassCard(float radiusDp) {
        GradientDrawable g = round(cCard, radiusDp);
        if (cStroke != 0) g.setStroke(Math.max(1, dp(0.8f)), cStroke);
        return g;
    }

    /** Interruptor no estilo miuix: trilho em pílula e bolinha branca. */
    private void styleSwitch(Switch sw) {
        try {
            GradientDrawable on = round(cAccent, 15);
            on.setSize(dp(50), dp(30));
            GradientDrawable off = round(cTrack, 15);
            off.setSize(dp(50), dp(30));
            android.graphics.drawable.StateListDrawable track = new android.graphics.drawable.StateListDrawable();
            track.addState(new int[]{android.R.attr.state_checked}, on);
            track.addState(new int[]{}, off);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(0xFFFFFFFF);
            dot.setSize(dp(24), dp(24));
            android.graphics.drawable.InsetDrawable thumb = new android.graphics.drawable.InsetDrawable(dot, dp(3));
            sw.setThumbTintList(null);
            sw.setTrackTintList(null);
            sw.setThumbDrawable(thumb);
            sw.setTrackDrawable(track);
            sw.setShowText(false);
            sw.setSwitchMinWidth(dp(50));
            sw.setBackground(null);
        } catch (Throwable ignored) {
        }
    }

    /** Barra de ajuste no estilo miuix: faixa grossa arredondada, sem bolinha. */
    private void styleSeek(SeekBar bar) {
        try {
            GradientDrawable bg = round(cTrack, 13);
            GradientDrawable fg = round(cAccent, 13);
            android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(fg, Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
            android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{bg, clip});
            ld.setId(0, android.R.id.background);
            ld.setId(1, android.R.id.progress);
            bar.setProgressDrawable(ld);
            bar.setThumb(new android.graphics.drawable.ColorDrawable(0));
            bar.setSplitTrack(false);
            bar.setThumbOffset(0);
            bar.setPadding(0, 0, 0, 0);
            bar.setProgressTintList(null);
            bar.setProgressBackgroundTintList(null);
            bar.setBackground(null);
            if (Build.VERSION.SDK_INT >= 29) {
                bar.setMinHeight(dp(26));
                bar.setMaxHeight(dp(26));
            }
        } catch (Throwable ignored) {
        }
    }

    /** Tela mínima com o erro, para não fechar sem explicação. */
    private View errorScreen(String err) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cBg);
        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(48), dp(16), dp(48));
        scroll.addView(page);
        page.addView(text("Hyper Eternity", 28, cText, true));
        TextView t = text("A tela de ajustes deu erro ao abrir. Mande um print disto:\n\n" + err, 12.5f, cText, false);
        t.setTextIsSelectable(true);
        t.setPadding(0, dp(12), 0, 0);
        page.addView(t);
        return scroll;
    }

    /** Cartão com o erro guardado da última vez que o app fechou sozinho. */
    private void crashCard(LinearLayout page) {
        final String err = CrashLog.read(this);
        if (err == null) return;
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(night ? 0xFF3A1E1E : 0xFFFBE9E7, 16));
        card.setPadding(dp(14), dp(12), dp(14), dp(6));
        card.addView(text("O app fechou com um erro da última vez", 15, cText, true));
        TextView t = text(err.length() > 1500 ? err.substring(0, 1500) + "…" : err, 11.5f, cSub, false);
        t.setTextIsSelectable(true);
        t.setPadding(0, dp(6), 0, dp(4));
        card.addView(t);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(4), 0, dp(6));
        r.addView(button("Copiar", true, new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("erro", err));
                    Toast.makeText(SettingsActivity.this, Tr.t("Erro copiado"), Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        }), weight());
        r.addView(button("Apagar", false, new View.OnClickListener() {
            public void onClick(View v) {
                CrashLog.clear(SettingsActivity.this);
                card.setVisibility(View.GONE);
            }
        }), weight());
        card.addView(r);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        page.addView(card, lp);
    }

    @Override
    protected void onResume() {
        super.onResume();
        RemoteSync.watch(statusPaint);
        paintStatus();
        // o LSPosed entrega o serviço logo depois que o app abre; se não vier, avisa
        handler.postDelayed(statusPaint, 3000L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        RemoteSync.unwatch(statusPaint);
        handler.removeCallbacks(statusPaint);
    }

    /** Linha de estado da ligação com o LSPosed e, se ela não vier, o aviso vermelho. */
    private void paintStatus() {
        if (status == null) return;
        boolean ok = RemoteSync.synced();
        boolean waited = android.os.SystemClock.uptimeMillis() - openedAt > 2500L;
        if (ok) {
            status.setText("● Ligado ao LSPosed · os ajustes valem na hora");
            status.setTextColor(cAccent);
        } else if (RemoteSync.connected()) {
            status.setText("● Ligado ao LSPosed · sincronizando…");
            status.setTextColor(cSub);
        } else {
            status.setText(waited ? "● Sem ligação com o LSPosed" : "● Procurando o LSPosed…");
            status.setTextColor(waited ? 0xFFC2453B : cSub);
        }
        if (warn != null) warn.setVisibility(View.GONE);
        if (stTitle != null) paintStatusCard(ok || RemoteSync.connected(), waited);
    }

    private int accent() {
        if (true) return night ? 0xFF277AF7 : 0xFF3482FF;   // azul do miuix
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                return getColor(night ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
            }
        } catch (Throwable ignored) {
        }
        return night ? 0xFF8AB4FF : 0xFF2F5FD0;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------------ layout

    private View build() {
        final android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(cBg);
        rootView = root;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(28), dp(16), dp(32));
        scroll.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1, -1));

        // barra pequena do topo (como no miuix): aparece quando o título grande sai da tela
        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        bar.setBackgroundColor(cBarBg);
        bar.setAlpha(0f);
        final TextView barTitle = text("Hyper Eternity", 17, cText, true);
        barTitle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // a barra fica invisível até rolar a página: invisível, ela não volta de página
                if (tab >= 0 && bar.getAlpha() > 0.5f) showPage(-1);
            }
        });
        barTitle.setGravity(Gravity.CENTER);
        bar.addView(barTitle, new LinearLayout.LayoutParams(-1, dp(52)));
        final View barLine = new View(this);
        barLine.setBackgroundColor(cLine);
        bar.addView(barLine, new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.6f))));
        root.addView(bar, new android.widget.FrameLayout.LayoutParams(-1, -2));
        topBar = bar;
        navBar = navBar();
        miBlur(bar, 40);
        miBlur(navBar, 40);
        android.widget.FrameLayout.LayoutParams nlp = new android.widget.FrameLayout.LayoutParams(navOn() ? -2 : -1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.addView(navBar, nlp);

        // a tela vai de ponta a ponta: o conteúdo desvia da barra de status, do recorte e dos gestos
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                android.graphics.Insets s = in.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                page.setPadding(dp(16) + s.left, dp(40) + s.top, dp(16) + s.right, dp(navOn() ? 112 : 96) + s.bottom);
                if (navBar != null) {
                    android.widget.FrameLayout.LayoutParams nl = (android.widget.FrameLayout.LayoutParams) navBar.getLayoutParams();
                    nl.bottomMargin = (navOn() ? dp(16) : 0) + (navOn() ? s.bottom : 0);
                    navBar.setPadding(navBar.getPaddingLeft(), navBar.getPaddingTop(), navBar.getPaddingRight(), dp(6) + (navOn() ? 0 : s.bottom));
                    navBar.setLayoutParams(nl);
                }
                bar.setPadding(s.left, s.top, s.right, 0);
                return android.view.WindowInsets.CONSUMED;
            }
        });
        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            public void onScrollChange(View v, int x, int y, int ox, int oy) {
                float a = (y - dp(28)) / (float) dp(28);
                a = a < 0f ? 0f : (a > 1f ? 1f : a);
                bar.setAlpha(a);
                if (bigTitle != null) bigTitle.setAlpha(1f - a);
            }
        });

        back = text("‹  Hyper Eternity", 16, cAccent, false);
        back.setPadding(dp(4), dp(8), dp(4), dp(8));
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onBackPressed();
            }
        });
        android.widget.FrameLayout topRow = new android.widget.FrameLayout(this);
        topRow.addView(back, new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        final NavIcon restart = new NavIcon(this, 5);
        restart.color = cText;
        restart.setPadding(dp(8), dp(8), dp(8), dp(8));
        TypedValue rtv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, rtv, true)) restart.setBackgroundResource(rtv.resourceId);
        restart.setContentDescription("Reiniciar");
        restart.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                restartMenu(v);
            }
        });
        topRow.addView(restart, new android.widget.FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END | Gravity.CENTER_VERTICAL));
        topRow.setMinimumHeight(dp(44));
        page.addView(topRow, new LinearLayout.LayoutParams(-1, -2));
        TextView title = text("Hyper Eternity", 36, cText, false);
        title.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        title.setPadding(dp(4), 0, 0, 0);
        bigTitle = title;
        page.addView(title);
        TextView sub = text("", 1, cSub, false);
        sub.setVisibility(View.GONE);
        sub.setPadding(dp(4), dp(6), dp(4), dp(4));
        page.addView(sub);
        status = text("", 12.5f, cSub, true);
        status.setVisibility(View.GONE);
        status.setPadding(dp(4), dp(2), dp(4), dp(4));
        page.addView(status);

        TextView w = text("O LSPosed ainda não se ligou a este app. Ative o módulo no LSPosed, reinicie a Interface do Sistema e abra esta tela de novo.", 14, 0xFFFFFFFF, false);
        w.setPadding(dp(14), dp(12), dp(14), dp(12));
        w.setBackground(round(0xFFC2453B, 16));
        w.setVisibility(View.GONE);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
        wlp.topMargin = dp(8);
        page.addView(w, wlp);
        warn = w;
        crashCard(page);

        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        // o título, a descrição, o estado do LSPosed e os avisos ficam só na página inicial
        int hi = page.indexOfChild(sub);
        for (int i = page.getChildCount() - 1; i >= hi; i--) {
            View c = page.getChildAt(i);
            page.removeViewAt(i);
            header.addView(c, 0);
        }
        page.addView(header);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        page.addView(content, new LinearLayout.LayoutParams(-1, -2));
        pageTitle = barTitle;
        scrollView = scroll;
        showPage(tab);
        return root;
    }

    static final String[] PAGES = {"Heads-up", "Notificações", "Gaveta e pilhas", "Tela de bloqueio", "Central estilo iOS", "Barra de status", "Música", "Privacidade"};
    static final String[] PAGE_HELP = {
            "Estilo, velocidade e conteúdo",
            "Conversas, linhas de texto e visual dos cartões",
            "Empilhamento e grupos em pilha",
            "Lista, relógio e rolagem",
            "Cortina, relógio do bloqueio e fundo",
            "Pílulas do relógio e cores",
            "Player da gaveta e brilho da capa",
            "Esconder apps e conversas"};
    static final String[] NAVS = {"Início", "Recursos", "Extensões", "Ajustes", "Sobre"};
    private LinearLayout header, navBar;
    private TextView back, pageTitle;
    private ScrollView scrollView;
    private int nav;
    private NavIcon[] navIcons;
    private TextView[] navLabels;

    private boolean navOn() {
        return ui.getBoolean("navbar", true);
    }

    /** Barra de navegação de baixo, flutuante como a do HyperMusicCover (ou presa embaixo). */
    private LinearLayout navBar() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        boolean fl = navOn();
        GradientDrawable bg = round(night ? 0xF21C1C1C : 0xF7FFFFFF, fl ? 40 : 0);
        if (!fl) bg.setCornerRadius(0);
        bg.setStroke(Math.max(1, dp(0.6f)), night ? 0x14FFFFFF : 0x0F000000);
        b.setBackground(bg);
        b.setElevation(dp(fl ? 10 : 2));
        b.setPadding(dp(fl ? 14 : 4), dp(10), dp(fl ? 14 : 4), dp(10));
        navIcons = new NavIcon[NAVS.length];
        navLabels = new TextView[NAVS.length];
        for (int i = 0; i < NAVS.length; i++) {
            final int idx = i;
            LinearLayout it = new LinearLayout(this);
            it.setOrientation(LinearLayout.VERTICAL);
            it.setGravity(Gravity.CENTER);
            it.setPadding(dp(fl ? 13 : 4), dp(8), dp(fl ? 13 : 4), dp(8));
            it.setContentDescription(NAVS[i]);
            NavIcon ic = new NavIcon(this, i);
            it.addView(ic, new LinearLayout.LayoutParams(dp(32), dp(32)));
            TextView t = text(NAVS[i], 11, cSub, false);
            t.setVisibility(View.GONE);
            it.addView(t);
            it.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    showNav(idx);
                }
            });
            navIcons[i] = ic;
            navLabels[i] = t;
            b.addView(it, fl ? new LinearLayout.LayoutParams(-2, -2) : new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return b;
    }

    private void paintNav() {
        if (navIcons == null) return;
        for (int i = 0; i < navIcons.length; i++) {
            int c = i == nav ? cText : (night ? 0xFF8A8A8A : 0xFF9A9A9A);
            navIcons[i].color = c;
            navIcons[i].filled = i == nav;
            navIcons[i].invalidate();
            navLabels[i].setTextColor(c);
        }
    }

    /** Ícones desenhados (casa, grade, peça, engrenagem, informação), sem depender de imagens. */
    static final class NavIcon extends View {
        final int kind;
        int color;
        boolean filled, ok;
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Path path = new android.graphics.Path();

        NavIcon(Context c, int kind) {
            super(c);
            this.kind = kind;
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }

        protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight(), u = w / 24f;
            p.setColor(color);
            p.setStrokeWidth(1.8f * u);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            path.reset();
            if (kind == 0) {            // casa
                path.moveTo(4 * u, 10.5f * u);
                path.lineTo(12 * u, 4 * u);
                path.lineTo(20 * u, 10.5f * u);
                path.lineTo(20 * u, 19 * u);
                path.quadTo(20 * u, 20.5f * u, 18.5f * u, 20.5f * u);
                path.lineTo(15 * u, 20.5f * u);
                path.lineTo(15 * u, 15 * u);
                path.lineTo(9 * u, 15 * u);
                path.lineTo(9 * u, 20.5f * u);
                path.lineTo(5.5f * u, 20.5f * u);
                path.quadTo(4 * u, 20.5f * u, 4 * u, 19 * u);
                path.close();
                if (filled) {
                    p.setStyle(android.graphics.Paint.Style.FILL_AND_STROKE);
                }
                c.drawPath(path, p);
            } else if (kind == 1) {     // deslizantes (recursos)
                c.drawLine(4 * u, 8 * u, 20 * u, 8 * u, p);
                c.drawLine(4 * u, 16 * u, 20 * u, 16 * u, p);
                if (filled) p.setStyle(android.graphics.Paint.Style.FILL_AND_STROKE);
                c.drawCircle(15 * u, 8 * u, 2.6f * u, p);
                c.drawCircle(9 * u, 16 * u, 2.6f * u, p);
            } else if (kind == 2) {     // grade (extensões)
                if (filled) p.setStyle(android.graphics.Paint.Style.FILL_AND_STROKE);
                float r = 2.2f * u;
                c.drawRoundRect(4 * u, 4 * u, 10.5f * u, 10.5f * u, r, r, p);
                c.drawRoundRect(13.5f * u, 4 * u, 20 * u, 10.5f * u, r, r, p);
                c.drawRoundRect(4 * u, 13.5f * u, 10.5f * u, 20 * u, r, r, p);
                c.drawRoundRect(13.5f * u, 13.5f * u, 20 * u, 20 * u, r, r, p);
            } else if (kind == 3) {     // hexágono com círculo (ajustes)
                for (int i = 0; i < 6; i++) {
                    double a = Math.PI / 3 * i - Math.PI / 2;
                    float x = 12 * u + (float) Math.cos(a) * 8.5f * u, y = 12 * u + (float) Math.sin(a) * 8.5f * u;
                    if (i == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                path.close();
                c.drawPath(path, p);
                if (filled) p.setStyle(android.graphics.Paint.Style.FILL_AND_STROKE);
                c.drawCircle(12 * u, 12 * u, 3 * u, p);
            } else if (kind == 4) {     // informação
                c.drawCircle(12 * u, 12 * u, 8.5f * u, p);
                c.drawLine(12 * u, 11 * u, 12 * u, 16.5f * u, p);
                p.setStyle(android.graphics.Paint.Style.FILL);
                c.drawCircle(12 * u, 7.8f * u, 1.2f * u, p);
            } else if (kind == 5) {     // menu: três pontos
                float pl = getPaddingLeft(), pt = getPaddingTop();
                float s = (w - pl - getPaddingRight()) / 24f;
                p.setStyle(android.graphics.Paint.Style.FILL);
                for (int k = 0; k < 3; k++) c.drawCircle(pl + 12 * s, pt + (5 + 7 * k) * s, 2.1f * s, p);
                p.setStyle(android.graphics.Paint.Style.STROKE);
            } else {                    // estado: anel grande com ! ou ✓
                p.setStrokeWidth(2.4f * u);
                c.drawCircle(12 * u, 12 * u, 10 * u, p);
                if (ok) {
                    path.moveTo(7.5f * u, 12.2f * u);
                    path.lineTo(10.8f * u, 15.4f * u);
                    path.lineTo(16.8f * u, 8.8f * u);
                    c.drawPath(path, p);
                } else {
                    c.drawLine(12 * u, 6.5f * u, 12 * u, 13 * u, p);
                    p.setStyle(android.graphics.Paint.Style.FILL);
                    c.drawCircle(12 * u, 17 * u, 1.4f * u, p);
                }
            }
        }
    }

    /** Troca de aba na barra de baixo. */
    private void showNav(int n) {
        if (n < 0 || n >= NAVS.length) n = 0;
        nav = n;
        ui.edit().putInt("nav", n).apply();
        tab = -1;
        showPage(-1);
    }

    /** Página da aba atual (-1) ou, em Recursos, a página de um recurso. */
    private void showPage(int p) {
        if (p < -1 || p >= PAGES.length) p = -1;
        if (p >= 0) nav = 1;
        tab = p;
        ui.edit().putInt("aba", p).apply();
        boolean root = p < 0;
        String name = root ? NAVS[nav] : PAGES[p];
        bigTitle.setText(name);
        if (pageTitle != null) pageTitle.setText(name);
        back.setText("‹  Recursos");
        privSub = 0;
        back.setVisibility(root ? View.GONE : View.VISIBLE);
        header.setVisibility(root && nav == 0 ? View.VISIBLE : View.GONE);
        content.setLayoutTransition(null);
        content.removeAllViews();
        if (rootView != null) rootView.setBackgroundColor(cBg);
        if (navBg0 != null && navBar != null) navBar.setBackground(navBg0);
        if (barBg0 != null && topBar != null) topBar.setBackground(barBg0);
        sobreBlur = null;
        if (rootView != null) rootView.setBackgroundColor(cBg);
        if (nav == 0 || !root) safeNotice(content);
        if (!root) {
            if (p == 0) buildHeadsUp(content);
            else if (p == 1) buildConversas(content);
            else if (p == 2) buildGaveta(content);
            else if (p == 3) buildBloqueio(content);
            else if (p == 4) buildCentral(content);
            else if (p == 5) buildBarra(content);
            else if (p == 6) buildMusic(content);
            else buildPrivacidade(content);
        } else if (nav == 0) buildInicio(content);
        else if (nav == 1) buildRecursos(content);
        else if (nav == 2) buildExtensoes(content);
        else if (nav == 3) buildAjustesApp(content);
        else buildSobre(content);
        paintNav();
        if (scrollView != null) scrollView.scrollTo(0, 0);
    }

    // ------------------------------------------------------------------ Início

    private TextView stTitle, stSub;
    private LinearLayout stCard;
    private NavIcon stIcon;

    private void buildInicio(LinearLayout page) {
        // cartão grande do estado, com o ícone grande saindo pelo canto (como no HyperMusicCover)
        android.widget.FrameLayout card = new android.widget.FrameLayout(this);
        card.setClipToOutline(true);
        stCard = new LinearLayout(this);
        stCard.setOrientation(LinearLayout.VERTICAL);
        stCard.setPadding(dp(18), dp(20), dp(18), dp(40));
        stTitle = text("", 24, cText, true);
        stSub = text("", 15, cText, false);
        // 7 toques na versão: liga/desliga os recursos ainda em teste (só para quem sabe)
        stSub.setOnClickListener(new View.OnClickListener() {
            int n;
            long last;
            public void onClick(View v) {
                long now = android.os.SystemClock.uptimeMillis();
                n = now - last < 600 ? n + 1 : 1;
                last = now;
                if (n < 7) return;
                n = 0;
                boolean dev = !ui.getBoolean("dev", false);
                ui.edit().putBoolean("dev", dev).apply();
                android.widget.Toast.makeText(SettingsActivity.this, dev ? "Recursos em teste visíveis" : "Recursos em teste ocultos", android.widget.Toast.LENGTH_SHORT).show();
                recreate();
            }
        });
        stSub.setPadding(0, dp(4), dp(60), 0);
        stCard.addView(stTitle);
        stCard.addView(stSub);
        card.addView(stCard, new android.widget.FrameLayout.LayoutParams(-1, -2));
        stIcon = new NavIcon(this, 6);
        android.widget.FrameLayout.LayoutParams ilp = new android.widget.FrameLayout.LayoutParams(dp(120), dp(120), Gravity.END | Gravity.BOTTOM);
        ilp.rightMargin = -dp(22);
        ilp.bottomMargin = -dp(26);
        card.addView(stIcon, ilp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(16);
        page.addView(card, clp);
        statusFrame = card;
        paintStatusCard(RemoteSync.connected(), android.os.SystemClock.uptimeMillis() - openedAt > 2500L);

        LinearLayout dev = section(page, null);
        info(dev, "Nome do aparelho", deviceName());
        info(dev, "Modelo", Build.MODEL);
        info(dev, "HyperOS", prop("ro.mi.os.version.incremental", Build.VERSION.INCREMENTAL));
        info(dev, "Android", "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");

        LinearLayout on = section(page, null);
        int n = 0;
        for (java.lang.reflect.Field fld : Keys.class.getFields()) {
            try {
                if (fld.getType() != String.class || fld.getName().startsWith("SAFE")) continue;
                String k = (String) fld.get(null);
                if (!Keys.isOption(k)) continue;
                boolean def = Keys.class.getField("D_" + fld.getName()).getBoolean(null);
                if (prefs.getBoolean(k, def)) n++;
            } catch (Throwable ignored) {
            }
        }
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(18), dp(16), dp(14), dp(16));
        NavIcon ic = new NavIcon(this, 1);
        ic.color = cAccent;
        r.addView(ic, new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(16), 0, 0, 0);
        tx.addView(text("Recursos", 17, cText, false));
        tx.addView(text(n == 0 ? "Nenhum ligado — tudo vem desligado" : n + (n == 1 ? " recurso ligado" : " recursos ligados"), 14, cSub, false));
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(text("›", 24, cSub, false));
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) r.setBackgroundResource(tv.resourceId);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showNav(1);
            }
        });
        on.addView(r);
    }

    private View statusFrame;

    private void paintStatusCard(boolean active, boolean waited) {
        if (stTitle == null) return;
        int bg, ic;
        if (active) {
            bg = night ? 0xFF1B3324 : 0xFFE2F4E7;
            ic = night ? 0xFF3FBF6A : 0xFF2EA65A;
            stTitle.setText("Módulo ativo");
            stSub.setText("Versão " + Keys.VERSION + " · os ajustes valem na hora");
        } else if (!waited) {
            bg = cCard;
            ic = cSub;
            stTitle.setText("Procurando o LSPosed…");
            stSub.setText("Versão " + Keys.VERSION);
        } else {
            bg = night ? 0xFF3A1F1F : 0xFFFBE6E4;
            ic = night ? 0xFFE0434F : 0xFFD8323F;
            stTitle.setText("Módulo não ativo");
            stSub.setText("Ative no LSPosed e reinicie a Interface do Sistema");
        }
        if (statusFrame != null) statusFrame.setBackground(round(bg, 20));
        stIcon.color = ic;
        stIcon.ok = active;
        stIcon.invalidate();
    }

    private void info(LinearLayout card, String label, String value) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(18), dp(12), dp(18), dp(12));
        b.addView(text(label, 17, cText, false));
        TextView v = text(value == null || value.length() == 0 ? "—" : value, 14.5f, cSub, false);
        v.setTextIsSelectable(true);
        b.addView(v);
        card.addView(b);
    }

    private String deviceName() {
        try {
            String n = Settings.Global.getString(getContentResolver(), "device_name");
            if (n != null && n.length() > 0) return n;
        } catch (Throwable ignored) {
        }
        return prop("ro.product.marketname", Build.MANUFACTURER + " " + Build.MODEL);
    }

    private static String prop(String k, String def) {
        try {
            Class sp = Class.forName("android.os.SystemProperties");
            String v = (String) sp.getMethod("get", String.class).invoke(null, k);
            if (v != null && v.length() > 0) return v;
        } catch (Throwable ignored) {
        }
        return def;
    }

    /** Menu do botão de reiniciar: cada app em que o módulo age, e o celular. */
    private void restartMenu(View anchor) {
        final String[][] apps = {
                {"Interface do Sistema", "com.android.systemui"},
                {"Plugin da Interface do Sistema", "miui.systemui.plugin"},
                {"Papel de parede", "com.miui.miwallpaper"},
                {"Tela sempre ativa (AOD)", "com.miui.aod"},
                {"Apple Music", "com.apple.android.music"},
                {"Amap", "com.autonavi.minimap"},
                {"Reiniciar o celular", ""}};
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, anchor, Gravity.END);
        for (int i = 0; i < apps.length; i++) pm.getMenu().add(0, i, i, Tr.t(apps[i][0]));
        pm.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem item) {
                String pkg = apps[item.getItemId()][1];
                String cmd = pkg.length() == 0 ? "svc power reboot || reboot"
                        : "killall " + pkg + " 2>/dev/null; am force-stop " + pkg + " 2>/dev/null; true";
                if ("com.android.systemui".equals(pkg)) cmd = "killall com.android.systemui";
                rootRun(cmd, apps[item.getItemId()][0]);
                return true;
            }
        });
        pm.show();
    }

    private void rootRun(final String cmd, final String what) {
        new Thread(new Runnable() {
            public void run() {
                boolean ok;
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                    ok = p.waitFor() == 0;
                } catch (Throwable t) {
                    ok = false;
                }
                final boolean done = ok;
                handler.post(new Runnable() {
                    public void run() {
                        Toast.makeText(SettingsActivity.this, done ? what + ": reiniciado" : "Sem acesso root para reiniciar " + what, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private void para(LinearLayout card, String s) {
        TextView t = text(s, 14, cText, false);
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        card.addView(t);
    }

    // ------------------------------------------------------------------ Recursos

    private void buildRecursos(LinearLayout page) {
        LinearLayout c = section(page, "Recursos");
        for (int i = 0; i < PAGES.length; i++) entry(c, PAGES[i], PAGE_HELP[i], i);
    }

    /** Linha com nome, resumo e a seta, como as entradas do HyperMusicCover. */
    private void entry(LinearLayout card, String name, String help, final int p) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(14), dp(14), dp(14));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(name, 16.5f, cText, false));
        TextView h = text(help, 13.5f, cSub, false);
        h.setPadding(0, dp(2), dp(8), 0);
        texts.addView(h);
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(text("›", 24, cSub, false));
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            r.setBackgroundResource(tv.resourceId);
        }
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showPage(p);
            }
        });
        card.addView(r);
    }

    // ------------------------------------------------------------------ Extensões

    static final String MUSIC_PKG = "com.ilha.headsup.music";

    private boolean musicInstalled() {
        try {
            getPackageManager().getPackageInfo(MUSIC_PKG, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void buildExtensoes(LinearLayout page) {
        if (!musicInstalled()) {
            LinearLayout c = section(page, "HyperMusicCover-Enhanced");
            para(c, "Capa do álbum e letras sincronizadas no bloqueio (HyperMusicCover-Enhanced, adaptado ao HyperOS 3). É um APK separado: instale a extensão \"HyperMusicCover-Enhanced\", ative no LSPosed e reinicie.");
            return;
        }
        LinearLayout mu = section(page, "Capa e letras no bloqueio");
        TextView about = text("Player do bloqueio com capa em destaque e letras sincronizadas, do HyperMusicCover-Enhanced.", 14, cText, false);
        about.setPadding(dp(16), dp(12), dp(16), dp(4));
        mu.addView(about);
        LinearLayout open = row();
        open.addView(button("Abrir ajustes da música", true, new View.OnClickListener() {
            public void onClick(View v) {
                openMusicSettings();
            }
        }), weight());
        mu.addView(open);
        mu.addView(note("A tela da música é a original, em inglês. Desinstale o HyperMusicCover separado, se tiver."));

        LinearLayout sy = section(page, "Sincronia das letras");
        slider(sy, "Adiantar a letra", "Quanto a letra anda à frente da música. Atrasada, aumente; adiantada, diminua.", Keys.LYRIC_SHIFT, Keys.D_LYRIC_SHIFT, -20, 30, "ds");
        sy.addView(note("Versões de clipe podem ter cortes diferentes do álbum, e a letra pode não bater em algum trecho."));

        LinearLayout il = section(page, "Ilha no bloqueio");
        TextView isl = text("A ilha do bloqueio é a do HyperMusicCover. Ligue em Abrir ajustes da música → \"Lock screen super island\". Para o vidro fosco, ligue \"Blur background\" na mesma tela.", 14, cText, false);
        isl.setPadding(dp(16), dp(12), dp(16), dp(12));
        il.addView(isl);

    }

    // ------------------------------------------------------------------ Ajustes do app

    private void buildAjustesApp(LinearLayout page) {
        LinearLayout ap = section(page, "Aparência");
        choice(ap, "Tema do app", new String[]{"Sistema", "Claro", "Escuro"}, "tema", 0, true);
        uiToggle(ap, "Barra de navegação flutuante", "Desligada, a barra fica presa embaixo, de ponta a ponta.", "navbar", true);
        LinearLayout lg = section(page, "Idioma");
        choice(lg, "Idioma do app", new String[]{"Automático", "Português", "English", "Español"}, "lang", 0, true);
        para(lg, "Os ajustes da música (HyperMusicCover) seguem em inglês.");
        LinearLayout lo = section(page, "Registros");
        para(lo, "Os registros ficam no LSPosed (Registros → Módulos). Copie o resumo abaixo para mandar junto.");
        LinearLayout r = row();
        r.addView(button("Copiar resumo dos ajustes", false, new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("ajustes", "Hyper Eternity " + Keys.VERSION + " · " + Build.DISPLAY + "\n" + exportJson()));
                    Toast.makeText(SettingsActivity.this, Tr.t("Resumo copiado"), Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        }), weight());
        lo.addView(r);
        LinearLayout ie = section(page, "Importar e exportar");
        LinearLayout r2 = row();
        r2.addView(button("Exportar", true, new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, "heads-up-ilha-ajustes.json");
                try {
                    startActivityForResult(i, 41);
                } catch (Throwable t) {
                    Toast.makeText(SettingsActivity.this, Tr.t("Sem app de arquivos para salvar"), Toast.LENGTH_SHORT).show();
                }
            }
        }), weight());
        r2.addView(button("Importar", false, new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, 42);
                } catch (Throwable t) {
                    Toast.makeText(SettingsActivity.this, Tr.t("Sem app de arquivos para abrir"), Toast.LENGTH_SHORT).show();
                }
            }
        }), weight());
        ie.addView(r2);
        ie.addView(note("Salva ou carrega todos os ajustes num arquivo. A música tem o próprio importar/exportar."));
        buildSistema(page);
    }

    /** Escolha entre opções (botões lado a lado), guardada nos ajustes da tela. */
    private void choice(LinearLayout card, String label, final String[] names, final String key, int def, final boolean recreate) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(14));
        box.addView(text(label, 16, cText, false));
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        seg.setBackground(round(cTrack, 14));
        int cur = ui.getInt(key, def);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = text(names[i], 14, i == cur ? 0xFFFFFFFF : cText, i == cur);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(6), dp(8), dp(6), dp(8));
            if (i == cur) t.setBackground(round(cAccent, 12));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    ui.edit().putInt(key, idx).apply();
                    if (recreate) recreate();
                    else showPage(tab);
                }
            });
            seg.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        box.addView(seg, lp);
        card.addView(box);
    }

    /** Liga/desliga da própria tela (não vai para a Interface do Sistema). */
    private void uiToggle(LinearLayout card, String label, String help, final String key, boolean def) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(12), dp(12), dp(12));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(label, 16, cText, false));
        TextView h = text(help, 13, cSub, false);
        h.setPadding(0, dp(2), dp(8), 0);
        texts.addView(h);
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        final Switch sw = new Switch(this);
        styleSwitch(sw);
        sw.setChecked(ui.getBoolean(key, def));
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sw.toggle();
            }
        });
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                ui.edit().putBoolean(key, on).apply();
                recreate();
            }
        });
        r.addView(sw);
        card.addView(r);
    }

    private String exportJson() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            for (java.util.Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
                if (e.getKey().startsWith("safe_")) continue;
                o.put(e.getKey(), e.getValue());
            }
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("modulo", "heads-up-ilha");
            root.put("versao", Keys.VERSION);
            root.put("ajustes", o);
            return root.toString(2);
        } catch (Throwable t) {
            return "{}";
        }
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        super.onActivityResult(code, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            if (code == 41) {
                java.io.OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt");
                out.write(exportJson().getBytes("UTF-8"));
                out.close();
                Toast.makeText(this, Tr.t("Ajustes exportados"), Toast.LENGTH_SHORT).show();
            } else if (code == 42) {
                java.io.InputStream in = getContentResolver().openInputStream(data.getData());
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                org.json.JSONObject root = new org.json.JSONObject(bo.toString("UTF-8"));
                org.json.JSONObject o = root.has("ajustes") ? root.getJSONObject("ajustes") : root;
                SharedPreferences.Editor e = prefs.edit().clear();
                java.util.Iterator<String> it = o.keys();
                int cnt = 0;
                while (it.hasNext()) {
                    String k = it.next();
                    Object v = o.get(k);
                    if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
                    else if (v instanceof Integer) e.putInt(k, (Integer) v);
                    else if (v instanceof Long) {
                        long l = (Long) v;
                        if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) e.putInt(k, (int) l);
                        else e.putLong(k, l);
                    } else if (v instanceof Double) e.putFloat(k, ((Double) v).floatValue());
                    else if (v instanceof String) e.putString(k, (String) v);
                    else continue;
                    cnt++;
                }
                e.apply();
                Toast.makeText(this, cnt + " ajustes importados", Toast.LENGTH_SHORT).show();
                showPage(tab);
            }
        } catch (Throwable t) {
            Toast.makeText(this, Tr.t("Não deu certo: ") + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ Sobre

    private void buildSobre(LinearLayout page) {
        // cabeçalho com a imagem escolhida ao fundo (de ponta a ponta), que some para baixo; um
        // escurecido atrás do nome deixa a leitura fácil
        // a imagem cobre a tela toda; cartões, barra de navegação e barra do topo mostram a
        // mesma imagem desfocada por trás deles (vidro fosco)
        final android.graphics.Bitmap bgb = asset("eternity_bg.jpg");
        android.widget.FrameLayout hf = new android.widget.FrameLayout(this);
        sobreBg = null;
        sobreBlur = null;
        if (bgb != null && rootView != null) {
            sobreBg = new CropDrawable(bgb);
            android.graphics.Bitmap sm = android.graphics.Bitmap.createScaledBitmap(bgb, Math.max(1, bgb.getWidth() / 16), Math.max(1, bgb.getHeight() / 16), true).copy(android.graphics.Bitmap.Config.ARGB_8888, true);
            for (int k = 0; k < 4; k++) Frost.boxBlur(sm, 2);
            sobreBlur = new CropDrawable(sm);
            // cabeçalho (até abaixo do título) com a imagem desfocada, que se funde na nítida
            int sbh = 0;
            int rid = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (rid > 0) sbh = getResources().getDimensionPixelSize(rid);
            rootView.setBackground(new HeadBlur(sobreBg, sobreBlur, sbh + dp(150), dp(70)));
            if (navBg0 == null) navBg0 = navBar.getBackground();
            if (barBg0 == null) barBg0 = topBar.getBackground();
            navBar.setBackground(new BgGlass(navBar, navOn() ? dp(40) : 0));
            topBar.setBackground(new BgGlass(topBar, 0));
        }
        LinearLayout hb = new LinearLayout(this);
        hb.setOrientation(LinearLayout.VERTICAL);
        hf.addView(hb, new android.widget.FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams hfl = new LinearLayout.LayoutParams(-1, -2);
        hfl.topMargin = dp(4);
        page.addView(hf, hfl);
        bleed(hf);
        sobreBg = bgb != null ? new CropDrawable(bgb) : null;

        // foto do perfil (animada), nome e usuário
        android.widget.ImageView av = new android.widget.ImageView(this);
        av.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        av.setClipToOutline(true);
        try {
            byte[] gif = assetBytes("eternity_avatar.webp");
            android.graphics.drawable.Drawable d = android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(gif)));
            av.setImageDrawable(d);
            if (d instanceof android.graphics.drawable.AnimatedImageDrawable) ((android.graphics.drawable.AnimatedImageDrawable) d).start();
        } catch (Throwable t) {
        }
        av.setBackground(null);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(128), dp(128));
        alp.gravity = Gravity.CENTER_HORIZONTAL;
        alp.topMargin = dp(28);
        hb.addView(av, alp);
        av.setOutlineProvider(new android.view.ViewOutlineProvider() {
            public void getOutline(View v, android.graphics.Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        TextView nm = text("Fabulous Lightning McQ", 24, sobreBg != null ? 0xFFFFFFFF : cText, true);
        if (sobreBg != null) nm.setShadowLayer(dp(10), 0, dp(1), 0xCC000000);
        nm.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = dp(10);
        hb.addView(nm, nlp);
        TextView us = text("@Gmugi", 15, sobreBg != null ? 0xDDFFFFFF : cSub, false);
        if (sobreBg != null) us.setShadowLayer(dp(10), 0, dp(1), 0xCC000000);
        us.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-1, -2);
        ulp.bottomMargin = dp(28);
        hb.addView(us, ulp);

        // canal do Telegram
        LinearLayout ch = section(page, "Telegram");
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(12), dp(16), dp(12));
        android.widget.ImageView cp = new android.widget.ImageView(this);
        cp.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        cp.setImageBitmap(asset("eternity_channel.jpg"));
        cp.setClipToOutline(true);
        cp.setOutlineProvider(new android.view.ViewOutlineProvider() {
            public void getOutline(View v, android.graphics.Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        r.addView(cp, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(14), 0, 0, 0);
        tx.addView(text("Gmugi 愛", 16, cText, false));
        tx.addView(text("t.me/GmugiUwu", 13, cAccent, false));
        r.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) r.setBackgroundResource(tv.resourceId);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/GmugiUwu")));
                } catch (Throwable t) {
                    Toast.makeText(SettingsActivity.this, Tr.t("Nenhum navegador para abrir o link"), Toast.LENGTH_SHORT).show();
                }
            }
        });
        ch.addView(r);

        // repositório no GitHub
        LinearLayout gh = section(page, "GitHub");
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.HORIZONTAL);
        g.setGravity(Gravity.CENTER_VERTICAL);
        g.setPadding(dp(16), dp(12), dp(16), dp(12));
        android.widget.ImageView gi = new android.widget.ImageView(this);
        gi.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        try {
            gi.setImageDrawable(getPackageManager().getApplicationIcon(getPackageName()));
        } catch (Throwable ignored) {
        }
        gi.setClipToOutline(true);
        gi.setOutlineProvider(new android.view.ViewOutlineProvider() {
            public void getOutline(View v, android.graphics.Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        g.addView(gi, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout gt = new LinearLayout(this);
        gt.setOrientation(LinearLayout.VERTICAL);
        gt.setPadding(dp(14), 0, 0, 0);
        gt.addView(text("GeMugi/Hyper-Eternity", 16, cText, false));
        gt.addView(text("github.com/GeMugi/Hyper-Eternity", 13, cAccent, false));
        g.addView(gt, new LinearLayout.LayoutParams(0, -2, 1f));
        TypedValue gtv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, gtv, true)) g.setBackgroundResource(gtv.resourceId);
        g.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/GeMugi/Hyper-Eternity")));
                } catch (Throwable t) {
                    Toast.makeText(SettingsActivity.this, Tr.t("Nenhum navegador para abrir o link"), Toast.LENGTH_SHORT).show();
                }
            }
        });
        gh.addView(g);
        buildCreditos(page);
    }

    private CropDrawable sobreBg, sobreBlur;
    private android.graphics.drawable.Drawable navBg0, barBg0;

    /** Estica a view até as duas bordas da tela (sai da margem da página). */
    private void bleed(final View v) {
        v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View x, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                int[] loc = new int[2];
                x.getLocationOnScreen(loc);
                int sw2 = getResources().getDisplayMetrics().widthPixels;
                if (loc[0] == 0 && x.getWidth() == sw2) return;
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) x.getLayoutParams();
                lp.leftMargin -= loc[0];
                lp.rightMargin = 0;
                lp.width = sw2;
                x.post(new Runnable() {
                    public void run() {
                        v.requestLayout();
                    }
                });
            }
        });
    }

    /** Desfoque de fundo nativo do HyperOS na view (o que fica atrás dela, ao vivo). */
    private void miBlur(View v, int radiusDp) {
        try {
            View.class.getMethod("setMiBackgroundBlurMode", int.class).invoke(v, 1);
            View.class.getMethod("setMiBackgroundBlurRadius", int.class).invoke(v, dp(radiusDp));
            try {
                View.class.getMethod("setPassWindowBlurEnabled", boolean.class).invoke(v, true);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean night() {
        return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** Texto com o efeito vibrancy (clareia no escuro, escurece no claro a cor de trás). */
    private void vibrant(TextView t) {
        boolean n = night();
        t.setTextColor(MediaCut.color(n, 70));
        t.getPaint().setBlendMode(MediaCut.mode(n));
    }

    /** Imagem cobrindo a área toda, centralizada e cortando o que sobra. */
    static final class CropDrawable extends android.graphics.drawable.Drawable {
        final android.graphics.Bitmap b;
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        final android.graphics.Matrix m = new android.graphics.Matrix();
        int w, h;

        CropDrawable(android.graphics.Bitmap b) {
            this.b = b;
        }

        void place(int w, int h) {
            this.w = w;
            this.h = h;
            float sc = Math.max((float) w / b.getWidth(), (float) h / b.getHeight());
            m.setScale(sc, sc);
            m.postTranslate((w - b.getWidth() * sc) / 2f, (h - b.getHeight() * sc) / 2f);
        }

        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect r = getBounds();
            if (r.width() != w || r.height() != h) place(r.width(), r.height());
            c.drawBitmap(b, m, p);
        }

        public void setAlpha(int a) {
        }

        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }
    }

    /** Fundo da tela: imagem nítida e, no topo, a mesma desfocada sumindo para baixo. */
    static final class HeadBlur extends android.graphics.drawable.Drawable {
        final CropDrawable sharp, soft;
        final int h, fade;
        final android.graphics.Paint mp = new android.graphics.Paint();
        final android.graphics.Paint dim = new android.graphics.Paint();

        HeadBlur(CropDrawable sharp, CropDrawable soft, int h, int fade) {
            this.sharp = sharp;
            this.soft = soft;
            this.h = h;
            this.fade = fade;
            mp.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
            dim.setColor(0x33000000);
        }

        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect r = getBounds();
            sharp.setBounds(r);
            sharp.draw(c);
            int sv = c.saveLayer(0, 0, r.width(), h + fade, null);
            soft.setBounds(r);
            soft.draw(c);
            c.drawRect(0, 0, r.width(), h + fade, dim);
            mp.setShader(new android.graphics.LinearGradient(0, h, 0, h + fade, 0xFF000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, 0, r.width(), h + fade, mp);
            c.restoreToCount(sv);
        }

        public void setAlpha(int a) {
        }

        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }
    }

    /** Cartão de vidro fosco: o pedaço do fundo desfocado que fica atrás dele, com um véu. */
    final class BgGlass extends android.graphics.drawable.Drawable implements android.view.ViewTreeObserver.OnScrollChangedListener {
        final View host;
        final float rad;
        final int[] a = new int[2], b2 = new int[2];
        final android.graphics.Path path = new android.graphics.Path();
        final android.graphics.Paint veil = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        BgGlass(View host, float rad) {
            this.host = host;
            this.rad = rad;
            veil.setColor(night() ? 0x59000000 : 0x66FFFFFF);
            host.getViewTreeObserver().addOnScrollChangedListener(this);
        }

        public void onScrollChanged() {
            invalidateSelf();
        }

        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect r = getBounds();
            path.reset();
            path.addRoundRect(r.left, r.top, r.right, r.bottom, rad, rad, android.graphics.Path.Direction.CW);
            int sv = c.save();
            c.clipPath(path);
            if (sobreBlur != null && rootView != null) {
                host.getLocationInWindow(a);
                rootView.getLocationInWindow(b2);
                c.translate(b2[0] - a[0], b2[1] - a[1]);
                sobreBlur.setBounds(0, 0, rootView.getWidth(), rootView.getHeight());
                sobreBlur.draw(c);
                c.translate(a[0] - b2[0], a[1] - b2[1]);
            }
            c.drawRect(r, veil);
            c.restoreToCount(sv);
        }

        public void setAlpha(int al) {
        }

        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private byte[] assetBytes(String name) throws java.io.IOException {
        java.io.InputStream in = getAssets().open(name);
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toByteArray();
    }

    private android.graphics.Bitmap asset(String name) {
        try {
            byte[] b = assetBytes(name);
            return android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Privacidade

    private int privSub;          // 0 página, 1 lista de apps, 2 conversas de um app
    private String privPkg;

    private java.util.LinkedHashSet<String> getLines(String key) {
        java.util.LinkedHashSet<String> h = new java.util.LinkedHashSet<>();
        for (String x : prefs.getString(key, "").split("\n")) if (x.length() > 0) h.add(x);
        return h;
    }

    private void putLines(String key, java.util.Set<String> h) {
        StringBuilder sb = new StringBuilder();
        for (String x : h) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(x);
        }
        prefs.edit().putString(key, sb.toString()).apply();
    }

    private void buildPrivacidade(LinearLayout page) {
        privSub = 0;
        LinearLayout c = section(page, "Privacidade");
        toggle(c, "Esconder conteúdo (teste)", "Desfoca as notificações dos apps e conversas escolhidos. Um toque mostra sem abrir o app. Aparece sozinho com o rosto reconhecido ou desbloqueado.", Keys.PRIV_ON, Keys.D_PRIV_ON);
        toggle(c, "Esconder também desbloqueado", "Mesmo desbloqueado, só aparece tocando.", Keys.PRIV_UNLOCKED, Keys.D_PRIV_UNLOCKED);
        LinearLayout l = section(page, "O que esconder");
        int na = getLines(Keys.PRIV_APPS).size(), nc = getLines(Keys.PRIV_CONVS).size();
        privEntry(l, "Apps", na == 0 ? "Nenhum escolhido" : na + (na == 1 ? " app" : " apps") + " com tudo escondido", new View.OnClickListener() {
            public void onClick(View v) {
                showApps();
            }
        });
        privEntry(l, "Conversas", nc == 0 ? "Nenhuma escolhida · só as de apps de mensagem" : nc + (nc == 1 ? " conversa" : " conversas"), new View.OnClickListener() {
            public void onClick(View v) {
                showConvApps();
            }
        });
        int nk = getLines(Keys.PRIV_CONTACTS).size();
        privEntry(l, "Contatos", nk == 0 ? "Nenhum escolhido · vale em qualquer app de mensagem" : nk + (nk == 1 ? " contato" : " contatos"), new View.OnClickListener() {
            public void onClick(View v) {
                showContacts();
            }
        });
        l.addView(note("Conversas aparecem na lista depois da primeira notificação delas. Contatos valem pelo nome salvo na agenda."));
    }

    private void privEntry(LinearLayout card, String name, String help, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(14), dp(14), dp(14));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text(name, 16.5f, cText, false));
        t.addView(text(help, 13.5f, cSub, false));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(text("›", 24, cSub, false));
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) r.setBackgroundResource(tv.resourceId);
        r.setOnClickListener(l);
        card.addView(r);
    }

    private void subPage(String title) {
        bigTitle.setText(title);
        if (pageTitle != null) pageTitle.setText(title);
        back.setText("‹  Privacidade");
        back.setVisibility(View.VISIBLE);
        content.removeAllViews();
        if (scrollView != null) scrollView.scrollTo(0, 0);
    }

    private void showApps() {
        privSub = 1;
        subPage("Apps");
        final LinearLayout c = section(content, "Toque para esconder o conteúdo do app");
        final TextView wait = text("Carregando os apps…", 14, cSub, false);
        wait.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.addView(wait);
        new Thread(new Runnable() {
            public void run() {
                final java.util.ArrayList<Object[]> apps = new java.util.ArrayList<>();
                try {
                    PackageManager pm = getPackageManager();
                    Intent i = new Intent(Intent.ACTION_MAIN);
                    i.addCategory(Intent.CATEGORY_LAUNCHER);
                    java.util.HashSet<String> seen = new java.util.HashSet<>();
                    for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                        String pk = ri.activityInfo.packageName;
                        if (!seen.add(pk)) continue;
                        apps.add(new Object[]{pk, String.valueOf(ri.loadLabel(pm)), ri.loadIcon(pm)});
                    }
                    java.util.Collections.sort(apps, new java.util.Comparator<Object[]>() {
                        public int compare(Object[] a, Object[] b) {
                            return ((String) a[1]).compareToIgnoreCase((String) b[1]);
                        }
                    });
                } catch (Throwable ignored) {
                }
                handler.post(new Runnable() {
                    public void run() {
                        if (privSub != 1) return;
                        c.removeView(wait);
                        java.util.LinkedHashSet<String> on = getLines(Keys.PRIV_APPS);
                        for (Object[] a : apps) appRow(c, (String) a[0], (String) a[1], (android.graphics.drawable.Drawable) a[2], on.contains(a[0]), Keys.PRIV_APPS, (String) a[0]);
                    }
                });
            }
        }).start();
    }

    private void appRow(LinearLayout card, String pkg, String label, android.graphics.drawable.Drawable icon, boolean checked, final String key, final String value) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(8), dp(12), dp(8));
        if (icon != null) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageDrawable(icon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(36), dp(36));
            ip.rightMargin = dp(14);
            r.addView(iv, ip);
        }
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(text(label, 16, cText, false));
        if (pkg != null) t.addView(text(pkg, 12, cSub, false));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        final Switch sw = new Switch(this);
        styleSwitch(sw);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                java.util.LinkedHashSet<String> h = getLines(key);
                if (v) h.add(value);
                else h.remove(value);
                putLines(key, h);
            }
        });
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sw.toggle();
            }
        });
        r.addView(sw);
        card.addView(r);
    }

    /** Apps de mensagem com conversas já vistas. */
    private void showConvApps() {
        privSub = 1;
        subPage("Conversas");
        LinearLayout c = section(content, "Escolha o app");
        java.util.LinkedHashMap<String, Integer> pk = new java.util.LinkedHashMap<>();
        for (String line : getLines(Keys.PRIV_SEEN)) {
            String[] f = line.split("\\|", 3);
            if (f.length < 3) continue;
            Integer n = pk.get(f[0]);
            pk.put(f[0], n == null ? 1 : n + 1);
        }
        if (pk.isEmpty()) {
            para(c, "Nenhuma conversa ainda. Elas aparecem aqui depois da primeira notificação.");
            return;
        }
        PackageManager pm = getPackageManager();
        for (final java.util.Map.Entry<String, Integer> e : pk.entrySet()) {
            String label = e.getKey();
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(e.getKey(), 0)));
            } catch (Throwable ignored) {
            }
            privEntry(c, label, e.getValue() + (e.getValue() == 1 ? " conversa" : " conversas"), new View.OnClickListener() {
                public void onClick(View v) {
                    showConvs(e.getKey());
                }
            });
        }
    }

    private void showConvs(String pkg) {
        privSub = 2;
        privPkg = pkg;
        String label = pkg;
        try {
            PackageManager pm = getPackageManager();
            label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable ignored) {
        }
        subPage(label);
        LinearLayout c = section(content, "Toque para esconder a conversa");
        java.util.LinkedHashSet<String> on = getLines(Keys.PRIV_CONVS);
        java.util.ArrayList<String[]> list = new java.util.ArrayList<>();
        for (String line : getLines(Keys.PRIV_SEEN)) {
            String[] f = line.split("\\|", 3);
            if (f.length == 3 && f[0].equals(pkg)) list.add(f);
        }
        java.util.Collections.sort(list, new java.util.Comparator<String[]>() {
            public int compare(String[] a, String[] b) {
                return a[2].compareToIgnoreCase(b[2]);
            }
        });
        for (String[] f : list) appRow(c, null, f[2], null, on.contains(f[0] + "|" + f[1]), Keys.PRIV_CONVS, f[0] + "|" + f[1]);
    }

    private void showContacts() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, 9);
            return;
        }
        privSub = 1;
        subPage("Contatos");
        final LinearLayout c = section(content, "Toque para esconder as mensagens do contato");
        final TextView wait = text("Carregando os contatos…", 14, cSub, false);
        wait.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.addView(wait);
        new Thread(new Runnable() {
            public void run() {
                final java.util.TreeSet<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                try {
                    android.database.Cursor cur = getContentResolver().query(android.provider.ContactsContract.Contacts.CONTENT_URI,
                            new String[]{android.provider.ContactsContract.Contacts.DISPLAY_NAME}, null, null, null);
                    if (cur != null) {
                        while (cur.moveToNext()) {
                            String s = cur.getString(0);
                            if (s != null && s.trim().length() > 0) names.add(s.trim());
                        }
                        cur.close();
                    }
                } catch (Throwable ignored) {
                }
                handler.post(new Runnable() {
                    public void run() {
                        if (privSub != 1) return;
                        c.removeView(wait);
                        java.util.LinkedHashSet<String> on = getLines(Keys.PRIV_CONTACTS);
                        if (names.isEmpty()) para(c, "Nenhum contato encontrado.");
                        for (String nm : names) appRow(c, null, nm, null, on.contains(nm.toLowerCase()), Keys.PRIV_CONTACTS, nm.toLowerCase());
                    }
                });
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (content != null && privSub == 2) {
            showConvApps();
            return;
        }
        if (content != null && privSub == 1) {
            showPage(5);
            return;
        }
        if (content != null && tab >= 0) {
            showPage(-1);
            return;
        }
        if (content != null && nav != 0) {
            showNav(0);
            return;
        }
        super.onBackPressed();
    }

    /** Aviso do modo de segurança (por 1 dia depois de desligar uma opção). */
    private void safeNotice(LinearLayout page) {
        String key = prefs.getString(Keys.SAFE_MSG, "");
        long at = prefs.getLong(Keys.SAFE_MSG_AT, 0L);
        if (key.length() == 0 || System.currentTimeMillis() - at > 24L * 3600L * 1000L) return;
        LinearLayout sc = section(page, "Modo de segurança");
        TextView tx = text("A Interface do Sistema reiniciou mais de uma vez logo depois de ligar uma opção (" + key + "), então ela foi desligada sozinha. O resto continua como estava. Se quiser, ligue de novo para testar.", 14, cText, false);
        tx.setPadding(dp(16), dp(12), dp(16), dp(4));
        sc.addView(tx);
        LinearLayout r = row();
        r.addView(button("Entendi", false, new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().remove(Keys.SAFE_MSG).remove(Keys.SAFE_MSG_AT).apply();
                showPage(tab);
            }
        }), weight());
        sc.addView(r);
    }

    private void buildHeadsUp(LinearLayout page) {
        // Heads-up: liga/desliga, estilo com os ajustes dele logo embaixo, teste
        LinearLayout hu = section(page, "Heads-up");
        toggle(hu, "Efeito ativado", "A notificação aparece com a animação do estilo escolhido.", Keys.HU_ENABLED, Keys.D_HU_ENABLED);
        styleChooser(hu);
        LinearLayout huButtons = row();
        huButtons.addView(button("Testar heads-up", true, new View.OnClickListener() {
            public void onClick(View v) {
                testHeadsUp();
            }
        }), weight());
        huButtons.addView(button("Permitir flutuante", false, new View.OnClickListener() {
            public void onClick(View v) {
                openNotificationSettings();
            }
        }), weight());
        hu.addView(huButtons);
        hu.addView(note("O teste usa os ajustes acima. Precisa de \"Notificações flutuantes\" ligado para este app."));

        // O que a heads-up mostra
        LinearLayout hc = section(page, "Conteúdo da heads-up");
        toggle(hc, "Sem botões", "Recolhida, mostra só título e texto. Os botões aparecem ao expandir. Ligações e alarmes mantêm os botões.", Keys.HU_NO_ACTIONS, Keys.D_HU_NO_ACTIONS);
        toggle(hc, "Uma por notificação", "Mostra só a notificação nova, sem juntar com as anteriores do app.", Keys.HU_SEPARATE, Keys.D_HU_SEPARATE);

    }

    private void buildConversas(LinearLayout page) {
        LinearLayout tx = section(page, "Texto");
        toggle(tx, "Mensagem inteira ao segurar", "Ao segurar uma notificação, o cartão mostra a mensagem completa, com rolagem.", Keys.MODAL_FULL, Keys.D_MODAL_FULL);
        toggle(tx, "Mais linhas de texto", "Os cartões da central mostram mais linhas da mensagem. Num grupo em pilha ficam com 3; ao abrir a pilha voltam ao escolhido. A heads-up continua igual.", Keys.LINES4, Keys.D_LINES4);
        slider(tx, "Linhas de texto", "Quantas linhas os cartões da central mostram.", Keys.LINES_N, Keys.D_LINES_N, 2, 10, "");
        LinearLayout cc = section(page, "Notificações de conversa");
        toggle(cc, "Hora das conversas no canto", "A hora vai para o canto direito, como nos outros cartões. Até 1 hora mostra \"agora\" ou \"5 min\".", Keys.CONV_TIME, Keys.D_CONV_TIME);
        toggle(cc, "Conversas sem a seta", "Esconde o botão de expandir. Puxe a notificação para expandir.", Keys.CONV_NO_EXPAND, Keys.D_CONV_NO_EXPAND);
        toggle(cc, "Conversas no visual do HyperOS (teste)", "Monta as conversas como os outros cartões do HyperOS: foto, nome, última mensagem e hora no canto.", Keys.CONV_MIUI, Keys.D_CONV_MIUI);
        toggle(cc, "Cada mensagem num cartão novo (teste)", "Como no iOS: cada mensagem nova vira um cartão. Ao abrir a conversa, todos somem. Precisa de \"Framework do sistema\" no escopo do LSPosed.", Keys.CONV_SPLIT, Keys.D_CONV_SPLIT);
        toggle(cc, "Grupos só com o nome de quem mandou (teste)", "Em grupos, o título mostra só quem mandou, sem o nome do grupo. Precisa de \"Framework do sistema\" no escopo.", Keys.CONV_SENDER, Keys.D_CONV_SENDER);
        cc.addView(note("Valem para as notificações novas ou atualizadas."));
        LinearLayout vi = section(page, "Visual dos cartões");
        toggle(vi, "Texto das notificações com vibrance", "O conteúdo das notificações (menos o título) ganha o efeito vibrancy, como os tempos do player.", Keys.BODY_VIB, false);
        slider(vi, "Intensidade do texto", "Quanto o texto clareia (escuro) ou escurece (claro) o vidro do cartão.", Keys.BODY_VIB_N, 70, 0, 100, "%");
        toggle(vi, "Hora das notificações como no iOS (teste)", "A hora das notificações ganha o mesmo efeito vibrancy, com a intensidade dos números do player.", Keys.NOTIF_VIB, Keys.D_NOTIF_VIB);

    }

    private void buildGaveta(LinearLayout page) {
        LinearLayout st = section(page, "Empilhamento na gaveta");
        toggle(st, "Empilhar notificações", "As que passam do limite encolhem embaixo, como no HyperOS 4.", Keys.ST_ENABLED, Keys.D_ST_ENABLED);
        slider(st, "Limite acima do fim da tela", "Maior começa a pilha mais alto.", Keys.ST_OFFSET, Keys.D_ST_OFFSET, 0, 400, " dp");
        toggle(st, "Grupos expandidos por cartão", "Cada notificação de um grupo aberto empilha separada.", Keys.ST_GROUPS, Keys.D_ST_GROUPS);
        toggle(st, "Grupos em pilha (teste)", "Notificações do mesmo app viram uma pilha de cartões. Toque para abrir; a seta empilha de novo. Apps sem grupo próprio (Instagram, Telegram) também empilham com \"Framework do sistema\" no escopo.", Keys.GROUP_STACK, Keys.D_GROUP_STACK);
        slider(st, "Pilha: velocidade", "Mais alto é mais rápido.", Keys.GS_SPEED, Keys.D_GS_SPEED, 50, 200, "%");
        slider(st, "Pilha: bounce", "0% sem pulo; 300% pula bem mais.", Keys.GS_BOUNCE, Keys.D_GS_BOUNCE, 0, 300, "%");
        slider(st, "Pilha: profundidade", "Quanto os cartões de trás aparecem.", Keys.GS_DEPTH, Keys.D_GS_DEPTH, 4, 30, " dp");
        slider(st, "Pilha: sombra entre os cartões", "Sombra suave entre os cartões. 0% tira.", Keys.GS_SHADOW, Keys.D_GS_SHADOW, 0, 100, "%");

    }

    private void buildBloqueio(LinearLayout page) {
        LinearLayout kg = section(page, "Tela de bloqueio");
        toggle(kg, "Bloqueio com o HyperMusicCover", "Relógio, capa, player e ilha do bloqueio usam o HyperMusicCover (precisa da extensão, veja Extensões).", Keys.KG_HMC, Keys.D_KG_HMC);
        toggle(kg, "Manter notificações após desbloquear", "As notificações vistas continuam no bloqueio até serem apagadas.", Keys.KG_KEEP, Keys.D_KG_KEEP);
        toggle(kg, "Empilhar no bloqueio", "Mostra todas as notificações e empilha as que passam do limite.", Keys.KG_ENABLED, Keys.D_KG_ENABLED);
        toggle(kg, "Rolar sem desbloquear", "Deslizar sobre as notificações rola a lista; fora delas, desbloqueia.", Keys.KG_SCROLL, Keys.D_KG_SCROLL);
        toggle(kg, "Relógio na posição inicial", "Com notificações, o relógio fica grande e no mesmo lugar.", Keys.KG_CLOCK_FIXED, Keys.D_KG_CLOCK_FIXED);
        toggle(kg, "Relógio acompanha a rolagem", "Ao rolar, as notificações empurram o relógio para cima.", Keys.KG_CLOCK, Keys.D_KG_CLOCK);
        toggle(kg, "Desfoque no topo", "As notificações se desfocam ao passar sob a barra de status.", Keys.KG_BLUR, Keys.D_KG_BLUR);
        toggle(kg, "Lista baixa (estilo iOS)", "A lista começa mais embaixo, perto do polegar.", Keys.KG_IOS, Keys.D_KG_IOS);
        slider(kg, "Início da lista", "Onde o primeiro cartão começa com muitas notificações.", Keys.KG_START, Keys.D_KG_START, 25, 90, "%");
        slider(kg, "Limite acima do fim da tela", "Deixe espaço para o cadeado e os atalhos de baixo.", Keys.KG_OFFSET, Keys.D_KG_OFFSET, 40, 500, " dp");
        toggle(kg, "Puxar para empilhar (teste)", "Com a lista no topo, continue rolando: as notificações se empilham até sobrar o player. Precisa de \"Empilhar no bloqueio\" e \"Rolar sem desbloquear\".", Keys.KG_HIDE, Keys.D_KG_HIDE);
        kg.addView(note("Vale na próxima vez que a tela bloquear."));


    }

    private void buildBarra(LinearLayout page) {
        LinearLayout sys = section(page, "Pílulas do relógio");
        toggle(sys, "Relógio vermelho ao gravar a tela", "Durante a gravação de tela, o relógio da barra de status ganha uma pílula vermelha, como no iOS.", Keys.REC_PILL, Keys.D_REC_PILL);
        toggle(sys, "Relógio azul com a localização em uso", "Quando algum app está usando a localização, o relógio ganha uma pílula azul.", Keys.LOC_PILL, Keys.D_LOC_PILL);
        toggle(sys, "Relógio verde com o roteador ligado", "Com o roteador Wi-Fi (hotspot) ligado, o relógio ganha uma pílula verde.", Keys.HOT_PILL, Keys.D_HOT_PILL);
        toggle(sys, "Vibrance na pílula do relógio", "Com uma pílula atrás, a hora vira um recorte e deixa ver o que está passando na tela, como o efeito vibrancy do iOS.", Keys.PILL_VIB, false);
        LinearLayout co = section(page, "Cores das pílulas");
        colorRow(co, "Cor da gravação", Keys.REC_COLOR, 0xFFFF3B30);
        colorRow(co, "Cor da localização", Keys.LOC_COLOR, 0xFF0A84FF);
        colorRow(co, "Cor do roteador", Keys.HOT_COLOR, 0xFF34C759);
    }

    private void buildCentral(LinearLayout page) {
        LinearLayout ios = section(page, "Cortina");
        toggle(ios, "Central em cortina (teste)", "A central desce do topo como uma cortina, com o fundo do bloqueio, relógio grande, lanterna e câmera. Desligado = central do HyperOS.", Keys.SHADE_SHEET, false);
        toggle(ios, "Cantos arredondados na cortina", "Arredonda a borda de baixo da cortina enquanto ela se move.", Keys.SHEET_ROUND, true);
        toggle(ios, "Fundo desfocado", "Usa o desfoque do HyperOS (a tela de trás desfocada). Desligado = papel de parede do bloqueio.", Keys.SHEET_BLUR, true);
        LinearLayout ck = section(page, "Relógio da cortina");
        toggle(ck, "Relógio do bloqueio na cortina (teste)", "Usa o mesmo modelo de relógio da tela de bloqueio (troca junto quando você muda o modelo). Alguns modelos sobem e encolhem ao rolar as notificações.", Keys.SHEET_LOCKCLOCK, false);
        slider(ck, "Posição do relógio", "Quanto o relógio próprio da cortina (e a lista) descem a partir do topo. Não vale com o relógio do bloqueio.", Keys.SHEET_CLOCK, 60, 0, 240, " dp");
    }

    private void buildSistema(LinearLayout page) {
        LinearLayout sys = section(page, "Sistema");
        LinearLayout sysButtons = row();
        sysButtons.addView(button("Reiniciar Interface do Sistema", false, new View.OnClickListener() {
            public void onClick(View v) {
                restartSystemUi();
            }
        }), weight());
        sysButtons.addView(button("Restaurar tudo", false, new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().clear().apply();
                showPage(tab);
            }
        }), weight());
        sys.addView(sysButtons);
        sys.addView(note("Reiniciar precisa de root. Toque no ↺ ao lado de um controle para voltar só ele ao valor original."));
    }

    // ------------------------------------------------------------------ aba Música

    private void buildMusic(LinearLayout page) {
        LinearLayout pg = section(page, "Player da gaveta");
        toggle(pg, "Fundo igual às notificações", "O player usa o mesmo vidro das outras notificações, em vez do mais escuro.", Keys.MEDIA_PURE, Keys.D_MEDIA_PURE);
        toggle(pg, "Capa gira e brilha na troca", "Como no ColorOS 17: ao trocar a música, a capa gira e solta um brilho que se espalha pelo cartão.", Keys.ALBUM_GLOW, Keys.D_ALBUM_GLOW);
        toggle(pg, "Brilho em onda de luz", "Como no ColorOS 17: uma onda de luz nasce na capa e se espalha pelo cartão, como uma gota na água. Desligado, a capa solta uma mancha desfocada. Posição e distância só valem para a mancha.", Keys.GLOW_SWEEP, Keys.D_GLOW_SWEEP);
        slider(pg, "Brilho: intensidade", "Quão forte o brilho aparece. Acima de 100% fica mais aceso que o padrão.", Keys.GLOW_POWER, Keys.D_GLOW_POWER, 10, 300, "%");
        slider(pg, "Brilho: tamanho", "Até onde ele se espalha em volta da capa.", Keys.GLOW_SIZE, Keys.D_GLOW_SIZE, 40, 200, "%");
        slider(pg, "Brilho: distância", "Quanto ele desce pelo cartão enquanto some.", Keys.GLOW_DIST, Keys.D_GLOW_DIST, 0, 250, "%");
        slider(pg, "Brilho: posição lateral", "Para a esquerda (negativo) ou direita, em % da capa.", Keys.GLOW_X, Keys.D_GLOW_X, -150, 150, "%");
        slider(pg, "Brilho: posição vertical", "Para cima (negativo) ou para baixo, em % da capa.", Keys.GLOW_Y, Keys.D_GLOW_Y, -100, 150, "%");
        slider(pg, "Brilho: duração", "Mais alto é mais lento.", Keys.GLOW_TIME, Keys.D_GLOW_TIME, 50, 250, "%");
        toggle(pg, "Título com as pontas desfocadas", "Nomes compridos rolam com as pontas desfocadas, em vez de cortadas.", Keys.MEDIA_EDGE, Keys.D_MEDIA_EDGE);
        toggle(pg, "Cometa na barra (teste)", "A barra do player fica fina e a parte tocada vai da cor da capa até uma ponta branca que brilha, como no iOS.", Keys.COMET_BAR, Keys.D_COMET_BAR);
        slider(pg, "Cometa: espessura da barra", "Grossura da barra com o cometa. 0 = igual à barra do sistema (sem mudar de tamanho ao pausar).", Keys.COMET_THICK, Keys.D_COMET_THICK, 0, 10, " dp");
        slider(pg, "Cometa: tamanho da cauda", "Comprimento máximo da cauda. Ela nasce curta e cresce com a música até esse tamanho, abrindo em V para trás.", Keys.COMET_TAIL, Keys.D_COMET_TAIL, 20, 200, " dp");
        slider(pg, "Cometa: começo apagado", "Quanto o começo da parte tocada fica mais transparente. 0 deixa sólido.", Keys.COMET_FADE, Keys.D_COMET_FADE, 0, 100, "%");
        slider(pg, "Cometa: brilho", "Quão forte a luz em volta da ponta aparece. 0 tira o brilho.", Keys.COMET_GLOW, Keys.D_COMET_GLOW, 0, 100, "%");
        slider(pg, "Cometa: tamanho do brilho", "Até onde a luz da ponta se espalha.", Keys.COMET_SIZE, Keys.D_COMET_SIZE, 50, 250, "%");
        slider(pg, "Cometa: pulsar", "O brilho pulsa no ritmo de um segundo. 0 deixa fixo.", Keys.COMET_PULSE, Keys.D_COMET_PULSE, 0, 100, "%");
        slider(pg, "Cometa: faíscas", "Pontinhos que ficam para trás enquanto toca. 0 tira.", Keys.COMET_SPARKS, Keys.D_COMET_SPARKS, 0, 10, "");
        LinearLayout cc = section(page, "Cor do cometa");
        toggle(cc, "Começo na cor da capa", "O começo da parte tocada pega a cor mais viva da capa, clareada. Desligado, a barra toda usa a cor da ponta.", Keys.COMET_AUTO, Keys.D_COMET_AUTO);
        colorRow(cc, "Cor da ponta", Keys.COMET_COLOR, 0xFFFFFFFF);
        toggle(pg, "Barra e tempos como no iOS (teste)", "A barra e os tempos ganham o efeito vibrancy do iOS, no tom do vidro. Troca sozinho entre claro e escuro.", Keys.MEDIA_CUT, Keys.D_MEDIA_CUT);
        slider(pg, "iOS: intensidade dos números", "Quanto os tempos clareiam (escuro) ou escurecem (claro) o vidro.", Keys.MV_TEXT, Keys.D_MV_TEXT, 0, 100, "%");
        slider(pg, "iOS: intensidade do trilho", "A parte da barra que ainda falta tocar.", Keys.MV_TRACK, Keys.D_MV_TRACK, 0, 100, "%");
        toggle(pg, "Vibrance no artista e nos botões (teste)", "O nome do artista e os botões de voltar e pular ganham o efeito vibrancy, como os tempos.", Keys.MEDIA_VIB_PLUS, Keys.D_MEDIA_VIB_PLUS);
        slider(pg, "Artista e botões: intensidade", "Quanto clareiam (escuro) ou escurecem (claro) o vidro.", Keys.MV_EXTRA, Keys.D_MV_EXTRA, 0, 100, "%");
        slider(pg, "iOS: intensidade da parte tocada", "A parte da barra que já tocou.", Keys.MV_PLAYED, Keys.D_MV_PLAYED, 0, 100, "%");

    }

    private void buildCreditos(LinearLayout page) {
        LinearLayout cr = section(page, "Créditos e licença");
        link(cr, "Hyper Eternity no GitHub", "Código-fonte, versões e novidades", "https://github.com/GeMugi/Hyper-Eternity");
        link(cr, "HyperMusicCover", "Autor original: zyl6932", "https://github.com/zyl6932/HyperMusicCover");
        link(cr, "HyperMusicCover-Enhanced", "Versão melhorada: Lewewe (versão incluída: 1.0.0)", "https://github.com/Lewewe/HyperMusicCover-Enhanced");
        cr.addView(note("Os dois são distribuídos sob a licença GNU AGPL v3.0. Por incluir esse código, o Hyper Eternity também passa a ser AGPL v3.0, e o código-fonte acompanha cada versão."));
    }

    /** Linha que abre um endereço no navegador. */
    private void link(LinearLayout card, String label, String help, final String url) {
        divider(card);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(16), dp(12), dp(16), dp(12));
        r.addView(text(label, 16, cText, false));
        TextView h = text(help, 13, cSub, false);
        h.setPadding(0, dp(2), 0, 0);
        r.addView(h);
        TextView u = text(url.replace("https://", ""), 12.5f, cAccent, false);
        u.setPadding(0, dp(2), 0, 0);
        r.addView(u);
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            r.setBackgroundResource(tv.resourceId);
        }
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                } catch (Throwable t) {
                    Toast.makeText(SettingsActivity.this, Tr.t("Nenhum navegador para abrir o link"), Toast.LENGTH_SHORT).show();
                }
            }
        });
        card.addView(r);
    }

    private void openMusicSettings() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setClassName(MUSIC_PKG, "com.os4.musiccover.MainActivity");
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, Tr.t("Não consegui abrir os ajustes da música: ") + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(Tr.t(s));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLineSpacing(0f, 1.15f);
        return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 12.5f, cSub, false);
        t.setPadding(dp(16), dp(2), dp(16), dp(14));
        return t;
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout section(LinearLayout page, String name) {
        boolean sob = tab < 0 && nav == 4;
        if (name != null) {
            TextView h = text(name, sob ? 15 : 14, cSub, sob);
            h.setPadding(dp(16), dp(22), dp(16), dp(10));
            if (sob) vibrant(h);
            page.addView(h);
        }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(sob && sobreBlur != null ? new BgGlass(card, dp(20)) : glassCard(20));
        card.setPadding(0, dp(6), 0, dp(6));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        if (name == null) slp.topMargin = dp(14);
        page.addView(card, slp);
        return card;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(12), dp(8), dp(12), dp(6));
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        return lp;
    }

    private void divider(LinearLayout card) {
        if (true) return;   // o miuix separa as linhas só pelo espaço
        if (card.getChildCount() == 0) return;
        View line = new View(this);
        line.setBackgroundColor(cLine);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.6f)));
        lp.leftMargin = dp(16);
        lp.rightMargin = dp(16);
        card.addView(line, lp);
    }

    /** Linha com a bolinha da cor; toque abre a escolha (cores prontas + matiz + claridade). */
    private void colorRow(LinearLayout card, String label, final String key, final int def) {
        divider(card);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(14), dp(16), dp(14));
        r.addView(text(label, 16, cText, false), new LinearLayout.LayoutParams(0, -2, 1f));
        final View dot = new View(this);
        final android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(prefs.getInt(key, def));
        g.setStroke(dp(1), 0x33888888);
        dot.setBackground(g);
        r.addView(dot, new LinearLayout.LayoutParams(dp(30), dp(30)));
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickColor(key, def, g);
            }
        });
        card.addView(r);
    }

    private void pickColor(final String key, final int def, final android.graphics.drawable.GradientDrawable dot) {
        final int[] cur = {prefs.getInt(key, def)};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));
        final View preview = new View(this);
        final android.graphics.drawable.GradientDrawable pg = new android.graphics.drawable.GradientDrawable();
        pg.setCornerRadius(dp(16));
        pg.setColor(cur[0]);
        preview.setBackground(pg);
        box.addView(preview, new LinearLayout.LayoutParams(-1, dp(32)));
        final float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(cur[0], hsv);
        final SeekBar hue = new SeekBar(this), sat = new SeekBar(this), val = new SeekBar(this);
        hue.setMax(360);
        sat.setMax(100);
        val.setMax(100);
        final Runnable fromBars = new Runnable() {
            public void run() {
                cur[0] = android.graphics.Color.HSVToColor(new float[]{hue.getProgress(), sat.getProgress() / 100f, val.getProgress() / 100f});
                pg.setColor(cur[0]);
            }
        };
        final Runnable toBars = new Runnable() {
            public void run() {
                android.graphics.Color.colorToHSV(cur[0], hsv);
                hue.setProgress(Math.round(hsv[0]));
                sat.setProgress(Math.round(hsv[1] * 100));
                val.setProgress(Math.round(hsv[2] * 100));
                pg.setColor(cur[0]);
            }
        };
        int[] presets = {0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759, 0xFF00C7BE, 0xFF0A84FF, 0xFF5856D6, 0xFFAF52DE, 0xFFFF2D55, 0xFFA2845E, 0xFF8E8E93, 0xFFFFFFFF};
        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setPadding(0, dp(12), 0, 0);
            for (int i = 0; i < 6; i++) {
                final int c = presets[row * 6 + i];
                View sw = new View(this);
                android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable();
                sg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                sg.setColor(c);
                sg.setStroke(dp(1), 0x33888888);
                sw.setBackground(sg);
                sw.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        cur[0] = c;
                        toBars.run();
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
                lp.setMargins(dp(5), 0, dp(5), 0);
                line.addView(sw, lp);
            }
            box.addView(line);
        }
        String[] names = {"Matiz", "Saturação", "Brilho"};
        SeekBar[] bars = {hue, sat, val};
        for (int i = 0; i < 3; i++) {
            TextView t = text(names[i], 13, cSub, false);
            t.setPadding(0, dp(12), 0, 0);
            box.addView(t);
            bars[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int p, boolean user) {
                    if (user) fromBars.run();
                }
                public void onStartTrackingTouch(SeekBar s) {}
                public void onStopTrackingTouch(SeekBar s) {}
            });
            box.addView(bars[i]);
        }
        toBars.run();
        new android.app.AlertDialog.Builder(this)
                .setView(box)
                .setPositiveButton(Tr.t("Salvar"), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        prefs.edit().putInt(key, cur[0]).apply();
                        dot.setColor(cur[0]);
                    }
                })
                .setNeutralButton(Tr.t("Padrão"), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        prefs.edit().remove(key).apply();
                        dot.setColor(def);
                    }
                })
                .setNegativeButton(Tr.t("Cancelar"), null)
                .show();
    }

    private void toggle(LinearLayout card, final String label, String help, final String key, final boolean def) {
        divider(card);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(12), dp(12), dp(12));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(label, 16, cText, false));
        TextView h = text(help, 13, cSub, false);
        h.setPadding(0, dp(2), dp(8), 0);
        texts.addView(h);
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        final Switch sw = new Switch(this);
        styleSwitch(sw);
        sw.setChecked(prefs.getBoolean(key, def));
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sw.toggle();
            }
        });
        // liga/desliga não tem "valor original" para voltar: sem o botão ↺ aqui
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                SharedPreferences.Editor e = prefs.edit().putBoolean(key, on);
                // modo de segurança: anota a última opção ligada
                if (on) e.putString(Keys.SAFE_LAST, key).putLong(Keys.SAFE_AT, System.currentTimeMillis());
                else if (key.equals(prefs.getString(Keys.SAFE_LAST, ""))) e.remove(Keys.SAFE_LAST).remove(Keys.SAFE_AT);
                e.apply();
            }
        });
        r.addView(sw);
        card.addView(r);
    }

    /**
     * Estilo da heads-up: botões OriginOS | iOS, a descrição do estilo escolhido e, logo abaixo,
     * um painel com a velocidade e o bounce só daquele estilo.
     */
    private void styleChooser(LinearLayout card) {
        final String key = Keys.HU_STYLE;
        final int def = Keys.D_HU_STYLE;
        final String[] names = {"OriginOS", "iOS"};
        final String[] helps = {
                "Nasce da ilha e cresce até o lugar dela, com a mola do OriginOS.",
                "Sai do ícone do app na barra de status como uma gota e cresce com a mola. Ao sair, volta para o ícone."
        };
        divider(card);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(14));
        android.animation.LayoutTransition lt = new android.animation.LayoutTransition();
        lt.enableTransitionType(android.animation.LayoutTransition.CHANGING);
        box.setLayoutTransition(lt);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text("Estilo", 16, cText, false), new LinearLayout.LayoutParams(0, -2, 1f));
        final View reset = resetButton("Estilo");
        head.addView(reset);
        box.addView(head);

        final LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        seg.setBackground(round(cTrack, 16));
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(-1, -2);
        segLp.topMargin = dp(8);
        box.addView(seg, segLp);

        final TextView desc = text("", 13, cSub, false);
        desc.setPadding(dp(2), dp(10), dp(2), 0);
        box.addView(desc);

        // um painel de ajustes por estilo; só o do estilo escolhido aparece
        final LinearLayout[] panels = new LinearLayout[2];
        panels[0] = stylePanel();
        slider(panels[0], "Velocidade", "Mais rápido encurta a animação.", Keys.HU_SPEED, Keys.D_HU_SPEED, 50, 200, "%");
        slider(panels[0], "Bounce", "0% sem pulo; 200% dá o dobro.", Keys.HU_BOUNCE, Keys.D_HU_BOUNCE, 0, 200, "%");
        panels[1] = stylePanel();
        slider(panels[1], "Velocidade", "Vale para a entrada e para a saída.", Keys.HU_IOS_SPEED, Keys.D_HU_IOS_SPEED, 50, 200, "%");
        slider(panels[1], "Bounce", "Quanto o cartão passa do tamanho final ao crescer.", Keys.HU_IOS_BOUNCE, Keys.D_HU_IOS_BOUNCE, 0, 200, "%");
        for (int i = 0; i < panels.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(12);
            box.addView(panels[i], lp);
        }

        final TextView[] items = new TextView[names.length];
        final Runnable paint = new Runnable() {
            public void run() {
                int cur = prefs.getInt(key, def);
                if (cur < 0 || cur >= names.length) cur = def;
                for (int i = 0; i < items.length; i++) {
                    boolean on = i == cur;
                    items[i].setBackground(on ? round(cAccent, 13) : null);
                    items[i].setTextColor(on ? (night ? 0xFF0E1530 : 0xFFFFFFFF) : cText);
                    items[i].setSelected(on);
                    panels[i].setVisibility(on ? View.VISIBLE : View.GONE);
                }
                desc.setText(helps[cur]);
                reset.setVisibility(cur != def ? View.VISIBLE : View.GONE);
            }
        };
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = text(names[i], 14, cText, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(8), dp(9), dp(8), dp(9));
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    prefs.edit().putInt(key, idx).apply();
                    paint.run();
                }
            });
            items[i] = t;
            seg.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs.edit().remove(key).apply();
                paint.run();
            }
        });
        paint.run();
        card.addView(box);
        // o cartão (e a página) acompanham suavemente a troca de painel
        smoothResize(card);
        if (card.getParent() instanceof ViewGroup) smoothResize((ViewGroup) card.getParent());
    }

    private void smoothResize(ViewGroup g) {
        android.animation.LayoutTransition t = g.getLayoutTransition();
        if (t == null) {
            t = new android.animation.LayoutTransition();
            g.setLayoutTransition(t);
        }
        t.enableTransitionType(android.animation.LayoutTransition.CHANGING);
    }

    /** Painel levemente destacado que agrupa os ajustes de um estilo. */
    private LinearLayout stylePanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackground(round(night ? 0x10FFFFFF : 0x08000000, 16));
        p.setPadding(0, dp(2), 0, dp(2));
        return p;
    }

    /** Botão pequeno (↺) que volta um ajuste ao padrão; só aparece quando o valor foi mudado. */
    private View resetButton(String label) {
        android.widget.ImageView b = new android.widget.ImageView(this);
        int icon = getResources().getIdentifier("ic_reset", "drawable", getPackageName());
        if (icon != 0) b.setImageResource(icon);
        b.setImageTintList(android.content.res.ColorStateList.valueOf(cAccent));
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setContentDescription("Restaurar o padrão de " + label);
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
            b.setBackgroundResource(tv.resourceId);
        }
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
        return b;
    }

    private void slider(LinearLayout card, final String label, String help, final String key, final int def,
                        final int min, int max, final String unit) {
        divider(card);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(10));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.addView(text(label, 16, cText, false), new LinearLayout.LayoutParams(0, -2, 1f));
        head.setGravity(Gravity.CENTER_VERTICAL);
        final View reset = resetButton(label);
        head.addView(reset);
        final TextView value = text("", 16, cAccent, true);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        value.setFontFeatureSettings("tnum");
        head.addView(value);
        box.addView(head);
        TextView h = text(help, 13, cSub, false);
        h.setPadding(0, dp(2), 0, dp(4));
        box.addView(h);
        final SeekBar bar = new SeekBar(this);
        styleSeek(bar);
        bar.setMax(max - min);
        int cur = prefs.getInt(key, def);
        if (cur < min) cur = min;
        if (cur > max) cur = max;
        bar.setProgress(cur - min);
        value.setText(show(cur, unit));
        reset.setVisibility(cur != def ? View.VISIBLE : View.GONE);
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                bar.setProgress(def - min);
                prefs.edit().remove(key).apply();
                reset.setVisibility(View.GONE);
            }
        });
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                value.setText(show(p + min, unit));
                reset.setVisibility(p + min != def ? View.VISIBLE : View.GONE);
            }

            public void onStartTrackingTouch(SeekBar s) {
            }

            public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt(key, s.getProgress() + min).apply();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(26));
        blp.topMargin = dp(6);
        blp.bottomMargin = dp(4);
        box.addView(bar, blp);
        card.addView(box);
    }

    /** Valor do controle deslizante; a unidade "ds" mostra décimos de segundo ("+0,3 s"). */
    private static String show(int v, String unit) {
        if ("ds".equals(unit)) {
            String s = (v < 0 ? "−" : v > 0 ? "+" : "") + (Math.abs(v) / 10) + "," + (Math.abs(v) % 10) + " s";
            return s;
        }
        return v + unit;
    }

    private Button button(String label, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(primary ? (night ? 0xFF0E1530 : 0xFFFFFFFF) : cAccent);
        b.setBackground(primary ? round(cAccent, 18) : glassCard(18));
        b.setStateListAnimator(null);
        b.setPadding(dp(10), dp(12), dp(10), dp(12));
        b.setOnClickListener(l);
        return b;
    }

    // ------------------------------------------------------------------ ações

    private void testHeadsUp() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
            return;
        }
        final NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel("teste", "Teste do heads-up", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Notificações de teste da animação");
        nm.createNotificationChannel(ch);
        int icon = getResources().getIdentifier("ic_stat", "drawable", getPackageName());
        final Notification n = new Notification.Builder(this, "teste")
                .setSmallIcon(icon != 0 ? icon : android.R.drawable.ic_dialog_info)
                .setContentTitle("Hyper Eternity")
                .setContentText(testText())
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                // botões de exemplo, para conferir que só aparecem ao expandir a heads-up
                .addAction(new Notification.Action.Builder(null, "Marcar como lido", testIntent(1)).build())
                .addAction(new Notification.Action.Builder(null, "Responder", testIntent(2)).build())
                .build();
        Toast.makeText(this, Tr.t("O teste aparece em 1,5 s"), Toast.LENGTH_SHORT).show();
        handler.postDelayed(new Runnable() {
            public void run() {
                nm.notify(1001, n);
            }
        }, 1500L);
    }

    private String testText() {
        boolean ios = prefs.getInt(Keys.HU_STYLE, Keys.D_HU_STYLE) == 1;
        int v = ios ? prefs.getInt(Keys.HU_IOS_SPEED, Keys.D_HU_IOS_SPEED) : prefs.getInt(Keys.HU_SPEED, Keys.D_HU_SPEED);
        int b = ios ? prefs.getInt(Keys.HU_IOS_BOUNCE, Keys.D_HU_IOS_BOUNCE) : prefs.getInt(Keys.HU_BOUNCE, Keys.D_HU_BOUNCE);
        return "Teste " + (ios ? "iOS" : "OriginOS") + ": velocidade " + v + "% · bounce " + b + "%";
    }

    private android.app.PendingIntent testIntent(int code) {
        Intent i = new Intent(this, SettingsActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return android.app.PendingIntent.getActivity(this, code, i,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 7 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) testHeadsUp();
        if (code == 9 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) showContacts();
    }

    private void openNotificationSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, Tr.t("Não consegui abrir os ajustes de notificação"), Toast.LENGTH_SHORT).show();
        }
    }

    private void restartSystemUi() {
        new Thread(new Runnable() {
            public void run() {
                boolean ok;
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "killall com.android.systemui"});
                    ok = p.waitFor() == 0;
                } catch (Throwable t) {
                    ok = false;
                }
                final boolean done = ok;
                handler.post(new Runnable() {
                    public void run() {
                        if (!done) {
                            Toast.makeText(SettingsActivity.this, Tr.t("Sem acesso root. Reinicie a Interface do Sistema pelo LSPosed ou reinicie o aparelho."), Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }
}
