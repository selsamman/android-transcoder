/*
 * Copyright (C) 2014 Yuya Tanaka
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.ypresto.androidtranscoder.engine;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import net.ypresto.androidtranscoder.TLog;

import net.ypresto.androidtranscoder.format.MediaFormatExtraConstants;
import net.ypresto.androidtranscoder.utils.MediaExtractorUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Refer: https://android.googlesource.com/platform/cts/+/lollipop-release/tests/tests/media/src/android/media/cts/ExtractDecodeEditEncodeMuxTest.java
public class VideoTrackTranscoder implements TrackTranscoder {

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
        private MediaCodec mDecoder;
        private ByteBuffer [] mDecoderInputBuffers;
        private OutputSurface mOutputSurface;
        private Integer mTrackIndex;
        private Long mPresentationTimeRequeued = null;
        boolean mBufferRequeued;
        int mResult;
        private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
        DecoderWrapper(MediaExtractor mediaExtractor) {
            mExtractor = mediaExtractor;
        }

        public void start() {
            mOutputSurface = new OutputSurface();
            MediaExtractorUtils.TrackResult trackResult = MediaExtractorUtils.getFirstVideoAndAudioTrack(mExtractor);
            if (trackResult.mVideoTrackFormat != null) {
                int trackIndex = trackResult.mVideoTrackIndex;
                mTrackIndex = trackIndex;
                mExtractor.selectTrack(trackIndex);
                MediaFormat inputFormat = mExtractor.getTrackFormat(trackIndex);
                if (inputFormat.containsKey(MediaFormatExtraConstants.KEY_ROTATION_DEGREES)) {
                    // Decoded video is rotated automatically in Android 5.0 lollipop.
                    // Turn off here because we don't want to encode rotated one.
                    // refer: https://android.googlesource.com/platform/frameworks/av/+blame/lollipop-release/media/libstagefright/Utils.cpp
                    inputFormat.setInteger(MediaFormatExtraConstants.KEY_ROTATION_DEGREES, 0);
                }
                mOutputSurface = new OutputSurface();

                try {
                    mDecoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                mDecoder.configure(inputFormat, mOutputSurface.getSurface(), null, 0);
                mDecoder.start();
                mDecoderStarted = true;
                mDecoderInputBuffers = mDecoder.getInputBuffers();
            }
        }
        private float mPresentationTimeus;
        private float mDurationUs;
        private TimeLine.Filter mFilter;
        private void setFilter(TimeLine.Filter filter, long presentationTimeUs, long durationUs) {
            mFilter = filter;
            mPresentationTimeus = presentationTimeUs;
            mDurationUs = durationUs;

        }
        private void filterTick (float presentationTimeUs) {
            if (mFilter == TimeLine.Filter.OPACITY_UP_RAMP) {
                mOutputSurface.setAlpha((presentationTimeUs - mPresentationTimeus) / mDurationUs);
            }
            if (mFilter == TimeLine.Filter.OPACITY_DOWN_RAMP) {
                mOutputSurface.setAlpha(1.0f - (presentationTimeUs - mPresentationTimeus) / mDurationUs);
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
            if (mOutputSurface != null) {
                mOutputSurface.release();
                mOutputSurface = null;
            }
            if (mDecoder != null) {
                mDecoder.stop();
                mDecoder.release();
                mDecoder = null;
            }
        }

    };
    LinkedHashMap<String, DecoderWrapper> mDecoderWrappers = new LinkedHashMap<String, DecoderWrapper>();

    private static final String TAG = "VideoTrackTranscoder";
    private static final long BUFFER_LEAD_TIME = 0;//100000; // Amount we will let other decoders get ahead
    private static final int DRAIN_STATE_NONE = 0;
    private static final int DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY = 1;
    private static final int DRAIN_STATE_CONSUMED = 2;
    private final LinkedHashMap<String, MediaExtractor> mExtractors;
    private final MediaFormat mOutputFormat;
    private final QueuedMuxer mMuxer;
    private MediaCodec mEncoder;
    private ByteBuffer[] mEncoderOutputBuffers;
    private MediaFormat mActualOutputFormat;
    private InputSurface mEncoderInputSurfaceWrapper;
    private boolean mIsEncoderEOS;
    private boolean mIsSegmentFinished;
    private boolean mEncoderStarted;
    private int mTexturesReady = 0;
    private int mTextures = 0;
    private long mOutputPresentationTimeDecodedUs = 0l;
    private long mPreviousOutputPresentationTimeDecodedUs = 0l;
    private List <TextureRender> mTextureRender;
    private boolean mIsLastSegment = false;
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    public VideoTrackTranscoder(LinkedHashMap<String, MediaExtractor> extractors,
                                MediaFormat outputFormat, QueuedMuxer muxer) {
        mOutputFormat = outputFormat;
        mMuxer = muxer;
        mExtractors = extractors;
    }

    @Override
    public void setupEncoder() {
        try {
            mEncoder = MediaCodec.createEncoderByType(mOutputFormat.getString(MediaFormat.KEY_MIME));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        mEncoder.configure(mOutputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoderInputSurfaceWrapper = new InputSurface(mEncoder.createInputSurface());
        mEncoderInputSurfaceWrapper.makeCurrent();
        mEncoder.start();
        mEncoderStarted = true;
        mEncoderOutputBuffers = mEncoder.getOutputBuffers();
    }
    private void createWrapperSlot (TimeLine.Segment segment) {

        if (mDecoderWrappers.keySet().size() < 2)
            return;

        // Release any inactive decoders
        Iterator<Map.Entry<String, VideoTrackTranscoder.DecoderWrapper>> iterator = mDecoderWrappers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, VideoTrackTranscoder.DecoderWrapper> decoderWrapperEntry = iterator.next();
            if (!segment.getVideoChannels().containsKey(decoderWrapperEntry.getKey())) {
                decoderWrapperEntry.getValue().release();
                segment.timeLine().getChannels().get(decoderWrapperEntry.getKey()).mInputEndTimeUs = 0l;
                iterator.remove();
                TLog.d(TAG, "Releasing Video Decoder " + decoderWrapperEntry.getKey());
                return;
            }
        }

    }
    /**
     * Setup all decoders and texture renderers needed for this segment - called at start of segment processing
     * We also close any ones not needed for this segment that may have been opened in a previous segment
     * @param segment
     */
    @Override
    public void setupDecoders(TimeLine.Segment segment, MediaTranscoderEngine.TranscodeThrottle throttle) {

        TLog.d(TAG, "Setting up Video Decoders for segment at " + segment.mOutputStartTimeUs + " for a duration of " + segment.getDuration());

          // Start any decoders being opened for the first time

        for (Map.Entry<String, TimeLine.InputChannel> entry : segment.getVideoChannels().entrySet()) {
            TimeLine.InputChannel inputChannel = entry.getValue();
            String channelName = entry.getKey();
            DecoderWrapper decoderWrapper = mDecoderWrappers.get(channelName);
            if (decoderWrapper == null) {
                createWrapperSlot(segment);
                decoderWrapper = new DecoderWrapper(mExtractors.get(channelName));
                mDecoderWrappers.put(channelName, decoderWrapper);
            }
            decoderWrapper.mIsSegmentEOS = false;
            if (!decoderWrapper.mDecoderStarted)
                decoderWrapper.start();
            TLog.d(TAG, "Video Decoder " + channelName + " at offset " + inputChannel.mInputOffsetUs + " starting at " + (inputChannel.mInputStartTimeUs + inputChannel.mInputOffsetUs) + " ending at " + (inputChannel.mInputEndTimeUs == null ? "EOS" : inputChannel.mInputEndTimeUs + inputChannel.mInputOffsetUs));
        }

        // Create array of texture renderers for each patch in the segment
        mTextureRender = new ArrayList<TextureRender>();
        ArrayList<OutputSurface> outputSurfaces = new ArrayList<OutputSurface>(2);
        for (Map.Entry<String, TimeLine.SegmentChannel> segmentChannelEntry : segment.getSegmentChannels().entrySet()) {
            String channelName = segmentChannelEntry.getKey();
            TimeLine.SegmentChannel segmentChannel = segmentChannelEntry.getValue();
            DecoderWrapper decoderWrapper = mDecoderWrappers.get(channelName);
            decoderWrapper.mOutputSurface.setAlpha(1.0f);
            if (!decoderWrapper.mIsDecoderEOS) {
                outputSurfaces.add(decoderWrapper.mOutputSurface);
                decoderWrapper.setFilter(segmentChannel.mFilter, mOutputPresentationTimeDecodedUs, segment.getDuration());
                throttle.participate("Video" + channelName);
            } else
                decoderWrapper.mIsSegmentEOS = true;
        }
        TextureRender textureRender = new TextureRender(outputSurfaces);
        textureRender.surfaceCreated();
        mTextureRender.add(textureRender);
        TLog.d(TAG, "Surface Texture Created for " + outputSurfaces.size() + " surfaces");
        mTextures = outputSurfaces.size();
        mIsSegmentFinished = false;
        mIsEncoderEOS = false;
        mIsLastSegment = segment.isLastSegment;
        mTexturesReady = 0;

    }

    @Override
    public MediaFormat getDeterminedFormat() {
        return mActualOutputFormat;
    }

    @Override
    public boolean stepPipeline(TimeLine.Segment outputSegment, MediaTranscoderEngine.TranscodeThrottle throttle) {
        boolean stepped = false;
        int status;
        while (drainEncoder(0) != DRAIN_STATE_NONE) stepped = true;
        do {
            status = drainDecoders(outputSegment, 0, throttle);
            if (status != DRAIN_STATE_NONE) stepped = true;
            // NOTE: not repeating to keep from deadlock when encoder is full.
        } while (status == DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY);
        while (drainExtractors(outputSegment, 0) != DRAIN_STATE_NONE) stepped = true;

        return stepped;
    }


    @Override
    public long getOutputPresentationTimeDecodedUs() {
        return mOutputPresentationTimeDecodedUs;
    }

    @Override
    public boolean isSegmentFinished() {
        return mIsSegmentFinished;
    }

    // TODO: CloseGuard
    @Override
    public void releaseEncoder() {
        TLog.d(TAG, "ReleaseEncoder");
        if (mEncoderInputSurfaceWrapper != null) {
            mEncoderInputSurfaceWrapper.release();
            mEncoderInputSurfaceWrapper = null;
        }
        if (mEncoder != null) {
            if (mEncoderStarted) mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
    }

    /**
     * Release any decoders not needed in the next segment
     */
    @Override
    public void releaseDecoders() {
        for (Map.Entry<String, DecoderWrapper> decoderWrapperEntry : mDecoderWrappers.entrySet()) {
            decoderWrapperEntry.getValue().release();
        }
    }

    /**
     * Release encoder and any lingering decoders
     */
    @Override
    public void release () {
        releaseDecoders();
        releaseEncoder();
    }

    /**
     * Drain extractors
     * @param segment
     * @param timeoutUs
     * @return DRAIN_STATE_CONSUMED - pipeline has been stepped, DRAIN_STATE_NONE - could not step
     */
    private int drainExtractors(TimeLine.Segment segment, long timeoutUs) {

        boolean sampleProcessed = false;

        for (Map.Entry<String, TimeLine.InputChannel> inputChannelEntry : segment.getVideoChannels().entrySet()) {

            String channelName = inputChannelEntry.getKey();
            DecoderWrapper decoderWrapper = mDecoderWrappers.get(channelName);
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

                // Get the sample into the buffer
                int sampleSize = decoderWrapper.mExtractor.readSampleData(decoderWrapper.mDecoderInputBuffers[result], 0);
                long sampleTime = decoderWrapper.mExtractor.getSampleTime();
                boolean isKeyFrame = (decoderWrapper.mExtractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
                decoderWrapper.mDecoder.queueInputBuffer(result, 0, sampleSize, sampleTime, isKeyFrame ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0);
                decoderWrapper.mExtractor.advance();
                sampleProcessed = true;

                // Seek at least to previous key frame if needed cause it's a lot faster
                TimeLine.SegmentChannel segmentChannel = segment.getSegmentChannel(channelName);
                Long seek = segmentChannel.getVideoSeek();
                if (seek != null && sampleTime < seek) {
                    decoderWrapper.mExtractor.seekTo(seek, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    segmentChannel.seekRequestedVideo(); // So we don't repeat
                    TLog.d(TAG, "Extractor Seek " + seek);
                }
            }
        }
        return  sampleProcessed ? DRAIN_STATE_CONSUMED : DRAIN_STATE_NONE;
    }

    /**
     * We have to drain all decoders
     * @param segment
     * @param timeoutUs
     * @return
     */
    private int drainDecoders(TimeLine.Segment segment, long timeoutUs, MediaTranscoderEngine.TranscodeThrottle throttle) {
        boolean consumed = false;

        // Go through each decoder in the segment and get it's frame into a texture
        for (Map.Entry<String, TimeLine.InputChannel> inputChannelEntry : segment.getVideoChannels().entrySet()) {

            String channelName = inputChannelEntry.getKey();
            TimeLine.InputChannel inputChannel = inputChannelEntry.getValue();
            DecoderWrapper decoderWrapper = mDecoderWrappers.get(channelName);

            // Only process if we have not end end of stream for this decoder or extractor
            if (!decoderWrapper.mIsDecoderEOS && !decoderWrapper.mIsSegmentEOS) {

                if (!decoderWrapper.mOutputSurface.isTextureReady() &&
                    !decoderWrapper.mOutputSurface.isEndOfInputStream()  &&
                    (decoderWrapper.mPresentationTimeRequeued == null || throttle.canProceed("Video" + channelName, decoderWrapper.mPresentationTimeRequeued))) {
                    decoderWrapper.mPresentationTimeRequeued =  null;

                    int result = decoderWrapper.dequeueOutputBuffer(timeoutUs);
                    switch (result) {
                        case MediaCodec.INFO_TRY_AGAIN_LATER:
                            continue;
                        case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                        case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                            TLog.d(TAG, "INFO_OUTPUT_BUFFERS_CHANGED for decoder " + channelName);
                            return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
                    }
                    consumed = true;
                    long bufferInputStartTime = decoderWrapper.mBufferInfo.presentationTimeUs;
                    long frameLength = mOutputPresentationTimeDecodedUs - mPreviousOutputPresentationTimeDecodedUs;
                    long bufferInputEndTime = bufferInputStartTime + frameLength;
                    long bufferOutputTime = bufferInputStartTime + inputChannel.mInputOffsetUs;

                    // See if encoder is end-of-stream and propogage to output surface
                    if ((decoderWrapper.mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        decoderWrapper.mBufferInfo.size = 0;
                        decoderWrapper.mOutputSurface.signalEndOfInputStream();
                        decoderWrapper.mIsDecoderEOS = true;
                        segment.forceEndOfStream(mOutputPresentationTimeDecodedUs + 1);
                        TLog.d(TAG, "End of Stream video " + mOutputPresentationTimeDecodedUs + " (" + decoderWrapper.mBufferInfo.presentationTimeUs + ")" + " for decoder " + channelName);
                        mTextures = 1; // Write if there is a texture
                        decoderWrapper.mDecoder.releaseOutputBuffer(result, false);
                        throttle.remove("Video" + channelName);
                    } else {

                        boolean doRender = (decoderWrapper.mBufferInfo.size > 0);
                        // NOTE: doRender will block if buffer (of encoder) is full.
                        // Refer: http://bigflake.com/mediacodec/CameraToMpegTest.java.txt

                        // End of Segment
                        if (doRender && inputChannel.mInputEndTimeUs != null && bufferInputStartTime >= inputChannel.mInputEndTimeUs) {
                            decoderWrapper.requeueOutputBuffer();
                            decoderWrapper.mIsSegmentEOS = true;
                            TLog.d(TAG, "End of Segment video " + bufferInputStartTime + " >= " + inputChannel.mInputEndTimeUs + " for video decoder " + channelName);
                            mTextures = 1; // Write if there is a texture
                            throttle.remove("Video" + channelName);

                            // Requeue buffer if to far ahead of other tracks
                        } else if (doRender && !throttle.canProceed("Video" + channelName, bufferOutputTime)) {
                            decoderWrapper.requeueOutputBuffer();
                            TLog.d(TAG, "RequeueOutputBuffer " + bufferOutputTime + " for video decoder " + channelName);
                            decoderWrapper.mPresentationTimeRequeued =  bufferOutputTime;
                            consumed = false;

                            // Within range get image into texture for rendering
                        } else if (doRender && decoderWrapper.mBufferInfo.presentationTimeUs >= inputChannel.mInputStartTimeUs) {
                            decoderWrapper.mDecoder.releaseOutputBuffer(result, true);
                            decoderWrapper.mOutputSurface.awaitNewImage();
                            decoderWrapper.mOutputSurface.setTextureReady();
                            decoderWrapper.filterTick(mOutputPresentationTimeDecodedUs);
                            ++mTexturesReady;
                            consumed = true;
                            TLog.v(TAG, "Texture ready " + mOutputPresentationTimeDecodedUs + " (" + decoderWrapper.mBufferInfo.presentationTimeUs + ")" + " for decoder " + channelName);
                            mOutputPresentationTimeDecodedUs = Math.max(bufferOutputTime, mOutputPresentationTimeDecodedUs);
                            inputChannel.mInputAcutalEndTimeUs = bufferInputEndTime;

                            // Seeking - release it without rendering
                        } else {
                            TLog.v(TAG, "Skipping video " + mOutputPresentationTimeDecodedUs + " (" + decoderWrapper.mBufferInfo.presentationTimeUs + ")" + " for decoder " + channelName);
                            decoderWrapper.mDecoder.releaseOutputBuffer(result, false);
                            inputChannel.mInputAcutalEndTimeUs = bufferInputEndTime;
                        }
                    }
                    mPreviousOutputPresentationTimeDecodedUs = mOutputPresentationTimeDecodedUs;
                }
            }
        }
        if (allDecodersEndOfStream()) {
            if (mIsLastSegment && !mIsSegmentFinished)
                mEncoder.signalEndOfInputStream();
            mIsSegmentFinished = true;
        }

        // If all textures have been accumulated draw the image and send it to the encoder
        if (mTexturesReady >= mTextures && mTextures > 0) {

            for (TextureRender textureRender : mTextureRender) {
                textureRender.drawFrame();
            }
            TLog.v(TAG, "Encoded video " + mOutputPresentationTimeDecodedUs + " for decoder ");
            mEncoderInputSurfaceWrapper.setPresentationTime(mOutputPresentationTimeDecodedUs * 1000);
            mEncoderInputSurfaceWrapper.swapBuffers();
            mOutputPresentationTimeDecodedUs += 1; // Hack to ensure next one greater than current;
            mTexturesReady = 0;
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
                if (mActualOutputFormat != null)
                    throw new RuntimeException("Video output format changed twice.");
                mActualOutputFormat = mEncoder.getOutputFormat();
                mMuxer.setOutputFormat(QueuedMuxer.SampleType.VIDEO, mActualOutputFormat);
                return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY;
            case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                mEncoderOutputBuffers = mEncoder.getOutputBuffers();
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

        mMuxer.writeSampleData(QueuedMuxer.SampleType.VIDEO, mEncoderOutputBuffers[result], mBufferInfo);
        mEncoder.releaseOutputBuffer(result, false);
        return DRAIN_STATE_CONSUMED;
    }
}
