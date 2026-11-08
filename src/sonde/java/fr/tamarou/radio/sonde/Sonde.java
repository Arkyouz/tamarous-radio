package fr.tamarou.radio.sonde;

import fr.tamarou.radio.audio.ChunkedHttpStream;
import fr.tamarou.radio.audio.IcyInputStream;
import fr.tamarou.radio.audio.Mp3Source;
import fr.tamarou.radio.audio.PcmSource;
import fr.tamarou.radio.audio.WebmOpusSource;
import fr.tamarou.radio.media.Deezer;
import fr.tamarou.radio.media.Http;
import fr.tamarou.radio.media.RadioBrowser;
import fr.tamarou.radio.media.TrackInfo;
import fr.tamarou.radio.media.YouTubeAudio;

import java.util.List;
import java.util.Map;

/** Test hors du jeu : recherche, son d'une chanson (et reprise à 60 s), puis une radio en direct. */
public final class Sonde {
    public static void main(String[] args) throws Exception {
        String q = args.length > 0 ? args[0] : "angele balance ton quoi";
        long t0 = System.currentTimeMillis();
        List<TrackInfo> res = Deezer.search(q);
        System.out.println("Recherche « " + q + " » : " + res.size() + " résultats en " + (System.currentTimeMillis() - t0) + " ms");
        res.stream().limit(3).forEach(t -> System.out.println("  " + t.display() + " (" + t.durationSec() + " s) " + t.coverUrl()));
        if (res.isEmpty()) return;

        t0 = System.currentTimeMillis();
        TrackInfo t = YouTubeAudio.find(res.getFirst());
        System.out.println("YouTube : " + t.videoId() + " (" + t.durationSec() + " s) en " + (System.currentTimeMillis() - t0) + " ms");
        t0 = System.currentTimeMillis();
        String url = YouTubeAudio.audioUrl(t.videoId());
        System.out.println("Adresse audio en " + (System.currentTimeMillis() - t0) + " ms");
        mesure("Chanson depuis le début", new WebmOpusSource(new ChunkedHttpStream(url), 0), 5);
        mesure("Chanson à partir de 60 s", new WebmOpusSource(new ChunkedHttpStream(url), 60_000), 5);

        var st = RadioBrowser.search("fip", "FR", null);
        System.out.println("Annuaire « fip » : " + st.size() + " radios" + (st.isEmpty() ? "" : ", 1re : " + st.getFirst().name() + " " + st.getFirst().url()));
        String radio = st.isEmpty() ? "https://icecast.radiofrance.fr/fip-midfi.mp3" : st.getFirst().url();
        var r = Http.open(radio, Map.of("Icy-MetaData", "1"));
        int metaInt = r.headers().firstValue("icy-metaint").map(Integer::parseInt).orElse(0);
        var in = new IcyInputStream(r.body(), metaInt, title -> System.out.println("  Titre en direct : " + title));
        mesure("Radio en direct (metaint " + metaInt + ")", new Mp3Source(in, 0), 12);
        System.exit(0);
    }

    private static void mesure(String nom, PcmSource src, int secondes) throws Exception {
        long t0 = System.currentTimeMillis();
        short[] buf = new short[4096];
        long n = 0;
        double sum = 0;
        long want = (long) src.sampleRate() * secondes;
        long firstAt = -1;
        while (n < want) {
            int r = src.read(buf, 0, buf.length);
            if (r < 0) break;
            if (firstAt < 0) firstAt = System.currentTimeMillis() - t0;
            for (int i = 0; i < r; i++) sum += (double) buf[i] * buf[i];
            n += r;
        }
        src.close();
        System.out.printf("%s : %d Hz, %.1f s décodées, volume moyen %.0f, 1er son après %d ms, total %d ms%n",
                nom, src.sampleRate(), n / (double) src.sampleRate(), Math.sqrt(sum / Math.max(1, n)), firstAt,
                System.currentTimeMillis() - t0);
    }
}
