package fr.tamarou.radio.media;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Lit un lien Spotify sans compte : la page publique « embed » contient le titre, les artistes,
 * la durée et la pochette. Spotify ne donne jamais le son ; on ne garde que ces infos.
 */
final class Spotify {
    private Spotify() {}

    static List<TrackInfo> tracks(String type, String id) throws IOException {
        JsonObject entity = entity(type, id);
        if ("track".equals(type)) {
            return List.of(new TrackInfo(
                    entity.getString("name", entity.getString("title", "?")),
                    artists(entity.getArray("artists")),
                    "",
                    image(entity),
                    entity.getInt("duration", 0) / 1000,
                    null));
        }
        // Album ou playlist : la liste n'a pas de pochette par titre, on prend celle de la liste
        String cover = image(entity);
        String album = "album".equals(type) ? entity.getString("name", "") : "";
        List<TrackInfo> out = new ArrayList<>();
        for (Object o : entity.getArray("trackList")) {
            if (o instanceof JsonObject t && t.getBoolean("isPlayable", true)) {
                out.add(new TrackInfo(t.getString("title", "?"), t.getString("subtitle", ""), album, cover,
                        t.getInt("duration", 0) / 1000, null));
            }
        }
        return out;
    }

    private static JsonObject entity(String type, String id) throws IOException {
        String html = Http.getText("https://open.spotify.com/embed/" + type + "/" + id);
        int i = html.indexOf("__NEXT_DATA__");
        if (i < 0) throw new IOException("Page Spotify inattendue");
        int start = html.indexOf('>', i) + 1;
        int end = html.indexOf("</script>", start);
        try {
            JsonObject root = JsonParser.object().from(html.substring(start, end));
            return root.getObject("props").getObject("pageProps").getObject("state").getObject("data").getObject("entity");
        } catch (JsonParserException | RuntimeException e) {
            throw new IOException("Page Spotify illisible", e);
        }
    }

    private static String artists(JsonArray a) {
        List<String> names = new ArrayList<>();
        if (a != null) for (Object o : a) if (o instanceof JsonObject j) names.add(j.getString("name", ""));
        return String.join(", ", names);
    }

    private static String image(JsonObject e) {
        JsonArray imgs = null;
        if (e.has("visualIdentity")) imgs = e.getObject("visualIdentity").getArray("image");
        if ((imgs == null || imgs.isEmpty()) && e.has("coverArt")) imgs = e.getObject("coverArt").getArray("sources");
        if (imgs == null || imgs.isEmpty()) return "";
        // La plus petite au-dessus de 200 px, sinon la première
        String best = imgs.getObject(0).getString("url", "");
        for (Object o : imgs) {
            if (o instanceof JsonObject j && j.getInt("maxWidth", 0) >= 200 && j.getInt("maxWidth", 0) <= 320) {
                best = j.getString("url", best);
            }
        }
        return best;
    }
}
