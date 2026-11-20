package au.com.parristech.ruizusync.core;

/** Byte / file counters for one transfer run. Pure Java, unit tested. */
public final class TransferStats {

    public enum Outcome {
        COPIED,
        DUPLICATE_SKIPPED,
        UNSUPPORTED,
        FAILED,
        DRM_BLOCKED
    }

    private int totalFiles;
    private long totalBytes;
    private int completedFiles;
    private int copiedFiles;
    private int duplicateFiles;
    private int unsupportedFiles;
    private int failedFiles;
    private int drmFiles;
    private int renamedFiles;
    private String currentFileName = "";
    private long currentBytes;
    private long currentTotal = -1;
    private long doneBytes;
    private long startMillis;

    public synchronized void start(int totalFiles, long totalBytes) {
        this.totalFiles = totalFiles;
        this.totalBytes = totalBytes;
        this.completedFiles = 0;
        this.copiedFiles = 0;
        this.duplicateFiles = 0;
        this.unsupportedFiles = 0;
        this.failedFiles = 0;
        this.drmFiles = 0;
        this.renamedFiles = 0;
        this.currentFileName = "";
        this.currentBytes = 0;
        this.currentTotal = -1;
        this.doneBytes = 0;
        this.startMillis = System.currentTimeMillis();
    }

    public synchronized void beginFile(String name, long size) {
        this.currentFileName = name == null ? "" : name;
        this.currentBytes = 0;
        this.currentTotal = size;
    }

    public synchronized void addBytes(long count) {
        if (count <= 0) {
            return;
        }
        currentBytes += count;
        doneBytes += count;
    }

    public synchronized void finishFile(Outcome outcome) {
        completedFiles++;
        switch (outcome) {
            case COPIED:
                copiedFiles++;
                break;
            case DUPLICATE_SKIPPED:
                duplicateFiles++;
                break;
            case UNSUPPORTED:
                unsupportedFiles++;
                break;
            case DRM_BLOCKED:
                drmFiles++;
                break;
            case FAILED:
                failedFiles++;
                break;
            default:
                break;
        }
        if (currentTotal > currentBytes) {
            // Count skipped / failed files as done so the overall bar can still reach 100%.
            doneBytes += currentTotal - currentBytes;
        }
        currentFileName = "";
        currentBytes = 0;
        currentTotal = -1;
    }

    public synchronized void markRenamed() {
        renamedFiles++;
    }

    public synchronized double fraction() {
        if (totalFiles > 0 && completedFiles >= totalFiles) {
            return 1.0;
        }
        if (totalBytes > 0) {
            return Math.min(1.0, (double) doneBytes / (double) totalBytes);
        }
        if (totalFiles > 0) {
            return Math.min(1.0, (double) completedFiles / (double) totalFiles);
        }
        return 0.0;
    }

    public synchronized double bytesPerSecond() {
        if (startMillis <= 0) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - startMillis;
        if (elapsed <= 0) {
            return 0;
        }
        return doneBytes * 1000.0 / elapsed;
    }

    public synchronized long etaSeconds() {
        if (totalBytes <= 0) {
            return -1;
        }
        long remaining = totalBytes - doneBytes;
        if (remaining <= 0) {
            return 0;
        }
        double rate = bytesPerSecond();
        if (rate <= 0) {
            return -1;
        }
        return Math.round(remaining / rate);
    }

    public synchronized int getTotalFiles() {
        return totalFiles;
    }

    public synchronized long getTotalBytes() {
        return totalBytes;
    }

    public synchronized int getCompletedFiles() {
        return completedFiles;
    }

    public synchronized int getCopiedFiles() {
        return copiedFiles;
    }

    public synchronized int getDuplicateFiles() {
        return duplicateFiles;
    }

    public synchronized int getUnsupportedFiles() {
        return unsupportedFiles;
    }

    public synchronized int getFailedFiles() {
        return failedFiles;
    }

    public synchronized int getDrmFiles() {
        return drmFiles;
    }

    public synchronized int getRenamedFiles() {
        return renamedFiles;
    }

    public synchronized String getCurrentFileName() {
        return currentFileName;
    }

    public synchronized long getCurrentBytes() {
        return currentBytes;
    }

    public synchronized long getCurrentTotal() {
        return currentTotal;
    }

    public synchronized long getDoneBytes() {
        return doneBytes;
    }

    public synchronized double getCurrentFileFraction() {
        if (currentTotal <= 0) {
            return -1;
        }
        return Math.min(1.0, (double) currentBytes / (double) currentTotal);
    }
}
