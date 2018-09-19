package net.veryrtc.peer;

import org.webrtc.PeerConnectionFactory;

class Constants {
    public static final String MEDIA_STREAM_ID = "ARDAMS";
    public static final String VIDEO_TRACK_ID = "ARDAMSv0";
    public static final String AUDIO_TRACK_ID = "ARDAMSa0";
    public static final String VIDEO_TRACK_TYPE = "video";
    public static final String VIDEO_CODEC_VP8 = "VP8";
    public static final String VIDEO_CODEC_VP9 = "VP9";
    public static final String VIDEO_CODEC_H264 = "H264";
    public static final String VIDEO_CODEC_H264_BASELINE = "H264 Baseline";
    public static final String VIDEO_CODEC_H264_HIGH = "H264 High";
    public static final String AUDIO_CODEC_OPUS = "opus";
    public static final String AUDIO_CODEC_ISAC = "ISAC";
    public static final String VIDEO_CODEC_PARAM_START_BITRATE = "x-google-start-bitrate";
    public static final String VIDEO_CODEC_PARAM_MAX_BITRATE = "x-google-max-bitrate";
    public static final String VIDEO_CODEC_PARAM_MIN_BITRATE = "x-google-min-bitrate";
    public static final String VIDEO_FLEXFEC_FIELDTRIAL =
                            "WebRTC-FlexFEC-03-Advertised/Enabled/WebRTC-FlexFEC-03/Enabled/";
    public static final String VIDEO_VP8_INTEL_HW_ENCODER_FIELDTRIAL = "WebRTC-IntelVP8/Enabled/";
    public static final String VIDEO_H264_HIGH_PROFILE_FIELDTRIAL = "WebRTC-H264HighProfile/Enabled/";
    private static final String FIELD_TRIAL_VP9 = "WebRTC-SupportVP9/Enabled/";
    private static final String FIELD_TRIAL_AUTOMATIC_RESIZE = "WebRTC-MediaCodecVideoEncoder-AutomaticResize/Enabled/";
    public static final String DISABLE_WEBRTC_AGC_FIELDTRIAL = "WebRTC-Audio-MinimizeResamplingOnMobile/Enabled/";
    public static final String VIDEO_FRAME_EMIT_FIELDTRIAL =
                                PeerConnectionFactory.VIDEO_FRAME_EMIT_TRIAL + "/" + PeerConnectionFactory.TRIAL_ENABLED + "/";
    public static final String AUDIO_CODEC_PARAM_BITRATE = "maxaveragebitrate";
    public static final String AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation";
    public static final String AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT = "googAutoGainControl";
    public static final String AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter";
    public static final String AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression";
    public static final String AUDIO_LEVEL_CONTROL_CONSTRAINT = "levelControl";
    public static final String DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT = "DtlsSrtpKeyAgreement";
    public static final int HD_VIDEO_WIDTH = 1280;
    public static final int HD_VIDEO_HEIGHT = 720;
    public static final int BPS_IN_KBPS = 1000;

    // Peer connection statistics callback period in ms.
    public static final int STAT_CALLBACK_PERIOD = 1000;
}
