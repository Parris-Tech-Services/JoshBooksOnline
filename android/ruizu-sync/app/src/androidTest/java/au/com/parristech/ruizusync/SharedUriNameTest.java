package au.com.parristech.ruizusync;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import au.com.parristech.ruizusync.core.MediaTypes;
import au.com.parristech.ruizusync.io.SourceRepo;
import au.com.parristech.ruizusync.model.SourceItem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Device-side cover for the cross-app share path, exercised through a real {@link ContentResolver}.
 *
 * <p>Reported symptom: eight shared MP3s were listed as "unsupported format" and shown as numeric
 * ids. The name a share delivers normally comes from {@code OpenableColumns.DISPLAY_NAME}; when a
 * sender grants read access but the name cannot be read, the old code used the URI's row id as the
 * file name, found no audio extension, and refused the file.</p>
 *
 * <p>{@link FakeUsbProvider} can now answer with an empty display name, which is exactly that
 * shape, so both branches run against a real provider rather than a mock.</p>
 */
@RunWith(AndroidJUnit4.class)
public final class SharedUriNameTest {

    private static final String NAMED_ID = "abc-123";
    private static final String NAMED_FILE = "Blessed Be Your Name.mp3";
    private static final String UNNAMED_ID = "1000110460";

    private Context context;
    private ContentResolver resolver;
    private SourceRepo repo;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        resolver = context.getContentResolver();
        // Start the provider in this process before resetting its state.
        resolver.query(DocumentsContract.buildDocumentUri(FakeUsbProvider.AUTHORITY, "root"),
                null, null, null, null).close();
        control("reset", null);
        control("addName", NAMED_ID + "\t" + NAMED_FILE);
        repo = new SourceRepo(context);
    }

    private android.os.Bundle control(String method, String value) {
        return resolver.call(Uri.parse("content://" + FakeUsbProvider.AUTHORITY), method, value, null);
    }

    private Uri documentUri(String id) {
        return DocumentsContract.buildDocumentUri(FakeUsbProvider.AUTHORITY, id);
    }

    @Test
    public void providerSuppliedNameIsUsedVerbatim() {
        SourceRepo.Meta meta = repo.queryMeta(documentUri(NAMED_ID));
        assertEquals(NAMED_FILE, meta.name);
        assertFalse("a real name is not a reconstruction", meta.nameReconstructed);
        assertFalse("a real name needs no unresolved warning", meta.nameUnresolved);
    }

    @Test
    public void sharedAudioWithoutADisplayNameIsStillSupported() {
        control("blankNames", "true");

        SourceRepo.Meta meta = repo.queryMeta(documentUri(UNNAMED_ID));
        assertTrue("audio type known, so the file must stay transferable: " + meta.name,
                MediaTypes.isSupported(meta.name));
        assertNotEquals("must not fall back to the bare row id", UNNAMED_ID, meta.name);
        assertTrue("the rebuilt name is reported as such", meta.nameReconstructed);
        assertFalse("a rebuilt name is not an unresolved file", meta.nameUnresolved);
    }

    @Test
    public void sharedAudioItemIsOfferedForTransferNotSkipped() {
        control("blankNames", "true");

        SourceItem item = repo.describe(documentUri(UNNAMED_ID));
        assertTrue("the shared file must be selectable, not marked unsupported",
                item.supportedFormat());
        assertNull("a readable audio type is not a transfer problem", item.problem);
        assertTrue("the name is flagged as rebuilt so the UI can say so", item.nameReconstructed);
    }

    @Test
    public void differentRowsStayDistinguishable() {
        control("blankNames", "true");
        String first = repo.queryMeta(documentUri("1000110460")).name;
        String second = repo.queryMeta(documentUri("1000110461")).name;
        assertNotEquals("two unnamed shared files must not collapse to one name", first, second);
    }
}
