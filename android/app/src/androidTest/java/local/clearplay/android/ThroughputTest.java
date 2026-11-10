package local.clearplay.android;

import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.CookieManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Explicit, bounded own-account probes. Never exports credentials, URLs or media bytes. */
@RunWith(AndroidJUnit4.class)
public class ThroughputTest {
    private static final int MIB = 1024 * 1024;
    private static final String QUARK_CLIENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.56 Chrome/100.0.4896.160 Electron/18.3.5.12 Safari/537.36 Channel/pckk_other_ch";
    private static final String BAIDU_CLIENT = "netdisk;P2SP;3.0.0.8;netdisk;11.12.3;ANG-AN00;android-android;10.0;JSbridge4.4.0;jointBridge;1.1.0;";
    private CookieManager cookies;
    private DriveClient client;
    private String provider;
    private MainActivity activity;
    private final Map<String, byte[]> reference = new LinkedHashMap<>();

    @Test public void compareOwnAccountTransfers() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        assumeTrue("Account check must be explicit", "true".equals(args.getString("clearplayAccountCheck")));
        assertEquals("31064", parseServerCode("{\"error_code\":31064}"));
        assertEquals("-6", parseServerCode("{\"errno\":\"-6\",\"request_id\":123}"));
        assertEquals("AccessDenied", parseServerCode("<Error><Code>AccessDenied</Code></Error>"));
        assertEquals("", parseServerCode("{\"request_id\":123}"));
        provider = args.getString("clearplayProvider", "quark");
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        // The adb runner opens exactly one activity. A duplicate launch can pause it.
        long launchDeadline = SystemClock.elapsedRealtime() + 15000;
        while (activity == null && SystemClock.elapsedRealtime() < launchDeadline) {
            instrumentation.runOnMainSync(() -> {
                for (var current : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
                    if (current instanceof MainActivity main) activity = main;
            });
            SystemClock.sleep(100);
        }
        assertNotNull("Foreground player activity is required", activity);
        instrumentation.runOnMainSync(() -> {
            activity.setTurnScreenOn(true);
            activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        instrumentation.runOnMainSync(() -> cookies = CookieManager.getInstance());
        client = new DriveClient(new DriveClient.Cookies() {
            public String get(String url) { return cookies.getCookie(url); }
            public void set(String url, String value) { cookies.setCookie(url, value); }
        });
        JSONArray history = new JSONArray(instrumentation.getTargetContext().getSharedPreferences("clearplay", 0).getString("history", "[]"));
        List<DriveClient.Item> items = new ArrayList<>();
        for (int i = 0; i < history.length(); i++) {
            var row = history.getJSONObject(i);
            if (provider.equals(row.optString("provider")) && row.optLong("size") > MIB) items.add(DriveClient.Item.from(row));
        }
        int itemIndex = integer(args, "sample", 0, 0, 20);
        status(new JSONObject().put("phase", "account").put("historySamples", items.size()).put("sample", itemIndex));
        assertTrue("No requested video in this provider's recent history", items.size() > itemIndex);
        var item = items.get(itemIndex);
        int total = (int) Math.min(integer(args, "mib", 8, 1, 64) * (long) MIB, item.size);
        int segment = integer(args, "segment", 2, 1, 16) * MIB;
        int duration = integer(args, "seconds", 15, 3, 60) * 1000;
        int rounds = integer(args, "rounds", 2, 1, 4);
        int parallel = integer(args, "workers", 3, 2, 8);
        String selected = args.getString("route", "all");
        List<DriveClient.Source> routes = routes(item);
        assertFalse("No downloadable original route returned", routes.isEmpty());
        boolean explore = selected.equals("explore");
        for (int round = 0; round < rounds; round++) {
            var roundRoutes = new ArrayList<>(routes);
            if (selected.equals("download") && round % 2 == 1) java.util.Collections.reverse(roundRoutes);
            for (var source : roundRoutes) {
                boolean selectedWeb = selected.equals("web-signed") && source.label.startsWith("web-signed");
                boolean alternatives = selected.equals("alternatives") && (source.label.equals("linkswift-oauth") || source.label.startsWith("media-"));
                boolean download = selected.equals("download") && (source.label.equals("filemetas") || source.label.equals("media-filemetas"));
                if (explore ? !(source.label.startsWith("web-signed") || source.label.startsWith("locate-node-")) : !selected.equals("all") && !source.label.equals(selected) && !selectedWeb && !alternatives && !download) continue;
                // Reverse order in alternate rounds to expose warm-cache/order effects.
                int[] order = selected.equals("download") ? new int[]{1} : round % 2 == 0 ? new int[]{0, 1, parallel} : new int[]{parallel, 1, 0};
                for (int workers : order) {
                    JSONObject result = measure(source, total, segment, duration, workers);
                    result.put("phase", "transfer").put("route", source.label).put("round", round + 1).put("sample", itemIndex);
                    status(result);
                    // Some CDN routes reject whole-file GET but accept a bounded Range.
                    if (result.optInt("httpError") == 429 || result.optInt("httpError") == 403 && workers > 0) break;
                    SystemClock.sleep(300);
                }
            }
        }
        if (selected.equals("download") || selected.equals("production-download")) {
            String label = selected.equals("download") ? "media-filemetas" : "production-download";
            var candidate = routes.stream().filter(s -> s.label.equals(label)).findFirst().orElse(null);
            assertNotNull("Video download route unavailable", candidate);
            assertTrue("Full download capped at 256 MiB", item.size > 0 && item.size <= 256L * MIB);
            byte[] hash = fullDownload(candidate, item.size, false, total, null);
            fullDownload(candidate, item.size, true, total, hash);
        }
        cookies.flush();
    }

    @After public void releaseScreen() {
        if (activity != null) InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            activity.setTurnScreenOn(false);
        });
    }

