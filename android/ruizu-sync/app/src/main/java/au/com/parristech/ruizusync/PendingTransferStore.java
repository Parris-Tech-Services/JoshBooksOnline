package au.com.parristech.ruizusync;

import android.content.Context;
import android.content.SharedPreferences;

import au.com.parristech.ruizusync.core.DuplicateMode;
import au.com.parristech.ruizusync.core.FolderPlanner;
import au.com.parristech.ruizusync.model.SourceItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persists an unfinished transfer queue so a long copy survives the process being killed.
 * Saved every few files; cleared when the run completes.
 */
public final class PendingTransferStore {

    private static final String PREFS = "ruizu_pending_transfer";
    private static final String KEY = "pending";

    public static final class Pending {
        public final TransferManager.Request request;
        public final int processed;
        public final long savedAt;

        Pending(TransferManager.Request request, int processed, long savedAt) {
            this.request = request;
            this.processed = processed;
            this.savedAt = savedAt;
        }

        public int remaining() {
            return Math.max(0, request.sources.size() - processed);
        }
    }

    private PendingTransferStore() {
    }

    public static void save(Context context, TransferManager.Request request, int processed) {
        if (context == null || request == null || request.destTree == null) {
            return;
        }
        try {
            JSONObject root = new JSONObject();
            root.put("dest", request.destTree.toString());
            root.put("organise", request.organiseMode.name());
            root.put("duplicates", request.duplicateMode.name());
            root.put("scopeFolder", request.duplicatesInTargetFolderOnly);
            root.put("playlists", request.writePlaylists);
            root.put("convert", request.convertToMp3);
            root.put("processed", processed);
            root.put("savedAt", System.currentTimeMillis());
            JSONArray sources = new JSONArray();
            for (SourceItem item : request.sources) {
                JSONObject entry = new JSONObject();
                entry.put("uri", item.uri.toString());
                entry.put("name", item.displayName);
                entry.put("size", item.size);
                entry.put("mime", item.mimeType);
                entry.put("folder", item.sourceFolder);
                JSONArray dirs = new JSONArray();
                for (String dir : item.treeRelDirs) {
                    dirs.put(dir);
                }
                entry.put("dirs", dirs);
                if (item.problem != null) {
                    entry.put("problem", item.problem);
                }
                sources.put(entry);
            }
            root.put("sources", sources);
            prefs(context).edit().putString(KEY, root.toString()).apply();
        } catch (Throwable ignored) {
            // Persistence is best effort; the in-memory queue still works.
        }
    }

    public static boolean hasPending(Context context) {
        String raw = prefs(context).getString(KEY, null);
        return raw != null && !raw.isEmpty();
    }

    public static Pending load(Context context) {
        String raw = prefs(context).getString(KEY, null);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(raw);
            JSONArray array = root.optJSONArray("sources");
            if (array == null || array.length() == 0) {
                return null;
            }
            List<SourceItem> sources = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.getJSONObject(i);
                List<String> dirs = new ArrayList<>();
                JSONArray dirArray = entry.optJSONArray("dirs");
                if (dirArray != null) {
                    for (int d = 0; d < dirArray.length(); d++) {
                        dirs.add(dirArray.getString(d));
                    }
                }
                sources.add(new SourceItem(
                        android.net.Uri.parse(entry.getString("uri")),
                        entry.optString("name", "Audio"),
                        entry.optLong("size", -1),
                        entry.optString("mime", ""),
                        entry.optString("folder", ""),
                        dirs,
                        entry.has("problem") ? entry.optString("problem") : null));
            }
            android.net.Uri dest = android.net.Uri.parse(root.getString("dest"));
            FolderPlanner.OrganiseMode organise = FolderPlanner.OrganiseMode.valueOf(
                    root.optString("organise", FolderPlanner.OrganiseMode.FLAT.name()));
            DuplicateMode duplicates = DuplicateMode.fromStoredName(
                    root.optString("duplicates", DuplicateMode.SKIP_FAST.name()),
                    DuplicateMode.SKIP_EXACT);
            TransferManager.Request request = new TransferManager.Request(
                    sources, dest, organise, duplicates,
                    root.optBoolean("scopeFolder", false),
                    root.optBoolean("playlists", false),
                    "", null, root.optBoolean("convert", false), null);
            return new Pending(request, root.optInt("processed", 0), root.optLong("savedAt", 0));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(KEY).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
