package net.programmierecke.radiodroid2.players.exoplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.preference.PreferenceManager;
import android.util.Log;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.audio.AudioAttributes.Builder;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.audio.AudioSink;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;

import net.programmierecke.radiodroid2.R;
import net.programmierecke.radiodroid2.data.ShoutcastInfo;
import net.programmierecke.radiodroid2.data.StreamLiveInfo;
import net.programmierecke.radiodroid2.players.PlayerWrapper;
import net.programmierecke.radiodroid2.players.RadioPlayer;
import net.programmierecke.radiodroid2.recording.RecordableListener;

import java.util.Map;

import okhttp3.OkHttpClient;

public class ExoPlayerWrapper implements PlayerWrapper, IcyDataSource.IcyDataSourceListener {

    final private String TAG = "ExoPlayerWrapper";

    private SimpleExoPlayer player;
    private PlayListener stateListener;

    private String streamUrl;

    private DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();

    private RecordableListener recordableListener;

    private long totalTransferredBytes;
    private long currentPlaybackTransferredBytes;

    private boolean isHls;

    @Override
    public void playRemote(@NonNull OkHttpClient httpClient, @NonNull String streamUrl, @NonNull Context context, boolean isAlarm) {
        // I don't know why, but it is still possible that streamUrl is null,
        // I still get exceptions from this from google
        if (streamUrl == null) {
            return;
        }
        if (!streamUrl.equals(this.streamUrl)) {
            currentPlaybackTransferredBytes = 0;
        }

        this.streamUrl = streamUrl;

        stateListener.onStateChanged(RadioPlayer.PlayState.PrePlaying);

        if (player != null) {
            player.stop();
        }

        if (player == null) {
            TrackSelection.Factory videoTrackSelectionFactory = new AdaptiveTrackSelection.Factory(bandwidthMeter);
            TrackSelector trackSelector = new DefaultTrackSelector(videoTrackSelectionFactory);

            player = ExoPlayerFactory.newSimpleInstance(new DefaultRenderersFactory(context), trackSelector);
            //player.setAudioStreamType(isAlarm ? AudioManager.STREAM_ALARM : AudioManager.STREAM_MUSIC);
            //player.setAudioStreamType(isAlarm ? C.STREAM_TYPE_ALARM : C.STREAM_TYPE_MUSIC);

            //instead of setAudioStreamType
            player.setAudioAttributes(isAlarm ?
                    new Builder().setUsage(C.USAGE_ALARM).build() :
                    new Builder().setContentType(C.CONTENT_TYPE_MUSIC).build());

            player.addListener(new ExoPlayerListener());
            player.setAudioDebugListener(new AudioEventListener());
        }

        isHls = streamUrl.endsWith(".m3u8");

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        final int retryTimeout = prefs.getInt("settings_retry_timeout", 4);
        final int retryDelay = prefs.getInt("settings_retry_delay", 10);

        DataSource.Factory dataSourceFactory = new RadioDataSourceFactory(httpClient, bandwidthMeter, this, retryTimeout, retryDelay);
        // Produces Extractor instances for parsing the media data.
        ExtractorsFactory extractorsFactory = new DefaultExtractorsFactory();

        if (!isHls) {
            MediaSource audioSource = new ExtractorMediaSource(Uri.parse(streamUrl), dataSourceFactory, extractorsFactory, null, null);
            player.prepare(audioSource);
        } else {
            MediaSource audioSource = new HlsMediaSource(Uri.parse(streamUrl), dataSourceFactory, null, null);
            player.prepare(audioSource);
        }

        player.setPlayWhenReady(true);

        // State changed will be called when audio session id is available.
    }

    @Override
    public void pause() {
        Log.i(TAG, "Pause. Stopping exoplayer.");

        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
    }

    @Override
    public void stop() {
        Log.i(TAG, "Stopping exoplayer.");

        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }

