package net.veryrtc.room;

public class Constants {
    /**
     *  Notification method names
     */
    public static final String NTF_METHOD_PARTICIPANT_JOINED = "participantJoined";
    public static final String NTF_METHOD_PARTICIPANT_PUBLISHED = "participantPublished";
    public static final String NTF_METHOD_PARTICIPANT_UNPUBLISHED = "participantUnpublished";
    public static final String NTF_METHOD_ICE_CANDIDATE = "iceCandidate";
    public static final String NTF_METHOD_PARTICIPANT_LEFT = "participantLeft";
    public static final String NTF_METHOD_SEND_MESSAGE = "sendMessage";
    public static final String NTF_METHOD_MEDIA_ERROR = "mediaError";

    /**
     * Request method names
     */
    public static final String REQ_METHOD_JOIN_ROOM = "joinRoom";
    public static final String REQ_METHOD_LEAVE_ROOM = "leaveRoom";
    public static final String REQ_METHOD_PUBLISH_VIDEO = "publishVideo";
    public static final String REQ_METHOD_UNPUBLISH_VIDEO = "unpublishVideo";
    public static final String REQ_METHOD_RECEIVE_VIDEO = "receiveVideoFrom";
    public static final String REQ_METHOD_UNRECEIVE_VIDEO = "unsubscribeFromVideo";
    public static final String REQ_METHOD_ON_ICE_CANDIDATE = "onIceCandidate";
    public static final String REQ_METHOD_SEND_MESSAGE = "sendMessage";
    public static final String REQ_METHOD_CUSTOM_REQUEST = "customRequest";
}
