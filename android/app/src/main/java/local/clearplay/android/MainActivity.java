package local.clearplay.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MainActivity extends Activity {
    private static final int BG = 0xff101217, PANEL = 0xff191d24, GOLD = 0xffeebd83, TEXT = 0xffe8e9eb, MUTED = 0xff919ba9;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newCachedThreadPool();
    private final CloudDataSource.Registry media = new CloudDataSource.Registry();
    private final List<DriveClient.Item> files = new ArrayList<>(), shown = new ArrayList<>(), queue = new ArrayList<>();
    private final List<String> directories = new ArrayList<>(), directoryNames = new ArrayList<>();
    private final List<View> libraryViews = new ArrayList<>();
    private final List<Button> tabs = new ArrayList<>();
    private final List<String> sourceUris = new ArrayList<>();
    private List<DriveClient.Source> sources = new ArrayList<>();
    private SharedPreferences prefs;
    private JSONArray history;
    private DriveClient client;
    private ExoPlayer player;
    private PlayerView playerView;
    private LinearLayout root;
    private FrameLayout playerBox;
    private TextView welcome, nowTitle, status, pathLabel, downloadStatus;
    private Button speedButton, qualityButton, fullscreenButton, nextButton, accountButton, moreButton, downloadButton;
    private EditText search;
    private ListView list;
    private FileAdapter adapter;
    private DriveClient.Item current;
    private String view = "baidu";
    private int page = 1, listRevision, playRevision, qualityIndex;
    private boolean hasMore, fullscreen, loading, qualitiesLoading;
    private Future<?> prepareTask;
    private Dialog loginDialog;
    private DriveClient.Item pendingDownload;
    private boolean downloadResolving, downloadError;
    private long downloadBytes, downloadTick;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("clearplay", MODE_PRIVATE);
        try { history = new JSONArray(prefs.getString("history", "[]")); } catch (Exception ignored) { history = new JSONArray(); }
        CookieManager cookies = CookieManager.getInstance(); cookies.setAcceptCookie(true);
        client = new DriveClient(new DriveClient.Cookies() {
            public String get(String url) { return cookies.getCookie(url); }
            public void set(String url, String value) { cookies.setCookie(url, value); }
        });
        buildUi();
        var factory = new DefaultDataSource.Factory(this, () -> new CloudDataSource(media));
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(factory))
                .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setPlaybackSpeed(prefs.getFloat("speed", 1f));
        speedButton.setText(speedText());
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean playing) {
                if (playing) { getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); status.setText(""); }
                else { getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); saveProgress(); }
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) welcome.setVisibility(View.GONE);
                if (state == Player.STATE_BUFFERING && !sources.isEmpty() && qualityIndex < sources.size() && sources.get(qualityIndex).label.equals("原画")) {
                    status.setText("原画正在缓冲，读取速度受网盘影响。点此切换其他可用画质。");
                    status.setOnClickListener(v -> chooseQuality());
                }
                if (state == Player.STATE_ENDED && prefs.getBoolean("autoplay", false)) nextVideo();
            }
            @Override public void onPlayerError(PlaybackException error) {
                String detail = "";
                for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause())
                    if (cause.getMessage() != null && cause.getMessage().startsWith("网盘媒体请求失败（HTTP")) { detail = cause.getMessage() + " "; break; }
                status.setText("视频暂时无法播放（" + error.getErrorCodeName() + "）。" + detail + "可切换画质，或点此重新获取资源。");
                status.setOnClickListener(v -> { if (current != null) prepare(current); });
            }
        });
        ui.postDelayed(new Runnable() {
            @Override public void run() { saveProgress(); ui.postDelayed(this, 5000); }
        }, 5000);
        ui.post(new Runnable() {
            @Override public void run() { updateDownloadStatus(); ui.postDelayed(this, 1000); }
        });
        selectView("baidu");
    }

    private void buildUi() {
        root = column(); root.setBackgroundColor(BG); setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        LinearLayout header = row(); header.setPadding(dp(16), dp(8), dp(12), dp(8));
        TextView brand = label("澄映  ·  CLEARPLAY", 20, GOLD); brand.setTypeface(null, Typeface.BOLD);
        header.addView(brand, weight());
        Button local = button("打开本地", v -> openLocal()); header.addView(local); root.addView(header); libraryViews.add(header);
        LinearLayout nav = row(); nav.setPadding(dp(8), 0, dp(8), dp(8));
        String[] labels = {"百度网盘", "夸克网盘", "最近播放", "本地"};
        String[] ids = {"baidu", "quark", "recent", "local"};
        for (int i = 0; i < labels.length; i++) {
            final String id = ids[i]; Button tab = button(labels[i], v -> selectView(id));
            nav.addView(tab, weight()); tabs.add(tab);
        }
        root.addView(nav); libraryViews.add(nav);
        playerBox = new FrameLayout(this); playerBox.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this); playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerBox.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        welcome = label("你的私人放映室\n\n连接网盘或打开本地视频", 16, MUTED); welcome.setGravity(Gravity.CENTER);
        playerBox.addView(welcome, new FrameLayout.LayoutParams(-1, -1)); root.addView(playerBox);
        nowTitle = label("等待你的下一部影片", 13, TEXT); nowTitle.setSingleLine(true); nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        nowTitle.setPadding(dp(14), dp(10), dp(14), dp(4)); root.addView(nowTitle); libraryViews.add(nowTitle);
        LinearLayout controls = row(); controls.setPadding(dp(8), dp(4), dp(8), dp(4));
        speedButton = button("1.0×", v -> chooseSpeed()); qualityButton = button("画质", v -> chooseQuality());
        nextButton = button("下一集", v -> nextVideo()); fullscreenButton = button("全屏", v -> toggleFullscreen());
        downloadButton = button("下载", v -> beginDownload(current)); downloadButton.setEnabled(false);
        for (Button b : List.of(speedButton, qualityButton, nextButton, fullscreenButton, downloadButton)) controls.addView(b, weight());
        qualityButton.setEnabled(false); nextButton.setEnabled(false); root.addView(controls);
        downloadStatus = label("", 12, GOLD); downloadStatus.setPadding(dp(14), dp(6), dp(14), dp(6));
        downloadStatus.setVisibility(View.GONE); downloadStatus.setOnClickListener(v -> {
            try { startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); }
            catch (android.content.ActivityNotFoundException error) { toast("文件保存在手机“下载/澄映”目录。"); }
        });
        root.addView(downloadStatus); libraryViews.add(downloadStatus);
        status = label("", 12, MUTED); status.setPadding(dp(14), dp(4), dp(14), dp(6)); root.addView(status); libraryViews.add(status);
        LinearLayout folder = row(); folder.setPadding(dp(8), 0, dp(8), 0);
        Button back = button("‹", v -> parentFolder()); folder.addView(back);
        pathLabel = label("全部文件", 13, TEXT); pathLabel.setSingleLine(true); pathLabel.setEllipsize(TextUtils.TruncateAt.START); folder.addView(pathLabel, weight());
        accountButton = button("登录", v -> manageAccount()); folder.addView(accountButton);
        folder.addView(button("刷新", v -> loadFiles(false))); root.addView(folder); libraryViews.add(folder);
        search = new EditText(this); search.setSingleLine(true); search.setTextSize(13); search.setTextColor(TEXT); search.setHintTextColor(MUTED);
        search.setHint("搜索当前已加载的目录"); search.setPadding(dp(14), dp(4), dp(14), dp(4)); root.addView(search); libraryViews.add(search);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { filterFiles(); }
            public void afterTextChanged(Editable e) { }
        });
        list = new ListView(this); list.setDividerHeight(dp(1)); list.setDivider(new android.graphics.drawable.ColorDrawable(0xff272b33));
        adapter = new FileAdapter(); list.setAdapter(adapter); root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1)); libraryViews.add(list);
        list.setOnItemClickListener((p, v, position, id) -> {
            DriveClient.Item file = shown.get(position);
            if (file.directory) { directories.add(file.path); directoryNames.add(file.name); loadFiles(false); }
            else {
                queue.clear(); for (DriveClient.Item candidate : files) if (!candidate.directory) queue.add(candidate);
                prepare(file);
            }
        });
        moreButton = button("加载更多", v -> loadFiles(true)); moreButton.setVisibility(View.GONE); root.addView(moreButton); libraryViews.add(moreButton);
        updatePlayerSize();
    }

    private void selectView(String next) {
        view = next; directories.clear(); directoryNames.clear();
        directories.add(next.equals("quark") ? "0" : "/"); directoryNames.add("全部文件");
        String[] ids = {"baidu", "quark", "recent", "local"};
        for (int i = 0; i < ids.length; i++) tabs.get(i).setTextColor(ids[i].equals(next) ? GOLD : MUTED);
        search.setText(""); accountButton.setVisibility(isCloud() ? View.VISIBLE : View.GONE);
        loadFiles(false);
    }
    private boolean isCloud() { return view.equals("baidu") || view.equals("quark"); }
    private void parentFolder() {
        if (directories.size() > 1) { directories.remove(directories.size() - 1); directoryNames.remove(directoryNames.size() - 1); loadFiles(false); }
    }
    private void loadFiles(boolean append) {
        if (append && loading) return;
        final int revision = ++listRevision, requestedPage = append ? page + 1 : 1;
        final String provider = view, directory = directories.get(directories.size() - 1);
        if (!append) { files.clear(); page = 1; hasMore = false; }
        loading = true; status.setOnClickListener(null); filterFiles();
        pathLabel.setText(String.join(" / ", directoryNames));
        accountButton.setText(prefs.getBoolean("connected-" + view, false) ? "账号" : "登录");
        if (!isCloud()) {
            loading = false;
            JSONArray rows;
            try { rows = provider.equals("recent") ? history : new JSONArray(prefs.getString("local", "[]")); }
            catch (Exception ignored) { rows = new JSONArray(); }
            for (int i = 0; i < rows.length(); i++) if (rows.optJSONObject(i) != null) files.add(DriveClient.Item.from(rows.optJSONObject(i)));
            status.setText(files.isEmpty() ? provider.equals("local") ? "点击“打开本地”，选择手机中的视频。" : "播放后会自动保留观看进度。" : "");
            filterFiles(); return;
        }
        status.setText("正在读取网盘目录…");
        worker.submit(() -> {
            try {
                DriveClient.Listing result = client.list(provider, directory, requestedPage);
                ui.post(() -> {
                    if (revision != listRevision || isFinishing()) return;
                    for (DriveClient.Item item : result.items) if (files.stream().noneMatch(f -> f.key().equals(item.key()))) files.add(item);
                    loading = false; page = requestedPage; hasMore = result.hasMore;
                    prefs.edit().putBoolean("connected-" + provider, true).apply(); accountButton.setText("账号");
                    status.setText(files.isEmpty() ? "该目录暂时没有视频，可进入其他文件夹。" : ""); filterFiles();
                });
            } catch (Exception error) {
                ui.post(() -> { if (revision == listRevision && !isFinishing()) {
                    loading = false; status.setText(message(error) + " 点击“登录”重新连接，或点此重试。");
                    status.setOnClickListener(v -> loadFiles(false)); filterFiles();
                } });
            }
        });
    }
    private void filterFiles() {
        if (adapter == null) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear(); for (DriveClient.Item file : files) if (file.name.toLowerCase(Locale.ROOT).contains(query)) shown.add(file);
        adapter.notifyDataSetChanged();
        moreButton.setVisibility(hasMore && !fullscreen ? View.VISIBLE : View.GONE); moreButton.setEnabled(!loading);
    }

    private void prepare(DriveClient.Item item) {
        saveProgress(); final int revision = ++playRevision;
        if (prepareTask != null) prepareTask.cancel(true);
        player.pause(); player.clearMediaItems(); media.clear();
        current = item; sources = new ArrayList<>(); sourceUris.clear();
        downloadButton.setEnabled(!item.provider.equals("local"));
        nowTitle.setText(item.name); welcome.setVisibility(View.GONE); status.setOnClickListener(null);
        qualityButton.setEnabled(false); qualityButton.setText("画质"); nextButton.setEnabled(queue.indexOf(item) < queue.size() - 1);
        long resume = progress(item);
        if (item.provider.equals("local")) {
            playUri(item.path, false, resume, true); remember(item, resume, 0); return;
        }
        status.setText("正在获取网盘播放资源…"); qualitiesLoading = true;
        prepareTask = worker.submit(() -> {
            try {
                List<DriveClient.Source> result = client.sources(item, seconds -> ui.post(() -> {
                    if (revision == playRevision && sources.isEmpty()) status.setText("网盘视频准备中，需等待约 " + seconds + " 秒…");
                }), ready -> ui.post(() -> applySources(ready, revision, resume)));
                ui.post(() -> {
                    if (revision != playRevision || isFinishing()) return;
                    qualitiesLoading = false; applySources(result, revision, resume);
                });
            } catch (Exception error) { ui.post(() -> { if (revision == playRevision && !isFinishing()) {
                qualitiesLoading = false;
                if (sources.isEmpty()) { status.setText(message(error) + " 点此重新获取。"); status.setOnClickListener(v -> prepare(item)); }
            } }); }
        });
    }
    private void applySources(List<DriveClient.Source> available, int revision, long resume) {
        if (revision != playRevision || isFinishing()) return;
        boolean first = sources.isEmpty();
        try {
            for (DriveClient.Source source : available) {
                int index = -1;
                for (int i = 0; i < sources.size(); i++) if (sources.get(i).label.equals(source.label)) { index = i; break; }
                if (index < 0) { sources.add(source); sourceUris.add(media.add(source)); }
                else if (sources.get(index) != source) { sources.set(index, source); sourceUris.set(index, media.add(source)); }
            }
            qualityButton.setEnabled(!sources.isEmpty());
            if (first && !sources.isEmpty()) {
                qualityIndex = 0;
                for (int i = 0; i < sources.size(); i++) if (sources.get(i).hls) { qualityIndex = i; break; }
                for (int i = 0; i < sources.size(); i++) if (sources.get(i).label.equals("720P")) { qualityIndex = i; break; }
                playQuality(qualityIndex, resume, true); remember(current, resume, 0);
            }
        } catch (Exception error) { status.setText(message(error)); }
    }
    private void beginDownload(DriveClient.Item item) {
        if (item == null || item.directory || item.provider.equals("local")) { toast("请选择网盘视频，或点击视频右侧的“下载”。"); return; }
        if (downloadResolving) { toast("正在获取下载地址…"); return; }
        if (android.os.Build.VERSION.SDK_INT < 29 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingDownload = item; requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 51); return;
        }
        downloadResolving = true; downloadError = false; downloadStatus.setVisibility(View.VISIBLE); downloadStatus.setText("正在获取原文件下载地址…");
        worker.submit(() -> {
            try {
                long id = CloudDownloads.enqueue(this, item, client.download(item));
                prefs.edit().putLong("last-download", id).apply();
                ui.post(() -> { downloadResolving = false; downloadBytes = 0; downloadTick = 0; updateDownloadStatus(); toast("已开始下载，保存在“下载/澄映”。"); });
            } catch (Exception error) { ui.post(() -> { downloadResolving = false; downloadError = true; downloadStatus.setText("下载未开始：" + message(error)); }); }
        });
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 51) {
            DriveClient.Item item = pendingDownload; pendingDownload = null;
            if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) beginDownload(item);
            else toast("未获得存储权限，无法保存到下载目录。");
        }
    }
    private void updateDownloadStatus() {
        if (downloadResolving || downloadError) return;
        long id = prefs.getLong("last-download", -1); if (id < 0) return;
        var manager = getSystemService(DownloadManager.class);
        try (var cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (!cursor.moveToFirst()) { downloadStatus.setVisibility(View.GONE); return; }
            int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            long now = android.os.SystemClock.elapsedRealtime();
            double speed = downloadTick == 0 || now == downloadTick ? 0 : Math.max(0, bytes - downloadBytes) * 1000.0 / (now - downloadTick) / 1000000;
            downloadBytes = bytes; downloadTick = now; downloadStatus.setVisibility(View.VISIBLE);
            downloadStatus.setText(switch (state) {
                case DownloadManager.STATUS_SUCCESSFUL -> "下载完成 · 已保存到 下载/澄映 · 点击查看";
                case DownloadManager.STATUS_FAILED -> "下载失败 · 请点击“下载”重新获取资源 · 点击查看记录";
                case DownloadManager.STATUS_PAUSED -> "下载已暂停 · 点击查看系统下载";
                case DownloadManager.STATUS_RUNNING -> String.format(Locale.ROOT, "下载中 %s · %.2f MB/s · 点击查看", total > 0 ? (bytes * 100 / total) + "%" : String.format(Locale.ROOT, "%.1f MB", bytes / 1000000.0), speed);
                default -> "下载任务已加入队列 · 点击查看";
            });
        }
    }
    private void playQuality(int index, long position, boolean play) {
        qualityIndex = index; DriveClient.Source source = sources.get(index); qualityButton.setText(source.label);
        if (source.hls && source.audio != null) {
            var data = new DefaultDataSource.Factory(this, () -> new CloudDataSource(media));
            var stream = new HlsMediaSource.Factory(data)
                    .setExtractorFactory(new DefaultHlsExtractorFactory(DefaultTsPayloadReaderFactory.FLAG_IGNORE_AAC_STREAM, true))
                    .setAllowChunklessPreparation(false)
                    .createMediaSource(new MediaItem.Builder().setUri(sourceUris.get(index)).setMimeType(MimeTypes.APPLICATION_M3U8).build());
            player.setMediaSource(stream, position); player.prepare(); player.setPlayWhenReady(play); status.setText("正在缓冲视频…");
        } else if (!source.hls && source.provider.equals("baidu") && source.audio != null) {
            var data = new DefaultDataSource.Factory(this, () -> new CloudDataSource(media));
            var original = new ProgressiveMediaSource.Factory(data, () -> java.util.Arrays.stream(new DefaultExtractorsFactory().createExtractors())
                    .map(OriginalVideoExtractor::new).toArray(androidx.media3.extractor.Extractor[]::new))
                    .createMediaSource(new MediaItem.Builder().setUri(sourceUris.get(index)).build());
            try {
                var audio = new HlsMediaSource.Factory(data).createMediaSource(new MediaItem.Builder().setUri(media.add(source.audio)).setMimeType(MimeTypes.APPLICATION_M3U8).build());
                player.setMediaSource(new MergingMediaSource(true, true, original, audio), position);
                player.prepare(); player.setPlayWhenReady(play); status.setText("正在加载原画…");
            } catch (Exception error) { status.setText(message(error)); }
        } else playUri(sourceUris.get(index), source.hls, position, play);
    }
    private void playUri(String uri, boolean hls, long position, boolean play) {
        MediaItem.Builder item = new MediaItem.Builder().setUri(uri);
        if (hls) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(item.build(), position); player.prepare(); player.setPlayWhenReady(play);
        status.setText("正在缓冲视频…");
    }
    private void nextVideo() {
        if (current == null) return;
        for (int i = 0; i + 1 < queue.size(); i++) if (queue.get(i).key().equals(current.key())) { prepare(queue.get(i + 1)); return; }
        toast("已经是最后一集");
    }
    private void chooseSpeed() {
        float[] speeds = {.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f};
        String[] labels = new String[speeds.length]; int selected = 2;
        for (int i = 0; i < speeds.length; i++) { labels[i] = speeds[i] + "×"; if (speeds[i] == player.getPlaybackParameters().speed) selected = i; }
        new AlertDialog.Builder(this).setTitle("播放速度").setSingleChoiceItems(labels, selected, (dialog, index) -> {
            player.setPlaybackSpeed(speeds[index]); prefs.edit().putFloat("speed", speeds[index]).apply(); speedButton.setText(speedText()); dialog.dismiss();
        }).setNegativeButton("取消", null).show();
    }
    private String speedText() { return player == null ? "1.0×" : player.getPlaybackParameters().speed + "×"; }
    private void chooseQuality() {
        String[] labels = sources.stream().map(s -> s.label).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle(qualitiesLoading ? "选择画质 · 其他画质加载中" : "选择画质").setSingleChoiceItems(labels, qualityIndex, (dialog, index) -> {
            if (index != qualityIndex || player.getPlayerError() != null) playQuality(index, Math.max(0, player.getCurrentPosition()), player.getPlayWhenReady());
            dialog.dismiss();
        }).setNeutralButton("刷新可用画质", (dialog, which) -> refreshQualities()).setNegativeButton("取消", null).show();
    }
    private void refreshQualities() {
        if (current == null || current.provider.equals("local")) return;
        if (qualitiesLoading) { toast("正在补充可用画质，请稍后再查看"); return; }
        qualitiesLoading = true; int revision = playRevision;
        DriveClient.Item item = current;
        prepareTask = worker.submit(() -> {
            try {
                client.sources(item, seconds -> { }, ready -> ui.post(() -> applySources(ready, revision, 0)));
                ui.post(() -> { if (revision == playRevision) { qualitiesLoading = false; toast("已更新可用画质；更高画质需转码完成且账号具备权限"); } });
            } catch (Exception error) { ui.post(() -> { if (revision == playRevision) { qualitiesLoading = false; toast(message(error)); } }); }
        });
    }

    private void openLocal() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, 100);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 100 || result != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        importLocal(uris);
    }
    void importLocal(List<Uri> uris) {
        JSONArray stored;
        try { stored = new JSONArray(prefs.getString("local", "[]")); } catch (Exception ignored) { stored = new JSONArray(); }
        List<DriveClient.Item> selected = new ArrayList<>();
        for (Uri uri : uris) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { /* 某些提供者只允许当前会话读取。 */ }
            String name = "本地视频"; long size = 0;
            try (var cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) { name = cursor.getString(0); if (!cursor.isNull(1)) size = cursor.getLong(1); }
            } catch (Exception ignored) { }
            DriveClient.Item item = new DriveClient.Item("local", uri.toString(), name, uri.toString(), false, size);
            selected.add(item);
        }
        JSONArray merged = new JSONArray();
        try {
            for (DriveClient.Item item : selected) merged.put(item.json());
            for (int i = 0; i < stored.length() && merged.length() < 100; i++) {
                JSONObject item = stored.optJSONObject(i);
                if (item != null && selected.stream().noneMatch(f -> f.id.equals(item.optString("id")))) merged.put(item);
            }
        } catch (Exception error) { toast("本地文件记录未能保存"); }
        prefs.edit().putString("local", merged.toString()).apply();
        selectView("local"); queue.clear(); queue.addAll(selected);
        if (!selected.isEmpty()) prepare(selected.get(0));
    }

    private void manageAccount() {
        if (!isCloud()) return;
        String provider = view;
        if (!prefs.getBoolean("connected-" + provider, false)) { showLogin(provider); return; }
        new AlertDialog.Builder(this).setTitle(providerName(provider)).setItems(new String[]{"重新登录", "自动播放下一集：" + (prefs.getBoolean("autoplay", false) ? "开" : "关"), "断开此网盘"}, (dialog, index) -> {
            if (index == 0) showLogin(provider);
            else if (index == 1) { prefs.edit().putBoolean("autoplay", !prefs.getBoolean("autoplay", false)).apply(); toast("已更新自动播放设置"); }
            else disconnect(provider);
        }).setNegativeButton("关闭", null).show();
    }
    private void disconnect(String provider) {
        if (current != null && current.provider.equals(provider)) {
            saveProgress(); playRevision++; if (prepareTask != null) prepareTask.cancel(true);
            player.pause(); player.clearMediaItems(); media.clear(); current = null; welcome.setVisibility(View.VISIBLE);
            nowTitle.setText("等待你的下一部影片"); qualityButton.setEnabled(false); nextButton.setEnabled(false); downloadButton.setEnabled(false);
        }
        CookieManager manager = CookieManager.getInstance();
        String[] hosts = provider.equals("baidu") ? new String[]{"pan.baidu.com", "passport.baidu.com", "baidu.com"} : new String[]{"pan.quark.cn", "drive.quark.cn", "quark.cn", "uc.cn", "ucweb.com"};
        for (String host : hosts) {
            String url = "https://" + host + "/", cookie = manager.getCookie(url);
            if (cookie == null) continue;
            for (String value : cookie.split(";")) {
                String name = value.split("=", 2)[0].trim();
                manager.setCookie(url, name + "=; Max-Age=0; Path=/; Secure");
                String domain = host.endsWith("baidu.com") ? "baidu.com" : host.endsWith("quark.cn") ? "quark.cn" : host;
                manager.setCookie(url, name + "=; Max-Age=0; Path=/; Domain=." + domain + "; Secure");
            }
        }
        manager.flush(); prefs.edit().putBoolean("connected-" + provider, false).apply(); loadFiles(false);
    }
    private void showLogin(String provider) {
        saveProgress(); player.pause();
        if (loginDialog != null) loginDialog.dismiss();
        Dialog dialog = new Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content = column(); content.setBackgroundColor(BG);
        LinearLayout bar = row();
        Button close = button("返回", v -> dialog.dismiss()); bar.addView(close);
        TextView title = label(providerName(provider) + "官网登录", 15, GOLD); bar.addView(title, weight());
        Button done = button("完成登录", null); bar.addView(done); content.addView(bar);
        TextView hint = label("请在官方页面使用手机号、短信或密码登录。", 12, MUTED);
        hint.setPadding(dp(12), dp(8), dp(12), dp(8)); content.addView(hint);
        WebView web = new WebView(this);
        WebSettings settings = web.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setUserAgentString(provider.equals("baidu") ? WebSettings.getDefaultUserAgent(this) : DriveClient.UA);
        settings.setUseWideViewPort(true); settings.setLoadWithOverviewMode(false); settings.setTextZoom(100);
        settings.setBuiltInZoomControls(true); settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setGeolocationEnabled(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        String url = provider.equals("baidu") ? "https://wappass.baidu.com/passport/?login&tpl=netdisk&u=https%3A%2F%2Fpan.baidu.com%2Fwap%2Fhome" : "https://pan.quark.cn/";
        String mobileStyle;
        try { mobileStyle = DriveClient.Net.text(getAssets().open("quark-login-mobile.js")); }
        catch (Exception ignored) { mobileStyle = ""; }
        final String layoutScript = mobileStyle;
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                try { DriveClient.Net.allowed(request.getUrl().toString(), provider); return false; }
                catch (Exception error) { hint.setText("请使用官方页面里的网页登录方式。"); return true; }
            }
            @Override public void onPageStarted(WebView v, String u, android.graphics.Bitmap icon) { hint.setText("正在加载官网…完成后点击右上角“完成登录”。"); }
            @Override public void onPageFinished(WebView v, String u) {
                if (provider.equals("quark") && "pan.quark.cn".equals(Uri.parse(u).getHost())) v.evaluateJavascript(layoutScript, null);
                hint.setText("请在官网完成登录，再点右上角“完成登录”。");
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) hint.setText("官网加载失败，请检查手机网络，然后点击下方“重新加载”。");
            }
            @Override public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) { handler.cancel(); hint.setText("官网证书验证失败，请检查手机时间或网络后重试。"); }
        });
        content.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(button("重新加载", v -> web.loadUrl(url)));
        done.setOnClickListener(v -> {
            done.setEnabled(false); hint.setText("正在验证网盘目录…"); CookieManager.getInstance().flush();
            worker.submit(() -> {
                try {
                    client.list(provider, provider.equals("quark") ? "0" : "/", 1);
                    ui.post(() -> {
                        if (!dialog.isShowing() || isFinishing()) return;
                        prefs.edit().putBoolean("connected-" + provider, true).apply(); CookieManager.getInstance().flush();
                        dialog.dismiss(); selectView(provider); toast("网盘连接成功");
                    });
                } catch (Exception error) { ui.post(() -> { if (dialog.isShowing()) { done.setEnabled(true); hint.setText(message(error)); } }); }
            });
        });
        dialog.setContentView(content); loginDialog = dialog;
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            content.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        dialog.setOnDismissListener(d -> { web.stopLoading(); web.destroy(); if (loginDialog == dialog) loginDialog = null; });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) { window.setLayout(-1, -1); window.setBackgroundDrawableResource(android.R.color.transparent); window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); }
        web.loadUrl(url);
    }

    private long progress(DriveClient.Item item) {
        for (int i = 0; i < history.length(); i++) {
            JSONObject row = history.optJSONObject(i);
            if (row != null && item.key().equals(row.optString("provider") + ":" + row.optString("id"))) return row.optLong("position");
        }
        return 0;
    }
    private void remember(DriveClient.Item item, long position, long duration) {
        try {
            JSONObject row = item.json().put("position", duration > 0 && duration - position < 3000 ? 0 : position).put("duration", duration);
            JSONArray next = new JSONArray().put(row);
            for (int i = 0; i < history.length() && next.length() < 100; i++) {
                JSONObject previous = history.optJSONObject(i);
                if (previous != null && !item.key().equals(previous.optString("provider") + ":" + previous.optString("id"))) next.put(previous);
            }
            history = next; prefs.edit().putString("history", history.toString()).apply();
        } catch (Exception error) { toast("播放进度未能保存"); }
    }
    private void saveProgress() {
        if (current != null && player != null && player.getDuration() > 0)
            remember(current, Math.max(0, player.getCurrentPosition()), player.getDuration());
    }
    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        for (View child : libraryViews) child.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        fullscreenButton.setText(fullscreen ? "退出全屏" : "全屏");
        if (!fullscreen) filterFiles();
        setRequestedOrientation(fullscreen ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        getWindow().getDecorView().setSystemUiVisibility(fullscreen ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : View.SYSTEM_UI_FLAG_VISIBLE);
        updatePlayerSize(); root.requestApplyInsets();
    }
    private void updatePlayerSize() {
        int width = getResources().getDisplayMetrics().widthPixels;
        playerBox.setLayoutParams(new LinearLayout.LayoutParams(-1, fullscreen ? 0 : width * 9 / 16, fullscreen ? 1 : 0));
    }
    @Override public void onConfigurationChanged(Configuration next) { super.onConfigurationChanged(next); updatePlayerSize(); }
    @Override public void onBackPressed() {
        if (fullscreen) toggleFullscreen();
        else if (directories.size() > 1 && isCloud()) parentFolder();
        else super.onBackPressed();
    }
    @Override protected void onStop() { saveProgress(); if (player != null) player.pause(); super.onStop(); }
    @Override protected void onDestroy() {
        saveProgress(); ui.removeCallbacksAndMessages(null); worker.shutdownNow();
        if (prepareTask != null) prepareTask.cancel(true);
        if (loginDialog != null) loginDialog.dismiss();
        if (player != null) { playerView.setPlayer(null); player.release(); }
        media.clear(); super.onDestroy();
    }
    private final class FileAdapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int position) { return shown.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View recycled, ViewGroup parent) {
            DriveClient.Item file = shown.get(position);
            LinearLayout box = column(); box.setPadding(dp(16), dp(13), dp(16), dp(13));
            box.setBackgroundColor(current != null && current.key().equals(file.key()) ? 0xff2b261f : BG);
            TextView title = label((file.directory ? "▣  " : "▷  ") + file.name, 14, file.directory ? GOLD : TEXT);
            title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); box.addView(title);
            String caption = file.directory ? "文件夹" : providerName(file.provider) + (file.size > 0 ? " · " + String.format(Locale.ROOT, "%.1f MB", file.size / 1048576.0) : "");
            long progress = progress(file);
            if (view.equals("recent") && progress > 0) caption += " · 已看到 " + String.format(Locale.ROOT, "%02d:%02d", progress / 60000, progress / 1000 % 60);
            TextView small = label(caption, 11, MUTED); small.setPadding(0, dp(5), 0, 0); box.addView(small);
            if (!file.directory && (file.provider.equals("baidu") || file.provider.equals("quark"))) {
                LinearLayout line = row(); line.addView(box, weight());
                Button download = button("下载", v -> beginDownload(file)); download.setFocusable(false); line.addView(download);
                return line;
            }
            return box;
        }
    }
    private static String providerName(String p) { return switch (p) { case "baidu" -> "百度网盘"; case "quark" -> "夸克网盘"; case "local" -> "本地视频"; default -> "最近播放"; }; }
    private String message(Exception e) { return e instanceof java.io.IOException ? e.getMessage() : "操作失败，请检查网络或重新选择视频。"; }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); return box; }
    private LinearLayout row() { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.HORIZONTAL); box.setGravity(Gravity.CENTER_VERTICAL); return box; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private TextView label(String value, int size, int color) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); return t; }
    private Button button(String value, View.OnClickListener click) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(12); b.setTextColor(GOLD);
        b.setMinWidth(dp(48)); b.setMinimumWidth(dp(48)); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable background = new GradientDrawable(); background.setColor(PANEL); background.setCornerRadius(dp(6)); b.setBackground(background);
        b.setOnClickListener(click); return b;
    }
}
