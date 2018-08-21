/*
 *  Copyright 2013 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.webrtc;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.Locale;

/**
 * Description of an RFC 4566 Session.
 * SDPs are passed as serialized Strings in Java-land and are materialized
 * to SessionDescriptionInterface as appropriate in the JNI layer.
 */
public class SessionDescription implements Serializable{
  private final static String kSessionDescriptionTypeName = "type";
  private final static String kSessionDescriptionSdpName = "sdp";

    /**
     * Java-land enum version of SessionDescriptionInterface's type() string.
     */
  public static enum Type {
    OFFER,
    PRANSWER,
    ANSWER;

    public String canonicalForm() {
      return name().toLowerCase(Locale.US);
    }

    public static Type fromCanonicalForm(String canonical) {
      return Type.valueOf(Type.class, canonical.toUpperCase(Locale.US));
    }
  }

  public Type type;
  public String description;

  public SessionDescription(Type type, String description) {
    this.type = type;
    this.description = description;
  }

  public SessionDescription(String sdp) {
    try {
      JSONObject json = new JSONObject(sdp);
      type = Type.fromCanonicalForm(json.getString(kSessionDescriptionTypeName));
      description = json.getString(kSessionDescriptionSdpName);
    } catch (JSONException e) {
      e.printStackTrace();
    }
  }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean containVideo() {
        int index = description.indexOf("m=video");
        if (index > 0) {
            String videoStr = description.substring(index);
            return !videoStr.contains("a=inactive");
        }

        return false;
    }

  @Override
  public String toString() {
    try {
      JSONObject json = new JSONObject();
      jsonPut(json, kSessionDescriptionTypeName, type);
      jsonPut(json, kSessionDescriptionSdpName, description);
      return json.toString();
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }

  // Put a |key|->|value| mapping in |json|.
  private static void jsonPut(JSONObject json, String key, Object value) {
    try {
      json.put(key, value);
    } catch (JSONException e) {
      throw new RuntimeException(e);
    }
  }
}
