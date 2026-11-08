package fr.tamarou.radio.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import fr.tamarou.radio.media.RadioBrowser;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Les radios favorites de chaque joueur (fichier config/radio-favoris.json). */
public final class Favorites {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static List<RadioBrowser.Station> list;

    private Favorites() {}

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("radio-favoris.json");
    }

    public static List<RadioBrowser.Station> all() {
        if (list == null) {
            list = new ArrayList<>();
            try {
                if (Files.exists(file())) {
                    List<RadioBrowser.Station> l = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8),
                            new TypeToken<List<RadioBrowser.Station>>() {}.getType());
                    if (l != null) list.addAll(l);
                }
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    public static boolean has(RadioBrowser.Station s) {
        return all().stream().anyMatch(f -> f.url().equals(s.url()));
    }

    public static void toggle(RadioBrowser.Station s) {
        if (!all().removeIf(f -> f.url().equals(s.url()))) all().add(s);
        try {
            Files.writeString(file(), GSON.toJson(all()), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
