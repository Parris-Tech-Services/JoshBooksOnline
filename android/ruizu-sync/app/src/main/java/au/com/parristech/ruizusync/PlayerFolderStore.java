package au.com.parristech.ruizusync;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;

import au.com.parristech.ruizusync.core.PlayerBinding;

/**
 * Remembers the player folder so reconnecting the same player repopulates the destination.
 *
 * <p>Two RUIZU players here report the same SAF volume UUID ({@code 0000-006F}), so the volume
 * alone cannot say which unit is attached. Entries are keyed by the device marker id read from the
 * player itself ({@link PlayerMarkerStore}), with the original volume key kept as a fallback so
 * folders saved before markers existed are still found. Reuse of a remembered folder is decided by
 * {@link PlayerBinding#decide(boolean, String, String)}: a folder remembered for one unit is never
 * silently applied to another.
 */
public final class PlayerFolderStore {

    private static final String PREFS = "ruizu_player_folders";
    private static final String TREE = "tree:";
    private static final String LABEL = "label:";
    /** Device marker id recorded against a volume, i.e. the player the volume entry belongs to. */
    private static final String BOUND_DEVICE = "bound:";

    private PlayerFolderStore() {
    }

    /**
     * Remembers {@code treeUri} for the connected player.
     *
     * @param deviceId marker id of the player this folder belongs to, or null when the player is
     *                 unidentified — in which case the entry stays volume-keyed and later lookups
     *                 fall back to the original behaviour.
     */
    public static void remember(Context context, Uri treeUri, String label, String deviceId) {
        if (context == null || treeUri == null) {
            return;
        }
        String volume = volumeKey(treeUri);
        if (volume == null) {
            return;
        }
        String safeLabel = label == null ? "" : label;
        String treeText = treeUri.toString();
        SharedPreferences.Editor editor = prefs(context).edit();
        String primaryKey = PlayerBinding.storeKey(deviceId, volume);
        if (primaryKey != null) {
            editor.putString(TREE + primaryKey, treeText).putString(LABEL + primaryKey, safeLabel);
        }
        // The volume entry is both the fallback for unidentified players and the pointer that lets a
        // lookup start from the attached volume at all.
        editor.putString(TREE + volume, treeText).putString(LABEL + volume, safeLabel);
        String device = trimToNull(deviceId);
        if (device == null) {
            editor.remove(BOUND_DEVICE + volume);
        } else {
            editor.putString(BOUND_DEVICE + volume, device);
        }
        editor.apply();
    }

    /** Remembered folder for this volume (the original key shape), or null. */
    public static String treeForVolume(Context context, String volumeUuid) {
        String volume = trimToNull(volumeUuid);
        if (volume == null) {
            return null;
        }
        return prefs(context).getString(TREE + volume, null);
    }

    public static String labelForVolume(Context context, String volumeUuid) {
        String volume = trimToNull(volumeUuid);
        if (volume == null) {
            return "";
        }
        return prefs(context).getString(LABEL + volume, "");
    }

    /** Remembered folder for one identified player, or null. */
    public static String treeForDevice(Context context, String deviceId) {
        String device = trimToNull(deviceId);
        if (device == null) {
            return null;
        }
        return prefs(context).getString(TREE + PlayerBinding.storeKey(device, null), null);
    }

    /** Device id recorded for this volume's remembered folder, or null when none was recorded. */
    public static String recordedDeviceIdForVolume(Context context, String volumeUuid) {
        String volume = trimToNull(volumeUuid);
        if (volume == null) {
            return null;
        }
        return prefs(context).getString(BOUND_DEVICE + volume, null);
    }

