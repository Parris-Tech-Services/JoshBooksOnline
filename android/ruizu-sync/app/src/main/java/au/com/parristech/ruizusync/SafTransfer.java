package au.com.parristech.ruizusync;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Only deletes journalled temporary documents created by this app. Existing library files are read-only. */
public final class SafTransfer {
    private final ContentResolver resolver;
    private final SharedPreferences journal;
    private final BooleanSupplier cancelled;
    public SafTransfer(Context context, BooleanSupplier cancelled) {
        resolver = context.getContentResolver();
        journal = context.getSharedPreferences("ruizu-pending", Context.MODE_PRIVATE);
        this.cancelled = cancelled;
    }
    private Uri parent(Uri tree) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    }
    private Map<String, Uri> children(Uri tree) throws IOException {
        Map<String, Uri> found = new LinkedHashMap<>();
        Uri list = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor c = resolver.query(list, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c == null) throw new IOException("USB folder unavailable; reconnect and choose it again");
            while (c.moveToNext()) found.put(c.getString(1).toLowerCase(Locale.ROOT),
                    DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)));
        }
        return found;
    }
    private void recover(Uri tree) throws IOException {
        String old = journal.getString(tree.toString(), null);
        if (old == null) return;
        Uri pending = Uri.parse(old);
        // The journal contains only documents returned by createDocument for this tree.
        String ownedName = journal.getString(tree + ":name", "");
        Map<String, Uri> files = children(tree);
        // A provider may keep the same document URI after rename. Never delete a published file.
        if (!ownedName.isEmpty() && pending.equals(files.get(ownedName))
                && !DocumentsContract.deleteDocument(resolver, pending))
            throw new IOException("Could not remove previous incomplete transfer; reconnect USB and retry");
        journal.edit().remove(tree.toString()).remove(tree + ":name").commit();
    }
    public boolean transfer(InputStream source, Uri tree, String rawName, long size,
                            VerifiedCopy.Progress progress) throws Exception {
        String name = TransferUtils.safeName(rawName);
        if (!TransferUtils.isSupportedAudio(name)) throw new IOException("Unsupported audio filename");
        if (size > VerifiedCopy.MAX_BYTES) throw new IOException("File exceeds FAT32 4 GiB limit");
        VerifiedCopy.checkCancelled(cancelled);
        recover(tree);
        Map<String, Uri> existing = children(tree);
        String free = name;
        for (int n = 2; existing.containsKey(free.toLowerCase(Locale.ROOT)); n++) {
            if (n > 10000) throw new IOException("Too many filename conflicts");
            free = TransferUtils.withNumber(name, n);
        }
        String temporaryName = ".ruizu-" + UUID.randomUUID() + ".part";
        Uri temporary = DocumentsContract.createDocument(resolver, parent(tree), "application/octet-stream", temporaryName);
        if (temporary == null) throw new IOException("USB storage is read-only or full");
        if (!journal.edit().putString(tree.toString(), temporary.toString()).putString(tree + ":name", temporaryName).commit()) {
            DocumentsContract.deleteDocument(resolver, temporary);
            throw new IOException("Could not record recovery information");
        }
        boolean published = false;
        Exception failure = null;
        try {
            byte[] hash;
            try (OutputStream out = resolver.openOutputStream(temporary, "wt")) {
                if (out == null) throw new IOException("Cannot write USB; reconnect or free space");
                hash = VerifiedCopy.copy(source, out, size, cancelled, progress);
            } // Closing must succeed before success or verification.
            try (InputStream check = resolver.openInputStream(temporary)) {
                if (check == null) throw new IOException("Cannot read USB for verification");
                VerifiedCopy.verify(hash, check, cancelled);
            }
            for (Map.Entry<String, Uri> entry : existing.entrySet()) {
                if (!TransferUtils.isConflictName(name, entry.getKey())) continue;
                try (InputStream check = resolver.openInputStream(entry.getValue())) {
                    if (check != null && Arrays.equals(hash, VerifiedCopy.digest(check, cancelled))) return false;
                }
            }
            VerifiedCopy.checkCancelled(cancelled);
            if (DocumentsContract.renameDocument(resolver, temporary, free) == null)
                throw new IOException("USB provider cannot finalise this file; transfer not completed");
            published = true;
            journal.edit().remove(tree.toString()).remove(tree + ":name").commit();
            return true;
        } catch (Exception error) {
            failure = error;
            throw error;
        } finally {
            if (!published) {
                try {
                    if (!DocumentsContract.deleteDocument(resolver, temporary)) throw new IOException("Incomplete file cleanup failed");
                    journal.edit().remove(tree.toString()).remove(tree + ":name").commit();
                } catch (Exception cleanup) {
                    if (failure != null) failure.addSuppressed(cleanup);
                    else throw new IOException("Reconnect USB and retry to clean up incomplete output", cleanup);
                }
            }
        }
    }
}
