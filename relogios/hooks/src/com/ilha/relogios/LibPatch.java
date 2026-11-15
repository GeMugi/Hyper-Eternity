package com.ilha.relogios;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Um patch de biblioteca do sistema: uma função pequena (arm64) que devolve uma chave desligada
 * passa a devolver ligada. O patch é feito sobre a cópia da própria biblioteca do aparelho, só
 * quando a função tem exatamente as instruções esperadas; em qualquer outro caso nada é mexido.
 */
public final class LibPatch {
    public final String id, title, summary, path, symbol;
    /** Instruções que valem como "desligado" (uma delas troca de lugar com {@link #on}). */
    final int[] off;
    final int on;

    LibPatch(String id, String title, String summary, String path, String symbol, int[] off, int on) {
        this.id = id;
        this.title = title;
        this.summary = summary;
        this.path = path;
        this.symbol = symbol;
        this.off = off;
        this.on = on;
    }

    static final int RET = 0xd65f03c0, BTI_C = 0xd503245f, PACIASP = 0xd503233f, NOP = 0xd503201f;
    static final int MOV_W0_WZR = 0x2a1f03e0, MOV_W0_0 = 0x52800000, MOV_W0_1 = 0x52800020;

    /** Patches conhecidos. Um novo entra aqui e aparece sozinho na tela da extensão. */
    public static final LibPatch[] ALL = {
            new LibPatch("fonte_variavel",
                    "Fonte variável nativa",
                    "Liga a chave typeface_redesign_readonly do motor de texto: o relógio do HyperOS 4 muda espessura e tamanho sem pesar (o HyperOS 3 vem com ela desligada).",
                    "/system/lib64/aconfig_text_flags_c_lib.so",
                    "com_android_text_flags_typeface_redesign_readonly",
                    new int[]{MOV_W0_WZR, MOV_W0_0}, MOV_W0_1),
    };

    public static LibPatch byId(String id) {
        for (LibPatch p : ALL) if (p.id.equals(id)) return p;
        return null;
    }

    /** Resultado da leitura de uma biblioteca. */
    public static final class Scan {
        public static final int MISSING = 0, UNKNOWN = 1, OFF = 2, ON = 3;
        public int state = MISSING;
        /** Posição (no arquivo) da instrução que muda. */
        public int offset = -1;
        public String detail = "";

        public boolean patchable() { return state == OFF; }
    }

    /** Lê a função na biblioteca (ELF de 64 bits, little-endian) e diz em que estado ela está. */
    public Scan scan(byte[] d) {
        Scan r = new Scan();
        if (d == null) { r.detail = "biblioteca não encontrada"; return r; }
        r.state = Scan.UNKNOWN;
        try {
            ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
            if (d.length < 64 || b.getInt(0) != 0x464c457f || d[4] != 2 || d[5] != 1) { r.detail = "não é uma biblioteca arm64"; return r; }
            if ((b.getShort(0x12) & 0xffff) != 183) { r.detail = "não é arm64"; return r; }
            long shoff = b.getLong(0x28);
            int shentsize = b.getShort(0x3A) & 0xffff, shnum = b.getShort(0x3C) & 0xffff;
            if (shoff <= 0 || shentsize < 64 || shoff + (long) shnum * shentsize > d.length) { r.detail = "tabela de seções inválida"; return r; }
            byte[] want = symbol.getBytes("US-ASCII");
            long addr = -1;
            for (int i = 0; i < shnum && addr < 0; i++) {
                int sh = (int) (shoff + (long) i * shentsize);
                if (b.getInt(sh + 4) != 11) continue;   // SHT_DYNSYM
                long off = b.getLong(sh + 0x18), size = b.getLong(sh + 0x20);
                int link = b.getInt(sh + 0x28);
                if (link < 0 || link >= shnum) continue;
                long strOff = b.getLong((int) (shoff + (long) link * shentsize) + 0x18);
                for (long s = off; s + 24 <= off + size && s + 24 <= d.length; s += 24) {
                    long name = strOff + (b.getInt((int) s) & 0xffffffffL);
                    if (name < 0 || name + want.length >= d.length) continue;
                    boolean eq = d[(int) name + want.length] == 0;
                    for (int k = 0; eq && k < want.length; k++) eq = d[(int) name + k] == want[k];
                    if (!eq) continue;
                    addr = b.getLong((int) s + 8);
                    break;
                }
            }
            if (addr <= 0) { r.detail = "função " + symbol + " não existe nesta biblioteca"; return r; }
            int code = -1;
            for (int j = 0; j < shnum; j++) {
                int t = (int) (shoff + (long) j * shentsize);
                long a = b.getLong(t + 0x10), fo = b.getLong(t + 0x18), sz = b.getLong(t + 0x20);
                if (a == 0 || addr < a || addr >= a + sz) continue;
                code = (int) (addr - a + fo);
                break;
            }
            if (code < 0 || code + 16 > d.length) { r.detail = "endereço da função fora do arquivo"; return r; }
            // aceita só a forma mínima: [bti c | paciasp | nop]* ; mov w0, X ; ret
            int k = 0;
            while (k < 3) {
                int ins = b.getInt(code + 4 * k);
                if (ins == BTI_C || ins == PACIASP || ins == NOP) k++;
                else break;
            }
            int ins = b.getInt(code + 4 * k), next = b.getInt(code + 4 * (k + 1));
            if (next != RET) { r.detail = String.format("função diferente da esperada (%08x %08x)", ins, next); return r; }
            r.offset = code + 4 * k;
            if (ins == on) { r.state = Scan.ON; r.detail = "ligada"; return r; }
            for (int o : off) {
                if (ins == o) { r.state = Scan.OFF; r.detail = "desligada"; return r; }
            }
            r.detail = String.format("instrução inesperada %08x", ins);
            r.offset = -1;
            return r;
        } catch (Throwable t) {
            r.state = Scan.UNKNOWN;
            r.detail = "erro ao ler: " + t;
            return r;
        }
    }

    /** Cópia da biblioteca com a chave ligada; null se ela não estiver no estado esperado. */
    public byte[] apply(byte[] d) {
        Scan s = scan(d);
        if (s.state != Scan.OFF || s.offset < 0) return null;
        byte[] out = d.clone();
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(s.offset, on);
        // confere: só 4 bytes mudaram e agora a função lê "ligada"
        int diff = 0;
        for (int i = 0; i < d.length; i++) if (d[i] != out[i]) diff++;
        if (diff < 1 || diff > 4 || scan(out).state != Scan.ON) return null;
        return out;
    }
}
