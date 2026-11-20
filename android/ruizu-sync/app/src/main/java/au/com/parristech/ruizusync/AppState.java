package au.com.parristech.ruizusync;

import android.net.Uri;

import au.com.parristech.ruizusync.core.DuplicateMode;
import au.com.parristech.ruizusync.core.FolderPlanner;
import au.com.parristech.ruizusync.model.SourceItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory selection state shared by the activity across configuration changes. The transfer
 * itself lives in {@link TransferManager}; this only remembers what the user picked.
 */
public final class AppState {

    private static final AppState INSTANCE = new AppState();

    public static AppState get() {
        return INSTANCE;
    }

    private final Map<String, SourceItem> sources = new LinkedHashMap<>();
    private final List<Uri> sourceTrees = new ArrayList<>();
    private Uri destTree;
    private String destLabel = "";
    private FolderPlanner.OrganiseMode organiseMode = FolderPlanner.OrganiseMode.FLAT;
    private DuplicateMode duplicateMode = DuplicateMode.SKIP_FAST;

    private AppState() {
    }

    public synchronized List<SourceItem> sources() {
        return new ArrayList<>(sources.values());
    }

    public synchronized int sourceCount() {
        return sources.size();
    }

    public synchronized void addSources(List<SourceItem> items) {
        for (SourceItem item : items) {
            sources.put(item.uri.toString(), item);
        }
    }

    public synchronized void addSource(SourceItem item) {
        sources.put(item.uri.toString(), item);
    }

    public synchronized void clearSources() {
        sources.clear();
        sourceTrees.clear();
    }

    public synchronized List<Uri> sourceTrees() {
        return new ArrayList<>(sourceTrees);
    }

    public synchronized void addSourceTree(Uri tree) {
        if (tree != null && !sourceTrees.contains(tree)) {
            sourceTrees.add(tree);
        }
    }

    public synchronized Uri destinationTree() {
        return destTree;
    }

    public synchronized String destinationLabel() {
        return destLabel;
    }

    public synchronized void setDestination(Uri tree, String label) {
        destTree = tree;
        destLabel = label == null ? "" : label;
    }

    public synchronized void clearDestination() {
        destTree = null;
        destLabel = "";
    }

    public synchronized FolderPlanner.OrganiseMode organiseMode() {
        return organiseMode;
    }

    public synchronized void setOrganiseMode(FolderPlanner.OrganiseMode mode) {
        organiseMode = mode == null ? FolderPlanner.OrganiseMode.FLAT : mode;
    }

    public synchronized DuplicateMode duplicateMode() {
        return duplicateMode;
    }

    public synchronized void setDuplicateMode(DuplicateMode mode) {
        duplicateMode = mode == null ? DuplicateMode.SKIP_FAST : mode;
    }
}
