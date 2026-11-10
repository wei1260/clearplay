package local.clearplay.android;

import android.os.Bundle;
import android.webkit.CookieManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayDeque;
import java.util.Map;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Explicit account diagnosis only; never included in the release app. */
@RunWith(AndroidJUnit4.class)
public class QuarkAccountTest {
    @Test public void inspectOwnAccountPlaybackResponses() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        assumeTrue("Own-account checks must be explicitly enabled", "true".equals(InstrumentationRegistry.getArguments().getString("clearplayAccountCheck")));
        instrumentation.runOnMainSync(CookieManager::getInstance);
        CookieManager cookies = CookieManager.getInstance();
        DriveClient client = new DriveClient(new DriveClient.Cookies() {
            public String get(String url) { return cookies.getCookie(url); }
            public void set(String url, String value) { cookies.setCookie(url, value); }
        });
        JSONObject report = new JSONObject();
        String cookie = cookies.getCookie("https://drive.quark.cn/");
        report.put("hasSession", cookie != null && !cookie.isEmpty());
        JSONArray cookieNames = new JSONArray();
        if (cookie != null) for (String pair : cookie.split(";")) cookieNames.put(pair.split("=", 2)[0].trim());
        report.put("cookieNames", cookieNames);
        JSONObject root = client.api("quark", "/file/sort", Map.of("pdir_fid", "0", "_page", 1, "_size", 100, "_sort", "file_type:asc,file_name:asc"), null);
        JSONArray raw = root.getJSONObject("data").optJSONArray("list"), flags = new JSONArray();
        report.put("rootCount", raw == null ? 0 : raw.length());
        if (raw != null) for (int i = 0; i < Math.min(5, raw.length()); i++) {
            JSONObject row = raw.getJSONObject(i); Object file = row.opt("file");
            flags.put(new JSONObject().put("file", file).put("fileValueType", file == null ? "null" : file.getClass().getSimpleName()).put("dir", row.opt("dir")).put("category", row.optInt("category")));
        }
        report.put("rootFlags", flags);
        ArrayDeque<String> folders = new ArrayDeque<>(); folders.add("0");
        DriveClient.Item video = null;
        int scanned = 0;
        for (; scanned < 80 && !folders.isEmpty() && video == null; scanned++) {
            var listing = client.list("quark", folders.removeFirst(), 1);
            for (int i = listing.items.size() - 1; i >= 0; i--) {
                DriveClient.Item item = listing.items.get(i);
                if (item.directory) folders.addFirst(item.path);
                else if (video == null) video = item;
            }
        }
        report.put("scannedFolders", scanned);
        report.put("foundVideo", video != null);
        JSONArray responses = new JSONArray();
        if (video != null) {
            responses.put(request(client, "/file/download", new JSONObject().put("fids", new JSONArray().put(video.id))));
            responses.put(request(client, "/file/v2/play/project", new JSONObject().put("fid", video.id).put("resolutions", "low,normal,high,super,2k,4k").put("supports", "fmp4_av,m3u8")));
            responses.put(request(client, "/file/v2/play/project", new JSONObject().put("fid", video.id).put("resolutions", "normal").put("supports", "fmp4_av,m3u8")));
            responses.put(request(client, "/file/v2/play", new JSONObject().put("fid", video.id).put("resolutions", "normal,low,high,super,2k,4k").put("supports", "fmp4")));
        }
        report.put("responses", responses);
        Bundle metadata = new Bundle(); metadata.putString("stream", "CLEARPLAY_QUARK_DIAG=" + report + "\n"); instrumentation.sendStatus(2, metadata);
        if (video != null) {
            var available = client.sources(video, seconds -> { });
            var selected = available.stream().filter(s -> s.label.equals("720P")).findFirst().orElse(available.get(0));
            JSONObject probe = new JSONObject().put("label", selected.label).put("hls", selected.hls).put("host", java.net.URI.create(selected.url).getHost());
            var conn = DriveClient.Net.open(selected.url, "quark", selected.headers, null, "bytes=0-4095");
            try {
                int code = conn.getResponseCode(); probe.put("httpStatus", code).put("contentType", conn.getContentType());
                if (code < 400) try (var input = conn.getInputStream()) {
                    byte[] first = new byte[16]; int count = input.read(first);
                    StringBuilder magic = new StringBuilder(); for (int i = 0; i < Math.max(0, count); i++) magic.append(String.format(java.util.Locale.ROOT, "%02x", first[i]));
                    probe.put("magic", magic.toString());
                }
            } finally { conn.disconnect(); }
            Bundle probeStatus = new Bundle(); probeStatus.putString("stream", "CLEARPLAY_MEDIA_PROBE=" + probe + "\n"); instrumentation.sendStatus(2, probeStatus);
        }
        if (video != null && "true".equals(InstrumentationRegistry.getArguments().getString("clearplayPlayCheck"))) report.put("playback", play(video));
        Bundle progress = new Bundle(); progress.putString("stream", "CLEARPLAY_QUARK_DIAG=" + report + "\n"); instrumentation.sendStatus(2, progress);
        assertNotNull("No video found in bounded account scan", video);
    }
    static JSONObject play(DriveClient.Item video) throws Exception {
        JSONObject result = new JSONObject();
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        // The device runner foregrounds one activity through adb. A second launch
        // can stop the first activity and pause playback during the measurement.
        var current = new java.util.concurrent.atomic.AtomicReference<MainActivity>();
        long launchDeadline = android.os.SystemClock.elapsedRealtime() + 15000;
        while (current.get() == null && android.os.SystemClock.elapsedRealtime() < launchDeadline) {
            instrumentation.runOnMainSync(() -> {
                for (var activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
                    if (activity instanceof MainActivity main) current.set(main);
            });
            android.os.SystemClock.sleep(100);
        }
        assertNotNull("Player activity did not resume", current.get());
        MainActivity activity = current.get();
        try {
            instrumentation.runOnMainSync(() -> {
                try {
                    var select = MainActivity.class.getDeclaredMethod("selectView", String.class); select.setAccessible(true); select.invoke(activity, video.provider);
                    var method = MainActivity.class.getDeclaredMethod("prepare", DriveClient.Item.class); method.setAccessible(true); method.invoke(activity, video);
                } catch (Exception error) { throw new AssertionError(error); }
            });
            long started = android.os.SystemClock.elapsedRealtime();
            long deadline = started + 60000;
            String requestedQuality = InstrumentationRegistry.getArguments().getString("clearplayQuality", "");
            java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicBoolean configured = new java.util.concurrent.atomic.AtomicBoolean();
            while (android.os.SystemClock.elapsedRealtime() < deadline && !ready.get()) {
                instrumentation.runOnMainSync(() -> {
                    var player = player(activity);
                    if (!requestedQuality.isEmpty() && !configured.get()) {
                        try {
                            var sourcesField = MainActivity.class.getDeclaredField("sources"); sourcesField.setAccessible(true);
                            var sources = (java.util.List<DriveClient.Source>) sourcesField.get(activity);
                            for (int i = 0; i < sources.size(); i++) if (sources.get(i).label.equals(requestedQuality.equals("original") ? "原画" : requestedQuality)) {
                                var selected = MainActivity.class.getDeclaredField("qualityIndex"); selected.setAccessible(true);
                                if (selected.getInt(activity) != i) {
                                    var method = MainActivity.class.getDeclaredMethod("playQuality", int.class, long.class, boolean.class); method.setAccessible(true);
                                    method.invoke(activity, i, 0L, true);
                                }
                                configured.set(true); break;
                            }
                            if (!configured.get()) return;
                        } catch (Exception error) { throw new AssertionError(error); }
                    }
                    if ("true".equals(InstrumentationRegistry.getArguments().getString("clearplayIgnorePhantom")) && !configured.get()) {
                        try {
                            var urisField = MainActivity.class.getDeclaredField("sourceUris"); urisField.setAccessible(true);
                            var uris = (java.util.List<String>) urisField.get(activity);
                            if (!uris.isEmpty()) {
                                var indexField = MainActivity.class.getDeclaredField("qualityIndex"); indexField.setAccessible(true);
                                var registryField = MainActivity.class.getDeclaredField("media"); registryField.setAccessible(true);
                                var registry = (CloudDataSource.Registry) registryField.get(activity);
                                var data = new androidx.media3.datasource.DefaultDataSource.Factory(activity, () -> new CloudDataSource(registry));
                                var media = new androidx.media3.exoplayer.hls.HlsMediaSource.Factory(data)
                                        .setExtractorFactory(new androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory(androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_IGNORE_AAC_STREAM, true))
                                        .setAllowChunklessPreparation(false)
                                        .createMediaSource(new androidx.media3.common.MediaItem.Builder().setUri(uris.get(indexField.getInt(activity))).setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8).build());
                                player.setMediaSource(media, 0); player.prepare(); player.play(); configured.set(true);
                            }
                        } catch (Exception error) { throw new AssertionError(error); }
                    }
                    var error = player.getPlayerError();
                    if (error != null) {
                        StringBuilder details = new StringBuilder(error.getErrorCodeName());
                        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) details.append(" | ").append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
                        fail(details.toString().replaceAll("https?://\\S+", "[remote-url]"));
                    }
                    ready.set(player.getPlaybackState() == androidx.media3.common.Player.STATE_READY && player.getCurrentPosition() > 500 && player.getVideoSize().width > 0 && player.getAudioDecoderCounters() != null && player.getAudioDecoderCounters().renderedOutputBufferCount > 0);
                });
                android.os.SystemClock.sleep(200);
            }
            assertTrue("Cloud video/audio did not start", ready.get());
            result.put("firstFrameAndAudioMs", android.os.SystemClock.elapsedRealtime() - started);
            java.util.concurrent.atomic.AtomicLong start = new java.util.concurrent.atomic.AtomicLong();
            float speed = Float.parseFloat(InstrumentationRegistry.getArguments().getString("clearplaySpeed", "2"));
            int duration = Integer.parseInt(InstrumentationRegistry.getArguments().getString("clearplayDurationMs", "4000"));
            instrumentation.runOnMainSync(() -> { var player = player(activity); player.setPlaybackSpeed(speed); player.play(); start.set(player.getCurrentPosition()); });
            boolean strict = "true".equals(InstrumentationRegistry.getArguments().getString("clearplayStrictPlayback"));
            java.util.concurrent.atomic.AtomicLong buffering = new java.util.concurrent.atomic.AtomicLong();
            long measuredStart = android.os.SystemClock.elapsedRealtime();
            if (strict) {
                long lastTick = measuredStart;
                while (android.os.SystemClock.elapsedRealtime() - measuredStart < duration) {
                    android.os.SystemClock.sleep(200);
                    long tick = android.os.SystemClock.elapsedRealtime(), delta = tick - lastTick; lastTick = tick;
                    instrumentation.runOnMainSync(() -> {
                        if (player(activity).getPlaybackState() == androidx.media3.common.Player.STATE_BUFFERING) buffering.addAndGet(delta);
                        assertNull("Player error during sustained playback", player(activity).getPlayerError());
                    });
                }
            } else android.os.SystemClock.sleep(duration);
            long measuredMs = android.os.SystemClock.elapsedRealtime() - measuredStart;
            instrumentation.runOnMainSync(() -> {
                var player = player(activity);
                try {
                    result.put("width", player.getVideoSize().width).put("height", player.getVideoSize().height)
                            .put("audioBuffers", player.getAudioDecoderCounters().renderedOutputBufferCount)
                            .put("videoFrames", player.getVideoDecoderCounters().renderedOutputBufferCount)
                            .put("speed", player.getPlaybackParameters().speed).put("advancedMs", player.getCurrentPosition() - start.get())
                            .put("measuredMs", measuredMs).put("bufferingMs", strict ? buffering.get() : -1).put("durationMs", player.getDuration());
                    if (strict) {
                        assertTrue("Sustained playback lost progress", player.getCurrentPosition() - start.get() > measuredMs * speed * .95);
                        assertTrue("Sustained playback rebuffered", buffering.get() < 1000);
                    }
                    assertTrue(speed + "x advance=" + (player.getCurrentPosition() - start.get()) + ", state=" + player.getPlaybackState() + ", buffered=" + player.getTotalBufferedDuration() + ", play=" + player.getPlayWhenReady() + ", suppressed=" + player.getPlaybackSuppressionReason() + ", window=" + activity.hasWindowFocus(), player.getCurrentPosition() - start.get() > duration * speed * .75);
                    player.pause();
                } catch (Exception error) { throw new AssertionError(error); }
            });
            playbackStatus("continuous", result);
            if ("true".equals(InstrumentationRegistry.getArguments().getString("clearplayRefreshCheck"))) {
                var uri = new java.util.concurrent.atomic.AtomicReference<android.net.Uri>();
                java.util.concurrent.atomic.AtomicLong before = new java.util.concurrent.atomic.AtomicLong();
                instrumentation.runOnMainSync(() -> {
                    try {
                        var player = player(activity); uri.set(player.getCurrentMediaItem().localConfiguration.uri); before.set(player.getCurrentPosition()); player.play();
                        var method = MainActivity.class.getDeclaredMethod("refreshQualities"); method.setAccessible(true); method.invoke(activity);
                    } catch (Exception error) { throw new AssertionError(error); }
                });
                android.os.SystemClock.sleep(4000);
                instrumentation.runOnMainSync(() -> {
                    var player = player(activity);
                    assertEquals("Refreshing qualities must retain the playing source", uri.get(), player.getCurrentMediaItem().localConfiguration.uri);
                    assertTrue("Playback must advance while qualities load", player.getCurrentPosition() - before.get() > 5000);
                    player.pause();
                });
                result.put("qualityRefreshRetainedPlayback", true);
            }
            String seek = InstrumentationRegistry.getArguments().getString("clearplaySeekMs", "");
            if (!seek.isEmpty()) {
                long target = Long.parseLong(seek);
                java.util.concurrent.atomic.AtomicInteger audioBefore = new java.util.concurrent.atomic.AtomicInteger();
                var previousCounters = new java.util.concurrent.atomic.AtomicReference<androidx.media3.exoplayer.DecoderCounters>();
                instrumentation.runOnMainSync(() -> {
                    var player = player(activity); previousCounters.set(player.getAudioDecoderCounters()); audioBefore.set(player.getAudioDecoderCounters().renderedOutputBufferCount);
                    player.seekTo(target); player.play();
                });
                java.util.concurrent.atomic.AtomicBoolean sought = new java.util.concurrent.atomic.AtomicBoolean();
                long seekDeadline = android.os.SystemClock.elapsedRealtime() + 20000;
                while (!sought.get() && android.os.SystemClock.elapsedRealtime() < seekDeadline) {
                    instrumentation.runOnMainSync(() -> {
                        var player = player(activity);
                        sought.set(player.getPlaybackState() == androidx.media3.common.Player.STATE_READY && player.getCurrentPosition() > target + 500 && player.getAudioDecoderCounters().renderedOutputBufferCount > audioBefore.get() + 10);
                    });
                    android.os.SystemClock.sleep(200);
                }
                instrumentation.runOnMainSync(() -> {
                    var player = player(activity);
                    try { result.put("seekPositionMs", player.getCurrentPosition()).put("seekState", player.getPlaybackState()).put("seekAudioBefore", audioBefore.get())
                            .put("seekAudioAfter", player.getAudioDecoderCounters().renderedOutputBufferCount).put("seekAudioCounterReplaced", player.getAudioDecoderCounters() != previousCounters.get()); }
                    catch (Exception error) { throw new AssertionError(error); }
                });
                playbackStatus("seek", result);
                assertTrue("Seek must resume audio and video", sought.get());
                result.put("seekTargetMs", target).put("seekResumed", true);
                instrumentation.runOnMainSync(() -> player(activity).pause());
            }
        } finally { instrumentation.runOnMainSync(() -> player(activity).pause()); }
        return result;
    }
    private static void playbackStatus(String phase, JSONObject result) throws Exception {
        var bundle = new android.os.Bundle(); bundle.putString("stream", "CLEARPLAY_PLAYBACK=" + new JSONObject().put("phase", phase).put("result", result) + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(2, bundle);
    }
    private static androidx.media3.exoplayer.ExoPlayer player(MainActivity activity) {
        try { var field = MainActivity.class.getDeclaredField("player"); field.setAccessible(true); return (androidx.media3.exoplayer.ExoPlayer) field.get(activity); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private JSONObject request(DriveClient client, String path, JSONObject body) throws Exception {
        var connection = DriveClient.Net.open(DriveClient.query("https://drive.quark.cn/1/clouddrive" + path, Map.of("pr", "ucpro", "fr", "pc")), "quark", client.headers("quark"), body, null);
        JSONObject result = new JSONObject().put("endpoint", path).put("resolutions", body.optString("resolutions"));
        try {
            int status = connection.getResponseCode(); result.put("httpStatus", status);
            JSONObject data = new JSONObject(DriveClient.Net.text(status >= 400 ? connection.getErrorStream() : connection.getInputStream()));
            result.put("code", data.optInt("code", -1)).put("status", data.optInt("status", -1));
            Object payload = data.opt("data");
            result.put("dataType", payload == null ? "null" : payload.getClass().getSimpleName());
            if (payload instanceof JSONArray rows) {
                result.put("items", rows.length());
                if (rows.length() > 0) result.put("firstKeys", rows.getJSONObject(0).names()).put("hasDownloadUrl", !rows.getJSONObject(0).optString("download_url").isEmpty());
            } else if (payload instanceof JSONObject object) {
                result.put("dataKeys", object.names());
                JSONArray rows = object.optJSONArray("video_list"), videos = new JSONArray();
                if (rows != null) for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i), info = row.optJSONObject("video_info");
                    JSONObject video = new JSONObject().put("resolution", row.optString("resolution")).put("hasInfo", info != null);
                    if (info != null) video.put("keys", info.names()).put("hasUrl", !info.optString("url").isEmpty()).put("success", info.opt("success")).put("finish", info.opt("finish")).put("format", info.optString("format")).put("host", info.optString("url").isEmpty() ? "" : java.net.URI.create(info.getString("url")).getHost());
                    videos.put(video);
                }
                result.put("videos", videos);
            }
        } finally { connection.disconnect(); }
        return result;
    }
}
