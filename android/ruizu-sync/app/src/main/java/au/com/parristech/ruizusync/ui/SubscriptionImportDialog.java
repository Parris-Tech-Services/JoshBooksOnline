package au.com.parristech.ruizusync.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;
import au.com.parristech.ruizusync.SubscriptionStore;
import au.com.parristech.ruizusync.core.Opml;
import java.util.ArrayList;
import java.util.List;

/** Selection is temporary until Import is pressed; existing subscriptions are never replaced. */
public final class SubscriptionImportDialog {
    private SubscriptionImportDialog() {}

    public static AlertDialog show(Activity activity, List<Opml.Feed> parsed, Runnable onImported) {
        List<Opml.Feed> feeds = new ArrayList<>();
        for (Opml.Feed feed : parsed) {
            if (!SubscriptionStore.contains(activity, feed.url)) feeds.add(feed);
        }
        if (feeds.isEmpty()) {
            Toast.makeText(activity, parsed.isEmpty() ? "No feeds found in that OPML file"
                    : "All feeds are already subscribed", Toast.LENGTH_SHORT).show();
            return null;
        }
        String[] labels = new String[feeds.size()];
        boolean[] selected = new boolean[feeds.size()];
        for (int i = 0; i < feeds.size(); i++) {
            labels[i] = feeds.get(i).title;
            selected[i] = true;
        }
        return new AlertDialog.Builder(activity)
                .setTitle("Choose subscriptions to import")
                .setMultiChoiceItems(labels, selected, (dialog, which, checked) -> selected[which] = checked)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Import selected", (dialog, which) -> {
                    int added = 0;
                    for (int i = 0; i < feeds.size(); i++) {
                        Opml.Feed feed = feeds.get(i);
                        if (selected[i] && !SubscriptionStore.contains(activity, feed.url)) {
                            SubscriptionStore.add(activity, feed.url, feed.title);
                            if (SubscriptionStore.contains(activity, feed.url)) added++;
                        }
                    }
                    onImported.run();
                    Toast.makeText(activity, "Imported " + added + (added == 1 ? " feed" : " feeds"),
                            Toast.LENGTH_SHORT).show();
                }).show();
    }
}
