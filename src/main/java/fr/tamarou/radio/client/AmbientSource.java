package fr.tamarou.radio.client;

import fr.tamarou.radio.audio.PcmSource;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.util.Map;

/** Les musiques d'ambiance de Minecraft, au hasard et sans fin (chaque joueur a son propre tirage). */
public final class AmbientSource implements PcmSource {
    public static final Map<String, String> EVENTS = Map.of(
            "overworld", "music.game",
            "creative", "music.creative",
            "menu", "music.menu",
            "underwater", "music.under_water",
            "end", "music.end",
            "nether", "music.nether.nether_wastes");

    private final ResourceLocation event;
    private OggSource current;
    private int silence; // un petit blanc entre deux morceaux

    public AmbientSource(String key) throws IOException {
        event = ResourceLocation.withDefaultNamespace(EVENTS.getOrDefault(key, "music.game"));
        current = new OggSource(RadioClient.openSoundFile(event), 0);
    }

    @Override
    public int sampleRate() {
        return current.sampleRate();
    }

    @Override
    public int read(short[] out, int off, int len) throws IOException {
        if (silence > 0) {
            int n = Math.min(len, silence);
            java.util.Arrays.fill(out, off, off + n, (short) 0);
            silence -= n;
            return n;
        }
        int n = current.read(out, off, len);
        if (n >= 0) return n;
        current.close();
        OggSource next = new OggSource(RadioClient.openSoundFile(event), 0);
        // Les musiques du jeu sont toutes à 44 100 Hz : on garde la même fréquence
        current = next;
        silence = sampleRate() * 4;
        return read(out, off, len);
    }

    @Override
    public void close() throws IOException {
        current.close();
    }
}
