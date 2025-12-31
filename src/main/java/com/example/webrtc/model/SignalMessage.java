package com.example.webrtc.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SignalMessage {
    private String type;
    private String roomId;
    private String sender;
    private Map<String, Object> data;

    public SignalMessage() {
    }

    public SignalMessage(String type, String roomId, String sender, Map<String, Object> data) {
        this.type = type;
        this.roomId = roomId;
        this.sender = sender;
        this.data = data;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }
}