    private List<DriveClient.Source> routes(DriveClient.Item item) throws Exception {
        List<DriveClient.Source> out = new ArrayList<>();
        if (provider.equals("quark")) {
            for (String mode : new String[]{"web", "client"}) {
                long start = SystemClock.elapsedRealtime();
                Map<String, String> headers = new LinkedHashMap<>(client.headers(provider));
                if (mode.equals("client")) headers.put("User-Agent", QUARK_CLIENT);
                String url = DriveClient.query("https://drive.quark.cn/1/clouddrive/file/download", Map.of("pr", "ucpro", "fr", "pc", "uc_param_str", ""));
                try {
                    JSONObject data = json(url, headers, new JSONObject().put("fids", new JSONArray().put(item.id)));
                    JSONArray rows = data.optJSONArray("data");
                    if (rows != null && rows.length() > 0 && !rows.getJSONObject(0).optString("download_url").isBlank()) {
                        Map<String, String> mediaHeaders = new LinkedHashMap<>(client.headers(provider));
                        // Keep the identity used to obtain this signed link and the freshly rotated session.
                        mediaHeaders.put("User-Agent", headers.get("User-Agent"));
                        out.add(new DriveClient.Source(mode, rows.getJSONObject(0).getString("download_url"), provider, mediaHeaders, false, null));
                    }
                    status(new JSONObject().put("phase", "route").put("route", mode).put("code", data.optInt("code", -1)).put("elapsedMs", SystemClock.elapsedRealtime() - start).put("available", out.stream().anyMatch(s -> s.label.equals(mode))));
                } catch (Exception error) { routeError(mode, error); }
            }
        } else {
            if (InstrumentationRegistry.getArguments().getString("route", "all").equals("production-download")) {
                JSONObject fields = client.api("baidu", "/api/gettemplatevariable", Map.of("fields", "[\"bdstoken\"]"), null).getJSONObject("result");
                var source = client.baiduOriginal(item, fields.optString("bdstoken"), client.headers("baidu"));
                if (source != null) out.add(new DriveClient.Source("production-download", source.url, "baidu", source.headers, false, null));
                status(new JSONObject().put("phase", "route").put("route", "production-download").put("available", source != null));
                return out;
            }
            try {
                JSONObject variables = client.api(provider, "/api/gettemplatevariable", Map.of("fields", "[\"bdstoken\"]"), null);
                String token = variables.getJSONObject("result").getString("bdstoken");
                JSONObject meta = client.api(provider, "/api/filemetas", Map.of("target", new JSONArray().put(item.path).toString(), "dlink", 1, "bdstoken", token), null);
                String url = meta.getJSONArray("info").getJSONObject(0).optString("dlink");
                if (!url.isBlank()) {
                    Map<String, String> headers = new LinkedHashMap<>(client.headers(provider));
                    headers.put("User-Agent", "pan.baidu.com");
                    out.add(new DriveClient.Source("filemetas", url, provider, headers, false, null));
                    Map<String, String> alternative = new LinkedHashMap<>(headers);
                    alternative.put("User-Agent", BAIDU_CLIENT);
                    out.add(new DriveClient.Source("filemetas-client", url, provider, alternative, false, null));
                }
            } catch (Exception error) { routeError("filemetas", error); }
            for (String method : new String[]{"file", "stream"}) {
                String url = DriveClient.query("https://pcs.baidu.com/rest/2.0/pcs/" + method, Map.of("method", "download", "app_id", "250528", "path", item.path));
                Map<String, String> headers = new LinkedHashMap<>(client.headers(provider));
                headers.put("User-Agent", BAIDU_CLIENT);
                out.add(new DriveClient.Source("pcs-" + method, url, provider, headers, false, null));
            }
            String selected = InstrumentationRegistry.getArguments().getString("route", "all");
            if (selected.equals("alternatives") || selected.equals("linkswift-oauth")) linkSwiftDownload(item, out);
            if (selected.equals("alternatives") || selected.equals("download")) mediaDownloads(item, out);
            if (selected.equals("explore") || selected.equals("web-signed")) webDownload(item, out);
            if (selected.equals("locate") || selected.equals("explore")) {
                try {
                    JSONObject info = client.api(provider, "/api/gettemplatevariable", Map.of("fields", "[\"uk\"]"), null).getJSONObject("result");
                    String uid = info.optString("uk"), bduss = "";
                    for (String pair : client.headers(provider).get("Cookie").split(";")) {
                        String[] parts = pair.trim().split("=", 2);
                        if (parts.length == 2 && parts[0].equals("BDUSS")) bduss = parts[1];
                    }
                    if (!uid.matches("[1-9][0-9]*") || bduss.isEmpty()) throw new IOException("missing own-session identity");
                    // Public PCS request-signing format, using only this device's own session.
                    // https://github.com/qjfoidnh/BaiduPCS-Go/tree/main/baidupcs/netdisksign
                    String dev = digest("MD5", bduss).toUpperCase(java.util.Locale.ROOT) + "|0";
                    long time = System.currentTimeMillis() / 1000;
                    String rand = digest("SHA-1", digest("SHA-1", bduss) + uid + "ebrcUYiuxaZv2XGu7KIYKxUrqfnOfpDF" + time + dev);
                    Map<String, Object> parameters = new LinkedHashMap<>(Map.of("method", "locatedownload", "app_id", "250528", "path", item.path, "ver", "4.0", "clienttype", "17", "channel", "0", "ant", "1", "check_blue", "1", "es", "1", "esl", "1"));
                    parameters.putAll(Map.of("apn_id", "1_0", "freeisp", "0", "queryfree", "0", "use", "0", "time", time, "rand", rand, "devuid", dev, "cuid", dev));
                    Map<String, String> headers = new LinkedHashMap<>(client.headers(provider)); headers.put("User-Agent", BAIDU_CLIENT);
                    JSONObject data = json(DriveClient.query("https://pcs.baidu.com/rest/2.0/pcs/file", parameters), headers, new JSONObject());
                    JSONArray urls = data.optJSONArray("urls");
                    int nodes = 0;
                    if (urls != null) for (int i = 0; i < urls.length(); i++) {
                        JSONObject row = urls.getJSONObject(i);
                        if (row.optInt("encrypt") == 0 && !row.optString("url").isBlank()) {
                            String url = row.getString("url").replaceFirst("^http://", "https://");
                            if (out.stream().anyMatch(s -> s.url.equals(url))) continue;
                            String label = selected.equals("explore") ? "locate-node-" + nodes : "locate";
                            out.add(new DriveClient.Source(label, url, provider, headers, false, null));
                            if (++nodes >= (selected.equals("explore") ? 3 : 1)) break;
                        }
                    }
                    status(new JSONObject().put("phase", "route").put("route", "locate").put("code", data.optInt("error_code", data.optInt("errno"))).put("available", nodes > 0).put("nodes", nodes));
                } catch (Exception error) { routeError("locate", error); }
            }
        }
        return out;
    }

