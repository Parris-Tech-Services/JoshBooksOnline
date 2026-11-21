package au.com.parristech.ruizusync.model;

import android.net.Uri;

import au.com.parristech.ruizusync.core.ByteFormat;
import au.com.parristech.ruizusync.core.MediaTypes;

import java.util.Collections;
import java.util.List;

/** One audio file the user selected, from phone storage, USB storage or a cloud provider. */
public final class SourceItem {

    public final Uri uri;
    public final String displayName;
    /** Byte size as reported by the provider, or -1 when unknown. */
    public final long size;
    public final String mimeType;
    /** Display name of the picked source folder, "" when files were picked individually. */
    public final String sourceFolder;
    /** Folder path relative to the picked source tree, for "keep folder tree". */
    public final List<String> treeRelDirs;
    /** Non-null when the file cannot be transferred (unsupported format etc.). */
    public final String problem;
    /**
     * True when the sending app supplied no file name and {@link #displayName} was rebuilt from
     * the audio type. The file itself is fine; only its name is a best effort.
     */
    public final boolean nameReconstructed;

    public SourceItem(Uri uri, String displayName, long size, String mimeType,
                      String sourceFolder, List<String> treeRelDirs, String problem) {
        this(uri, displayName, size, mimeType, sourceFolder, treeRelDirs, problem, false);
    }

    public SourceItem(Uri uri, String displayName, long size, String mimeType,
                      String sourceFolder, List<String> treeRelDirs, String problem,
                      boolean nameReconstructed) {
        this.uri = uri;
        this.displayName = displayName == null || displayName.trim().isEmpty() ? "Untitled" : displayName;
        this.size = size;
        this.mimeType = mimeType == null ? "" : mimeType;
        this.sourceFolder = sourceFolder == null ? "" : sourceFolder;
        this.treeRelDirs = treeRelDirs == null ? Collections.<String>emptyList() : treeRelDirs;
        this.problem = problem;
        this.nameReconstructed = nameReconstructed;
    }

    public boolean supportedFormat() {
        return MediaTypes.isSupported(displayName);
    }

    public String sizeLabel() {
        return size > 0 ? ByteFormat.bytes(size) : "size unknown";
    }
}
