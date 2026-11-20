package au.com.parristech.ruizusync.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Makes tag-derived folder names safe for the FAT32 / exFAT file system that USB MP3 players
 * almost always use: no reserved characters, no reserved DOS device names, no trailing dots or
 * spaces, and a hard length cap.
 */
public final class FileNameSanitizer {

    private static final int MAX_SEGMENT_LENGTH = 120;
    private static final String RESERVED_CHARS = "/\\:*?\"<>|";

    private static final Set<String> RESERVED_NAMES = new HashSet<>(Arrays.asList(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"));

    private FileNameSanitizer() {
    }

    /**
     * Sanitises a single path segment (a folder name or a file name without extension).
     *
     * @return a safe segment, or "" when nothing usable is left.
     */
    public static String segment(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                sb.append(' ');
            } else if (RESERVED_CHARS.indexOf(c) >= 0) {
                sb.append('-');
            } else {
                sb.append(c);
            }
        }
        String out = sb.toString().replaceAll("\\s+", " ").trim();
        while (!out.isEmpty() && (out.charAt(out.length() - 1) == '.' || out.charAt(out.length() - 1) == ' ')) {
            out = out.substring(0, out.length() - 1);
        }
        if (out.isEmpty()) {
            return "";
        }
        if (out.length() > MAX_SEGMENT_LENGTH) {
            int cut = MAX_SEGMENT_LENGTH;
            if (Character.isHighSurrogate(out.charAt(cut - 1))) {
                cut--;
            }
            out = out.substring(0, cut).trim();
        }
        if (RESERVED_NAMES.contains(out.toLowerCase(Locale.ROOT))) {
            out = "_" + out;
        }
        return out;
    }

    /**
     * Sanitises a full file name while keeping the extension.
     *
     * @return a safe file name; "track" is used when nothing usable remains.
     */
    public static String fileName(String raw) {
        String name = raw == null ? "" : raw.trim();
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot + 1) : "";

        String safeBase = segment(base);
        String safeExtension = segment(extension).replace(" ", "");
        if (safeBase.isEmpty()) {
            safeBase = "track";
        }
        if (safeExtension.isEmpty()) {
            return safeBase;
        }
        return safeBase + "." + safeExtension;
    }

    /** Sanitises every segment of a relative folder path, dropping empty ones. */
    public static List<String> pathSegments(List<String> rawSegments) {
        List<String> out = new ArrayList<>();
        if (rawSegments == null) {
            return out;
        }
        for (String raw : rawSegments) {
            String safe = segment(raw);
            if (!safe.isEmpty()) {
                out.add(safe);
            }
        }
        return out;
    }
}
