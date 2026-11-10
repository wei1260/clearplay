package local.clearplay.android;

import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PlaybackTest {
    private static ExoPlayer player(MainActivity activity) {
        try { Field f = MainActivity.class.getDeclaredField("player"); f.setAccessible(true); return (ExoPlayer) f.get(activity); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static Button button(View root, String text) {
        if (root instanceof Button b && b.getText().toString().equals(text)) return b;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            Button b = button(group.getChildAt(i), text); if (b != null) return b;
        }
        return null;
    }
    private static void await(ActivityScenario<MainActivity> scenario, java.util.function.Predicate<MainActivity> condition) {
        long end = SystemClock.elapsedRealtime() + 20000;
        AtomicBoolean ready = new AtomicBoolean();
        while (SystemClock.elapsedRealtime() < end) {
            scenario.onActivity(a -> ready.set(condition.test(a)));
            if (ready.get()) return;
            SystemClock.sleep(150);
        }
        fail("Android playback condition timed out");
    }
    @Test public void nativeAudioVideoSpeedPauseSeekAndProgress() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        File fixture = new File(context.getCacheDir(), "clearplay-audio-test.webm");
        try (var input = instrumentation.getContext().getAssets().open("audio-video.webm"); var output = new java.io.FileOutputStream(fixture)) { input.transferTo(output); }
        context.getSharedPreferences("clearplay", 0).edit().clear().commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a -> {
                assertNotNull(button(a.getWindow().getDecorView(), "百度网盘"));
                assertNotNull(button(a.getWindow().getDecorView(), "夸克网盘"));
                a.importLocal(List.of(Uri.fromFile(fixture)));
            });
            await(scenario, a -> player(a).getPlaybackState() == Player.STATE_READY && player(a).getCurrentPosition() > 300 && player(a).getVideoSize().width == 640);
            await(scenario, a -> player(a).getAudioDecoderCounters() != null && player(a).getAudioDecoderCounters().renderedOutputBufferCount > 0);
            AtomicReference<Long> before = new AtomicReference<>();
            scenario.onActivity(a -> { player(a).setPlaybackSpeed(2); before.set(player(a).getCurrentPosition()); });
            SystemClock.sleep(1500);
            scenario.onActivity(a -> {
                long advanced = player(a).getCurrentPosition() - before.get();
                assertTrue("2x must advance real media time", advanced > 2300 && advanced < 4000);
                player(a).pause(); player(a).seekTo(6500);
            });
            await(scenario, a -> player(a).getPlaybackState() == Player.STATE_READY && player(a).getCurrentPosition() >= 6400);
            scenario.onActivity(a -> {
                assertFalse(player(a).getPlayWhenReady());
                assertNotNull(button(a.getWindow().getDecorView(), "全屏"));
                button(a.getWindow().getDecorView(), "全屏").performClick();
            });
            SystemClock.sleep(700);
            scenario.onActivity(a -> {
                assertNotNull(button(a.getWindow().getDecorView(), "退出全屏"));
                assertEquals(2f, player(a).getPlaybackParameters().speed, 0.001);
                button(a.getWindow().getDecorView(), "退出全屏").performClick();
            });
        } finally { fixture.delete(); }
        JSONArray history = new JSONArray(context.getSharedPreferences("clearplay", 0).getString("history", "[]"));
        assertTrue(history.length() > 0);
        assertTrue(history.getJSONObject(0).getLong("position") >= 6400);
    }
}
