package au.com.parristech.ruizusync;

import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;

/** Persist the choice separately from the OS grant, and restore only while both remain valid. */
public final class DestinationAccess {
    private DestinationAccess() {}
    public static boolean writable(Context context, Uri tree) {
        if (tree == null || !DocumentsContract.isTreeUri(tree)) return false;
        for (UriPermission grant : context.getContentResolver().getPersistedUriPermissions()) {
            if (tree.equals(grant.getUri()) && grant.isReadPermission() && grant.isWritePermission()) return true;
        }
        return false;
    }
    public static void remember(Context context, Uri tree) {
        if (!writable(context, tree)) return;
        context.getSharedPreferences("ruizu", Context.MODE_PRIVATE).edit().putString("destination_tree", tree.toString()).apply();
    }
    public static Uri restore(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("ruizu", Context.MODE_PRIVATE);
        String saved = prefs.getString("destination_tree", prefs.getString("folder_music", null));
        if (saved == null) return null;
        try {
            Uri tree = Uri.parse(saved);
            return writable(context, tree) ? tree : null;
        } catch (RuntimeException invalid) { return null; }
    }
}
