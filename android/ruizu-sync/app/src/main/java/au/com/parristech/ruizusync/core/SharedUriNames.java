package au.com.parristech.ruizusync.core;

/**
 * Works out a usable file name for a document that another app shared into RUIZU Sync.
 *
 * <p>A cross-app share hands this app a {@code content://} URI whose last path segment is a
 * database row id rather than a file name, for example
 * {@code content://media/external/audio/media/1000110460}. The real name arrives from the
 * provider in {@code OpenableColumns.DISPLAY_NAME}. When the provider refuses that query - most
 * often because the sending app could not pass its read grant along - the previous code fell back
 * to the row id, found no file extension, and reported an ordinary MP3 as "unsupported format"
 * while showing the number instead of a file name.</p>
 *
 * <p>This class holds that decision so it can be tested on the JVM without a device: give it what
 * the provider actually returned and it either keeps the provider's real name or reconstructs a
 * supported name from the MIME type. It deliberately does not invent a name from a row id when
 * the type is unknown: an honest "could not identify this file" is better than a silent skip.</p>
 */
public final class SharedUriNames {

    /** Readable stem used when the sender supplied no name but did supply an audio type. */
    static final String FALLBACK_STEM = "Shared audio";

    private SharedUriNames() {
    }

    /** Outcome of resolving one shared document. */
    public static final class Resolved {
        /**
         * A usable name. Empty only when nothing at all could be determined. It is never a bare
         * row id paired with a supported format, because a row id cannot identify a file.
         */
        public final String name;
        /** True when {@link #name} is the sender's own file name, not a reconstruction. */
        public final boolean fromProvider;
        /** True when {@link #name} has a format the RUIZU player accepts. */
        public final boolean supported;
        /** True when the sender gave no readable name and {@link #name} was reconstructed. */
        public final boolean reconstructed;

        Resolved(String name, boolean fromProvider, boolean supported, boolean reconstructed) {
            this.name = name == null ? "" : name;
            this.fromProvider = fromProvider;
            this.supported = supported;
            this.reconstructed = reconstructed;
        }
    }

    /**
     * A MediaStore row id (or a similar opaque numeric document id) is not a file name. Requiring
     * at least three digits avoids treating short numeric names such as "99" as ids.
     */
    public static boolean isBareRowId(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.length() < 3) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decides the name and support status for one shared document.
     *
     * @param providerName    {@code OpenableColumns.DISPLAY_NAME}, or null/empty when the provider
     *                        refused the query or returned no name
     * @param mimeType        the provider's MIME type for the document, or null/empty when unknown
     * @param lastPathSegment the URI's trailing segment, used only to recognise a row id
     */
    public static Resolved resolve(String providerName, String mimeType, String lastPathSegment) {
        String provided = providerName == null ? "" : providerName.trim();
        if (!provided.isEmpty() && !isBareRowId(provided)) {
            // The provider named the file: respect it exactly, including an unsupported format.
            return new Resolved(provided, true, MediaTypes.isSupported(provided), false);
        }

        String extension = MediaTypes.extensionForMime(mimeType);
        if (!extension.isEmpty()) {
            // No usable name, but the audio type is known: build a supported name from it. The
            // row id is kept so several files shared at once stay distinguishable and can still
            // be traced back to their MediaStore entry.
            String id = isBareRowId(provided) ? provided.trim()
                    : (isBareRowId(lastPathSegment) ? lastPathSegment.trim() : "");
            String suffix = id.isEmpty() ? "" : " " + id;
            return new Resolved(FALLBACK_STEM + suffix + "." + extension, false, true, true);
        }

        if (!provided.isEmpty()) {
            // A row id with no type: report it as unidentified rather than pretending it has a
            // supported format. The id is kept so the transfer log can name the row.
            return new Resolved(provided, false, false, false);
        }
        return new Resolved("", false, false, false);
    }
}
