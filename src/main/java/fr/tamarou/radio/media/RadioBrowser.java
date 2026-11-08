package fr.tamarou.radio.media;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Annuaire gratuit des radios du monde entier (radio-browser.info). On ne garde que le MP3 et l'OGG. */
public final class RadioBrowser {
    /** Plusieurs serveurs de l'annuaire : si l'un ne répond pas, on essaie le suivant (« all » choisit un serveur en service). */
    private static final String[] SERVERS = {"de2.api.radio-browser.info", "de1.api.radio-browser.info", "all.api.radio-browser.info"};

    public record Station(String name, String url, String country, String tags, String codec) {}

    private RadioBrowser() {}

    /** Les radios les plus écoutées d'un pays (code « FR », « BE »…), ou une recherche par nom ou genre. */
    public static List<Station> search(String name, String countryCode, String tag) throws IOException {
        StringBuilder q = new StringBuilder("/json/stations/search?hidebroken=true&order=clickcount&reverse=true&limit=60");
        if (name != null && !name.isBlank()) q.append("&name=").append(Http.enc(name.trim()));
        if (countryCode != null && !countryCode.isBlank()) q.append("&countrycode=").append(Http.enc(countryCode));
        if (tag != null && !tag.isBlank()) q.append("&tag=").append(Http.enc(tag.toLowerCase(Locale.ROOT)));
        IOException last = null;
        for (String s : SERVERS) {
            try {
                return parse(Http.getText("https://" + s + q));
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    private static List<Station> parse(String body) throws IOException {
        JsonArray a;
        try {
            a = JsonParser.array().from(body);
        } catch (JsonParserException e) {
            throw new IOException("Annuaire illisible", e);
        }
        List<Station> out = new ArrayList<>();
        for (Object o : a) {
            if (!(o instanceof JsonObject j)) continue;
            String codec = j.getString("codec", "").toUpperCase(Locale.ROOT);
            if (!codec.equals("MP3") && !codec.equals("OGG")) continue;
            String url = j.getString("url_resolved", j.getString("url", ""));
            if (!url.startsWith("http")) continue;
            if (j.getInt("hls", 0) == 1) continue;
            out.add(new Station(j.getString("name", "?").trim(), url, j.getString("countrycode", ""),
                    j.getString("tags", ""), codec));
        }
        return out;
    }
}
