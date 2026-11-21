package au.com.parristech.ruizusync;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import au.com.parristech.ruizusync.model.SourceItem;
import au.com.parristech.ruizusync.ui.MainActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SourceSelectionTest {
    @Test public void unavailableAudioIsNotCountedAsReadyOrUnsupported() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.getUiAutomation()
                .grantRuntimePermission(context.getPackageName(), "android.permission.POST_NOTIFICATIONS");
        AppState state = AppState.get();
        state.clearSources();
        state.addSource(new SourceItem(Uri.parse("content://fixture/1"), "missing.mp3", 100,
                "audio/mpeg", "", null, "Permission lost; choose the file again"));
        state.setDestination(Uri.parse("content://fixture/tree/player"), "Test player");
        Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(() -> {
                View root = activity.getWindow().getDecorView();
                assertTrue(contains(root, "0 of 1 files ready to copy"));
                assertTrue(contains(root, "Permission lost; choose the file again"));
                assertFalse(contains(root, "in an unsupported format"));
                assertFalse(findStart(root).isEnabled());
            });
        } finally {
            instrumentation.runOnMainSync(activity::finishAndRemoveTask);
            instrumentation.waitForIdleSync();
            state.clearSources();
            state.clearDestination();
        }
    }
    private static boolean contains(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (contains(group.getChildAt(i), text)) return true;
        }
        return false;
    }
    private static Button findStart(View view) {
        if (view instanceof Button && "Start transfer".contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findStart(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
