package net.ypresto.androidtranscoder.engine;

import net.ypresto.androidtranscoder.TLog;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the wiring for a time sequence in terms of input channels, output channels and filters
 *
 TimeLine timeline = new TimeLine(LogLevelForTests)
     .addChannel("A", in1.getFileDescriptor())
     .addChannel("B", in1.getFileDescriptor())
     .addChannel("C", in1.getFileDescriptor())
     .addAudioOnlyChannel("D", in2.getFileDescriptor())
 .createSegment()
     .output("C")
     .output("D")
     .duration(1000)
 .timeLine().createSegment()
     .output("C", TimeLine.Filter.OPACITY_DOWN_RAMP)
     .output("A", TimeLine.Filter.OPACITY_UP_RAMP)
     .output("D")
     .duration(2000)
 .timeLine().createSegment()
     .duration(1500)
     .output("A")
     .output("D")
     .text("Hello World").top(50).left(10).size(5).width(90).color(255,255,255,255)
 .timeLine().createSegment()
     .seek("B", 1000)
     .output("B")
     .duration(1500)
     .output("D")
 .timeLine();


/**
 * TimeLine contains a list of segments which defines each sequential segment of time that
 * has a unique configuration of channels to be combined and output during that time range.
 * TimeLine also contains inputChannels which may be assigned to each segment
 */
public class TimeLine {
    private static final String TAG = "TimeLine";

    static long TO_END_OF_FILE = -1;
    private List<Segment> mSegments = new ArrayList<Segment>();
    private LinkedHashMap<String, InputChannel> mTimeLineChannels = new LinkedHashMap<String, InputChannel>();
    public TimeLine () {}
    public TimeLine (int logLevel) {
        TLog.setLevel(logLevel);
    }
    public TimeLine (int logLevel, String tags) {
        TLog.setLevel(logLevel);
        TLog.setTags(tags);
    }

    public Segment createSegment() {
        TLog.i(TAG, "createSegment: ");
        for (Segment segment : mSegments)
            segment.isLastSegment = false;
        Segment segment = new Segment(this);
        mSegments.add(segment);
        return segment;
    }

    /**
     * Get a List of all segments
     * @return
     */
    public  List<Segment> getSegments() {
        return mSegments;
    }

    /**
     * Get a List of all channels used for creating the master list of extractors
     * @return
     */
    public  LinkedHashMap<String, InputChannel> getChannels() {return mTimeLineChannels;}


    /**
     * Add a video/audio input and assign as a channel
     *
     * @param inputFileDescriptor
     * @param inputChannel
     * @return
     */
    public TimeLine addChannel(String inputChannel, FileDescriptor inputFileDescriptor) {
        TLog.i(TAG, "addChannel: " + inputChannel + ":" + inputFileDescriptor.toString());
        mTimeLineChannels.put(inputChannel, new InputChannel(inputFileDescriptor, ChannelType.AUDIO_VIDEO));
        return this;
    }


    /**
     * Add a video/audio input and assign as a channel
     *
     * @param inputFileDescriptor
     * @param inputChannel
     * @return
     */
    public TimeLine addImageChannel(String inputChannel, FileDescriptor inputFileDescriptor) {
        TLog.i(TAG, "addChannel (image): " + inputChannel + ":" + inputFileDescriptor.toString());
        mTimeLineChannels.put(inputChannel, new InputChannel(inputFileDescriptor, ChannelType.IMAGE));
        return this;
    }

    /**
     * Add a video/audio input and assign as a channel
     *
     * @param inputFileDescriptor
     * @param inputChannel
     * @return
     */
    public TimeLine addVideoOnlyChannel(String inputChannel, FileDescriptor inputFileDescriptor) {
        TLog.i(TAG, "addChannel (video only): " + inputChannel + ":" + inputFileDescriptor.toString());
        mTimeLineChannels.put(inputChannel, new InputChannel(inputFileDescriptor, ChannelType.VIDEO));
        return this;
    }

