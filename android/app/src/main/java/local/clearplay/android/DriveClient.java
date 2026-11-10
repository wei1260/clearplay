package local.clearplay.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorCompletionService;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;

class DriveClient {
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36";
    private static final String QUARK_DOWNLOAD_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.56 Chrome/100.0.4896.160 Electron/18.3.5.12 Safari/537.36 Channel/pckk_other_ch";
    private static final Pattern VIDEO = Pattern.compile("(?i).*\\.(mp4|m4v|mov|webm|mkv|avi|ts|m2ts|flv|wmv)$");
    interface Cookies {
        String get(String url);
        void set(String url, String value);
    }
    final Cookies cookies;
    DriveClient(Cookies cookies) { this.cookies = cookies; }

    static final class Item {
        final String provider, id, name, path;
        final boolean directory;
        final long size;
        Item(String provider, String id, String name, String path, boolean directory, long size) {
            this.provider = provider; this.id = id; this.name = name; this.path = path;
            this.directory = directory; this.size = size;
        }
        String key() { return provider + ":" + id; }
        JSONObject json() throws Exception {
            return new JSONObject().put("provider", provider).put("id", id).put("name", name)
                    .put("path", path).put("directory", directory).put("size", size);
        }
        static Item from(JSONObject o) {
            return new Item(o.optString("provider"), o.optString("id"), o.optString("name"),
                    o.optString("path"), o.optBoolean("directory"), o.optLong("size"));
        }
    }
    static final class Listing {
        final List<Item> items;
        final boolean hasMore;
        Listing(List<Item> items, boolean hasMore) { this.items = items; this.hasMore = hasMore; }
    }
    static final class Source {
        final String label, url, provider;
        final Map<String, String> headers;
        final boolean hls;
        String manifest;
        Source audio;
        long fileSize = -1;
        Source(String label, String url, String provider, Map<String, String> headers, boolean hls, String manifest) {
            this.label = label; this.url = url; this.provider = provider;
            this.headers = Map.copyOf(headers); this.hls = hls; this.manifest = manifest;
        }
    }

