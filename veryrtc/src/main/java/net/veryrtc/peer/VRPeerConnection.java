/*
 * (C) Copyright 2016 VTT (http://www.vtt.fi)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package net.veryrtc.peer;

import android.util.Log;

import net.veryrtc.util.LooperExecutor;

import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.RtpParameters;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.StatsObserver;
import org.webrtc.StatsReport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A peer connection wrapper which is used by VeryRTCPeer to support multiple connectivity.
 *
 */
class VRPeerConnection implements PeerConnection.Observer, SdpObserver {

    private static final String TAG = "VRPeerConnection";

    private PeerConnection pc;
    private String connectionId;
    private LooperExecutor executor;
    private SessionDescription localSdp; // either offer or answer SDP
    private RtpSender localVideoSender;
    private boolean preferIsac;
    private boolean videoCallEnable;
    private String preferredVideoCodec;
    private boolean isInitiator;
    private HashMap<String, ObservedDataChannel> observedDataChannels;
    private LinkedList<IceCandidate> queuedRemoteCandidates;
    MediaConstraints sdpMediaConstraints = null;
    Vector<Observer> observers;
    VeryRTCPeer.PeerConnectionParameters peerConnectionParameters;
    private Timer statsTimer;

    /**
     * An interface which declares WebRTC callbacks
     * <p>
     * This interface class has to be implemented outside API. VeryRTCPeer requires an Observer
     * instance in constructor
     * </p>
     */
    interface Observer {
        /**
         * WebRTC event which is triggered when local SDP offer has been generated
         * @param localSdpOffer The generated local SDP offer
         * @param connection The connection for which this event takes place
         */
        void onLocalSdpOfferGenerated(final SessionDescription localSdpOffer, final VRPeerConnection connection);

        /**
         * WebRTC event which is triggered when local SDP answer has been generated
         * @param localSdpAnswer The generated local SDP answer
         * @param connection The connection for which this event takes place
         */
        void onLocalSdpAnswerGenerated(final SessionDescription localSdpAnswer, final VRPeerConnection connection);

        /**
         * WebRTC event which is triggered when new ice candidate is received
         * @param localIceCandidate Ice candidate
         * @param connection The connection for which this event takes place
         */
        void onIceCandidate(final IceCandidate localIceCandidate, final VRPeerConnection connection);

        /**
         * WebRTC event which is triggered when local ice candidates are removed.
         * @param candidates Ice candidate
         * @param connection The connection for which this event takes place
         */
        void onIceCandidatesRemoved(final IceCandidate[] candidates, final VRPeerConnection connection);

        /**
         * WebRTC event which is triggered when ICE status has changed
         * @param newState The new ICE connection state
         * @param connection The connection for which this event takes place
         */
        void onIceConnectionChange(final PeerConnection.IceConnectionState newState, final VRPeerConnection connection);

        /**
         * WebRTC event which is triggered when A new remote stream is added to connection
         * @param stream The new remote media stream
         * @param connection The connection for which this event takes place
         */
        void onRemoteStreamAdded(final MediaStream stream, final VRPeerConnection connection);

        /**
         * WebRTC event which is triggered when a remote media stream is terminated
         * @param stream The removed remote media stream
         * @param connection The connection for which this event takes place
         */
        void onRemoteStreamRemoved(final MediaStream stream, final VRPeerConnection connection);

        /**
         * WebRTC event which is triggered once peer connection statistics is ready.
         */
        void onPeerConnectionStatsReady(final StatsReport[] reports);

        /**
         * WebRTC event which is triggered when there is an error with the connection
         * @param error Error string
         */
        void onPeerConnectionError(final String error);

        /**
         * WebRTC event which is triggered when peer opens a data channel
         * @param dataChannel The data channel
         * @param connection The connection for which the data channel belongs to
         */
        void onDataChannel(final DataChannel dataChannel, final VRPeerConnection connection);

        /**
         * WebRTC event which is triggered when a data channel buffer amount has changed
         * @param previousAmount The previous amount
         * @param connection The connection for which the data channel belongs to
         * @param channel The data channel which triggered the event
         */
        void onBufferedAmountChange(long previousAmount, final VRPeerConnection connection, final DataChannel channel);

        /**
         * WebRTC event which is triggered when a data channel state has changed. Possible values:
         * DataChannel.State { CONNECTING, OPEN, CLOSING, CLOSED };
         * @param connection The connection for which the data channel belongs to
         * @param channel The data channel which triggered the event
         */
        void onStateChange(final VRPeerConnection connection, final DataChannel channel);