    private void mediaDownloads(DriveClient.Item item, List<DriveClient.Source> out) throws Exception {
        // Source: OpenListTeam/OpenList drivers/baidu_netdisk/util.go, linkCrack/Video.
        String selected = InstrumentationRegistry.getArguments().getString("route", "all");
        for (String mode : selected.equals("download") ? new String[]{"filemetas"} : new String[]{"filemetas", "mediainfo"}) {
            String label = "media-" + mode;
            try {
                Map<String, Object> parameters = new LinkedHashMap<>();
                if (mode.equals("filemetas")) parameters.putAll(Map.of("target", new JSONArray().put(item.path).toString(), "dlink", 1, "web", 5, "origin", "dlna"));
                else parameters.putAll(Map.of("type", "VideoURL", "path", item.path, "fs_id", item.id, "devuid", "0%1", "clienttype", 1, "channel", "android_15_25010PN30C_bd-netdisk_1523a", "nom3u8", 1, "dlink", 1, "media", 1, "origin", "dlna"));
                JSONObject response = client.api("baidu", "/api/" + mode, parameters, null);
                JSONObject info = mode.equals("filemetas") ? response.getJSONArray("info").optJSONObject(0) : response.optJSONObject("info");
                String url = info == null ? "" : info.optString("dlink");
                if (!url.isBlank()) {
                    Map<String, String> headers = new LinkedHashMap<>(client.headers("baidu")); headers.put("User-Agent", "netdisk");
                    out.add(new DriveClient.Source(label, url, "baidu", headers, false, null));
                }
                status(new JSONObject().put("phase", "route").put("route", label).put("available", !url.isBlank()).put("code", response.optInt("errno", -1)));
            } catch (Exception error) { routeError(label, error); }
        }
    }

