package com.chat.client;

import com.chat.network.AudioCallService;
import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.net.Socket;

public class ClientGUI extends JFrame {
    private String currentUsername;
    private Socket socket;
    private DataInputStream dis;
    private DataOutputStream dos;
    private AudioCallService audioService = new AudioCallService();

    private DefaultListModel<String> userListModel = new DefaultListModel<>();
    private JList<String> userList = new JList<>(userListModel);
    private JPanel chatBoxPanel = new JPanel();
    private JTextField txtMessage = new JTextField();
    private JLabel lblHeader = new JLabel("Chọn người dùng hoặc Phòng Chung để chat");

    public ClientGUI(String username) {
        this.currentUsername = username;
        initGUI();
        connectServer();
    }

    private void initGUI() {
        setTitle("Zalo Chat - " + currentUsername);
        setSize(1000, 680);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // --- BÊN TRÁI: DANH SÁCH USER ---
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setPreferredSize(new Dimension(260, 0));

        JLabel lblOnline = new JLabel("  Danh sách Online", SwingConstants.LEFT);
        lblOnline.setFont(new Font("Segoe UI", Font.BOLD, 16));
        lblOnline.setPreferredSize(new Dimension(260, 50));
        leftPanel.add(lblOnline, BorderLayout.NORTH);

        userList.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        userList.setFixedCellHeight(42);
        leftPanel.add(new JScrollPane(userList), BorderLayout.CENTER);

        // --- BÊN PHẢI: KHUNG CHAT ---
        JPanel rightPanel = new JPanel(new BorderLayout());

        // Header + Nút Gọi Audio
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBorder(new EmptyBorder(10, 15, 10, 15));
        lblHeader.setFont(new Font("Segoe UI", Font.BOLD, 15));

        JButton btnCall = new JButton("📞 Voice Call (UDP)");
        btnCall.setBackground(new Color(40, 167, 69));
        btnCall.setForeground(Color.WHITE);
        btnCall.addActionListener(e -> startVoiceCall());

        headerPanel.add(lblHeader, BorderLayout.WEST);
        headerPanel.add(btnCall, BorderLayout.EAST);
        rightPanel.add(headerPanel, BorderLayout.NORTH);

        // Khung hiển thị Tin nhắn
        chatBoxPanel.setLayout(new BoxLayout(chatBoxPanel, BoxLayout.Y_AXIS));
        JScrollPane scrollPane = new JScrollPane(chatBoxPanel);
        rightPanel.add(scrollPane, BorderLayout.CENTER);

        // Thanh công cụ Nhập tin nhắn & File & Emoji[cite: 1]
        JPanel inputContainer = new JPanel(new BorderLayout(8, 8));
        inputContainer.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel toolsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JButton btnFile = new JButton("📎 File");
        JButton btnEmoji = new JButton("😊 Emoji");
        toolsPanel.add(btnFile);
        toolsPanel.add(btnEmoji);

        inputContainer.add(toolsPanel, BorderLayout.NORTH);

        txtMessage.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        JButton btnSend = new JButton("Gửi");
        btnSend.setBackground(new Color(0, 104, 255)); // Zalo Blue
        btnSend.setForeground(Color.WHITE);

        inputContainer.add(txtMessage, BorderLayout.CENTER);
        inputContainer.add(btnSend, BorderLayout.EAST);
        rightPanel.add(inputContainer, BorderLayout.SOUTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        splitPane.setDividerLocation(260);
        add(splitPane);

        // Xử lý Sự kiện
        btnSend.addActionListener(e -> sendMessage());
        txtMessage.addActionListener(e -> sendMessage());
        btnFile.addActionListener(e -> sendFile());
        btnEmoji.addActionListener(e -> showEmojiPicker());
        userList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && userList.getSelectedValue() != null) {
                lblHeader.setText("Đang chat với: " + userList.getSelectedValue());
            }
        });
    }

    private void connectServer() {
        try {
            socket = new Socket("localhost", 12345);
            dis = new DataInputStream(socket.getInputStream());
            dos = new DataOutputStream(socket.getOutputStream());

            dos.writeUTF(currentUsername);

            new Thread(this::listenServer).start();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Không thể kết nối tới Chat Server!");
        }
    }

    private void listenServer() {
        try {
            while (true) {
                String cmd = dis.readUTF();
                if ("USER_LIST".equals(cmd)) {
                    String[] users = dis.readUTF().split(",");
                    SwingUtilities.invokeLater(() -> {
                        userListModel.clear();
                        userListModel.addElement("Phòng Chung (Broadcast)");
                        for (String u : users) {
                            if (!u.equals(currentUsername)) userListModel.addElement(u);
                        }
                    });
                } else if ("TEXT".equals(cmd) || "BROADCAST".equals(cmd)) {
                    String sender = dis.readUTF();
                    String msg = dis.readUTF();
                    SwingUtilities.invokeLater(() -> addBubble("[" + sender + "]: " + msg, false));
                } else if ("FILE".equals(cmd)) {
                    String sender = dis.readUTF();
                    String fileName = dis.readUTF();
                    SwingUtilities.invokeLater(() -> addBubble("[" + sender + "] đã gửi file: " + fileName, false));
                }
            }
        } catch (IOException e) {
            System.out.println("Mất kết nối Server.");
        }
    }

    private void sendMessage() {
        String msg = txtMessage.getText().trim();
        String target = userList.getSelectedValue();
        if (msg.isEmpty() || target == null) return;

        try {
            if ("Phòng Chung (Broadcast)".equals(target)) {
                dos.writeUTF("BROADCAST");
                dos.writeUTF(msg);
            } else {
                dos.writeUTF("TEXT");
                dos.writeUTF(target);
                dos.writeUTF(msg);
                addBubble("Tôi: " + msg, true);
            }
            txtMessage.setText("");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendFile() {
        JFileChooser chooser = new JFileChooser();
        String target = userList.getSelectedValue();
        if (target == null || "Phòng Chung (Broadcast)".equals(target)) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn 1 người dùng cụ thể để gửi file!");
            return;
        }

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            new Thread(() -> {
                try {
                    dos.writeUTF("FILE");
                    dos.writeUTF(target);
                    dos.writeUTF(file.getName());
                    dos.writeLong(file.length());

                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[4096];
                        int read;
                        while ((read = fis.read(buffer)) != -1) {
                            dos.write(buffer, 0, read);
                        }
                    }
                    dos.flush();
                    SwingUtilities.invokeLater(() -> addBubble("Đã gửi file: " + file.getName(), true));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }

    private void showEmojiPicker() {
        JPopupMenu menu = new JPopupMenu();
        JPanel p = new JPanel(new GridLayout(2, 4, 4, 4));
        String[] emojis = {"😊", "😂", "❤️", "👍", "🔥", "🎉", "💩", "😍"};
        for (String emo : emojis) {
            JButton btn = new JButton(emo);
            btn.addActionListener(e -> {
                txtMessage.setText(txtMessage.getText() + emo);
                menu.setVisible(false);
            });
            p.add(btn);
        }
        menu.add(p);
        menu.show(txtMessage, 0, -60);
    }

    private void startVoiceCall() {
        JOptionPane.showMessageDialog(this, "Đã bật Microphone & Loa cho cuộc gọi UDP thời gian thực!");
        audioService.startCall("127.0.0.1", 6000, 6001);
    }

    private void addBubble(String message, boolean isSelf) {
        JLabel lbl = new JLabel("<html><body style='width: 220px; padding: 4px;'>" + message + "</body></html>");
        lbl.setOpaque(true);
        lbl.setBackground(isSelf ? new Color(0, 114, 245) : new Color(50, 50, 50));
        lbl.setForeground(Color.WHITE);
        lbl.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        lbl.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

        JPanel p = new JPanel(new FlowLayout(isSelf ? FlowLayout.RIGHT : FlowLayout.LEFT));
        p.setOpaque(false);
        p.add(lbl);

        chatBoxPanel.add(p);
        chatBoxPanel.revalidate();
        chatBoxPanel.repaint();
    }

    public static void main(String[] args) {
        FlatDarkLaf.setup();
        String user = JOptionPane.showInputDialog("Nhập tên đăng nhập (user1 -> user6):");
        if (user != null && !user.trim().isEmpty()) {
            SwingUtilities.invokeLater(() -> new ClientGUI(user).setVisible(true));
        }
    }
}