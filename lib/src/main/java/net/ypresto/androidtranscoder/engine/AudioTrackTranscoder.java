package net.ypresto.androidtranscoder.engine;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import net.ypresto.androidtranscoder.TLog;

import net.ypresto.androidtranscoder.compat.MediaCodecBufferCompatWrapper;
import net.ypresto.androidtranscoder.utils.MediaExtractorUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import static net.ypresto.androidtranscoder.engine.AudioChannel.BUFFER_INDEX_END_OF_STREAM;

public class AudioTrackTranscoder implements TrackTranscoder {
    private static final String TAG = "AudioTrackTranscoder";
    private static final QueuedMuxer.SampleType SAMPLE_TYPE = QueuedMuxer.SampleType.AUDIO;
    private static final long BUFFER_LEAD_TIME = 100000; // Amount we will let other decoders get ahead

    private static final int DRAIN_STATE_NONE = 0;
    private static final int DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY = 1;
    private static final int DRAIN_STATE_CONSUMED = 2;

    private final LinkedHashMap<String, MediaExtractor> mExtractors;
    private final QueuedMuxer mMuxer;

    private LinkedHashMap<String, Integer> mTrackIndexes;
    private final LinkedHashMap<String, MediaFormat> mInputFormat;
    private final MediaFormat mOutputFormat;

    private MediaCodec mEncoder;
    private MediaFormat mActualOutputFormat;

    private HashMap<String, MediaCodecBufferCompatWrapper> mDecoderBuffers;
    private MediaCodecBufferCompatWrapper mEncoderBuffers;

    private boolean mIsEncoderEOS;
    private boolean mEncoderStarted;

    private AudioChannel mAudioChannel;
    private boolean mIsSegmentFinished;
    private boolean mIsLastSegment = false;
    private long mOutputPresentationTimeDecodedUs = 0l;
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    public AudioTrackTranscoder(LinkedHashMap<String, MediaExtractor> extractor,
                                MediaFormat outputFormat, QueuedMuxer muxer) {
        mExtractors = extractor;
        mTrackIndexes = new LinkedHashMap<String, Integer>();
        mOutputFormat = outputFormat;
        mMuxer = muxer;
        mInputFormat = new LinkedHashMap<String, MediaFormat>();
    }
    /**
     * Wraps an extractor -> decoder -> output surface that corresponds to an input channel.
     * The extractor is passed in when created and the start should be called when a segment
     * is found that needs the wrapper.
     */
    private class DecoderWrapper {
        private boolean mIsExtractorEOS;
        private boolean mIsDecoderEOS;
        private boolean mIsSegmentEOS;
        private boolean mDecoderStarted;
        private MediaExtractor mExtractor;
        private MediaCodecBufferCompatWrapper mDecoderInputBuffers;
        private MediaCodec mDecoder;
        private Integer mTrackIndex;
        boolean mBufferRequeued;
        private Long mPresentationTimeRequeued = null;
        int mResult;
        private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
        DecoderWrapper(MediaExtractor mediaExtractor) {
            mExtractor = mediaExtractor;
        }

        private void start() {
            MediaExtractorUtils.TrackResult trackResult = MediaExtractorUtils.getFirstVideoAndAudioTrack(mExtractor);
            if (trackResult.mAudioTrackFormat != null) {
                int trackIndex = trackResult.mAudioTrackIndex;
                mTrackIndex = trackIndex;
                mExtractor.selectTrack(trackIndex);
                MediaFormat inputFormat = mExtractor.getTrackFormat(trackIndex);

                try {
                    mDecoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                mDecoder.configure(inputFormat, null, null, 0);
                mDecoder.start();
                mDecoderStarted = true;
                mDecoderInputBuffers =  new MediaCodecBufferCompatWrapper(mDecoder);
            }
        }
        private int dequeueOutputBuffer(long timeoutUs) {
            if (!mBufferRequeued)
                mResult = mDecoder.dequeueOutputBuffer(mBufferInfo, timeoutUs);
            mBufferRequeued = false;
            return mResult;
        }
        private void requeueOutputBuffer() {
            mBufferRequeued = true;
        }
        private void release() {
            if (mDecoder != null) {
                mDecoder.stop();
                mDecoder.release();
                mDecoder = null;
            }
        }

    };
    LinkedHashMap<String, DecoderWrapper> mDecoderWrappers = new LinkedHashMap<String, DecoderWrapper>();

