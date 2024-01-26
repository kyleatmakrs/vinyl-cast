package tech.schober.vinylcast.audio;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Process;
import android.util.Pair;

import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import tech.schober.vinylcast.utils.VinylCastHelpers;
import timber.log.Timber;

/**
 * Runnable used to convert raw PCM audio data from rawAudioInputStream to an AAC ADTS input stream.
 * Based on https://stackoverflow.com/questions/18862715/how-to-generate-the-aac-adts-elementary-stream-with-android-mediacodec
 */
public class ConvertAudioStreamProvider implements Runnable, AudioStreamProvider {
    private static final String TAG = "ConvertAudioTask";

    private static final String CODEC_MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int CODEC_BIT_RATE = 192000;
    private static final long CODEC_TIMEOUT = 10000;
    private static final boolean CODEC_VERBOSE = false;

    // ADTS Header Information from https://wiki.multimedia.cx/index.php/ADTS
    private static final int ADTS_HEADER_AUDIO_OBJECT_TYPE = 2; // AAC LC
    private static final int ADTS_HEADER_SAMPLE_RATE_INDEX = 3; // 3 = 48000, 4 = 44100
    private static final int ADTS_HEADER_CHANNEL_CONFIG = 2; // 2 Channel

    private InputStream inputAudioStream;
    private int sampleRate;
    private int channelCount;
    private OutputStream convertedAudioWriteStream;
    private InputStream convertedAudioReadStream;

    /**
     * Create a ConvertAudioTask
     * @param rawAudioStream
     * @param bufferSize
     */
    public ConvertAudioStreamProvider(AudioStreamProvider rawAudioStream, int bufferSize) throws IOException {
        this.inputAudioStream = rawAudioStream.getAudioInputStream();
        this.sampleRate = rawAudioStream.getSampleRate();
        this.channelCount = rawAudioStream.getChannelCount();
        Timber.d("ConvertAudioTask - sampleRate: %d, channel count: %d", sampleRate, channelCount);

        Pair<OutputStream, InputStream> convertedAudioStreams = VinylCastHelpers.getPipedAudioStreams(bufferSize);
        this.convertedAudioWriteStream = convertedAudioStreams.first;
        this.convertedAudioReadStream = convertedAudioStreams.second;
    }

    /**
     * Handle providing raw audio to MediaCodec InputBuffer
     * @param codec
     * @param inputBufferId
     * @return number bytes provided
     * @throws IOException
     */
    private int queueCodecInputBuffer(MediaCodec codec, int inputBufferId) throws IOException {
        ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferId);
        inputBuffer.clear();

        int bytesAvailable = inputAudioStream.available();
        int bytesToWrite = bytesAvailable < inputBuffer.limit() ? bytesAvailable : inputBuffer.limit();

