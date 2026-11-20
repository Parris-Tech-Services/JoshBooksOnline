package au.com.parristech.ruizusync;

/** Thrown inside the copy loop when the user cancels a running transfer. */
public final class CancelledException extends Exception {

    private static final long serialVersionUID = 1L;

    public CancelledException() {
        super("Transfer cancelled");
    }
}
