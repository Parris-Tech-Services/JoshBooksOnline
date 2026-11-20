package au.com.parristech.ruizusync.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a source file should be converted to MP3 before it is copied to the player.
 * Pure Java, unit tested; the actual encoder runs on device.
 */
public final class ConversionPolicy {

    /** Formats players frequently do not decode, so conversion to MP3 makes sense. */
    public static final Set<String> LOSSY_OR_ODD_FORMATS = Collections.unmodifiableSet(
            new LinkedHashSet<>(java.util.Arrays.asList("ogg", "opus", "aif", "aiff", "m4a", "m4b", "aac", "wma", "ape")));

    public static final int DEFAULT_BITRATE_KBPS = 192;

    private ConversionPolicy() {
    }

    /**
     * @param displayName     source file name
     * @param enabled         user turned on "convert to MP3"
     * @param formatsToConvert extensions (without dot) that should be converted; null means all
     *                        formats except MP3
     */
    public static boolean shouldConvert(String displayName, boolean enabled, Set<String> formatsToConvert) {
        if (!enabled) {
            return false;
        }
        String extension = MediaTypes.extensionOf(displayName);
        if (extension.isEmpty() || "mp3".equals(extension)) {
            return false;
        }
        if (formatsToConvert == null || formatsToConvert.isEmpty()) {
            return true;
        }
        return formatsToConvert.contains(extension);
    }

    public static String convertedName(String displayName) {
        String base = FileNameSanitizer.fileName(displayName);
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        if (stem.isEmpty()) {
            stem = "Track";
        }
        return stem + ".mp3";
    }

    public static String describe(Set<String> formats) {
        if (formats == null || formats.isEmpty()) {
            return "no formats";
        }
        StringBuilder sb = new StringBuilder();
        for (String format : formats) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(format.toUpperCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