    private void linkSwiftDownload(DriveClient.Item item, List<DriveClient.Source> out) throws Exception {
        // Public app identifier from LinkSwift; GET only checks previously granted consent.
        // A new grant must be confirmed by the user on Baidu's page, never by this test.
        String target = DriveClient.query("https://openapi.baidu.com/oauth/2.0/authorize", Map.of("response_type", "token", "scope", "basic,netdisk", "client_id", "omiOnr2tYnN9vSyDErcVFWpPU2mZA7YO", "redirect_uri", "oob", "confirm_login", 0));
        String token = "";
        try {
            for (int hop = 0; hop < 6; hop++) {
                java.net.URI uri = DriveClient.Net.allowed(target, "baidu");
                if (uri.getRawFragment() != null) {
                    token = android.net.Uri.parse("https://localhost/?" + uri.getRawFragment()).getQueryParameter("access_token");
                    if (token != null && !token.isBlank()) break;
                }
                HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
                try {
                    connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
                    connection.setRequestProperty("User-Agent", DriveClient.UA);
                    String ownCookie = cookies.getCookie(target); if (ownCookie != null) connection.setRequestProperty("Cookie", ownCookie);
                    int http = connection.getResponseCode();
                    String location = connection.getHeaderField("Location");
                    if (http >= 300 && http < 400 && location != null) { target = uri.resolve(location).toString(); continue; }
                    String page = DriveClient.Net.text(http >= 400 ? connection.getErrorStream() : connection.getInputStream());
                    status(new JSONObject().put("phase", "route").put("route", "linkswift-oauth").put("available", false).put("http", http)
                            .put("reason", page.contains("grant_permissions") ? "consent-required" : "login-or-consent-required"));
                    return;
                } finally { connection.disconnect(); }
            }
            if (token == null || token.isBlank()) { status(new JSONObject().put("phase", "route").put("route", "linkswift-oauth").put("available", false).put("reason", "no-existing-authorization")); return; }
            Map<String, String> headers = Map.of("User-Agent", "pan.baidu.com");
            String endpoint = DriveClient.query("https://pan.baidu.com/rest/2.0/xpan/multimedia", Map.of("method", "filemetas", "dlink", 1, "fsids", "[" + item.id + "]", "access_token", token));
            JSONObject response = json(endpoint, headers, null);
            JSONArray list = response.optJSONArray("list");
            String url = list == null || list.length() == 0 ? "" : list.getJSONObject(0).optString("dlink");
            if (!url.isBlank()) out.add(new DriveClient.Source("linkswift-oauth", DriveClient.query(url, Map.of("access_token", token)), "baidu", headers, false, null));
            status(new JSONObject().put("phase", "route").put("route", "linkswift-oauth").put("code", response.optInt("errno", -1)).put("available", !url.isBlank()));
        } catch (Exception error) { routeError("linkswift-oauth", error); }
    }