        stopRecording();
    }

    @Override
    public boolean isPlaying() {
        return player != null && player.getPlayWhenReady();
    }

    @Override
    public long getBufferedMs() {
        if (player != null) {
            return (int) (player.getBufferedPosition() - player.getCurrentPosition());
        }

        return 0;
    }

    @Override
    public int getAudioSessionId() {
        if (player != null) {
            return player.getAudioSessionId();
        }
        return 0;
    }

    @Override
    public long getTotalTransferredBytes() {
        return totalTransferredBytes;
    }

    @Override
    public long getCurrentPlaybackTransferredBytes() {
        return currentPlaybackTransferredBytes;
    }

    @Override
    public void setVolume(float newVolume) {
        if (player != null) {
            player.setVolume(newVolume);
        }
    }

    @Override
    public void setStateListener(PlayListener listener) {
        stateListener = listener;
    }

    @Override
    public void onDataSourceConnected() {

    }

    @Override
    public void onDataSourceConnectionLost() {

    }

    @Override
    public void onDataSourceConnectionLostIrrecoverably() {
        stop();
        stateListener.onStateChanged(RadioPlayer.PlayState.Idle);
        stateListener.onPlayerError(R.string.error_stream_reconnect_timeout);
    }

    @Override
    public void onDataSourceShoutcastInfo(@Nullable ShoutcastInfo shoutcastInfo) {
        stateListener.onDataSourceShoutcastInfo(shoutcastInfo, false);
    }

    @Override
    public void onDataSourceStreamLiveInfo(StreamLiveInfo streamLiveInfo) {
        stateListener.onDataSourceStreamLiveInfo(streamLiveInfo);
    }

    @Override
    public void onDataSourceBytesRead(byte[] buffer, int offset, int length) {
        totalTransferredBytes += length;
        currentPlaybackTransferredBytes += length;

        if (recordableListener != null) {
            recordableListener.onBytesAvailable(buffer, offset, length);
        }
    }

    @Override
    public boolean canRecord() {
        return player != null;
    }

    @Override
    public void startRecording(@NonNull RecordableListener recordableListener) {
        this.recordableListener = recordableListener;
    }

    @Override
    public void stopRecording() {
        if (recordableListener != null) {
            recordableListener.onRecordingEnded();
            recordableListener = null;
        }
    }

    @Override
    public boolean isRecording() {
        return recordableListener != null;
    }

    @Override
    public Map<String, String> getNameFormattingArgs() {
        return null;
    }

    @Override
    public String getExtension() {
        return isHls ? "ts" : "mp3";
    }

    private class ExoPlayerListener implements Player.EventListener {

        /**
         * Called when the timeline and/or manifest has been refreshed.
         * <p>
         * Note that if the timeline has changed then a position discontinuity may also have occurred.
         * For example, the current period index may have changed as a result of periods being added or
         * removed from the timeline. This will <em>not</em> be reported via a separate call to
         * {@link #onPositionDiscontinuity(int)}.
         *
         * @param timeline The latest timeline. Never null, but may be empty.
         * @param manifest The latest manifest. May be null.
         * @param reason   The {@link Player.TimelineChangeReason} responsible for this timeline change.
         */
        @Override
        public void onTimelineChanged(Timeline timeline, Object manifest, int reason) {
            // Do nothing
        }

        @Override
        public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
            // Do nothing
        }

        @Override
        public void onLoadingChanged(boolean isLoading) {
            // Do nothing
        }

        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            // Do nothing
        }

        @Override
        public void onRepeatModeChanged(int repeatMode) {
            // Do nothing
        }

        /**
         * Called when the value of {@link Player.getShuffleModeEnabled} changes.
         *
         * @param shuffleModeEnabled Whether shuffling of windows is enabled.
         */
        @Override
        public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {

        }

        @Override
        public void onPlayerError(ExoPlaybackException error) {
            // Stop playing since it is either irrecoverable error in the player or our data source failed to reconnect.

            stop();
            stateListener.onStateChanged(RadioPlayer.PlayState.Idle);
            stateListener.onPlayerError(R.string.error_play_stream);
        }

        /**
         * Called when a position discontinuity occurs without a change to the timeline. A position
         * discontinuity occurs when the current window or period index changes (as a result of playback
         * transitioning from one period in the timeline to the next), or when the playback position
         * jumps within the period currently being played (as a result of a seek being performed, or
         * when the source introduces a discontinuity internally).
         * <p>
         * When a position discontinuity occurs as a result of a change to the timeline this method is
         * <em>not</em> called. {@link #onTimelineChanged(Timeline, Object, int)} is called in this
         * case.
         *
         * @param reason The {@link Player.DiscontinuityReason} responsible for the discontinuity.
         */
        @Override
        public void onPositionDiscontinuity(int reason) {

        }

        @Override
        public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
            // Do nothing
        }

        /**
         * Called when all pending seek requests have been processed by the player. This is guaranteed
         * to happen after any necessary changes to the player state were reported to
         * {@link #onPlayerStateChanged(boolean, int)}.
         */
        @Override
        public void onSeekProcessed() {

        }
    }

    private class AudioEventListener implements AudioRendererEventListener {
        @Override
        public void onAudioEnabled(DecoderCounters counters) {

        }

        @Override
        public void onAudioSessionId(int audioSessionId) {
            stateListener.onStateChanged(RadioPlayer.PlayState.Playing);
        }

        @Override
        public void onAudioDecoderInitialized(String decoderName, long initializedTimestampMs, long initializationDurationMs) {

        }

        @Override
        public void onAudioInputFormatChanged(Format format) {

        }

        /**
         * Called when an {@link AudioSink} underrun occurs.
         *
         * @param bufferSize             The size of the {@link AudioSink}'s buffer, in bytes.
         * @param bufferSizeMs           The size of the {@link AudioSink}'s buffer, in milliseconds, if it is
         *                               configured for PCM output. {@link C#TIME_UNSET} if it is configured for passthrough output,
         *                               as the buffered media can have a variable bitrate so the duration may be unknown.
         * @param elapsedSinceLastFeedMs The time since the {@link AudioSink} was last fed data.
         */
        @Override
        public void onAudioSinkUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {

        }

        @Override
        public void onAudioDisabled(DecoderCounters counters) {

        }
    }
}