    /**
     * Add a video/audio input and assign as a channel
     *
     * @param inputFileDescriptor
     * @param inputChannel
     * @return
     */
    public TimeLine addAudioOnlyChannel(String inputChannel, FileDescriptor inputFileDescriptor) {
        TLog.i(TAG, "addChannel (audio only): " + inputChannel + ":" + inputFileDescriptor.toString());
        mTimeLineChannels.put(inputChannel, new InputChannel(inputFileDescriptor, ChannelType.AUDIO));
        return this;
    }

    /**
     * Add a video/audio and assign as a channel
     *
     * @param inputFileDescriptor
     * @param inputChannel
     * @param channelType
     * @return
     */
    public TimeLine addChannel(String inputChannel, FileDescriptor inputFileDescriptor, ChannelType channelType) {
        TLog.i(TAG, "addChannel (" + channelType + "): " + inputChannel + ":" + inputFileDescriptor.toString());
        mTimeLineChannels.put(inputChannel, new InputChannel(inputFileDescriptor, channelType));
        return this;
    }

    /**
     * Get the entire timeline duration
     * @return
     */
    public Long getDuration () {
        long durationUs = 0l;
        for (Segment segment : getSegments()) {
            durationUs += segment.getDuration();
        }
        return durationUs;
    }

    /**
     * Represents a mapping of one or two input channels to an output channel, optionally
     * applying a filter.
     */
    public class SegmentChannel {
        public InputChannel mChannel;
        public Filter mFilter;
        public Long mTimeScale;
        public Long mSeek;
        boolean mAudioSeekRequested = false;
        boolean mVideoSeekRequested = false;

        public Long getVideoSeek() {
            return  mVideoSeekRequested ? null : mSeek;
        }
        public Long getAudioSeek() {
            return  mAudioSeekRequested ? null : mSeek;
        }
        public void seekRequestedVideo() {
            mVideoSeekRequested = true;
        }
        public void seekRequestedAudio() {
            mAudioSeekRequested = true;
        }

        SegmentChannel(InputChannel input, Filter filter) {
            mChannel = input;
            mFilter = filter;
        }
    }

    public enum Filter {OPACITY_UP_RAMP, OPACITY_DOWN_RAMP, MUTE, SUPPRESS};
    public enum ChannelType {VIDEO, AUDIO, AUDIO_VIDEO, IMAGE}

    /**
     * An input file / start time combination
     */
    public class InputChannel {
        public Long mLengthUs;  // Length based on metadata
        public Long mVideoInputStartTimeUs = 0l;
        public Long mAudioInputStartTimeUs = 0l;
        public Long mInputEndTimeUs = 0l;
        public Long mVideoInputOffsetUs = 0l;
        public Long mAudioInputOffsetUs = 0l;
        public Long mVideoInputAcutalEndTimeUs =0l;
        public Long mAudioInputAcutalEndTimeUs =0l;
        public long mVideoFrameLength = 1000000 / 24;
        public long mSeekShortage = 0l;
        public long mDurationShortage = 0l;
        public Filter mFilter;
        public ChannelType mChannelType;
        public FileDescriptor mInputFileDescriptor = null;

        InputChannel() {
        }

        InputChannel(FileDescriptor inputFileDescriptor, ChannelType channelType) {
            mInputFileDescriptor = inputFileDescriptor;
            mChannelType = channelType;
        }
    }

    public class Segment {
        private TimeLine mTimeLine;
        private LinkedHashMap<String, SegmentChannel> mSegmentChannels = new LinkedHashMap();
        private SegmentChannel mLastSegmentChannel = null;
        private HashMap<String, Long> mSeeks = new HashMap<String, Long>();
        private Long mDuration;
        public Long mOutputStartTimeUs;
        public boolean isLastSegment = true;

