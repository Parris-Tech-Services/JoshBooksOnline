package au.com.parristech.ruizusync;

import java.io.*;
import java.security.*;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Streaming core shared by SAF and fault-injection tests. Never owns/deletes a source. */
public final class VerifiedCopy {
    public static final long MAX_BYTES = 0xffff_ffffL; // FAT32 single-file limit, long counters throughout.
    public interface Progress { void update(long copied, long total); }
    private VerifiedCopy() {}
    public static byte[] copy(InputStream in, OutputStream out, long expected,
                              BooleanSupplier cancelled, Progress progress) throws IOException {
        if (expected > MAX_BYTES) throw new IOException("File exceeds FAT32 4 GiB limit");
        MessageDigest digest = sha256();
        byte[] buffer = new byte[65536];
        long copied = 0;
        int count;
        checkCancelled(cancelled);
        while ((count = in.read(buffer)) != -1) {
            checkCancelled(cancelled);
            if (count == 0) continue;
            copied += count;
            if (copied > MAX_BYTES || (expected >= 0 && copied > expected))
                throw new IOException("Source length changed or exceeds FAT32 limit");
            out.write(buffer, 0, count);
            digest.update(buffer, 0, count);
            progress.update(copied, expected);
        }
        checkCancelled(cancelled);
        if (expected >= 0 && copied != expected) throw new EOFException("Incomplete source; retry after reconnecting");
        out.flush();
        return digest.digest();
    }
    public static byte[] digest(InputStream in, BooleanSupplier cancelled) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[65536];
        long count = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            checkCancelled(cancelled);
            count += n;
            if (count > MAX_BYTES) throw new IOException("File exceeds FAT32 limit");
            digest.update(buffer, 0, n);
        }
        checkCancelled(cancelled);
        return digest.digest();
    }
    public static void verify(byte[] expected, InputStream in, BooleanSupplier cancelled) throws IOException {
        if (!Arrays.equals(expected, digest(in, cancelled))) throw new IOException("USB integrity check failed; retry transfer");
    }
    public static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Transfer cancelled");
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
