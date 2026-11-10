package local.clearplay.android;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** System-managed downloads continue after the player leaves the foreground. */
final class CloudDownloads {
    static long enqueue(Context context, DriveClient.Item item, DriveClient.Source source) throws IOException {
        if (source == null || source.hls || source.url == null) throw new IOException("网盘没有返回原文件下载资源。");
        // Resolve inside our domain/cookie checks before handing the final URL to Android.
        var connection = DriveClient.Net.open(source.url, source.provider, source.headers, null, "bytes=0-0", 10000);
        String url;
        try {
            if (connection.getResponseCode() != 206 || !String.valueOf(connection.getHeaderField("Content-Range")).matches("bytes 0-0/[1-9][0-9]*"))
                throw new IOException("下载资源暂时不可用，请重新获取。");
            url = connection.getURL().toString();
        } finally { connection.disconnect(); }
        var request = new DownloadManager.Request(Uri.parse(url)).setTitle(item.name)
                .setDescription("澄映 · 原文件下载")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "澄映/" + savedName(item.name));
        DriveClient.Net.safeHeaders(url, source.provider, source.headers).forEach(request::addRequestHeader);
        request.addRequestHeader("Accept-Encoding", "identity");
        return context.getSystemService(DownloadManager.class).enqueue(request);
    }

    static String savedName(String original) {
        String name = original.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "视频.mp4";
        int dot = name.lastIndexOf('.');
        String suffix = " (" + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date()) + ")";
        return dot > 0 ? name.substring(0, dot) + suffix + name.substring(dot) : name + suffix;
    }
}
