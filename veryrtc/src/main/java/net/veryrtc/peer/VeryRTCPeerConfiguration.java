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

/**
 * Media configuration object used in construction of VeryRTCPeer
 */
public class VeryRTCPeerConfiguration {

    /**
     * Renderer type
     */
    public enum NBMRendererType {
        NATIVE, OPENGLES
    }

    /**
     * Audio codec
     */
    public enum NBMAudioCodec {
        OPUS, ISAC
    }

    /**
     * Video codec
     */
    public enum NBMVideoCodec {
        VP8("VP8"),
        VP9("VP9"),
        H264_BASELINE("H264 Baseline"),
        H264_HIGH("H264 High")
        ;

        String name;
        NBMVideoCodec(String name) {
            this.name = name;
        }


        @Override
        public String toString() {
            return this.name;
        }
    }

    /**
     * Camera position
     * <p>
     * Synonymous to active camera. Currently supports back, front and any cameras
     * </p>
     */
    public enum NBMCameraPosition {
        ANY, BACK, FRONT
    }

    /**
     * Video format struct
     */
    public static class NBMVideoFormat {
        /**
         * Video resolution supported
         */
        public enum Resolution {
            VRDefault,
            VR320x240,
            VR640x480,
            VR1280x720,
            VR1920x1080
        }

        /**
         * Video frame height in pixels
         */
        public final int heigth;
        /**
         * Video frame width in pixels
         */
        public final int width;


        /**
         * Video frames per second
         */
        public final int frameRate;

        public NBMVideoFormat(Resolution rs, int frameRate) {
            switch (rs) {
                case VR320x240:
                    width = 320;
                    heigth = 240;
                    break;
                case VR640x480:
                    width = 640;
                    heigth = 480;
                    break;
                case VR1280x720:
                    width = 1280;
                    heigth = 720;
                    break;
                case VR1920x1080:
                    width = 1920;
                    heigth = 1080;
                    break;
                default:
                    width = 640;
                    heigth = 480;
                    break;
            }

            this.frameRate = frameRate;
        }
    }

    private boolean videoCallEnable;
    private boolean useCamera2;
    private NBMAudioCodec audioCodec;
    private int audioStartBitrate;
    private NBMVideoCodec videoCodec;
    private boolean videoHwCodecEnable;
    private int videoMaxBitrate;
    private NBMCameraPosition cameraPosition;
    private NBMVideoFormat videoCaptureFormat;

    public boolean getVideoCallEnable() {
        return videoCallEnable;
    }
    public boolean getUseCamera2() {
        return useCamera2;
    }
    public NBMCameraPosition getCameraPosition() {
        return cameraPosition;
    }
    public NBMAudioCodec getAudioCodec() {
        return audioCodec;
    }
    public int getAudioStartBitrate() {
        return audioStartBitrate;
    }
    public NBMVideoCodec getVideoCodec() {
        return videoCodec;
    }
    public boolean getVideoHwCodecEnable() {
        return videoHwCodecEnable;
    }
    public int getVideoMaxBitrate() {
        return videoMaxBitrate;
    }
    public NBMVideoFormat getVideoCaptureFormat() {
        return videoCaptureFormat;
    }

    /**
     * Default constructor
     * <p>
     * Default values: <br>
     * videoCallEnable true <br>
     * useCamera2 true <br>
     * rendererType OPENGLES <br>
     * audioCodec OPUS <br>
     * audioStartBitrate unlimited <br>
     * videoCodec H264 <br>
     * videoHwCodecEnable false <br>
     * videoMaxBitrateKbps unlimited <br>
     * videoCaptureFormat <br>
     * width 640 <br>
     * height 480 <br>
     * ImageFormat.NV21 <br>
     * fram rate 15 <br>
     * cameraPosition FRONT
     * </p>
     */
    public VeryRTCPeerConfiguration() {
        videoCallEnable = true;
        useCamera2 = true;
        audioCodec = NBMAudioCodec.OPUS;
        audioStartBitrate = 0;

        videoCodec = NBMVideoCodec.H264_HIGH;
        videoHwCodecEnable = false;
        videoMaxBitrate = 0;

        videoCaptureFormat = new NBMVideoFormat(NBMVideoFormat.Resolution.VR640x480, 15);
        cameraPosition = NBMCameraPosition.FRONT;

    }

    public VeryRTCPeerConfiguration(boolean videoCallEnable,
                                    boolean useCamera2,
                                    NBMAudioCodec audioCodec,
                                    int audioStartBitrate,
                                    NBMVideoCodec videoCodec,
                                    boolean videoHwCodecEnable,
                                    int videoMaxBitrate,
                                    NBMVideoFormat videoCaptureFormat,
                                    NBMCameraPosition cameraPosition) {
        this.videoCallEnable = videoCallEnable;
        this.useCamera2 = useCamera2;
        this.audioCodec = audioCodec;
        this.audioStartBitrate = audioStartBitrate;
        this.videoCodec = videoCodec;
        this.videoHwCodecEnable = videoHwCodecEnable;
        this.videoMaxBitrate = videoMaxBitrate;
        this.videoCaptureFormat = videoCaptureFormat;
        this.cameraPosition = cameraPosition;
    }
}
