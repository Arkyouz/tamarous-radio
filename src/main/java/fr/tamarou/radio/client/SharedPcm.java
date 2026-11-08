package fr.tamarou.radio.client;

import fr.tamarou.radio.RadioMod;
import fr.tamarou.radio.audio.PcmSource;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Le son d'une radio, décodé une seule fois et distribué à plusieurs haut-parleurs du jeu (la radio et ses
 * enceintes). Tous les lecteurs suivent la même ligne de temps : ils jouent exactement le même moment,
 * sans écho entre les enceintes. Un fil à part télécharge et décode ; tout est remis à 48 000 Hz, en mono.
 */
public final class SharedPcm {
    public static final int RATE = 48000;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);
    private static final int AHEAD = RATE * 8; // 8 s d'avance au maximum

    private final ArrayDeque<short[]> chunks = new ArrayDeque<>();
    private long base;    // position (en échantillons) du début du premier morceau gardé
    private long written; // position de la fin de ce qui a été décodé
    private final List<Reader> readers = new ArrayList<>();
    private volatile boolean ended, closed;
    private volatile long served;
    private PcmSource source;
    private final LongSupplier untilStart;
    private final Thread thread;

    /**
     * @param untilStart millisecondes avant le top départ : en attendant, silence (la ligne de temps n'avance pas)
     * @param opener     ouvre la source (téléchargement compris), dans le fil de décodage
     * @param onError    appelé avec le message si l'ouverture ou la lecture échoue
     */
    public SharedPcm(String name, LongSupplier untilStart, Callable<PcmSource> opener, Consumer<String> onError) {
        this.untilStart = untilStart;
        thread = new Thread(() -> run(opener, onError), "Radio - " + name);
        thread.setDaemon(true);
        thread.start();
    }

    /** Un nouveau lecteur, à donner à un son du jeu. À créer tous en même temps pour qu'ils restent calés. */
    public synchronized Reader reader() {
        Reader r = new Reader();
        r.pos = base;
        readers.add(r);
        return r;
    }

    /** Échantillons de vrai son déjà joués (le silence d'attente ne compte pas). */
    public long served() {
        return served;
    }

    public boolean ended() {
        return ended;
    }

    public void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
        thread.interrupt();
        closeSource();
    }

    private void run(Callable<PcmSource> opener, Consumer<String> onError) {
        try {
            PcmSource src = opener.call();
            synchronized (this) {
                if (closed) {
                    src.close();
                    return;
                }
                source = src;
            }
            short[] in = new short[4096];
            short[] out = new short[8192];
            double step = src.sampleRate() / (double) RATE;
            double pos = 0;
            short prev = 0;
            while (!closed) {
                int n = src.read(in, 0, in.length);
                if (n < 0) break;
                if (n == 0) continue;
                if (src.sampleRate() == RATE) {
                    push(in, n);
                    continue;
                }
                // Changement de fréquence (44 100 → 48 000 Hz) par interpolation linéaire
                int o = 0;
                while (pos < n - 1) {
                    int i = (int) Math.floor(pos);
                    double f = pos - i;
                    short a = i < 0 ? prev : in[i];
                    short b = in[i + 1];
                    out[o++] = (short) (a + (b - a) * f);
                    if (o == out.length) {
                        push(out, o);
                        o = 0;
                    }
                    pos += step;
                }
                if (o > 0) push(out, o);
                pos -= n;
                prev = in[n - 1];
            }
        } catch (Throwable e) {
            if (!closed) {
                RadioMod.LOGGER.warn("Son impossible à lire : {}", e.toString());
                onError.accept(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        } finally {
            ended = true;
            closeSource();
        }
    }

    private void push(short[] data, int n) throws InterruptedException {
        synchronized (this) {
            while (!closed && written - minPos() > AHEAD) wait(200);
            if (closed) return;
            chunks.addLast(java.util.Arrays.copyOf(data, n));
            written += n;
        }
    }

    private long minPos() {
        if (readers.isEmpty()) return base;
        long m = Long.MAX_VALUE;
        for (Reader r : readers) m = Math.min(m, r.pos);
        return m;
    }

    /** Oublie ce que tous les lecteurs ont déjà joué. */
    private void trim() {
        long m = minPos();
        while (!chunks.isEmpty() && base + chunks.peekFirst().length <= m) base += chunks.removeFirst().length;
        notifyAll();
    }

    private void closeSource() {
        PcmSource s;
        synchronized (this) {
            s = source;
            source = null;
        }
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Un lecteur : ce que Minecraft lit pour un de ses sons (la radio ou une enceinte). */
    public final class Reader implements AudioStream {
        private long pos;
        private long real; // échantillons de vrai son donnés par ce lecteur

        @Override
        public AudioFormat getFormat() {
            return FORMAT;
        }

        @Override
        public ByteBuffer read(int bytes) {
            int want = Math.max(2, bytes / 2);
            long wait = untilStart.getAsLong();
            if (wait > 0 && !closed) {
                // Juste assez de silence pour tomber pile sur le top départ
                int n = (int) Math.max(1, Math.min(want, Math.min(RATE / 5, wait * RATE / 1000)));
                return BufferUtils.createByteBuffer(n * 2);
            }
            synchronized (SharedPcm.this) {
                if (pos < base) pos = base;
                long avail = written - pos;
                if (avail <= 0) {
                    if (ended || closed) return null; // fin : Minecraft arrête le son
                    // Pas encore prêt : un peu de silence, sans avancer (toutes les enceintes attendent ensemble)
                    int n = Math.min(want, RATE / 5);
                    return BufferUtils.createByteBuffer(n * 2);
                }
                int n = (int) Math.min(want, avail);
                ByteBuffer b = BufferUtils.createByteBuffer(n * 2);
                // Recopie depuis les morceaux, à partir de pos
                long chunkStart = base;
                int left = n;
                long p = pos;
                for (short[] c : chunks) {
                    long chunkEnd = chunkStart + c.length;
                    if (p < chunkEnd && left > 0) {
                        int from = (int) (p - chunkStart);
                        int count = (int) Math.min(left, c.length - from);
                        for (int i = 0; i < count; i++) b.putShort(c[from + i]);
                        p += count;
                        left -= count;
                    }
                    chunkStart = chunkEnd;
                    if (left == 0) break;
                }
                pos += n;
                real += n;
                served = Math.max(served, real);
                trim();
                b.flip();
                return b;
            }
        }

        @Override
        public void close() {
            synchronized (SharedPcm.this) {
                readers.remove(this);
                trim();
            }
        }
    }
}
