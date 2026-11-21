package au.com.parristech.ruizusync;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import au.com.parristech.ruizusync.core.Opml;
import au.com.parristech.ruizusync.ui.MainActivity;
import au.com.parristech.ruizusync.ui.SubscriptionImportDialog;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SubscriptionImportTest {
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences prefs;
    private String original;
    private Activity activity;
    private AlertDialog dialog;
    private List<Opml.Feed> parsed;

    @Before public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("only the isolated test installation may be modified",
                context.getPackageName().endsWith(".saftest"));
        prefs = context.getSharedPreferences("ruizu_subscriptions", Context.MODE_PRIVATE);
        original = prefs.getString("feeds", null);
        prefs.edit().putString("feeds", "[]").commit();
        SubscriptionStore.add(context, "https://example.org/existing", "Keep my title");
        // Exercise the Android XML implementation, not only the desktop JVM parser.
        parsed = Opml.parse("<opml><body>"
                + "<outline text=\"Replacement title\" xmlUrl=\"https://example.org/existing\"/>"
                + "<outline text=\"祈り &amp; Music\" xmlUrl=\"https://example.org/first?a=1&amp;b=2\"/>"
                + "<outline text=\"Second\" xmlUrl=\"https://example.org/second\"/>"
                + "</body></opml>");
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),
                    "android.permission.POST_NOTIFICATIONS");
        }
        activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(() -> dialog = SubscriptionImportDialog.show(activity, parsed, () -> {}));
    }

    @After public void tearDown() {
        if (activity != null) {
            instrumentation.runOnMainSync(() -> {
                if (dialog != null) dialog.dismiss();
                activity.finishAndRemoveTask();
            });
            instrumentation.waitForIdleSync();
        }
        if (prefs != null) {
            SharedPreferences.Editor edit = prefs.edit();
            if (original == null) edit.remove("feeds"); else edit.putString("feeds", original);
            edit.commit();
        }
    }

    @Test public void previewAndCancelPreserveExistingSubscriptions() {
        assertEquals(1, SubscriptionStore.list(context).size());
        instrumentation.runOnMainSync(() -> {
            assertEquals("existing feeds must not appear as new imports", 2, dialog.getListView().getCount());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        });
        instrumentation.waitForIdleSync();
        assertEquals(1, SubscriptionStore.list(context).size());
        assertEquals("Keep my title", SubscriptionStore.list(context).get(0).title);
    }

    @Test public void onlySelectedFeedsAreSavedAndReimportIsIdempotent() {
        instrumentation.runOnMainSync(() -> {
            dialog.getListView().performItemClick(dialog.getListView().getChildAt(1), 1, 1);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });
        instrumentation.waitForIdleSync();
        assertEquals(2, SubscriptionStore.list(context).size());
        assertTrue(SubscriptionStore.contains(context, "https://example.org/first?a=1&b=2"));
        assertFalse(SubscriptionStore.contains(context, "https://example.org/second"));
        assertEquals("Keep my title", SubscriptionStore.list(context).get(0).title);
        assertEquals("祈り & Music", SubscriptionStore.list(context).get(1).title);
        instrumentation.runOnMainSync(() -> {
            dialog = SubscriptionImportDialog.show(activity, parsed, () -> {});
            assertEquals(1, dialog.getListView().getCount());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        });
        instrumentation.waitForIdleSync();
        assertEquals(2, SubscriptionStore.list(context).size());
    }
}
