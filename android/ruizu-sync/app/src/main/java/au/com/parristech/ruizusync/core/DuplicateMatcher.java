package au.com.parristech.ruizusync.core;

import java.io.IOException;
import java.util.List;

/**
 * Duplicate rules:
 *
 * <ul>
 *   <li>Cheap mode: a file already on the player with the same name (case-insensitive) and the
 *       same byte size is treated as a duplicate.</li>
 *   <li>Exact mode: when sizes match but names differ, the source and the existing file are both
 *       hashed with SHA-256 and compared, so re-tagged copies are still recognised.</li>
 * </ul>
 *
 * <p>Hashing only happens for size matches, so normal transfers never pay for a second read.</p>
 */
public final class DuplicateMatcher {

    private DuplicateMatcher() {
    }

    public static DuplicateCheck check(SourceFileInfo source,
                                       DestIndex index,
                                       boolean exact,
                                       DestHasher destHasher) throws IOException {
        return check(source, index, exact, destHasher, null);
    }

    /**
     * @param onlyInFolder when non-null, only files inside that folder ("" = player root) count
     *                     as duplicates; when null the whole player tree is searched.
     */
    public static DuplicateCheck check(SourceFileInfo source,
                                       DestIndex index,
                                       boolean exact,
                                       DestHasher destHasher,
                                       String onlyInFolder) throws IOException {
        if (source == null || source.size < 0) {
            return DuplicateCheck.none();
        }
        List<DestEntry> candidates = onlyInFolder == null
                ? index.sameSize(source.size)
                : index.sameSizeInFolder(source.size, onlyInFolder);
        if (candidates.isEmpty()) {
            return DuplicateCheck.none();
        }
        for (DestEntry candidate : candidates) {
            if (candidate.name.equalsIgnoreCase(source.name)) {
                return DuplicateCheck.duplicate(DuplicateCheck.Reason.SAME_NAME_AND_SIZE, candidate);
            }
        }
        if (!exact || source.hashProvider == null) {
            return DuplicateCheck.none();
        }
        String sourceHash = source.hashProvider.compute();
        if (sourceHash == null || sourceHash.isEmpty()) {
            return DuplicateCheck.none();
        }
        for (DestEntry candidate : candidates) {
            String existing = candidate.hash();
            if (existing == null && destHasher != null) {
                existing = destHasher.hashOf(candidate);
                candidate.setHash(existing);
            }
            if (sourceHash.equalsIgnoreCase(existing)) {
                return DuplicateCheck.duplicate(DuplicateCheck.Reason.SAME_CONTENT, candidate);
            }
        }
        return DuplicateCheck.none();
    }
}