        inputBuffer.put(IOUtils.toByteArray(inputAudioStream, bytesToWrite));
        codec.queueInputBuffer(inputBufferId, 0, bytesToWrite, 0, 0);
        return bytesToWrite;
    }

    /**
     * Handle reading encoded audio from MediaCodec OutputBuffer
     * @param codec
     * @param outputBufferId
     * @param info
     * @return number bytes read
     * @throws IOException
     */
    private int dequeueCodecOutputBuffer(MediaCodec codec, int outputBufferId, MediaCodec.BufferInfo info) throws IOException {
        int outBitsSize = info.size;
        int outPacketSize = outBitsSize + 7;    // 7 is ADTS header size
        ByteBuffer outBuf = codec.getOutputBuffer(outputBufferId);

        outBuf.position(info.offset);
        outBuf.limit(info.offset + outBitsSize);

        byte[] packet = new byte[outPacketSize];
        addADTStoPacket(packet, outPacketSize);
        outBuf.get(packet, 7, outBitsSize);

        convertedAudioWriteStream.write(packet, 0, outPacketSize);
        convertedAudioWriteStream.flush();

        outBuf.clear();
        codec.releaseOutputBuffer(outputBufferId, false);

        return outBitsSize;
    }

    /**
     * Add ADTS header at the beginning of each and every AAC packet.
     * This is needed as MediaCodec encoder generates a packet of raw
     * AAC data.
     * <p>
     * Note the packetLen must count in the ADTS header itself.
     **/
    private void addADTStoPacket(byte[] packet, int packetLen) {
        int profile = ADTS_HEADER_AUDIO_OBJECT_TYPE;
        int freqIdx = ADTS_HEADER_SAMPLE_RATE_INDEX;
        int chanCfg = ADTS_HEADER_CHANNEL_CONFIG;

        // fill in ADTS data
        packet[0] = (byte) 0xFF;
        packet[1] = (byte) 0xF9;
        packet[2] = (byte) (((profile - 1) << 6) + (freqIdx << 2) + (chanCfg >> 2));
        packet[3] = (byte) (((chanCfg & 3) << 6) + (packetLen >> 11));
        packet[4] = (byte) ((packetLen & 0x7FF) >> 3);
        packet[5] = (byte) (((packetLen & 7) << 5) + 0x1F);
        packet[6] = (byte) 0xFC;
    }

    @Override
    public void run() {
        Timber.d("starting...");
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

        MediaFormat format = MediaFormat.createAudioFormat(CODEC_MIME_TYPE, sampleRate, channelCount);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, CODEC_BIT_RATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CODEC_BIT_RATE);

        MediaCodec codec;
        try {
            codec = MediaCodec.createEncoderByType(CODEC_MIME_TYPE);
        } catch (IOException e) {
            Timber.e(e, "Exception creating codec");
            return;
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        codec.start();
        int numBytesSubmitted = 0;
        int numBytesDequeued = 0;
        int bufferId;

        while (!Thread.currentThread().isInterrupted()) {
            // MediaCodec InputBuffer
            bufferId = codec.dequeueInputBuffer(CODEC_TIMEOUT);
            try {
                if (bufferId != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    int size = queueCodecInputBuffer(codec, bufferId);
                    numBytesSubmitted += size;
                    if (CODEC_VERBOSE && size > 0) {
                        Timber.d("queued %d bytes of input data.", size);
                    }
                }
            } catch (IOException e) {
                Timber.e(e, "Exception queuing input buffer. Queuing End Of Stream.");
                codec.queueInputBuffer(bufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }

            // MediaCodec OutputBuffer
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            bufferId = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                Timber.d("Dequeued End Of Stream.");
                break;
            } else {
                try {
                    if (bufferId >= 0) {
                        int outBitsSize = dequeueCodecOutputBuffer(codec, bufferId, info);
                        numBytesDequeued += outBitsSize;
                        if (CODEC_VERBOSE) {
                            Timber.d("  dequeued " + outBitsSize + " bytes of output data.");
                        }
                    }
                } catch (InterruptedIOException e) {
                    Timber.d("interrupted");
                    break;
                } catch (IOException e) {
                    Timber.e(e, "Exception dequeuing output buffer");
                    break;
                }
            }
        }

        if (CODEC_VERBOSE) {
            Timber.d("queued a total of %d bytes, dequeued %d bytes.", numBytesSubmitted, numBytesDequeued);
        }
        int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int inBitrate = sampleRate * channelCount * 16;  // bit/sec
        int outBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE);
        float desiredRatio = (float) outBitrate / (float) inBitrate;
        float actualRatio = (float) numBytesDequeued / (float) numBytesSubmitted;
        if (actualRatio < 0.9 * desiredRatio || actualRatio > 1.1 * desiredRatio) {
            Timber.w("desiredRatio = %f, actualRatio = %f", desiredRatio, actualRatio);
        }
        codec.stop();
        codec.release();

        Timber.d("stopping...");
        try {
            convertedAudioWriteStream.close();
        } catch (IOException e) {
            Timber.e(e, "Exception closing streams");
        }
    }

    @Override
    public InputStream getAudioInputStream() {
        return convertedAudioReadStream;
    }

    @Override
    public int getSampleRate() {
        return sampleRate;
    }

    @Override
    public int getChannelCount() {
        return channelCount;
    }

    @Override
    public int getAudioEncoding() {
        return AUDIO_ENCODING_AAC;
    }

    public static int getConvertAudioStreamSampleRate() {
        return NativeAudioEngine.getSampleRate();
    }

    public static int getConvertAudioStreamChannelCount() {
        return NativeAudioEngine.getChannelCount();
    }

    public static int getConvertAudioStreamBitRate() {
        return CODEC_BIT_RATE;
    }

    public static @AudioEncoding int getConvertAudioStreamAudioEncoding() {
        return AUDIO_ENCODING_AAC;
    }
}
