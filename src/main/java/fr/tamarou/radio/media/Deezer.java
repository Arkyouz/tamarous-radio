package fr.tamarou.radio.media;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recherche de chansons. Deezer donne gratuitement, sans compte, le titre, l'artiste, la pochette et la durée.
 * Un lien Spotify est transformé en « artiste + titre » (lu dans la page publique) puis cherché sur Deezer.
 */
public final class Deezer {
    private static final Pattern DEEZER_TRACK = Pattern.compile("deezer\\.com/(?:[a-z]{2}/)?track/(\\d+)");
    private static final Pattern DEEZER_LIST = Pattern.compile("deezer\\.com/(?:[a-z]{2}/)?(playlist|album)/(\\d+)");
    private static final Pattern DEEZER_SHORT = Pattern.compile("(?:link\\.deezer\\.com|deezer\\.page\\.link)/\\S+");
    private static final Pattern SPOTIFY = Pattern.compile("open\\.spotify\\.com/(?:intl-[a-z]+/)?(track|album|playlist)/([A-Za-z0-9]+)");

    private Deezer() {}

    /** Texte libre, lien Deezer (titre, album, playlist) ou lien Spotify (titre, album, playlist). */
    public static List<TrackInfo> search(String input) throws IOException {
        String q = input.trim();
        if (q.isEmpty()) return List.of();
        if (DEEZER_SHORT.matcher(q).find()) {
            q = resolveRedirect(q);
        }
        Matcher m = DEEZER_TRACK.matcher(q);
        if (m.find()) return List.of(parseTrack(json("https://api.deezer.com/track/" + m.group(1)), null));
        m = DEEZER_LIST.matcher(q);
        if (m.find()) return list("https://api.deezer.com/" + m.group(1) + "/" + m.group(2) + "/tracks?limit=100");
        m = SPOTIFY.matcher(q);
        if (m.find()) return Spotify.tracks(m.group(1), m.group(2));
        return list("https://api.deezer.com/search?limit=25&q=" + Http.enc(q));
    }

    /** La meilleure correspondance Deezer pour « artiste titre » (pochette d'un titre venu de Spotify). */
    public static TrackInfo best(String artist, String title, int durationSec) {
        try {
            List<TrackInfo> r = list("https://api.deezer.com/search?limit=5&q=" + Http.enc(artist + " " + title));
            if (!r.isEmpty()) return r.getFirst();
        } catch (IOException ignored) {
        }
        return new TrackInfo(title, artist, "", "", durationSec, null);
    }

    private static List<TrackInfo> list(String url) throws IOException {
        JsonObject o = json(url);
        JsonArray data = o.getArray("data");
        List<TrackInfo> out = new ArrayList<>();
        // Une liste d'album n'a pas la pochette sur chaque titre : on prend celle de l'album
        String albumCover = null;
        if (url.contains("/album/")) {
            try {
                albumCover = json(url.substring(0, url.indexOf("/tracks"))).getString("cover_medium");
            } catch (IOException ignored) {
            }
        }
        for (Object e : data) {
            if (e instanceof JsonObject t && !"false".equals(String.valueOf(t.get("readable")))) {
                out.add(parseTrack(t, albumCover));
            }
        }
        return out;
    }

    private static TrackInfo parseTrack(JsonObject t, String fallbackCover) {
        JsonObject artist = t.getObject("artist");
        JsonObject album = t.getObject("album");
        String cover = album.getString("cover_medium", fallbackCover == null ? "" : fallbackCover);
        if (cover == null) cover = "";
        return new TrackInfo(
                t.getString("title", "?"),
                artist.getString("name", ""),
                album.getString("title", ""),
                cover,
                t.getInt("duration", 0),
                null);
    }

    private static JsonObject json(String url) throws IOException {
        try {
            JsonObject o = JsonParser.object().from(Http.getText(url));
            if (o.has("error")) throw new IOException("Deezer : " + o.getObject("error").getString("message", "erreur"));
            return o;
        } catch (JsonParserException e) {
            throw new IOException("Réponse Deezer illisible", e);
        }
    }

    private static String resolveRedirect(String shortUrl) throws IOException {
        String url = shortUrl.startsWith("http") ? shortUrl : "https://" + shortUrl;
        try {
            var r = Http.CLIENT.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .header("User-Agent", Http.USER_AGENT).build(), java.net.http.HttpResponse.BodyHandlers.discarding());
            return r.uri().toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
