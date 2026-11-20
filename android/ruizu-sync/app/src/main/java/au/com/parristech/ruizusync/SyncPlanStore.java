package au.com.parristech.ruizusync;

import android.content.Context;
import android.content.SharedPreferences;

import au.com.parristech.ruizusync.core.DuplicateMode;
import au.com.parristech.ruizusync.core.FolderPlanner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A saved "folder pair" style transfer plan: source folder -> player folder plus options. */
public final class SyncPlanStore {

    private static final String PREFS = "ruizu_sync_plan";
    private static final String KEY = "plan";

    public static final class Plan {
        public final String sourceTree;
        public final String sourceName;
        public final String destTree;
        public final String destLabel;
        public final List<String> destPrefix;
        public final FolderPlanner.OrganiseMode organise;
        public final DuplicateMode duplicates;
        public final boolean verify;
        public final boolean scopeFolder;
        public final boolean playlists;
        public final boolean convert;
        public final String playlistName;
        public final boolean enabled;

        public Plan(String sourceTree, String sourceName, String destTree, String destLabel,
                    List<String> destPrefix, FolderPlanner.OrganiseMode organise,
                    DuplicateMode duplicates, boolean verify, boolean scopeFolder,
                    boolean playlists, boolean convert, String playlistName, boolean enabled) {
            this.sourceTree = sourceTree == null ? "" : sourceTree;
            this.sourceName = sourceName == null ? "" : sourceName;
            this.destTree = destTree == null ? "" : destTree;
            this.destLabel = destLabel == null ? "" : destLabel;
            this.destPrefix = destPrefix == null ? new ArrayList<>() : new ArrayList<>(destPrefix);
            this.organise = organise == null ? FolderPlanner.OrganiseMode.FLAT : organise;
            this.duplicates = duplicates == null ? DuplicateMode.SKIP_FAST : duplicates;
            this.verify = verify;
            this.scopeFolder = scopeFolder;
            this.playlists = playlists;
            this.convert = convert;
            this.playlistName = playlistName == null ? "" : playlistName;
            this.enabled = enabled;
        }
    }

    private SyncPlanStore() {
    }

    public static boolean has(Context context) {
        return prefs(context).contains(KEY);
    }

    public static Plan load(Context context) {
        String raw = prefs(context).getString(KEY, null);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(raw);
            List<String> prefix = new ArrayList<>();
            JSONArray array = root.optJSONArray("prefix");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    prefix.add(array.optString(i));
                }
            }
            return new Plan(root.optString("sourceTree"), root.optString("sourceName"),
                    root.optString("destTree"), root.optString("destLabel"), prefix,
                    FolderPlanner.OrganiseMode.valueOf(root.optString("organise", FolderPlanner.OrganiseMode.FLAT.name())),
                    DuplicateMode.valueOf(root.optString("duplicates", DuplicateMode.SKIP_FAST.name())),
                    root.optBoolean("verify", true), root.optBoolean("scope", false),
                    root.optBoolean("playlists", false), root.optBoolean("convert", false),
                    root.optString("playlistName", ""), root.optBoolean("enabled", true));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void save(Context context, Plan plan) {
        if (plan == null) {
            return;
        }
        try {
            JSONObject root = new JSONObject();
            root.put("sourceTree", plan.sourceTree);
            root.put("sourceName", plan.sourceName);
            root.put("destTree", plan.destTree);
            root.put("destLabel", plan.destLabel);
            JSONArray prefix = new JSONArray();
            for (String segment : plan.destPrefix) {
                prefix.put(segment);
            }
            root.put("prefix", prefix);
            root.put("organise", plan.organise.name());
            root.put("duplicates", plan.duplicates.name());
            root.put("verify", plan.verify);
            root.put("scope", plan.scopeFolder);
            root.put("playlists", plan.playlists);
            root.put("convert", plan.convert);
            root.put("playlistName", plan.playlistName);
            root.put("enabled", plan.enabled);
            prefs(context).edit().putString(KEY, root.toString()).apply();
        } catch (Throwable ignored) {
            // Best effort.
        }
    }

    public static void setEnabled(Context context, boolean enabled) {
        Plan plan = load(context);
        if (plan == null) {
            return;
        }
        save(context, new Plan(plan.sourceTree, plan.sourceName, plan.destTree, plan.destLabel,
                plan.destPrefix, plan.organise, plan.duplicates, plan.verify, plan.scopeFolder,
                plan.playlists, plan.convert, plan.playlistName, enabled));
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(KEY).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
