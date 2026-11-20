package au.com.parristech.ruizusync.net;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict HTTP resume policy for private podcast cache files.
 *
 * A partial file can only be appended when its original URL and strong HTTP validator are
 * remembered AND the server proves the exact requested byte range still belongs to that entity.
 * We never persist a signed/private podcast URL: only its SHA-256 digest and the HTTP validator.
 * This class contains no Android dependencies so its safety decisions run in JVM unit tests.
 */
public final class DownloadResumePolicy {

    private static final Pattern CONTENT_RANGE = Pattern.compile(
            "(?i)^bytes\\s+(\\d+)-(\\d+)/(\\d+)$");
    private static final int MAX_VALIDATOR_LENGTH = 256;
    private static final int MAX_META_BYTES = 2048;

    public static final class State {
        public final String sourceKey;
        public final String validatorType;
        public final String validator;

        public State(String sourceKey, String validatorType, String validator) {
            this.sourceKey = sourceKey;
            this.validatorType = validatorType;
            this.validator = validator;
        }
    }

    public static final class Range {
        public final long first;
        public final long last;
        public final long total;

        Range(long first, long last, long total) {
            this.first = first;
            this.last = last;
            this.total = total;
        }

        public long responseBytes() {
            return last - first + 1;
        }
    }

    private DownloadResumePolicy() {}

    public static String key(String url) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static State fromHeaders(String url, String etag, String lastModified) {
        String strong = strongEtag(etag);
        if (strong != null) {
            return new State(key(url), "etag", strong);
        }
        String date = httpDate(lastModified);
        if (date != null) {
            return new State(key(url), "date", date);
        }
        return null; // Resume disabled when the server has not supplied an identity validator.
    }

    public static boolean canResume(State state, String url, long partialLength) {
        return state != null && partialLength > 0 && state.sourceKey.equals(key(url))
                && ("etag".equals(state.validatorType)
                    ? state.validator.equals(strongEtag(state.validator))
                    : "date".equals(state.validatorType)
                        && state.validator.equals(httpDate(state.validator)));
    }

    /** A 206 response must repeat the selected validator; otherwise restart from byte zero. */
    public static boolean sameEntity(State previous, String etag, String lastModified) {
        if (previous == null) {
            return false;
        }
        if ("etag".equals(previous.validatorType)) {
            return previous.validator.equals(strongEtag(etag));
        }
        if ("date".equals(previous.validatorType)) {
            return previous.validator.equals(httpDate(lastModified));
        }
        return false;
    }

    public static Range parseRange(String value, long maxBytes) {
        if (value == null) {
            return null;
        }
        Matcher matcher = CONTENT_RANGE.matcher(value.trim());
        if (!matcher.matches()) {
            return null;
        }
        try {
            long first = Long.parseLong(matcher.group(1));
            long last = Long.parseLong(matcher.group(2));
            long total = Long.parseLong(matcher.group(3));
            if (first < 0 || last < first || total <= last || total > maxBytes) {
                return null;
            }
            return new Range(first, last, total);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static boolean validResumeRange(Range range, long partialBytes, long responseLength) {
        return range != null && partialBytes > 0 && range.first == partialBytes
                && range.last == range.total - 1 // requested "bytes=N-" through EOF
                && (responseLength < 0 || responseLength == range.responseBytes());
    }

    public static State load(File file) {
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_META_BYTES) {
            return null;
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
            String sourceKey = props.getProperty("source");
            String type = props.getProperty("type");
            String validator = props.getProperty("validator");
            if (sourceKey == null || !sourceKey.matches("[a-f0-9]{64}")
                    || validator == null || type == null) {
                return null;
            }
            if ("etag".equals(type) && strongEtag(validator) == null) {
                return null;
            }
            if ("date".equals(type) && httpDate(validator) == null) {
                return null;
            }
            if (!"etag".equals(type) && !"date".equals(type)) {
                return null;
            }
            return new State(sourceKey, type, validator);
        } catch (IOException | IllegalArgumentException ignored) {
            return null;
        }
    }

    public static void save(File file, State state) throws IOException {
        if (state == null) {
            throw new IOException("Cannot persist a download without a validator");
        }
        Properties properties = new Properties();
        properties.setProperty("source", state.sourceKey);
        properties.setProperty("type", state.validatorType);
        properties.setProperty("validator", state.validator);
        File temp = new File(file.getPath() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                properties.store(out, "RUIZU Sync private download resume metadata");
                out.getFD().sync();
            }
            if (!temp.renameTo(file)) {
                throw new IOException("Cannot save download resume metadata");
            }
        } finally {
            if (temp.exists()) {
                temp.delete();
            }
        }
    }

    private static String strongEtag(String value) {
        if (value == null) {
            return null;
        }
        String tag = value.trim();
        if (tag.length() < 2 || tag.length() > MAX_VALIDATOR_LENGTH
                || !tag.startsWith("\"") || !tag.endsWith("\"")) {
            return null; // Weak ETags cannot safely guard If-Range.
        }
        return tag;
    }

    private static String httpDate(String value) {
        if (value == null) {
            return null;
        }
        String date = value.trim();
        // Last-Modified normally uses the HTTP-date IMF-fixdate format; reject custom tokens.
        if (date.length() != 29 || !date.endsWith(" GMT")
                || !date.matches("[A-Za-z]{3}, [0-9]{2} [A-Za-z]{3} [0-9]{4} "
                        + "[0-9]{2}:[0-9]{2}:[0-9]{2} GMT")) {
            return null;
        }
        return date;
    }
}
