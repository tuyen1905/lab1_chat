package com.chat.server;

import com.chat.db.Database;
import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class ChatServer extends JFrame {
    private static final int PORT = 12345;
    private static final Map<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private boolean isRunning = false;

    // Thành phần Giao diện GUI
    private final JLabel lblStatus = new JLabel("Trạng thái: Đã dừng");
    private final JTextArea txtLog = new JTextArea();
    private final DefaultListModel<String> clientListModel = new DefaultListModel<>();
    private final JList<String> lstClients = new JList<>(clientListModel);
    private final JButton btnStartStop = new JButton("Khởi động Server");
    private final JButton btnKick = new JButton("🚫 Kick Client");

    public ChatServer() {
        initUI();
        Database.initDatabase();
    }

    private void initUI() {
        setTitle("Zalo Chat - Server Control Panel (Java 23)");
        setSize(920, 580);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        // --- THANH TRẠNG THÁI TRÊN CÙNG ---
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBorder(new EmptyBorder(12, 15, 12, 15));

        lblStatus.setFont(new Font("Segoe UI", Font.BOLD, 16));
        lblStatus.setForeground(new Color(220, 53, 69)); // Đỏ

        btnStartStop.setFont(new Font("Segoe UI", Font.BOLD, 14));
        btnStartStop.setBackground(new Color(0, 122, 255)); // Blue
        btnStartStop.setForeground(Color.WHITE);
        btnStartStop.addActionListener(e -> toggleServer());

        headerPanel.add(lblStatus, BorderLayout.WEST);
        headerPanel.add(btnStartStop, BorderLayout.EAST);
        add(headerPanel, BorderLayout.NORTH);

        // --- NỘI DUNG CHÍNH (LOG & DANH SÁCH CLIENT) ---
        JPanel centerPanel = new JPanel(new BorderLayout(10, 10));
        centerPanel.setBorder(new EmptyBorder(0, 15, 15, 15));

        // Nhật ký Server (Bên trái)
        txtLog.setEditable(false);
        txtLog.setFont(new Font("Consolas", Font.PLAIN, 13));
        JScrollPane logScroll = new JScrollPane(txtLog);
        logScroll.setBorder(BorderFactory.createTitledBorder("Nhật ký Server (Activity Log)"));

        // Danh sách Client & Nút Kick (Bên phải)
        JPanel rightPanel = new JPanel(new BorderLayout(8, 8));
        rightPanel.setPreferredSize(new Dimension(260, 0));
        rightPanel.setBorder(BorderFactory.createTitledBorder("Client Đang Kết Nối"));

        lstClients.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        lstClients.setFixedCellHeight(38);
        rightPanel.add(new JScrollPane(lstClients), BorderLayout.CENTER);

        btnKick.setBackground(new Color(220, 53, 69)); // Red button
        btnKick.setForeground(Color.WHITE);
        btnKick.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btnKick.addActionListener(e -> kickSelectedClient());
        rightPanel.add(btnKick, BorderLayout.SOUTH);

        centerPanel.add(logScroll, BorderLayout.CENTER);
        centerPanel.add(rightPanel, BorderLayout.EAST);

        add(centerPanel, BorderLayout.CENTER);
    }

    private synchronized void toggleServer() {
        if (!isRunning) {
            startServer();
        } else {
            stopServer();
        }
    }

    private void startServer() {
        try {
            serverSocket = new ServerSocket(PORT);
            executor = Executors.newVirtualThreadPerTaskExecutor(); // Virtual Threads Java 23
            isRunning = true;

            lblStatus.setText("Trạng thái: Đang chạy trên Port " + PORT);
            lblStatus.setForeground(new Color(40, 167, 69)); // Xanh lá
            btnStartStop.setText("Dừng Server");
            btnStartStop.setBackground(new Color(220, 53, 69));

            log("[SERVER] Đã khởi tạo và lắng nghe kết nối thành công trên Port " + PORT);

            // Thread riêng lắng nghe các Client kết nối
            executor.submit(() -> {
                while (isRunning && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        executor.submit(new ClientHandler(clientSocket));
                    } catch (IOException e) {
                        if (!isRunning) break;
                    }
                }
            });

        } catch (IOException e) {
            log("[ERR] Lỗi khi khởi động Server: " + e.getMessage());
        }
    }

    private void stopServer() {
        isRunning = false;
        try {
            // Ngắt kết nối tất cả Client đang Online
            for (ClientHandler handler : onlineUsers.values()) {
                handler.closeConnection();
            }
            onlineUsers.clear();
            updateClientListUI();

            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            if (executor != null) {
                executor.shutdownNow();
            }

            lblStatus.setText("Trạng thái: Đã dừng");
            lblStatus.setForeground(new Color(220, 53, 69));
            btnStartStop.setText("Khởi động Server");
            btnStartStop.setBackground(new Color(0, 122, 255));

            log("[SERVER] Server đã dừng hoạt động.");
        } catch (IOException e) {
            log("[ERR] Lỗi dừng Server: " + e.getMessage());
        }
    }

    private void kickSelectedClient() {
        String selectedUser = lstClients.getSelectedValue();
        if (selectedUser == null) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một client từ danh sách để Kick!");
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                "Bạn có chắc chắn muốn kick client [" + selectedUser + "] khỏi máy chủ?",
                "Xác nhận Kick",
                JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            ClientHandler handler = onlineUsers.get(selectedUser);
            if (handler != null) {
                handler.kick();
                log("[KICK] Admin đã kick client: " + selectedUser);
            }
        }
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            txtLog.append(message + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });
    }

    private void updateClientListUI() {
        SwingUtilities.invokeLater(() -> {
            clientListModel.clear();
            onlineUsers.keySet().forEach(clientListModel::addElement);
        });
    }

    // Class xử lý kết nối từng Client
    class ClientHandler implements Runnable {
        private final Socket socket;
        private DataInputStream dis;
        private DataOutputStream dos;
        private String username;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                dis = new DataInputStream(socket.getInputStream());
                dos = new DataOutputStream(socket.getOutputStream());

                username = dis.readUTF(); // Đọc username đăng nhập
                onlineUsers.put(username, this);
                updateUserStatusInDB(username, "ONLINE");

                log("[+] Client kết nối thành công: " + username + " (" + socket.getInetAddress().getHostAddress() + ")");
                updateClientListUI();
                broadcastUserList();

                while (isRunning) {
                    String command = dis.readUTF();
                    switch (command) {
                        case "TEXT" -> {
                            String target = dis.readUTF();
                            String msg = dis.readUTF();
                            saveMessageToDB(username, target, "TEXT", msg);
                            sendPrivateMessage(target, "TEXT", username, msg);
                            log("[MSG] " + username + " -> " + target + ": " + msg);
                        }
                        case "BROADCAST" -> {
                            String msg = dis.readUTF();
                            saveMessageToDB(username, "ALL", "TEXT", msg);
                            broadcastMessage(username, msg);
                            log("[BROADCAST] " + username + ": " + msg);
                        }
                        case "FILE" -> {
                            String target = dis.readUTF();
                            String fileName = dis.readUTF();
                            long fileSize = dis.readLong();

                            File dir = new File("server_files");
                            if (!dir.exists()) dir.mkdirs();
                            File file = new File(dir, System.currentTimeMillis() + "_" + fileName);

                            byte[] buffer = new byte[4096];
                            try (FileOutputStream fos = new FileOutputStream(file)) {
                                long totalRead = 0;
                                int read;
                                while (totalRead < fileSize && (read = dis.read(buffer, 0, (int) Math.min(buffer.length, fileSize - totalRead))) != -1) {
                                    fos.write(buffer, 0, read);
                                    totalRead += read;
                                }
                            }
                            saveMessageToDB(username, target, "FILE", fileName);
                            sendPrivateMessage(target, "FILE", username, fileName);
                            log("[FILE] " + username + " -> " + target + ": " + fileName);
                        }
                    }
                }
            } catch (IOException e) {
                if (username != null) {
                    log("[-] Client ngắt kết nối: " + username);
                }
            } finally {
                closeConnection();
            }
        }

        public void kick() {
            try {
                if (dos != null) {
                    dos.writeUTF("KICKED");
                    dos.writeUTF("Bạn đã bị Server ngắt kết nối (Kicked)!");
                }
            } catch (IOException ignored) {}
            closeConnection();
        }

        public void closeConnection() {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException ignored) {}

            if (username != null && onlineUsers.containsKey(username)) {
                onlineUsers.remove(username);
                updateUserStatusInDB(username, "OFFLINE");
                updateClientListUI();
                broadcastUserList();
            }
        }

        private void sendPrivateMessage(String targetUser, String type, String sender, String content) throws IOException {
            ClientHandler targetHandler = onlineUsers.get(targetUser);
            if (targetHandler != null) {
                targetHandler.dos.writeUTF(type);
                targetHandler.dos.writeUTF(sender);
                targetHandler.dos.writeUTF(content);
            }
        }

        private void broadcastMessage(String sender, String msg) {
            onlineUsers.forEach((user, handler) -> {
                try {
                    handler.dos.writeUTF("BROADCAST");
                    handler.dos.writeUTF(sender);
                    handler.dos.writeUTF(msg);
                } catch (IOException ignored) {}
            });
        }

        private void broadcastUserList() {
            String usersStr = String.join(",", onlineUsers.keySet());
            onlineUsers.forEach((u, handler) -> {
                try {
                    handler.dos.writeUTF("USER_LIST");
                    handler.dos.writeUTF(usersStr);
                } catch (IOException ignored) {}
            });
        }

        private void saveMessageToDB(String sender, String receiver, String type, String content) {
            String sql = "INSERT INTO messages(sender, receiver, msg_type, content) VALUES (?, ?, ?, ?)";
            try (Connection conn = Database.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, sender);
                pstmt.setString(2, receiver);
                pstmt.setString(3, type);
                pstmt.setString(4, content);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                log("[DB ERR] " + e.getMessage());
            }
        }

        private void updateUserStatusInDB(String username, String status) {
            String sql = "UPDATE users SET status = ? WHERE username = ?";
            try (Connection conn = Database.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, status);
                pstmt.setString(2, username);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                log("[DB ERR] " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        FlatDarkLaf.setup();
        SwingUtilities.invokeLater(() -> new ChatServer().setVisible(true));
    }
}