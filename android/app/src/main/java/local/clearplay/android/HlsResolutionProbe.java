package local.clearplay.android;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.DiscardingTrackOutput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ForwardingTrackOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;

/** Confirms actual Baidu resolution; requesting 2K can silently return a 720P stream. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class HlsResolutionProbe {
    static int height(DriveClient.Source source) throws Exception {
        String base = source.url, manifest = source.manifest;
        for (int depth = 0; depth < 3; depth++) {
            String first = java.util.Arrays.stream(manifest.split("\n")).map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("#")).findFirst().orElseThrow();
            String url = DriveClient.Net.reference(base).resolve(DriveClient.Net.reference(first)).toString();
            if (manifest.contains("#EXT-X-STREAM-INF:")) {
                var connection = DriveClient.Net.open(url, source.provider, source.headers, null, null);
                try { manifest = DriveClient.Net.text(connection.getInputStream()); base = connection.getURL().toString(); }
                finally { connection.disconnect(); }
                continue;
            }
            if (manifest.contains("#EXT-X-KEY:") || manifest.contains("#EXT-X-MAP:")) return 0;
            var connection = DriveClient.Net.open(url, source.provider, source.headers, null, "bytes=0-262143");
            final int[] height = {0};
            var extractor = new TsExtractor(TsExtractor.MODE_HLS, new TimestampAdjuster(0), new DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_IGNORE_AAC_STREAM));
            try (var stream = connection.getInputStream()) {
                extractor.init(new ExtractorOutput() {
                    @Override public TrackOutput track(int id, int type) {
                        return new ForwardingTrackOutput(new DiscardingTrackOutput()) {
                            @Override public void format(Format format) { if (type == C.TRACK_TYPE_VIDEO && format.height > 0) height[0] = format.height; }
                        };
                    }
                    @Override public void endTracks() { }
                    @Override public void seekMap(SeekMap map) { }
                });
                var input = new DefaultExtractorInput(stream::read, 0, C.LENGTH_UNSET);
                if (!extractor.sniff(input)) return 0;
                input.resetPeekPosition();
                var position = new PositionHolder();
                while (height[0] == 0 && input.getPosition() < 262144 && extractor.read(input, position) != C.RESULT_END_OF_INPUT) { }
                return height[0];
            } finally { extractor.release(); connection.disconnect(); }
        }
        return 0;
    }
}
