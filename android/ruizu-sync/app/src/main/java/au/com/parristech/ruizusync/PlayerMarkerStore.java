package au.com.parristech.ruizusync;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import au.com.parristech.ruizusync.core.PlayerIdentity;
import au.com.parristech.ruizusync.io.DestRepo;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads and creates the per-device identity marker inside the player folder the user picked.
 *
 * <p>This exists because the SAF volume UUID cannot tell two players apart: both RUIZU units here
 * report {@code 0000-006F}. The marker lives on the player's own storage, so it identifies the
 * unit that is physically attached rather than the mount point that happens to be shared.
 *
 * <p>Rules this class keeps:
 * <ul>
 *   <li>It only ever touches its own marker file inside the chosen tree — never another file, and
 *       never outside the tree.</li>
 *   <li>It never overwrites an existing marker. A marker it cannot read is reported, not repaired,
 *       because re-identifying a player would make two units look like one.</li>
 *   <li>It only reports an identity it has read back from storage, so a failed write can never
 *       become a claimed identity.</li>
 * </ul>
 */
public final class PlayerMarkerStore {

    /** Result of looking at the marker file. */
    public enum State {
        /** A marker was read and parsed: {@link Reading#identity} is usable. */
        IDENTIFIED,
        /** No marker file exists yet. */
        ABSENT,
        /** A marker exists but is not a marker this app understands. */
        MALFORMED,
        /** The marker could not be read or created (permissions, detached, I/O failure). */
        UNREADABLE
    }

    public static final class Reading {
        public final State state;
        public final PlayerIdentity identity;

        Reading(State state, PlayerIdentity identity) {
            this.state = state;
            this.identity = identity;
        }

        /** An explicit "we do not know" value, for callers whose first step may fail. */
        public static Reading unidentified() {
            return new Reading(State.UNREADABLE, null);
        }

        public boolean identified() {
            return identity != null;
        }

        /** The device id, or null when the player is unidentified. */
        public String deviceId() {
            return identity == null ? null : identity.id;
        }

        /** User-visible name, or null when the player is unidentified. */
        public String deviceName() {
            return identity == null ? null : identity.name;
        }
    }

    private static final String MIME = "application/json";

    private PlayerMarkerStore() {
    }

    /** Reads the marker without creating anything. Never throws. */
    public static Reading read(Context context, Uri treeUri) {
        if (context == null || treeUri == null) {
            return new Reading(State.UNREADABLE, null);
        }
        try {
            DestRepo repo = new DestRepo(context, treeUri);
            DestRepo.Node node = repo.findChild(repo.root(), PlayerIdentity.MARKER_FILE_NAME);
            if (node == null) {
                return new Reading(State.ABSENT, null);
            }
            if (node.directory) {
                return new Reading(State.MALFORMED, null);
            }
            String raw = readBounded(context, treeUri, node.docId);
            if (raw == null) {
                return new Reading(State.UNREADABLE, null);
            }
            PlayerIdentity identity = PlayerIdentity.parse(raw);
            return identity == null
                    ? new Reading(State.MALFORMED, null)
                    : new Reading(State.IDENTIFIED, identity);
        } catch (Throwable t) {
            return new Reading(State.UNREADABLE, null);
        }
    }

    /**
     * Reads the marker, creating one when the folder has none. An existing marker is never
     * replaced; a folder that cannot hold a marker is reported as unreadable so the caller can say
     * "unidentified" rather than pretend.
     */
    public static Reading ensure(Context context, Uri treeUri, String suggestedName) {
        Reading current = read(context, treeUri);
        if (current.state != State.ABSENT) {
            return current;
        }
        if (context == null || treeUri == null) {
            return new Reading(State.UNREADABLE, null);
        }
        PlayerIdentity created = PlayerIdentity.create(suggestedName, System.currentTimeMillis());
        DestRepo.Node node = null;
        try {
            DestRepo repo = new DestRepo(context, treeUri);
            node = repo.createFile(repo.root(), PlayerIdentity.MARKER_FILE_NAME, MIME);
            try (OutputStream out = repo.openWrite(node)) {
                out.write(created.toJson().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            deleteQuietly(context, treeUri, node);
            return new Reading(State.UNREADABLE, null);
        }
        Reading stored = read(context, treeUri);
        if (stored.state == State.IDENTIFIED && created.id.equals(stored.identity.id)) {
            return stored;
        }
        // The write did not survive a read-back, so there is no identity to claim.
        deleteQuietly(context, treeUri, node);
        return new Reading(State.UNREADABLE, null);
    }

    private static void deleteQuietly(Context context, Uri treeUri, DestRepo.Node node) {
        if (node == null || context == null || treeUri == null) {
            return;
        }
        try {
            new DestRepo(context, treeUri).delete(node);
        } catch (Throwable ignored) {
            // Leaving an unreadable marker behind is reported as unidentified, not repaired.
        }
    }

    /** Reads at most {@link PlayerIdentity#MAX_MARKER_CHARS} bytes; null when it cannot be read. */
    private static String readBounded(Context context, Uri treeUri, String docId) {
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
            byte[] chunk = new byte[256];
            int total = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > PlayerIdentity.MAX_MARKER_CHARS) {
                    return null;
                }
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
