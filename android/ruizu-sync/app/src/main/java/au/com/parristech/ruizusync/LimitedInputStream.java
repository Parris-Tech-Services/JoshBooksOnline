package au.com.parristech.ruizusync;
import java.io.*;
/** Bounds untrusted RSS bytes, including feeds that never produce an episode. */
final class LimitedInputStream extends FilterInputStream {
    private long remaining;
    LimitedInputStream(InputStream input, long limit) { super(input); remaining = limit; }
    @Override public int read() throws IOException {
        int value = super.read();
        if (value != -1 && --remaining < 0) throw new IOException("Podcast feed exceeds 5 MiB");
        return value;
    }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        int n = in.read(bytes, offset, (int)Math.min(length, Math.max(1, remaining + 1)));
        if (n > 0 && (remaining -= n) < 0) throw new IOException("Podcast feed exceeds 5 MiB");
        return n;
    }
}
