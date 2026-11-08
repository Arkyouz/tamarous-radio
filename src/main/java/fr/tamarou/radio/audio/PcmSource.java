package fr.tamarou.radio.audio;

import java.io.Closeable;
import java.io.IOException;

/**
 * Du son décodé : échantillons 16 bits en <b>mono</b> (OpenAL ne place dans l'espace que le son mono,
 * c'est ce qui fait que la radio s'entend moins quand on s'éloigne).
 */
public interface PcmSource extends Closeable {
    int sampleRate();

    /** Remplit {@code out} ; renvoie le nombre d'échantillons, ou -1 à la fin. */
    int read(short[] out, int off, int len) throws IOException;

    /** Mélange des canaux en un seul. */
    static short mix(short[] in, int frame, int channels) {
        if (channels == 1) return in[frame];
        int sum = 0;
        for (int c = 0; c < channels; c++) sum += in[frame * channels + c];
        return (short) (sum / channels);
    }
}
