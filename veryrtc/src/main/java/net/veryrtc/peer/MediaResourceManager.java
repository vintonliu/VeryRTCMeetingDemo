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
import android.util.Log;

import net.veryrtc.peer.VeryRTCPeerConfiguration.NBMCameraPosition;
import net.veryrtc.util.LooperExecutor;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.EglBase;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoSink;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.HashMap;

/**
 * The class implements the management of media resources.
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


final class MediaResourceManager {
    private static final String TAG = "MediaResourceManager";

    private static class ProxyRenderer implements VideoRenderer.Callbacks {
        private VideoRenderer.Callbacks target;

        @Override
        synchronized public void renderFrame(VideoRenderer.I420Frame frame) {
            if (target == null) {
                Logging.d(TAG, "Dropping frame in proxy because target is null.");
                VideoRenderer.renderFrameDone(frame);
                return;
            }

            target.renderFrame(frame);
        }

        synchronized public void setTarget(VideoRenderer.Callbacks target) {
            this.target = target;
        }
    }

    private static class ProxyVideoSink implements VideoSink {
        private VideoSink target;

        @Override
        synchronized public void onFrame(VideoFrame frame) {
            if (target == null) {
                Logging.d(TAG, "Dropping frame in proxy because target is null.");
                return;
            }

            target.onFrame(frame);
        }

        synchronized public void setTarget(VideoSink target) {
            this.target = target;
        }
    }

    private LooperExecutor executor;
    private PeerConnectionFactory factory;
    private MediaConstraints pcConstraints;
    private MediaConstraints audioConstraints;
    private MediaConstraints sdpMediaConstraints;
    private boolean videoCallEnabled;
    private boolean renderVideo;
    private boolean videoCapturerStopped;
    private MediaStream localMediaStream;
    private AudioSource audioSource;
    // enableAudio is set to true if audio should be sent.
    private boolean enableAudio;
    private AudioTrack localAudioTrack;
    private VideoSource videoSource;
    private VideoTrack localVideoTrack;
    private HashMap<MediaStream, VideoTrack> remoteVideoTracks;
    private ProxyRenderer remoteProxyRenderer;
    private final ProxyVideoSink localProxyVideoSink = new ProxyVideoSink();
    private HashMap<VideoRenderer.Callbacks, VideoRenderer> remoteVideoRenderers;
    private HashMap<VideoRenderer, MediaStream> remoteVideoMediaStreams;

    private VeryRTCPeer.PeerConnectionParameters peerConnectionParameters;
    private VideoCapturer videoCapturer;
    private NBMCameraPosition currentCameraPosition;
    private SurfaceViewRenderer pipRenderer;
    private Context context;

    MediaResourceManager(Context context, VeryRTCPeer.PeerConnectionParameters peerConnectionParameters,
                         LooperExecutor executor, PeerConnectionFactory factory, VideoCapturer videoCapturer){
        this.context = context;
        this.peerConnectionParameters = peerConnectionParameters;
        this.localMediaStream = null;
        this.executor = executor;
        this.factory = factory;
        this.videoCapturer = videoCapturer;
        videoCallEnabled = peerConnectionParameters.videoCallEnable;
        renderVideo = true;
        remoteVideoTracks = new HashMap<>();
        remoteVideoRenderers = new HashMap<>();
        remoteVideoMediaStreams = new HashMap<>();
        enableAudio = true;
        localAudioTrack = null;
    }

    void createMediaConstraints() {
        // Create peer connection constraints.
        pcConstraints = new MediaConstraints();

        // Enable DTLS for normal calls and disable for loopback calls.
        if (peerConnectionParameters.loopback) {
            pcConstraints.optional.add(new MediaConstraints.KeyValuePair(Constants.DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "false"));
        } else {
            pcConstraints.optional.add(new MediaConstraints.KeyValuePair(Constants.DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "true"));
        }

//        pcConstraints.optional.add(new MediaConstraints.KeyValuePair("internalSctpDataChannels", "true"));

        // Check if there is a camera on device and disable video call if not.
        if (videoCapturer == null) {
            Log.w(TAG, "No camera on device. Switch to audio only call.");
            videoCallEnabled = false;
        }
        // Create video constraints if video call is enabled.
        if (videoCallEnabled) {
            int videoWidth = peerConnectionParameters.videoWidth;
            int videoHeight = peerConnectionParameters.videoHeight;
            // If video resolution is not specified, default to HD.
            if (videoWidth == 0 || videoHeight == 0) {
                videoWidth = Constants.HD_VIDEO_WIDTH;
                videoHeight = Constants.HD_VIDEO_HEIGHT;
            }

            // Add fps constraints.
            int videoFps = peerConnectionParameters.videoFps;
            // If fps is not specified, default to 30.
            if (videoFps == 0) {
                videoFps = 15;
            }

            Logging.d(TAG, "Capturing format: " + videoWidth + "x" + videoHeight + "@" + videoFps);
        }

        // Create audio constraints.
        audioConstraints = new MediaConstraints();
        // added for audio performance measurements
        if (peerConnectionParameters.noAudioProcessing) {
            Log.d(TAG, "Disabling audio processing");
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(Constants.AUDIO_ECHO_CANCELLATION_CONSTRAINT, "false"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(Constants.AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, "false"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(Constants.AUDIO_HIGH_PASS_FILTER_CONSTRAINT, "false"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(Constants.AUDIO_NOISE_SUPPRESSION_CONSTRAINT, "false"));
        }
        if (peerConnectionParameters.enableLevelControl) {
            Log.d(TAG, "Enabling level control.");
            audioConstraints.mandatory.add(
                    new MediaConstraints.KeyValuePair(Constants.AUDIO_LEVEL_CONTROL_CONSTRAINT, "true"));
        }
        // Create SDP constraints.
        sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        if (videoCallEnabled || peerConnectionParameters.loopback) {
            sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        } else {
            sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        }
    }

    public MediaConstraints getPcConstraints(){
        return pcConstraints;
    }

    public MediaConstraints getSdpMediaConstraints(){
        return sdpMediaConstraints;
    }

    public MediaStream getLocalMediaStream() {
        return localMediaStream;
    }

    public void stopVideoSource() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (videoCapturer != null && !videoCapturerStopped) {
                    Log.d(TAG, "Stop video source.");
                    try {
                        videoCapturer.stopCapture();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    videoCapturerStopped = true;
                }
            }
        });
    }

    public void startVideoSource() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (videoCapturer != null && videoCapturerStopped) {
                    Log.d(TAG, "Restart video source.");
                    videoCapturer.startCapture(peerConnectionParameters.videoWidth,
                            peerConnectionParameters.videoHeight,
                            peerConnectionParameters.videoFps);
                    videoCapturerStopped = false;
                }
            }
        });
    }

    private AudioTrack createAudioTrack() {
        audioSource = factory.createAudioSource(audioConstraints);
        localAudioTrack = factory.createAudioTrack(Constants.AUDIO_TRACK_ID, audioSource);
        localAudioTrack.setEnabled(enableAudio);
        return localAudioTrack;
    }

    private VideoTrack createVideoTrack(VideoCapturer capturer) {
        videoSource = factory.createVideoSource(capturer);

        capturer.startCapture(peerConnectionParameters.videoWidth,
                              peerConnectionParameters.videoHeight,
                              peerConnectionParameters.videoFps);

        localVideoTrack = factory.createVideoTrack(Constants.VIDEO_TRACK_ID, videoSource);
        localVideoTrack.setEnabled(renderVideo);
        localVideoTrack.addSink(localProxyVideoSink);
        return localVideoTrack;
    }

    private class AttachRendererTask implements Runnable {
        private VideoRenderer.Callbacks remoteRender;
        private MediaStream remoteStream;

        private AttachRendererTask(VideoRenderer.Callbacks remoteRender, MediaStream remoteStream){
            this.remoteRender = remoteRender;
            this.remoteStream = remoteStream;
        }
        public void run() {
            Log.d(TAG, "Attaching VideoRenderer to remote stream (" + remoteStream + ")");

            // Check if the remote stream has a video track
            if (remoteStream.videoTracks.size() == 1) {
                // Get the video track
                VideoTrack remoteVideoTrack = remoteStream.videoTracks.get(0);
                // Set video track enabled if we have enabled video rendering
                remoteVideoTrack.setEnabled(renderVideo);

                VideoRenderer videoRenderer = remoteVideoRenderers.get(remoteRender);
                if (videoRenderer != null) {
                    MediaStream mediaStream = remoteVideoMediaStreams.get(videoRenderer);
                    if (mediaStream != null) {
                        VideoTrack videoTrack = remoteVideoTracks.get(mediaStream);
                        if (videoTrack != null) {
                            videoTrack.removeRenderer(videoRenderer);
                        }
                    }
                }

                VideoRenderer newVideoRenderer = new VideoRenderer(remoteRender);
                remoteVideoTrack.addRenderer(newVideoRenderer);
                remoteVideoRenderers.put(remoteRender, newVideoRenderer);
                remoteVideoMediaStreams.put(newVideoRenderer, remoteStream);
                remoteVideoTracks.put(remoteStream, remoteVideoTrack);
                Log.d(TAG, "Attached.");
            }
        }
    }

    public void attachRendererToRemoteStream(VideoRenderer.Callbacks remoteRender, MediaStream remoteStream){
        Log.d(TAG, "Schedule attaching VideoRenderer to remote stream (" + remoteStream + ")");
        executor.execute(new AttachRendererTask(remoteRender, remoteStream));
    }

    public void createLocalMediaStream(EglBase.Context renderEGLContext, final SurfaceViewRenderer localRender) {
        if (factory == null) {
            Log.e(TAG, "Peerconnection factory is not created");
            return;
        }

        if (videoCallEnabled) {
            factory.setVideoHwAccelerationOptions(renderEGLContext, renderEGLContext);

            pipRenderer = localRender;
            pipRenderer.init(renderEGLContext, null);
            pipRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
            pipRenderer.setZOrderMediaOverlay(true);
            pipRenderer.setEnableHardwareScaler(true);
            localProxyVideoSink.setTarget(pipRenderer);
        }

        // Set default WebRTC tracing and INFO libjingle logging.
        // NOTE: this _must_ happen while |factory| is alive!
        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO);

        localMediaStream = factory.createLocalMediaStream(Constants.MEDIA_STREAM_ID);

        // If video call is enabled and the device has camera(s)
        if (videoCallEnabled) {
            localMediaStream.addTrack(createVideoTrack(videoCapturer));
        }

        // Create audio track
        localMediaStream.addTrack(createAudioTrack());

        Log.d(TAG, "Local media stream created.");
    }

    private void switchCameraInternal() {
        if (videoCapturer instanceof CameraVideoCapturer) {
            if (!videoCallEnabled || videoCapturer == null) {
                Log.e(TAG, "Failed to switch camera. Video: " + videoCallEnabled);
                return; // No video is sent or only one camera is available or error happened.
            }
            Log.d(TAG, "Switch camera");
            CameraVideoCapturer cameraVideoCapturer = (CameraVideoCapturer) videoCapturer;
            cameraVideoCapturer.switchCamera(null);
        } else {
            Log.d(TAG, "Will not switch camera, video caputurer is not a camera");
        }
    }

    public void switchCamera(){
        executor.execute(new Runnable() {
            @Override
            public void run() {
                switchCameraInternal();
            }
        });
    }

    public void changeCaptureFormat(final int width, final int height, final int framerate) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                changeCaptureFormatInternal(width, height, framerate);
            }
        });
    }

    private void changeCaptureFormatInternal(int width, int height, int framerate) {
        if (!videoCallEnabled || videoCapturer == null) {
            Log.e(TAG,
                    "Failed to change capture format. Video: " + videoCallEnabled);
            return;
        }
        Log.d(TAG, "changeCaptureFormat: " + width + "x" + height + "@" + framerate);
        videoSource.adaptOutputFormat(width, height, framerate);
    }

    public void setAudioEnabled(final boolean enable) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                enableAudio = enable;
                if (localAudioTrack != null) {
                    localAudioTrack.setEnabled(enableAudio);
                }
            }
        });
    }

    void setVideoEnabled(final boolean enable) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                renderVideo = enable;
                if (localVideoTrack != null) {
                    localVideoTrack.setEnabled(renderVideo);
                }
                for (VideoTrack tv : remoteVideoTracks.values()) {
                    tv.setEnabled(renderVideo);
                }
            }
        });
    }

    boolean getVideoEnabled(){
        return renderVideo;
    }

    boolean getAudioEnabled() {
        return enableAudio;
    }

    void close(){
        // Uncomment only if you know what you are doing
        localMediaStream.dispose();
        localMediaStream = null;

        Log.d(TAG, "Closing audio source.");
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }

        Log.d(TAG, "Stopping capture.");
        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            videoCapturerStopped = true;
            videoCapturer.dispose();
            videoCapturer = null;
        }

        Log.d(TAG, "Closing video source.");
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }

        if (pipRenderer != null) {
            pipRenderer.release();
            pipRenderer = null;
        }
    }

    public void RemoteStreamRemoved(MediaStream stream) {
        remoteVideoTracks.remove(stream);
    }
}