        public Long getDuration () {
            if (mDuration != null)
                return mDuration;

            HashMap.Entry<String, SegmentChannel> firstChannelEntry = mSegmentChannels.entrySet().iterator().next();
            return firstChannelEntry.getValue().mChannel.mLengthUs -
                    (mSeeks.get(firstChannelEntry.getKey()) == null ? 0l : mSeeks.get(firstChannelEntry.getKey()));
        }
        public SegmentChannel getSegmentChannel(String channel) {
            return mSegmentChannels.get(channel);
        }
        public void start (Long presentationTime, Long videoPresentationTime, Long audioPresentationTime, Long videoEncodedTime, Long audioEncodedTime) {

            mOutputStartTimeUs = presentationTime;

            for (HashMap.Entry<String, SegmentChannel> segmentChannelEntry : mSegmentChannels.entrySet()) {

                SegmentChannel segmentChannel = segmentChannelEntry.getValue();
                String channelName = segmentChannelEntry.getKey();
                InputChannel inputChannel = segmentChannel.mChannel;

                Long actualSeek = mSeeks.get(channelName) != null ?  mSeeks.get(channelName) : 0l;
                Long seek = (actualSeek / inputChannel.mVideoFrameLength) * inputChannel.mVideoFrameLength;
                inputChannel.mSeekShortage += (actualSeek - seek);
                Long seekAddition =  (inputChannel.mSeekShortage / inputChannel.mVideoFrameLength) * inputChannel.mVideoFrameLength;
                inputChannel.mSeekShortage -= seekAddition;
                seek += seekAddition;

                Long actualDuration = getDuration();
                Long duration = (actualDuration / inputChannel.mVideoFrameLength) * inputChannel.mVideoFrameLength;
                inputChannel.mDurationShortage += (actualDuration - duration);
                Long durationAddition =  (inputChannel.mDurationShortage / inputChannel.mVideoFrameLength) * inputChannel.mVideoFrameLength;
                inputChannel.mDurationShortage -= durationAddition;
                duration += durationAddition;

                inputChannel.mVideoInputStartTimeUs = seek + inputChannel.mInputEndTimeUs;
                inputChannel.mAudioInputStartTimeUs = seek + inputChannel.mInputEndTimeUs;

                inputChannel.mVideoInputOffsetUs = videoPresentationTime - (seek + inputChannel.mVideoInputAcutalEndTimeUs);
                inputChannel.mAudioInputOffsetUs = audioPresentationTime - (seek + inputChannel.mAudioInputAcutalEndTimeUs);

                inputChannel.mInputEndTimeUs = inputChannel.mInputEndTimeUs + seek + duration;
                inputChannel.mAudioInputAcutalEndTimeUs = inputChannel.mInputEndTimeUs;
                inputChannel.mVideoInputAcutalEndTimeUs = inputChannel.mInputEndTimeUs;

                segmentChannel.mSeek = (seek > 0) ? inputChannel.mVideoInputStartTimeUs : null;
                inputChannel.mFilter = segmentChannel.mFilter;

                TLog.d(TAG, "Segment Channel " + channelName + " PT: " + presentationTime +
                        " VStart: " + inputChannel.mVideoInputStartTimeUs +
                        " AStart: " + inputChannel.mAudioInputStartTimeUs +
                        " End: " + inputChannel.mInputEndTimeUs +
                        " VOff: " + inputChannel.mVideoInputOffsetUs +
                        " AOff: " + inputChannel.mAudioInputOffsetUs +
                        " duration: " + duration +
                        " seek: " + seek + " ASeek: " +
                        " VPT:" + videoPresentationTime +
                        " APT:" + audioPresentationTime +
                        " VET:" + videoEncodedTime +
                        " AET:" + audioEncodedTime +
                        " drift:" + (videoPresentationTime - audioPresentationTime));
            }
        }

        public TimeLine timeLine () {return mTimeLine;}

        /**
         * Get all channels that participate in this segment
         * @return
         */
        public LinkedHashMap<String, InputChannel> getChannels() {
            LinkedHashMap<String, InputChannel> channels = new LinkedHashMap<String, InputChannel>();
            for (Map.Entry<String, TimeLine.SegmentChannel> entry : mSegmentChannels.entrySet())
                channels.put(entry.getKey(), entry.getValue().mChannel);
            return channels;
        }