    private void webDownload(DriveClient.Item item, List<DriveClient.Source> out) throws Exception {
        try {
            assertEquals("RC4 signature vector", "u/MW6NlArwrT", webSign("Key", "Plaintext"));
            JSONObject fields = client.api("baidu", "/api/gettemplatevariable", Map.of("fields", "[\"sign1\",\"sign3\",\"timestamp\",\"bdstoken\"]", "_", System.currentTimeMillis()), null).getJSONObject("result");
            if (fields.optString("sign1").isBlank() || fields.optString("sign3").isBlank()) {
                var home = DriveClient.Net.open("https://pan.baidu.com/disk/home?_=" + System.currentTimeMillis(), "baidu", client.headers("baidu"), null, null);
                try {
                    String html = DriveClient.Net.text(home.getInputStream());
                    for (String name : new String[]{"sign1", "sign3", "timestamp"}) {
                        var match = java.util.regex.Pattern.compile("\"" + name + "\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\"|[0-9]+)").matcher(html);
                        if (match.find()) fields.put(name, new JSONObject("{\"value\":" + match.group(1) + "}").get("value"));
                    }
                } finally { home.disconnect(); }
            }
            if (fields.optString("sign1").isBlank() || fields.optString("sign3").isBlank() || fields.optString("timestamp").isBlank()) {
                status(new JSONObject().put("phase", "route").put("route", "web-signed").put("available", false).put("reason", "signature-fields-not-returned")); return;
            }
            status(new JSONObject().put("phase", "signature").put("route", "web-signed").put("ageSeconds", System.currentTimeMillis() / 1000 - fields.optLong("timestamp")));
            String endpoint = DriveClient.query("https://pan.baidu.com/api/download", Map.of("web", "1", "clienttype", "0", "channel", "chunlei", "bdstoken", fields.optString("bdstoken"), "_", System.currentTimeMillis()));
            Map<String, String> form = Map.of("sign", webSign(fields.getString("sign3"), fields.getString("sign1")), "timestamp", fields.optString("timestamp"), "fidlist", "[" + item.id + "]", "type", "dlink");
            HttpURLConnection connection = (HttpURLConnection) DriveClient.Net.allowed(endpoint, "baidu").toURL().openConnection();
            JSONObject data;
            try {
                connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
                client.headers("baidu").forEach(connection::setRequestProperty);
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                byte[] body = DriveClient.query("", form).substring(1).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                try (var output = connection.getOutputStream()) { output.write(body); }
                data = new JSONObject(DriveClient.Net.text(connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream()));
            } finally { connection.disconnect(); }
            JSONArray links = data.optJSONArray("dlink");
            if (data.optInt("errno", -1) == 0 && links != null && links.length() > 0) {
                String url = links.getJSONObject(0).optString("dlink");
                if (!url.isBlank()) {
                    Map<String, String> headers = new LinkedHashMap<>(client.headers("baidu")); headers.put("User-Agent", "pan.baidu.com");
                    out.add(new DriveClient.Source("web-signed", url, "baidu", headers, false, null));
                    Map<String, String> clientHeaders = new LinkedHashMap<>(headers); clientHeaders.put("User-Agent", BAIDU_CLIENT);
                    out.add(new DriveClient.Source("web-signed-client", url, "baidu", clientHeaders, false, null));
                }
            }
            status(new JSONObject().put("phase", "route").put("route", "web-signed").put("code", data.optInt("errno", -1)).put("available", out.stream().anyMatch(s -> s.label.equals("web-signed"))));
        } catch (Exception error) { routeError("web-signed", error); }
    }

