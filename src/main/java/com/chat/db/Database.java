package com.chat.db;

import java.sql.*;

public class Database {
    private static final String DB_URL = "jdbc:mysql://localhost:3306/chat_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "1905";

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    public static void initDatabase() {
        try (Connection conn = getConnection()) {
            if (conn != null && !conn.isClosed()) {
                System.out.println("[DB] Kết nối Cơ sở dữ liệu MySQL (chat_db) thành công!");
            }
        } catch (SQLException e) {
            System.err.println("[DB Error] Không thể kết nối MySQL: " + e.getMessage());
        }
    }
}