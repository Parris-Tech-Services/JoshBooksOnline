package au.com.parristech.ruizusync.share;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Private, read-only test fixture. Numeric URIs deliberately contain no file extension. */
public final class SharedAudioProvider extends ContentProvider {
    public static final byte[] PAYLOAD = {0x49, 0x44, 0x33, 0x04, 0, 0};
    private File audio;

    @Override public boolean onCreate() {
        audio = new File(getContext().getCacheDir(), "cross-app-audio-fixture");
        try (FileOutputStream out = new FileOutputStream(audio)) {
            out.write(PAYLOAD);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot create shared audio test fixture", failure);
        }
        return true;
    }

    private String displayName(Uri uri) {
        switch (uri.getLastPathSegment()) {
            case "101": return "shared-music.mp3";
            case "102": return "shared-podcast.mp3";
            case "103": return "unshared-control.mp3";
            default: throw new IllegalArgumentException("Unknown fixture URI");
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        String name = displayName(uri);
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case OpenableColumns.DISPLAY_NAME: values[i] = name; break;
                case OpenableColumns.SIZE: values[i] = (long) PAYLOAD.length; break;
                case MediaStore.MediaColumns.MIME_TYPE: values[i] = "audio/mpeg"; break;
                // Exercise SourceRepo's retry without the optional IS_DRM column.
                default: throw new IllegalArgumentException("Unsupported fixture column");
            }
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(values);
        return cursor;
    }

    @Override public String getType(Uri uri) {
        displayName(uri);
        return "audio/mpeg";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        displayName(uri);
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only fixture");
        return ParcelFileDescriptor.open(audio, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only fixture");
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("Read-only fixture");
    }
    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("Read-only fixture");
    }
}
