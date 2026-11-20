package au.com.parristech.ruizusync.core;

import java.util.Locale;

/** Human readable byte / speed / duration formatting. Pure Java, unit tested on the JVM. */
public final class ByteFormat {

    private static final String[] UNITS = {"KB", "MB", "GB", "TB"};

    private ByteFormat() {
    }

    public static String bytes(long bytes) {
        if (bytes < 0) {
            return "--";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = -1;
        while (value >= 1024.0 && unit < UNITS.length - 1) {
            value /= 1024.0;
            unit++;
        }
        String pattern;
        if (value >= 100) {
            pattern = "%.0f %s";
        } else if (value >= 10) {
            pattern = "%.1f %s";
        } else {
            pattern = "%.2f %s";
        }
        return String.format(Locale.US, pattern, value, UNITS[unit]);
    }

    public static String speed(double bytesPerSecond) {
        if (bytesPerSecond <= 0) {
            return "--";
        }
        return bytes((long) bytesPerSecond) + "/s";
    }

    public static String duration(long totalSeconds) {
        if (totalSeconds < 0) {
            return "--";
        }
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes < 60) {
            return String.format(Locale.US, "%dm %02ds", minutes, seconds);
        }
        long hours = minutes / 60;
        minutes = minutes % 60;
        return String.format(Locale.US, "%dh %02dm", hours, minutes);
    }
}
