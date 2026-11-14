package com.chat;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ServerApp extends JFrame {
    private static final int PORT = 8888;
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private DefaultTableModel tableModel;
    private JTable clientTable;
    private JTextArea logArea;
    private JLabel statusLabel;
    private ServerSocket serverSocket;

    public ServerApp() {
        setTitle("SERVER QUẢN TRỊ HỆ THỐNG CHAT LAN");
        setSize(900, 560);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        initUI();
        DatabaseHelper.initDatabase();
        startServer();
    }

    private void initUI() {
        JPanel mainPanel = new JPanel(new BorderLayout(12, 12));
        mainPanel.setBorder(new EmptyBorder(12, 12, 12, 12));
        mainPanel.setBackground(new Color(30, 33, 40));

        // Header
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JLabel title = new JLabel("BẢNG ĐIỀU KHIỂN SERVER (PORT: " + PORT + ")");
        title.setFont(new Font("Segoe UI", Font.BOLD, 18));
        title.setForeground(new Color(97, 175, 239));
        statusLabel = new JLabel("Đang hoạt động | Online: 0");
        statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        statusLabel.setForeground(new Color(152, 195, 121));
        header.add(title, BorderLayout.WEST);
        header.add(statusLabel, BorderLayout.EAST);
        mainPanel.add(header, BorderLayout.NORTH);

        // Split Pane: Bảng Client (Trái) & Log hệ thống (Phải)
        String[] cols = {"Username", "Địa chỉ IP", "Port", "Thời gian kết nối"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        clientTable = new JTable(tableModel);
        clientTable.setRowHeight(28);
        clientTable.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        JScrollPane tableScroll = new JScrollPane(clientTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Danh sách Client đang kết nối"));

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(new Color(22, 24, 29));
        logArea.setForeground(new Color(220, 223, 228));
        logArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Nhật ký hoạt động (Activity Log)"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, logScroll);
        splitPane.setDividerLocation(430);
        mainPanel.add(splitPane, BorderLayout.CENTER);

        // Thanh công cụ Admin phía dưới
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        controlPanel.setOpaque(false);

        JButton btnBan = new JButton("🚫 Ban Client được chọn");
        btnBan.setBackground(new Color(224, 108, 117));
        btnBan.setForeground(Color.WHITE);
        btnBan.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btnBan.addActionListener(e -> banSelectedClient());

        JButton btnUnbanAll = new JButton("🔓 Gỡ Ban tất cả");
        btnUnbanAll.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        btnUnbanAll.addActionListener(e -> {
            DatabaseHelper.unbanAll();
            appendLog("[ADMIN] Đã xóa toàn bộ danh sách cấm (Unban All).");
        });

        JButton btnClearLog = new JButton("🧹 Xóa Log");
        btnClearLog.addActionListener(e -> logArea.setText(""));

        controlPanel.add(btnClearLog);
        controlPanel.add(btnUnbanAll);
        controlPanel.add(btnBan);
        mainPanel.add(controlPanel, BorderLayout.SOUTH);

        setContentPane(mainPanel);
    }

    private void startServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                appendLog("[SYSTEM] Server khởi động thành công tại IP: " + InetAddress.getLocalHost().getHostAddress());
                while (!serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    new Thread(new ClientHandler(socket)).start();
                }
            } catch (IOException e) {
                appendLog("[ERROR] Lỗi Server: " + e.getMessage());
            }
        }).start();
    }

    private void banSelectedClient() {
        int row = clientTable.getSelectedRow();
        if (row == -1) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một Client trong bảng để Ban!");
            return;
        }
        String username = (String) tableModel.getValueAt(row, 0);
        String ip = (String) tableModel.getValueAt(row, 1);
        ClientHandler handler = clients.get(username);
        if (handler != null) {
            DatabaseHelper.banUser(username, ip);
            handler.sendPacket(new Packet(Packet.Type.BAN, "SERVER", username, "Bạn đã bị Admin cấm truy cập!"));
            handler.disconnect();
            appendLog("[BAN] Admin đã khóa tài khoản '" + username + "' (IP: " + ip + ")");
        }
    }

    private void appendLog(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + time + "] " + msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void updateClientTable() {
        SwingUtilities.invokeLater(() -> {
            tableModel.setRowCount(0);
            for (ClientHandler ch : clients.values()) {
                tableModel.addRow(new Object[]{ch.username, ch.ip, ch.port, ch.connectTime});
            }
            statusLabel.setText("Đang hoạt động | Online: " + clients.size());
        });
    }

    private void broadcastUserList() {
        String userListStr = String.join(",", clients.keySet());
        Packet p = new Packet(Packet.Type.USER_LIST, "SERVER", "ALL", userListStr);
        for (ClientHandler ch : clients.values()) {
            ch.sendPacket(p);
        }
    }

    private class ClientHandler implements Runnable {
        private final Socket socket;
        private ObjectOutputStream out;
        private ObjectInputStream in;
        private String username;
        private final String ip;
        private final int port;
        private final String connectTime;

        public ClientHandler(Socket socket) {
            this.socket = socket;
            this.ip = socket.getInetAddress().getHostAddress();
            this.port = socket.getPort();
            this.connectTime = new SimpleDateFormat("HH:mm:ss dd/MM").format(new Date());
        }

        @Override
        public void run() {
            try {
                out = new ObjectOutputStream(socket.getOutputStream());
                in = new ObjectInputStream(socket.getInputStream());

                while (!socket.isClosed()) {
                    Packet packet = (Packet) in.readObject();
                    switch (packet.getType()) {
                        case LOGIN -> {
                            this.username = packet.getSender();
                            if (DatabaseHelper.isBanned(username, ip)) {
                                sendPacket(new Packet(Packet.Type.BAN, "SERVER", username, "IP hoặc Tài khoản của bạn đang bị cấm!"));
                                appendLog("[BLOCKED] Từ chối kết nối từ " + username + " (" + ip + ") do nằm trong danh sách Ban.");
                                socket.close();
                                return;
                            }
                            clients.put(username, this);
                            updateClientTable();
                            appendLog("[CONNECT] '" + username + "' (" + ip + ":" + port + ") đã kết nối.");
                            broadcastUserList();
                        }
                        case TEXT -> {
                            ClientHandler target = clients.get(packet.getReceiver());
                            if (target != null) target.sendPacket(packet);
                            DatabaseHelper.saveMessage(packet.getSender(), packet.getReceiver(), packet.getContent());
                            appendLog("[CHAT] " + packet.getSender() + " -> " + packet.getReceiver() + ": " + packet.getContent());
                        }
                        case BROADCAST -> {
                            for (ClientHandler ch : clients.values()) {
                                if (!ch.username.equals(packet.getSender())) ch.sendPacket(packet);
                            }
                            DatabaseHelper.saveMessage(packet.getSender(), "BROADCAST", packet.getContent());
                            appendLog("[BROADCAST] " + packet.getSender() + ": " + packet.getContent());
                        }
                        case FILE, AUDIO -> {
                            ClientHandler target = clients.get(packet.getReceiver());
                            if (target != null) target.sendPacket(packet);
                            appendLog("[" + packet.getType() + "] " + packet.getSender() + " gửi dữ liệu tới " + packet.getReceiver());
                        }
                        case VIDEO_CALL_REQ -> {
                            // Gắn kèm IP thật của người gọi để 2 máy stream UDP trực tiếp
                            ClientHandler target = clients.get(packet.getReceiver());
                            if (target != null) {
                                target.sendPacket(new Packet(Packet.Type.VIDEO_CALL_REQ, packet.getSender(), packet.getReceiver(), ip));
                                appendLog("[VIDEO] " + packet.getSender() + " (" + ip + ") gọi Video tới " + packet.getReceiver());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (username != null) appendLog("[DISCONNECT] '" + username + "' đã thoát.");
            } finally {
                disconnect();
            }
        }

        public synchronized void sendPacket(Packet p) {
            try {
                out.writeObject(p);
                out.flush();
                out.reset(); // Tránh tràn bộ nhớ cache khi gửi file/audio lớn
            } catch (IOException ignored) {}
        }

        public void disconnect() {
            if (username != null) {
                clients.remove(username);
                updateClientTable();
                broadcastUserList();
            }
            try { if (!socket.isClosed()) socket.close(); } catch (IOException ignored) {}
        }
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel"); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new ServerApp().setVisible(true));
    }
}
