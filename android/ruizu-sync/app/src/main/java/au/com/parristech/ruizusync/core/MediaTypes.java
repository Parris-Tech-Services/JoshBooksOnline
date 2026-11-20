package au.com.parristech.ruizusync.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Audio formats that RUIZU MP3 players are documented to play, plus their MIME types.
 *
 * <p>The player firmware decides what it can actually decode; this list is deliberately the common
 * RUIZU set: MP3, WAV, FLAC, WMA, APE, AAC, M4A/M4B and OGG/Opus. Anything else is reported as
 * unsupported instead of being copied and silently ignored by the player.</p>
 */
public final class MediaTypes {

    private static final Map<String, String> EXTENSION_TO_MIME;

    static {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("mp3", "audio/mpeg");
        map.put("wav", "audio/x-wav");
        map.put("flac", "audio/flac");
        map.put("wma", "audio/x-ms-wma");
        map.put("ape", "audio/x-ape");
        map.put("aac", "audio/aac");
        map.put("m4a", "audio/mp4");
        map.put("m4b", "audio/mp4");
        map.put("ogg", "audio/ogg");
        map.put("opus", "audio/ogg");
        map.put("aif", "audio/x-aiff");
        map.put("aiff", "audio/x-aiff");
        EXTENSION_TO_MIME = Collections.unmodifiableMap(map);
    }

    private MediaTypes() {
    }

    /** Returns the lower-cased extension without the dot, or "" when the name has none. */
    public static String extensionOf(String displayName) {
        if (displayName == null) {
            return "";
        }
        String name = displayName.trim();
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isSupported(String displayName) {
        return EXTENSION_TO_MIME.containsKey(extensionOf(displayName));
    }

    /** MIME type used when creating the document on the player, e.g. audio/mpeg. */
    public static String mimeTypeFor(String displayName) {
        String mime = EXTENSION_TO_MIME.get(extensionOf(displayName));
        return mime == null ? "application/octet-stream" : mime;
    }

    /** MIME filter for the SAF file picker. */
    public static String[] pickerMimeTypes() {
        List<String> unique = new ArrayList<>();
        unique.add("audio/*");
        for (String type : EXTENSION_TO_MIME.values()) {
            if (!unique.contains(type)) {
                unique.add(type);
            }
        }
        // Google Drive labels some files as generic binary; still let the user pick them so the
        // post-selection format check can explain what is supported.
        unique.add("application/octet-stream");
        return unique.toArray(new String[0]);
    }

    /** Extension implied by a MIME type, or "" when it does not map to a supported format. */
    public static String extensionForMime(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        int semicolon = normalized.indexOf(';');
        if (semicolon >= 0) {
            normalized = normalized.substring(0, semicolon).trim();
        }
        switch (normalized) {
            case "audio/mpeg":
            case "audio/mp3":
            case "audio/mpeg3":
            case "audio/x-mpeg-3":
                return "mp3";
            case "audio/mp4":
            case "audio/x-m4a":
            case "audio/x-m4b":
                return "m4a";
            case "audio/aac":
                return "aac";
            case "audio/flac":
            case "audio/x-flac":
                return "flac";
            case "audio/wav":
            case "audio/x-wav":
            case "audio/wave":
                return "wav";
            case "audio/ogg":
            case "application/ogg":
            case "audio/opus":
                return "ogg";
            case "audio/x-ms-wma":
                return "wma";
            case "audio/x-ape":
            case "audio/ape":
                return "ape";
            case "audio/aiff":
            case "audio/x-aiff":
                return "aiff";
            default:
                return "";
        }
    }

    /**
     * Adds the extension implied by the MIME type when a feed or link has no usable file
     * extension, e.g. podcast enclosures such as https://example.com/episode/42.
     */
    public static String normalizeAudioName(String name, String mimeType) {
        if (isSupported(name)) {
            return name;
        }
        String extension = extensionForMime(mimeType);
        if (extension.isEmpty()) {
            return name == null ? "" : name;
        }
        String base = name == null ? "" : name.trim();
        if (base.isEmpty()) {
            base = "Audio";
        }
        while (base.endsWith(".")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "." + extension;
    }

    public static String supportedList() {
        return "MP3, WAV, FLAC, WMA, APE, AAC, M4A/M4B, OGG/Opus, AIFF";
    }
}
