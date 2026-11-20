package au.com.parristech.ruizusync;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;

/**
 * Remembers the player folder per removable volume (keyed by the volume UUID that appears in the
 * SAF document id), so reconnecting the same player repopulates the destination automatically.
 */
public final class PlayerFolderStore {

    private static final String PREFS = "ruizu_player_folders";

    private PlayerFolderStore() {
    }

    public static void remember(Context context, Uri treeUri, String label) {
        if (context == null || treeUri == null) {
            return;
        }
        String key = volumeKey(treeUri);
        if (key == null) {
            return;
        }
        prefs(context).edit()
                .putString("tree:" + key, treeUri.toString())
                .putString("label:" + key, label == null ? "" : label)
                .apply();
    }

    public static String treeForVolume(Context context, String volumeUuid) {
        if (volumeUuid == null || volumeUuid.isEmpty()) {
            return null;
        }
        return prefs(context).getString("tree:" + volumeUuid, null);
    }

    public static String labelForVolume(Context context, String volumeUuid) {
        if (volumeUuid == null || volumeUuid.isEmpty()) {
            return "";
        }
        return prefs(context).getString("label:" + volumeUuid, "");
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

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
