package fr.tamarou.radio.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

/** MP3 (la plupart des radios, les fichiers du serveur). */
public final class Mp3Source implements PcmSource {
    private final InputStream in;
    private final Bitstream bits;
    private final Decoder decoder = new Decoder();
    private final int rate;
    private Header pending;
    private short[] buf = new short[0];
    private int bufPos, bufLen;

    public Mp3Source(InputStream raw, long skipMs) throws IOException {
        this.in = raw instanceof BufferedInputStream ? raw : new BufferedInputStream(raw, 64 * 1024);
        this.bits = new Bitstream(in);
        try {
            double done = 0;
            Header h = bits.readFrame();
            if (h == null) throw new IOException("Pas de son MP3");
            // Avance sans décoder jusqu'au moment demandé (quelqu'un a lancé la chanson avant nous)
            while (h != null && done + h.ms_per_frame() < skipMs) {
                done += h.ms_per_frame();
                bits.closeFrame();
                h = bits.readFrame();
            }
            if (h == null) throw new IOException("Fin du fichier");
            pending = h;
            rate = h.frequency();
        } catch (BitstreamException e) {
            throw new IOException("MP3 illisible", e);
        }
    }

    @Override
    public int sampleRate() {
        return rate;
    }

    @Override
    public int read(short[] out, int off, int len) throws IOException {
        while (bufPos >= bufLen) {
            if (!decodeNext()) return -1;
        }
        int n = Math.min(len, bufLen - bufPos);
        System.arraycopy(buf, bufPos, out, off, n);
        bufPos += n;
        return n;
    }

    private boolean decodeNext() throws IOException {
        try {
            Header h = pending != null ? pending : bits.readFrame();
            pending = null;
            if (h == null) return false;
            SampleBuffer sb;
            try {
                sb = (SampleBuffer) decoder.decodeFrame(h, bits);
            } catch (DecoderException | ArrayIndexOutOfBoundsException e) {
                // Une trame abîmée (flux radio coupé) : on passe à la suivante
                bits.closeFrame();
                bufPos = bufLen = 0;
                return true;
            }
            int ch = sb.getChannelCount();
            int frames = sb.getBufferLength() / ch;
            if (buf.length < frames) buf = new short[frames];
            short[] s = sb.getBuffer();
            for (int i = 0; i < frames; i++) buf[i] = PcmSource.mix(s, i, ch);
            bufPos = 0;
            bufLen = frames;
            bits.closeFrame();
            return true;
        } catch (BitstreamException e) {
            throw new IOException("MP3 coupé", e);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            bits.close();
        } catch (BitstreamException ignored) {
        }
        in.close();
    }
}
