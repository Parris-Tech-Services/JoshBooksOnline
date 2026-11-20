package au.com.parristech.ruizusync.io;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;

import au.com.parristech.ruizusync.core.HashUtil;
import au.com.parristech.ruizusync.core.NameCollision;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes into the folder the user picked as the player destination through the Storage Access
 * Framework, so the same code path works for USB storage and for cloud folders.
 */
public final class DestRepo {

    private static final int MAX_SCAN = 60000;

    /** One document (file or folder) on the destination side. */
    public static final class Node {
        public final String docId;
        public final String name;
        public final boolean directory;
        public final long size;
        public final String relPath;

        Node(String docId, String name, boolean directory, long size, String relPath) {
            this.docId = docId;
            this.name = name;
            this.directory = directory;
            this.size = size;
            this.relPath = relPath;
        }
    }

    private final Context context;
    private final ContentResolver resolver;
    private final Uri treeUri;
    private final String rootDocId;
    private final String rootName;
    /** parent relPath (lower case) -> (child name lower case -> node). */
    private final Map<String, Map<String, Node>> childCache = new LinkedHashMap<>();

    public DestRepo(Context context, Uri treeUri) throws IOException {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        if (treeUri == null) {
            throw new IOException("No player folder selected");
        }
        if (!DocumentsContract.isTreeUri(treeUri)) {
            throw new IOException("Selected location is not a folder");
        }
        this.treeUri = treeUri;
        final String docId;
        try {
            docId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Throwable t) {
            throw new IOException("Selected folder cannot be opened", t);
        }
        this.rootDocId = docId;
        this.rootName = readDisplayName(buildDocUri(rootDocId), fallbackName(treeUri));
        // Fail fast when the persisted permission does not actually allow listing.
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId);
        try (Cursor cursor = resolver.query(childrenUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            if (cursor == null) {
                throw new IOException("Cannot read the player folder");
            }
        } catch (SecurityException e) {
            throw new IOException("No permission to use the player folder", e);
        }
    }

    public Node root() {
        return new Node(rootDocId, rootName, true, -1, "");
    }

    public String rootName() {
        return rootName;
    }

    /** Every file below the player root, up to {@code maxEntries}. */
    public List<Node> walkFiles(int maxEntries) throws IOException {
        List<Node> files = new ArrayList<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(root());
        int visited = 0;
        while (!queue.isEmpty()) {
            if (++visited > MAX_SCAN || files.size() > maxEntries) {
                break;
            }
            Node dir = queue.poll();
            for (Node child : childrenFor(dir).values()) {
                if (child.directory) {
                    queue.add(child);
                } else {
                    files.add(child);
                }
            }
        }
        return files;
    }

    /** Creates the folder path if needed and returns the deepest folder. */
    public Node ensureDir(List<String> segments) throws IOException {
        Node dir = root();
        if (segments == null || segments.isEmpty()) {
            return dir;
        }
        for (String segment : segments) {
            String key = segment.toLowerCase(Locale.ROOT);
            Node existing = childrenFor(dir).get(key);
            if (existing != null && existing.directory) {
                dir = existing;
                continue;
            }
            String targetName = existing == null ? segment : uniqueName(dir, segment);
            dir = createDirectory(dir, targetName);
        }
        return dir;
    }

    public Node createFile(Node dir, String name, String mimeType) throws IOException {
        Uri newUri;
        try {
            newUri = DocumentsContract.createDocument(resolver, buildDocUri(dir.docId), mimeType, name);
        } catch (FileNotFoundException e) {
            throw new IOException("Cannot create " + name + " in " + dir.name + ": " + e.getMessage(), e);
        }
        if (newUri == null) {
            throw new IOException("Cannot create " + name + " in " + dir.name);
        }
        String docId = DocumentsContract.getDocumentId(newUri);
        String actualName = readDisplayName(newUri, name);
        String relPath = dir.relPath.isEmpty() ? actualName : dir.relPath + "/" + actualName;
        Node node = new Node(docId, actualName, false, -1, relPath);
        childrenFor(dir).put(actualName.toLowerCase(Locale.ROOT), node);
        return node;
    }

    public OutputStream openWrite(Node file) throws IOException {
        OutputStream out = resolver.openOutputStream(buildDocUri(file.docId), "w");
        if (out == null) {
            throw new IOException("Cannot open " + file.name + " for writing");
        }
        return out;
    }

    public void delete(Node node) throws IOException {
        boolean deleted;
        try {
            deleted = DocumentsContract.deleteDocument(resolver, buildDocUri(node.docId));
        } catch (FileNotFoundException e) {
            deleted = false;
        }
        if (!deleted) {
            throw new IOException("Cannot delete " + node.relPath);
        }
        String parentPath = node.relPath.contains("/")
                ? node.relPath.substring(0, node.relPath.lastIndexOf('/'))
                : "";
        Map<String, Node> siblings = childCache.get(parentPath.toLowerCase(Locale.ROOT));
        if (siblings != null) {
            siblings.remove(node.name.toLowerCase(Locale.ROOT));
        }
        String prefix = node.relPath.toLowerCase(Locale.ROOT);
        childCache.keySet().removeIf(key -> key.equals(prefix) || key.startsWith(prefix + "/"));
    }

