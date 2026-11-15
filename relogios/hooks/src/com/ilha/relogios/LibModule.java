package com.ilha.relogios;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * O módulo Magisk/KernelSU/APatch com as bibliotecas corrigidas. Ele é montado no próprio aparelho,
 * a partir das bibliotecas do próprio sistema, e confere a cada partida se o sistema mudou (uma
 * atualização da ROM): se mudou, ele se desliga sozinho antes de pôr a cópia velha no lugar.
 */
public final class LibModule {
    private LibModule() {}

    public static final String ID = "hyper_eternity_libs";
    static final String DIR = "/data/adb/modules/" + ID, UPD = "/data/adb/modules_update/" + ID;

    /** Uma biblioteca a pôr no módulo. */
    public static final class Item {
        final LibPatch patch;
        final byte[] orig, patched;
        final String origSha, newSha;

        Item(LibPatch p, byte[] orig, byte[] patched) {
            this.patch = p;
            this.orig = orig;
            this.patched = patched;
            this.origSha = sha256(orig);
            this.newSha = sha256(patched);
        }
    }

    public static byte[] read(String path) {
        File f = new File(path);
        if (!f.isFile() || !f.canRead() || f.length() > 64L * 1024 * 1024) return null;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    public static String sha256(byte[] d) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(d);
            StringBuilder b = new StringBuilder();
            for (byte x : h) b.append(String.format("%02x", x & 0xff));
            return b.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    static String rel(LibPatch p) {
        return p.path.startsWith("/") ? p.path.substring(1) : p.path;
    }

    /**
     * Prepara os itens a partir das bibliotecas atuais; os que não puderem ser corrigidos ficam fora
     * (com o motivo em "why"). Com o módulo já montado, o sistema mostra a nossa cópia: a original
     * vem da cópia guardada dentro do módulo (pasta orig, que não é montada).
     */
    public static List<Item> prepare(List<LibPatch> wanted, StringBuilder why, boolean root) {
        ArrayList<Item> out = new ArrayList<Item>();
        if (Hos.isHos4()) {
            why.append("HyperOS 4: as bibliotecas já vêm com isto ligado\n");
            return out;
        }
        for (LibPatch p : wanted) {
            byte[] orig = read(p.path);
            LibPatch.Scan s = p.scan(orig);
            if (s.state == LibPatch.Scan.ON && root) {
                byte[] saved = suRead(DIR + "/orig/" + rel(p));
                if (saved != null && p.scan(saved).state == LibPatch.Scan.OFF) {
                    orig = saved;
                    s = p.scan(orig);
                }
            }
            if (s.state != LibPatch.Scan.OFF) {
                why.append(p.title).append(": ").append(s.state == LibPatch.Scan.ON ? "já está ligada no sistema" : s.detail).append('\n');
                continue;
            }
            byte[] np = p.apply(orig);
            if (np == null) { why.append(p.title).append(": não consegui gerar a cópia\n"); continue; }
            out.add(new Item(p, orig, np));
        }
        return out;
    }

    static final String UPDATE_BINARY =
            "#!/sbin/sh\n" +
            "umask 022\n" +
            "ui_print() { echo \"$1\"; }\n" +
            "require_new_magisk() {\n" +
            "  ui_print \"*******************************\"\n" +
            "  ui_print \" Please install Magisk v20.4+! \"\n" +
            "  ui_print \"*******************************\"\n" +
            "  exit 1\n" +
            "}\n" +
            "OUTFD=$2\n" +
            "ZIPFILE=$3\n" +
            "mount /data 2>/dev/null\n" +
            "[ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk\n" +
            ". /data/adb/magisk/util_functions.sh\n" +
            "[ $MAGISK_VER_CODE -lt 20400 ] && require_new_magisk\n" +
            "install_module\n" +
            "exit 0\n";

    /**
     * Antes de montar: confere cada biblioteca do sistema. Se não for mais a original (nem a nossa,
     * caso o gerenciador já tenha montado) ou se a versão do sistema mudou, tira as cópias e se desliga.
     */
    static final String POST_FS_DATA =
            "#!/system/bin/sh\n" +
            "MODDIR=${0%/*}\n" +
            "ok=1\n" +
            "why=\"\"\n" +
            "fp=$(getprop ro.build.fingerprint)\n" +
            "[ \"$fp\" = \"$(cat \"$MODDIR/fingerprint\" 2>/dev/null)\" ] || { ok=0; why=\"versão do sistema mudou\"; }\n" +
            "while read -r rel orig new; do\n" +
            "  [ -z \"$rel\" ] && continue\n" +
            "  cur=$(sha256sum \"/$rel\" 2>/dev/null | cut -d' ' -f1)\n" +
            "  [ \"$cur\" = \"$orig\" ] || [ \"$cur\" = \"$new\" ] || { ok=0; why=\"biblioteca /$rel mudou\"; }\n" +
            "done < \"$MODDIR/patches.txt\"\n" +
            "if [ $ok = 1 ]; then\n" +
            "  echo ok > \"$MODDIR/status\"\n" +
            "else\n" +
            "  rm -rf \"$MODDIR/system\"\n" +
            "  touch \"$MODDIR/disable\"\n" +
            "  echo \"desligado: $why\" > \"$MODDIR/status\"\n" +
            "  sed -i \"s|^description=.*|description=Desligado sozinho ($why). Abra o Hyper Eternity Relógios para gerar de novo.|\" \"$MODDIR/module.prop\"\n" +
            "fi\n";

    /** Monta o zip do módulo. */
    public static void writeZip(List<Item> items, String fingerprint, int versionCode, OutputStream os) throws Exception {
        ZipOutputStream z = new ZipOutputStream(os);
        StringBuilder names = new StringBuilder(), list = new StringBuilder();
        for (Item it : items) {
            names.append(names.length() > 0 ? ", " : "").append(it.patch.title);
            String rel = it.patch.path.startsWith("/") ? it.patch.path.substring(1) : it.patch.path;
            list.append(rel).append(' ').append(it.origSha).append(' ').append(it.newSha).append('\n');
        }
        put(z, "module.prop", ("id=" + ID + "\n" +
                "name=Hyper Eternity · bibliotecas\n" +
                "version=" + versionCode + "\n" +
                "versionCode=" + versionCode + "\n" +
                "author=Hyper Eternity Relógios\n" +
                "description=Gerado neste aparelho a partir das bibliotecas dele: " + names + ". Desliga sozinho se o sistema for atualizado.\n").getBytes("UTF-8"));
        put(z, "META-INF/com/google/android/update-binary", UPDATE_BINARY.getBytes("UTF-8"));
        put(z, "META-INF/com/google/android/updater-script", "#MAGISK\n".getBytes("UTF-8"));
        put(z, "post-fs-data.sh", POST_FS_DATA.getBytes("UTF-8"));
        put(z, "patches.txt", list.toString().getBytes("UTF-8"));
        put(z, "fingerprint", (fingerprint == null ? "" : fingerprint).getBytes("UTF-8"));
        put(z, "customize.sh", ("set_perm \"$MODPATH/post-fs-data.sh\" 0 0 0755\n" +
                "for f in $(find \"$MODPATH/system\" -type f); do set_perm \"$f\" 0 0 0644 u:object_r:system_lib_file:s0; done\n").getBytes("UTF-8"));
        for (Item it : items) {
            put(z, rel(it.patch), it.patched);
            put(z, "orig/" + rel(it.patch), it.orig);   // a original, para gerar de novo com o módulo montado
        }
        z.finish();
        z.flush();
    }

    static void put(ZipOutputStream z, String name, byte[] data) throws Exception {
        ZipEntry e = new ZipEntry(name);
        e.setTime(0L);
        z.putNextEntry(e);
        z.write(data);
        z.closeEntry();
    }

    public static File writeZip(File dir, List<Item> items, String fingerprint, int versionCode) throws Exception {
        File f = new File(dir, ID + ".zip");
        OutputStream o = new FileOutputStream(f);
        try {
            writeZip(items, fingerprint, versionCode, o);
        } finally {
            o.close();
        }
        f.setReadable(true, false);
        return f;
    }

    // ------------------------------------------------------------------ root

    /** Resultado de um comando com root. */
    public static final class Res {
        public int code = -1;
        public String out = "";
        public boolean ok() { return code == 0; }
    }

    public static Res su(String script) {
        Res r = new Res();
        Process p = null;
        try {
            p = new ProcessBuilder("su").redirectErrorStream(true).start();
            OutputStream o = p.getOutputStream();
            o.write((script + "\nexit\n").getBytes("UTF-8"));
            o.flush();
            o.close();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            r.code = p.waitFor();
            r.out = b.toString("UTF-8").trim();
        } catch (Throwable t) {
            r.code = -1;
            r.out = String.valueOf(t);
        } finally {
            if (p != null) p.destroy();
        }
        return r;
    }

    /** Estado do módulo no aparelho (precisa de root). */
    public static final class State {
        public boolean root, installed, pending, disabled, removing;
        public String manager = "", status = "", description = "";
    }

    /** Lê um arquivo com root (null se não der). */
    public static byte[] suRead(String path) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", "cat '" + path.replace("'", "") + "'").start();
            p.getOutputStream().close();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return p.waitFor() == 0 && b.size() > 0 ? b.toByteArray() : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** Patches que estão no módulo instalado (lidos do patches.txt dele). */
    public static java.util.Set<String> inModule() {
        java.util.HashSet<String> r = new java.util.HashSet<String>();
        byte[] d = suRead(DIR + "/patches.txt");
        if (d == null) return r;
        try {
            for (String l : new String(d, "UTF-8").split("\n")) {
                String path = "/" + l.trim().split(" ")[0];
                for (LibPatch p : LibPatch.ALL) if (p.path.equals(path)) r.add(p.id);
            }
        } catch (Throwable ignored) {
        }
        return r;
    }

    public static State state() {
        State s = new State();
        Res r = su("for m in magisk ksud apd; do command -v $m >/dev/null 2>&1 && { echo \"mgr=$m\"; break; }; done\n" +
                "[ -d " + DIR + " ] && echo installed=1\n" +
                "[ -d " + UPD + " ] && echo pending=1\n" +
                "[ -f " + DIR + "/disable ] && echo disabled=1\n" +
                "[ -f " + DIR + "/remove ] && echo removing=1\n" +
                "[ -f " + DIR + "/status ] && echo \"status=$(cat " + DIR + "/status)\"\n" +
                "echo root=1\n");
        if (!r.out.contains("root=1")) return s;
        s.root = true;
        for (String l : r.out.split("\n")) {
            int i = l.indexOf('=');
            if (i < 0) continue;
            String k = l.substring(0, i), v = l.substring(i + 1);
            if (k.equals("mgr")) s.manager = v;
            else if (k.equals("installed")) s.installed = true;
            else if (k.equals("pending")) s.pending = true;
            else if (k.equals("disabled")) s.disabled = true;
            else if (k.equals("removing")) s.removing = true;
            else if (k.equals("status")) s.status = v;
        }
        return s;
    }

    /** Instala o zip pelo gerenciador de root que existir. */
    public static Res install(File zip) {
        if (Hos.isHos4()) {
            Res r = new Res();
            r.out = "HyperOS 4: as bibliotecas já vêm com isto ligado, nada a instalar";
            return r;
        }
        String z = zip.getAbsolutePath();
        return su("Z=/data/local/tmp/" + ID + ".zip\n" +
                "cp '" + z + "' $Z && chmod 644 $Z || exit 3\n" +
                "rm -f " + DIR + "/remove " + DIR + "/disable\n" +
                "if command -v magisk >/dev/null 2>&1; then magisk --install-module $Z; R=$?\n" +
                "elif command -v ksud >/dev/null 2>&1; then ksud module install $Z; R=$?\n" +
                "elif command -v apd >/dev/null 2>&1; then apd module install $Z; R=$?\n" +
                "else echo 'nenhum gerenciador de root (Magisk, KernelSU, APatch) encontrado'; R=4; fi\n" +
                "rm -f $Z\n" +
                "exit $R");
    }

    /** Marca o módulo para sair na próxima partida (o sistema volta às bibliotecas originais). */
    public static Res remove() {
        return su("[ -d " + UPD + " ] && rm -rf " + UPD + "\n" +
                "[ -d " + DIR + " ] && touch " + DIR + "/remove\n" +
                "exit 0");
    }

    public static Res reboot() {
        return su("svc power reboot || reboot");
    }
}
