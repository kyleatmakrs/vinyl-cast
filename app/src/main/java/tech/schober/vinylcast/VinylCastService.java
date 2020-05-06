package tech.schober.vinylcast;

import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.session.MediaButtonReceiver;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.Session;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

import tech.schober.vinylcast.audio.AudioRecorder;
import tech.schober.vinylcast.audio.AudioVisualizer;
import tech.schober.vinylcast.audio.ConvertAudioTask;
import tech.schober.vinylcast.audio.NativeAudioEngine;
import tech.schober.vinylcast.server.HttpStreamServer;
import tech.schober.vinylcast.server.HttpStreamServerImpl;
import tech.schober.vinylcast.ui.main.MainActivity;
import tech.schober.vinylcast.utils.Helpers;

public class VinylCastService extends MediaBrowserServiceCompat {
    private static final String TAG = "VinylCastService";

    private static final String NOTIFICATION_CHANNEL_ID = "tech.schober.vinylcast.CHANNEL_ACTIVELY_RECORDING";
    private static final int NOTIFICATION_ID = 4242;

    public static final String ACTION_START_RECORDING = "tech.schober.vinylcast.ACTION_START_RECORDING";
    public static final String ACTION_STOP_RECORDING = "tech.schober.vinylcast.ACTION_STOP_RECORDING";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({AUDIO_ENCODING_WAV, AUDIO_ENCODING_AAC})
    public @interface AudioEncoding {}
    public static final int AUDIO_ENCODING_WAV = 0;
    public static final int AUDIO_ENCODING_AAC = 1;

    private static final int AUDIO_STREAM_BUFFER_SIZE = 8192;
    private static final int AUDIO_VISUALIZER_FFT_LENGTH = 256;
    private static final int AUDIO_VISUALIZER_FFT_BINS = 16;

    private final IBinder binder = new VinylCastBinder();
    private MainActivity mainActivity;

    private AudioRecorder audioRecorder;

    private Thread convertAudioThread;
    private @AudioEncoding int audioEncoding = AUDIO_ENCODING_WAV;

    private HttpStreamServer httpStreamServer;

    private AudioVisualizer audioVisualizer;

