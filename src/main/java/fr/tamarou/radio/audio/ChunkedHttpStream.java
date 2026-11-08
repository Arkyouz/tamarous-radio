package fr.tamarou.radio.audio;

import fr.tamarou.radio.media.Http;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Télécharge un fichier par morceaux (en-tête Range), comme un navigateur. Les serveurs de YouTube
 * coupent ou ralentissent les gros téléchargements d'un seul coup.
 */
public final class ChunkedHttpStream extends InputStream {
    private static final int CHUNK = 1024 * 1024;
    private final String url;
    private long pos;
    private long total = -1;
    private byte[] buf = new byte[0];
    private int bufPos;
    private boolean closed;

    public ChunkedHttpStream(String url) {
        this.url = url;
    }

    private boolean fill() throws IOException {
        if (closed || (total >= 0 && pos >= total)) return false;
        long end = pos + CHUNK - 1;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", Http.USER_AGENT)
                .header("Range", "bytes=" + pos + "-" + end).build();
        HttpResponse<byte[]> r;
        try {
            r = Http.CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompu", e);
        }
        if (r.statusCode() == 416) return false;
        if (r.statusCode() >= 400) throw new IOException("HTTP " + r.statusCode());
        r.headers().firstValue("Content-Range").ifPresent(cr -> {
            int slash = cr.lastIndexOf('/');
            if (slash >= 0 && !cr.endsWith("*")) total = Long.parseLong(cr.substring(slash + 1).trim());
        });
        buf = r.body();
        bufPos = 0;
        if (r.statusCode() == 200) total = buf.length; // le serveur a tout envoyé d'un coup
        pos += buf.length;
        return buf.length > 0;
    }

    @Override
    public int read() throws IOException {
        if (bufPos >= buf.length && !fill()) return -1;
        return buf[bufPos++] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (bufPos >= buf.length && !fill()) return -1;
        int n = Math.min(len, buf.length - bufPos);
        System.arraycopy(buf, bufPos, b, off, n);
        bufPos += n;
        return n;
    }

    @Override
    public void close() {
        closed = true;
    }
}
