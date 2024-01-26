package tech.schober.vinylcast.audio;

import android.util.Pair;

import androidx.annotation.IntDef;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.CopyOnWriteArrayList;

import tech.schober.vinylcast.utils.VinylCastHelpers;
import timber.log.Timber;

public class AudioRecordStreamProvider implements AudioStreamProvider {

    private static final String TAG = "AudioRecorder";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({AUDIO_API_OPENSLES, AUDIO_API_AAUDIO})
    public @interface AudioApi {}
    public static final int AUDIO_API_OPENSLES = 1;
    public static final int AUDIO_API_AAUDIO = 2;

    public static final int AUDIO_DEVICE_ID_NONE = -1;
    public static final int AUDIO_DEVICE_ID_AUTO_SELECT = 0;

    protected int bufferSize;
    private CopyOnWriteArrayList<OutputStream> nativeAudioWriteStreams = new CopyOnWriteArrayList<>();

    public AudioRecordStreamProvider(int recordingDeviceId, int playbackDeviceId, boolean lowLatency, int bufferSize) {
        NativeAudioEngine.setRecordingDeviceId(recordingDeviceId);
        NativeAudioEngine.setPlaybackDeviceId(playbackDeviceId);
        NativeAudioEngine.setLowLatency(lowLatency);
        this.bufferSize = bufferSize;
    }

    public boolean start() {
        Timber.d("start");

        boolean preparedSuccess = NativeAudioEngine.prepareRecording();
        if (!preparedSuccess) {
            Timber.w("Failed to Prepare to Record.");
            return false;
        }
        Timber.d("Prepared to Record - sampleRate: %d, channel count: %d", NativeAudioEngine.getSampleRate(), NativeAudioEngine.getChannelCount());

        // callback from NativeAudioEngine with audioData will end up on own thread
        NativeAudioEngine.setAudioDataListener(new NativeAudioEngineListener() {
            @Override
            public void onAudioData(byte[] audioData) {
                //Log.v(TAG, "audioData.length: " + audioData.length);
                for (OutputStream writeStream : nativeAudioWriteStreams) {
                    try {
                        writeStream.write(audioData);
                        writeStream.flush();
                    } catch (IOException e) {
                        Timber.w(e,"Exception writing to raw audio output stream. Removing from list of streams.");
                        nativeAudioWriteStreams.remove(writeStream);
                    }
                }

                if (nativeAudioWriteStreams.isEmpty()) {
                    Timber.e("No open write streams.");
                }
            }
        });

        return NativeAudioEngine.startRecording();
    }

    public boolean stop() {
        Timber.d("stop");

        try {
            for (OutputStream writeStream : nativeAudioWriteStreams) {
                nativeAudioWriteStreams.remove(writeStream);
                writeStream.close();
            }
        } catch (IOException e) {
            Timber.e(e, "Exception closing streams");
        }

        return NativeAudioEngine.stopRecording();
    }

    @Override
    public InputStream getAudioInputStream() {
        Timber.d("getAudioInputStream");
        Pair<OutputStream, InputStream> audioStreams;
        try {
            audioStreams = VinylCastHelpers.getPipedAudioStreams(bufferSize);
        } catch (IOException e) {
            Timber.e(e, "Exception creating audio stream");
            return null;
        }
        nativeAudioWriteStreams.add(audioStreams.first);
        return audioStreams.second;
    }

    @Override
    public int getSampleRate() {
        return NativeAudioEngine.getSampleRate();
    }

    @Override
    public int getChannelCount() {
        return NativeAudioEngine.getChannelCount();
    }

    @Override
    public int getAudioEncoding() {
        return AUDIO_ENCODING_WAV;
    }

    public String getAudioApi() {
        switch(NativeAudioEngine.getAudioApi()) {
            case AUDIO_API_OPENSLES:
                return "OpenSL ES";
            case AUDIO_API_AAUDIO:
                return "AAudio";
            default:
                return "[not recording]";
        }
    }

    public static int getAudioRecordStreamSampleRate() {
        return NativeAudioEngine.getSampleRate();
    }

    public static int getAudioRecordStreamChannelCount() {
        return NativeAudioEngine.getChannelCount();
    }

    public static int getAudioRecordStreamBitRate() {
        return NativeAudioEngine.getBitRate();
    }

    public static @AudioEncoding int getAudioRecordStreamAudioEncoding() {
        return AUDIO_ENCODING_WAV;
    }
}
