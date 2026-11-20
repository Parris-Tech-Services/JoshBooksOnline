package au.com.parristech.ruizusync.net;

import android.net.Uri;

import au.com.parristech.ruizusync.core.MediaTypes;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * HTTPS audio and feed fetching for podcast episodes and direct audio links.
 *
 * <p>Only HTTPS is allowed, redirects are followed manually (HTTPS-only at every hop), responses
 * must be audio, and transfers are capped at 2 GB. Streaming services (Spotify/YouTube) and
 * playlists are rejected: this app copies audio files, it never extracts or bypasses DRM.</p>
 */
public final class RemoteAudio {

    public static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    /**
     * Character budget for a feed download. Feeds are streamed and parsing stops as soon as
     * {@link FeedScanner#MAX_EPISODES} episodes are collected, so this is only a safety valve for
     * pathological documents - long-running shows regularly ship 10-30 MB feeds.
     */
    public static final int MAX_FEED_CHARS = 32 * 1024 * 1024;

    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final String USER_AGENT = "RuizuSync/1.0 (Android; JoshBooksOnline)";

    public static final class Probe {
        public final String name;
        public final long size;
        public final String mimeType;

        Probe(String name, long size, String mimeType) {
            this.name = name;
            this.size = size;
            this.mimeType = mimeType;
        }
    }

    private RemoteAudio() {
    }

    public static boolean isRemote(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
    }

    /** Best-effort HEAD probe; falls back to a one-byte ranged GET when HEAD is refused. */
    public static Probe probe(Uri uri) {
        String name = nameFromUrl(uri);
        long size = -1;
        String mime = "";
        HttpURLConnection connection = null;
        try {
            connection = connect(new URL(uri.toString()), "HEAD");
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                size = connection.getContentLengthLong();
                mime = normalizeMime(connection.getContentType());
            }
        } catch (Throwable ignored) {
            // Many CDNs refuse HEAD; fall through to the ranged GET below.
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        if (size <= 0 || mime.isEmpty()) {
            Probe ranged = rangedProbe(uri);
            if (ranged != null) {
                if (size <= 0) {
                    size = ranged.size;
                }
                if (mime.isEmpty()) {
                    mime = ranged.mimeType;
                }
            }
        }
        return new Probe(MediaTypes.normalizeAudioName(name, mime), size > 0 ? size : -1, mime);
    }

