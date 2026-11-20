package au.com.parristech.ruizusync.core;

/** User selectable duplicate handling. Pure Java, unit tested. */
public enum DuplicateMode {
    SKIP_FAST("Skip duplicates (name + size)"),
    SKIP_EXACT("Skip duplicates (SHA-256 content)"),
    OVERWRITE("Overwrite duplicates"),
    KEEP_BOTH("Keep both (rename new copy)");

    public final String label;

    DuplicateMode(String label) {
        this.label = label;
    }

    public boolean exactHash() {
        return this == SKIP_EXACT;
    }

    public boolean skips() {
        return this == SKIP_FAST || this == SKIP_EXACT;
    }

    @Override
    public String toString() {
        return label;
    }
}
