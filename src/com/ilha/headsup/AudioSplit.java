package com.ilha.headsup;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;

import com.ilha.headsup.xp.XposedBridge;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Sistema (system_server): apps escolhidos tocam no alto-falante enquanto um Bluetooth de áudio está
 * conectado (como o "redirecionamento de áudio" do OriginOS).
 *
 * Usa a política de áudio do próprio Android (a mesma dos carros com várias zonas de som): dois
 * "destinos" de reprodução, o Bluetooth e o alto-falante, e uma afinidade por app (uid) que tira o app
 * do Bluetooth. Os outros apps seguem o caminho normal. Sem Bluetooth, a política sai e tudo volta ao padrão.
 */
final class AudioSplit {
    private AudioSplit() {}

    static final int RULE_MATCH_ATTRIBUTE_USAGE = 1, ROUTE_FLAG_RENDER = 1;
    static int logs, fails;
    static Context ctx;
    static AudioManager am;
    static SharedPreferences prefs;
    static Handler h;
    static Object policy;
    static int policyBt = -1, policySpk = -1;
    static final Map<String, Integer> applied = new HashMap<>();

    static void log(String m) {
        if (logs++ < 80) XposedBridge.log("[HeadsUpIlha] saída de áudio: " + m);
    }

    /** Vale só durante as nossas chamadas de afinidade (na nossa linha de execução). */
    static final ThreadLocal<Boolean> granting = new ThreadLocal<>();

