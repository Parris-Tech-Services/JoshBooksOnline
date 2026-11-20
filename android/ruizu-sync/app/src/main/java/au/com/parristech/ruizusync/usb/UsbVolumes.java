package au.com.parristech.ruizusync.usb;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Detects removable storage (a USB MP3 player shows up here once Android has mounted it) and
 * produces a Storage Access Framework picker intent aimed at that volume.
 */
public final class UsbVolumes {

    public static final class Info {
        public final String label;
        public final String state;
        public final StorageVolume volume;

        Info(StorageVolume volume, String label, String state) {
            this.volume = volume;
            this.label = label;
            this.state = state;
        }
    }

    private UsbVolumes() {
    }

    public static List<Info> detect(Context context) {
        List<Info> found = new ArrayList<>();
        try {
            StorageManager manager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            if (manager == null) {
                return found;
            }
            for (StorageVolume volume : manager.getStorageVolumes()) {
                if (!volume.isRemovable() || volume.isPrimary()) {
                    continue;
                }
                String state = volume.getState();
                if (!Environment.MEDIA_MOUNTED.equals(state)) {
                    continue;
                }
                String label;
                try {
                    label = volume.getDescription(context);
                } catch (Throwable ignored) {
                    label = "USB storage";
                }
                found.add(new Info(volume, label, state));
            }
        } catch (Throwable ignored) {
            // StorageManager is not available on every build; the SAF picker still works.
        }
        return found;
    }

    /**
     * @return an ACTION_OPEN_DOCUMENT_TREE intent pre-targeted at this volume, or null when the
     *         platform does not support it (caller falls back to a generic folder picker).
     */
    public static Intent openTreeIntent(StorageVolume volume) {
        if (volume == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        try {
            Intent intent = volume.createOpenDocumentTreeIntent();
            if (intent != null) {
                return intent;
            }
        } catch (Throwable ignored) {
            // Some OEM builds reject this; fall back.
        }
        return null;
    }

    public static List<Info> emptyList() {
        return Collections.emptyList();
    }
}
