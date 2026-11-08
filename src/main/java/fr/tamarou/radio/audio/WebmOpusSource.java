package fr.tamarou.radio.audio;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Piste audio Opus dans un conteneur WebM (le son des chansons). Petit lecteur EBML maison :
 * on ne garde que l'en-tête Opus et les blocs audio, tout le reste est sauté.
 */
public final class WebmOpusSource implements PcmSource {
    private static final int SEGMENT = 0x18538067, CLUSTER = 0x1F43B675, BLOCK_GROUP = 0xA0,
            TRACKS = 0x1654AE6B, TRACK_ENTRY = 0xAE, INFO = 0x1549A966,
            TIMECODE = 0xE7, SIMPLE_BLOCK = 0xA3, BLOCK = 0xA1, CODEC_PRIVATE = 0x63A2, TIMECODE_SCALE = 0x2AD7B1;
    private static final int RATE = 48000;

    private final InputStream in;
    private OpusDecoder decoder;
    private int channels = 2;
    private int preSkip;
    private long scaleNs = 1_000_000L;
    private long clusterTime;
    private final long skipMs;
    private long discard; // échantillons décodés à jeter (pré-saut Opus + reprise en cours de chanson)
    private boolean started;

    private short[] pcm = new short[5760 * 2];
    private short[] buf = new short[5760];
    private int bufPos, bufLen;

    public WebmOpusSource(InputStream raw, long skipMs) {
        this.in = raw instanceof BufferedInputStream ? raw : new BufferedInputStream(raw, 64 * 1024);
        this.skipMs = Math.max(0, skipMs);
    }

    @Override
    public int sampleRate() {
        return RATE;
    }

    @Override
    public int read(short[] out, int off, int len) throws IOException {
        while (bufPos >= bufLen) {
            if (!nextBlock()) return -1;
        }
        int n = Math.min(len, bufLen - bufPos);
        System.arraycopy(buf, bufPos, out, off, n);
        bufPos += n;
        return n;
    }

    /** Lit des éléments jusqu'au prochain bloc audio décodé. */
    private boolean nextBlock() throws IOException {
        while (true) {
            int id;
            try {
                id = readId();
            } catch (EOFException e) {
                return false;
            }
            long size = readSize();
            switch (id) {
                case SEGMENT, CLUSTER, BLOCK_GROUP, TRACKS, TRACK_ENTRY, INFO -> { /* on entre dedans */ }
                case TIMECODE -> clusterTime = readUInt((int) size);
                case TIMECODE_SCALE -> scaleNs = readUInt((int) size);
                case CODEC_PRIVATE -> readOpusHead(readBytes((int) size));
                case SIMPLE_BLOCK, BLOCK -> {
                    if (decodeBlock(readBytes((int) size))) return true;
                }
                default -> skip(size);
            }
        }
    }

    private void readOpusHead(byte[] h) {
        // "OpusHead", version, canaux, pré-saut (petit-boutiste)
        if (h.length >= 12 && h[0] == 'O' && h[1] == 'p') {
            channels = Math.max(1, h[9] & 0xFF);
            preSkip = (h[10] & 0xFF) | ((h[11] & 0xFF) << 8);
        }
    }

    private boolean decodeBlock(byte[] b) throws IOException {
        int p = 0;
        int first = b[p] & 0xFF;
        int len = Integer.numberOfLeadingZeros(first) - 23;
        p += len; // numéro de piste (une seule piste audio)
        short rel = (short) (((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF));
        p += 3; // temps relatif + drapeaux
        long timeMs = (clusterTime + rel) * scaleNs / 1_000_000L;
        if (!started) {
            // Les blocs bien avant le moment voulu sont sautés sans les décoder
            if (timeMs < skipMs - 300) return false;
            started = true;
            try {
                decoder = new OpusDecoder(RATE, channels);
            } catch (OpusException e) {
                throw new IOException("Opus : " + e.getMessage(), e);
            }
            long startSample = timeMs * RATE / 1000;
            discard = Math.max(0, skipMs * RATE / 1000 - startSample) + (startSample == 0 ? preSkip : 0);
        }
        if (pcm.length < 5760 * channels) pcm = new short[5760 * channels];
        int frames;
        try {
            frames = decoder.decode(b, p, b.length - p, pcm, 0, 5760, false);
        } catch (OpusException e) {
            return false; // trame abîmée : on continue
        }
        int start = 0;
        if (discard > 0) {
            int d = (int) Math.min(discard, frames);
            discard -= d;
            start = d;
        }
        if (start >= frames) return false;
        if (buf.length < frames) buf = new short[frames];
        bufLen = 0;
        for (int i = start; i < frames; i++) buf[bufLen++] = PcmSource.mix(pcm, i, channels);
        bufPos = 0;
        return bufLen > 0;
    }

    private int readId() throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        int len = Integer.numberOfLeadingZeros(b) - 23;
        if (len < 1 || len > 4) throw new IOException("WebM abîmé");
        int v = b;
        for (int i = 1; i < len; i++) v = (v << 8) | readByte();
        return v;
    }

    private long readSize() throws IOException {
        int b = readByte();
        int len = Integer.numberOfLeadingZeros(b) - 23;
        if (len < 1 || len > 8) throw new IOException("WebM abîmé");
        long v = b & (0xFF >> len);
        boolean allOnes = v == (0xFF >> len);
        for (int i = 1; i < len; i++) {
            int n = readByte();
            if (n != 0xFF) allOnes = false;
            v = (v << 8) | n;
        }
        return allOnes ? -1 : v;
    }

    private long readUInt(int size) throws IOException {
        long v = 0;
        for (int i = 0; i < size; i++) v = (v << 8) | readByte();
        return v;
    }

    private byte[] readBytes(int size) throws IOException {
        if (size < 0 || size > 16 * 1024 * 1024) throw new IOException("WebM abîmé");
        return in.readNBytes(size);
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private void skip(long size) throws IOException {
        if (size < 0) return;
        long left = size;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) throw new EOFException();
                s = 1;
            }
            left -= s;
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
