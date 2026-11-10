package local.clearplay.android;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.CookieManager;

import androidx.media3.datasource.DataSpec;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class ReportedPlaybackTest {
    @Test public void inspectReportedVideo() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        assumeTrue("Account check must be explicit", "true".equals(args.getString("clearplayAccountCheck")));
        String provider = args.getString("clearplayProvider", "quark");
        String hint = args.containsKey("clearplayTarget") ? new String(android.util.Base64.decode(args.getString("clearplayTarget"), android.util.Base64.URL_SAFE), java.nio.charset.StandardCharsets.UTF_8) : "";
        JSONArray history = new JSONArray(InstrumentationRegistry.getInstrumentation().getTargetContext().getSharedPreferences("clearplay", 0).getString("history", "[]"));
        DriveClient.Item item = null;
        int sample = Integer.parseInt(args.getString("clearplaySample", "0")), matching = 0;
        for (int i = 0; i < history.length(); i++) {
            var row = history.getJSONObject(i);
            if (provider.equals(row.optString("provider")) && row.optString("name").contains(hint) && matching++ == sample) { item = DriveClient.Item.from(row); break; }
        }
        status(new JSONObject().put("phase", "history").put("provider", provider).put("targetFound", item != null));
        assertNotNull("Reported provider video not found in recent history", item);
        CookieManager manager = CookieManager.getInstance();
        if ("true".equals(args.getString("clearplayColdAuth")) && provider.equals("quark")) clearVideoAuth(manager);
        if ("true".equals(args.getString("clearplayPlayCheck"))) {
            status(new JSONObject().put("phase", "reported-playback").put("provider", provider).put("result", QuarkAccountTest.play(item)));
            return;
        }
        AtomicReference<String> expected = new AtomicReference<>("");
        List<String> attributes = new ArrayList<>();
        boolean sync = "true".equals(args.getString("clearplaySyncCookies"));
        DriveClient client = new DriveClient(new DriveClient.Cookies() {
            public String get(String url) { return manager.getCookie(url); }
            public void set(String url, String value) {
                if (value.startsWith("Video-Auth=")) {
                    expected.set(value.substring(11).split(";", 2)[0]);
                    for (String field : value.split(";")) if (field.trim().toLowerCase().startsWith("domain=") || field.trim().toLowerCase().startsWith("path=")) attributes.add(field.trim());
                }
                if (!sync) manager.setCookie(url, value);
                else {
                    CountDownLatch done = new CountDownLatch(1);
                    new Handler(Looper.getMainLooper()).post(() -> manager.setCookie(url, value, accepted -> done.countDown()));
                    try { assertTrue("Cookie callback timed out", done.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { throw new AssertionError(error); }
                }
            }
        });
        long start = SystemClock.elapsedRealtime();
        List<DriveClient.Source> sources = client.sources(item, seconds -> {
            try { status(new JSONObject().put("phase", "platform-wait").put("seconds", seconds)); } catch (Exception error) { throw new AssertionError(error); }
        }, ready -> {
            try {
                JSONArray labels = new JSONArray(); for (var source : ready) labels.put(source.label);
                status(new JSONObject().put("phase", "available-qualities").put("elapsedMs", SystemClock.elapsedRealtime() - start).put("labels", labels));
            } catch (Exception error) { throw new AssertionError(error); }
        });
        status(new JSONObject().put("phase", "sources").put("elapsedMs", SystemClock.elapsedRealtime() - start).put("count", sources.size()).put("syncCookies", sync).put("newVideoAuth", !expected.get().isEmpty()).put("cookieAttributes", new JSONArray(attributes)));
        for (DriveClient.Source source : sources) {
            if ("true".equals(args.getString("clearplayRangeProbe"))) {
                if (source.label.equals("原画")) {
                    for (String range : new String[]{"", "bytes=0-524287"}) {
                        long clock = SystemClock.elapsedRealtime();
                        var connection = DriveClient.Net.open(source.url, source.provider, source.headers, null, range.isEmpty() ? null : range);
                        JSONObject result = new JSONObject().put("phase", "original-range").put("bounded", !range.isEmpty()).put("http", connection.getResponseCode()).put("openMs", SystemClock.elapsedRealtime() - clock);
                        try (var input = connection.getInputStream()) {
                            int total = 0, n; byte[] bytes = new byte[32768];
                            while (total < 524288 && (n = input.read(bytes, 0, Math.min(bytes.length, 524288 - total))) > 0) total += n;
                            result.put("bytes", total).put("totalMs", SystemClock.elapsedRealtime() - clock);
                        } finally { connection.disconnect(); }
                        status(result);
                    }
                }
                continue;
            }
            JSONObject result = new JSONObject().put("phase", "source-probe").put("label", source.label).put("hls", source.hls).put("hasAudioSource", source.audio != null);
            if (!expected.get().isEmpty()) result.put("snapshotHasNewAuth", expected.get().equals(cookie(source.headers.get("Cookie"), "Video-Auth")));
            CloudDataSource.Registry registry = new CloudDataSource.Registry();
            long clock = SystemClock.elapsedRealtime();
            try {
                String root = registry.add(source);
                byte[] first = read(registry, root, source.hls ? 0 : 4096);
                result.put("openMs", SystemClock.elapsedRealtime() - clock).put("bytes", first.length);
                if (source.hls) inspectManifest(registry, new String(first, java.nio.charset.StandardCharsets.UTF_8), result, 0);
                else result.put("hasFtyp", new String(first, java.nio.charset.StandardCharsets.ISO_8859_1).contains("ftyp"));
                result.put("success", true);
            } catch (Exception error) {
                result.put("success", false).put("error", safe(error));
                if (provider.equals("quark")) {
                    SystemClock.sleep(350);
                    var fresh = new LinkedHashMap<>(source.headers);
                    fresh.put("Cookie", manager.getCookie("https://drive.quark.cn/"));
                    result.put("freshHasNewAuth", expected.get().equals(cookie(fresh.get("Cookie"), "Video-Auth")));
                    try {
                        var conn = DriveClient.Net.open(source.url, "quark", fresh, null, "bytes=0-4095");
                        try { result.put("freshHeaderHttp", conn.getResponseCode()); } finally { conn.disconnect(); }
                    } catch (Exception retry) { result.put("freshHeaderError", safe(retry)); }
                }
            }
            status(result);
        }
    }
    private void inspectManifest(CloudDataSource.Registry registry, String text, JSONObject result, int depth) throws Exception {
        if (depth > 2) return;
        String first = text.lines().filter(s -> s.startsWith("clearplay://")).findFirst().orElse(null);
        if (first == null) return;
        byte[] child = read(registry, first, text.contains("#EXT-X-STREAM-INF:") ? 0 : 4096);
        String content = new String(child, java.nio.charset.StandardCharsets.UTF_8);
        if (content.startsWith("#EXTM3U")) inspectManifest(registry, content, result, depth + 1);
        else result.put("segmentBytes", child.length);
        var audio = java.util.regex.Pattern.compile("#EXT-X-MEDIA:TYPE=AUDIO[^\n]*URI=\"([^\"]+)\"").matcher(text);
        if (audio.find()) {
            JSONObject check = new JSONObject();
            inspectManifest(registry, new String(read(registry, audio.group(1), 0), java.nio.charset.StandardCharsets.UTF_8), check, depth + 1);
            result.put("audio", check);
        }
    }
    private byte[] read(CloudDataSource.Registry registry, String uri, int limit) throws Exception {
        CloudDataSource source = new CloudDataSource(registry);
        try {
            DataSpec.Builder spec = new DataSpec.Builder().setUri(uri);
            if (limit > 0) spec.setLength(limit);
            source.open(spec.build());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
            while ((count = source.read(buffer, 0, limit > 0 ? Math.min(buffer.length, limit - bytes.size()) : buffer.length)) > 0) {
                bytes.write(buffer, 0, count); if (limit > 0 && bytes.size() >= limit) break;
            }
            return bytes.toByteArray();
        } finally { source.close(); }
    }
    private void clearVideoAuth(CookieManager manager) throws Exception {
        CountDownLatch done = new CountDownLatch(3);
        new Handler(Looper.getMainLooper()).post(() -> {
            for (String domain : new String[]{"", "; Domain=.quark.cn", "; Domain=.drive.quark.cn"})
                manager.setCookie("https://drive.quark.cn/", "Video-Auth=; Max-Age=0; Path=/; Secure" + domain, accepted -> done.countDown());
        });
        assertTrue(done.await(5, TimeUnit.SECONDS)); manager.flush();
    }
    private static String cookie(String header, String name) {
        if (header == null) return "";
        for (String pair : header.split(";")) { String[] parts = pair.trim().split("=", 2); if (parts.length == 2 && parts[0].equals(name)) return parts[1]; }
        return "";
    }
    private static String safe(Exception error) {
        if (error instanceof IllegalArgumentException) return "IllegalArgumentException: invalid media URI syntax";
        return (error.getClass().getSimpleName() + ": " + error.getMessage()).replaceAll("https?://\\S+|[^\\s]+\\?[^\\s]+", "[media-address]");
    }
    private static void status(JSONObject object) {
        Bundle progress = new Bundle(); progress.putString("stream", "CLEARPLAY_REPORTED=" + object + "\n"); InstrumentationRegistry.getInstrumentation().sendStatus(2, progress);
    }
}
