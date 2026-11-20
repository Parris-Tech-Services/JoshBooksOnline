package au.com.parristech.ruizusync.core;

/** One already existing file on the player. Pure Java, unit tested. */
public final class DestEntry {

    /** Path relative to the player root, using '/' separators. */
    public final String relPath;
    /** Name of the folder holding the file, "" for the player root. */
    public final String folderRelPath;
    public final String name;
    public final long size;

    private volatile String hash;

    public DestEntry(String relPath, String name, long size) {
        this.relPath = relPath;
        this.name = name;
        this.size = size;
        int slash = relPath.lastIndexOf('/');
        this.folderRelPath = slash < 0 ? "" : relPath.substring(0, slash);
    }

    /** SHA-256 of the content, only computed when the user asked for exact duplicate detection. */
    public String hash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }
}