        /**
         * WebRTC event which is triggered when a message is received from a data channel
         * @param buffer The message buffer
         * @param connection The connection for which the data channel belongs to
         * @param channel The data channel which triggered the event
         */
        void onMessage(final DataChannel.Buffer buffer, final VRPeerConnection connection, final DataChannel channel);
    }

    /* This private class exists to receive per-channel events and forward them to upper layers
       with the channel instance
      */
    private class ObservedDataChannel implements DataChannel.Observer {
        private DataChannel channel;

        public ObservedDataChannel(String label, DataChannel.Init init) {
            channel = pc.createDataChannel(label, init);
            if (channel != null) {
                channel.registerObserver(this);
                Log.i(TAG, "Created data channel with Id: " + label);
            }
            else {
                Log.e(TAG, "Failed to create data channel with Id: " + label);
            }
        }

        public DataChannel getChannel() {
            return channel;
        }

        @Override
        public void onBufferedAmountChange(long previousAmount) {
            Log.i(TAG, "[ObservedDataChannel] VRPeerConnection onBufferedAmountChange");
            for (Observer observer : observers) {
                observer.onBufferedAmountChange(previousAmount, VRPeerConnection.this, channel);
            }
        }

        @Override
        public void onStateChange(){
            for (Observer observer : observers) {
                observer.onStateChange(VRPeerConnection.this, channel);
            }
        }

        @Override
        public void onMessage(DataChannel.Buffer buffer) {
            Log.i(TAG, "[ObservedDataChannel] VRPeerConnection onMessage");
            for (Observer observer : observers) {
                observer.onMessage(buffer, VRPeerConnection.this, channel);
            }
        }
    }

    public VRPeerConnection(String connectionId,
                            boolean preferIsac,
                            boolean videoCallEnable,
                            String preferredVideoCodec,
                            LooperExecutor executor,
                            VeryRTCPeer.PeerConnectionParameters params) {

        this.connectionId = connectionId;
        observers = new Vector<>();
        this.preferIsac = preferIsac;
        this.videoCallEnable = videoCallEnable;
        this.preferredVideoCodec = preferredVideoCodec;
        this.executor = executor;
        this.isInitiator = false;
        this.peerConnectionParameters = params;
        queuedRemoteCandidates = new LinkedList<>();
        observedDataChannels = new HashMap<>();
        localVideoSender = null;
        statsTimer = new Timer();
    }

    public DataChannel createDataChannel(String label, DataChannel.Init init) {
        ObservedDataChannel dataChannel = new ObservedDataChannel(label, init);
        observedDataChannels.put(label, dataChannel);
        return dataChannel.getChannel();
    }

    @SuppressWarnings("unused")
    public HashMap<String, DataChannel> getDataChannels(){
        HashMap<String, DataChannel> channels = new HashMap<>();
        for (HashMap.Entry<String, ObservedDataChannel> entry : observedDataChannels.entrySet()) {
            String key = entry.getKey();
            ObservedDataChannel value = entry.getValue();
            channels.put(key, value.getChannel());
        }
        return channels;
    }

    @SuppressWarnings("unused")
    public String getConnectionId() {
        return connectionId;
    }

    public DataChannel getDataChannel(String dataChannelId){
        ObservedDataChannel channel = this.observedDataChannels.get(dataChannelId);
        if (channel == null) {
            return null;
        }
        else {
            return channel.getChannel();
        }
    }

    public void setPc(PeerConnection pc) {
        this.pc = pc;
    }

    public PeerConnection getPc(){
        return pc;
    }

    public void addObserver(Observer observer){
        observers.add(observer);
    }

    public void removeObserver(Observer observer) {
        if (observers.contains(observer)) {
            observers.remove(observer);
        }
    }

    private void drainCandidates() {
        if (queuedRemoteCandidates != null) {
            Log.d(TAG, "Add " + queuedRemoteCandidates.size() + " remote candidates");
            for (IceCandidate candidate : queuedRemoteCandidates) {
                pc.addIceCandidate(candidate);
            }
            queuedRemoteCandidates = null;
        }
    }

    public void createOffer(MediaConstraints sdpMediaConstraints) {
        this.sdpMediaConstraints = sdpMediaConstraints;
        if (pc != null){// && !isError) {
            Log.d(TAG, "PC Create OFFER");
            isInitiator = true;
            pc.createOffer(this, this.sdpMediaConstraints);
        }
    }

