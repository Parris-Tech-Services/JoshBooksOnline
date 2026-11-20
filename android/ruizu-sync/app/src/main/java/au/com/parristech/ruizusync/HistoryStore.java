package au.com.parristech.ruizusync;

import android.content.Context;
import android.content.SharedPreferences;

/** Remembers the last run summary and log so the user can review or copy it later. */
public final class HistoryStore {

    private static final String PREFS = "ruizu_history";
    private static final int MAX_LOG_CHARS = 12000;

    private HistoryStore() {
    }

    public static void saveLastRun(Context context, String summary, String log, long finishedAt) {
        if (context == null) {
            return;
        }
        String clipped = log == null ? "" : (log.length() > MAX_LOG_CHARS ? log.substring(log.length() - MAX_LOG_CHARS) : log);
        prefs(context).edit()
                .putString("summary", summary == null ? "" : summary)
                .putString("log", clipped)
                .putLong("at", finishedAt)
                .apply();
    }

    public static String lastRunSummary(Context context) {
        return prefs(context).getString("summary", "");
    }

    public static String lastRunLog(Context context) {
        return prefs(context).getString("log", "");
    }

    public static long lastRunAt(Context context) {
        return prefs(context).getLong("at", 0L);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
