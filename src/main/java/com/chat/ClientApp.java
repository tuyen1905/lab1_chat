package com.chat;

import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;

public class ClientApp extends JFrame {
    private static final int SERVER_PORT = 8888;
    private static final String MULTICAST_IP = "230.0.0.1";
    private static final int MULTICAST_PORT = 4446;
    private static final int VIDEO_UDP_PORT = 9999;

    private String username;
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private MulticastSocket multicastSocket;

    private DefaultListModel<String> userListModel;
    private JList<String> userList;
    private JTextArea chatArea;
    private JTextField inputField;
    private JComboBox<String> modeCombo;
    private JLabel remoteVideoLabel;
    private volatile boolean isVideoCalling = false;

    public ClientApp() {
        if (!showLoginDialog()) System.exit(0);
        setTitle("CHAT CLIENT - Xin chào: " + username);
        setSize(980, 620);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        initUI();
        startMulticastListener();
        startVideoReceiver();
    }

    private boolean showLoginDialog() {
        JTextField ipField = new JTextField("192.168.1.10"); // Đổi thành IP Máy thực 1 (Server)
        JTextField userField = new JTextField("May_" + (int)(Math.random() * 100));
        Object[] form = {"IP Máy chủ (Server):", ipField, "Tên hiển thị (Username):", userField};

        int opt = JOptionPane.showConfirmDialog(null, form, "Kết nối tới Server Chat", JOptionPane.OK_CANCEL_OPTION);
        if (opt != JOptionPane.OK_OPTION) return false;

        try {
            this.username = userField.getText().trim();
            socket = new Socket(ipField.getText().trim(), SERVER_PORT);
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());
            out.writeObject(new Packet(Packet.Type.LOGIN, username, "SERVER", "LOGIN"));
            out.flush();
            new Thread(this::listenToServer).start();
            return true;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Không thể kết nối Server: " + e.getMessage(), "Lỗi mạng", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void initUI() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Cột trái: Danh sách Online & Chế độ gửi
        JPanel leftPanel = new JPanel(new BorderLayout(5, 5));
        leftPanel.setPreferredSize(new Dimension(210, 0));
        modeCombo = new JComboBox<>(new String[]{"Nhắn riêng (Unicast)", "Broadcast (Tất cả)", "Multicast (Nhóm 230.0.0.1)"});
        leftPanel.add(modeCombo, BorderLayout.NORTH);

        userListModel = new DefaultListModel<>();
        userList = new JList<>(userListModel);
        userList.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        JScrollPane userScroll = new JScrollPane(userList);
        userScroll.setBorder(BorderFactory.createTitledBorder("Người dùng Online"));
        leftPanel.add(userScroll, BorderLayout.CENTER);
        root.add(leftPanel, BorderLayout.WEST);

        // Cột giữa: Khung chat & Thanh nhập liệu
        JPanel centerPanel = new JPanel(new BorderLayout(5, 5));
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 14)); // Hỗ trợ hiển thị rõ Icon biểu cảm
        chatArea.setLineWrap(true);
        centerPanel.add(new JScrollPane(chatArea), BorderLayout.CENTER);

        // Thanh công cụ đa phương tiện (Emoji, File, Audio, Video)
        JPanel mediaBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton btnEmoji = new JButton("😀 Biểu cảm");
        btnEmoji.addActionListener(e -> showEmojiPopup(btnEmoji));

        JButton btnFile = new JButton("📎 Đính kèm");
        btnFile.addActionListener(e -> sendFileAttachment());

        JButton btnAudio = new JButton("🎤 Ghi âm (5s)");
        btnAudio.addActionListener(e -> recordAndSendAudio());

        JButton btnVideo = new JButton("📹 Gọi Video");
        btnVideo.addActionListener(e -> startVideoCall());

        mediaBar.add(btnEmoji);
        mediaBar.add(btnFile);
        mediaBar.add(btnAudio);
        mediaBar.add(btnVideo);

        JPanel bottomBox = new JPanel(new BorderLayout(5, 5));
        bottomBox.add(mediaBar, BorderLayout.NORTH);
        inputField = new JTextField();
        inputField.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 14));
        inputField.addActionListener(e -> sendTextMessage());
        JButton btnSend = new JButton("Gửi ➤");
        btnSend.addActionListener(e -> sendTextMessage());
        bottomBox.add(inputField, BorderLayout.CENTER);
        bottomBox.add(btnSend, BorderLayout.EAST);

        centerPanel.add(bottomBox, BorderLayout.SOUTH);
        root.add(centerPanel, BorderLayout.CENTER);

        // Cột phải: Khung hiển thị Video Call
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setPreferredSize(new Dimension(260, 0));
        rightPanel.setBorder(BorderFactory.createTitledBorder("Màn hình Video Call"));
        remoteVideoLabel = new JLabel("Chưa có cuộc gọi Video", SwingConstants.CENTER);
        rightPanel.add(remoteVideoLabel, BorderLayout.CENTER);

        JButton btnStopVideo = new JButton("⏹ Dừng Video");
        btnStopVideo.addActionListener(e -> {
            isVideoCalling = false;
            remoteVideoLabel.setIcon(null);
            remoteVideoLabel.setText("Đã kết thúc Video");
        });
        rightPanel.add(btnStopVideo, BorderLayout.SOUTH);
        root.add(rightPanel, BorderLayout.EAST);

        setContentPane(root);
    }

    // 1. Gửi Text (Unicast / Broadcast / Multicast)
    private void sendTextMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;
        int mode = modeCombo.getSelectedIndex();

        try {
            if (mode == 1) { // Broadcast qua Server
                sendPacket(new Packet(Packet.Type.BROADCAST, username, "ALL", text));
                appendChat("[Broadcast - Tôi]: " + text);
            } else if (mode == 2) { // Multicast trực tiếp qua UDP nhóm
                String msg = "[Multicast - " + username + "]: " + text;
                byte[] buf = msg.getBytes("UTF-8");
                DatagramPacket dp = new DatagramPacket(buf, buf.length, InetAddress.getByName(MULTICAST_IP), MULTICAST_PORT);
                multicastSocket.send(dp);
            } else { // Unicast 1-1
                String target = userList.getSelectedValue();
                if (target == null) {
                    JOptionPane.showMessageDialog(this, "Hãy chọn người nhận ở danh sách bên trái!");
                    return;
                }
                sendPacket(new Packet(Packet.Type.TEXT, username, target, text));
                appendChat("[Tôi -> " + target + "]: " + text);
            }
            inputField.setText("");
        } catch (Exception ex) {
            appendChat("[Lỗi gửi tin]: " + ex.getMessage());
        }
    }

    // 2. Bảng chọn Icon biểu cảm
    private void showEmojiPopup(Component invoker) {
        JPopupMenu popup = new JPopupMenu();
        JPanel grid = new JPanel(new GridLayout(3, 5, 4, 4));
        String[] emojis = {"😀", "😂", "😍", "😎", "😭", "😡", "👍", "❤️", "🔥", "🎉", "🙏", "🤝", "💯", "🚀", "✨"};
        for (String em : emojis) {
            JButton b = new JButton(em);
            b.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 16));
            b.addActionListener(e -> {
                inputField.setText(inputField.getText() + em);
                popup.setVisible(false);
                inputField.requestFocus();
            });
            grid.add(b);
        }
        popup.add(grid);
        popup.show(invoker, 0, -110);
    }

    // 3. Đính kèm và gửi File
    private void sendFileAttachment() {
        String target = userList.getSelectedValue();
        if (target == null) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn người nhận file!");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try {
                byte[] fileBytes = java.nio.file.Files.readAllBytes(file.toPath());
                sendPacket(new Packet(Packet.Type.FILE, username, target, file.getName(), fileBytes));
                appendChat("[Tôi -> " + target + "] Đã gửi tệp: " + file.getName());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Lỗi đọc file: " + ex.getMessage());
            }
        }
    }

    // 4. Thu âm Audio 5 giây và gửi
    private void recordAndSendAudio() {
        String target = userList.getSelectedValue();
        if (target == null) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn người nhận tin nhắn thoại!");
            return;
        }
        new Thread(() -> {
            try {
                AudioFormat format = new AudioFormat(16000, 16, 1, true, true);
                TargetDataLine mic = AudioSystem.getTargetDataLine(format);
                mic.open(format);
                mic.start();
                appendChat("[Hệ thống] Đang ghi âm giọng nói trong 5 giây...");

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                long end = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < end) {
                    int count = mic.read(buffer, 0, buffer.length);
                    if (count > 0) baos.write(buffer, 0, count);
                }
                mic.stop();
                mic.close();

                sendPacket(new Packet(Packet.Type.AUDIO, username, target, "VoiceMessage.wav", baos.toByteArray()));
                appendChat("[Tôi -> " + target + "] Đã gửi tin nhắn thoại (5s) 🎤");
            } catch (Exception ex) {
                appendChat("[Lỗi Micro]: " + ex.getMessage());
            }
        }).start();
    }

    private void playAudio(byte[] audioData) {
        new Thread(() -> {
            try {
                AudioFormat format = new AudioFormat(16000, 16, 1, true, true);
                SourceDataLine speaker = AudioSystem.getSourceDataLine(format);
                speaker.open(format);
                speaker.start();
                speaker.write(audioData, 0, audioData.length);
                speaker.drain();
                speaker.close();
            } catch (Exception ignored) {}
        }).start();
    }

    // 5. Gọi Video & Truyền luồng khung hình qua UDP
    private void startVideoCall() {
        String target = userList.getSelectedValue();
        if (target == null) {
            JOptionPane.showMessageDialog(this, "Chọn người muốn gọi Video!");
            return;
        }
        sendPacket(new Packet(Packet.Type.VIDEO_CALL_REQ, username, target, "REQUEST"));
        appendChat("[Video] Đang gửi yêu cầu gọi Video tới " + target + "...");
    }

    private void startVideoSender(String targetIP) {
        isVideoCalling = true;
        new Thread(() -> {
            try (DatagramSocket udpSocket = new DatagramSocket()) {
                InetAddress addr = InetAddress.getByName(targetIP);
                Robot robot = new Robot(); // Dùng Robot chụp luồng hình ảnh (hoặc thay bằng Webcam.getDefault().getImage())
                Rectangle captureRect = new Rectangle(0, 0, 480, 360);

                while (isVideoCalling) {
                    BufferedImage frame = robot.createScreenCapture(captureRect);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(frame, "jpg", baos);
                    byte[] imgBytes = baos.toByteArray();
                    if (imgBytes.length < 60000) {
                        udpSocket.send(new DatagramPacket(imgBytes, imgBytes.length, addr, VIDEO_UDP_PORT));
                    }
                    Thread.sleep(100); // ~10 FPS mượt mà qua LAN
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void startVideoReceiver() {
        new Thread(() -> {
            try (DatagramSocket udpSocket = new DatagramSocket(VIDEO_UDP_PORT)) {
                byte[] buf = new byte[65000];
                while (true) {
                    DatagramPacket dp = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(dp);
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(dp.getData(), 0, dp.getLength()));
                    if (img != null) {
                        Image scaled = img.getScaledInstance(240, 180, Image.SCALE_FAST);
                        SwingUtilities.invokeLater(() -> {
                            remoteVideoLabel.setText("");
                            remoteVideoLabel.setIcon(new ImageIcon(scaled));
                        });
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }

    // Lắng nghe gói tin Multicast trên LAN
    private void startMulticastListener() {
        new Thread(() -> {
            try {
                multicastSocket = new MulticastSocket(MULTICAST_PORT);
                InetAddress group = InetAddress.getByName(MULTICAST_IP);
                multicastSocket.joinGroup(group);
                byte[] buf = new byte[2048];
                while (true) {
                    DatagramPacket dp = new DatagramPacket(buf, buf.length);
                    multicastSocket.receive(dp);
                    String msg = new String(dp.getData(), 0, dp.getLength(), "UTF-8");
                    appendChat(msg);
                }
            } catch (Exception e) {
                appendChat("[Multicast] Lỗi tham gia nhóm: " + e.getMessage());
            }
        }).start();
    }

    // Lắng nghe dữ liệu từ TCP Server
    private void listenToServer() {
        try {
            while (!socket.isClosed()) {
                Packet p = (Packet) in.readObject();
                switch (p.getType()) {
                    case USER_LIST -> SwingUtilities.invokeLater(() -> {
                        String selected = userList.getSelectedValue();
                        userListModel.clear();
                        for (String u : p.getContent().split(",")) {
                            if (!u.isEmpty() && !u.equals(username)) userListModel.addElement(u);
                        }
                        if (selected != null) userList.setSelectedValue(selected, true);
                    });
                    case TEXT -> appendChat("[" + p.getSender() + "]: " + p.getContent());
                    case BROADCAST -> appendChat("[Broadcast - " + p.getSender() + "]: " + p.getContent());
                    case FILE -> {
                        appendChat("[" + p.getSender() + "] đã gửi file: " + p.getContent());
                        SwingUtilities.invokeLater(() -> {
                            int ans = JOptionPane.showConfirmDialog(this,
                                    p.getSender() + " gửi tệp '" + p.getContent() + "'. Bạn có muốn lưu không?");
                            if (ans == JOptionPane.YES_OPTION) {
                                JFileChooser saver = new JFileChooser();
                                saver.setSelectedFile(new File(p.getContent()));
                                if (saver.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                                    try {
                                        java.nio.file.Files.write(saver.getSelectedFile().toPath(), p.getData());
                                    } catch (IOException ignored) {}
                                }
                            }
                        });
                    }
                    case AUDIO -> {
                        appendChat("[" + p.getSender() + "] đã gửi một tin nhắn thoại 🔊 (Đang phát...)");
                        playAudio(p.getData());
                    }
                    case VIDEO_CALL_REQ -> {
                        String callerIP = p.getContent();
                        appendChat("[Video] Đang kết nối hình ảnh với " + p.getSender() + " (" + callerIP + ")");
                        startVideoSender(callerIP);
                    }
                    case BAN -> {
                        JOptionPane.showMessageDialog(this, p.getContent(), "BỊ CẤM TRUY CẬP", JOptionPane.ERROR_MESSAGE);
                        System.exit(0);
                    }
                }
            }
        } catch (Exception e) {
            appendChat("[Mất kết nối tới Server]");
        }
    }

    private void sendPacket(Packet p) {
        try {
            out.writeObject(p);
            out.flush();
            out.reset();
        } catch (IOException ignored) {}
    }

    private void appendChat(String msg) {
        SwingUtilities.invokeLater(() -> {
            chatArea.append(msg + "\n");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel"); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new ClientApp().setVisible(true));
    }
}