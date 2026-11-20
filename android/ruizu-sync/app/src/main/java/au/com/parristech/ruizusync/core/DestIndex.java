package au.com.parristech.ruizusync.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-memory index of the files already on the player, used for duplicate detection and for
 * picking a free target name. Pure Java, unit tested.
 */
public final class DestIndex {

    private final Map<String, List<DestEntry>> bySize = new HashMap<>();
    private final Map<String, DestEntry> byRelPathLower = new LinkedHashMap<>();
    private final Map<String, Set<String>> namesByFolderLower = new HashMap<>();

    public void add(DestEntry entry) {
        List<DestEntry> sameSize = bySize.get(sizeKey(entry.size));
        if (sameSize == null) {
            sameSize = new ArrayList<>();
            bySize.put(sizeKey(entry.size), sameSize);
        }
        sameSize.add(entry);
        byRelPathLower.put(entry.relPath.toLowerCase(Locale.ROOT), entry);
        namesByFolderLower
                .computeIfAbsent(entry.folderRelPath.toLowerCase(Locale.ROOT), k -> new HashSet<>())
                .add(entry.name.toLowerCase(Locale.ROOT));
    }

    public int size() {
        return byRelPathLower.size();
    }

    public Collection<DestEntry> all() {
        return Collections.unmodifiableCollection(byRelPathLower.values());
    }

    /** Entries with exactly this byte size; empty when the size is unknown (-1). */
    public List<DestEntry> sameSize(long size) {
        List<DestEntry> entries = bySize.get(sizeKey(size));
        return entries == null ? Collections.<DestEntry>emptyList() : entries;
    }

    /**
     * Entries with this byte size that live directly inside one folder ("" = player root).
     * Used when the user only wants duplicates inside the destination folder to count.
     */
    public List<DestEntry> sameSizeInFolder(long size, String folderRelPath) {
        List<DestEntry> all = sameSize(size);
        if (all.isEmpty()) {
            return all;
        }
        String key = folderRelPath == null ? "" : folderRelPath.toLowerCase(Locale.ROOT);
        List<DestEntry> scoped = new ArrayList<>();
        for (DestEntry entry : all) {
            if (entry.folderRelPath.toLowerCase(Locale.ROOT).equals(key)) {
                scoped.add(entry);
            }
        }
        return scoped;
    }

    /** All files directly inside one folder ("" = player root), sorted by name. */
    public List<DestEntry> filesInFolder(String folderRelPath) {
        String key = folderRelPath == null ? "" : folderRelPath.toLowerCase(Locale.ROOT);
        List<DestEntry> files = new ArrayList<>();
        for (DestEntry entry : byRelPathLower.values()) {
            if (entry.folderRelPath.toLowerCase(Locale.ROOT).equals(key)) {
                files.add(entry);
            }
        }
        files.sort(Comparator.comparing(entry -> entry.name.toLowerCase(Locale.ROOT)));
        return files;
    }

    /** Lower-cased names already present in the given folder ("" = player root). */
    public Set<String> namesInFolder(String folderRelPath) {
        Set<String> names = namesByFolderLower.get(folderRelPath.toLowerCase(Locale.ROOT));
        return names == null ? Collections.<String>emptySet() : Collections.unmodifiableSet(names);
    }

    public DestEntry at(String relPath) {
        return byRelPathLower.get(relPath.toLowerCase(Locale.ROOT));
    }

    /** Drops an entry after it was deleted or overwritten on the player. */
    public void remove(String relPath) {
        DestEntry removed = byRelPathLower.remove(relPath.toLowerCase(Locale.ROOT));
        if (removed == null) {
            return;
        }
        String sizeKey = sizeKey(removed.size);
        List<DestEntry> sameSize = bySize.get(sizeKey);
        if (sameSize != null) {
            sameSize.remove(removed);
            if (sameSize.isEmpty()) {
                bySize.remove(sizeKey);
            }
        }
        String folderKey = removed.folderRelPath.toLowerCase(Locale.ROOT);
        Set<String> names = namesByFolderLower.get(folderKey);
        if (names != null) {
            names.remove(removed.name.toLowerCase(Locale.ROOT));
            if (names.isEmpty()) {
                namesByFolderLower.remove(folderKey);
            }
        }
    }

    private static String sizeKey(long size) {
        return size < 0 ? "unknown" : Long.toString(size);
    }
}