        /**
         * Get all video channels that participate in this segment
         * @return
         */
        public LinkedHashMap<String, InputChannel> getImageChannels() {
            LinkedHashMap<String, InputChannel> channels = new LinkedHashMap<String, InputChannel>();
            for (Map.Entry<String, TimeLine.SegmentChannel> entry : mSegmentChannels.entrySet())
                if (entry.getValue().mChannel.mChannelType == ChannelType.IMAGE)
                    channels.put(entry.getKey(), entry.getValue().mChannel);
            return channels;
        }

        /**
         * Get all video channels that participate in this segment
         * @return
         */
        public LinkedHashMap<String, InputChannel> getVideoChannels() {
            LinkedHashMap<String, InputChannel> channels = new LinkedHashMap<String, InputChannel>();
            for (Map.Entry<String, TimeLine.SegmentChannel> entry : mSegmentChannels.entrySet())
                if (entry.getValue().mChannel.mChannelType == ChannelType.VIDEO || entry.getValue().mChannel.mChannelType == ChannelType.AUDIO_VIDEO)
                    channels.put(entry.getKey(), entry.getValue().mChannel);
            return channels;
        }

        /**
         * Get all audio channels that participate in this segment
         * @return
         */
        public LinkedHashMap<String, InputChannel> getAudioChannels() {
            LinkedHashMap<String, InputChannel> channels = new LinkedHashMap<String, InputChannel>();
            for (Map.Entry<String, TimeLine.SegmentChannel> entry : mSegmentChannels.entrySet())
                if (entry.getValue().mChannel.mChannelType == ChannelType.AUDIO || entry.getValue().mChannel.mChannelType == ChannelType.AUDIO_VIDEO)
                    channels.put(entry.getKey(), entry.getValue().mChannel);
            return channels;
        }

        /**
         * Private constructor - use Segment.create() to create a segment
         */
        private Segment(TimeLine timeLine) {
            mTimeLine = timeLine;
        }

        /**
         * Set the duration of the channel for this segment, otherwise to end of stream
         * @param time
         * @return
         */
        public Segment duration(long time) {
            TLog.i(TAG, "duration: " + time);
            this.mDuration = time * 1000l;
            return this;
        }

        /**
         * Set start time of input channel, otherwise where it left off
         * @param channel
         * @param time
         * @return
         */
        public Segment seek(String channel, long time) {
            TLog.i(TAG, "seek: " + channel + " " + time);
            this.mSeeks.put(channel, time * 1000l);
            return this;
        }

        /**
         * Add a single channel routed directly to the encoder
         *
         * @param inputChannelName
         */
        public Segment output(String inputChannelName) {
            TLog.i(TAG, "output: " + inputChannelName);
            InputChannel inputChannel = mTimeLineChannels.get(inputChannelName);
            //if (inputChannel.mChannelType != ChannelType.AUDIO)
            mLastSegmentChannel = new SegmentChannel(inputChannel, null);
            mSegmentChannels.put(inputChannelName, mLastSegmentChannel);
            return this;
        }

        /**
         * Add a single channel input that is filtered before being sent to the encoder
         *
         * @param inputChannelName
         * @param filter to be applied of type Filter
         */
        public Segment output(String inputChannelName, Filter filter) {
            TLog.i(TAG, "output: " + inputChannelName + " with " + filter);
            InputChannel inputChannel = mTimeLineChannels.get(inputChannelName);
            mLastSegmentChannel = new SegmentChannel(inputChannel, filter);
            mSegmentChannels.put(inputChannelName, mLastSegmentChannel );
            return this;
        }

        /**
         * Add a filter to the previous segment channel
         * @param filter to be applied of type Filter
         * @return Segment
         */
        public Segment filter(Filter filter) {
            mLastSegmentChannel.mFilter = filter;
            return this;
        }

        /**
         * Scale the input so it matches the time of the segment
         * Only larger values than the segement are currently supported (for speedups)
         * @param timeScale
         * @return
         */
        public Segment timeScale(long timeScale) {
            mLastSegmentChannel.mTimeScale = timeScale;
            return this;
        }


        int getChannelCount() {
            return mSegmentChannels.size();
        }

        LinkedHashMap<String, SegmentChannel> getSegmentChannels() {
            return mSegmentChannels;
        }

    }
}