    /**
     * O serviço de áudio confere a permissão de roteamento com "quem está chamando de fora"; uma chamada
     * feita de dentro do próprio sistema (o nosso caso) é sempre negada, mesmo o sistema tendo a permissão.
     * Durante as nossas chamadas, essa conferência responde que sim.
     */
    static void hookPermission() {
        try {
            Class<?> ci = Class.forName("android.app.ContextImpl");
            XposedBridge.hookMethod(ci.getDeclaredMethod("checkCallingPermission", String.class), new com.ilha.headsup.xp.XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (Boolean.TRUE.equals(granting.get()) && "android.permission.MODIFY_AUDIO_ROUTING".equals(p.args[0]))
                        p.setResult(android.content.pm.PackageManager.PERMISSION_GRANTED);
                }
            });
        } catch (Throwable t) {
            log("sem gancho na permissão: " + t);
        }
    }

    static void install() {
        hookPermission();
        HandlerThread t = new HandlerThread("eternity-audio");
        t.start();
        h = new Handler(t.getLooper());
        h.postDelayed(new Runnable() {
            public void run() {
                try {
                    if (!ready()) {
                        h.postDelayed(this, 5000);
                        return;
                    }
                    tick();
                } catch (Throwable e) {
                    log("erro: " + e);
                }
                h.postDelayed(this, 4000);
            }
        }, 15000);
        log("instalado (espera o sistema terminar de iniciar)");
    }

    static boolean ready() throws Exception {
        if (am != null) return true;
        Class<?> st = Class.forName("android.os.ServiceManager");
        if (st.getMethod("getService", String.class).invoke(null, "audio") == null) return false;
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thr = at.getMethod("currentActivityThread").invoke(null);
        if (thr == null) return false;
        ctx = (Context) at.getMethod("getSystemContext").invoke(thr);
        if (ctx == null) return false;
        am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) log("pronto");
        return am != null;
    }

    static LinkedHashSet<String> apps() {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        String v = prefs.getString(Keys.AUDIO_SPLIT_APPS, "");
        if (v != null) for (String l : v.split("\n")) if (l.trim().length() > 0) s.add(l.trim());
        return s;
    }

    static boolean cbOn;
    static int activeRedirected = 0;
    static long refreshAt;

    /**
     * Quando o último app redirecionado para de tocar, o Android não devolve ao Bluetooth os outros apps
     * que pegaram carona no alto-falante (a saída de som é compartilhada no hardware). Refazer a política
     * nessa hora obriga o sistema a recalcular a saída de todo mundo.
     */
    static void playbackWatch() {
        if (cbOn) return;
        cbOn = true;
        am.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
            public void onPlaybackConfigChanged(List<android.media.AudioPlaybackConfiguration> configs) {
                try {
                    if (policy == null) return;
                    int n = 0;
                    for (android.media.AudioPlaybackConfiguration c : configs) {
                        int st = (Integer) c.getClass().getMethod("getPlayerState").invoke(c);
                        if (st != 2 /* STARTED */) continue;
                        int uid = (Integer) c.getClass().getMethod("getClientUid").invoke(c);
                        if (applied.containsValue(uid)) n++;
                    }
                    if (activeRedirected > 0 && n == 0) {
                        // espera um pouco: o app pode estar só trocando de faixa
                        refreshAt = android.os.SystemClock.uptimeMillis() + 1500;
                        h.postDelayed(new Runnable() {
                            public void run() {
                                if (android.os.SystemClock.uptimeMillis() < refreshAt - 50 || activeRedirected > 0) return;
                                refresh();
                            }
                        }, 1600);
                    }
                    activeRedirected = n;
                } catch (Throwable t) {
                    log("vigia de reprodução: " + t);
                }
            }
        }, h);
        log("vigiando o que toca");
    }

    /** Refaz a política (e as afinidades): devolve ao Bluetooth quem ficou preso no alto-falante. */
    static void refresh() {
        try {
            if (policy == null) return;
            drop();
            tick();
            log("saída recalculada (app redirecionado parou de tocar)");
        } catch (Throwable t) {
            log("não deu para recalcular: " + t);
        }
    }

    static void tick() throws Exception {
        playbackWatch();
        if (prefs == null) prefs = XposedBridge.remotePrefs(Keys.GROUP);
        boolean on = prefs != null && prefs.getBoolean(Keys.AUDIO_SPLIT, Keys.D_AUDIO_SPLIT);
        LinkedHashSet<String> apps = on ? apps() : new LinkedHashSet<String>();
        AudioDeviceInfo bt = null, spk = null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) spk = d;
            else if (bt == null && (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == 26 /* BLE_HEADSET */ || t == 27 /* BLE_SPEAKER */)) bt = d;
        }
        boolean want = on && !apps.isEmpty() && bt != null && spk != null;
        if (!want) {
            if (policy != null) {
                drop();
                log("desligado (" + (!on ? "opção desligada" : apps.isEmpty() ? "nenhum app" : "sem Bluetooth de áudio") + ")");
            }
            return;
        }
        if (policy == null || policyBt != bt.getId() || policySpk != spk.getId()) {
            drop();
            register(bt, spk);
            if (policy == null) return;
        }
        granting.set(Boolean.TRUE);
        try {
            affinities(apps, spk);
        } finally {
            granting.set(null);
        }
    }

    static void affinities(LinkedHashSet<String> apps, AudioDeviceInfo spk) throws Exception {
        // apps: aplica os novos, tira os que saíram da lista
        android.content.pm.PackageManager pm = ctx.getPackageManager();
        Method set = policy.getClass().getMethod("setUidDeviceAffinity", int.class, List.class);
        Method rem = policy.getClass().getMethod("removeUidDeviceAffinity", int.class);
        for (String pk : new ArrayList<>(applied.keySet())) {
            if (apps.contains(pk)) continue;
            Object r = rem.invoke(policy, applied.remove(pk));
            log(pk + " volta ao Bluetooth (" + r + ")");
        }
        List<AudioDeviceInfo> to = new ArrayList<>();
        to.add(spk);
        for (String pk : apps) {
            if (applied.containsKey(pk)) continue;
            int uid;
            try {
                uid = pm.getPackageUid(pk, 0);
            } catch (Throwable e) {
                continue;   // app desinstalado
            }
            Object r = set.invoke(policy, uid, to);
            if (Boolean.TRUE.equals(r)) applied.put(pk, uid);   // recusado: tenta de novo na próxima volta
            if (!Boolean.TRUE.equals(r) && fails++ > 6) continue;
            log(pk + " (uid " + uid + ") no alto-falante: " + r);
        }
    }

    static void register(AudioDeviceInfo bt, AudioDeviceInfo spk) {
        try {
            Object mixBt = mix(bt), mixSpk = mix(spk);
            Class<?> pb = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
            Object b = pb.getConstructor(Context.class).newInstance(ctx);
            Class<?> mixCls = Class.forName("android.media.audiopolicy.AudioMix");
            Method add = pb.getMethod("addMix", mixCls);
            add.invoke(b, mixBt);
            add.invoke(b, mixSpk);
            Object p = pb.getMethod("build").invoke(b);
            Object r = AudioManager.class.getMethod("registerAudioPolicy", p.getClass()).invoke(am, p);
            if (!(r instanceof Integer) || (Integer) r != 0) {
                log("política recusada pelo sistema: " + r);
                return;
            }
            policy = p;
            policyBt = bt.getId();
            policySpk = spk.getId();
            applied.clear();
            log("ligado: Bluetooth " + bt.getProductName() + " (tipo " + bt.getType() + "), alto-falante id " + spk.getId());
        } catch (Throwable e) {
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
            log("não deu para criar a política: " + c);
        }
    }

    /** Destino de reprodução: mídia, jogos e áudio sem tipo, para um aparelho de saída. */
    static Object mix(AudioDeviceInfo dev) throws Exception {
        Class<?> rb = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Object rule = rb.getConstructor().newInstance();
        Method addRule = rb.getMethod("addRule", AudioAttributes.class, int.class);
        for (int u : new int[]{AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN}) {
            addRule.invoke(rule, new AudioAttributes.Builder().setUsage(u).build(), RULE_MATCH_ATTRIBUTE_USAGE);
        }
        Object built = rb.getMethod("build").invoke(rule);
        Class<?> mb = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Object b = mb.getConstructor(built.getClass()).newInstance(built);
        AudioFormat f = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
        mb.getMethod("setFormat", AudioFormat.class).invoke(b, f);
        mb.getMethod("setRouteFlags", int.class).invoke(b, ROUTE_FLAG_RENDER);
        mb.getMethod("setDevice", AudioDeviceInfo.class).invoke(b, dev);
        return mb.getMethod("build").invoke(b);
    }

    static void drop() {
        if (policy == null) return;
        try {
            AudioManager.class.getMethod("unregisterAudioPolicy", policy.getClass()).invoke(am, policy);
        } catch (Throwable e) {
            try {
                AudioManager.class.getMethod("unregisterAudioPolicyAsync", policy.getClass()).invoke(am, policy);
            } catch (Throwable e2) {
                log("não deu para tirar a política: " + e2);
            }
        }
        policy = null;
        policyBt = policySpk = -1;
        applied.clear();
    }
}
