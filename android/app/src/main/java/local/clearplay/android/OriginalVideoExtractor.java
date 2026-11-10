package local.clearplay.android;

import androidx.media3.common.C;
import androidx.media3.extractor.DiscardingTrackOutput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;

import java.io.IOException;

/** Baidu originals can store all audio fragments after the video fragments. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class OriginalVideoExtractor implements Extractor {
    private final Extractor delegate;
    OriginalVideoExtractor(Extractor delegate) { this.delegate = delegate; }
    @Override public boolean sniff(ExtractorInput input) throws IOException { return delegate.sniff(input); }
    @Override public void init(ExtractorOutput output) {
        delegate.init(new ExtractorOutput() {
            @Override public TrackOutput track(int id, int type) {
                // Independent authenticated audio is merged by the player instead.
                return type == C.TRACK_TYPE_AUDIO ? new DiscardingTrackOutput() : output.track(id, type);
            }
            @Override public void endTracks() { output.endTracks(); }
            @Override public void seekMap(SeekMap map) { output.seekMap(map); }
        });
    }
    @Override public int read(ExtractorInput input, PositionHolder position) throws IOException { return delegate.read(input, position); }
    @Override public void seek(long position, long timeUs) { delegate.seek(position, timeUs); }
    @Override public void release() { delegate.release(); }
    @Override public Extractor getUnderlyingImplementation() { return delegate.getUnderlyingImplementation(); }
}