    public void createAnswer(final MediaConstraints sdpMediaConstraints) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (pc != null){// && !isError) {
                    Log.d(TAG, "PC create ANSWER");
                    isInitiator = false;
                    pc.createAnswer(VRPeerConnection.this, sdpMediaConstraints);
                }
            }
        });
    }

    protected void setRemoteDescriptionInternal(SessionDescription sdp) {
        if (pc == null){// || isError) {
            return;
        }
        String sdpDescription = sdp.description;
        if (preferIsac) {
            sdpDescription = preferCodec(sdpDescription, Constants.AUDIO_CODEC_ISAC, true);
        }
        if (videoCallEnable) {
            sdpDescription = preferCodec(sdpDescription, preferredVideoCodec, false);
        }
//        if (videoCallEnable && peerConnectionParameters.videoStartBitrate > 0) {
//            sdpDescription = setStartBitrate(Constants.VIDEO_CODEC_VP8, true, sdpDescription, peerConnectionParameters.videoStartBitrate);
//            sdpDescription = setStartBitrate(Constants.VIDEO_CODEC_VP9, true, sdpDescription, peerConnectionParameters.videoStartBitrate);
//            sdpDescription = setStartBitrate(Constants.VIDEO_CODEC_H264, true, sdpDescription, peerConnectionParameters.videoStartBitrate);
//        }

        if (peerConnectionParameters.audioStartBitrate > 0) {
            sdpDescription = setStartBitrate(Constants.AUDIO_CODEC_OPUS, false, sdpDescription, peerConnectionParameters.audioStartBitrate);
        }
        Log.d(TAG, "Set remote SDP.");
        SessionDescription sdpRemote = new SessionDescription(sdp.type, sdpDescription);
        pc.setRemoteDescription(VRPeerConnection.this, sdpRemote);
    }

    protected void setRemoteDescription(final SessionDescription sdp) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                setRemoteDescriptionInternal(sdp);
            }
        });
    }

    public void addRemoteIceCandidate(final IceCandidate candidate) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (pc != null){// && !isError) {
                    if (queuedRemoteCandidates != null) {
                        queuedRemoteCandidates.add(candidate);
                    } else {
                        pc.addIceCandidate(candidate);
                    }
                }
            }
        });
    }

    public void close(){
        Log.d(TAG, "Closing peer connection.");
        statsTimer.cancel();

        if (pc != null) {
            pc.dispose();
            pc = null;
        }
        Log.d(TAG, "Closing peer connection done.");
    }

    /**
     *
     * @param codec
     * @param isVideoCodec
     * @param sdpDescription
     * @param bitrateKbps
     * @return
     */
    private static String setStartBitrate(String codec, boolean isVideoCodec, String sdpDescription, int bitrateKbps) {
        String[] lines = sdpDescription.split("\r\n");
        int rtpmapLineIndex = -1;
        boolean sdpFormatUpdated = false;
        String codecRtpMap = null;
        // Search for codec rtpmap in format
        // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
        String regex = "^a=rtpmap:(\\d+) " + codec + "(/\\d+)+[\r]?$";
        Pattern codecPattern = Pattern.compile(regex);
        for (int i = 0; i < lines.length; i++) {
            Matcher codecMatcher = codecPattern.matcher(lines[i]);
            if (codecMatcher.matches()) {
                codecRtpMap = codecMatcher.group(1);
                rtpmapLineIndex = i;
                break;
            }
        }
        if (codecRtpMap == null) {
            Log.w(TAG, "No rtpmap for " + codec + " codec");
            return sdpDescription;
        }
        Log.d(TAG, "Found " + codec + " rtpmap " + codecRtpMap + " at " + lines[rtpmapLineIndex]);
        // Check if a=fmtp string already exist in remote SDP for this codec and
        // update it with new bitrate parameter.
        regex = "^a=fmtp:" + codecRtpMap + " \\w+=\\d+.*[\r]?$";
        codecPattern = Pattern.compile(regex);
        for (int i = 0; i < lines.length; i++) {
            Matcher codecMatcher = codecPattern.matcher(lines[i]);
            if (codecMatcher.matches()) {
                Log.d(TAG, "Found " + codec + " " + lines[i]);
                if (isVideoCodec) {
                    lines[i] += "; " + Constants.VIDEO_CODEC_PARAM_START_BITRATE + "=" + bitrateKbps;
                } else {
                    lines[i] += "; " + Constants.AUDIO_CODEC_PARAM_BITRATE + "=" + (bitrateKbps * 1000);
                }
                Log.d(TAG, "Update remote SDP line: " + lines[i]);
                sdpFormatUpdated = true;
                break;
            }
        }
        StringBuilder newSdpDescription = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            newSdpDescription.append(lines[i]).append("\r\n");
            // Append new a=fmtp line if no such line exist for a codec.
            if (!sdpFormatUpdated && i == rtpmapLineIndex) {
                String bitrateSet;
                if (isVideoCodec) {
                    bitrateSet = "a=fmtp:" + codecRtpMap + " " + Constants.VIDEO_CODEC_PARAM_START_BITRATE + "=" + bitrateKbps;
                } else {
                    bitrateSet = "a=fmtp:" + codecRtpMap + " " + Constants.AUDIO_CODEC_PARAM_BITRATE + "=" + (bitrateKbps * 1000);
                }
                Log.d(TAG, "Add remote SDP line: " + bitrateSet);
                newSdpDescription.append(bitrateSet).append("\r\n");
            }
        }
        return newSdpDescription.toString();
    }

    /** Returns the line number containing "m=audio|video", or -1 if no such line exists. */
    private static int findMediaDescriptionLine(boolean isAudio, String[] sdpLines) {
        final String mediaDescription = isAudio ? "m=audio " : "m=video ";
        for (int i = 0; i < sdpLines.length; ++i) {
            if (sdpLines[i].startsWith(mediaDescription)) {
                return i;
            }
        }
        return -1;
    }

    private static String joinString(
            Iterable<? extends CharSequence> s, String delimiter, boolean delimiterAtEnd) {
        Iterator<? extends CharSequence> iter = s.iterator();
        if (!iter.hasNext()) {
            return "";
        }
        StringBuilder buffer = new StringBuilder(iter.next());
        while (iter.hasNext()) {
            buffer.append(delimiter).append(iter.next());
        }
        if (delimiterAtEnd) {
            buffer.append(delimiter);
        }
        return buffer.toString();
    }

    private static String movePayloadTypesToFront(List<String> preferredPayloadTypes, String mLine) {
        // The format of the media description line should be: m=<media> <port> <proto> <fmt> ...
        final List<String> origLineParts = Arrays.asList(mLine.split(" "));
        if (origLineParts.size() <= 3) {
            Log.e(TAG, "Wrong SDP media description format: " + mLine);
            return null;
        }
        final List<String> header = origLineParts.subList(0, 3);
        final List<String> unpreferredPayloadTypes =
                new ArrayList<String>(origLineParts.subList(3, origLineParts.size()));
        unpreferredPayloadTypes.removeAll(preferredPayloadTypes);
        // Reconstruct the line with |preferredPayloadTypes| moved to the beginning of the payload
        // types.
        final List<String> newLineParts = new ArrayList<String>();
        newLineParts.addAll(header);
        newLineParts.addAll(preferredPayloadTypes);
        newLineParts.addAll(unpreferredPayloadTypes);
        return joinString(newLineParts, " ", false /* delimiterAtEnd */);
    }

    /**
     *
     * @param sdpDescription
     * @param codec
     * @param isAudio
     * @return
     */
    private static String preferCodec(String sdpDescription, String codec, boolean isAudio) {
        final String[] lines = sdpDescription.split("\r\n");
        final int mLineIndex = findMediaDescriptionLine(isAudio, lines);
        if (mLineIndex == -1) {
            Log.w(TAG, "No mediaDescription line, so can't prefer " + codec);
            return sdpDescription;
        }
        // A list with all the payload types with name |codec|. The payload types are integers in the
        // range 96-127, but they are stored as strings here.
        final List<String> codecPayloadTypes = new ArrayList<String>();
        // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
        final Pattern codecPattern = Pattern.compile("^a=rtpmap:(\\d+) " + codec + "(/\\d+)+[\r]?$");
        for (int i = 0; i < lines.length; ++i) {
            Matcher codecMatcher = codecPattern.matcher(lines[i]);
            if (codecMatcher.matches()) {
                codecPayloadTypes.add(codecMatcher.group(1));
            }
        }
        if (codecPayloadTypes.isEmpty()) {
            Log.w(TAG, "No payload types with name " + codec);
            return sdpDescription;
        }

        final String newMLine = movePayloadTypesToFront(codecPayloadTypes, lines[mLineIndex]);
        if (newMLine == null) {
            return sdpDescription;
        }
        Log.d(TAG, "Change media description from: " + lines[mLineIndex] + " to " + newMLine);
        lines[mLineIndex] = newMLine;
        return joinString(Arrays.asList(lines), "\r\n", true /* delimiterAtEnd */);
    }

    public void findVideoSender() {
        for (RtpSender sender : pc.getSenders()) {
            if (sender.track() != null) {
                String trackType = sender.track().kind();
                if (trackType.equals(Constants.VIDEO_TRACK_TYPE)) {
                    Log.d(TAG, "Found video sender.");
                    localVideoSender = sender;
                }
            }
        }
    }

    public void setVideoMaxBitrate(final Integer maxBitrateKbps) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (pc == null || localVideoSender == null/* || isError*/) {
                    return;
                }
                Log.d(TAG, "Requested max video bitrate: " + maxBitrateKbps);
                if (localVideoSender == null) {
                    Log.w(TAG, "Sender is not ready.");
                    return;
                }

                RtpParameters parameters = localVideoSender.getParameters();
                if (parameters.encodings.size() == 0) {
                    Log.w(TAG, "RtpParameters are not ready.");
                    return;
                }

                for (RtpParameters.Encoding encoding : parameters.encodings) {
                    // Null value means no limit.
                    encoding.maxBitrateBps = maxBitrateKbps == null ? null : maxBitrateKbps * Constants.BPS_IN_KBPS;
                }
                if (!localVideoSender.setParameters(parameters)) {
                    Log.e(TAG, "RtpSender.setParameters failed.");
                }
                Log.d(TAG, "Configured max video bitrate to: " + maxBitrateKbps);
            }
        });
    }

    private void getStats() {
        if (pc == null) {
            return;
        }

        boolean success = pc.getStats(new StatsObserver() {
            @Override
            public void onComplete(final StatsReport[] reports) {
                for (Observer observer : observers) {
                    observer.onPeerConnectionStatsReady(reports);
                }
            }
        }, null);

        if (!success) {
            Log.e(TAG, "getStats() returns false!");
        }
    }

    public void enableStatsEvents(boolean enable, int periodMs) {
        if (enable) {
            try {
                statsTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        executor.execute(new Runnable() {
                            @Override
                            public void run() {
                                getStats();
                            }
                        });
                    }
                }, 0, periodMs);
            } catch (Exception e) {
                Log.e(TAG, "Can not schedule statistics timer", e);
            }
        } else {
            statsTimer.cancel();
        }
    }

    /** Triggered when the SignalingState changes. */
    @Override
    public void onSignalingChange(final PeerConnection.SignalingState signalingState) {
        Log.d(TAG, "SignalingState: " + signalingState);
    }

    /** Triggered when the IceConnectionState changes. */
    @Override
    public void onIceConnectionChange(final PeerConnection.IceConnectionState newState) {
        Log.d(TAG, "IceConnectionState: " + newState);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                for (Observer o : observers) {
                    o.onIceConnectionChange(newState, VRPeerConnection.this);
                }
            }
        });
    }

    /** Triggered when the ICE connection receiving status changes. */
    @Override
    public void onIceConnectionReceivingChange(final boolean receiving) {
        Log.d(TAG, "IceConnectionReceiving changed to " + receiving);
    }

    /** Triggered when the IceGatheringState changes. */
    @Override
    public void onIceGatheringChange(final PeerConnection.IceGatheringState iceGatheringState) {
        Log.d(TAG, "IceGatheringState: " + iceGatheringState);
    }

    /** Triggered when a new ICE candidate has been found. */
    @Override
    public void onIceCandidate(final IceCandidate iceCandidate) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                for (Observer observer : observers) {
                    observer.onIceCandidate(iceCandidate, VRPeerConnection.this);
                }
            }
        });
    }

    /** Triggered when some ICE candidates have been removed. */
    @Override
    public void onIceCandidatesRemoved(final IceCandidate[] candidates) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                for (Observer observer : observers) {
                    observer.onIceCandidatesRemoved(candidates, VRPeerConnection.this);
                }
            }
        });
    }

    /** Triggered when media is received on a new stream from remote peer. */
    @Override
    public void onAddStream(final MediaStream mediaStream) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (pc == null) {
                    return;
                }
                if (mediaStream.audioTracks.size() > 1 || mediaStream.videoTracks.size() > 1) {
                    for (Observer observer : observers) {
                        observer.onPeerConnectionError("Weird-looking stream: " + mediaStream);
                    }
                    return;
                }
                for (Observer observer : observers) {
                    observer.onRemoteStreamAdded(mediaStream, VRPeerConnection.this);
                }
            }
        });
    }

    /** Triggered when a remote peer close a stream. */
    @Override
    public void onRemoveStream(final MediaStream mediaStream) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (pc == null) {
                    return;
                }
                if (mediaStream.audioTracks.size() > 1 || mediaStream.videoTracks.size() > 1) {
                    for (Observer observer : observers) {
                        observer.onPeerConnectionError("Weird-looking stream: " + mediaStream);
                    }
                    return;
                }
                for (Observer observer : observers) {
                    observer.onRemoteStreamRemoved(mediaStream, VRPeerConnection.this);
                }
            }
        });
    }

    /** Triggered when a remote peer opens a DataChannel. */
    @Override
    public void onDataChannel(final DataChannel dataChannel) {
        Log.i(TAG, "[datachannel] Peer opened data channel");
        executor.execute(new Runnable() {
            @Override
            public void run() {
                for (Observer observer : observers) {
                    observer.onDataChannel(dataChannel, VRPeerConnection.this);
                }
            }
        });
    }

    /** Triggered when renegotiation is necessary. */
    @Override
    public void onRenegotiationNeeded() {
        Log.d(TAG, "[datachannel] OnRenegotiationNeeded called.");
        /*if (sdpMediaConstraints != null) {
            Log.d(TAG, sdpMediaConstraints.toString());
            pc.createOffer(VRPeerConnection.this, sdpMediaConstraints);
        }*/
    }

    /** Triggered when a new track is signaled by the remote peer, as a result of setRemoteDescription */
    @Override
    public void onAddTrack(final RtpReceiver receiver, final MediaStream[] mediaStreams) {
        executor.execute(new Runnable() {
            @Override
            public void run() {

            }
        });
    }

    /** Called on success of Create{Offer,Answer}(). */
    @Override
    public void onCreateSuccess(final SessionDescription sessionDescription) {
        assert(localSdp != null);

        String sdpDescription = sessionDescription.description;
        if (preferIsac) {
            sdpDescription = preferCodec(sdpDescription, Constants.AUDIO_CODEC_ISAC, true);
        }
        if (videoCallEnable) {
            sdpDescription = preferCodec(sdpDescription, preferredVideoCodec, false);
        }
        final SessionDescription sdp = new SessionDescription(sessionDescription.type, sdpDescription);
        localSdp = sdp;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (pc != null) {// && !isError) {
                    Log.d(TAG, "Set local SDP from " + sdp.type);
                    pc.setLocalDescription(VRPeerConnection.this, sdp);
                }
            }
        });
    }

    /** Called on success of Set{Local,Remote}Description(). */
    @Override
    public void onSetSuccess() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (pc == null) {// || isError) {
                    return;
                }
                if (isInitiator) {
                    // For offering peer connection we first create offer and set
                    // local SDP, then after receiving answer set remote SDP.
                    if (pc.getRemoteDescription() == null) {
                        // We've just set our local SDP so time to send it.
                        Log.d(TAG, "Local SDP set succesfully");
                        for (Observer observer : observers) {
                            observer.onLocalSdpOfferGenerated(localSdp, VRPeerConnection.this);
                        }
                    } else {
                        // We've just set remote description, so drain remote
                        // and send local ICE candidates.
                        Log.d(TAG, "Remote SDP set succesfully");
                        drainCandidates();
                    }
                } else {
                    // For answering peer connection we set remote SDP and then
                    // create answer and set local SDP.
                    if (pc.getLocalDescription() != null) {
                        // We've just set our local SDP so time to send it, drain
                        // remote and send local ICE candidates.
                        Log.d(TAG, "Local SDP set succesfully");
                        for (Observer observer : observers) {
                            observer.onLocalSdpAnswerGenerated(localSdp, VRPeerConnection.this);
                        }
                        drainCandidates();
                    } else {
                        // We've just set remote SDP - do nothing for now -
                        // answer will be created soon.
                        Log.d(TAG, "Remote SDP set succesfully");
                    }
                }
            }
        });
    }

    /** Called on error of Create{Offer,Answer}(). */
    @Override
    public void onCreateFailure(final String s) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                for (Observer observer : observers) {
                    observer.onPeerConnectionError(s);
                }
            }
        });
    }

    /** Called on error of Set{Local,Remote}Description(). */
    @Override
    public void onSetFailure(final String s) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                for (Observer observer : observers) {
                    observer.onPeerConnectionError(s);
                }
            }
        });
    }
}
