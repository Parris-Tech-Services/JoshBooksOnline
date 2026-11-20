package au.com.parristech.ruizusync.core;

import java.io.IOException;

/** What the duplicate matcher needs to know about an incoming file. Pure Java, unit tested. */
public final class SourceFileInfo {

    public interface HashProvider {
        /** SHA-256 of the source content; may be null when it cannot be computed. */
        String compute() throws IOException;
    }

    public final String name;
    public final long size;
    public final HashProvider hashProvider;

    public SourceFileInfo(String name, long size, HashProvider hashProvider) {
        this.name = name;
        this.size = size;
        this.hashProvider = hashProvider;
    }
}
