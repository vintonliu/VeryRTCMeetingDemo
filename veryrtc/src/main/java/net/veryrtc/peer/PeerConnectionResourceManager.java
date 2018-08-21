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

import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import net.veryrtc.peer.VeryRTCPeer.PeerConnectionParameters;
import net.veryrtc.util.LooperExecutor;

/**
 * The class implements the management of PeerConnection instances.
 *
 * The implementation is based on PeerConnectionClient.java of package org.appspot.apprtc
 * (please see the copyright notice below)
 */
final class PeerConnectionResourceManager {
    private static final String TAG = "PCResourceManager";

    private boolean preferIsac;
    private boolean videoCallEnabled;
    private LooperExecutor executor;
    private PeerConnectionFactory factory;
    private HashMap<String,VRPeerConnection> connections;
    private PeerConnectionParameters peerConnectionParameters;
    private String preferredVideoCodec;

    PeerConnectionResourceManager(PeerConnectionParameters peerConnectionParameters,
                                         LooperExecutor executor, PeerConnectionFactory factory) {

        this.peerConnectionParameters = peerConnectionParameters;
        this.executor = executor;
        this.factory = factory;
        videoCallEnabled = peerConnectionParameters.videoCallEnable;

        // Check preferred video codec.
        preferredVideoCodec = Constants.VIDEO_CODEC_VP8;
        if (videoCallEnabled && peerConnectionParameters.videoCodec != null) {
            switch (peerConnectionParameters.videoCodec) {
                case Constants.VIDEO_CODEC_VP8:
                    preferredVideoCodec = Constants.VIDEO_CODEC_VP8;
                    break;
                case Constants.VIDEO_CODEC_VP9:
                    preferredVideoCodec = Constants.VIDEO_CODEC_VP9;
                    break;
                case Constants.VIDEO_CODEC_H264_BASELINE:
                case Constants.VIDEO_CODEC_H264_HIGH:
                    preferredVideoCodec = Constants.VIDEO_CODEC_H264;
                    break;
                default:
                    preferredVideoCodec = Constants.VIDEO_CODEC_H264;
            }
        }
        Log.d(TAG, "Preferred video codec: " + preferredVideoCodec);

        // Check if ISAC is used by default.
        preferIsac = peerConnectionParameters.audioCodec != null &&
                peerConnectionParameters.audioCodec.equals(Constants.AUDIO_CODEC_ISAC);
        connections = new HashMap<>();
    }

    VRPeerConnection createPeerConnection(final List<PeerConnection.IceServer> iceServers,
                                          MediaConstraints pcConstraints,
                                          String connectionId) {

        Log.d(TAG, "Create peer connection.");
        Log.d(TAG, "PCConstraints: " + pcConstraints.toString());

        // TCP candidates are only useful when connecting to a server that supports ICE-TCP.
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA;

        VRPeerConnection connectionWrapper = new VRPeerConnection(connectionId, preferIsac,
                videoCallEnabled, preferredVideoCodec, executor, peerConnectionParameters);
        PeerConnection peerConnection = factory.createPeerConnection(rtcConfig, pcConstraints, connectionWrapper);

        connectionWrapper.setPc(peerConnection);
        connections.put(connectionId, connectionWrapper);

        // Set default WebRTC tracing and INFO libjingle logging.
        // NOTE: this _must_ happen while |factory| is alive!
        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO);
        Log.d(TAG, "Peer connection created.");
        return connectionWrapper;
    }

    VRPeerConnection getConnection(String connectionId){
        return connections.get(connectionId);
    }

    Collection<VRPeerConnection> getConnections(){
        return connections.values();
    }

    void closeConnection(String connectionId){
        VRPeerConnection connection = connections.remove(connectionId);
        if (connection != null) {
            connection.close();
        }
    }

    void closeAllConnections(){
        for(VRPeerConnection connection : connections.values()){
            connection.close();
        }
        connections.clear();
    }

}
