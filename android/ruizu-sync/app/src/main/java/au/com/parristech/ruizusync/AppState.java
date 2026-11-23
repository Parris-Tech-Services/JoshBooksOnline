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
    /** Marker id of the player the destination was chosen on, or null when unidentified. */
    private String destDeviceId;
    private String destDeviceName;
    private FolderPlanner.OrganiseMode organiseMode = FolderPlanner.OrganiseMode.FLAT;
    private DuplicateMode duplicateMode = DuplicateMode.SKIP_FAST;
    private boolean duplicatesInTargetFolderOnly = false;
    private boolean writePlaylists = false;
    private boolean convertToMp3 = false;

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
        setDestination(tree, label, null, null);
    }

    /**
     * @param deviceId   marker id of the player this folder belongs to, or null when the player
     *                   could not be identified. Never invented: null means "we do not know".
     * @param deviceName user-visible name from the same marker.
     */
    public synchronized void setDestination(Uri tree, String label, String deviceId, String deviceName) {
        destTree = tree;
        destLabel = label == null ? "" : label;
        destDeviceId = deviceId == null || deviceId.trim().isEmpty() ? null : deviceId.trim();
        destDeviceName = deviceName == null || deviceName.trim().isEmpty() ? null : deviceName.trim();
    }

    /** Marker id of the player the destination was chosen on, or null when unidentified. */
    public synchronized String destinationDeviceId() {
        return destDeviceId;
    }

    public synchronized String destinationDeviceName() {
        return destDeviceName;
    }

    public synchronized void clearDestination() {
        destTree = null;
        destLabel = "";
        destDeviceId = null;
        destDeviceName = null;
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
        // Overwriting is not permitted, so a legacy OVERWRITE selection migrates to a safe mode
        // instead of leaving the UI on an option the transfer engine will refuse.
        duplicateMode = DuplicateMode.safe(mode);
    }



    public synchronized boolean duplicatesInTargetFolderOnly() {
        return duplicatesInTargetFolderOnly;
    }

    public synchronized void setDuplicatesInTargetFolderOnly(boolean value) {
        duplicatesInTargetFolderOnly = value;
    }

    public synchronized boolean writePlaylists() {
        return writePlaylists;
    }

    public synchronized void setWritePlaylists(boolean value) {
        writePlaylists = value;
    }

    public synchronized boolean convertToMp3() {
        return convertToMp3;
    }

    public synchronized void setConvertToMp3(boolean value) {
        convertToMp3 = value;
    }
}
