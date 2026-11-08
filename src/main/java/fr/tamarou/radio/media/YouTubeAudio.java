package fr.tamarou.radio.media;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Le son des chansons : on cherche la chanson sur YouTube Music (comme les bots Discord) et on lit sa piste
 * audio Opus. YouTube n'apparaît jamais dans le jeu.
 */
public final class YouTubeAudio {
    public static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static volatile boolean ready;

    private YouTubeAudio() {}

    private static void init() {
        if (ready) return;
        synchronized (YouTubeAudio.class) {
            if (ready) return;
            NewPipe.init(new JavaDownloader(), new Localization("fr", "FR"), new ContentCountry("FR"));
            ready = true;
        }
    }

    /** Trouve la vidéo qui correspond le mieux (durée la plus proche). Renvoie la piste avec son id et sa durée. */
    public static TrackInfo find(TrackInfo t) throws IOException {
        init();
        String q = (t.artist() + " " + t.title()).trim();
        StreamInfoItem best = null;
        long bestScore = Long.MAX_VALUE;
        // D'abord les « titres » YouTube Music (versions officielles), puis la recherche normale
        for (String filter : List.of(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS, YoutubeSearchQueryHandlerFactory.VIDEOS)) {
            List<InfoItem> items;
            try {
                items = SearchInfo.getInfo(ServiceList.YouTube,
                        ServiceList.YouTube.getSearchQHFactory().fromQuery(q, List.of(filter), "")).getRelatedItems();
            } catch (Exception e) {
                continue;
            }
            int rank = 0;
            for (InfoItem it : items) {
                if (!(it instanceof StreamInfoItem s) || s.getDuration() <= 0) continue;
                if (rank++ >= 6) break;
                long score = t.durationSec() > 0 ? Math.abs(s.getDuration() - t.durationSec()) : 0;
                score = score * 10 + rank; // à durée égale, le premier résultat gagne
                String name = s.getName().toLowerCase(Locale.ROOT);
                if (name.contains("live") && !t.title().toLowerCase(Locale.ROOT).contains("live")) score += 300;
                if (score < bestScore) {
                    bestScore = score;
                    best = s;
                }
            }
            if (best != null && bestScore < 60) break; // à moins de ~5 s : bonne version
        }
        if (best == null) throw new IOException("Chanson introuvable : " + q);
        String id = idFromUrl(best.getUrl());
        return t.withVideo(id, (int) best.getDuration());
    }

    /** L'adresse de la piste audio Opus (WebM) d'une vidéo. Elle expire après quelques heures. */
    public static String audioUrl(String videoId) throws IOException {
        init();
        if (!VIDEO_ID.matcher(videoId).matches()) throw new IOException("Identifiant invalide");
        StreamInfo info;
        try {
            info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + videoId);
        } catch (Exception e) {
            throw new IOException("YouTube : " + e.getMessage(), e);
        }
        AudioStream best = null;
        for (AudioStream a : info.getAudioStreams()) {
            if (a.getFormat() != MediaFormat.WEBMA_OPUS || a.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP || !a.isUrl()) continue;
            // Piste d'origine (pas un doublage automatique)
            if (a.getAudioTrackType() != null && a.getAudioTrackType().name().equals("DUBBED")) continue;
            if (best == null || a.getAverageBitrate() > best.getAverageBitrate()) best = a;
        }
        if (best == null) throw new IOException("Pas de piste audio lisible");
        return best.getContent();
    }

    private static String idFromUrl(String url) {
        int i = url.indexOf("v=");
        String id = i >= 0 ? url.substring(i + 2) : url.substring(url.lastIndexOf('/') + 1);
        return id.length() > 11 ? id.substring(0, 11) : id;
    }

    /** NewPipe a besoin de quelqu'un pour faire ses requêtes : on lui prête le client HTTP de Java. */
    private static final class JavaDownloader extends Downloader {
        private static final Set<String> RESTRICTED = Set.of("host", "connection", "content-length", "expect", "upgrade");

        @Override
        public Response execute(Request request) throws IOException {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(request.url())).timeout(Duration.ofSeconds(20));
            byte[] data = request.dataToSend();
            b.method(request.httpMethod(), data == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(data));
            boolean ua = false;
            for (var e : request.headers().entrySet()) {
                if (RESTRICTED.contains(e.getKey().toLowerCase(Locale.ROOT))) continue;
                if (e.getKey().equalsIgnoreCase("user-agent")) ua = true;
                for (String v : e.getValue()) b.header(e.getKey(), v);
            }
            if (!ua) b.header("User-Agent", Http.USER_AGENT);
            try {
                HttpResponse<byte[]> r = Http.CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
                return new Response(r.statusCode(), "", r.headers().map(),
                        new String(r.body(), StandardCharsets.UTF_8), r.uri().toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrompu", e);
            }
        }
    }
}