    public static InputStream open(Uri uri) throws IOException {
        HttpURLConnection connection = connect(new URL(uri.toString()), "GET");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IOException("Download failed with HTTP " + code);
        }
        String mime = normalizeMime(connection.getContentType());
        if (!isAudioContentType(mime)) {
            connection.disconnect();
            throw new IOException("The link did not return an audio file ("
                    + (mime.isEmpty() ? "unknown content type" : mime) + ")");
        }
        long length = connection.getContentLengthLong();
        if (length > MAX_BYTES) {
            connection.disconnect();
            throw new IOException("File exceeds the 2 GB transfer limit");
        }
        return new ConnectionStream(connection.getInputStream(), connection);
    }

    /**
     * Streams a podcast feed and returns the episodes found so far. Reading stops early once
     * {@link FeedScanner#MAX_EPISODES} episodes are collected or the character budget is spent,
     * so large feeds (e.g. long-running shows with thousands of episodes) load in seconds.
     */
    public static List<FeedScanner.Episode> fetchFeedEpisodes(String url, int maxChars) throws IOException {
        HttpURLConnection connection = connect(new URL(url), "GET");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("Feed request failed with HTTP " + code);
            }
            String mime = normalizeMime(connection.getContentType());
            if (!mime.isEmpty() && !isFeedContentType(mime)) {
                throw new IOException("The link is not a podcast feed (" + mime + ")");
            }
            FeedScanner.StreamScanner scanner = new FeedScanner.StreamScanner();
            try (Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[16 * 1024];
                long total = 0;
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    total += read;
                    if (total > maxChars) {
                        break; // partial feed is fine: keep the episodes already parsed
                    }
                    scanner.feed(buffer, 0, read);
                    if (scanner.done()) {
                        break;
                    }
                }
            }
            return scanner.episodes();
        } finally {
            connection.disconnect();
        }
    }

    public interface DownloadProgress {
        void onProgress(long done, long total);
    }

    /**
     * Downloads an audio file to cache with HTTP Range resume: an interrupted download continues
     * from the bytes already on disk. Returns the number of bytes in the finished file.
     */
    public static long downloadToFile(String url, java.io.File target, DownloadProgress progress)
            throws IOException {
        java.io.File part = new java.io.File(target.getPath() + ".part");
        long existing = part.isFile() ? part.length() : 0L;
        HttpURLConnection connection = connect(new URL(url), "GET",
                existing > 0 ? "bytes=" + existing + "-" : null);
        try {
            int code = connection.getResponseCode();
            boolean append = code == 206 && existing > 0;
            if (code != 200 && code != 206) {
                throw new IOException("Download failed with HTTP " + code);
            }
            String mime = normalizeMime(connection.getContentType());
            if (!isAudioContentType(mime)) {
                throw new IOException("The link did not return an audio file ("
                        + (mime.isEmpty() ? "unknown content type" : mime) + ")");
            }
            long total;
            if (append) {
                long tail = parseContentRange(connection.getHeaderField("Content-Range"));
                total = tail > 0 ? tail : existing + connection.getContentLengthLong();
            } else {
                existing = 0;
                total = connection.getContentLengthLong();
            }
            if (total > MAX_BYTES) {
                throw new IOException("File exceeds the 2 GB transfer limit");
            }
            long written = existing;
            try (InputStream in = connection.getInputStream();
                 java.io.FileOutputStream out = new java.io.FileOutputStream(part, append)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    written += read;
                    if (written > MAX_BYTES) {
                        throw new IOException("File exceeds the 2 GB transfer limit");
                    }
                    out.write(buffer, 0, read);
                    if (progress != null) {
                        progress.onProgress(written, total);
                    }
                }
                out.getFD().sync();
            }
            if (target.exists() && !target.delete()) {
                throw new IOException("Cannot replace " + target.getName());
            }
            if (!part.renameTo(target)) {
                throw new IOException("Cannot finish writing " + target.getName());
            }
            return written;
        } finally {
            connection.disconnect();
        }
    }

    public static String nameFromUrl(Uri uri) {
        String path = uri.getPath();
        String segment = "";
        if (path != null) {
            int slash = path.lastIndexOf('/');
            segment = slash >= 0 ? path.substring(slash + 1) : path;
        }
        try {
            segment = Uri.decode(segment);
        } catch (Throwable ignored) {
            // Keep the raw segment.
        }
        segment = segment == null ? "" : segment.trim();
        return segment.isEmpty() ? "Audio" : segment;
    }

    public static boolean isAudioContentType(String mime) {
        if (mime == null || mime.isEmpty()) {
            return false;
        }
        if (mime.startsWith("audio/")) {
            return !mime.startsWith("audio/x-mpegurl");
        }
        return mime.equals("application/ogg") || mime.equals("application/octet-stream");
    }

    static String normalizeMime(String contentType) {
        if (contentType == null) {
            return "";
        }
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        int semicolon = normalized.indexOf(';');
        if (semicolon >= 0) {
            normalized = normalized.substring(0, semicolon).trim();
        }
        return normalized;
    }

    private static boolean isFeedContentType(String mime) {
        return mime.contains("xml") || mime.startsWith("text/") || mime.equals("application/rss+xml")
                || mime.equals("application/atom+xml") || mime.equals("application/octet-stream");
    }

    private static Probe rangedProbe(Uri uri) {
        HttpURLConnection connection = null;
        try {
            connection = connect(new URL(uri.toString()), "GET");
            connection.setRequestProperty("Range", "bytes=0-0");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            String mime = normalizeMime(connection.getContentType());
            long size = parseContentRange(connection.getHeaderField("Content-Range"));
            return new Probe("", size, mime);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static long parseContentRange(String header) {
        if (header == null) {
            return -1;
        }
        int slash = header.lastIndexOf('/');
        if (slash < 0 || slash == header.length() - 1) {
            return -1;
        }
        try {
            return Long.parseLong(header.substring(slash + 1).trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static HttpURLConnection connect(URL url, String method) throws IOException {
        return connect(url, method, null);
    }

    private static HttpURLConnection connect(URL url, String method, String range) throws IOException {
        URL current = url;
        for (int attempt = 0; attempt <= MAX_REDIRECTS; attempt++) {
            String protocol = current.getProtocol();
            if (!"https".equalsIgnoreCase(protocol)) {
                throw new IOException("HTTPS link required");
            }
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            if (range != null) {
                connection.setRequestProperty("Range", range);
            }
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("Redirect without a destination");
                }
                current = new URL(current, location);
                continue;
            }
            return connection;
        }
        throw new IOException("Too many redirects");
    }

    private static final class ConnectionStream extends FilterInputStream {
        private final HttpURLConnection connection;
        private long read;

        ConnectionStream(InputStream in, HttpURLConnection connection) {
            super(in);
            this.connection = connection;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int value = super.read(buffer, offset, length);
            if (value > 0) {
                count(value);
            }
            return value;
        }

        private void count(long bytes) throws IOException {
            read += bytes;
            if (read > MAX_BYTES) {
                throw new IOException("File exceeds the 2 GB transfer limit");
            }
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                connection.disconnect();
            }
        }
    }
}
