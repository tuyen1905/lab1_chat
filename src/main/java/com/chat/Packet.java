package com.chat;

import java.io.Serializable;

public class Packet implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Type {
        LOGIN, TEXT, BROADCAST, FILE, AUDIO, VIDEO_CALL_REQ, USER_LIST, BAN, LOGOUT
    }

    private Type type;
    private String sender;
    private String receiver; // Tên user nhận, "ALL" (Broadcast), hoặc IP máy nhận Video
    private String content;  // Nội dung text, tên file, hoặc IP:Port UDP
    private byte[] data;     // Mảng byte cho File đính kèm hoặc đoạn ghi âm Audio

    public Packet(Type type, String sender, String receiver, String content) {
        this.type = type;
        this.sender = sender;
        this.receiver = receiver;
        this.content = content;
    }

    public Packet(Type type, String sender, String receiver, String content, byte[] data) {
        this(type, sender, receiver, content);
        this.data = data;
    }

    public Type getType() { return type; }
    public String getSender() { return sender; }
    public String getReceiver() { return receiver; }
    public String getContent() { return content; }
    public byte[] getData() { return data; }
}