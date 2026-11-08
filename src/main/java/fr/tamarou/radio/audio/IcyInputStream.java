package fr.tamarou.radio.audio;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Flux de radio avec les infos « ICY » : toutes les {@code metaInt} octets de son, la radio glisse
 * un petit texte (StreamTitle='Artiste - Titre';). On le retire du son et on le donne à {@code onTitle}.
 */
public final class IcyInputStream extends FilterInputStream {
    private final int metaInt;
    private final Consumer<String> onTitle;
    private int untilMeta;

    public IcyInputStream(InputStream in, int metaInt, Consumer<String> onTitle) {
        super(in);
        this.metaInt = metaInt;
        this.untilMeta = metaInt;
        this.onTitle = onTitle;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (metaInt <= 0) return super.read(b, off, len);
        if (untilMeta == 0) {
            readMeta();
            untilMeta = metaInt;
        }
        int n = super.read(b, off, Math.min(len, untilMeta));
        if (n > 0) untilMeta -= n;
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        byte[] tmp = new byte[(int) Math.min(n, 8192)];
        int r = read(tmp, 0, tmp.length);
        return Math.max(r, 0);
    }

    private void readMeta() throws IOException {
        int lenByte = in.read();
        if (lenByte <= 0) return;
        byte[] meta = in.readNBytes(lenByte * 16);
        String s = new String(meta, StandardCharsets.UTF_8).trim();
        int i = s.indexOf("StreamTitle='");
        if (i >= 0) {
            int start = i + 13;
            int end = s.indexOf("';", start);
            if (end < 0) end = s.lastIndexOf('\'');
            if (end > start) onTitle.accept(s.substring(start, end).trim());
        }
    }
}