    static String query(String base, Map<String, ?> params) {
        StringBuilder out = new StringBuilder(base).append(base.contains("?") ? '&' : '?');
        params.forEach((k, v) -> out.append(encode(k))
                .append('=').append(encode(String.valueOf(v))).append('&'));
        return out.substring(0, out.length() - 1);
    }
    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException error) { throw new AssertionError(error); }
    }
    Map<String, String> headers(String provider) throws IOException {
        String site = provider.equals("baidu") ? "https://pan.baidu.com/" : "https://drive.quark.cn/";
        String cookie = cookies.get(site);
        if (cookie == null || cookie.isBlank()) throw new IOException("请先在官网登录窗口完成登录。");
        return Map.of("Cookie", cookie, "User-Agent", UA, "Referer",
                provider.equals("baidu") ? "https://pan.baidu.com/disk/main" : "https://pan.quark.cn/");
    }
    JSONObject api(String provider, String path, Map<String, ?> parameters, JSONObject body) throws Exception {
        Map<String, Object> p = new LinkedHashMap<>();
        String base;
        if (provider.equals("baidu")) {
            base = "https://pan.baidu.com"; p.put("web", 1); p.put("clienttype", 0);
        } else {
            base = "https://drive.quark.cn/1/clouddrive"; p.put("pr", "ucpro"); p.put("fr", "pc");
        }
        p.putAll(parameters);
        String url = query(base + path, p);
        HttpURLConnection connection = Net.open(url, provider, apiHeaders(provider, path), body, null);
        try {
            for (var entry : connection.getHeaderFields().entrySet()) {
                if ("set-cookie".equalsIgnoreCase(entry.getKey()))
                    for (String value : entry.getValue()) cookies.set(url, value);
            }
            int status = connection.getResponseCode();
            String text = Net.text(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            JSONObject data;
            try { data = new JSONObject(text); }
            catch (Exception error) { throw new IOException("网盘响应无法识别，请稍后重试。"); }
            int code = provider.equals("baidu") ? data.optInt("errno", -1) : data.optInt("code", -1);
            if (status >= 400 || code != 0 || (!provider.equals("baidu") && data.optInt("status") != 200))
                throw new IOException("网盘请求失败（" + code + "），请检查登录状态和账号权益。");
            return data;
        } finally { connection.disconnect(); }
    }

    Map<String, String> apiHeaders(String provider, String path) throws IOException {
        Map<String, String> request = new LinkedHashMap<>(headers(provider));
        if (provider.equals("quark") && path.equals("/file/download")) request.put("User-Agent", QUARK_DOWNLOAD_UA);
        return request;
    }

    Listing list(String provider, String directory, int page) throws Exception {
        if (page < 1 || (!provider.equals("baidu") && !provider.equals("quark"))) throw new IOException("无效的目录请求。");
        JSONObject data;
        if (provider.equals("baidu")) {
            if (!directory.startsWith("/")) throw new IOException("无效的网盘目录。");
            data = api(provider, "/api/list", Map.of("dir", directory, "page", page, "num", 100, "order", "name", "desc", 0, "showempty", 0), null);
        } else {
            if (!directory.matches("[\\w-]+")) throw new IOException("无效的网盘目录。");
            data = api(provider, "/file/sort", Map.of("pdir_fid", directory, "_page", page, "_size", 100, "_fetch_total", 1, "_sort", "file_type:asc,file_name:asc"), null);
        }
        JSONArray rows = provider.equals("baidu") ? data.optJSONArray("list") : data.optJSONObject("data").optJSONArray("list");
        List<Item> items = new ArrayList<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject f = rows.getJSONObject(i);
            boolean baidu = provider.equals("baidu");
            boolean folder = baidu ? f.optInt("isdir") == 1 : Boolean.FALSE.equals(f.opt("file")) || f.optBoolean("dir");
            String name = f.optString(baidu ? "server_filename" : "file_name");
            if (folder || f.optInt("category") == 1 || VIDEO.matcher(name).matches()) {
                String id = f.optString(baidu ? "fs_id" : "fid");
                items.add(new Item(provider, id, name, baidu ? f.optString("path") : id, folder, f.optLong("size")));
            }
        }
        boolean more = rows != null && rows.length() == 100;
        if (provider.equals("baidu") && data.has("has_more")) more = data.optInt("has_more") == 1 || data.optBoolean("has_more");
        JSONObject meta = data.optJSONObject("metadata");
        if (meta != null && meta.has("_total")) more = page * 100 < meta.optLong("_total");
        return new Listing(items, more);
    }

    List<Source> sources(Item item, IntConsumer preparing) throws Exception {
        return sources(item, preparing, available -> { });
    }
    Source download(Item item) throws Exception {
        if (item.directory) throw new IOException("请选择要下载的视频文件。");
        Source source;
        if (item.provider.equals("baidu")) {
            if (!item.id.matches("\\d+") || !item.path.startsWith("/")) throw new IOException("文件路径已失效，请刷新目录。");
            JSONObject fields = api("baidu", "/api/gettemplatevariable", Map.of("fields", "[\"bdstoken\"]"), null).getJSONObject("result");
            source = baiduOriginal(item, fields.optString("bdstoken"), headers("baidu"));
        } else if (item.provider.equals("quark")) source = quarkOriginal(item);
        else throw new IOException("请选择百度或夸克网盘中的视频。");
        if (source == null) throw new IOException("网盘没有返回原文件下载资源，请重新登录或稍后重试。");
        return source;
    }
    List<Source> sources(Item item, IntConsumer preparing, Consumer<List<Source>> available) throws Exception {
        if (item.directory) throw new IOException("请选择视频文件。");
        return item.provider.equals("baidu") ? baiduSources(item, preparing, available) : quarkSources(item, available);
    }
    private List<Source> baiduSources(Item item, IntConsumer preparing, Consumer<List<Source>> available) throws Exception {
        if (!item.id.matches("\\d+") || !item.path.startsWith("/")) throw new IOException("视频路径已失效，请重新选择。");
        Map<String, String> headers = headers("baidu");
        JSONObject template = api("baidu", "/api/gettemplatevariable", Map.of("fields", "[\"bdstoken\"]"), null);
        JSONObject result = template.optJSONObject("result");
        String token = result == null ? "" : result.optString("bdstoken");
        String[] types = {"M3U8_AUTO_720", "M3U8_AUTO_480", "M3U8_HLS_MP3_128", "M3U8_AUTO_1080", "M3U8_AUTO_2K"};
        String[] labels = {"720P", "480P", "音频", "1080P", "2K"};
        var pool = Executors.newFixedThreadPool(6);
        var tasks = new ExecutorCompletionService<Source>(pool);
        List<Source> out = new ArrayList<>();
        Source audio = null;
        boolean audioFinished = false;
        try {
            for (int i = 0; i < types.length; i++) {
                final int index = i;
                tasks.submit(() -> {
                    Source stream = baiduStream(item.path, token, types[index], labels[index], headers, preparing);
                    // 保留音频请求已结束的标记，避免在独立音轨返回前启动无声视频。
                    return stream == null && labels[index].equals("音频") ? new Source("音频", null, "baidu", Map.of(), true, null) : stream;
                });
            }
            tasks.submit(() -> {
                return baiduOriginal(item, token, headers);
            });
            for (int i = 0; i < types.length + 1; i++) {
                Source source = tasks.take().get();
                if (source != null && source.label.equals("音频")) {
                    audioFinished = true; audio = source.url == null ? null : source;
                } else if (source != null && out.stream().noneMatch(s -> s.label.equals(source.label))) out.add(source);
                if (audioFinished) {
                    for (Source video : out) video.audio = audio;
                    // 原画不拖住转码起播；全部转码不可用时才以原画作为初始资源。
                    boolean videoReady = out.stream().anyMatch(s -> s.hls) || i == types.length;
                    List<Source> ready = out.stream().filter(s -> s.hls || videoReady).collect(java.util.stream.Collectors.toList());
                    if (!ready.isEmpty()) available.accept(ready);
                }
            }
        } finally { pool.shutdownNow(); }
        if (out.isEmpty()) throw new IOException("网盘没有提供可播放资源，请检查文件和账号权益。");
        return out;
    }
    Source baiduOriginal(Item item, String token, Map<String, String> headers) {
        try {
            Map<String, Object> params = new LinkedHashMap<>(Map.of("target", new JSONArray().put(item.path).toString(), "dlink", 1, "bdstoken", token, "web", 5, "origin", "dlna"));
            JSONObject meta = api("baidu", "/api/filemetas", params, null);
            JSONArray info = meta.optJSONArray("info");
            String url = info != null && info.length() > 0 ? info.getJSONObject(0).optString("dlink") : "";
            if (url.isBlank()) return null;
            url = url.replaceFirst("^http://", "https://"); Net.allowed(url, "baidu");
            Map<String, String> original = new LinkedHashMap<>(headers); original.put("User-Agent", "netdisk");
            Source source = new Source("原画", url, "baidu", original, false, null);
            source.fileSize = item.size;
            if (probeOriginal(source)) return source;
        } catch (Exception ignored) { /* 原画不回退普通下载链路。 */ }
        return null;
    }
    boolean probeOriginal(Source source) throws IOException {
        HttpURLConnection connection = Net.open(source.url, "baidu", source.headers, null, "bytes=0-0", 3000);
        try {
            return connection.getResponseCode() == 206 && String.valueOf(connection.getHeaderField("Content-Range")).matches("bytes 0-0/[1-9][0-9]*");
        } finally { connection.disconnect(); }
    }
    Source baiduStream(String path, String token, String type, String label, Map<String, String> headers, IntConsumer preparing) {
        Map<String, Object> p = new LinkedHashMap<>(Map.of("path", path, "type", type, "web", 1, "clienttype", 0, "bdstoken", token));
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                String url = query("https://pan.baidu.com/api/streaming", p);
                HttpURLConnection connection = Net.open(url, "baidu", headers, null, null);
                String manifest;
                try {
                    if (connection.getResponseCode() >= 400) return null;
                    manifest = Net.text(connection.getInputStream());
                } finally { connection.disconnect(); }
                if (manifest.stripLeading().startsWith("#EXTM3U")) {
                    Source source = new Source(label, url, "baidu", headers, true, manifest);
                    if (label.equals("2K")) {
                        int height = HlsResolutionProbe.height(source);
                        if (height <= 0) return null;
                        source = new Source(height >= 1440 ? "2K · " + height + "P" : height + "P", url, "baidu", headers, true, manifest);
                    }
                    return source;
                }
                JSONObject waiting = new JSONObject(manifest);
                int seconds = waiting.optInt("adTime", -1);
                if (attempt != 0 || waiting.optInt("errno") != 133 || seconds < 0 || !waiting.has("adToken")) return null;
                preparing.accept(seconds);
                Thread.sleep(seconds * 1000L);
                p.put("adToken", waiting.getString("adToken"));
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception ignored) { /* 当前清晰度不可用，不影响其他已成功资源。 */ }
        return null;
    }
    private List<Source> quarkSources(Item item, Consumer<List<Source>> available) throws Exception {
        if (!item.id.matches("[\\w-]+")) throw new IOException("无效的视频编号。");
        List<Source> out = new ArrayList<>();
        List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        var pool = Executors.newFixedThreadPool(2);
        var tasks = new ExecutorCompletionService<List<Source>>(pool);
        tasks.submit(() -> {
          List<Source> original = new ArrayList<>();
          try {
            Source source = quarkOriginal(item);
            if (source != null) original.add(source);
          } catch (IOException error) { errors.add("原画：" + error.getMessage()); }
          return original;
        });
        tasks.submit(() -> {
          List<Source> streams = new ArrayList<>();
          try {
            JSONObject data = api("quark", "/file/v2/play", Map.of(), new JSONObject().put("fid", item.id).put("resolutions", "normal,low,high,super,2k,4k").put("supports", "fmp4"));
            // 网页播放响应会更新 Video-Auth，媒体请求必须使用响应后的会话。
            Map<String, String> playbackHeaders = headers("quark");
            JSONArray rows = data.optJSONObject("data").optJSONArray("video_list");
            if (rows != null) for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i), info = row.optJSONObject("video_info");
                if (info == null || info.optString("url").isBlank() || Boolean.FALSE.equals(info.opt("success")) || Boolean.FALSE.equals(info.opt("finish"))) continue;
                int height = info.optInt("height");
                String label = height >= 1440 && height < 2160 ? "2K · " + height + "P" : height > 0 ? height + "P" : switch (row.optString("resolution")) {
                    case "low" -> "流畅"; case "normal" -> "480P"; case "high" -> "720P";
                    case "super" -> "1080P"; case "2k" -> "2K"; case "4k" -> "4K"; default -> "高清";
                };
                String url = info.getString("url");
                if (streams.stream().noneMatch(s -> s.label.equals(label)))
                    streams.add(new Source(label, url, "quark", playbackHeaders, info.optString("format").contains("m3u8") || url.contains(".m3u8"), null));
            }
          } catch (IOException error) { errors.add("网页播放：" + error.getMessage()); }
          return streams;
        });
        try {
            for (int i = 0; i < 2; i++) {
                out.addAll(tasks.take().get());
                if (!out.isEmpty()) available.accept(new ArrayList<>(out));
            }
        } finally { pool.shutdownNow(); }
        if (out.isEmpty()) throw new IOException("夸克没有返回播放资源，请检查登录状态、转码和账号权益。" + (errors.isEmpty() ? "" : "\n" + String.join("\n", errors)));
        return out;
    }
    private Source quarkOriginal(Item item) throws Exception {
        if (!item.id.matches("[\\w-]+")) throw new IOException("无效的视频编号。");
        JSONObject data = api("quark", "/file/download", Map.of(), new JSONObject().put("fids", new JSONArray().put(item.id)));
        JSONArray rows = data.optJSONArray("data");
        String url = rows != null && rows.length() > 0 ? rows.getJSONObject(0).optString("download_url") : "";
        if (url.isBlank()) return null;
        Source source = new Source("原画", url, "quark", apiHeaders("quark", "/file/download"), false, null);
        source.fileSize = item.size;
        return source;
    }

    static final class Net {
        static final Map<String, List<String>> DOMAINS = Map.of("baidu", List.of("baidu.com", "baidupcs.com", "baidubce.com", "bdstatic.com"),
                "quark", List.of("quark.cn", "quarkcdn.net", "uc.cn", "ucweb.com", "aliyuncs.com"));
        static URI allowed(String value, String provider) throws IOException {
            try {
                URI u = reference(value);
                if (!"https".equals(u.getScheme()) || u.getUserInfo() != null || (u.getPort() != -1 && u.getPort() != 443) ||
                        !DOMAINS.getOrDefault(provider, List.of()).stream().anyMatch(d -> u.getHost() != null && (u.getHost().equals(d) || u.getHost().endsWith("." + d))))
                    throw new IllegalArgumentException();
                return u;
            } catch (IllegalArgumentException error) { throw new IOException("不支持的网盘媒体地址。"); }
        }
        static URI reference(String value) {
            StringBuilder encoded = new StringBuilder();
            String reserved = "-._~:/?#[]@!$&'()*+,;=";
            for (int i = 0; i < value.length();) {
                int code = value.codePointAt(i);
                if (code == '%' && i + 2 < value.length() && Character.digit(value.charAt(i + 1), 16) >= 0 && Character.digit(value.charAt(i + 2), 16) >= 0) {
                    encoded.append(value, i, i + 3); i += 3; continue;
                }
                if (code >= 'a' && code <= 'z' || code >= 'A' && code <= 'Z' || code >= '0' && code <= '9' || code < 128 && reserved.indexOf(code) >= 0) encoded.appendCodePoint(code);
                else for (byte b : new String(Character.toChars(code)).getBytes(StandardCharsets.UTF_8)) {
                    int v = b & 255; encoded.append('%').append("0123456789ABCDEF".charAt(v >>> 4)).append("0123456789ABCDEF".charAt(v & 15));
                }
                i += Character.charCount(code);
            }
            return URI.create(encoded.toString());
        }
        static Map<String, String> safeHeaders(String url, String provider, Map<String, String> original) throws IOException {
            String host = allowed(url, provider).getHost();
            Map<String, String> headers = new LinkedHashMap<>(original);
            List<String> cookies = provider.equals("baidu") ? List.of("baidu.com", "baidupcs.com") : List.of("quark.cn", "uc.cn", "ucweb.com");
            if (cookies.stream().noneMatch(d -> host.equals(d) || host.endsWith("." + d))) headers.remove("Cookie");
            return headers;
        }
        static HttpURLConnection open(String url, String provider, Map<String, String> headers, JSONObject body, String range) throws IOException {
            return open(url, provider, headers, body, range, 20000);
        }
        static HttpURLConnection open(String url, String provider, Map<String, String> headers, JSONObject body, String range, int timeout) throws IOException {
            URI target = allowed(url, provider);
            for (int hop = 0; hop < 6; hop++) {
                HttpURLConnection c = (HttpURLConnection) target.toURL().openConnection();
                c.setInstanceFollowRedirects(false); c.setConnectTimeout(timeout); c.setReadTimeout(timeout);
                safeHeaders(target.toString(), provider, headers).forEach(c::setRequestProperty);
                c.setRequestProperty("Accept-Encoding", "identity");
                if (range != null) c.setRequestProperty("Range", range);
                if (body != null) {
                    c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
                    try (var output = c.getOutputStream()) { output.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                }
                int status = c.getResponseCode();
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) return c;
                String next = c.getHeaderField("Location"); c.disconnect();
                if (next == null) throw new IOException("媒体重定向地址缺失。");
                target = allowed(target.resolve(reference(next)).toString(), provider);
            }
            throw new IOException("媒体重定向次数过多。");
        }
        static String text(InputStream stream) throws IOException {
            if (stream == null) throw new IOException("网盘响应为空。");
            try (stream; var bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = stream.read(buffer)) != -1) {
                    if (bytes.size() + n > 4 * 1024 * 1024) throw new IOException("播放清单或接口响应过大。");
                    bytes.write(buffer, 0, n);
                }
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }
}
