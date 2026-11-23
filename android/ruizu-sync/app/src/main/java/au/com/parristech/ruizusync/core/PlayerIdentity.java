package au.com.parristech.ruizusync.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Identity of one physical player, stored as a small marker file inside the folder the user chose
 * for it.
 *
 * <p>The SAF volume UUID is <em>not</em> device identity: two RUIZU players in this household both
 * report volume {@code 0000-006F} and mount at the same path, so a destination remembered against
 * the volume alone belongs to whichever unit happens to be plugged in. The marker travels with the
 * device on its own storage, which is the only place that can answer "which player is this?".
 *
 * <p>Pure Java with a small bounded JSON reader so the marker format is covered by the JVM test
 * suite without Android or a device.
 */
public final class PlayerIdentity {

    /**
     * Marker file name. Fixed and recognisable so a support session can spot it on the player's
     * FAT volume without opening the app.
     */
    public static final String MARKER_FILE_NAME = ".ruizu-sync-device.json";

    /** A real marker is a few dozen bytes; anything this large is not ours. */
    public static final int MAX_MARKER_CHARS = 1024;

    public static final int MAX_ID_CHARS = 64;
    public static final int MAX_NAME_CHARS = 48;

    private static final int FORMAT_VERSION = 1;
    private static final String DEFAULT_NAME = "Unnamed player";
    private static final int MAX_FIELDS = 16;

    public final String id;
    public final String name;
    /** Milliseconds since the epoch when the marker was first written; 0 when unknown. */
    public final long firstSeenMs;

    private PlayerIdentity(String id, String name, long firstSeenMs) {
        this.id = id;
        this.name = name;
        this.firstSeenMs = firstSeenMs;
    }

    /** A brand new identity for a player that has no marker yet. */
    public static PlayerIdentity create(String name, long nowMs) {
        return new PlayerIdentity(UUID.randomUUID().toString(), cleanName(name), Math.max(0, nowMs));
    }

    /** An identity from known parts, or null when the id is not usable. */
    public static PlayerIdentity of(String id, String name, long firstSeenMs) {
        String cleanId = cleanId(id);
        if (cleanId == null) {
            return null;
        }
        return new PlayerIdentity(cleanId, cleanName(name), Math.max(0, firstSeenMs));
    }

    /**
     * Parse a marker document. Returns null for absent, oversized, malformed or foreign content so
     * callers report "unidentified" instead of inventing an identity.
     */
    public static PlayerIdentity parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty() || text.length() > MAX_MARKER_CHARS) {
            return null;
        }
        Map<String, String> fields = flatObject(text);
        if (fields == null) {
            return null;
        }
        String version = fields.get("version");
        if (version != null && !String.valueOf(FORMAT_VERSION).equals(version.trim())) {
            return null;
        }
        String cleanId = cleanId(fields.get("id"));
        if (cleanId == null) {
            return null;
        }
        long firstSeen = 0;
        String rawFirstSeen = fields.get("firstSeenMs");
        if (rawFirstSeen != null) {
            try {
                firstSeen = Long.parseLong(rawFirstSeen.trim());
            } catch (NumberFormatException ignored) {
                firstSeen = 0;
            }
        }
        return new PlayerIdentity(cleanId, cleanName(fields.get("name")), Math.max(0, firstSeen));
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"version\":").append(FORMAT_VERSION)
                .append(",\"id\":\"").append(escape(id))
                .append("\",\"name\":\"").append(escape(name))
                .append("\",\"firstSeenMs\":").append(firstSeenMs)
                .append('}');
        return sb.toString();
    }

    /** First 8 characters of the id: enough to tell two players apart in the UI. */
    public String shortId() {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    /** "Name (a1b2c3d4)" — what the UI shows so the user can tell the units apart. */
    public String describe() {
        return name + " (" + shortId() + ")";
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PlayerIdentity)) {
            return false;
        }
        PlayerIdentity that = (PlayerIdentity) other;
        return id.equals(that.id) && name.equals(that.name) && firstSeenMs == that.firstSeenMs;
    }

    @Override
    public int hashCode() {
        return id.hashCode() * 31 + name.hashCode();
    }

    @Override
    public String toString() {
        return "PlayerIdentity{" + describe() + "}";
    }

    private static String cleanId(String raw) {
        if (raw == null) {
            return null;
        }
        String id = raw.trim();
        if (id.isEmpty() || id.length() > MAX_ID_CHARS) {
            return null;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return null;
            }
        }
        return id;
    }

    private static String cleanName(String raw) {
        if (raw == null) {
            return DEFAULT_NAME;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        boolean lastSpace = false;
        for (int i = 0; i < raw.length() && sb.length() < MAX_NAME_CHARS; i++) {
            char c = raw.charAt(i);
            if (Character.isISOControl(c)) {
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (!lastSpace && sb.length() > 0) {
                    sb.append(' ');
                    lastSpace = true;
                }
                continue;
            }
            lastSpace = false;
            sb.append(c);
        }
        String name = sb.toString().trim();
        return name.isEmpty() ? DEFAULT_NAME : name;
    }

    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * Reads a flat JSON object of string/number values. Hand-rolled because {@code org.json} is not
     * available to the JVM test suite, and a marker only ever needs a flat object.
     */
    private static Map<String, String> flatObject(String text) {
        Cursor cursor = new Cursor(text);
        Map<String, String> out = new LinkedHashMap<>();
        cursor.skipWhitespace();
        if (!cursor.eat('{')) {
            return null;
        }
        cursor.skipWhitespace();
        if (cursor.eat('}')) {
            cursor.skipWhitespace();
            return cursor.atEnd() ? out : null;
        }
        while (true) {
            cursor.skipWhitespace();
            String key = cursor.string();
            if (key == null) {
                return null;
            }
            cursor.skipWhitespace();
            if (!cursor.eat(':')) {
                return null;
            }
            cursor.skipWhitespace();
            String value = cursor.peekQuote() ? cursor.string() : cursor.token();
            if (value == null) {
                return null;
            }
            if (out.put(key, value) != null || out.size() > MAX_FIELDS) {
                return null;
            }
            cursor.skipWhitespace();
            if (cursor.eat(',')) {
                continue;
            }
            if (cursor.eat('}')) {
                cursor.skipWhitespace();
                return cursor.atEnd() ? out : null;
            }
            return null;
        }
    }

    /** Position-tracking reader for the flat object above; every method may return null. */
    private static final class Cursor {
        private final String text;
        private int index;

        Cursor(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return index >= text.length();
        }

        void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        boolean peekQuote() {
            return index < text.length() && text.charAt(index) == '"';
        }

        boolean eat(char expected) {
            if (index < text.length() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        String string() {
            if (!eat('"')) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (index >= text.length()) {
                        return null;
                    }
                    char escape = text.charAt(index++);
                    switch (escape) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (index + 4 > text.length()) {
                                return null;
                            }
                            int code = 0;
                            for (int k = 0; k < 4; k++) {
                                int digit = Character.digit(text.charAt(index + k), 16);
                                if (digit < 0) {
                                    return null;
                                }
                                code = (code << 4) | digit;
                            }
                            index += 4;
                            sb.append((char) code);
                            break;
                        default:
                            return null;
                    }
                    continue;
                }
                if (c < 0x20) {
                    return null;
                }
                sb.append(c);
                if (sb.length() > MAX_MARKER_CHARS) {
                    return null;
                }
            }
            return null;
        }

        String token() {
            int start = index;
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == ',' || c == '}' || Character.isWhitespace(c)) {
                    break;
                }
                index++;
            }
            return index > start ? text.substring(start, index) : null;
        }
    }
}
