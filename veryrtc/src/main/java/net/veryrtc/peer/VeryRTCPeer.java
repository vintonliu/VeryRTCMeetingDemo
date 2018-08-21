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

import android.content.Context;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import net.veryrtc.util.LooperExecutor;
import net.veryrtc.webrtcpeerandroid.R;

import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerationAndroid;
import org.webrtc.CameraEnumerator;
import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnection.IceConnectionState;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;
import org.webrtc.StatsReport;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.voiceengine.WebRtcAudioManager;
import org.webrtc.voiceengine.WebRtcAudioRecord;
import org.webrtc.voiceengine.WebRtcAudioTrack;
import org.webrtc.voiceengine.WebRtcAudioUtils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Class implements the interface for managing WebRTC connections in harmonious manner with
 * other Kurento APIs (HTML5 and iOs).
 *
 * The implementation is based on PeerConnectionClient.java of package org.appspot.apprtc
 * (please see the copyright notice below)
 */

/*
 * libjingle
 * Copyright 2014 Google Inc.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
 * EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

/**
 * Main API class for implementing WebRTC peer on Android
 */
public class VeryRTCPeer implements VRPeerConnection.Observer {
    private static final String TAG = "VeryRTCPeer";

    // Fix for devices running old Android versions not finding the libraries.
    // https://bugs.chromium.org/p/webrtc/issues/detail?id=6751
    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("boringssl.cr");
            System.loadLibrary("protobuf_lite.cr");
        } catch (UnsatisfiedLinkError e) {
            Logging.w(TAG, "Failed to load native dependencies: ", e);
        }
    }

    private final LooperExecutor executor;
    private Context context;
    private PeerConnectionParameters peerConnectionParameters;
    private ParcelFileDescriptor aecDumpFileDescriptor;
    private SurfaceViewRenderer localRenderer;
    private VideoCapturer videoCapturer;
    private boolean screencaptureEnabled = false;
    private boolean captureToTexture = true;
    private VeryRTCPeerConfiguration.NBMCameraPosition currentCameraPosition;
    private final Observer observer;
    private final EglBase rootEglBase;
    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnectionResourceManager connectionManager;
    private MediaResourceManager mediaManager;
    private LinkedList<PeerConnection.IceServer> iceServers;
    private CpuMonitor cpuMonitor;
    private boolean initialized = false;
    private boolean isError = false;
    private boolean videoCallEnabled;
    private boolean dataChannelEnabled = false;

    /**
     * An interface which declares WebRTC callbacks
     * <p>
     * This interface class has to be implemented outside API. VeryRTCPeer requires an Observer
     * instance in constructor
     * </p>
     */
    public interface Observer {

        /**
         * Called when the VeryRTCPeer initialization has completed
         */
        void onInitialize();

        /**
         * WebRTC event which is triggered when local SDP offer has been generated
         * @param localSdpOffer The generated local SDP offer
         * @param connectionId The connection for which this event takes place
         */
        void onLocalSdpOfferGenerated(final SessionDescription localSdpOffer, final String connectionId);

        /**
         * WebRTC event which is triggered when local SDP answer has been generated
         * @param localSdpAnswer The generated local SDP answer
         * @param connectionId The connection for which this event takes place
         */
        void onLocalSdpAnswerGenerated(SessionDescription localSdpAnswer, final String connectionId);

        /**
         * WebRTC event which is triggered when new ice candidate is received
         * @param localIceCandidate Ice candidate
         * @param connectionId The connection for which this event takes place
         */
        void onIceCandidate(IceCandidate localIceCandidate, final String connectionId);

        /**
         * WebRTC event which is triggered when local ice candidates are removed.
         * @param candidates Ice candidate
         * @param connectionId The connection for which this event takes place
         */
        void onIceCandidatesRemoved(final IceCandidate[] candidates, final String connectionId);

        /**
         * WebRTC event which is triggered when ICE has connected
         * @param connectionId The connection for which this event takes place
         */
        void onIceConnected(final String connectionId);

        /**
         * WebRTC event which is triggered when ICE has disconnected
         * @param connectionId The connection for which this event takes place
         */
        void onIceDisconnected(final String connectionId);

        /**
         * WebRTC event which is triggered when ICE connecting failed
         * @param connectionId The connection for which this event takes place
         */
        void onIceFailed(final String connectionId);

        /**
         * WebRTC event which is triggered when A new remote stream is added to connection
         * @param stream The new remote media stream
         * @param connectionId The connection for which this event takes place
         */
        void onRemoteStreamAdded(MediaStream stream, final String connectionId);

        /**
         * WebRTC event which is triggered when a remote media stream is terminated
         * @param stream The removed remote media stream
         * @param connectionId The connection for which this event takes place
         */
        void onRemoteStreamRemoved(MediaStream stream, final String connectionId);

        /**
         * Callback fired once call statics report ready
         * @param encoderStat
         * @param bweStat
         * @param connectionStat
         * @param videoSendStat
         * @param videoRecvStat
         */
        void onPeerConnectionStatsReady(final String encoderStat,
                                        final String bweStat,
                                        final String connectionStat,
                                        final String videoSendStat,
                                        final String videoRecvStat);

        /**
         * WebRTC event which is triggered when there is an error with the connection
         * @param error Error string
         */
        void onPeerConnectionError(String error);

        /**
         * WebRTC event which is triggered when peer opens a data channel
         * @param dataChannel The data channel
         * @param connectionId The connection for which the data channel belongs to
         */
        void onDataChannel(DataChannel dataChannel, final String connectionId);

        /**
         * WebRTC event which is triggered when a data channel buffer amount has changed
         * @param previousAmount The previous amount
         * @param connectionId The connection for which the data channel belongs to
         * @param dataChannelId The data channel which triggered the event
         */
        void onBufferedAmountChange(long previousAmount, final String connectionId, final String dataChannelId);

        /**
         * WebRTC event which is triggered when a data channel state has changed. Possible values:
         * DataChannel.State { CONNECTING, OPEN, CLOSING, CLOSED };
         * @param connectionId The connection for which the data channel belongs to
         * @param dataChannelId The data channel which triggered the event
         * @param state The data channel state
         */
        void onStateChange(final String connectionId, final String dataChannelId, final DataChannel.State state);

        /**
         * WebRTC event which is triggered when a message is received from a data channel
         * @param buffer The message buffer
         * @param connectionId The connection for which the data channel belongs to
         * @param dataChannelId The data channel which triggered the event
         */
        void onMessage(final DataChannel.Buffer buffer, final String connectionId, final String dataChannelId);
    }

    /**
     * Peer connection parameters.
     */
    public static class DataChannelParameters {
        public final boolean ordered;
        public final int maxRetransmitTimeMs;
        public final int maxRetransmits;
        public final String protocol;
        public final boolean negotiated;
        public final int id;

        public DataChannelParameters() {
            this.ordered = true;
            this.maxRetransmitTimeMs = -1;
            this.maxRetransmits = -1;
            this.negotiated = false;
            this.protocol = "";
            this.id = -1;
        }

        public DataChannelParameters(boolean ordered, int maxRetransmitTimeMs, int maxRetransmits,
                                     String protocol, boolean negotiated, int id) {
            this.ordered = ordered;
            this.maxRetransmitTimeMs = maxRetransmitTimeMs;
            this.maxRetransmits = maxRetransmits;
            this.protocol = protocol;
            this.negotiated = negotiated;
            this.id = id;
        }
    }

    /**
     * Peer connection parameters.
     */
    public static class PeerConnectionParameters {
        public final boolean videoCallEnable;
        public final boolean loopback;
        public final boolean tracing;
        public final int videoWidth;
        public final int videoHeight;
        public final int videoFps;
        public final int videoMaxBitrate;
        public final String videoCodec;
        public final boolean videoCodecHwAcceleration;
        public final boolean videoFlexfecEnabled;
        public final boolean useCamera2;
        public final VeryRTCPeerConfiguration.NBMCameraPosition cameraPosition;
        public final int audioStartBitrate;
        public final String audioCodec;
        public final boolean noAudioProcessing;
        public final boolean aecDump;
        public final boolean useOpenSLES;
        public final boolean disableBuiltInAEC;
        public final boolean disableBuiltInAGC;
        public final boolean disableBuiltInNS;
        public final boolean enableLevelControl;
        public final boolean disableWebRtcAGCAndHPF;
        private final DataChannelParameters dataChannelParameters;

        /**
         * Default constructor
         * <p>
         * Default values: <br>
         * videoCallEnable true <br>
         * loopback false <br>
         * tracing false <br>
         * videoWidth 640 <br>
         * videoHeight 480 <br>
         * videoFps 15 fps <br>
         * videoMaxBitrate 1000 <br>
         * videoCodec H264 <br>
         * videoCodecHwAcceleration false <br>
         * videoFlexfecEnabled true <br>
         * useCamera2 true <br>
         * cameraPosition FRONT <br>
         * audioStartBitrate 32 kbps <br>
         * audioCodec OPUS <br>
         * noAudioProcessing false <br>
         * aecDump false <br>
         * useOpenSLES false <br>
         * disableBuiltInAEC false <br>
         * disableBuiltInAGC false <br>
         * disableBuiltInNS false <br>
         * enableLevelControl false <br>
         * disableWebRtcAGCAndHPF false <br>
         * dataChannelParameters null <br>
         * </p>
         */
        public PeerConnectionParameters() {
            videoCallEnable = true;
            loopback = false;
            tracing = false;
            videoWidth = 640;
            videoHeight = 480;
            videoFps = 15;
            videoMaxBitrate = 1000;
            videoCodec = Constants.VIDEO_CODEC_H264;
            videoCodecHwAcceleration = false;
            videoFlexfecEnabled = true;
            useCamera2 = true;
            cameraPosition = VeryRTCPeerConfiguration.NBMCameraPosition.FRONT;
            audioStartBitrate = 32;
            audioCodec = Constants.AUDIO_CODEC_OPUS;
            noAudioProcessing = false;
            aecDump = false;
            useOpenSLES = false;
            disableBuiltInAEC = false;
            disableBuiltInAGC = false;
            disableBuiltInNS = false;
            enableLevelControl = false;
            disableWebRtcAGCAndHPF = false;
            dataChannelParameters = null;
        }

        public PeerConnectionParameters(
                boolean videoCallEnable, boolean loopback, boolean tracing,
                int videoWidth, int videoHeight, int videoFps, int videoMaxBitrate,
                String videoCodec, boolean videoCodecHwAcceleration, boolean videoFlexfecEnabled,
                boolean useCamera2, VeryRTCPeerConfiguration.NBMCameraPosition cameraPosition,
                int audioStartBitrate, String audioCodec,
                boolean noAudioProcessing, boolean aecDump, boolean useOpenSLES,
                boolean disableBuiltInAEC, boolean disableBuiltInAGC, boolean disableBuiltInNS,
                boolean enableLevelControl, boolean disableWebRtcAGCAndHPF,
                DataChannelParameters dataChannelParameters) {
            this.videoCallEnable = videoCallEnable;
            this.loopback = loopback;
            this.tracing = tracing;
            this.videoWidth = videoWidth;
            this.videoHeight = videoHeight;
            this.videoFps = videoFps;
            this.videoMaxBitrate = videoMaxBitrate;
            this.videoCodec = videoCodec;
            this.videoCodecHwAcceleration = videoCodecHwAcceleration;
            this.videoFlexfecEnabled = videoFlexfecEnabled;
            this.useCamera2 = useCamera2;
            this.cameraPosition = cameraPosition;
            this.audioStartBitrate = audioStartBitrate;
            this.audioCodec = audioCodec;
            this.noAudioProcessing = noAudioProcessing;
            this.aecDump = aecDump;
            this.useOpenSLES = useOpenSLES;
            this.disableBuiltInAEC = disableBuiltInAEC;
            this.disableBuiltInAGC = disableBuiltInAGC;
            this.disableBuiltInNS = disableBuiltInNS;
            this.enableLevelControl = enableLevelControl;
            this.disableWebRtcAGCAndHPF = disableWebRtcAGCAndHPF;
            this.dataChannelParameters = dataChannelParameters;
        }
    }

	/**
	* VeryRTCPeer constructor
     * <p>
     *     This constructor should always be used in order to properly create a VeryRTCPeer instance
     * </p>
	* @param  context			Android context instance
    * @param peerConnectionParameters parameters for create peer connection
	* @param  observer		An observer instance which implements WebRTC callback functions
	*/
    public VeryRTCPeer(Context context,
                       final PeerConnectionParameters peerConnectionParameters,
                       Observer observer) {
        this.context = context;
        this.peerConnectionParameters = peerConnectionParameters;
        this.observer = observer;
        this.executor = new LooperExecutor();
        this.rootEglBase = EglBase.create();
        this.videoCallEnabled = peerConnectionParameters.videoCallEnable;
        this.dataChannelEnabled = peerConnectionParameters.dataChannelParameters != null;
        this.videoCapturer = null;

        // Looper thread is started once in private ctor and is used for all
        // peer connection API calls to ensure new peer connection peerConnectionFactory is
        // created on the same thread as previously destroyed peerConnectionFactory.
        executor.requestStart();

        iceServers = new LinkedList<>();
        PeerConnection.IceServer turnServer =
                PeerConnection.IceServer.builder("turn:120.132.120.136:3478?transport=udp")
                        .setUsername("apprtc1").setPassword("apprtc1")
                        .createIceServer();
        PeerConnection.IceServer turnServer1 =
                PeerConnection.IceServer.builder("turn:120.132.120.136:3478?transport=tcp")
                        .setUsername("apprtc1").setPassword("apprtc1")
                        .createIceServer();
        PeerConnection.IceServer stunServer =
                PeerConnection.IceServer.builder("stun:120.132.120.136:3478")
                        .createIceServer();
        iceServers.add(turnServer);
        iceServers.add(turnServer1);
        iceServers.add(stunServer);
    }

	/**
	 * Initializes VeryRTCPeer
	 * <p>
	 * VeryRTCPeer must be initialized before use. This function can be called immediately after constructor
     * @param localRenderer Callback for rendering the locally produced media stream
     * @param iceServers    An config instance for ICE STUN and TURN servers
	 * <p>
	 */
    public void initialize(final SurfaceViewRenderer localRenderer,
                           final LinkedList<PeerConnection.IceServer> iceServers) {
        this.localRenderer = localRenderer;
        if (iceServers != null) {
            this.iceServers = iceServers;
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                createPeerConnectionFactoryInternal(context);

                cpuMonitor = new CpuMonitor(context);

                if (peerConnectionParameters.videoCallEnable) {
                    videoCapturer = createVideoCapturer();
                    if (videoCapturer == null) {
                        videoCallEnabled = false;
                    }
                }

                connectionManager =
                        new PeerConnectionResourceManager(peerConnectionParameters,
                                                        executor, peerConnectionFactory);
                mediaManager =
                        new MediaResourceManager(context, peerConnectionParameters,
                                                executor, peerConnectionFactory, videoCapturer);
                initialized = true;
                observer.onInitialize();
            }
        });
    }

    @SuppressWarnings("unused")
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Closes all connections
     */
    public void close(){
        executor.execute(new Runnable() {
            @Override
            public void run() {
                closeInternal();
            }
        });
    }

    private void closeInternal() {
        if (peerConnectionFactory != null && peerConnectionParameters.aecDump) {
            peerConnectionFactory.stopAecDump();
            try {
                aecDumpFileDescriptor.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        Log.d(TAG, "Closing peer connection.");

        for(VRPeerConnection c : connectionManager.getConnections()){
            c.getPc().removeStream(mediaManager.getLocalMediaStream());
        }

        if (connectionManager != null) {
            connectionManager.closeAllConnections();
            connectionManager = null;
        }

        if (mediaManager != null) {
            mediaManager.close();
            mediaManager = null;
        }

        Log.d(TAG, "Closing peer connection factory.");
        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            peerConnectionFactory = null;
        }

        rootEglBase.release();

        PeerConnectionFactory.stopInternalTracingCapture();
        PeerConnectionFactory.shutdownInternalTracer();
        Log.d(TAG, "Closing peer connection done.");

        initialized = false;
    }

    private EglBase.Context getRenderContext() { return rootEglBase.getEglBaseContext(); }

    private void createPeerConnectionFactoryInternal(Context context) {
        Log.d(TAG, "Create peer connection peerConnectionFactory. Use video: " + peerConnectionParameters.videoCallEnable);
        isError = false;

        // Initialize field trials.
        String fieldTrials = "";
        if (peerConnectionParameters.videoFlexfecEnabled) {
            fieldTrials += Constants.VIDEO_FLEXFEC_FIELDTRIAL;
            Log.d(TAG, "Enable FlexFEC field trial.");
        }
        fieldTrials += Constants.VIDEO_VP8_INTEL_HW_ENCODER_FIELDTRIAL;
        if (peerConnectionParameters.disableWebRtcAGCAndHPF) {
            fieldTrials += Constants.DISABLE_WEBRTC_AGC_FIELDTRIAL;
            Log.d(TAG, "Disable WebRTC AGC field trial.");
        }
        fieldTrials += Constants.VIDEO_FRAME_EMIT_FIELDTRIAL;

        if (peerConnectionParameters.videoCallEnable &&
            peerConnectionParameters.videoCodec != null &&
            peerConnectionParameters.videoCodec.equalsIgnoreCase(Constants.VIDEO_CODEC_H264_HIGH)) {
              // TODO(magjed): Strip High from SDP when selecting Baseline instead of using field trial.
              fieldTrials += Constants.VIDEO_H264_HIGH_PROFILE_FIELDTRIAL;
        }

        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .setFieldTrials(fieldTrials)
                        .setEnableVideoHwAcceleration(peerConnectionParameters.videoCodecHwAcceleration)
                        .createInitializationOptions());

        // Enable/disable OpenSL ES playback.
        if (!peerConnectionParameters.useOpenSLES) {
            Log.d(TAG, "Disable OpenSL ES audio even if device supports it");
            WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(true /* enable */);
        } else {
            Log.d(TAG, "Allow OpenSL ES audio if device supports it");
            WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(false);
        }

        if (peerConnectionParameters.disableBuiltInAEC) {
            Log.d(TAG, "Disable built-in AEC even if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(true);
        } else {
            Log.d(TAG, "Enable built-in AEC if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(false);
        }

        if (peerConnectionParameters.disableBuiltInAGC) {
            Log.d(TAG, "Disable built-in AGC even if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(true);
        } else {
            Log.d(TAG, "Enable built-in AGC if device supports it");
            WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(false);
        }

        if (peerConnectionParameters.disableBuiltInNS) {
            Log.d(TAG, "Disable built-in NS even if device supports it");
            WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(true);
        } else {
            Log.d(TAG, "Enable built-in NS if device supports it");
            WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(false);
        }

        // Set audio record error callbacks.
        WebRtcAudioRecord.setErrorCallback(new WebRtcAudioRecord.WebRtcAudioRecordErrorCallback() {
            @Override
            public void onWebRtcAudioRecordInitError(String errorMessage) {
                Log.e(TAG, "onWebRtcAudioRecordInitError: " + errorMessage);
                reportError(errorMessage);
            }

            @Override
            public void onWebRtcAudioRecordStartError(
                    WebRtcAudioRecord.AudioRecordStartErrorCode errorCode, String errorMessage) {
                Log.e(TAG, "onWebRtcAudioRecordStartError: " + errorCode + ". " + errorMessage);
                reportError(errorMessage);
            }

            @Override
            public void onWebRtcAudioRecordError(String errorMessage) {
                Log.e(TAG, "onWebRtcAudioRecordError: " + errorMessage);
                reportError(errorMessage);
            }
        });

        WebRtcAudioTrack.setErrorCallback(new WebRtcAudioTrack.WebRtcAudioTrackErrorCallback() {
            @Override
            public void onWebRtcAudioTrackInitError(String errorMessage) {
                Log.e(TAG, "onWebRtcAudioTrackInitError: " + errorMessage);
                reportError(errorMessage);
            }

            @Override
            public void onWebRtcAudioTrackStartError(String errorMessage) {
                Log.e(TAG, "onWebRtcAudioTrackStartError: " + errorMessage);
                reportError(errorMessage);
            }

            @Override
            public void onWebRtcAudioTrackError(String errorMessage) {
                Log.e(TAG, "onWebRtcAudioTrackError: " + errorMessage);
                reportError(errorMessage);
            }
        });

        peerConnectionFactory = new PeerConnectionFactory(null);

        Log.d(TAG, "Peer connection peerConnectionFactory created.");
    }

    private class GenerateOfferSdpTask implements Runnable {

        String connectionId;
        boolean includeLocalMedia;

        private GenerateOfferSdpTask(String connectionId, boolean includeLocalMedia){
            this.connectionId = connectionId;
            this.includeLocalMedia = includeLocalMedia;
        }

        public void run() {
            if (mediaManager.getLocalMediaStream() == null) {
                mediaManager.createMediaConstraints();
                startLocalMediaInternal();
            }

            VRPeerConnection connection = connectionManager.getConnection(connectionId);

            if (connection == null) {
                connection = connectionManager.createPeerConnection(
                                                            iceServers,
                                                            mediaManager.getPcConstraints(),
                                                            connectionId);

                connection.addObserver(VeryRTCPeer.this);

                if (includeLocalMedia) {
                    connection.getPc().addStream(mediaManager.getLocalMediaStream());
                }

                if (videoCallEnabled) {
                    connection.findVideoSender();
                }

                if (peerConnectionParameters.aecDump) {
                    startAecDump();
                }

                if (dataChannelEnabled && peerConnectionParameters.dataChannelParameters != null) {
                    DataChannel.Init init = new DataChannel.Init();
                    init.ordered = peerConnectionParameters.dataChannelParameters.ordered;
                    init.negotiated = peerConnectionParameters.dataChannelParameters.negotiated;
                    init.maxRetransmits = peerConnectionParameters.dataChannelParameters.maxRetransmits;
                    init.maxRetransmitTimeMs = peerConnectionParameters.dataChannelParameters.maxRetransmitTimeMs;
                    init.id = peerConnectionParameters.dataChannelParameters.id;
                    init.protocol = peerConnectionParameters.dataChannelParameters.protocol;
                    createDataChannel(this.connectionId, "RtcData", init);
                }

                // Create offer. Offer SDP will be sent to answering client in
                // PeerConnectionEvents.onLocalDescription event.
                connection.createOffer(mediaManager.getSdpMediaConstraints());

            }
        }
    }

	/**
	* Generate SDP offer
	*
	* @param  connectionId		A unique identifier for the connection
    * @param includeLocalMedia
	*/
    public void generateOffer(String connectionId, boolean includeLocalMedia){
        executor.execute(new GenerateOfferSdpTask(connectionId, includeLocalMedia));
    }

    private class ProcessOfferSdpTask implements Runnable {

        SessionDescription remoteOffer;
        String connectionId;

        private ProcessOfferSdpTask(SessionDescription remoteOffer, String connectionId){
            this.remoteOffer = remoteOffer;
            this.connectionId = connectionId;
        }

        public void run() {
            if (mediaManager.getLocalMediaStream() == null) {
                mediaManager.createMediaConstraints();
                startLocalMediaInternal();
            }

            VRPeerConnection connection = connectionManager.getConnection(connectionId);

            if (connection == null) {
                connection = connectionManager.createPeerConnection(iceServers,
                        mediaManager.getPcConstraints(), connectionId);

                connection.addObserver(VeryRTCPeer.this);

                if (videoCallEnabled) {
                    connection.findVideoSender();
                }

                if (peerConnectionParameters.aecDump) {
                    startAecDump();
                }

                connection.setRemoteDescriptionInternal(remoteOffer);

                // Create answer. Answer SDP will be sent to offering client in
                // PeerConnectionEvents.onLocalDescription event.
                connection.createAnswer(mediaManager.getSdpMediaConstraints());
            }
        }
    }

    /**
     * Processes received SDP offer
     * <p>
     *
     * <p>
     * @param remoteOffer The received offer
     * @param connectionId A unique identifier for the connection
     */
    public void processOffer(final SessionDescription remoteOffer, final String connectionId) {
        executor.execute(new ProcessOfferSdpTask(remoteOffer, connectionId));
    }

    /**
     * Processes received SDP answer
     * @param remoteAnswer The received answer
     * @param connectionId A unique identifier for the connection
     */
    public void processAnswer(final SessionDescription remoteAnswer, final String connectionId) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                VRPeerConnection connection = connectionManager.getConnection(connectionId);

                if (connection != null) {
                    connection.setRemoteDescriptionInternal(remoteAnswer);
                } else {
                    observer.onPeerConnectionError("Connection for id " + connectionId + " cannot be found!");
                }
            }
        });
    }

    /**
     * Adds remote ice candidate for connection
     * @param remoteIceCandidate The received ICE candidate
     * @param connectionId A unique identifier for the connection
     */
    public void addRemoteIceCandidate(IceCandidate remoteIceCandidate, String connectionId) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                VRPeerConnection connection = connectionManager.getConnection(connectionId);

                if (connection != null) {
                    connection.addRemoteIceCandidate(remoteIceCandidate);
                } else {
                    observer.onPeerConnectionError("Connection for id " + connectionId + " cannot be found!");
                }
            }
        });
    }

    private void startAecDump() {
        try {
            aecDumpFileDescriptor =
                    ParcelFileDescriptor.open(new File(Environment.getExternalStorageDirectory().getPath()
                                    + File.separator + "Download/audio.aecdump"),
                            ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE
                                    | ParcelFileDescriptor.MODE_TRUNCATE);

            peerConnectionFactory.startAecDump(aecDumpFileDescriptor.getFd(), -1);
        } catch (IOException e) {
            Log.e(TAG, "Can not open aecdump file", e);
        }
    }

    /**
     * Closes specific connection
     * @param connectionId A unique identifier for the connection
     */
    public void closeConnection(String connectionId){
        if (connectionManager.getConnection(connectionId)==null) {
            return;
        }
        connectionManager.getConnection(connectionId).getPc().removeStream(mediaManager.getLocalMediaStream());
        connectionManager.closeConnection(connectionId);
    }

    @SuppressWarnings("unused")
    public DataChannel getDataChannel(String connectionId, String dataChannelId) {
        return connectionManager.getConnection(connectionId).getDataChannel(dataChannelId);
    }

    private DataChannel createDataChannel(String connectionId, String dataChannelId, DataChannel.Init init) {
        VRPeerConnection connection = connectionManager.getConnection(connectionId);
        if (connection!=null) {
            return connection.createDataChannel(dataChannelId, init);
        }
        else {
            Log.e(TAG, "Cannot find connection by id: " + connectionId);
        }
        return null;
    }

    private boolean startLocalMediaInternal() {
        if (mediaManager != null && mediaManager.getLocalMediaStream() == null) {
            mediaManager.createLocalMediaStream(getRenderContext(), localRenderer);
            mediaManager.startVideoSource();
            return true;
        } else {
            return false;
        }
    }

    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        for (String deviceName : deviceNames) {
            Logging.d(TAG, deviceName);
            final List<CameraEnumerationAndroid.CaptureFormat> formatList =
                    enumerator.getSupportedFormats(deviceName);
            for (CameraEnumerationAndroid.CaptureFormat format:
                    formatList) {
                Logging.d(TAG, format.toString());
            }
        }

        // First, try to find front facing camera
        Logging.d(TAG, "Looking for front facing cameras.");
        // If current camera is set to front and the device has one
        if (peerConnectionParameters.cameraPosition == VeryRTCPeerConfiguration.NBMCameraPosition.FRONT) {
            for (String deviceName : deviceNames) {
                if (enumerator.isFrontFacing(deviceName)) {
                    Logging.d(TAG, "Creating front facing camera capturer.");
                    VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                    if (videoCapturer != null) {
                        currentCameraPosition = VeryRTCPeerConfiguration.NBMCameraPosition.FRONT;
                        return videoCapturer;
                    }
                }
            }
        }
        // If current camera is set to back and the device has one
        else if (peerConnectionParameters.cameraPosition == VeryRTCPeerConfiguration.NBMCameraPosition.BACK) {
            // Front facing camera not found, try something else
            for (String deviceName : deviceNames) {
                if (!enumerator.isFrontFacing(deviceName)) {
                    Logging.d(TAG, "Creating back facing camera capturer.");
                    VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                    if (videoCapturer != null) {
                        currentCameraPosition = VeryRTCPeerConfiguration.NBMCameraPosition.BACK;
                        return videoCapturer;
                    }
                }
            }
        }
        // If current camera is set to any then we pick the faceing-front camera of the device
        else {
            for (String deviceName : deviceNames) {
                if (enumerator.isFrontFacing(deviceName)) {
                    Logging.d(TAG, "Creating other camera capturer.");
                    VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                    if (videoCapturer != null) {
                        currentCameraPosition = VeryRTCPeerConfiguration.NBMCameraPosition.FRONT;
                        return videoCapturer;
                    }
                }
            }
        }

        return null;
    }

    private VideoCapturer createVideoCapturer() {
        VideoCapturer videoCapturer = null;
        /*if (mSettings.getMpcParameters().videoFileAsCameraEnable &&
                !mSettings.getMpcParameters().videoFileAsCamera.isEmpty()) {
            try {
                videoCapturer = new FileVideoCapturer(mSettings.getMpcParameters().videoFileAsCamera);
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        } else */if (screencaptureEnabled) {

        } else if (peerConnectionParameters.useCamera2) {
            if (!captureToTexture) {
                reportError(context.getString(R.string.camera2_texture_only_error));
                return null;
            }

            Logging.d(TAG, "Creating capturer using camera2 API");
            videoCapturer = createCameraCapturer(new Camera2Enumerator(context));
        } else {
            Logging.d(TAG, "Creating capturer using camera1 API");
            videoCapturer = createCameraCapturer(new Camera1Enumerator(captureToTexture));
        }

        if (videoCapturer == null) {
//            reportError("Failed to open camera");
            return null;
        }
        return videoCapturer;
    }

    /**
     * Start local video capture
     */
    public void startVideoSource() {
        if (mediaManager != null) {
            mediaManager.startVideoSource();
        }

        if (cpuMonitor != null) {
            cpuMonitor.resume();
        }
    }

    /**
     * Stop local video capture
     */
    public void stopVideoSource() {
        if (mediaManager != null) {
            mediaManager.stopVideoSource();
        }

        if (cpuMonitor != null) {
            cpuMonitor.pause();
        }
    }

    /**
     * Attaches remote stream to renderer
     * @param remoteRender A render view for rendering the remote media
     * @param remoteStream The remote media stream
     */
    public void attachRendererToRemoteStream(SurfaceViewRenderer remoteRender, MediaStream remoteStream){
        remoteRender.init(getRenderContext(), null);
        remoteRender.setEnableHardwareScaler(true);
        mediaManager.attachRendererToRemoteStream(remoteRender, remoteStream);
    }

    /**
     * Switches camera between front and back
     */
    public void switchCamera(){
        mediaManager.switchCamera();
    }

    /**
     * Check if video is enabled
     * @return true if video is enabled, otherwise false
     */
    @SuppressWarnings("unused")
    public boolean videoEnabled(){
        return mediaManager.getVideoEnabled();
    }

    /**
     * Enable or disable video
     * @param enable If true then video will be enabled, if false then video will be disabled
     */
    public void enableVideo(boolean enable){
        mediaManager.setVideoEnabled(enable);
    }

    /**
     * Check if audio is enabled
     * @return true if audio is enabled, otherwise false
     */
    @SuppressWarnings("unused")
    public boolean audioEnabled(){
        return mediaManager.getAudioEnabled();
    }

    /**
     * Enable or disable audio
     * @param enable If true then audio will be enabled, if false then audio will be disabled
     */
    public void enableAudio(boolean enable){
        mediaManager.setAudioEnabled(enable);
    }

    private void reportError(final String errorMessage) {
        Log.e(TAG, "Peerconnection error: " + errorMessage);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (!isError) {
                    observer.onPeerConnectionError(errorMessage);
                    isError = true;
                }
            }
        });
    }

    private Map<String, String> getReportMap(StatsReport report) {
        Map<String, String> reportMap = new HashMap<String, String>();
        for (StatsReport.Value value : report.values) {
            reportMap.put(value.name, value.value);
        }
        return reportMap;
    }

    private void updateEncoderStatistics(final StatsReport[] reports) {
        StringBuilder encoderStat = new StringBuilder(128);
        StringBuilder bweStat = new StringBuilder();
        StringBuilder connectionStat = new StringBuilder();
        StringBuilder videoSendStat = new StringBuilder();
        StringBuilder videoRecvStat = new StringBuilder();
        String fps = null;
        String targetBitrate = null;
        String actualBitrate = null;

        for (StatsReport report : reports) {
            if (report.type.equals("ssrc") && report.id.contains("ssrc") && report.id.contains("send")) {
                // Send video statistics.
                Map<String, String> reportMap = getReportMap(report);
                String trackId = reportMap.get("googTrackId");
                if (trackId != null && trackId.contains(Constants.VIDEO_TRACK_ID)) {
                    fps = reportMap.get("googFrameRateSent");
                    videoSendStat.append(report.id).append("\n");
                    for (StatsReport.Value value : report.values) {
                        String name = value.name.replace("goog", "");
                        videoSendStat.append(name).append("=").append(value.value).append("\n");
                    }
                }
            } else if (report.type.equals("ssrc") && report.id.contains("ssrc")
                    && report.id.contains("recv")) {
                // Receive video statistics.
                Map<String, String> reportMap = getReportMap(report);
                // Check if this stat is for video track.
                String frameWidth = reportMap.get("googFrameWidthReceived");
                if (frameWidth != null) {
                    videoRecvStat.append(report.id).append("\n");
                    for (StatsReport.Value value : report.values) {
                        String name = value.name.replace("goog", "");
                        videoRecvStat.append(name).append("=").append(value.value).append("\n");
                    }
                }
            } else if (report.id.equals("bweforvideo")) {
                // BWE statistics.
                Map<String, String> reportMap = getReportMap(report);
                targetBitrate = reportMap.get("googTargetEncBitrate");
                actualBitrate = reportMap.get("googActualEncBitrate");

                bweStat.append(report.id).append("\n");
                for (StatsReport.Value value : report.values) {
                    String name = value.name.replace("goog", "").replace("Available", "");
                    bweStat.append(name).append("=").append(value.value).append("\n");
                }
            } else if (report.type.equals("googCandidatePair")) {
                // Connection statistics.
                Map<String, String> reportMap = getReportMap(report);
                String activeConnection = reportMap.get("googActiveConnection");
                if (activeConnection != null && activeConnection.equals("true")) {
                    connectionStat.append(report.id).append("\n");
                    for (StatsReport.Value value : report.values) {
                        String name = value.name.replace("goog", "");
                        connectionStat.append(name).append("=").append(value.value).append("\n");
                    }
                }
            }
        }

        if (videoCallEnabled) {
            if (fps != null) {
                encoderStat.append("Fps:  ").append(fps).append("\n");
            }
            if (targetBitrate != null) {
                encoderStat.append("Target BR: ").append(targetBitrate).append("\n");
            }
            if (actualBitrate != null) {
                encoderStat.append("Actual BR: ").append(actualBitrate).append("\n");
            }
        }

        if (cpuMonitor != null) {
            encoderStat.append("CPU%: ")
                    .append(cpuMonitor.getCpuUsageCurrent())
                    .append("/")
                    .append(cpuMonitor.getCpuUsageAverage())
                    .append(". Freq: ")
                    .append(cpuMonitor.getFrequencyScaleAverage());
        }

        synchronized (observer) {
            observer.onPeerConnectionStatsReady(encoderStat.toString(),
                    bweStat.toString(),
                    connectionStat.toString(),
                    videoSendStat.toString(),
                    videoRecvStat.toString());
        }
    }

    /**
     * WebRTC event which is triggered when local SDP offer has been generated
     *
     * @param localSdpOffer The generated local SDP offer
     * @param connection    The connection for which this event takes place
     */
    @Override
    public void onLocalSdpOfferGenerated(final SessionDescription localSdpOffer, final VRPeerConnection connection) {
        synchronized (observer) {
            observer.onLocalSdpOfferGenerated(localSdpOffer, connection.getConnectionId());
        }

        if (peerConnectionParameters.videoMaxBitrate > 0) {
            connection.setVideoMaxBitrate(peerConnectionParameters.videoMaxBitrate);
        }
    }

    /**
     * WebRTC event which is triggered when local SDP answer has been generated
     *
     * @param localSdpAnswer The generated local SDP answer
     * @param connection     The connection for which this event takes place
     */
    @Override
    public void onLocalSdpAnswerGenerated(final SessionDescription localSdpAnswer, final VRPeerConnection connection) {
        synchronized (observer) {
            observer.onLocalSdpAnswerGenerated(localSdpAnswer, connection.getConnectionId());
        }
        if (peerConnectionParameters.videoMaxBitrate > 0) {
            connection.setVideoMaxBitrate(peerConnectionParameters.videoMaxBitrate);
        }
    }

    /**
     * WebRTC event which is triggered when new ice candidate is received
     *
     * @param localIceCandidate Ice candidate
     * @param connection        The connection for which this event takes place
     */
    @Override
    public void onIceCandidate(final IceCandidate localIceCandidate, final VRPeerConnection connection) {
        synchronized (observer) {
            observer.onIceCandidate(localIceCandidate, connection.getConnectionId());
        }
    }

    /**
     * WebRTC event which is triggered when local ice candidates are removed.
     *
     * @param candidates Ice candidate
     * @param connection The connection for which this event takes place
     */
    @Override
    public void onIceCandidatesRemoved(final IceCandidate[] candidates, final VRPeerConnection connection) {
        synchronized (observer) {
            observer.onIceCandidatesRemoved(candidates, connection.getConnectionId());
        }
    }

    /**
     * WebRTC event which is triggered when ICE status has changed
     *
     * @param newState   The new ICE connection state
     * @param connection The connection for which this event takes place
     */
    @Override
    public void onIceConnectionChange(final IceConnectionState newState, final VRPeerConnection connection) {
        if (newState == IceConnectionState.CONNECTED) {
            connection.enableStatsEvents(true, Constants.STAT_CALLBACK_PERIOD);
            synchronized (observer) {
                observer.onIceConnected(connection.getConnectionId());
            }
        } else if (newState == IceConnectionState.DISCONNECTED) {
            synchronized (observer) {
                observer.onIceDisconnected(connection.getConnectionId());
            }
        } else if (newState == IceConnectionState.FAILED) {
            synchronized (observer) {
                observer.onIceFailed(connection.getConnectionId());
            }
        }
    }

    /**
     * WebRTC event which is triggered when A new remote stream is added to connection
     *
     * @param stream     The new remote media stream
     * @param connection The connection for which this event takes place
     */
    @Override
    public void onRemoteStreamAdded(final MediaStream stream, final VRPeerConnection connection) {
        if (stream.videoTracks.size() != 1) {
            return;
        }

        synchronized (observer) {
            observer.onRemoteStreamAdded(stream, connection.getConnectionId());
        }
    }

    /**
     * WebRTC event which is triggered when a remote media stream is terminated
     *
     * @param stream     The removed remote media stream
     * @param connection The connection for which this event takes place
     */
    @Override
    public void onRemoteStreamRemoved(final MediaStream stream, final VRPeerConnection connection) {
        mediaManager.RemoteStreamRemoved(stream);

        synchronized (observer) {
            observer.onRemoteStreamRemoved(stream, connection.getConnectionId());
        }
    }

    /**
     * WebRTC event which is triggered once peer connection statistics is ready.
     *
     * @param reports
     */
    @Override
    public void onPeerConnectionStatsReady(StatsReport[] reports) {
        updateEncoderStatistics(reports);
    }

    /**
     * WebRTC event which is triggered when there is an error with the connection
     *
     * @param error Error string
     */
    @Override
    public void onPeerConnectionError(final String error) {
        synchronized (observer) {
            observer.onPeerConnectionError(error);
        }
    }

    /**
     * WebRTC event which is triggered when peer opens a data channel
     *
     * @param dataChannel The data channel
     * @param connection  The connection for which the data channel belongs to
     */
    @Override
    public void onDataChannel(final DataChannel dataChannel, final VRPeerConnection connection) {
        // ignore
    }

    /**
     * WebRTC event which is triggered when a data channel buffer amount has changed
     *
     * @param previousAmount          The previous amount
     * @param connection The connection for which the data channel belongs to
     * @param channel    The data channel which triggered the event
     */
    @Override
    public void onBufferedAmountChange(final long previousAmount,
                                       final VRPeerConnection connection,
                                       final DataChannel channel) {
        Log.d(TAG, "Data channel buffered amount changed: " + channel.label() + ": " + channel.state());
        executor.execute(new Runnable() {
            @Override
            public void run() {
                observer.onBufferedAmountChange(previousAmount, connection.getConnectionId(), channel.label());
            }
        });
    }

    /**
     * WebRTC event which is triggered when a data channel state has changed. Possible values:
     * DataChannel.State { CONNECTING, OPEN, CLOSING, CLOSED };
     *
     * @param connection The connection for which the data channel belongs to
     * @param channel    The data channel which triggered the event
     */
    @Override
    public void onStateChange(final VRPeerConnection connection, final DataChannel channel) {
        Log.d(TAG, "Data channel state changed: " + channel.label() + ": " + channel.state());
        executor.execute(new Runnable() {
            @Override
            public void run() {
                observer.onStateChange(connection.getConnectionId(), channel.label(), channel.state());
            }
        });
    }

    /**
     * WebRTC event which is triggered when a message is received from a data channel
     *
     * @param buffer     The message buffer
     * @param connection The connection for which the data channel belongs to
     * @param channel    The data channel which triggered the event
     */
    @Override
    public void onMessage(final DataChannel.Buffer buffer,
                          final VRPeerConnection connection,
                          final DataChannel channel) {
        if (buffer.binary) {
            Log.d(TAG, "Received binary msg over " + channel);
            return;
        }
        ByteBuffer data = buffer.data;
        final byte[] bytes = new byte[data.capacity()];
        data.get(bytes);
        String strData = new String(bytes);
        Log.d(TAG, "Got msg: " + strData + " over " + channel);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                observer.onMessage(buffer, connection.getConnectionId(), channel.label());
            }
        });
    }
}