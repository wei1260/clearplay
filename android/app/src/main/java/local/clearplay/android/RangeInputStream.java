package local.clearplay.android;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/** Three bounded original-file reads, consumed in order; discarded on seek/close. */
final class RangeInputStream extends InputStream {
    interface Opener { HttpURLConnection open(long start, long end) throws IOException; }
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private final Opener opener;
    private final long size, end;
    private final int chunk;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final ConcurrentLinkedQueue<Future<byte[]>> pending = new ConcurrentLinkedQueue<>();
    private final Set<HttpURLConnection> connections = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile Future<byte[]> awaiting;
    private long next;
    private byte[] buffer;
    private int position;

    static RangeInputStream open(Opener opener, long size, long start, long length) throws IOException {
        return open(opener, size, start, length, 4 * 1024 * 1024);
    }
    static RangeInputStream open(Opener opener, long size, long start, long length, int chunk) throws IOException {
        if (size <= 0 || start < 0 || start >= size || length == 0 || chunk <= 0) return null;
        long end = length < 0 ? size : start + Math.min(length, size - start);
        long firstEnd = start + Math.min(chunk, end - start) - 1;
        HttpURLConnection first = opener.open(start, firstEnd);
        byte[] bytes;
        try {
            if (first.getResponseCode() == 200) return null; // Let the existing single stream handle non-Range servers.
            bytes = readChunk(first, start, firstEnd, size);
        } finally { first.disconnect(); }
        return new RangeInputStream(opener, size, end, chunk, start + bytes.length, bytes);
    }
    private RangeInputStream(Opener opener, long size, long end, int chunk, long next, byte[] first) {
        this.opener = opener; this.size = size; this.end = end; this.chunk = chunk; this.next = next; buffer = first;
        prefetch();
    }
    private void prefetch() {
        while (!closed && pending.size() < 3 && next < end) {
            long begin = next, finish = begin + Math.min(chunk, end - begin) - 1;
            next = finish + 1;
            try { pending.add(pool.submit(() -> {
                HttpURLConnection connection = opener.open(begin, finish);
                connections.add(connection);
                try {
                    if (closed) throw new IOException("播放读取已取消。");
                    return readChunk(connection, begin, finish, size);
                } finally { connections.remove(connection); connection.disconnect(); }
            })); } catch (java.util.concurrent.RejectedExecutionException error) { if (!closed) throw error; }
        }
    }
    private static byte[] readChunk(HttpURLConnection connection, long start, long end, long size) throws IOException {
        int code = connection.getResponseCode();
        if (code >= 400) throw new IOException("网盘媒体请求失败（HTTP " + code + "）。请重新取得资源。");
        var range = CONTENT_RANGE.matcher(String.valueOf(connection.getHeaderField("Content-Range")));
        if (code != 206 || !range.matches() || !range.group(1).equals(String.valueOf(start)) ||
                !range.group(2).equals(String.valueOf(end)) || !range.group(3).equals(String.valueOf(size)))
            throw new IOException("网盘返回的分段范围与文件不一致，请重新取得资源。");
        int count = Math.toIntExact(end - start + 1);
        byte[] bytes = new byte[count]; int received = 0;
        try (InputStream input = connection.getInputStream()) {
            while (received < count) {
                int n = input.read(bytes, received, count - received);
                if (n < 0) throw new IOException("网盘媒体分段不完整，请重新取得资源。");
                received += n;
            }
        }
        return bytes;
    }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        if (closed) throw new IOException("播放读取已取消。");
        if (length == 0) return 0;
        if (position == buffer.length) {
            awaiting = pending.poll();
            if (awaiting == null) return -1;
            try { buffer = awaiting.get(); position = 0; }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("播放读取已取消。", error); }
            catch (ExecutionException error) { throw error.getCause() instanceof IOException io ? io : new IOException("分段读取失败。", error.getCause()); }
            catch (java.util.concurrent.CancellationException error) { throw new IOException("播放读取已取消。", error); }
            finally { awaiting = null; }
            if (closed) throw new IOException("播放读取已取消。");
            prefetch();
        }
        int count = Math.min(length, buffer.length - position);
        System.arraycopy(buffer, position, bytes, offset, count); position += count; return count;
    }
    @Override public int read() throws IOException {
        byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }
    @Override public void close() {
        closed = true;
        Future<byte[]> current = awaiting;
        if (current != null) current.cancel(true);
        pending.forEach(future -> future.cancel(true)); pending.clear();
        connections.forEach(HttpURLConnection::disconnect);
        pool.shutdownNow();
    }
}
