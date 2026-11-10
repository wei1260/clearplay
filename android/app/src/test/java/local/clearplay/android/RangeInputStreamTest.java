package local.clearplay.android;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RangeInputStreamTest {
    private static final byte[] BYTES = new byte[103];
    static { for (int i = 0; i < BYTES.length; i++) BYTES[i] = (byte) (i * 31); }

    @Test public void parallelResultsAreOrderedAndLastChunkIsExact() throws Exception {
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        try (var stream = RangeInputStream.open((start, end) -> {
            int count = active.incrementAndGet(); peak.accumulateAndGet(count, Math::max);
            try { Thread.sleep(start == 16 ? 60 : 10); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            finally { active.decrementAndGet(); }
            return response(start, end, 206, false, false);
        }, BYTES.length, 0, -1, 16)) {
            assertArrayEquals(BYTES, stream.readAllBytes());
            assertEquals(-1, stream.read());
            assertTrue(peak.get() > 1); assertTrue(peak.get() <= 3);
        }
    }
    @Test public void seekAndFiniteReadDoNotFetchOutsideRequestedRange() throws Exception {
        try (var stream = RangeInputStream.open((start, end) -> {
            assertTrue(start >= 37); assertTrue(end < 66);
            return response(start, end, 206, false, false);
        }, BYTES.length, 37, 29, 8)) {
            assertArrayEquals(Arrays.copyOfRange(BYTES, 37, 66), stream.readAllBytes());
        }
    }
    @Test public void serverIgnoringRangeUsesExistingSingleStreamFallback() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        assertNull(RangeInputStream.open((start, end) -> {
            requests.incrementAndGet(); return response(start, end, 200, false, false);
        }, BYTES.length, 0, -1, 8));
        assertEquals(1, requests.get());
    }
    @Test public void mismatchedOffsetsAndTruncatedSegmentsAreRejected() throws Exception {
        assertThrows(IOException.class, () -> RangeInputStream.open((s, e) -> response(s, e, 206, true, false), BYTES.length, 0, -1, 8));
        assertThrows(IOException.class, () -> RangeInputStream.open((s, e) -> response(s, e, 206, false, true), BYTES.length, 0, -1, 8));
        assertThrows(IOException.class, () -> RangeInputStream.open((s, e) -> response(s, e, 403, false, false), BYTES.length, 0, -1, 8));
    }
    @Test public void closeCancelsOutstandingRequestsAndRejectsReads() throws Exception {
        CountDownLatch started = new CountDownLatch(3), stopped = new CountDownLatch(3);
        var stream = RangeInputStream.open((start, end) -> {
            if (start > 0) {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { Thread.currentThread().interrupt(); throw new IOException("cancelled"); }
                finally { stopped.countDown(); }
            }
            return response(start, end, 206, false, false);
        }, BYTES.length, 0, -1, 8);
        assertTrue(started.await(2, TimeUnit.SECONDS)); stream.close();
        assertTrue(stopped.await(2, TimeUnit.SECONDS));
        assertThrows(IOException.class, stream::read);
    }
    private static HttpURLConnection response(long start, long end, int code, boolean wrongRange, boolean truncated) throws IOException {
        return new HttpURLConnection(new URL("https://drive.quark.cn/test")) {
            public int getResponseCode() { return code; }
            public String getHeaderField(String name) { return "bytes " + (wrongRange ? start + 1 : start) + "-" + end + "/" + BYTES.length; }
            public java.io.InputStream getInputStream() { return new ByteArrayInputStream(Arrays.copyOfRange(BYTES, (int) start, (int) end + (truncated ? 0 : 1))); }
            public void connect() { }
            public void disconnect() { }
            public boolean usingProxy() { return false; }
        };
    }
}
