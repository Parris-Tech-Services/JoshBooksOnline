package au.com.parristech.ruizusync;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Last-run summary plus a durable, restart-surviving transfer history (JSON lines). */
public final class HistoryStore {

    private static final String PREFS = "ruizu_history";
    private static final String FILE = "transfer-history.jsonl";
    private static final int MAX_RECORDS = 300;
    private static final int MAX_LOG_CHARS = 12000;

    public static final class Run {
        public final long at;
        public final String summary;
        public final int copied;
        public final int failed;
        public final int duplicates;
        public final int unsupported;
        public final int retried;
        public final int playlists;
        public final String log;

        public Run(long at, String summary, int copied, int failed, int duplicates, int unsupported,
                   int retried, int playlists, String log) {
            this.at = at;
            this.summary = summary == null ? "" : summary;
            this.copied = copied;
            this.failed = failed;
            this.duplicates = duplicates;
            this.unsupported = unsupported;
            this.retried = retried;
            this.playlists = playlists;
            this.log = log == null ? "" : log;
        }

        String toJson() {
            try {
                JSONObject root = new JSONObject();
                root.put("at", at);
                root.put("summary", summary);
                root.put("copied", copied);
                root.put("failed", failed);
                root.put("duplicates", duplicates);
                root.put("unsupported", unsupported);
                root.put("retried", retried);
                root.put("playlists", playlists);
                root.put("log", clip(log));
                return root.toString();
            } catch (Throwable ignored) {
                return "{}";
            }
        }
    }

    private HistoryStore() {
    }

    /** Records a completed run durably and keeps the quick "last run" fields in sync. */
    public static void record(Context context, Run run) {
        if (context == null || run == null) {
            return;
        }
        append(context, run);
        prefs(context).edit()
                .putString("summary", run.summary)
                .putString("log", clip(run.log))
                .putLong("at", run.at)
                .apply();
    }

    public static List<Run> recentRuns(Context context, int limit) {
        List<Run> runs = readAll(context);
        Collections.reverse(runs);
        if (runs.size() > limit) {
            return new ArrayList<>(runs.subList(0, limit));
        }
        return runs;
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

    // ---- storage ---------------------------------------------------------------------------

    private static synchronized void append(Context context, Run run) {
        List<String> lines = readLines(context);
        lines.add(run.toJson());
        while (lines.size() > MAX_RECORDS) {
            lines.remove(0);
        }
        writeLines(context, lines);
    }

    private static List<Run> readAll(Context context) {
        List<Run> runs = new ArrayList<>();
        for (String line : readLines(context)) {
            try {
                JSONObject root = new JSONObject(line);
                runs.add(new Run(root.optLong("at"), root.optString("summary"),
                        root.optInt("copied"), root.optInt("failed"), root.optInt("duplicates"),
                        root.optInt("unsupported"), root.optInt("retried"), root.optInt("playlists"),
                        root.optString("log")));
            } catch (Throwable ignored) {
                // Skip malformed lines.
            }
        }
        return runs;
    }

    private static List<String> readLines(Context context) {
        List<String> lines = new ArrayList<>();
        File file = file(context);
        if (!file.isFile()) {
            return lines;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lines.add(line);
                }
            }
        } catch (Throwable ignored) {
            // Treat an unreadable file as empty.
        }
        return lines;
    }

    private static void writeLines(Context context, List<String> lines) {
        File file = file(context);
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        } catch (Throwable ignored) {
            return;
        }
        if (!temp.renameTo(file)) {
            // Best effort; keep the temp file rather than corrupt the history.
            temp.delete();
        }
    }

    private static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE);
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > MAX_LOG_CHARS ? text.substring(text.length() - MAX_LOG_CHARS) : text;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
