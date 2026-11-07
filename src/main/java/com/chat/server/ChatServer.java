package com.chat.server;

import com.chat.db.Database;
import java.io.*;
import java.net.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class ChatServer {
    private static final int PORT = 12345;
    private static final Map<String, ClientHandler> onlineUsers = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        Database.initDatabase();
        System.out.println("=== CHAT SERVER RUNNING ON PORT " + PORT + " (JAVA 23) ===");

        // Dùng Virtual Threads của Java 23 cho hiệu năng tối đa
        try (ServerSocket serverSocket = new ServerSocket(PORT);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {

            while (true) {
                Socket clientSocket = serverSocket.accept();
                executor.submit(new ClientHandler(clientSocket));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class ClientHandler implements Runnable {
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

                username = dis.readUTF(); // Client gửi username khi kết nối
                onlineUsers.put(username, this);
                updateUserStatusInDB(username, "ONLINE");
                broadcastUserList();

                System.out.println("[+] " + username + " đã kết nối.");

                while (true) {
                    String command = dis.readUTF();
                    switch (command) {
                        case "TEXT" -> {
                            String target = dis.readUTF();
                            String msg = dis.readUTF();
                            saveMessageToDB(username, target, "TEXT", msg);
                            sendPrivateMessage(target, "TEXT", username, msg);
                        }
                        case "BROADCAST" -> {
                            String msg = dis.readUTF();
                            saveMessageToDB(username, "ALL", "TEXT", msg);
                            broadcastMessage(username, msg);
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
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println("[-] " + username + " ngắt kết nối.");
            } finally {
                if (username != null) {
                    onlineUsers.remove(username);
                    updateUserStatusInDB(username, "OFFLINE");
                    broadcastUserList();
                }
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
                e.printStackTrace();
            }
        }

        private void updateUserStatusInDB(String username, String status) {
            String sql = "UPDATE users SET status = ? WHERE username = ?";
            try (Connection conn = Database.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, status);
                pstmt.setString(2, username);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }
}