package com.chat;

import java.sql.*;
import java.util.HashSet;
import java.util.Set;

public class DatabaseHelper {
    // Tự động tạo database chat_db nếu chưa có và hỗ trợ lưu tiếng Việt + Icon biểu cảm (utf8mb4)
    private static final String DB_URL = "jdbc:mysql://localhost:3306/chat_db?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Ho_Chi_Minh";
    private static final String DB_USER = "root"; // Đổi lại nếu bạn dùng user MySQL khác
    private static final String DB_PASS = "1905";

    private static final Set<String> memoryBanList = new HashSet<>();

    private static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    public static void initDatabase() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement()) {

                stmt.execute("CREATE TABLE IF NOT EXISTS banned_users (" +
                        "id INT PRIMARY KEY AUTO_INCREMENT, " +
                        "username VARCHAR(100), " +
                        "ip_address VARCHAR(45), " +
                        "banned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ") CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");

                stmt.execute("CREATE TABLE IF NOT EXISTS messages (" +
                        "id INT PRIMARY KEY AUTO_INCREMENT, " +
                        "sender VARCHAR(100), " +
                        "receiver VARCHAR(100), " +
                        "content TEXT, " +
                        "sent_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ") CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");

                System.out.println("Kết nối MySQL (chat_db) thành công!");
            }
        } catch (Exception e) {
            System.out.println("Chạy chế độ lưu trữ RAM (Lỗi kết nối MySQL): " + e.getMessage());
        }
    }

    public static void banUser(String username, String ip) {
        memoryBanList.add(username.toLowerCase());
        memoryBanList.add(ip);
        String sql = "INSERT INTO banned_users(username, ip_address) VALUES(?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, ip);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("Lỗi lưu Ban vào MySQL: " + e.getMessage());
        }
    }

    public static void unbanAll() {
        memoryBanList.clear();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            // Dùng TRUNCATE để tránh lỗi SQL_SAFE_UPDATES trong MySQL
            stmt.execute("TRUNCATE TABLE banned_users");
        } catch (Exception e) {
            System.err.println("Lỗi xóa danh sách Ban: " + e.getMessage());
        }
    }

    public static boolean isBanned(String username, String ip) {
        if (memoryBanList.contains(username.toLowerCase()) || memoryBanList.contains(ip)) {
            return true;
        }
        String sql = "SELECT 1 FROM banned_users WHERE username = ? OR ip_address = ? LIMIT 1";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, ip);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static void saveMessage(String sender, String receiver, String content) {
        String sql = "INSERT INTO messages(sender, receiver, content) VALUES(?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sender);
            ps.setString(2, receiver);
            ps.setString(3, content);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("Lỗi lưu tin nhắn vào MySQL: " + e.getMessage());
        }
    }
}