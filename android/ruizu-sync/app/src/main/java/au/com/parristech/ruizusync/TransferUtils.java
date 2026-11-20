package au.com.parristech.ruizusync;

import java.util.Locale;

public final class TransferUtils {
    private TransferUtils() {}

    public static boolean isSupportedAudio(String fileName) {
        if (fileName == null) return false;
        String s = fileName.toLowerCase(Locale.ROOT);
        return s.endsWith(".mp3") || s.endsWith(".m4a") || s.endsWith(".m4b")
            || s.endsWith(".wav") || s.endsWith(".flac") || s.endsWith(".aac")
            || s.endsWith(".ogg") || s.endsWith(".wma");
    }

    public static String safeName(String name) {
        if (name == null || name.trim().isEmpty()) return "Audio.mp3";
        String s = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (s.equals(".") || s.equals("..") || s.isEmpty()) return "Audio.mp3";
        s = s.replaceAll("[. ]+$", "");
        if (s.isEmpty()) return "Audio.mp3";
        int dot = s.lastIndexOf('.');
        String stem = dot > 0 ? s.substring(0, dot) : s;
        String extension = dot > 0 ? s.substring(dot) : "";
        if (stem.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])")) stem = "_" + stem;
        // UTF-8 byte bound (FAT providers often impose 255 bytes), preserve extension.
        while ((stem + extension).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 180 && !stem.isEmpty()) {
            stem = stem.substring(0, stem.offsetByCodePoints(stem.length(), -1));
        }
        return stem + extension;
    }

    public static String withNumber(String name, int n) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name + " (" + n + ")";
        return name.substring(0, dot) + " (" + n + ")" + name.substring(dot);
    }

    public static boolean isConflictName(String requested, String candidate) {
        if (requested.equalsIgnoreCase(candidate)) return true;
        int dot = requested.lastIndexOf('.');
        if (dot <= 0) return false;
        String pattern = java.util.regex.Pattern.quote(requested.substring(0, dot))
            + " \\([0-9]+\\)" + java.util.regex.Pattern.quote(requested.substring(dot));
        return java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(candidate).matches();
    }

    public static boolean isStreamingLink(String input) {
        if (input == null) return false;
        String s = input.toLowerCase(Locale.ROOT);
        return s.matches("(?s)^https?://([^/]*\\.)?(youtube\\.com|youtu\\.be|music\\.youtube\\.com|spotify\\.com)(/|\\?|$).*");
    }
}