    // RC4 of the server's current sign1 under sign3, encoded as Base64; never persisted.
    private static String webSign(String key, String value) {
        if (key.isEmpty()) throw new IllegalArgumentException("Missing signing key");
        int[] state = new int[256]; for (int i = 0; i < 256; i++) state[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + state[i] + key.charAt(i % key.length())) & 255;
            int swap = state[i]; state[i] = state[j]; state[j] = swap;
        }
        byte[] encoded = new byte[value.length()]; int i = 0; j = 0;
        for (int n = 0; n < value.length(); n++) {
            i = (i + 1) & 255; j = (j + state[i]) & 255;
            int swap = state[i]; state[i] = state[j]; state[j] = swap;
            encoded[n] = (byte) (value.charAt(n) ^ state[(state[i] + state[j]) & 255]);
        }
        return java.util.Base64.getEncoder().encodeToString(encoded);
    }

    private JSONObject json(String url, Map<String, String> headers, JSONObject body) throws Exception {
        var connection = DriveClient.Net.open(url, provider, headers, body, null);
        try {
            for (var entry : connection.getHeaderFields().entrySet()) if ("set-cookie".equalsIgnoreCase(entry.getKey())) {
                for (String value : entry.getValue()) {
                    var latch = new java.util.concurrent.CountDownLatch(1);
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> cookies.setCookie(url, value, accepted -> latch.countDown()));
                    assertTrue("Cookie update timed out", latch.await(5, TimeUnit.SECONDS));
                }
            }
            return new JSONObject(DriveClient.Net.text(connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream()));
        } finally { connection.disconnect(); }
    }

    private JSONObject measure(DriveClient.Source source, int total, int segment, int duration, int concurrency) throws Exception {
        long started = SystemClock.elapsedRealtime(), deadline = started + duration;
        byte[] data = new byte[total];
        int chunks = concurrency == 0 ? 1 : (total + segment - 1) / segment;
        int[] counts = new int[chunks];
        AtomicInteger next = new AtomicInteger(), errors = new AtomicInteger(), httpError = new AtomicInteger();
        AtomicBoolean rejected = new AtomicBoolean();
        AtomicReference<String> serverCode = new AtomicReference<>(""), errorType = new AtomicReference<>("");
        AtomicLong bytes = new AtomicLong(), firstByte = new AtomicLong();
        var active = ConcurrentHashMap.<HttpURLConnection>newKeySet();
        Map<String, String> requestHeaders = new LinkedHashMap<>(source.headers);
        if (provider.equals("quark")) requestHeaders.put("Cookie", client.headers(provider).get("Cookie"));
        var pool = Executors.newFixedThreadPool(Math.max(1, concurrency));
        for (int worker = 0; worker < Math.max(1, concurrency); worker++) pool.submit(() -> {
            int index;
            while (!rejected.get() && (index = next.getAndIncrement()) < chunks && SystemClock.elapsedRealtime() < deadline) {
                int begin = concurrency == 0 ? 0 : index * segment, end = concurrency == 0 ? total : Math.min(total, begin + segment);
                HttpURLConnection connection = null;
                try {
                    connection = DriveClient.Net.open(source.url, provider, requestHeaders, null, concurrency == 0 ? null : "bytes=" + begin + "-" + (end - 1));
                    active.add(connection); connection.setReadTimeout(2000);
                    int http = connection.getResponseCode();
                    if (http >= 400) {
                        httpError.set(http);
                        if (http == 403 || http == 429) rejected.set(true);
                        serverCode.compareAndSet("", safeServerCode(connection));
                        throw new IOException("http");
                    }
                    String contentRange = connection.getHeaderField("Content-Range");
                    if (concurrency > 0 && (http != 206 || contentRange == null || !contentRange.startsWith("bytes " + begin + "-" + (end - 1) + "/"))) throw new IOException("range mismatch");
                    String type = connection.getContentType();
                    if (type != null && (type.contains("json") || type.contains("html"))) throw new IOException("not media");
                    try (var input = connection.getInputStream()) {
                        int position = begin, n;
                        while (position < end && SystemClock.elapsedRealtime() < deadline && (n = input.read(data, position, Math.min(65536, end - position))) != -1) {
                            firstByte.compareAndSet(0, SystemClock.elapsedRealtime() - started);
                            position += n; counts[index] += n; bytes.addAndGet(n);
                        }
                    }
                } catch (Exception error) { errors.incrementAndGet(); errorType.compareAndSet("", error.getClass().getSimpleName()); }
                finally { if (connection != null) { active.remove(connection); connection.disconnect(); } }
            }
        });
        pool.shutdown();
        if (!pool.awaitTermination(duration + 21000L, TimeUnit.MILLISECONDS)) {
            active.forEach(HttpURLConnection::disconnect); pool.shutdownNow();
            assertTrue("Download worker failed to stop", pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        long elapsed = Math.max(1, SystemClock.elapsedRealtime() - started);
        boolean complete = bytes.get() == total && errors.get() == 0;
        String key = "sample-" + total;
        boolean compared = complete && reference.containsKey(key);
        boolean matches = compared && MessageDigest.isEqual(reference.get(key), MessageDigest.getInstance("SHA-256").digest(data));
        if (complete && !reference.containsKey(key)) reference.put(key, MessageDigest.getInstance("SHA-256").digest(data));
        JSONObject result = new JSONObject().put("mode", concurrency == 0 ? "whole" : "range").put("workers", Math.min(chunks, Math.max(1, concurrency)))
                .put("segmentBytes", concurrency == 0 ? 0 : segment).put("requestedBytes", total).put("bytes", bytes.get()).put("elapsedMs", elapsed)
                .put("firstByteMs", firstByte.get()).put("bytesPerSecond", bytes.get() * 1000 / elapsed).put("complete", complete)
                .put("errors", errors.get()).put("httpError", httpError.get()).put("serverCode", serverCode.get()).put("errorType", errorType.get())
                .put("hashCompared", compared).put("hashMatches", matches);
        Arrays.fill(data, (byte) 0);
        return result;
    }

    // Export only an error identifier, never response messages, signed URLs or request IDs.
    private static String safeServerCode(HttpURLConnection connection) {
        try (var input = connection.getErrorStream()) {
            if (input == null) return "";
            byte[] body = new byte[4096]; int length = input.read(body);
            if (length < 1) return "";
            return parseServerCode(new String(body, 0, length, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException error) { return ""; }
    }

    private static String parseServerCode(String value) {
        var jsonCode = java.util.regex.Pattern.compile("\"(?:error_code|errno|code)\"\\s*:\\s*\"?(-?[0-9]{1,8})(?:\"|\\s|,|\\})").matcher(value);
        if (jsonCode.find()) return jsonCode.group(1);
        var xmlCode = java.util.regex.Pattern.compile("<Code>([A-Za-z_]{1,40})</Code>").matcher(value);
        return xmlCode.find() ? xmlCode.group(1) : "";
    }

    private byte[] fullDownload(DriveClient.Source source, long expected, boolean resume, int prefixLength, byte[] referenceHash) throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var file = java.io.File.createTempFile("clearplay-download-", ".partial", context.getCacheDir());
        long started = SystemClock.elapsedRealtime(), deadline = started + 120000, received = 0, lastStatus = started;
        var hash = MessageDigest.getInstance("SHA-256");
        var prefix = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        long interruptAt = Math.min(4L * MIB, expected / 4);
        boolean interrupted = false;
        status(new JSONObject().put("phase", "download-start").put("resumed", resume).put("expectedBytes", expected));
        try {
            while (received < expected) {
                long offset = received, stop = resume && !interrupted ? interruptAt : expected;
                HttpURLConnection connection = DriveClient.Net.open(source.url, provider, source.headers, null, offset == 0 ? null : "bytes=" + offset + "-", 10000);
                try {
                    int http = connection.getResponseCode();
                    assertEquals("Download response", offset == 0 ? 200 : 206, http);
                    if (offset == 0) assertEquals("Original file length", expected, connection.getContentLengthLong());
                    else assertEquals("Resume range", "bytes " + offset + "-" + (expected - 1) + "/" + expected, connection.getHeaderField("Content-Range"));
                    try (var input = connection.getInputStream(); var output = new java.io.FileOutputStream(file, offset > 0)) {
                        while (received < stop) {
                            assertTrue("Full download deadline exceeded", SystemClock.elapsedRealtime() < deadline);
                            int n = input.read(buffer, 0, (int) Math.min(buffer.length, stop - received));
                            assertTrue("Download truncated", n > 0);
                            output.write(buffer, 0, n); hash.update(buffer, 0, n);
                            if (received < prefixLength) prefix.update(buffer, 0, (int) Math.min(n, prefixLength - received));
                            received += n;
                            long now = SystemClock.elapsedRealtime();
                            if (now - lastStatus >= 10000) {
                                status(new JSONObject().put("phase", "download-progress").put("resumed", resume).put("bytes", received).put("elapsedMs", now - started)); lastStatus = now;
                            }
                        }
                    }
                } finally { connection.disconnect(); }
                if (resume && !interrupted) {
                    assertEquals(interruptAt, file.length()); interrupted = true;
                    status(new JSONObject().put("phase", "download-interrupted").put("bytes", received));
                }
            }
            assertEquals("Saved download length", expected, file.length());
            // Re-read the saved file: validate disk output, not only the received buffers.
            var savedHash = MessageDigest.getInstance("SHA-256");
            try (var input = new java.io.FileInputStream(file)) { int n; while ((n = input.read(buffer)) >= 0) savedHash.update(buffer, 0, n); }
            byte[] digest = hash.digest();
            assertTrue("Saved file hash differs", MessageDigest.isEqual(digest, savedHash.digest()));
            boolean compared = reference.containsKey("sample-" + prefixLength);
            boolean prefixMatches = compared && MessageDigest.isEqual(reference.get("sample-" + prefixLength), prefix.digest());
            if (compared) assertTrue("Full file prefix differs from transfer baseline", prefixMatches);
            if (referenceHash != null) assertTrue("Resumed file hash differs from full download", MessageDigest.isEqual(referenceHash, digest));
            long elapsed = Math.max(1, SystemClock.elapsedRealtime() - started);
            status(new JSONObject().put("phase", "download-complete").put("resumed", resume).put("bytes", received).put("elapsedMs", elapsed)
                    .put("bytesPerSecond", received * 1000 / elapsed).put("diskHashMatches", true).put("fullHashCompared", referenceHash != null)
                    .put("fullHashMatches", referenceHash != null).put("prefixCompared", compared).put("prefixMatches", prefixMatches));
            return digest;
        } finally {
            Arrays.fill(buffer, (byte) 0);
            assertTrue("Temporary downloaded file cleanup failed", !file.exists() || file.delete());
        }
    }

    private static int integer(Bundle args, String name, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, Integer.parseInt(args.getString(name, String.valueOf(fallback)))));
    }
    private static String digest(String algorithm, String value) throws Exception {
        byte[] hash = MessageDigest.getInstance(algorithm).digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
    private void routeError(String route, Exception error) throws Exception {
        status(new JSONObject().put("phase", "route").put("route", route).put("available", false).put("errorType", error.getClass().getSimpleName()));
    }
    private void status(JSONObject result) throws Exception {
        result.put("provider", provider);
        Bundle status = new Bundle(); status.putString("stream", "CLEARPLAY_THROUGHPUT=" + result + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
    }
}
