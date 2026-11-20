package au.com.parristech.ruizusync.io;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import au.com.parristech.ruizusync.core.MediaTypes;
import au.com.parristech.ruizusync.model.SourceItem;
import au.com.parristech.ruizusync.net.RemoteAudio;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads music through the Storage Access Framework: individual documents picked with
 * ACTION_OPEN_DOCUMENT, whole folder trees picked with ACTION_OPEN_DOCUMENT_TREE (this is how USB
 * players and Google Drive both show up), and audio tag metadata for folder organisation.
 */
public final class SourceRepo {

    private static final int MAX_TREE_FILES = 5000;
    private static final int MAX_TREE_DEPTH = 10;

    public static final class Meta {
        public String name = "";
        public long size = -1;
        public String mimeType = "";
        public boolean drm;
    }

    public static final class TrackTags {
        public String artist;
        public String album;
    }

    public static final class Listing {
        public final String treeName;
        public final List<SourceItem> items;
        public final int ignoredNonAudio;
        public final int unreadableFolders;
        public final boolean truncated;

        Listing(String treeName, List<SourceItem> items, int ignoredNonAudio,
                int unreadableFolders, boolean truncated) {
            this.treeName = treeName;
            this.items = items;
            this.ignoredNonAudio = ignoredNonAudio;
            this.unreadableFolders = unreadableFolders;
            this.truncated = truncated;
        }
    }

    private static final class DirTask {
        final String docId;
        final List<String> relDirs;
        final int depth;

        DirTask(String docId, List<String> relDirs, int depth) {
            this.docId = docId;
            this.relDirs = relDirs;
            this.depth = depth;
        }
    }

    private final Context context;
    private final ContentResolver resolver;

    public SourceRepo(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
    }

    /** Name, size, MIME type and (best effort) DRM flag for one document. */
    public Meta queryMeta(Uri uri) {
        if (RemoteAudio.isRemote(uri)) {
            RemoteAudio.Probe probe = RemoteAudio.probe(uri);
            Meta remote = new Meta();
            remote.name = probe.name;
            remote.size = probe.size;
            remote.mimeType = probe.mimeType;
            return remote;
        }
        Meta meta = new Meta();
        String[] withDrm = {
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.IS_DRM
        };
        try (Cursor cursor = resolver.query(uri, withDrm, null, null, null)) {
            fillFrom(cursor, meta);
        } catch (IllegalArgumentException | SecurityException unsupportedColumn) {
            try (Cursor cursor = resolver.query(uri, new String[]{
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                    MediaStore.MediaColumns.MIME_TYPE}, null, null, null)) {
                fillFrom(cursor, meta);
            } catch (Throwable ignored) {
                // Fall through to the name guess below.
            }
        } catch (Throwable ignored) {
            // Provider unavailable; keep what we can.
        }
        if (meta.name.isEmpty()) {
            meta.name = guessName(uri);
        }
        return meta;
    }

