package au.com.parristech.ruizusync;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import au.com.parristech.ruizusync.io.SourceRepo;
import au.com.parristech.ruizusync.model.SourceItem;
import au.com.parristech.ruizusync.share.ShareAudioActivity;
import au.com.parristech.ruizusync.share.SharedAudioProvider;
import au.com.parristech.ruizusync.ui.MainActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.InputStream;
import java.util.List;
import static org.junit.Assert.*;

/**
 * Actual owner-to-recipient sharing, not an adb shell intent or a recipient-owned row.
 * Fixture bytes only test transport; this does not claim decoder or USB compatibility.
 */
@RunWith(AndroidJUnit4.class)
public final class CrossAppAudioShareTest {
    @Test public void multipleGrantedAudioFilesKeepNamesAndReadableBytes() throws Exception {
        checkShare(false);
    }

    @Test public void singleGrantedAudioFileKeepsNameAndReadableBytes() throws Exception {
        checkShare(true);
    }

    private void checkShare(boolean single) throws Exception {
        int count = single ? 1 : 2;
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context target = instrumentation.getTargetContext();
        Context sender = instrumentation.getContext();
        assertNotEquals("sender and receiver must have different Android UIDs",
                sender.getApplicationInfo().uid, target.getApplicationInfo().uid);
        assertEquals("the test must not depend on broad media access",
                PackageManager.PERMISSION_DENIED,
                target.checkSelfPermission("android.permission.READ_MEDIA_AUDIO"));
        Uri unshared = Uri.parse("content://" + sender.getPackageName()
                + ".sharedaudio/audio/103");
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // Avoid leaving a notification dialog for the next activity test.
            instrumentation.getUiAutomation().grantRuntimePermission(
                    target.getPackageName(), "android.permission.POST_NOTIFICATIONS");
        }
        AppState.get().clearSources();
        Activity receiver = instrumentation.startActivitySync(
                new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            Intent launchSender = new Intent()
                    .setClassName(sender.getPackageName(), ShareAudioActivity.class.getName())
                    .putExtra("targetPackage", target.getPackageName())
                    .putExtra("single", single)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            target.startActivity(launchSender);
            long deadline = SystemClock.uptimeMillis() + 15000;
            while (AppState.get().sourceCount() < count && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(50);
            }
            List<SourceItem> selected = AppState.get().sources();
            assertEquals("all files must reach the production share receiver", count, selected.size());
            SourceRepo repo = new SourceRepo(target);
            for (int i = 0; i < selected.size(); i++) {
                SourceItem item = selected.get(i);
                assertEquals(i == 0 ? "shared-music.mp3" : "shared-podcast.mp3", item.displayName);
                assertTrue("shared MP3 must not be called unsupported", item.supportedFormat());
                assertNull("granted source must have no transfer problem", item.problem);
                assertEquals(SharedAudioProvider.PAYLOAD.length, item.size);
                try (InputStream in = repo.open(item.uri)) {
                    assertArrayEquals("grant must cover stream access as well as metadata",
                            SharedAudioProvider.PAYLOAD, in.readAllBytes());
                }
            }
            // A non-exported provider must not become broadly readable through the test setup.
            try (InputStream ignored = target.getContentResolver().openInputStream(unshared)) {
                fail("unshared sibling URI must remain inaccessible");
            } catch (SecurityException expected) {
                // Per-file grants are effective; no provider-wide permission was added.
            }
        } finally {
            instrumentation.runOnMainSync(receiver::finishAndRemoveTask);
            instrumentation.waitForIdleSync();
            AppState.get().clearSources();
        }
    }
}
