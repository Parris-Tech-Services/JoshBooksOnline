package au.com.parristech.ruizusync.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * User selectable duplicate handling. Pure Java, unit tested.
 *
 * <p>Overwriting is deliberately not offered: existing audio on the player is never deleted or
 * replaced, so a transfer can only skip a duplicate or add another copy alongside it. The
 * OVERWRITE constant is kept purely so that state saved by older builds can be recognised and
 * migrated instead of crashing; it is never selectable and {@code TransferManager} rejects it.
 */
public enum DuplicateMode {
    SKIP_FAST("Skip duplicates (name + size)"),
    SKIP_EXACT("Skip duplicates (SHA-256 content)"),
    KEEP_BOTH("Keep both (rename new copy)"),

    /** Legacy only. Never selectable, never honoured. */
    @Deprecated
    OVERWRITE("Overwrite duplicates (no longer available)");

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

    /** False for modes that would destroy existing player audio. */
    public boolean supported() {
        return this != OVERWRITE;
    }

    /** Modes the UI may offer, in display order. */
    public static List<DuplicateMode> selectable() {
        List<DuplicateMode> modes = new ArrayList<>();
        for (DuplicateMode mode : values()) {
            if (mode.supported()) {
                modes.add(mode);
            }
        }
        return Collections.unmodifiableList(modes);
    }

    /**
     * Maps any stored value, including a legacy OVERWRITE, onto a mode that is safe to run.
     * SKIP_EXACT is the conservative choice: it only skips audio proven identical by SHA-256
     * and never touches the original.
     */
    public static DuplicateMode safe(DuplicateMode mode) {
        return mode == null || !mode.supported() ? SKIP_EXACT : mode;
    }

    /** Parses a stored name, falling back to a safe mode for unknown or legacy values. */
    public static DuplicateMode fromStoredName(String name, DuplicateMode fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return safe(valueOf(name));
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
