package au.com.parristech.ruizusync;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import au.com.parristech.ruizusync.core.MediaTypes;
import au.com.parristech.ruizusync.io.SourceRepo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression cover for the "MP3 appears as the numeric id and is called unsupported" report.
 *
 * <p>A shared audio file reaches the app as a {@code content://media/...} URI whose last path
 * segment is a row id, not a name. {@link SourceRepo#queryMeta(Uri)} is the code that has to
 * resolve the real display name; if it fails, {@code guessName} falls back to that numeric id,
 * the extension is unknown, and the file is reported as an unsupported format.
 *
 * <p>This exercises the production query against a real MediaStore row rather than guessing
 * from adb intent experiments.
 */
@RunWith(AndroidJUnit4.class)
public final class MediaStoreSourceTest {

    private static final String FILE_NAME = "ruizu-probe-display-name.mp3";

    private Context context;
    private ContentResolver resolver;
    private Uri inserted;

    @Before
    public void publishAudioRow() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, FILE_NAME);
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        try {
            inserted = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        } catch (SecurityException denied) {
            // The emulator image may refuse media inserts; that is an environment limit, not a
            // statement about the app, so skip rather than reporting a false regression.
            Assume.assumeNoException("MediaStore insert not permitted here", denied);
        }
        Assume.assumeTrue("MediaStore returned no row", inserted != null);
        try (java.io.OutputStream out = resolver.openOutputStream(inserted)) {
            assertTrue("provider gave no output stream", out != null);
            out.write(new byte[]{0x49, 0x44, 0x33, 0x04, 0x00, 0x00}); // "ID3" header
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Audio.Media.IS_PENDING, 0);
        resolver.update(inserted, done, null, null);
    }

    @After
    public void removeRow() {
        if (inserted != null) {
            try {
                resolver.delete(inserted, null, null);
            } catch (RuntimeException ignored) {
                // Cleanup only.
            }
        }
    }

    @Test
    public void queryMetaResolvesRealDisplayNameFromMediaStoreUri() {
        SourceRepo repo = new SourceRepo(context);
        SourceRepo.Meta meta = repo.queryMeta(inserted);

        assertEquals("display name came from the provider, not the numeric row id",
                FILE_NAME, meta.name);
        assertNotEquals("must not fall back to the numeric row id",
                inserted.getLastPathSegment(), meta.name);
        assertTrue("size should be known for a published MediaStore row", meta.size > 0);
    }

    @Test
    public void sharedMediaStoreAudioIsRecognisedAsSupported() {
        SourceRepo repo = new SourceRepo(context);
        SourceRepo.Meta meta = repo.queryMeta(inserted);

        // This is the user-visible symptom: a numeric id has no audio extension, so the file was
        // listed as "unsupported format" and skipped instead of transferred.
        assertTrue("resolved name must be a supported audio format: " + meta.name,
                MediaTypes.isSupported(meta.name));
        assertEquals("audio/mpeg", MediaTypes.mimeTypeFor(meta.name));
    }
}
