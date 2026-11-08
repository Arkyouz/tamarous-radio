package fr.tamarou.radio.media;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Petites requêtes HTTP partagées par toutes les sources (Deezer, radios, YouTube). */
public final class Http {
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";

    public static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private Http() {}

    public static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static String getText(String url) throws IOException {
        return getText(url, Map.of());
    }

    public static String getText(String url, Map<String, String> headers) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT);
        headers.forEach(b::header);
        try {
            HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() >= 400) throw new IOException("HTTP " + r.statusCode() + " pour " + url);
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompu", e);
        }
    }

    public static byte[] getBytes(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT).build();
        try {
            HttpResponse<byte[]> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() >= 400) throw new IOException("HTTP " + r.statusCode() + " pour " + url);
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompu", e);
        }
    }

    /** Ouvre un flux (radio en direct). Pas de délai de lecture : le flux ne s'arrête jamais. */
    public static HttpResponse<InputStream> open(String url, Map<String, String> headers) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT);
        headers.forEach(b::header);
        try {
            HttpResponse<InputStream> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() >= 400) {
                r.body().close();
                throw new IOException("HTTP " + r.statusCode() + " pour " + url);
            }
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompu", e);
        }
    }
}
