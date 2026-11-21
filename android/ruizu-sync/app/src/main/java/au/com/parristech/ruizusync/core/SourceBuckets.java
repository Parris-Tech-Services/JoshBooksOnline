package au.com.parristech.ruizusync.core;

/**
 * Decides why one selected source will not be transferred.
 *
 * <p>The distinction matters to the user: "unsupported format" promises the file is the wrong kind
 * of audio, while "could not be identified" admits that this app never managed to read what the
 * file is. Reporting the second as the first is what made a set of ordinary shared MP3s look
 * unsupported, so the rule lives here where it can be tested without a device.</p>
 */
public final class SourceBuckets {

    private SourceBuckets() {
    }

    /** How one selected source will be treated by the transfer. */
    public enum Bucket {
        /** A supported audio format with nothing blocking it. */
        SUPPORTED,
        /** The format itself is not one the player accepts. */
        UNSUPPORTED_FORMAT,
        /** Something about the item could not be read; the format may well be fine. */
        UNREADABLE
    }

    /**
     * @param supportedFormat the item's name resolves to a supported audio format
     * @param problem         the item's transfer problem, or null/empty when there is none
     */
    public static Bucket of(boolean supportedFormat, String problem) {
        // Match TransferManager: a reported problem blocks even a recognised MP3.
        if (problem != null && !problem.isEmpty()) {
            return Bucket.UNREADABLE;
        }
        if (supportedFormat) {
            return Bucket.SUPPORTED;
        }
        return Bucket.UNSUPPORTED_FORMAT;
    }
}
