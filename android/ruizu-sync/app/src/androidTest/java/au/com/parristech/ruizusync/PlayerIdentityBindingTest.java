package au.com.parristech.ruizusync;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import au.com.parristech.ruizusync.core.PlayerBinding;
import au.com.parristech.ruizusync.core.PlayerIdentity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Proves the wrong-player guard end to end through the real SAF plumbing: the marker file is
 * created and read through {@link PlayerMarkerStore}, and {@link PlayerFolderStore} decides whether
 * the remembered folder belongs to the player that is attached.
 *
 * <p>Both RUIZU units report volume {@code 0000-006F}, so the fake provider is addressed with two
 * document ids on that one volume id and the attached player is changed by rewriting its marker —
 * exactly the state a second unit produces in the field.
 */
@RunWith(AndroidJUnit4.class)
public final class PlayerIdentityBindingTest {

    private static final String VOLUME = "0000-006F";
    private static final String PREFS = "ruizu_player_folders";

    private Context context;
    private ContentResolver resolver;
    private SharedPreferences prefs;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        resolver = context.getContentResolver();
        callProvider("reset", null);
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
    }

    @After public void tearDown() {
        // The store is left clear rather than restored: no other suite asserts on saved player
        // entries, and "no saved player" is the state a fresh install has.
        prefs.edit().clear().commit();
        callProvider("reset", null);
    }

    private Uri tree(String suffix) {
        return DocumentsContract.buildTreeDocumentUri(FakeUsbProvider.AUTHORITY, VOLUME + ":" + suffix);
    }

    /**
     * A tree root the fake provider knows as a directory. Both players report the volume id
     * {@code 0000-006F}, so the suffix is what a real unit's chosen folder would be.
     */
    private Uri newTree(String suffix) {
        callProvider("mkTree", VOLUME + ":" + suffix);
        return tree(suffix);
    }

    private Bundle callProvider(String method, String argument) {
        return resolver.call(Uri.parse("content://" + FakeUsbProvider.AUTHORITY), method, argument, null);
    }

    /** Finds the marker document id of the attached player, or null when it has none. */
    private String markerDocId(Uri tree) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cursor = resolver.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null) {
                return null;
            }
            while (cursor.moveToNext()) {
                if (PlayerIdentity.MARKER_FILE_NAME.equals(cursor.getString(1))) {
                    return cursor.getString(0);
                }
            }
        }
        return null;
    }

    /**
     * Puts another unit's marker at the same path, as plugging the other player in would: the tree
     * keeps exactly one marker file, now carrying a different identity.
     */
    private void attachPlayerWithIdentity(Uri tree, PlayerIdentity identity) throws Exception {
        replaceMarker(tree, identity.toJson());
    }

    /**
     * Replaces the marker file's contents through the provider. Delete-then-create rather than an
     * in-place write, because a DocumentsProvider only truncates when the mode asks for it and this
     * test must not depend on that detail.
     */
    private void replaceMarker(Uri tree, String content) throws Exception {
        String docId = markerDocId(tree);
        assertNotNull("the test needs an existing marker to replace", docId);
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        assertTrue(DocumentsContract.deleteDocument(resolver, doc));

        Uri treeRoot = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));
        Uri created = DocumentsContract.createDocument(resolver, treeRoot, "application/json",
                PlayerIdentity.MARKER_FILE_NAME);
        assertNotNull("the marker could not be recreated", created);
        try (OutputStream out = resolver.openOutputStream(created, "w")) {
            assertNotNull(out);
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        assertNotNull(markerDocId(tree));
    }

    private String readMarker(Uri tree) throws Exception {
        String docId = markerDocId(tree);
        if (docId == null) {
            return null;
        }
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        try (java.io.InputStream in = resolver.openInputStream(doc)) {
            if (in == null) {
                return null;
            }
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            int read;
            while ((read = in.read()) != -1) {
                buffer.write(read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test public void markerIsCreatedOnceAndKeptStable() {
        Uri player = newTree("Josh");
        PlayerMarkerStore.Reading first = PlayerMarkerStore.ensure(context, player, "Josh's RUIZU");
        assertEquals(PlayerMarkerStore.State.IDENTIFIED, first.state);
        assertNotNull(first.deviceId());
        assertEquals("Josh's RUIZU", first.deviceName());

        // A second ensure must adopt the existing marker, not mint a new identity.
        PlayerMarkerStore.Reading second = PlayerMarkerStore.ensure(context, player, "Some other name");
        assertEquals(first.deviceId(), second.deviceId());
        assertEquals("Josh's RUIZU", second.deviceName());
    }

    @Test public void identifiedPlayerWithItsOwnMarkerRestoresNormally() {
        Uri player = newTree("Josh");
        PlayerMarkerStore.Reading created = PlayerMarkerStore.ensure(context, player, "Josh's RUIZU");
        assertNotNull(created.deviceId());
        PlayerFolderStore.remember(context, player, "Josh's RUIZU", created.deviceId());

        PlayerFolderStore.Restore restore = PlayerFolderStore.evaluateRestore(context, VOLUME);
        assertNotNull(restore);
        assertEquals(PlayerBinding.Restore.MATCH, restore.decision);
        assertTrue(PlayerBinding.allowsAutomaticReuse(restore.decision));
        assertEquals(created.deviceId(), restore.connectedDeviceId);
        assertEquals(player.toString(), restore.treeUri.toString());
    }

    @Test public void differentMarkerOnTheSameVolumeIsRefused() throws Exception {
        Uri player = newTree("Shared");
        PlayerMarkerStore.Reading josh = PlayerMarkerStore.ensure(context, player, "Josh's RUIZU");
        assertNotNull(josh.deviceId());
        PlayerFolderStore.remember(context, player, "Josh's RUIZU", josh.deviceId());

        // Now Sylvie's player is attached to the same volume id: same path, different marker.
        PlayerIdentity sylvie = PlayerIdentity.of("33333333-3333-3333-3333-333333333333",
                "Sylvie's RUIZU", 5L);
        assertNotNull(sylvie);
        attachPlayerWithIdentity(player, sylvie);

        PlayerFolderStore.Restore restore = PlayerFolderStore.evaluateRestore(context, VOLUME);
        assertNotNull(restore);
        assertEquals(PlayerBinding.Restore.REFUSE_DIFFERENT_DEVICE, restore.decision);
        assertFalse(PlayerBinding.allowsAutomaticReuse(restore.decision));
        assertEquals("Sylvie's RUIZU", restore.connectedDeviceName);
        assertEquals(josh.deviceId(), restore.storedDeviceId);
        assertEquals("Josh's RUIZU", restore.storedLabel);
    }

    @Test public void aPlayerWithItsOwnEntryIsStillRecognisedAfterTheVolumeEntryMoved() throws Exception {
        Uri joshTree = newTree("Josh");
        PlayerMarkerStore.Reading josh = PlayerMarkerStore.ensure(context, joshTree, "Josh's RUIZU");
        assertNotNull(josh.deviceId());
        PlayerFolderStore.remember(context, joshTree, "Josh's RUIZU", josh.deviceId());

        Uri sylvieTree = newTree("Sylvie");
        PlayerMarkerStore.Reading sylvie = PlayerMarkerStore.ensure(context, sylvieTree, "Sylvie's RUIZU");
        assertNotNull(sylvie.deviceId());
        PlayerFolderStore.remember(context, sylvieTree, "Sylvie's RUIZU", sylvie.deviceId());

        // The volume entry now names Sylvie's folder, and Sylvie's unit is attached.
        PlayerFolderStore.Restore sylvieRestore = PlayerFolderStore.evaluateRestore(context, VOLUME);
        assertNotNull(sylvieRestore);
        assertEquals(PlayerBinding.Restore.MATCH, sylvieRestore.decision);
        assertEquals(sylvie.deviceId(), sylvieRestore.connectedDeviceId);
        assertEquals(sylvieTree.toString(), sylvieRestore.treeUri.toString());

        // Now Josh's unit is attached at the same volume id. Its own entry still exists, so it is
        // recognised and its own folder is used rather than the volume entry belonging to Sylvie.
        attachPlayerWithIdentity(sylvieTree, josh.identity);
        PlayerFolderStore.Restore joshRestore = PlayerFolderStore.evaluateRestore(context, VOLUME);
        assertNotNull(joshRestore);
        assertEquals(PlayerBinding.Restore.MATCH, joshRestore.decision);
        assertEquals(josh.deviceId(), joshRestore.connectedDeviceId);
        assertEquals("Josh's RUIZU", joshRestore.connectedDeviceName);
        assertEquals(joshTree.toString(), joshRestore.treeUri.toString());
    }

    @Test public void recordedIdentityWithNoMarkerIsRefused() throws Exception {
        Uri player = newTree("Josh");
        PlayerMarkerStore.Reading created = PlayerMarkerStore.ensure(context, player, "Josh's RUIZU");
        assertNotNull(created.deviceId());
        PlayerFolderStore.remember(context, player, "Josh's RUIZU", created.deviceId());

        Uri doc = DocumentsContract.buildDocumentUriUsingTree(player, markerDocId(player));
        assertTrue(DocumentsContract.deleteDocument(resolver, doc));

        PlayerFolderStore.Restore restore = PlayerFolderStore.evaluateRestore(context, VOLUME);
        assertNotNull(restore);
        assertEquals(PlayerBinding.Restore.REFUSE_UNVERIFIED, restore.decision);
        assertFalse(PlayerBinding.allowsAutomaticReuse(restore.decision));
    }

    @Test public void legacyInstallStillFindsItsRememberedFolder() {
        // An install from before device markers: only the volume-keyed entry exists.
        Uri player = newTree("Legacy");
        prefs.edit().putString("tree:" + VOLUME, player.toString())
                .putString("label:" + VOLUME, "USB storage").commit();
        assertNull(prefs.getString("bound:" + VOLUME, null));

        PlayerFolderStore.Restore restore = PlayerFolderStore.evaluateRestore(context, VOLUME);
        assertNotNull(restore);
        assertEquals(PlayerBinding.Restore.LEGACY, restore.decision);
        assertTrue(PlayerBinding.allowsAutomaticReuse(restore.decision));
        assertEquals(player.toString(), restore.treeUri.toString());
        assertNull(restore.storedDeviceId);
    }

    @Test public void malformedMarkerIsReportedAndNeverReplaced() throws Exception {
        Uri player = newTree("Broken");
        PlayerMarkerStore.Reading created = PlayerMarkerStore.ensure(context, player, "Josh's RUIZU");
        assertNotNull(created.deviceId());
        PlayerFolderStore.remember(context, player, "Josh's RUIZU", created.deviceId());

        String garbage = "this is not a marker";
        replaceMarker(player, garbage);

        PlayerMarkerStore.Reading reading = PlayerMarkerStore.read(context, player);
        assertEquals(PlayerMarkerStore.State.MALFORMED, reading.state);
        assertNull(reading.deviceId());

        // ensure() must not re-identify a player whose marker it cannot understand.
        PlayerMarkerStore.Reading afterEnsure = PlayerMarkerStore.ensure(context, player, "Josh's RUIZU");
        assertEquals(PlayerMarkerStore.State.MALFORMED, afterEnsure.state);
        assertEquals(garbage, readMarker(player));

        PlayerFolderStore.Restore restore = PlayerFolderStore.evaluateRestore(context, VOLUME);
        assertNotNull(restore);
        assertEquals(PlayerBinding.Restore.REFUSE_UNVERIFIED, restore.decision);
    }

    @Test public void unwritablePlayerIsReportedUnidentifiedAndLeavesNoMarker() {
        Uri player = newTree("Full");
        callProvider("full", "true");
        try {
            PlayerMarkerStore.Reading reading = PlayerMarkerStore.ensure(context, player, "Josh's RUIZU");
            assertEquals(PlayerMarkerStore.State.UNREADABLE, reading.state);
            assertNull(reading.deviceId());
            assertNull("a marker that could not be written must not be left behind",
                    markerDocId(player));
        } finally {
            callProvider("full", "false");
        }
    }
}