    private MediaSessionCompat mediaSession;
    private SessionManager castSessionManager;
    private SessionManagerListener castSessionManagerListener;

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class VinylCastBinder extends Binder {

        public MainActivity getMainActivity() {
            return VinylCastService.this.mainActivity;
        }

        public void setMainActivity(MainActivity activity) {
            VinylCastService.this.mainActivity = activity;
            updateMainActivity();
        }

        public void setAudioEncoding(@AudioEncoding int audioEncoding) {
            VinylCastService.this.audioEncoding = audioEncoding;
        }

        public void setRecordingDeviceId(int recordingDeviceId) {
            AudioRecorder.setRecordingDeviceId(recordingDeviceId);
        }

        public void setPlaybackDeviceId(int playbackDeviceId) {
            AudioRecorder.setPlaybackDeviceId(playbackDeviceId);
        }

        public InputStream getAudioInputStream() {
            if (isRecording()) {
                return audioRecorder.getAudioInputStream();
            } else {
                return null;
            }
        }

        public int getSampleRate() {
            return AudioRecorder.getSampleRate();
        }

        public int getChannelCount() {
            return AudioRecorder.getChannelCount();
        }

        public String getAudioApi() {
            return AudioRecorder.getAudioApi();
        }

        public int getAudioStreamBufferSize() {
            return AUDIO_STREAM_BUFFER_SIZE;
        }

        public HttpStreamServer getHttpStreamServer() {
            return httpStreamServer;
        }

        public void updateMediaSessionMetadata(String trackTitle, String artist, String album, BitmapDrawable albumImage) {
            MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, trackTitle)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1);
            if (albumImage != null) {
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumImage.getBitmap());
            }
            mediaSession.setMetadata(metadataBuilder.build());
            mediaSession.setActive(true);

            // Put the service in the foreground, post notification
            Helpers.createStopNotification(mediaSession, VinylCastService.this, VinylCastService.class, NOTIFICATION_CHANNEL_ID, NOTIFICATION_ID);

            // currently, only way to update Cast metadata is to re-send URL which causes reload of stream
            //castMedia(trackTitle, artist, album, imageUrl);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind");
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "onUnbind");
        return super.onUnbind(intent);
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");
        super.onCreate();

        // get Cast session manager
        castSessionManager = CastContext.getSharedInstance(this).getSessionManager();

        // Create a MediaSessionCompat
        mediaSession = new MediaSessionCompat(this, TAG);

        // Set an initial PlaybackState with ACTION_PLAY, so media buttons can start the player
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_STOP);
        mediaSession.setPlaybackState(stateBuilder.build());

        // MediaSessionCallback() has methods that handle callbacks from a media controller
        mediaSession.setCallback(mediaSessionCallback);

        // Set the session's token so that client activities can communicate with it.
        setSessionToken(mediaSession.getSessionToken());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (castSessionManagerListener == null) {
            castSessionManagerListener = new CastSessionManagerListener();
            castSessionManager.addSessionManagerListener(castSessionManagerListener);
        }

        String action = intent.getAction();
        Log.d(TAG, "onStartCommand received action: " + action);

        switch (action) {
            case VinylCastService.ACTION_START_RECORDING:
                start();
                break;
            case VinylCastService.ACTION_STOP_RECORDING:
                stop();
                break;
            default:
                MediaButtonReceiver.handleIntent(mediaSession, intent);
                break;
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        if (castSessionManagerListener != null) {
            castSessionManager.removeSessionManagerListener(castSessionManagerListener);
            castSessionManagerListener = null;
        }
        mediaSession.release();
        mediaSession = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        // Clients can connect, but since the BrowserRoot is an empty string
        // onLoadChildren will return nothing. This disables the ability to browse for content.
        return new BrowserRoot("", null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.sendResult(null);
        return;
    }


    private void start() {
        Log.i(TAG, "start");
        startAudioRecord();

        if (audioEncoding == AUDIO_ENCODING_AAC) {
            InputStream convertedAudioInputStream = startAudioConversion(audioRecorder.getAudioInputStream(), audioRecorder.getSampleRate(), audioRecorder.getChannelCount(), AUDIO_STREAM_BUFFER_SIZE);
            startHttpServer(convertedAudioInputStream, audioEncoding);
        } else {
            startHttpServer(audioRecorder.getAudioInputStream(), audioEncoding);
        }

        //startAudioRecognition();
        startAudioVisualizer(audioRecorder.getAudioInputStream(), audioRecorder.getSampleRate(), AUDIO_VISUALIZER_FFT_LENGTH, AUDIO_VISUALIZER_FFT_BINS);

        // put service in the foreground, post notification
        Helpers.createStopNotification(mediaSession, VinylCastService.this, VinylCastService.class, NOTIFICATION_CHANNEL_ID, NOTIFICATION_ID);

        updateMediaSession();
        updateMainActivity();
        updateCastSession();
    }

    private void stop() {
        Log.i(TAG, "stop");
        stopAudioVisualizer();
        stopAudioRecognition();
        stopHttpServer();
        stopAudioConversion();
        stopAudioRecord();

        // remove service from foreground
        stopForeground(true);

        updateMediaSession();
        updateMainActivity();
        updateCastSession();
    }

    public boolean isRecording() {
        return audioRecorder != null;
    }

    private void startAudioRecord() {
        audioRecorder = new AudioRecorder(AUDIO_STREAM_BUFFER_SIZE);
        audioRecorder.start();
    }

    private void stopAudioRecord() {
        audioRecorder.stop();
        audioRecorder = null;
    }

    private InputStream startAudioConversion(InputStream rawAudioInputStream, int convertedSampleRate, int convertedChannelCount, int audioBufferSize) {
        InputStream convertedAudioInputStream;
        try {
            ConvertAudioTask convertAudioTask = new ConvertAudioTask(rawAudioInputStream, convertedSampleRate, convertedChannelCount, audioBufferSize);
            convertedAudioInputStream = convertAudioTask.getAudioInputStream();
            convertAudioThread = new Thread(convertAudioTask, "ConvertAudio");
            convertAudioThread.start();
        } catch (IOException e) {
            Log.e(TAG, "Exception starting audio record task.", e);
            return null;
        }

        return convertedAudioInputStream;
    }

    private void stopAudioConversion() {
        if (convertAudioThread != null) {
            convertAudioThread.interrupt();
            convertAudioThread = null;
        }
    }

    private void startHttpServer(InputStream audioStream, @AudioEncoding int audioEncoding) {
        try {
            httpStreamServer = new HttpStreamServerImpl(this, HttpStreamServer.HTTP_SERVER_URL_PATH, HttpStreamServer.HTTP_SERVER_PORT, audioEncoding, audioStream, AUDIO_STREAM_BUFFER_SIZE);
            httpStreamServer.start();
        } catch (IOException e) {
            Log.e(TAG, "Exception creating webserver", e);
        }
    }

    private void stopHttpServer() {
        if (httpStreamServer != null) {
            httpStreamServer.stop();
            httpStreamServer = null;
        }
    }

    private void startAudioVisualizer(final InputStream rawAudioInputStream, final int sampleRate, int fftLength, int fftBins) {
        audioVisualizer = new AudioVisualizer(rawAudioInputStream, AUDIO_STREAM_BUFFER_SIZE, sampleRate, fftLength, fftBins);
        audioVisualizer.setAudioVisualizerListener(mainActivity);
        audioVisualizer.start();
    }

    private void stopAudioVisualizer() {
        if (audioVisualizer != null) {
            audioVisualizer.stop();
            audioVisualizer = null;
        }
    }

    private void startAudioRecognition() {
        Intent startRecognitionIntent = new Intent();
        startRecognitionIntent.setClassName(this, "tech.schober.audioacr.AudioRecognitionService");

        startService(startRecognitionIntent);
    }

    private void stopAudioRecognition() {
        Intent stopRecognitionIntent = new Intent();
        stopRecognitionIntent.setClassName(this, "tech.schober.audioacr.AudioRecognitionService");

        stopService(stopRecognitionIntent);
    }

    private void updateMediaSession() {
        int state = isRecording() ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_STOPPED;
        long action = isRecording() ? PlaybackStateCompat.ACTION_STOP : PlaybackStateCompat.ACTION_PLAY;
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .setActions(action)
                .build());
        mediaSession.setActive(isRecording());
    }

    private void updateCastSession() {
        if (castSessionManager.getCurrentCastSession() == null) {
            return;
        }
        if (castSessionManager.getCurrentCastSession().getRemoteMediaClient() == null) {
            return;
        }

        RemoteMediaClient remoteMediaClient = castSessionManager.getCurrentCastSession().getRemoteMediaClient();
        if (isRecording()) {
            MediaMetadata audioMetadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);
            String url = httpStreamServer.getStreamUrl();
            MediaInfo mediaInfo = new MediaInfo.Builder(url)
                    .setContentType(httpStreamServer.getContentType())
                    .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
                    .setStreamDuration(MediaInfo.UNKNOWN_DURATION)
                    .setMetadata(audioMetadata)
                    .build();
            Log.d(TAG, "Cast MediaInfo: " + mediaInfo);
            MediaLoadRequestData mediaLoadRequestData = new MediaLoadRequestData.Builder().setMediaInfo(mediaInfo).build();
            remoteMediaClient.load(mediaLoadRequestData);
        } else {
            remoteMediaClient.stop();
        }
    }

    private void updateMainActivity() {
        if (mainActivity != null) {
            mainActivity.updateRecordingState(isRecording());
            if (isRecording()) {
                mainActivity.setStatus(getString(R.string.status_recording) + "\n" + httpStreamServer.getStreamUrl(), true);
            } else {
                mainActivity.setStatus(getString(R.string.status_ready), true);
            }
        }
    }

    MediaSessionCompat.Callback mediaSessionCallback = new MediaSessionCompat.Callback() {
        @Override
        public void onPlay() {
            Log.d(TAG, "MediaSessionCompat onPlay");
            start();
        }

        @Override
        public void onStop() {
            Log.d(TAG, "MediaSessionCompat onStop");
            stop();
        }
    };

    private class CastSessionManagerListener implements SessionManagerListener {
        @Override
        public void onSessionStarting(Session session) {
        }

        @Override
        public void onSessionStarted(Session session, String sessionId) {
            Log.d(TAG, "Cast onSessionStarted");
            // cast session started after service already started so trigger updateCastSession
            updateCastSession();
        }

        @Override
        public void onSessionStartFailed(Session session, int i) {
        }

        @Override
        public void onSessionEnding(Session session) {
        }

        @Override
        public void onSessionResumed(Session session, boolean wasSuspended) {
        }

        @Override
        public void onSessionResumeFailed(Session session, int i) {
        }

        @Override
        public void onSessionSuspended(Session session, int i) {
        }

        @Override
        public void onSessionEnded(Session session, int error) {
        }

        @Override
        public void onSessionResuming(Session session, String s) {
        }
    }
}
