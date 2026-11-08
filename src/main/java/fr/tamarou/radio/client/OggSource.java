package fr.tamarou.radio.client;

import fr.tamarou.radio.audio.PcmSource;
import net.minecraft.client.sounds.JOrbisAudioStream;

import java.io.IOException;
import java.io.InputStream;

/** OGG Vorbis (musiques et disques de Minecraft, certaines radios) avec le décodeur du jeu. */
public final class OggSource implements PcmSource {
    private final JOrbisAudioStream ogg;
    private final int channels;
    private final int rate;
    private long toSkip;
    private float[] frame;
    private int inFrame;
    private short[] buf = new short[8192];
    private int bufLen, bufPos;

    public OggSource(InputStream in, long skipMs) throws IOException {
        ogg = new JOrbisAudioStream(in);
        channels = Math.max(1, ogg.getFormat().getChannels());
        rate = (int) ogg.getFormat().getSampleRate();
        toSkip = skipMs * rate / 1000;
        frame = new float[channels];
    }

    @Override
    public int sampleRate() {
        return rate;
    }

    @Override
    public int read(short[] out, int off, int len) throws IOException {
        while (bufPos >= bufLen) {
            bufPos = bufLen = 0;
            boolean more = ogg.readChunk(f -> {
                frame[inFrame++] = f;
                if (inFrame == channels) {
                    inFrame = 0;
                    if (toSkip > 0) {
                        toSkip--;
                        return;
                    }
                    float sum = 0;
                    for (float v : frame) sum += v;
                    int s = Math.round(sum / channels * 32767f);
                    if (bufLen == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                    buf[bufLen++] = (short) Math.max(-32768, Math.min(32767, s));
                }
            });
            if (!more && bufLen == 0) return -1;
        }
        int n = Math.min(len, bufLen - bufPos);
        System.arraycopy(buf, bufPos, out, off, n);
        bufPos += n;
        return n;
    }

    @Override
    public void close() throws IOException {
        ogg.close();
    }
}