    /** SHA-256 of an existing player file, or null when it cannot be read. */
    public String hash(Node node) {
        try (InputStream in = resolver.openInputStream(buildDocUri(node.docId))) {
            return in == null ? null : HashUtil.sha256Hex(in);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Finds a direct child by name (case-insensitive), or null when absent. */
    public Node findChild(Node dir, String name) throws IOException {
        return childrenFor(dir).get(name.toLowerCase(Locale.ROOT));
    }

    public Set<String> existingNames(Node dir) throws IOException {
        return new HashSet<>(childrenFor(dir).keySet());
    }

    public String uniqueName(Node dir, String desiredName) throws IOException {
        return NameCollision.resolve(desiredName, existingNames(dir));
    }

    /** Free space on the player, or -1 when the provider cannot tell us. */
    public long availableBytes() {
        Long fromProvider = queryProviderAvailableBytes();
        if (fromProvider != null && fromProvider > 0) {
            return fromProvider;
        }
        return volumeAvailableBytes();
    }

    private Node createDirectory(Node parent, String name) throws IOException {
        Uri newUri;
        try {
            newUri = DocumentsContract.createDocument(resolver, buildDocUri(parent.docId),
                    DocumentsContract.Document.MIME_TYPE_DIR, name);
        } catch (FileNotFoundException e) {
            throw new IOException("Cannot create folder " + name + ": " + e.getMessage(), e);
        }
        if (newUri == null) {
            throw new IOException("Cannot create folder " + name);
        }
        String docId = DocumentsContract.getDocumentId(newUri);
        String actualName = readDisplayName(newUri, name);
        String relPath = parent.relPath.isEmpty() ? actualName : parent.relPath + "/" + actualName;
        Node node = new Node(docId, actualName, true, -1, relPath);
        childrenFor(parent).put(actualName.toLowerCase(Locale.ROOT), node);
        return node;
    }

    private Map<String, Node> childrenFor(Node dir) throws IOException {
        String key = dir.relPath.toLowerCase(Locale.ROOT);
        Map<String, Node> cached = childCache.get(key);
        if (cached == null) {
            cached = queryChildren(dir);
            childCache.put(key, cached);
        }
        return cached;
    }

    private Map<String, Node> queryChildren(Node dir) throws IOException {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dir.docId);
        Map<String, Node> map = new LinkedHashMap<>();
        try (Cursor cursor = resolver.query(childrenUri, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (cursor == null) {
                throw new IOException("Cannot list " + (dir.relPath.isEmpty() ? "the player folder" : dir.relPath));
            }
            while (cursor.moveToNext()) {
                String docId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (docId == null || name == null) {
                    continue;
                }
                boolean isDirectory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                long size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                String relPath = dir.relPath.isEmpty() ? name : dir.relPath + "/" + name;
                Node node = new Node(docId, name, isDirectory, size, relPath);
                map.put(name.toLowerCase(Locale.ROOT), node);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Cannot list "
                    + (dir.relPath.isEmpty() ? "the player folder" : dir.relPath) + ": " + t.getMessage(), t);
        }
        return map;
    }

    private Long queryProviderAvailableBytes() {
        try (Cursor cursor = resolver.query(buildDocUri(rootDocId),
                new String[]{"availableBytes", DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex("availableBytes");
                if (index >= 0 && !cursor.isNull(index)) {
                    long value = cursor.getLong(index);
                    if (value > 0) {
                        return value;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Most providers do not expose this column.
        }
        return null;
    }

    private long volumeAvailableBytes() {
        try {
            StorageManager manager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            if (manager == null) {
                return -1;
            }
            for (StorageVolume volume : manager.getStorageVolumes()) {
                String uuid = volume.getUuid();
                String prefix = uuid == null || uuid.isEmpty() ? "primary" : uuid;
                if (!rootDocId.startsWith(prefix + ":")) {
                    continue;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    File dir = volume.getDirectory();
                    if (dir != null) {
                        return new StatFs(dir.getAbsolutePath()).getAvailableBytes();
                    }
                }
            }
        } catch (Throwable ignored) {
            // Best effort only.
        }
        return -1;
    }

    private Uri buildDocUri(String docId) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
    }

    private String readDisplayName(Uri uri, String fallback) {
        try (Cursor cursor = resolver.query(uri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                if (index >= 0 && !cursor.isNull(index)) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) {
                        String normalized = normalizeName(name);
                        if (!normalized.isEmpty()) {
                            return normalized;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall back.
        }
        return fallback;
    }

    private static String normalizeName(String raw) {
        String name = raw.trim();
        int colon = name.lastIndexOf(':');
        if (colon >= 0 && colon < name.length() - 1) {
            name = name.substring(colon + 1);
        }
        return name.trim();
    }

    private static String fallbackName(Uri treeUri) {
        String segment = treeUri.getLastPathSegment();
        if (segment == null || segment.trim().isEmpty()) {
            return "Player storage";
        }
        String name = normalizeName(segment);
        return name.isEmpty() ? "Player storage" : name;
    }
}
