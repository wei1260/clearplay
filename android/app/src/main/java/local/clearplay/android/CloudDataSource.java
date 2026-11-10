package local.clearplay.android;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class CloudDataSource extends BaseDataSource {
    static final class Registry {
        private final Map<String, DriveClient.Source> entries = new ConcurrentHashMap<>();
        synchronized void clear() { entries.clear(); }
        synchronized String add(DriveClient.Source source) throws IOException {
            if (source.url != null) DriveClient.Net.allowed(source.url, source.provider);
            if (source.hls && source.audio != null) {
                String audio = add(source.audio);
                DriveClient.Source video = new DriveClient.Source(source.label, source.url, source.provider, source.headers, true, source.manifest);
                String videoUrl = add(video);
                String master = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"sound\",NAME=\"Audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"" + audio + "\"\n#EXT-X-STREAM-INF:BANDWIDTH=8000000,AUDIO=\"sound\"\n" + videoUrl + "\n";
                source = new DriveClient.Source(source.label, null, source.provider, Map.of(), true, master);
            }
            String id = UUID.randomUUID().toString(); entries.put(id, source);
            return "clearplay://media/" + id;
        }
        DriveClient.Source get(String value) throws IOException {
            java.net.URI uri = java.net.URI.create(value);
            if (!"clearplay".equals(uri.getScheme()) || !"media".equals(uri.getHost())) throw new IOException("播放地址无效。");
            DriveClient.Source entry = entries.get(uri.getPath().substring(1));
            if (entry == null) throw new IOException("播放会话已结束，请重新选择视频。");
            return entry;
        }
        String rewrite(String text, String base, DriveClient.Source source) throws IOException {
            if (base == null) return text; // 合成的主清单只含本机随机地址。
            StringBuilder out = new StringBuilder();
            Pattern uri = Pattern.compile("URI=\"([^\"]+)\"");
            for (String line : text.split("\n", -1)) {
                if (line.startsWith("#")) {
                    Matcher matcher = uri.matcher(line);
                    StringBuffer changed = new StringBuffer();
                    while (matcher.find()) matcher.appendReplacement(changed, Matcher.quoteReplacement("URI=\"" + child(matcher.group(1), base, source) + "\""));
                    matcher.appendTail(changed); out.append(changed);
                } else if (!line.trim().isEmpty()) out.append(child(line.trim(), base, source));
                out.append('\n');
            }
            return out.toString();
        }
        private String child(String value, String base, DriveClient.Source parent) throws IOException {
            String url = DriveClient.Net.reference(base).resolve(DriveClient.Net.reference(value)).toString();
            return add(new DriveClient.Source(parent.label, url, parent.provider, parent.headers, url.matches("(?i).*\\.m3u8(?:\\?.*)?"), null));
        }
    }
    private final Registry registry;
    private InputStream input;
    private HttpURLConnection connection;
    private Uri uri;
    private long remaining;
    private boolean opened;
    CloudDataSource(Registry registry) { super(true); this.registry = registry; }

    @Override public long open(DataSpec spec) throws IOException {
        transferInitializing(spec); uri = spec.uri;
        DriveClient.Source source = registry.get(uri.toString());
        try {
            if (source.manifest != null) {
                byte[] data = registry.rewrite(source.manifest, source.url, source).getBytes(StandardCharsets.UTF_8);
                if (spec.position > data.length) throw new IOException("无效的清单读取范围。");
                remaining = data.length - spec.position;
                if (spec.length != C.LENGTH_UNSET) remaining = Math.min(remaining, spec.length);
                input = new ByteArrayInputStream(data, (int) spec.position, (int) remaining);
            } else {
                if (source.provider.equals("quark") && source.label.equals("原画") && !source.hls && source.fileSize > 0) {
                    input = RangeInputStream.open((start, end) -> DriveClient.Net.open(source.url, source.provider, source.headers, null,
                            "bytes=" + start + "-" + end), source.fileSize, spec.position, spec.length);
                    if (input != null) {
                        remaining = source.fileSize - spec.position;
                        if (spec.length != C.LENGTH_UNSET) remaining = Math.min(remaining, spec.length);
                        opened = true; transferStarted(spec); return remaining;
                    }
                }
                String range = spec.position > 0 || spec.length != C.LENGTH_UNSET ? "bytes=" + spec.position + "-" + (spec.length == C.LENGTH_UNSET ? "" : spec.position + spec.length - 1) : null;
                connection = DriveClient.Net.open(source.url, source.provider, source.headers, null, range);
                int status = connection.getResponseCode();
                if (status >= 400) throw new IOException("网盘媒体请求失败（HTTP " + status + "）。请重新取得资源。");
                String contentType = connection.getContentType();
                if (source.hls || (contentType != null && contentType.toLowerCase().contains("mpegurl"))) {
                    String text = DriveClient.Net.text(connection.getInputStream());
                    byte[] data = registry.rewrite(text, connection.getURL().toString(), source).getBytes(StandardCharsets.UTF_8);
                    input = new ByteArrayInputStream(data); remaining = data.length;
                } else {
                    input = connection.getInputStream();
                    if (status == 200 && spec.position > 0) {
                        long left = spec.position;
                        while (left > 0) {
                            long skipped = input.skip(left);
                            if (skipped == 0) { if (input.read() == -1) throw new IOException("视频读取范围超出文件。"); skipped = 1; }
                            left -= skipped;
                        }
                    }
                    remaining = spec.length != C.LENGTH_UNSET ? spec.length : connection.getContentLengthLong();
                    if (remaining < 0) remaining = C.LENGTH_UNSET;
                    else if (status == 200 && spec.length == C.LENGTH_UNSET) remaining -= spec.position;
                }
            }
            opened = true; transferStarted(spec); return remaining;
        } catch (IOException error) { close(); throw error; }
    }
    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        int count = input.read(buffer, offset, remaining == C.LENGTH_UNSET ? length : (int) Math.min(length, remaining));
        if (count == -1) return C.RESULT_END_OF_INPUT;
        if (remaining != C.LENGTH_UNSET) remaining -= count;
        bytesTransferred(count); return count;
    }
    @Override public Uri getUri() { return uri; }
    @Override public void close() throws IOException {
        try { if (input != null) input.close(); }
        finally {
            input = null;
            if (connection != null) { connection.disconnect(); connection = null; }
            if (opened) { opened = false; transferEnded(); }
        }
    }
}
