package au.com.parristech.ruizusync;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Remembered podcast feeds and the episodes already seen/downloaded. */
public final class SubscriptionStore {

    private static final String PREFS = "ruizu_subscriptions";
    private static final String KEY = "feeds";

    public static final class Feed {
        public final String url;
        public final String title;
        public final long lastChecked;
        public final Set<String> seen;

        Feed(String url, String title, long lastChecked, Set<String> seen) {
            this.url = url;
            this.title = title;
            this.lastChecked = lastChecked;
            this.seen = seen;
        }
    }

    private SubscriptionStore() {
    }

    public static synchronized List<Feed> list(Context context) {
        List<Feed> feeds = new ArrayList<>();
        for (JSONObject entry : entries(context)) {
            String url = entry.optString("url", "");
            if (url.isEmpty()) {
                continue;
            }
            Set<String> seen = new LinkedHashSet<>();
            JSONArray seenArray = entry.optJSONArray("seen");
            if (seenArray != null) {
                for (int i = 0; i < seenArray.length(); i++) {
                    seen.add(seenArray.optString(i));
                }
            }
            feeds.add(new Feed(url, entry.optString("title", url),
                    entry.optLong("checked", 0L), seen));
        }
        return feeds;
    }

    public static synchronized boolean contains(Context context, String url) {
        for (Feed feed : list(context)) {
            if (feed.url.equalsIgnoreCase(url)) {
                return true;
            }
        }
        return false;
    }

    public static synchronized void add(Context context, String url, String title) {
        if (url == null || url.trim().isEmpty() || contains(context, url)) {
            return;
        }
        JSONArray all = entriesArray(context);
        try {
            JSONObject entry = new JSONObject();
            entry.put("url", url.trim());
            entry.put("title", title == null || title.trim().isEmpty() ? url.trim() : title.trim());
            entry.put("checked", System.currentTimeMillis());
            entry.put("seen", new JSONArray());
            all.put(entry);
            write(context, all);
        } catch (Throwable ignored) {
            // Best effort.
        }
    }

    public static synchronized void remove(Context context, String url) {
        JSONArray all = entriesArray(context);
        JSONArray kept = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject entry = all.optJSONObject(i);
            if (entry != null && !entry.optString("url").equalsIgnoreCase(url)) {
                kept.put(entry);
            }
        }
        write(context, kept);
    }

    public static synchronized void markChecked(Context context, String url, Collection<String> seen,
                                                String title) {
        JSONArray all = entriesArray(context);
        for (int i = 0; i < all.length(); i++) {
            JSONObject entry = all.optJSONObject(i);
            if (entry == null || !entry.optString("url").equalsIgnoreCase(url)) {
                continue;
            }
            try {
                entry.put("checked", System.currentTimeMillis());
                if (title != null && !title.trim().isEmpty()) {
                    entry.put("title", title.trim());
                }
                Set<String> merged = new LinkedHashSet<>();
                JSONArray existing = entry.optJSONArray("seen");
                if (existing != null) {
                    for (int s = 0; s < existing.length(); s++) {
                        merged.add(existing.optString(s));
                    }
                }
                if (seen != null) {
                    merged.addAll(seen);
                }
                JSONArray updated = new JSONArray();
                for (String item : merged) {
                    updated.put(item);
                }
                entry.put("seen", updated);
            } catch (Throwable ignored) {
                // Keep whatever was there.
            }
        }
        write(context, all);
    }

    private static List<JSONObject> entries(Context context) {
        List<JSONObject> list = new ArrayList<>();
        JSONArray all = entriesArray(context);
        for (int i = 0; i < all.length(); i++) {
            JSONObject entry = all.optJSONObject(i);
            if (entry != null) {
                list.add(entry);
            }
        }
        return list;
    }

    private static JSONArray entriesArray(Context context) {
        try {
            String raw = prefs(context).getString(KEY, "[]");
            return new JSONArray(raw == null ? "[]" : raw);
        } catch (Throwable ignored) {
            return new JSONArray();
        }
    }

    private static void write(Context context, JSONArray array) {
        prefs(context).edit().putString(KEY, array.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
