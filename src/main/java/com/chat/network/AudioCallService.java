package com.chat.network;

import javax.sound.sampled.*;
import java.net.*;

public class AudioCallService {
    private DatagramSocket socket;
    private boolean isCalling = false;

    public void startCall(String targetIp, int targetPort, int localPort) {
        isCalling = true;
        try {
            socket = new DatagramSocket(localPort);

            // Audio Format: 16kHz, 16bit, Mono
            AudioFormat format = new AudioFormat(16000.0f, 16, 1, true, true);

            // Luồng 1: Thu âm từ Micro và Gửi qua UDP Socket
            new Thread(() -> {
                try {
                    TargetDataLine mic = AudioSystem.getTargetDataLine(format);
                    mic.open(format);
                    mic.start();
                    byte[] buffer = new byte[1024];
                    InetAddress address = InetAddress.getByName(targetIp);

                    while (isCalling) {
                        int read = mic.read(buffer, 0, buffer.length);
                        DatagramPacket packet = new DatagramPacket(buffer, read, address, targetPort);
                        socket.send(packet);
                    }
                    mic.close();
                } catch (Exception e) {
                    stopCall();
                }
            }).start();

            // Luồng 2: Nhận gói UDP và Phát ra Loa
            new Thread(() -> {
                try {
                    SourceDataLine speaker = AudioSystem.getSourceDataLine(format);
                    speaker.open(format);
                    speaker.start();
                    byte[] buffer = new byte[1024];

                    while (isCalling) {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        socket.receive(packet);
                        speaker.write(packet.getData(), 0, packet.getLength());
                    }
                    speaker.close();
                } catch (Exception e) {
                    stopCall();
                }
            }).start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stopCall() {
        isCalling = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
}