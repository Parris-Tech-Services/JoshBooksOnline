package au.com.parristech.ruizusync.core;

/**
 * Binds a remembered player folder to the player it was remembered for, and decides whether the
 * connected player may reuse it.
 *
 * <p>Both RUIZU players report the same SAF volume UUID, so "the saved folder for this volume" is
 * not evidence that the attached unit is the one the user chose it for. The store therefore keeps
 * two kinds of entry: one keyed by the device marker id (preferred, distinguishes two units that
 * share a volume) and the original volume-keyed entry (fallback for folders that have no marker
 * yet, so an existing install is not silently forgotten).
 */
public final class PlayerBinding {

    private static final String DEVICE_PREFIX = "device:";

    private PlayerBinding() {
    }

    /**
     * Preference key for a saved folder: the device marker id when the player is identified, and
     * the volume UUID otherwise. The volume form is the original key shape, so folders remembered
     * by an install that predates device markers stay readable.
     *
     * @return the key, or null when neither a device id nor a volume UUID is known.
     */
    public static String storeKey(String deviceId, String volumeUuid) {
        String device = trimToNull(deviceId);
        if (device != null) {
            return DEVICE_PREFIX + device;
        }
        return trimToNull(volumeUuid);
    }

    /** True for the marker-id key form; the persisted shape of a saved entry. */
    public static boolean isDeviceKey(String key) {
        return key != null && key.startsWith(DEVICE_PREFIX);
    }

    /** What the app may do with the remembered folder for the connected player. */
    public enum Restore {
        /** The remembered folder is bound to the connected player: reuse it. */
        MATCH,
        /** Nothing was ever recorded about identity: keep the original single-player behaviour. */
        LEGACY,
        /** A different unit is attached: never auto-select the remembered folder. */
        REFUSE_DIFFERENT_DEVICE,
        /** An identity was recorded but the attached player has none readable: refuse and ask. */
        REFUSE_UNVERIFIED
    }

    /**
     * @param connectedHasOwnEntry the connected player has a folder remembered under its own
     *                             marker id, which is stronger evidence than the volume entry.
     * @param storedDeviceId       device id recorded with the remembered folder, blank when the
     *                             entry predates device markers.
     * @param connectedDeviceId    device id read from the attached player, blank when unreadable or
     *                             absent.
     */
    public static Restore decide(boolean connectedHasOwnEntry,
                                 String storedDeviceId,
                                 String connectedDeviceId) {
        String stored = trimToNull(storedDeviceId);
        String connected = trimToNull(connectedDeviceId);
        if (connectedHasOwnEntry && connected != null) {
            return Restore.MATCH;
        }
        if (stored == null) {
            return Restore.LEGACY;
        }
        if (connected == null) {
            return Restore.REFUSE_UNVERIFIED;
        }
        return stored.equals(connected) ? Restore.MATCH : Restore.REFUSE_DIFFERENT_DEVICE;
    }

    /** True when the remembered folder may be selected without asking the user first. */
    public static boolean allowsAutomaticReuse(Restore restore) {
        return restore == Restore.MATCH || restore == Restore.LEGACY;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
