package au.com.parristech.ruizusync.core;

import java.util.Locale;
import java.util.Set;

/** Picks a file name that does not collide inside one player folder. Pure Java, unit tested. */
public final class NameCollision {

    private NameCollision() {
    }

    /**
     * @param desiredName      preferred file name
     * @param existingLowerNames lower-cased names already in the target folder
     * @return desiredName when free, otherwise "name (2).ext", "name (3).ext", ...
     */
    public static String resolve(String desiredName, Set<String> existingLowerNames) {
        if (existingLowerNames == null || !existingLowerNames.contains(desiredName.toLowerCase(Locale.ROOT))) {
            return desiredName;
        }
        int dot = desiredName.lastIndexOf('.');
        String base = dot > 0 ? desiredName.substring(0, dot) : desiredName;
        String extension = dot > 0 ? desiredName.substring(dot) : "";
        for (int attempt = 2; attempt < 10000; attempt++) {
            String candidate = base + " (" + attempt + ")" + extension;
            if (!existingLowerNames.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return base + " (" + System.currentTimeMillis() + ")" + extension;
    }
}