    @Override
    public void setupEncoder() {

        try {
            mEncoder = MediaCodec.createEncoderByType(mOutputFormat.getString(MediaFormat.KEY_MIME));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        mEncoder.configure(mOutputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoder.start();
        mEncoderStarted = true;
        mEncoderBuffers = new MediaCodecBufferCompatWrapper(mEncoder);

    }
    private void createWrapperSlot (TimeLine.Segment segment) {
        if (mDecoderWrappers.keySet().size() < 2)
            return;

        // Release any inactive decoders
        Iterator<Map.Entry<String, DecoderWrapper>> iterator = mDecoderWrappers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, DecoderWrapper> decoderWrapperEntry = iterator.next();
            if (!segment.getAudioChannels().containsKey(decoderWrapperEntry.getKey())) {
                segment.timeLine().getChannels().get(decoderWrapperEntry.getKey()).mInputEndTimeUs = 0l;
                decoderWrapperEntry.getValue().release();
                iterator.remove();
                TLog.d(TAG, "Releasing Audio Decoder " + decoderWrapperEntry.getKey());
                return;
            }
        }

    }
    @Override
    public void setupDecoders(TimeLine.Segment segment, MediaTranscoderEngine.TranscodeThrottle throttle) {

        TLog.d(TAG, "Setting up Audio Decoders for segment at " + segment.mOutputStartTimeUs + " for a duration of " + segment.getDuration());

        LinkedHashMap<String, MediaCodec> decoders = new LinkedHashMap<String, MediaCodec>();

        // Start any decoders being opened for the first time
        for (Map.Entry<String, TimeLine.InputChannel> entry : segment.getAudioChannels().entrySet()) {
            TimeLine.InputChannel inputChannel = entry.getValue();
            String channelName = entry.getKey();

            DecoderWrapper decoderWrapper = mDecoderWrappers.get(channelName);
            if (decoderWrapper == null) {
                createWrapperSlot(segment);
                decoderWrapper = new DecoderWrapper(mExtractors.get(channelName));
                mDecoderWrappers.put(channelName, decoderWrapper);
                throttle.participate("Audio" + channelName);
            }
            if (!decoderWrapper.mDecoderStarted) {
                decoderWrapper.start();
            }
            if (decoderWrapper.mIsDecoderEOS) {
                TLog.d(TAG, "Audio Decoder " + channelName + " is at EOS -- dropping");
            } else {
                TLog.d(TAG, "Audio Decoder " + channelName + " at offset " + inputChannel.mInputOffsetUs + " starting at " + inputChannel.mInputStartTimeUs + " ending at " + inputChannel.mInputEndTimeUs);
                decoderWrapper.mIsSegmentEOS = false;
                decoders.put(entry.getKey(), decoderWrapper.mDecoder);
            }
        }

        // Setup an audio channel that will mix from multiple decoders
        mAudioChannel = mAudioChannel == null ? new AudioChannel(decoders, mEncoder, mOutputFormat) :
                        mAudioChannel.createFromExisting(decoders, mEncoder, mOutputFormat);
        mIsSegmentFinished = false;
        mIsEncoderEOS = false;
        mIsLastSegment = segment.isLastSegment;
        TLog.d(TAG, "starting an audio segment");
    }

    @Override
    public MediaFormat getDeterminedFormat() {
        return mActualOutputFormat;
    }

    @Override
    public boolean stepPipeline(TimeLine.Segment outputSegment, MediaTranscoderEngine.TranscodeThrottle throttle) {
        boolean stepped = false;
        Long presentationTimeUs;

        int status;
        while (drainEncoder(0) != DRAIN_STATE_NONE) stepped = true;
        do {
            status = drainDecoder(outputSegment, 0, throttle);
            if (status != DRAIN_STATE_NONE) stepped = true;
            // NOTE: not repeating to keep from deadlock when encoder is full.
        } while (status == DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY);

        while ((presentationTimeUs = mAudioChannel.feedEncoder(0)) != null) {
            if (presentationTimeUs >= 0) {
                TLog.d(TAG, "Encoded audio from " + mOutputPresentationTimeDecodedUs + " to " + presentationTimeUs);
                mOutputPresentationTimeDecodedUs = Math.max(mOutputPresentationTimeDecodedUs, presentationTimeUs);
            } else {
                for (Map.Entry<String, DecoderWrapper> decoderWrapperEntry : mDecoderWrappers.entrySet()) {
                    decoderWrapperEntry.getValue().mIsSegmentEOS = true;
                }
            }
            stepped = true;
        }
        while (drainExtractors(outputSegment, 0) != DRAIN_STATE_NONE) stepped = true;

        return stepped;
    }

    private int drainExtractors(TimeLine.Segment segment, long timeoutUs) {

        boolean sampleProcessed = false;

        for (Map.Entry<String, TimeLine.InputChannel> inputChannelEntry : segment.getAudioChannels().entrySet()) {

            DecoderWrapper decoderWrapper = mDecoderWrappers.get(inputChannelEntry.getKey());
            String channelName = inputChannelEntry.getKey();
            if (!decoderWrapper.mIsExtractorEOS) {

                // Find out which track the extractor has samples for next
                int trackIndex = decoderWrapper.mExtractor.getSampleTrackIndex();

                // Sample is for a different track (like audio) ignore
                if (trackIndex >= 0 && trackIndex != decoderWrapper.mTrackIndex) {
                    if (inputChannelEntry.getValue().mChannelType == TimeLine.ChannelType.AUDIO)
                        decoderWrapper.mExtractor.advance(); // Skip video
                    continue;
                }

                // Get buffer index to be filled
                int result = decoderWrapper.mDecoder.dequeueInputBuffer(timeoutUs);

                // If no buffers available ignore
                if (result < 0)
                    continue;

                // If end of stream
                if (trackIndex < 0) {
                    decoderWrapper.mIsExtractorEOS = true;
                    decoderWrapper.mDecoder.queueInputBuffer(result, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    continue;
                }
                int sampleSize = decoderWrapper.mExtractor.readSampleData(decoderWrapper.mDecoderInputBuffers.getInputBuffer(result), 0);
                long sampleTime = decoderWrapper.mExtractor.getSampleTime();
                boolean isKeyFrame = (decoderWrapper.mExtractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
                decoderWrapper.mDecoder.queueInputBuffer(result, 0, sampleSize, decoderWrapper.mExtractor.getSampleTime(), isKeyFrame ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0);

                decoderWrapper.mExtractor.advance();
                sampleProcessed = true;

                // Seek at least to previous key frame if needed cause it's a lot faster
                TimeLine.SegmentChannel segmentChannel = segment.getSegmentChannel(channelName);
                Long seek = segmentChannel.getAudioSeek();
                if (seek != null && sampleTime < seek) {
                    decoderWrapper.mExtractor.seekTo(seek, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    segmentChannel.seekRequestedAudio(); // So we don't repeat
                    TLog.d(TAG, "Extractor Seek " + seek);
                }

            }
        }
        return  sampleProcessed ? DRAIN_STATE_CONSUMED : DRAIN_STATE_NONE;
    }

    private int drainDecoder(TimeLine.Segment segment, long timeoutUs, MediaTranscoderEngine.TranscodeThrottle throttle) {

        boolean consumed = false;

        // Go through each decoder in the segment to get a buffer to process
        for (Map.Entry<String, TimeLine.InputChannel> inputChannelEntry : segment.getAudioChannels().entrySet()) {

            String channelName = inputChannelEntry.getKey();
            TimeLine.InputChannel inputChannel = inputChannelEntry.getValue();
            DecoderWrapper decoderWrapper = mDecoderWrappers.get(inputChannelEntry.getKey());

            // Only process if we have not end end of stream for this decoder or extractor
            if (!decoderWrapper.mIsDecoderEOS && !decoderWrapper.mIsSegmentEOS &&
                (decoderWrapper.mPresentationTimeRequeued == null || throttle.canProceed("Audio" + channelName, decoderWrapper.mPresentationTimeRequeued))) {
                decoderWrapper.mPresentationTimeRequeued =  null;

                if (inputChannel.mInputEndTimeUs != null &&
                        mOutputPresentationTimeDecodedUs - inputChannel.mInputOffsetUs > inputChannel.mInputEndTimeUs) {
                    decoderWrapper.mIsSegmentEOS = true;
                    TLog.d(TAG, "End of segment audio " + mOutputPresentationTimeDecodedUs + " for decoder " + channelName);
                    throttle.remove("Audio" + channelName);
                    continue;
                }

                int result = decoderWrapper.dequeueOutputBuffer(timeoutUs);
                switch (result) {
                    case MediaCodec.INFO_TRY_AGAIN_LATER:
                        continue;
                    case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                        mAudioChannel.setActualDecodedFormat(decoderWrapper.mDecoder.getOutputFormat());
                    case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                        return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
                }
                consumed = true;
                long bufferInputStartTime = decoderWrapper.mBufferInfo.presentationTimeUs;
                long bufferInputEndTime = bufferInputStartTime + mAudioChannel.getBufferDurationUs(channelName, result);
                long bufferOutputTime = bufferInputStartTime + inputChannel.mInputOffsetUs;

                // End of stream - requeue the buffer
                if ((decoderWrapper.mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    decoderWrapper.mIsDecoderEOS = true;
                    segment.forceEndOfStream(mOutputPresentationTimeDecodedUs);
                    decoderWrapper.requeueOutputBuffer();
                    mAudioChannel.removeBuffers(channelName);
                    if (mIsLastSegment)
                        mAudioChannel.drainDecoderBufferAndQueue(channelName, BUFFER_INDEX_END_OF_STREAM, 0l, 0l, 0l, 0l);
                    TLog.d(TAG, "Audio End of Stream audio " + mOutputPresentationTimeDecodedUs + " (" + decoderWrapper.mBufferInfo.presentationTimeUs + ")" + " for decoder " + channelName);
                    throttle.remove("Audio" + channelName);

                // Process a buffer with data
                } else if (decoderWrapper.mBufferInfo.size > 0) {

                    // If we are before start skip entirely
                    if (bufferInputStartTime < inputChannel.mInputStartTimeUs) {
                        inputChannel.mInputAcutalEndTimeUs = bufferInputEndTime;
                        decoderWrapper.mDecoder.releaseOutputBuffer(result, false);
                        TLog.d(TAG, "Skipping Audio for Decoder " + channelName + " at " + bufferOutputTime);

                    // Requeue buffer if to far ahead of other tracks
                    } else if (!throttle.canProceed("Audio" + channelName, bufferOutputTime)) {
                        decoderWrapper.requeueOutputBuffer();
                        TLog.d(TAG, "Requeue Audio Buffer " + bufferOutputTime + " for decoder " + channelName);
                        consumed = false;
                        decoderWrapper.mPresentationTimeRequeued =  bufferOutputTime;

                        // Submit buffer for audio mixing
                    } else {
                        TLog.d(TAG, "Submitting Audio for Decoder " + channelName + " at " + bufferOutputTime);
                        inputChannel.mInputAcutalEndTimeUs = bufferInputEndTime;
                        mAudioChannel.drainDecoderBufferAndQueue(channelName, result, decoderWrapper.mBufferInfo.presentationTimeUs,
                                inputChannel.mInputOffsetUs, inputChannel.mInputStartTimeUs, inputChannel.mInputEndTimeUs);
                    }
                }
            }
        }
        if (allDecodersEndOfStream()) {
            //if (mIsLastSegment && !mIsSegmentFinished)
            //    mEncoder.signalEndOfInputStream();
            mIsSegmentFinished = true;
        }

        return consumed ? DRAIN_STATE_CONSUMED : DRAIN_STATE_NONE;
    }

    boolean allDecodersEndOfStream () {
        boolean isDecoderEndOfStream = true;
        for (Map.Entry<String, DecoderWrapper> decoderWrapperEntry : mDecoderWrappers.entrySet()) {
            if (!(decoderWrapperEntry.getValue().mIsDecoderEOS || decoderWrapperEntry.getValue().mIsSegmentEOS))
                isDecoderEndOfStream = false;
        }
        return isDecoderEndOfStream;
    }

    private int drainEncoder(long timeoutUs) {
        if (mIsEncoderEOS) return DRAIN_STATE_NONE;

        int result = mEncoder.dequeueOutputBuffer(mBufferInfo, timeoutUs);
        switch (result) {
            case MediaCodec.INFO_TRY_AGAIN_LATER:
                return DRAIN_STATE_NONE;
            case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                if (mActualOutputFormat != null) {
                    throw new RuntimeException("Audio output format changed twice.");
                }
                mActualOutputFormat = mEncoder.getOutputFormat();
                mMuxer.setOutputFormat(SAMPLE_TYPE, mActualOutputFormat);
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
            case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                mEncoderBuffers = new MediaCodecBufferCompatWrapper(mEncoder);
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }

        if (mActualOutputFormat == null) {
            throw new RuntimeException("Could not determine actual output format.");
        }

        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            mIsEncoderEOS = true;
            mIsSegmentFinished = true;
            mBufferInfo.set(0, 0, 0, mBufferInfo.flags);
        }
        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // SPS or PPS, which should be passed by MediaFormat.
            mEncoder.releaseOutputBuffer(result, false);
            return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
        }

        mMuxer.writeSampleData(SAMPLE_TYPE, mEncoderBuffers.getOutputBuffer(result), mBufferInfo);

        mEncoder.releaseOutputBuffer(result, false);
        return DRAIN_STATE_CONSUMED;
    }

    @Override
    public long getOutputPresentationTimeDecodedUs() {
        return mOutputPresentationTimeDecodedUs;
    }

    @Override
    public boolean isSegmentFinished() {
        return mIsSegmentFinished;
    }

    @Override
    public void releaseDecoders() {
        for (Map.Entry<String, DecoderWrapper> decoderWrapperEntry : mDecoderWrappers.entrySet()) {
            decoderWrapperEntry.getValue().release();
        }
    }
    @Override
    public void releaseEncoder() {
        TLog.d(TAG, "ReleaseEncoder");
        if (mEncoder != null) {
            if (mEncoderStarted) mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
    }
    @Override
    public void release() {
        releaseDecoders();
        releaseEncoder();
    }
}
