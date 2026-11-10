package local.clearplay.android;

import android.app.DownloadManager;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.MessageDigest;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Clicks the actual row button, verifies a permanent system download without starting playback. */
@RunWith(AndroidJUnit4.class)
public class DownloadButtonTest {
    @Test public void downloadsFromVisibleButton() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var args = InstrumentationRegistry.getArguments();
        assumeTrue("Explicit account check required", "true".equals(args.getString("clearplayAccountCheck")));
        var context = instrumentation.getTargetContext();
        var prefs = context.getSharedPreferences("clearplay", 0);
        String provider = args.getString("clearplayProvider", "baidu");
        var history = new JSONArray(prefs.getString("history", "[]"));
        DriveClient.Item chosen = null;
        for (int i=0;i<history.length();i++) {
            var row=history.getJSONObject(i);
            if (provider.equals(row.optString("provider")) && row.optLong("size") > 1024*1024 && row.optLong("size") <= 256L*1024*1024) { chosen=DriveClient.Item.from(row); break; }
        }
        assertNotNull("No bounded recent cloud file",chosen);
        final DriveClient.Item item=chosen;
        var activity = new java.util.concurrent.atomic.AtomicReference<MainActivity>();
        long launch=SystemClock.elapsedRealtime();
        while(activity.get()==null&&SystemClock.elapsedRealtime()-launch<15000) {
            instrumentation.runOnMainSync(()->{for(var current: ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) if(current instanceof MainActivity main) activity.set(main);});
            SystemClock.sleep(100);
        }
        assertNotNull("Player must be foreground",activity.get());
        MainActivity main=activity.get(); long before=prefs.getLong("last-download",-1);
        try {
            instrumentation.runOnMainSync(()->{
                main.setTurnScreenOn(true);main.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                try {
                    var select=MainActivity.class.getDeclaredMethod("selectView",String.class);select.setAccessible(true);select.invoke(main,"recent");
                    var shownField=MainActivity.class.getDeclaredField("shown");shownField.setAccessible(true);
                    var shown=(java.util.List<DriveClient.Item>)shownField.get(main);
                    int index=-1;for(int i=0;i<shown.size();i++)if(shown.get(i).key().equals(item.key()))index=i;
                    assertTrue(index>=0);var listField=MainActivity.class.getDeclaredField("list");listField.setAccessible(true);((ListView)listField.get(main)).setSelection(index);
                } catch(Exception error){throw new AssertionError(error);}
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->{
                try {
                    var listField=MainActivity.class.getDeclaredField("list");listField.setAccessible(true);var list=(ListView)listField.get(main);
                    var shownField=MainActivity.class.getDeclaredField("shown");shownField.setAccessible(true);var shown=(java.util.List<DriveClient.Item>)shownField.get(main);
                    int index=-1;for(int i=0;i<shown.size();i++)if(shown.get(i).key().equals(item.key()))index=i;
                    Button button=findButton(list.getChildAt(index-list.getFirstVisiblePosition()));
                    assertNotNull("Visible row download button required",button);assertTrue(button.isShown());assertTrue(button.isEnabled());button.performClick();
                } catch(Exception error){throw new AssertionError(error);}
            });
            long started=SystemClock.elapsedRealtime(),id=before;
            while(id==before&&SystemClock.elapsedRealtime()-started<30000){SystemClock.sleep(200);id=prefs.getLong("last-download",-1);}
            assertTrue("Click must enqueue a system download",id>=0&&id!=before);
            var manager=context.getSystemService(DownloadManager.class);long bytes=0,last=0;
            boolean done=false;
            while(SystemClock.elapsedRealtime()-started<150000&&!done) {
                try(var cursor=manager.query(new DownloadManager.Query().setFilterById(id))) {
                    assertTrue(cursor.moveToFirst());int state=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    int reason=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    bytes=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    assertNotEquals("System download failed, reason="+reason,DownloadManager.STATUS_FAILED,state);
                    done=state==DownloadManager.STATUS_SUCCESSFUL;
                    if(SystemClock.elapsedRealtime()-last>10000){status(new JSONObject().put("phase","system-download").put("bytes",bytes).put("state",state).put("reason",reason));last=SystemClock.elapsedRealtime();}
                }
                if(!done)SystemClock.sleep(500);
            }
            assertTrue("System download did not finish",done);assertEquals(item.size,bytes);
            long downloadMs=SystemClock.elapsedRealtime()-started;
            var disk=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];long saved=0;
            try(var input=context.getContentResolver().openInputStream(manager.getUriForDownloadedFile(id))){int n;while((n=input.read(buffer))>=0){disk.update(buffer,0,n);saved+=n;}}
            assertEquals(item.size,saved);
            var client=new DriveClient(new DriveClient.Cookies(){public String get(String url){return android.webkit.CookieManager.getInstance().getCookie(url);}public void set(String url,String value){android.webkit.CookieManager.getInstance().setCookie(url,value);}});
            var source=client.download(item);var connection=DriveClient.Net.open(source.url,provider,source.headers,null,null);
            var reference=MessageDigest.getInstance("SHA-256");long received=0,deadline=SystemClock.elapsedRealtime()+120000;
            try {assertEquals(200,connection.getResponseCode());try(var input=connection.getInputStream()){int n;while((n=input.read(buffer))>=0){assertTrue(SystemClock.elapsedRealtime()<deadline);reference.update(buffer,0,n);received+=n;}}}finally{connection.disconnect();}
            assertEquals(item.size,received);assertTrue("Saved file SHA-256 differs from original",MessageDigest.isEqual(disk.digest(),reference.digest()));
            instrumentation.runOnMainSync(()->{
                try {var field=MainActivity.class.getDeclaredField("player");field.setAccessible(true);assertNull("Download button must not start playback",((androidx.media3.exoplayer.ExoPlayer)field.get(main)).getCurrentMediaItem());
                    var update=MainActivity.class.getDeclaredMethod("updateDownloadStatus");update.setAccessible(true);update.invoke(main);
                    var label=MainActivity.class.getDeclaredField("downloadStatus");label.setAccessible(true);assertTrue(((TextView)label.get(main)).getText().toString().contains("下载完成"));
                }catch(Exception error){throw new AssertionError(error);}
            });
            status(new JSONObject().put("phase","complete").put("provider",provider).put("bytes",saved).put("elapsedMs",downloadMs).put("bytesPerSecond",saved*1000/Math.max(1,downloadMs)).put("sha256Matches",true).put("playbackStarted",false).put("savedInDownloads",true));
        } finally {instrumentation.runOnMainSync(()->{main.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);main.setTurnScreenOn(false);});}
    }
    private static Button findButton(View view){if(view instanceof Button button&&button.getText().toString().equals("下载"))return button;if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){var result=findButton(group.getChildAt(i));if(result!=null)return result;}return null;}
    private static void status(JSONObject row){var bundle=new android.os.Bundle();bundle.putString("stream","CLEARPLAY_DOWNLOAD_UI="+row+"\n");InstrumentationRegistry.getInstrumentation().sendStatus(2,bundle);}
}
