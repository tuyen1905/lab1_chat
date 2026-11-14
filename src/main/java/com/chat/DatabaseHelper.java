package com.chat;

import java.sql.*;
import java.util.HashSet;
import java.util.Set;

public class DatabaseHelper {
    private static final String DB_URL = "jdbc:sqlite:chat_server.db";
    private static final Set<String> memoryBanList = new HashSet<>();

    public static void initDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS banned_users (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "username TEXT, ip_address TEXT, banned_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("CREATE TABLE IF NOT EXISTS messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "sender TEXT, receiver TEXT, content TEXT, sent_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
        } catch (Exception e) {
            System.out.println("Chạy chế độ lưu trữ RAM (Chưa nạp driver SQLite): " + e.getMessage());
        }
    }

    public static void banUser(String username, String ip) {
        memoryBanList.add(username.toLowerCase());
        memoryBanList.add(ip);
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("INSERT INTO banned_users(username, ip_address) VALUES(?, ?)")) {
            ps.setString(1, username);
            ps.setString(2, ip);
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    public static void unbanAll() {
        memoryBanList.clear();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM banned_users");
        } catch (Exception ignored) {}
    }

    public static boolean isBanned(String username, String ip) {
        if (memoryBanList.contains(username.toLowerCase()) || memoryBanList.contains(ip)) return true;
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM banned_users WHERE username = ? OR ip_address = ?")) {
            ps.setString(1, username);
            ps.setString(2, ip);
            return ps.executeQuery().next();
        } catch (Exception e) {
            return false;
        }
    }

    public static void saveMessage(String sender, String receiver, String content) {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement ps = conn.prepareStatement("INSERT INTO messages(sender, receiver, content) VALUES(?, ?, ?)")) {
            ps.setString(1, sender);
            ps.setString(2, receiver);
            ps.setString(3, content);
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }
}