    /** True when the persisted read/write permission for this tree is still held. */
    public static boolean hasPersistedPermission(Context context, Uri treeUri) {
        if (context == null || treeUri == null) {
            return false;
        }
        try {
            for (android.content.UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
                if (permission.getUri().equals(treeUri) && permission.isWritePermission()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Fall through to false.
        }
        return false;
    }

    /** Everything the UI needs to decide whether the remembered folder may be reused. */
    public static final class Restore {
        public final PlayerBinding.Restore decision;
        /** Folder to reuse; only meaningful when {@link #decision} allows reuse. */
        public final Uri treeUri;
        /** Label for the folder to reuse, or for the remembered entry when refusing. */
        public final String label;
        /** Device id recorded with the remembered folder, or null for a legacy entry. */
        public final String storedDeviceId;
        public final String storedLabel;
        /** Device id read from the attached player, or null when unidentified. */
        public final String connectedDeviceId;
        public final String connectedDeviceName;
        public final PlayerMarkerStore.State connectedState;
        public final boolean permissionHeld;

        Restore(PlayerBinding.Restore decision, Uri treeUri, String label, String storedDeviceId,
                String storedLabel, String connectedDeviceId, String connectedDeviceName,
                PlayerMarkerStore.State connectedState, boolean permissionHeld) {
            this.decision = decision;
            this.treeUri = treeUri;
            this.label = label == null ? "" : label;
            this.storedDeviceId = storedDeviceId;
            this.storedLabel = storedLabel == null ? "" : storedLabel;
            this.connectedDeviceId = connectedDeviceId;
            this.connectedDeviceName = connectedDeviceName;
            this.connectedState = connectedState;
            this.permissionHeld = permissionHeld;
        }
    }

    /**
     * Looks at the remembered folder for a connected volume and decides whether it belongs to the
     * attached player.
     *
     * @return null when this volume has no remembered folder at all.
     */
    public static Restore evaluateRestore(Context context, String volumeUuid) {
        if (context == null) {
            return null;
        }
        String volume = trimToNull(volumeUuid);
        if (volume == null) {
            return null;
        }
        String pointerText = treeForVolume(context, volume);
        if (pointerText == null || pointerText.trim().isEmpty()) {
            return null;
        }
        Uri pointer;
        try {
            pointer = Uri.parse(pointerText);
        } catch (Throwable t) {
            return null;
        }
        if (pointer == null) {
            return null;
        }
        PlayerMarkerStore.Reading marker = PlayerMarkerStore.read(context, pointer);
        String connectedId = marker.deviceId();
        String storedId = recordedDeviceIdForVolume(context, volume);
        String ownTree = treeForDevice(context, connectedId);
        boolean hasOwnEntry = ownTree != null && !ownTree.trim().isEmpty();
        PlayerBinding.Restore decision = PlayerBinding.decide(hasOwnEntry, storedId, connectedId);

        Uri chosen = pointer;
        String labelKey;
        if (PlayerBinding.allowsAutomaticReuse(decision)) {
            if (decision == PlayerBinding.Restore.MATCH && hasOwnEntry) {
                Uri own;
                try {
                    own = Uri.parse(ownTree);
                } catch (Throwable t) {
                    own = pointer;
                }
                chosen = own == null ? pointer : own;
            }
            labelKey = PlayerBinding.storeKey(
                    decision == PlayerBinding.Restore.MATCH ? connectedId : storedId, volume);
        } else {
            labelKey = PlayerBinding.storeKey(storedId, volume);
        }
        String label = labelKey == null ? "" : prefs(context).getString(LABEL + labelKey, "");
        String storedKey = PlayerBinding.storeKey(storedId, volume);
        String storedLabel = storedKey == null ? "" : prefs(context).getString(LABEL + storedKey, "");
        return new Restore(decision, chosen, label, storedId, storedLabel,
                connectedId, marker.deviceName(), marker.state,
                hasPersistedPermission(context, chosen));
    }

    /** Volume UUID (or "primary") from a tree document id such as "1A2B-3C4D:Music". */
    private static String volumeKey(Uri treeUri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            int colon = docId.indexOf(':');
            return colon > 0 ? docId.substring(0, colon) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
