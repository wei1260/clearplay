package local.clearplay.android;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class CloudTest {
    @Test public void downloadFetchesOriginalWithoutRequestingPlaybackStreams() throws Exception {
        var client = new DriveClient(new DriveClient.Cookies() {
            public String get(String url) { return "BDUSS=test"; }
            public void set(String url, String value) { }
        }) {
            @Override JSONObject api(String provider, String path, Map<String, ?> params, JSONObject body) throws Exception {
                if (path.equals("/api/gettemplatevariable")) return new JSONObject("{\"result\":{\"bdstoken\":\"test\"}}");
                if (provider.equals("baidu")) {
                    assertEquals("/api/filemetas", path); assertEquals("dlna", params.get("origin"));
                    return new JSONObject("{\"info\":[{\"dlink\":\"https://d.pcs.baidu.com/video.mp4\"}]}");
                }
                assertEquals("/file/download", path);
                assertEquals("q123", body.getJSONArray("fids").getString(0));
                return new JSONObject("{\"data\":[{\"download_url\":\"https://drive.quark.cn/video.mp4\"}]}");
            }
            @Override boolean probeOriginal(Source source) { return true; }
        };
        var baidu = client.download(new DriveClient.Item("baidu","123","a.mp4","/a.mp4",false,1000));
        assertFalse(baidu.hls); assertEquals("netdisk", baidu.headers.get("User-Agent"));
        var quark = client.download(new DriveClient.Item("quark","q123","b.mp4","q123",false,2000));
        assertFalse(quark.hls); assertTrue(quark.headers.get("User-Agent").contains("quark-cloud-drive/"));
        assertEquals(2000, quark.fileSize);
    }
    @Test public void savedDownloadNameCannotEscapeDownloadDirectory() {
        String name = CloudDownloads.savedName("../../课程\\片段\n.mp4");
        assertFalse(name.contains("/")); assertFalse(name.contains("\\")); assertFalse(name.contains("\n"));
        assertTrue(name.endsWith(".mp4")); assertTrue(name.contains("课程"));
        assertTrue(CloudDownloads.savedName("..").startsWith("视频"));
    }
    @Test public void baiduVideoOriginalNeverFallsBackToNormalLink() throws Exception {
        var accept = new java.util.concurrent.atomic.AtomicBoolean(true);
        var calls = new java.util.ArrayList<String>();
        var client = new DriveClient(new DriveClient.Cookies() {
            public String get(String url) { return "BDUSS=test"; }
            public void set(String url, String value) { }
        }) {
            @Override JSONObject api(String provider, String path, Map<String, ?> params, JSONObject body) throws Exception {
                calls.add(params.get("target").toString());
                assertTrue(params.containsKey("origin"));
                assertEquals(5, params.get("web")); assertEquals("dlna", params.get("origin"));
                return new JSONObject("{\"info\":[{\"dlink\":\"http://d.pcs.baidu.com/video.mp4\"}]}");
            }
            @Override boolean probeOriginal(Source source) {
                assertEquals("https://d.pcs.baidu.com/video.mp4", source.url);
                assertEquals("netdisk", source.headers.get("User-Agent"));
                return accept.get();
            }
        };
        var headers = client.headers("baidu");
        var first = client.baiduOriginal(new DriveClient.Item("baidu","123","a.mp4","/one/a.mp4",false,1000),"token",headers);
        assertEquals("netdisk", first.headers.get("User-Agent")); assertEquals(1000,first.fileSize);
        accept.set(false);
        var second = client.baiduOriginal(new DriveClient.Item("baidu","456","b.mp4","/two/b.mp4",false,2000),"token",headers);
        assertNull(second);
        assertEquals(java.util.List.of("[\"/one/a.mp4\"]","[\"/two/b.mp4\"]"),calls);
    }
    @Test public void quarkOriginalKeepsDownloadIdentityAndFreshSession() throws Exception {
        var session = new java.util.concurrent.atomic.AtomicReference<>("session=before");
        var client = new DriveClient(new DriveClient.Cookies() {
            public String get(String url) { return session.get(); }
            public void set(String url, String value) { session.set(value); }
        }) {
            @Override JSONObject api(String provider, String path, Map<String, ?> params, JSONObject body) throws Exception {
                if (path.equals("/file/download")) {
                    assertTrue(apiHeaders(provider, path).get("User-Agent").contains("quark-cloud-drive/"));
                    session.set("session=after");
                    return new JSONObject("{\"data\":[{\"download_url\":\"https://drive.quark.cn/video.mp4\"}]}");
                }
                assertEquals(UA, apiHeaders(provider, path).get("User-Agent"));
                return new JSONObject("{\"data\":{\"video_list\":[]}}");
            }
        };
        var sources = client.sources(new DriveClient.Item("quark", "test", "test.mp4", "test", false, 12345), seconds -> {});
        assertEquals(1, sources.size());
        assertEquals("session=after", sources.get(0).headers.get("Cookie"));
        assertTrue(sources.get(0).headers.get("User-Agent").contains("quark-cloud-drive/"));
        assertEquals(12345, sources.get(0).fileSize);
        assertEquals(DriveClient.UA, client.apiHeaders("baidu", "/api/filemetas").get("User-Agent"));
    }
    @Test public void originalAudioDoesNotCreateAnInvalidHlsMasterForMp4() throws Exception {
        var registry = new CloudDataSource.Registry();
        var source = new DriveClient.Source("原画", "https://d.pcs.baidu.com/file.mp4", "baidu", Map.of(), false, null);
        source.audio = new DriveClient.Source("音频", "https://pan.baidu.com/audio.m3u8", "baidu", Map.of(), true, "#EXTM3U\n");
        assertSame(source, registry.get(registry.add(source)));
        assertFalse(source.hls); assertNull(source.manifest);
    }
    @Test public void availableBaiduVideoAndAudioStartBeforeSlowOriginalAndHighQuality() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var delivered = new java.util.concurrent.CountDownLatch(1);
        var client = new DriveClient(new DriveClient.Cookies() {
            public String get(String url) { return "session=test"; }
            public void set(String url, String value) { }
        }) {
            @Override boolean probeOriginal(Source source) { return true; }
            @Override JSONObject api(String provider, String path, Map<String, ?> params, JSONObject body) throws Exception {
                if (path.equals("/api/gettemplatevariable")) return new JSONObject("{\"result\":{\"bdstoken\":\"test\"}}");
                assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                return new JSONObject("{\"info\":[{\"dlink\":\"https://d.pcs.baidu.com/original.mp4\"}]}");
            }
            @Override Source baiduStream(String path, String token, String type, String label, Map<String, String> headers, java.util.function.IntConsumer preparing) {
                if (label.equals("1080P") || label.equals("2K")) {
                    try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); } catch (InterruptedException error) { throw new AssertionError(error); }
                    return null; // 当前账号不可用，不能虚构高画质。
                }
                return new Source(label, "https://pan.baidu.com/" + type, "baidu", headers, true, "#EXTM3U\n");
            }
        };
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var future = executor.submit(() -> client.sources(new DriveClient.Item("baidu", "123", "test.mp4", "/test.mp4", false, 1), seconds -> { }, ready -> {
            assertTrue(ready.stream().allMatch(s -> s.audio != null));
            if (ready.stream().anyMatch(s -> s.label.equals("720P"))) delivered.countDown();
        }));
        try {
            assertTrue("Slow original must not gate video and audio", delivered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(future.isDone()); release.countDown();
            var sources = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(3, sources.size()); assertTrue(sources.stream().noneMatch(s -> s.label.equals("2K")));
            assertTrue(sources.stream().filter(s -> s.label.equals("原画")).allMatch(s -> s.audio != null));
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test public void quarkWebPlaybackDoesNotWaitForOriginalDownloadAndKeepsReal2k() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var delivered = new java.util.concurrent.CountDownLatch(1);
        var client = new DriveClient(new DriveClient.Cookies() {
            public String get(String url) { return "session=test"; }
            public void set(String url, String value) { }
        }) {
            @Override JSONObject api(String provider, String path, Map<String, ?> params, JSONObject body) throws Exception {
                if (path.equals("/file/download")) {
                    assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); throw new IOException("denied");
                }
                assertTrue(body.getString("resolutions").contains("2k"));
                return new JSONObject("{\"data\":{\"video_list\":[{\"resolution\":\"2k\",\"video_info\":{\"url\":\"https://dl.quark.cn/2k.m3u8\",\"height\":1440,\"format\":\"m3u8\"}}]}}");
            }
        };
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var future = executor.submit(() -> client.sources(new DriveClient.Item("quark", "test", "test.mp4", "test", false, 1), seconds -> { }, ready -> {
            assertEquals("2K · 1440P", ready.get(0).label); delivered.countDown();
        }));
        try {
            assertTrue(delivered.await(2, java.util.concurrent.TimeUnit.SECONDS)); assertFalse(future.isDone()); release.countDown();
            assertEquals(1, future.get(5, java.util.concurrent.TimeUnit.SECONDS).size());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test public void providerHlsExpressionsAreEncodedWithoutChangingExistingSignatures() throws Exception {
        var registry = new CloudDataSource.Registry();
        var source = new DriveClient.Source("540P", "https://video-play.drive.quark.cn/dir/master.m3u8", "quark", Map.of("Cookie", "Video-Auth=test"), true, null);
        String manifest = registry.rewrite("#EXTM3U\nsegment.ts?auth_key=test&x-oss-process=if_status_eq_404{hls/ts,from_a%2Fb}&token=a%2Bb\n", source.url, source);
        String child = manifest.lines().filter(s -> s.startsWith("clearplay:")).findFirst().orElseThrow();
        String remote = registry.get(child).url;
        assertTrue(remote.contains("if_status_eq_404%7Bhls/ts,from_a%2Fb%7D"));
        assertTrue(remote.contains("token=a%2Bb"));
        assertFalse(remote.contains("%252F"));
        assertEquals("Video-Auth=test", registry.get(child).headers.get("Cookie"));
        assertEquals("https://pan.baidu.com/a%20b?q=%E4%B8%AD%E6%96%87", DriveClient.Net.allowed("https://pan.baidu.com/a b?q=中文", "baidu").toString());
    }
    @Test public void quarkMediaCarriesFreshVideoAuthorizationFromPlaybackResponse() throws Exception {
        var cookie = new java.util.concurrent.atomic.AtomicReference<>("session=before");
        var client = new DriveClient(new DriveClient.Cookies() {
            public String get(String url) { return cookie.get(); }
            public void set(String url, String value) { }
        }) {
            @Override JSONObject api(String provider, String path, Map<String, ?> params, JSONObject body) throws Exception {
                if (path.equals("/file/download")) throw new IOException("网盘请求失败（23018）");
                cookie.set("session=after; Video-Auth=test-only");
                return new JSONObject("{\"data\":{\"video_list\":[{\"resolution\":\"high\",\"video_info\":{\"url\":\"https://video-play.drive.quark.cn/stream.m3u8\",\"format\":\"m3u8\",\"height\":720,\"success\":true,\"finish\":true}}]}}");
            }
        };
        var result = client.sources(new DriveClient.Item("quark", "test-video", "test.mp4", "test-video", false, 1), seconds -> { });
        assertEquals("session=after; Video-Auth=test-only", result.get(0).headers.get("Cookie"));
        assertTrue(result.get(0).hls);
    }
    @Test public void quarkWebPlaybackWorksWhenOriginalDownloadIsDenied() throws Exception {
        var client = new DriveClient(new DriveClient.Cookies() {
            public String get(String url) { return "session=test"; }
            public void set(String url, String value) { }
        }) {
            @Override JSONObject api(String provider, String path, Map<String, ?> params, JSONObject body) throws Exception {
                if (path.equals("/file/download")) throw new IOException("网盘请求失败（23018）");
                assertEquals("/file/v2/play", path);
                assertEquals("fmp4", body.getString("supports"));
                assertEquals("test-video", body.getString("fid"));
                return new JSONObject("{\"data\":{\"video_list\":[{\"resolution\":\"high\",\"video_info\":{\"url\":\"https://dl.quark.cn/video.mp4\",\"format\":\"fmp4\",\"height\":720,\"success\":true,\"finish\":true}}]}}");
            }
        };
        var result = client.sources(new DriveClient.Item("quark", "test-video", "test.mp4", "test-video", false, 1), seconds -> { });
        assertEquals(1, result.size());
        assertEquals("720P", result.get(0).label);
        assertFalse(result.get(0).hls);
        assertEquals("https://pan.quark.cn/", result.get(0).headers.get("Referer"));
    }
    @Test public void quarkPlaybackFailureRetainsBothProviderErrorCodes() {
        var client = new DriveClient(new DriveClient.Cookies() {
            public String get(String url) { return "session=test"; }
            public void set(String url, String value) { }
        }) {
            @Override JSONObject api(String provider, String path, Map<String, ?> params, JSONObject body) throws IOException {
                throw new IOException("网盘请求失败（" + (path.equals("/file/download") ? 23018 : 14018) + "）");
            }
        };
        IOException error = assertThrows(IOException.class, () -> client.sources(new DriveClient.Item("quark", "test-video", "test.mp4", "test-video", false, 1), seconds -> { }));
        assertTrue(error.getMessage().contains("23018")); assertTrue(error.getMessage().contains("14018"));
    }
    @Test public void mediaHostsRejectLocalInsecureAndForgedAddresses() throws Exception {
        assertEquals("d.pcs.baidu.com", DriveClient.Net.allowed("https://d.pcs.baidu.com/video", "baidu").getHost());
        for (String url : new String[]{"http://pan.baidu.com/video", "https://127.0.0.1/video", "https://baidu.com.evil.test/video", "https://pan.baidu.com:8080/video", "https://user:pass@pan.baidu.com/video"}) {
            assertThrows(IOException.class, () -> DriveClient.Net.allowed(url, "baidu"));
        }
        assertThrows(IOException.class, () -> DriveClient.Net.allowed("https://pan.baidu.com/video", "quark"));
    }
    @Test public void redirectHeadersKeepCookiesOnlyOnTheirProvider() throws Exception {
        var headers = Map.of("Cookie", "session=test", "User-Agent", "agent", "Referer", "https://pan.quark.cn/");
        assertFalse(DriveClient.Net.safeHeaders("https://bucket.aliyuncs.com/clip", "quark", headers).containsKey("Cookie"));
        assertEquals("session=test", DriveClient.Net.safeHeaders("https://drive.quark.cn/clip", "quark", headers).get("Cookie"));
        assertFalse(DriveClient.Net.safeHeaders("https://bucket.baidubce.com/clip", "baidu", headers).containsKey("Cookie"));
        assertTrue(headers.containsKey("Cookie"));
    }
    @Test public void fileIdAndChinesePathsKeepTheirOriginalValues() throws Exception {
        var file = new JSONObject("{\"provider\":\"baidu\",\"id\":9223372036854775807,\"name\":\"视频.mp4\",\"path\":\"/新目录/视频.mp4\"}");
        var item = DriveClient.Item.from(file);
        assertEquals("9223372036854775807", item.id);
        String url = DriveClient.query("https://pan.baidu.com/api/list", Map.of("dir", item.path));
        assertEquals(item.path, URLDecoder.decode(url.substring(url.indexOf('=') + 1), "UTF-8"));
        assertEquals(item.key(), DriveClient.Item.from(item.json()).key());
    }
    @Test public void audioMasterUsesOpaqueUrlsAndSeparateAuthenticatedManifests() throws Exception {
        var registry = new CloudDataSource.Registry();
        var headers = Map.of("Cookie", "session=test");
        var source = new DriveClient.Source("720P", "https://pan.baidu.com/api/streaming?private=video", "baidu", headers, true, "#EXTM3U\nhttps://d.pcs.baidu.com/video.ts?private=signature\n");
        source.audio = new DriveClient.Source("音频", "https://pan.baidu.com/api/streaming?private=audio", "baidu", headers, true, "#EXTM3U\nhttps://d.pcs.baidu.com/audio.ts?private=signature\n");
        String entry = registry.add(source);
        var master = registry.get(entry);
        assertTrue(master.manifest.contains("#EXT-X-MEDIA:TYPE=AUDIO"));
        assertFalse(master.manifest.contains("private"));
        var matcher = Pattern.compile("clearplay://media/[a-f0-9-]+").matcher(master.manifest);
        int children = 0;
        while (matcher.find()) {
            var child = registry.get(matcher.group());
            String manifest = registry.rewrite(child.manifest, child.url, child);
            assertFalse(manifest.contains("private"));
            assertFalse(manifest.contains("https://"));
            assertEquals("session=test", child.headers.get("Cookie"));
            assertNull(child.audio); children++;
        }
        assertEquals(2, children);
        registry.clear();
        assertThrows(IOException.class, () -> registry.get(entry));
    }
    @Test public void manifestKeyMapAndRelativeSegmentsAreValidated() throws Exception {
        var registry = new CloudDataSource.Registry();
        var source = new DriveClient.Source("720P", "https://dl.quark.cn/folder/main.m3u8", "quark", Map.of(), true, null);
        String input = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXT-X-MAP:URI=\"init.mp4\"\nchild.m3u8\nsegment.ts\n";
        String changed = registry.rewrite(input, source.url, source);
        assertEquals(4, changed.split("clearplay://media/", -1).length - 1);
        assertThrows(IOException.class, () -> registry.rewrite("#EXTM3U\nhttps://127.0.0.1/private\n", source.url, source));
    }
}