    /**
     * DRM check for locally mounted storage only. Cloud providers would need one network query per
     * file to answer this, so they are skipped; DRM-protected content never reaches the player
     * either way because this app never decrypts anything.
     */
    public boolean isDrmProtected(Uri uri) {
        if (RemoteAudio.isRemote(uri)) {
            return false;
        }
        String authority = uri == null ? null : uri.getAuthority();
        if (authority == null) {
            return false;
        }
        if (!authority.equals("com.android.externalstorage.documents") && !authority.equals(MediaStore.AUTHORITY)) {
            return false;
        }
        try (Cursor cursor = resolver.query(uri, new String[]{MediaStore.MediaColumns.IS_DRM}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(MediaStore.MediaColumns.IS_DRM);
                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getInt(index) == 1;
                }
            }
        } catch (Throwable ignored) {
            // Column unsupported by this provider.
        }
        return false;
    }

    public InputStream open(Uri uri) throws IOException {
        if (RemoteAudio.isRemote(uri)) {
            return RemoteAudio.open(uri);
        }
        InputStream in = resolver.openInputStream(uri);
        if (in == null) {
            throw new FileNotFoundException("Cannot open " + uri);
        }
        return in;
    }

    /** Artist / album tags, used by the "Artist / Album" folder layout. Never throws. */
    public TrackTags readTags(Uri uri) {
        TrackTags tags = new TrackTags();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            tags.artist = firstNonBlank(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST));
            tags.album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
        } catch (Throwable ignored) {
            // Missing or unreadable tags are fine: the planner falls back to "Unknown Artist".
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
                // Nothing to do.
            }
        }
        return tags;
    }

    public String treeDisplayName(Uri treeUri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
            Meta meta = queryMeta(docUri);
            String name = normalizeName(meta.name);
            if (!name.isEmpty() && !looksLikeDocumentId(name)) {
                return name;
            }
        } catch (Throwable ignored) {
            // Fall back to the URI itself.
        }
        return "Selected folder";
    }

    /** Recursively lists supported audio files under a picked folder tree. */
    public Listing scanTree(Uri treeUri) throws IOException {
        final String rootDocId;
        try {
            rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Throwable t) {
            throw new IOException("That location is not a folder", t);
        }
        String treeName = treeDisplayName(treeUri);
        List<SourceItem> items = new ArrayList<>();
        int ignored = 0;
        int unreadable = 0;
        boolean truncated = false;

        ArrayDeque<DirTask> queue = new ArrayDeque<>();
        queue.add(new DirTask(rootDocId, Collections.<String>emptyList(), 0));

        while (!queue.isEmpty()) {
            if (items.size() >= MAX_TREE_FILES) {
                truncated = true;
                break;
            }
            DirTask task = queue.poll();
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, task.docId);
            try (Cursor cursor = resolver.query(childrenUri, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
                if (cursor == null) {
                    unreadable++;
                    continue;
                }
                while (cursor.moveToNext()) {
                    String docId = cursor.getString(0);
                    String name = cursor.getString(1);
                    String mime = cursor.getString(2);
                    if (docId == null || name == null) {
                        continue;
                    }
                    boolean isDirectory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    if (isDirectory) {
                        if (task.depth < MAX_TREE_DEPTH) {
                            List<String> childPath = new ArrayList<>(task.relDirs);
                            childPath.add(name);
                            queue.add(new DirTask(docId, childPath, task.depth + 1));
                        }
                        continue;
                    }
                    if (!MediaTypes.isSupported(name)) {
                        ignored++;
                        continue;
                    }
                    if (items.size() >= MAX_TREE_FILES) {
                        truncated = true;
                        break;
                    }
                    long size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    items.add(new SourceItem(docUri, name, size, mime == null ? "" : mime,
                            treeName, task.relDirs, null));
                }
            } catch (Throwable t) {
                if (task.depth == 0 && items.isEmpty()) {
                    throw new IOException("Cannot read the selected folder: " + t.getMessage(), t);
                }
                unreadable++;
            }
        }
        return new Listing(treeName, items, ignored, unreadable, truncated);
    }

    private void fillFrom(Cursor cursor, Meta meta) {
        if (cursor == null || !cursor.moveToFirst()) {
            return;
        }
        int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
            String name = cursor.getString(nameIndex);
            if (name != null && !name.trim().isEmpty()) {
                meta.name = name;
            }
        }
        int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
            meta.size = cursor.getLong(sizeIndex);
        }
        int mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
        if (mimeIndex >= 0 && !cursor.isNull(mimeIndex)) {
            String mime = cursor.getString(mimeIndex);
            if (mime != null) {
                meta.mimeType = mime;
            }
        }
        int drmIndex = cursor.getColumnIndex(MediaStore.MediaColumns.IS_DRM);
        if (drmIndex >= 0 && !cursor.isNull(drmIndex)) {
            meta.drm = cursor.getInt(drmIndex) == 1;
        }
    }

    private static String guessName(Uri uri) {
        if (uri == null) {
            return "Untitled";
        }
        String segment = uri.getLastPathSegment();
        if (segment == null) {
            return "Untitled";
        }
        String name = normalizeName(segment);
        return name.isEmpty() ? "Untitled" : name;
    }

    private static String normalizeName(String raw) {
        if (raw == null) {
            return "";
        }
        String name = raw.trim();
        int colon = name.lastIndexOf(':');
        if (colon >= 0 && colon < name.length() - 1) {
            name = name.substring(colon + 1);
        }
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        return name;
    }

    /** External storage root documents are sometimes labelled with their raw id. */
    private static boolean looksLikeDocumentId(String name) {
        if (name.isEmpty()) {
            return true;
        }
        if (name.matches("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) {
            return true;
        }
        return name.equals("primary");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** Describes one individually picked document, including its parent folder name. */
    public SourceItem describe(Uri uri) {
        return describe(uri, "");
    }

    /**
     * @param categoryHint folder name used by the "By source folder" / "Music & Podcasts"
     *                     layouts, e.g. "Podcasts" for imported feed episodes
     */
    public SourceItem describe(Uri uri, String categoryHint) {
        if (RemoteAudio.isRemote(uri)) {
            RemoteAudio.Probe probe = RemoteAudio.probe(uri);
            return new SourceItem(uri, probe.name, probe.size, probe.mimeType,
                    categoryHint == null ? "" : categoryHint, Collections.<String>emptyList(),
                    isPlainHttp(uri) ? "HTTPS link required" : null);
        }
        Meta meta = queryMeta(uri);
        return new SourceItem(uri, meta.name, meta.size, meta.mimeType,
                parentFolderFromDocId(uri), Collections.<String>emptyList(), null);
    }

    /**
     * Builds a source item for a remote episode without probing the server again; the feed
     * already supplied the title, size and content type.
     */
    public SourceItem describeRemote(Uri uri, String categoryHint, String nameHint,
                                     long sizeHint, String mimeHint) {
        String mime = mimeHint == null ? "" : mimeHint;
        String preferred = nameHint == null ? "" : nameHint.trim();
        String name = MediaTypes.normalizeAudioName(preferred, mime);
        if (!MediaTypes.isSupported(name)) {
            String fromUrl = RemoteAudio.nameFromUrl(uri);
            if (MediaTypes.isSupported(fromUrl)) {
                name = fromUrl;
            }
        }
        return new SourceItem(uri, name, sizeHint, mime,
                categoryHint == null ? "" : categoryHint, Collections.<String>emptyList(),
                isPlainHttp(uri) ? "HTTPS link required" : null);
    }

    private static boolean isPlainHttp(Uri uri) {
        return uri != null && "http".equalsIgnoreCase(uri.getScheme());
    }

    /** "By source folder" layout: only external storage document ids expose the real path. */
    private static String parentFolderFromDocId(Uri uri) {
        try {
            String authority = uri == null ? null : uri.getAuthority();
            if (!"com.android.externalstorage.documents".equals(authority)) {
                return "";
            }
            String docId = DocumentsContract.getDocumentId(uri);
            int colon = docId.indexOf(':');
            String path = colon >= 0 ? docId.substring(colon + 1) : docId;
            int slash = path.lastIndexOf('/');
            if (slash <= 0) {
                return "";
            }
            String parent = path.substring(0, slash);
            int parentSlash = parent.lastIndexOf('/');
            return parentSlash >= 0 ? parent.substring(parentSlash + 1) : parent;
        } catch (Throwable ignored) {
            return "";
        }
    }
}
