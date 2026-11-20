package au.com.parristech.ruizusync;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import au.com.parristech.ruizusync.model.SourceItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Named playlists (ordered source lists) saved on the phone, ready to re-export. */
public final class PlaylistStore {

    private static final String PREFS = "ruizu_playlists";
    private static final String KEY = "playlists";

    public static final class Playlist {
        public final String name;
        public final List<SourceItem> items;

        Playlist(String name, List<SourceItem> items) {
            this.name = name;
            this.items = items;
        }
    }

    private PlaylistStore() {
    }

    public static synchronized List<String> names(Context context) {
        List<String> names = new ArrayList<>();
        JSONObject root = read(context);
        JSONArray array = root.names();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                names.add(array.optString(i));
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static synchronized Playlist load(Context context, String name) {
        try {
            JSONObject root = read(context);
            JSONArray items = root.optJSONArray(name);
            if (items == null) {
                return null;
            }
            List<SourceItem> sources = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                SourceItem item = decode(items.getJSONObject(i));
                if (item != null) {
                    sources.add(item);
                }
            }
            return new Playlist(name, sources);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static synchronized void save(Context context, String name, List<SourceItem> items) {
        if (name == null || name.trim().isEmpty() || items == null) {
            return;
        }
        try {
            JSONObject root = read(context);
            JSONArray array = new JSONArray();
            for (SourceItem item : items) {
                array.put(encode(item));
            }
            root.put(name.trim(), array);
            write(context, root);
        } catch (Throwable ignored) {
            // Best effort.
        }
    }

    public static synchronized void delete(Context context, String name) {
        try {
            JSONObject root = read(context);
            root.remove(name);
            write(context, root);
        } catch (Throwable ignored) {
            // Best effort.
        }
    }

    private static JSONObject encode(SourceItem item) throws Exception {
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
        return entry;
    }

    private static SourceItem decode(JSONObject entry) {
        try {
            List<String> dirs = new ArrayList<>();
            JSONArray dirArray = entry.optJSONArray("dirs");
            if (dirArray != null) {
                for (int d = 0; d < dirArray.length(); d++) {
                    dirs.add(dirArray.getString(d));
                }
            }
            return new SourceItem(Uri.parse(entry.getString("uri")),
                    entry.optString("name", "Audio"),
                    entry.optLong("size", -1),
                    entry.optString("mime", ""),
                    entry.optString("folder", ""),
                    dirs,
                    null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JSONObject read(Context context) {
        try {
            String raw = prefs(context).getString(KEY, "{}");
            return new JSONObject(raw == null ? "{}" : raw);
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    private static void write(Context context, JSONObject root) {
        prefs(context).edit().putString(KEY, root.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
