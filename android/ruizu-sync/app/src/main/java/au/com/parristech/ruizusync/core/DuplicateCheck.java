package au.com.parristech.ruizusync.core;

/** Result of a duplicate lookup. Pure Java, unit tested. */
public final class DuplicateCheck {

    public enum Reason {
        NONE,
        SAME_NAME_AND_SIZE,
        SAME_CONTENT
    }

    public final Reason reason;
    public final DestEntry match;

    private DuplicateCheck(Reason reason, DestEntry match) {
        this.reason = reason;
        this.match = match;
    }

    public static DuplicateCheck none() {
        return new DuplicateCheck(Reason.NONE, null);
    }

    public static DuplicateCheck duplicate(Reason reason, DestEntry match) {
        return new DuplicateCheck(reason, match);
    }

    public boolean isDuplicate() {
        return reason != Reason.NONE;
    }

    public String describe() {
        switch (reason) {
            case SAME_NAME_AND_SIZE:
                return "same name and size as " + match.relPath;
            case SAME_CONTENT:
                return "identical content to " + match.relPath;
            case NONE:
            default:
                return "not a duplicate";
        }
    }
}
