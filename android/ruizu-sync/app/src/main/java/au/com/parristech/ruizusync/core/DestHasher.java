package au.com.parristech.ruizusync.core;

import java.io.IOException;

/** Computes the SHA-256 of a file that already exists on the player. Implemented on Android. */
public interface DestHasher {
    /** @return SHA-256 hex, or null when the existing file cannot be read. */
    String hashOf(DestEntry entry) throws IOException;
}
