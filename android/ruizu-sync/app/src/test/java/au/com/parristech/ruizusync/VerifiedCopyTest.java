package au.com.parristech.ruizusync;
import org.junit.Test;
import java.io.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class VerifiedCopyTest {
    private final byte[] data = new byte[200000];
    private byte[] copy(InputStream in, OutputStream out, long size) throws IOException {
        return VerifiedCopy.copy(in, out, size, () -> false, (n, total) -> {});
    }
    @Test public void roundTripIntegrityAndProgress() throws Exception {
        for (int i=0;i<data.length;i++) data[i]=(byte)i;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtomicLong progress = new AtomicLong();
        byte[] digest = VerifiedCopy.copy(new ByteArrayInputStream(data), out, data.length,
            () -> false, (n,total) -> { assertTrue(n >= progress.get()); assertEquals(data.length,total); progress.set(n); });
        assertArrayEquals(data,out.toByteArray());
        assertEquals(data.length,progress.get());
        VerifiedCopy.verify(digest,new ByteArrayInputStream(out.toByteArray()), () -> false);
    }
    @Test(expected=EOFException.class) public void truncatedSourceFails() throws Exception {
        copy(new ByteArrayInputStream(data),new ByteArrayOutputStream(),data.length+1);
    }
    @Test(expected=IOException.class) public void excessSourceFails() throws Exception {
        copy(new ByteArrayInputStream(data),new ByteArrayOutputStream(),data.length-1);
    }
    @Test(expected=IOException.class) public void outOfSpaceFails() throws Exception {
        copy(new ByteArrayInputStream(data),new OutputStream() { public void write(int b) throws IOException {throw new IOException("ENOSPC");}}, data.length);
    }
    @Test(expected=IOException.class) public void disconnectFails() throws Exception {
        copy(new InputStream() { public int read() throws IOException {throw new IOException("USB detached");}},new ByteArrayOutputStream(),-1);
    }
    @Test(expected=InterruptedIOException.class) public void cancelledBeforeWrite() throws Exception {
        VerifiedCopy.copy(new ByteArrayInputStream(data),new ByteArrayOutputStream(),data.length,() -> true,(n,t)->fail());
    }
    @Test(expected=InterruptedIOException.class) public void cancelDuringTransfer() throws Exception {
        AtomicBoolean cancel = new AtomicBoolean();
        VerifiedCopy.copy(new ByteArrayInputStream(data),new ByteArrayOutputStream(),data.length,cancel::get,(n,t)->cancel.set(true));
    }
    @Test(expected=IOException.class) public void corruptionDetected() throws Exception {
        byte[] digest = copy(new ByteArrayInputStream(data),new ByteArrayOutputStream(),data.length);
        data[100] = 42;
        VerifiedCopy.verify(digest,new ByteArrayInputStream(data),()->false);
    }
    @Test public void unknownLengthSupported() throws Exception {
        assertEquals(32,copy(new ByteArrayInputStream(data),new ByteArrayOutputStream(),-1).length);
    }
    @Test public void countersHandleOverTwoGiB() throws Exception {
        // Real 2 GiB+ stream without allocation or USB claims.
        long length = (1L << 31) + 17;
        InputStream source = new InputStream() {
            long remaining = length;
            public int read() { if (remaining == 0) return -1; remaining--; return 0; }
            public int read(byte[] b,int off,int len) {
                if (remaining == 0) return -1;
                int n=(int)Math.min(len,remaining); remaining-=n; return n;
            }
        };
        AtomicLong copied = new AtomicLong();
        VerifiedCopy.copy(source,OutputStream.nullOutputStream(),length,()->false,(n,t)->copied.set(n));
        assertEquals(length,copied.get());
    }
    @Test(expected=IOException.class) public void fat32LimitPreflight() throws Exception {
        copy(new ByteArrayInputStream(data),new ByteArrayOutputStream(),1L << 32);
    }
    @Test public void boundedFeedAtLimit() throws Exception {
        try(InputStream input = new LimitedInputStream(new ByteArrayInputStream(new byte[10]),10)) {
            assertEquals(10,input.read(new byte[20])); assertEquals(-1,input.read());
        }
    }
    @Test(expected=IOException.class) public void oversizedFeedFails() throws Exception {
        new LimitedInputStream(new ByteArrayInputStream(new byte[11]),10).read(new byte[20]);
    }
}
