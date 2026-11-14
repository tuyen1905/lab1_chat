package com.chat;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.Timer;

public class ClientApp extends JFrame {
    private static final int SERVER_PORT = 8888;
    private static final String MULTICAST_IP = "230.0.0.1";
    private static final int MULTICAST_PORT = 4446;
    private static final int VIDEO_UDP_PORT = 9999;

    // Bảng màu chuẩn Zalo
    private static final Color ZALO_BLUE = new Color(0, 104, 255);
    private static final Color ZALO_LIGHT_BLUE = new Color(229, 239, 255);
    private static final Color ZALO_BORDER_BLUE = new Color(194, 217, 255);
    private static final Color CHAT_BG = new Color(238, 240, 243);
    private static final Color SIDEBAR_BG = Color.WHITE;
    private static final Color HOVER_BG = new Color(243, 245, 248);
    private static final Color SELECTED_BG = new Color(229, 239, 255);
    private static final Color TEXT_PRIMARY = new Color(25, 30, 40);
    private static final Color TEXT_SECONDARY = new Color(115, 125, 140);

    private static final String CHANNEL_BROADCAST = "📢 Broadcast (Tất cả)";
    private static final String CHANNEL_MULTICAST = "🌐 Nhóm Multicast (230.0.0.1)";
    private static final String END_CALL_SIGNAL = "__ZALO_END_VIDEO_CALL__";

    private String username;
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private MulticastSocket multicastSocket;

    // Quản lý hội thoại & chưa đọc
    private String currentChatTarget = CHANNEL_BROADCAST;
    private final Map<String, List<ChatItem>> conversations = new ConcurrentHashMap<>();
    private final Map<String, Integer> unreadCounts = new ConcurrentHashMap<>();

    // Thành phần giao diện chính
    private DefaultListModel<String> contactListModel;
    private JList<String> contactList;
    private JLabel chatHeaderTitle;
    private JLabel chatHeaderStatus;
    private AvatarBadge chatHeaderAvatar;
    private JButton btnHeaderVideo;
    private JPanel messagesContainer;
    private JScrollPane messagesScrollPane;
    private JTextField inputField;

    // Ghi âm kiểu Zalo (Toggle)
    private JButton btnMic;
    private volatile boolean isRecording = false;
    private TargetDataLine activeMicLine;
    private ByteArrayOutputStream recordedAudioBuffer;
    private long recordStartTime;
    private Timer recordTimer;

    // Video Call Webcam kiểu Zalo
    private VideoCallDialog activeVideoDialog;
    private volatile boolean isVideoCalling = false;
    private volatile boolean isCameraEnabled = true;

    public ClientApp() {
        if (!showLoginDialog()) System.exit(0);

        conversations.put(CHANNEL_BROADCAST, Collections.synchronizedList(new ArrayList<>()));
        conversations.put(CHANNEL_MULTICAST, Collections.synchronizedList(new ArrayList<>()));

        setTitle("Zalo LAN Chat - " + username);
        setSize(1060, 680);
        setMinimumSize(new Dimension(860, 560));
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        initUI();
        startMulticastListener();
        startVideoReceiver();
        switchConversation(CHANNEL_BROADCAST);
    }

    private boolean showLoginDialog() {
        JPanel panel = new JPanel(new GridLayout(0, 1, 6, 6));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        JTextField ipField = new JTextField("192.168.1.10");
        JTextField userField = new JTextField("User_" + (100 + new Random().nextInt(900)));

        JLabel lblTitle = new JLabel("ĐĂNG NHẬP ZALO LAN");
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 16));
        lblTitle.setForeground(ZALO_BLUE);

        panel.add(lblTitle);
        panel.add(new JLabel("Địa chỉ IP Máy chủ (Server):"));
        panel.add(ipField);
        panel.add(new JLabel("Tên hiển thị của bạn:"));
        panel.add(userField);

        int opt = JOptionPane.showConfirmDialog(null, panel, "Kết nối hệ thống Chat", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (opt != JOptionPane.OK_OPTION) return false;

        try {
            this.username = userField.getText().trim();
            if (this.username.isEmpty()) this.username = "Khach_" + new Random().nextInt(100);
            socket = new Socket(ipField.getText().trim(), SERVER_PORT);
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());
            out.writeObject(new Packet(Packet.Type.LOGIN, username, "SERVER", "LOGIN"));
            out.flush();
            new Thread(this::listenToServer).start();
            return true;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Không thể kết nối tới Server:\n" + e.getMessage(), "Lỗi kết nối", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void initUI() {
        JPanel root = new JPanel(new BorderLayout());

        // 1. THANH ĐIỀU HƯỚNG DỌC NGOÀI CÙNG BÊN TRÁI (MÀU XANH ZALO)
        JPanel navRail = new JPanel();
        navRail.setPreferredSize(new Dimension(64, 0));
        navRail.setBackground(ZALO_BLUE);
        navRail.setLayout(new BoxLayout(navRail, BoxLayout.Y_AXIS));
        navRail.setBorder(new EmptyBorder(14, 8, 14, 8));

        AvatarBadge myAvatar = new AvatarBadge(username, 42, Color.WHITE, ZALO_BLUE);
        myAvatar.setAlignmentX(Component.CENTER_ALIGNMENT);
        myAvatar.setToolTipText("Tài khoản: " + username);

        JLabel navChatIcon = new JLabel("💬", SwingConstants.CENTER);
        navChatIcon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 22));
        navChatIcon.setForeground(Color.WHITE);
        navChatIcon.setAlignmentX(Component.CENTER_ALIGNMENT);
        navChatIcon.setBorder(new EmptyBorder(20, 0, 10, 0));

        navRail.add(myAvatar);
        navRail.add(navChatIcon);
        navRail.add(Box.createVerticalGlue());

        // 2. DANH SÁCH HỘI THOẠI & NGƯỜI DÙNG ONLINE (SIDEBAR TRẮNG)
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setPreferredSize(new Dimension(280, 0));
        sidebar.setBackground(SIDEBAR_BG);
        sidebar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(225, 228, 234)));

        JPanel sidebarHeader = new JPanel(new BorderLayout());
        sidebarHeader.setBackground(SIDEBAR_BG);
        sidebarHeader.setBorder(new EmptyBorder(14, 16, 12, 16));
        JLabel lblBrand = new JLabel("Tin nhắn");
        lblBrand.setFont(new Font("Segoe UI", Font.BOLD, 19));
        lblBrand.setForeground(TEXT_PRIMARY);
        JLabel lblMe = new JLabel("● " + username);
        lblMe.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblMe.setForeground(new Color(40, 167, 69));
        sidebarHeader.add(lblBrand, BorderLayout.WEST);
        sidebarHeader.add(lblMe, BorderLayout.EAST);
        sidebar.add(sidebarHeader, BorderLayout.NORTH);

        contactListModel = new DefaultListModel<>();
        contactListModel.addElement(CHANNEL_BROADCAST);
        contactListModel.addElement(CHANNEL_MULTICAST);

        contactList = new JList<>(contactListModel);
        contactList.setCellRenderer(new ContactCellRenderer());
        contactList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        contactList.setSelectedIndex(0);
        contactList.setBackground(SIDEBAR_BG);
        contactList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && contactList.getSelectedValue() != null) {
                switchConversation(contactList.getSelectedValue());
            }
        });

        JScrollPane contactScroll = new JScrollPane(contactList);
        contactScroll.setBorder(null);
        sidebar.add(contactScroll, BorderLayout.CENTER);

        JPanel leftWrapper = new JPanel(new BorderLayout());
        leftWrapper.add(navRail, BorderLayout.WEST);
        leftWrapper.add(sidebar, BorderLayout.CENTER);
        root.add(leftWrapper, BorderLayout.WEST);

        // 3. KHUNG CHAT CHÍNH BÊN PHẢI
        JPanel chatAreaWrapper = new JPanel(new BorderLayout());
        chatAreaWrapper.setBackground(CHAT_BG);

        // Header cuộc trò chuyện
        JPanel chatHeader = new JPanel(new BorderLayout(12, 0));
        chatHeader.setBackground(Color.WHITE);
        chatHeader.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(225, 228, 234)),
                new EmptyBorder(10, 16, 10, 16)
        ));

        chatHeaderAvatar = new AvatarBadge("B", 40, ZALO_BLUE, Color.WHITE);
        JPanel titleStack = new JPanel(new GridLayout(2, 1, 0, 2));
        titleStack.setOpaque(false);
        chatHeaderTitle = new JLabel(CHANNEL_BROADCAST);
        chatHeaderTitle.setFont(new Font("Segoe UI", Font.BOLD, 16));
        chatHeaderTitle.setForeground(TEXT_PRIMARY);
        chatHeaderStatus = new JLabel("● Đang hoạt động");
        chatHeaderStatus.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        chatHeaderStatus.setForeground(new Color(40, 167, 69));
        titleStack.add(chatHeaderTitle);
        titleStack.add(chatHeaderStatus);

        JPanel headerLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        headerLeft.setOpaque(false);
        headerLeft.add(chatHeaderAvatar);
        headerLeft.add(titleStack);

        btnHeaderVideo = new JButton("📹 Gọi Video");
        btnHeaderVideo.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btnHeaderVideo.setBackground(ZALO_LIGHT_BLUE);
        btnHeaderVideo.setForeground(ZALO_BLUE);
        btnHeaderVideo.setFocusPainted(false);
        btnHeaderVideo.setBorder(new EmptyBorder(8, 14, 8, 14));
        btnHeaderVideo.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnHeaderVideo.addActionListener(e -> initiateVideoCall());

        chatHeader.add(headerLeft, BorderLayout.WEST);
        chatHeader.add(btnHeaderVideo, BorderLayout.EAST);
        chatAreaWrapper.add(chatHeader, BorderLayout.NORTH);

        // Khu vực danh sách bong bóng tin nhắn (2 cột trái - phải)
        messagesContainer = new JPanel();
        messagesContainer.setLayout(new BoxLayout(messagesContainer, BoxLayout.Y_AXIS));
        messagesContainer.setBackground(CHAT_BG);
        messagesContainer.setBorder(new EmptyBorder(14, 18, 14, 18));

        // Wrapper để đẩy tin nhắn bám từ trên xuống tự nhiên
        JPanel scrollContent = new JPanel(new BorderLayout());
        scrollContent.setBackground(CHAT_BG);
        scrollContent.add(messagesContainer, BorderLayout.NORTH);

        messagesScrollPane = new JScrollPane(scrollContent);
        messagesScrollPane.setBorder(null);
        messagesScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        chatAreaWrapper.add(messagesScrollPane, BorderLayout.CENTER);

        // Khu vực nhập tin nhắn & công cụ phía dưới (kiểu Zalo)
        JPanel composerPanel = new JPanel(new BorderLayout());
        composerPanel.setBackground(Color.WHITE);
        composerPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(225, 228, 234)));

        // Thanh công cụ Emoji, Đính kèm File, Ghi âm Micro
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        toolbar.setBackground(Color.WHITE);
        toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(240, 242, 245)));

        JButton btnEmoji = createToolbarButton("Biểu cảm", new ColorEmojiIcon("😀", 20));
        btnEmoji.addActionListener(e -> showColorEmojiPicker(btnEmoji));

        JButton btnAttach = createToolbarButton("Gửi File", new ColorEmojiIcon("📎", 20));
        btnAttach.addActionListener(e -> sendFileAttachment());

        btnMic = createToolbarButton("Ghi âm", new ColorEmojiIcon("🎤", 20));
        btnMic.addActionListener(e -> toggleVoiceRecording());

        toolbar.add(btnEmoji);
        toolbar.add(btnAttach);
        toolbar.add(btnMic);

        // Ô nhập văn bản + nút Gửi
        JPanel inputRow = new JPanel(new BorderLayout(10, 0));
        inputRow.setBackground(Color.WHITE);
        inputRow.setBorder(new EmptyBorder(10, 14, 12, 14));

        inputField = new JTextField();
        inputField.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        inputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 224, 230), 1, true),
                new EmptyBorder(8, 12, 8, 12)
        ));
        inputField.addActionListener(e -> sendTextMessage());

        JButton btnSend = new JButton("GỬI ➤");
        btnSend.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btnSend.setBackground(ZALO_BLUE);
        btnSend.setForeground(Color.WHITE);
        btnSend.setFocusPainted(false);
        btnSend.setBorder(new EmptyBorder(9, 18, 9, 18));
        btnSend.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnSend.addActionListener(e -> sendTextMessage());

        inputRow.add(inputField, BorderLayout.CENTER);
        inputRow.add(btnSend, BorderLayout.EAST);

        composerPanel.add(toolbar, BorderLayout.NORTH);
        composerPanel.add(inputRow, BorderLayout.CENTER);
        chatAreaWrapper.add(composerPanel, BorderLayout.SOUTH);

        root.add(chatAreaWrapper, BorderLayout.CENTER);
        setContentPane(root);
    }

    private JButton createToolbarButton(String text, Icon icon) {
        JButton btn = new JButton(text, icon);
        btn.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        btn.setForeground(TEXT_PRIMARY);
        btn.setBackground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorder(new EmptyBorder(5, 10, 5, 10));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    // Chuyển đổi cuộc trò chuyện và dựng lại giao diện bong bóng 2 cột
    private void switchConversation(String target) {
        currentChatTarget = target;
        unreadCounts.put(target, 0);
        contactList.repaint();

        chatHeaderTitle.setText(target);
        boolean isPrivate = !target.equals(CHANNEL_BROADCAST) && !target.equals(CHANNEL_MULTICAST);
        btnHeaderVideo.setVisible(isPrivate);

        if (target.equals(CHANNEL_BROADCAST)) {
            chatHeaderAvatar.update("ALL", new Color(255, 149, 0));
            chatHeaderStatus.setText("Kênh thông báo toàn bộ người dùng Online");
            inputField.setToolTipText("Nhập tin nhắn gửi tới tất cả mọi người...");
        } else if (target.equals(CHANNEL_MULTICAST)) {
            chatHeaderAvatar.update("MC", new Color(111, 66, 193));
            chatHeaderStatus.setText("Nhóm UDP Multicast LAN (" + MULTICAST_IP + ":" + MULTICAST_PORT + ")");
            inputField.setToolTipText("Nhập tin nhắn gửi vào nhóm Multicast...");
        } else {
            chatHeaderAvatar.update(target, getAvatarColor(target));
            chatHeaderStatus.setText("● Đang hoạt động");
            inputField.setToolTipText("Nhập @, tin nhắn tới " + target + "...");
        }

        messagesContainer.removeAll();
        List<ChatItem> history = conversations.computeIfAbsent(target, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (history) {
            for (ChatItem item : history) {
                messagesContainer.add(createMessageRow(item));
                messagesContainer.add(Box.createVerticalStrut(10));
            }
        }
        messagesContainer.revalidate();
        messagesContainer.repaint();
        scrollToBottom();
    }

    private void addMessageToConversation(String channel, ChatItem item) {
        conversations.computeIfAbsent(channel, k -> Collections.synchronizedList(new ArrayList<>())).add(item);
        SwingUtilities.invokeLater(() -> {
            if (channel.equals(currentChatTarget)) {
                messagesContainer.add(createMessageRow(item));
                messagesContainer.add(Box.createVerticalStrut(10));
                messagesContainer.revalidate();
                messagesContainer.repaint();
                scrollToBottom();
            } else {
                unreadCounts.merge(channel, 1, Integer::sum);
                contactList.repaint();
            }
        });
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = messagesScrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    // =========================================================================
    // DỰNG BONG BÓNG CHAT 2 CỘT (PHẢI: TÔI | TRÁI: NGƯỜI KHÁC) + NGÀY GIỜ ZALO
    // =========================================================================
    private JPanel createMessageRow(ChatItem item) {
        JPanel row = new JPanel(new FlowLayout(item.isMe ? FlowLayout.RIGHT : FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1000));

        // Nếu là tin người khác gửi, hiển thị Avatar bên trái
        if (!item.isMe) {
            AvatarBadge senderAvatar = new AvatarBadge(item.sender, 34, getAvatarColor(item.sender), Color.WHITE);
            JPanel avatarHolder = new JPanel(new BorderLayout());
            avatarHolder.setOpaque(false);
            avatarHolder.add(senderAvatar, BorderLayout.NORTH);
            row.add(avatarHolder);
        }

        // Khung bong bóng bo góc kiểu Zalo
        RoundedBubblePanel bubble = new RoundedBubblePanel(
                item.isMe ? ZALO_LIGHT_BLUE : Color.WHITE,
                item.isMe ? ZALO_BORDER_BLUE : new Color(222, 226, 232),
                16
        );
        bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
        bubble.setBorder(new EmptyBorder(9, 13, 8, 13));

        // Tên người gửi (nếu ở kênh nhóm/broadcast và không phải mình)
        if (!item.isMe && (currentChatTarget.equals(CHANNEL_BROADCAST) || currentChatTarget.equals(CHANNEL_MULTICAST))) {
            JLabel lblSender = new JLabel(item.sender);
            lblSender.setFont(new Font("Segoe UI", Font.BOLD, 11));
            lblSender.setForeground(ZALO_BLUE);
            lblSender.setAlignmentX(Component.LEFT_ALIGNMENT);
            bubble.add(lblSender);
            bubble.add(Box.createVerticalStrut(3));
        }

        // Nội dung tùy theo loại tin nhắn: TEXT / FILE / AUDIO
        switch (item.kind) {
            case TEXT -> {
                JPanel richTextPanel = buildRichEmojiTextPanel(item.text);
                richTextPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
                bubble.add(richTextPanel);
            }
            case FILE -> {
                JPanel fileCard = buildFileAttachmentCard(item);
                fileCard.setAlignmentX(Component.LEFT_ALIGNMENT);
                bubble.add(fileCard);
            }
            case AUDIO -> {
                JPanel audioCard = buildAudioBubbleCard(item);
                audioCard.setAlignmentX(Component.LEFT_ALIGNMENT);
                bubble.add(audioCard);
            }
        }

        // Dòng ngày giờ kiểu Zalo phía dưới bong bóng
        bubble.add(Box.createVerticalStrut(5));
        JLabel lblTime = new JLabel(item.timestamp);
        lblTime.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        lblTime.setForeground(TEXT_SECONDARY);
        lblTime.setAlignmentX(Component.LEFT_ALIGNMENT);
        bubble.add(lblTime);

        row.add(bubble);
        return row;
    }

    // Hiển thị văn bản kèm Icon biểu cảm MÀU sắc nét (không bị đen trắng)
    private JPanel buildRichEmojiTextPanel(String text) {
        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setOpaque(false);

        String[] lines = text.split("\n");
        for (String line : lines) {
            JPanel linePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 1));
            linePanel.setOpaque(false);
            linePanel.setAlignmentX(Component.LEFT_ALIGNMENT);

            StringBuilder textBuf = new StringBuilder();
            int i = 0;
            while (i < line.length()) {
                int codePoint = line.codePointAt(i);
                String chStr = new String(Character.toChars(codePoint));
                if (ColorEmojiIcon.isSupportedEmoji(chStr)) {
                    if (textBuf.length() > 0) {
                        linePanel.add(createStyledTextLabel(textBuf.toString()));
                        textBuf.setLength(0);
                    }
                    JLabel emojiLbl = new JLabel(new ColorEmojiIcon(chStr, 22));
                    linePanel.add(emojiLbl);
                } else {
                    textBuf.append(chStr);
                }
                i += Character.charCount(codePoint);
            }
            if (textBuf.length() > 0) {
                linePanel.add(createStyledTextLabel(textBuf.toString()));
            }
            container.add(linePanel);
        }
        return container;
    }

    private JLabel createStyledTextLabel(String str) {
        // Tự động xuống dòng nếu đoạn văn bản dài hơn 340px
        JLabel lbl = new JLabel();
        lbl.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        lbl.setForeground(TEXT_PRIMARY);
        if (str.length() > 48) {
            lbl.setText("<html><div style='width: 310px;'>" + escapeHtml(str) + "</div></html>");
        } else {
            lbl.setText(str);
        }
        return lbl;
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&").replace("<", "<").replace(">", ">");
    }

    // Thẻ đính kèm File trên khung chat - Bấm vào để mở trực tiếp
    private JPanel buildFileAttachmentCard(ChatItem item) {
        JPanel card = new JPanel(new BorderLayout(10, 0));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210, 220, 235), 1, true),
                new EmptyBorder(8, 10, 8, 12)
        ));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // Biểu tượng loại tệp có màu
        String ext = getFileExtension(item.fileName).toUpperCase();
        JLabel iconBadge = new JLabel(ext.isEmpty() ? "FILE" : ext, SwingConstants.CENTER);
        iconBadge.setOpaque(true);
        iconBadge.setPreferredSize(new Dimension(44, 44));
        iconBadge.setFont(new Font("Segoe UI", Font.BOLD, 11));
        iconBadge.setForeground(Color.WHITE);
        iconBadge.setBackground(getFileBadgeColor(ext));

        JPanel info = new JPanel(new GridLayout(2, 1, 0, 2));
        info.setOpaque(false);
        JLabel lblName = new JLabel(item.fileName);
        lblName.setFont(new Font("Segoe UI", Font.BOLD, 13));
        lblName.setForeground(TEXT_PRIMARY);

        String sizeStr = formatFileSize(item.binaryData != null ? item.binaryData.length : 0);
        JLabel lblSub = new JLabel(sizeStr + " • Nhấn để mở tệp");
        lblSub.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        lblSub.setForeground(ZALO_BLUE);

        info.add(lblName);
        info.add(lblSub);

        card.add(iconBadge, BorderLayout.WEST);
        card.add(info, BorderLayout.CENTER);

        card.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openOrSaveAttachedFile(item);
            }
        });
        return card;
    }

    private void openOrSaveAttachedFile(ChatItem item) {
        if (item.binaryData == null) return;
        try {
            Path downloadDir = Paths.get(System.getProperty("user.home"), "ZaloLAN_Downloads");
            Files.createDirectories(downloadDir);
            Path targetPath = downloadDir.resolve(item.fileName);
            Files.write(targetPath, item.binaryData);

            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(targetPath.toFile());
            } else {
                JOptionPane.showMessageDialog(this, "Đã lưu tệp tại:\n" + targetPath);
            }
        } catch (Exception ex) {
            // Dự phòng mở hộp thoại lưu file nếu hệ điều hành chưa có app mở định dạng này
            JFileChooser saver = new JFileChooser();
            saver.setSelectedFile(new File(item.fileName));
            if (saver.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                try {
                    Files.write(saver.getSelectedFile().toPath(), item.binaryData);
                    JOptionPane.showMessageDialog(this, "Đã lưu thành công!");
                } catch (IOException ignored) {}
            }
        }
    }

    // Bong bóng Tin nhắn thoại (Audio) - Bấm vào để phát
    private JPanel buildAudioBubbleCard(ChatItem item) {
        JPanel card = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        card.setOpaque(false);
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel playBadge = new JLabel(" ▶ ", SwingConstants.CENTER);
        playBadge.setOpaque(true);
        playBadge.setBackground(ZALO_BLUE);
        playBadge.setForeground(Color.WHITE);
        playBadge.setFont(new Font("Segoe UI", Font.BOLD, 12));
        playBadge.setBorder(new EmptyBorder(4, 6, 4, 6));

        JLabel lblAudio = new JLabel("Tin nhắn thoại (" + item.text + ")  ılılıllı");
        lblAudio.setFont(new Font("Segoe UI", Font.BOLD, 13));
        lblAudio.setForeground(ZALO_BLUE);

        card.add(playBadge);
        card.add(lblAudio);

        card.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                playAudioData(item.binaryData, playBadge, lblAudio, item.text);
            }
        });
        return card;
    }

    // =========================================================================
    // CÁC CHỨC NĂNG GỬI TIN, EMOJI MÀU, FILE, MICRO GHI ÂM & VIDEO CALL WEBCAM
    // =========================================================================
    private void sendTextMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        String now = getCurrentTimestamp();
        try {
            if (currentChatTarget.equals(CHANNEL_BROADCAST)) {
                sendPacket(new Packet(Packet.Type.BROADCAST, username, "ALL", text));
                addMessageToConversation(CHANNEL_BROADCAST, ChatItem.text(username, text, now, true));
            } else if (currentChatTarget.equals(CHANNEL_MULTICAST)) {
                String payload = username + "||" + text;
                byte[] buf = payload.getBytes(StandardCharsets.UTF_8);
                DatagramPacket dp = new DatagramPacket(buf, buf.length, InetAddress.getByName(MULTICAST_IP), MULTICAST_PORT);
                multicastSocket.send(dp);
            } else {
                sendPacket(new Packet(Packet.Type.TEXT, username, currentChatTarget, text));
                addMessageToConversation(currentChatTarget, ChatItem.text(username, text, now, true));
            }
            inputField.setText("");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Lỗi gửi tin nhắn: " + ex.getMessage());
        }
    }

    private void showColorEmojiPicker(Component invoker) {
        JPopupMenu popup = new JPopupMenu();
        popup.setBackground(Color.WHITE);
        popup.setBorder(BorderFactory.createLineBorder(new Color(210, 215, 225), 1));

        JPanel grid = new JPanel(new GridLayout(4, 4, 6, 6));
        grid.setBackground(Color.WHITE);
        grid.setBorder(new EmptyBorder(10, 10, 10, 10));

        for (String em : ColorEmojiIcon.SUPPORTED_EMOJIS) {
            JButton btn = new JButton(new ColorEmojiIcon(em, 28));
            btn.setBackground(Color.WHITE);
            btn.setFocusPainted(false);
            btn.setBorder(new EmptyBorder(4, 4, 4, 4));
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            btn.addActionListener(e -> {
                inputField.setText(inputField.getText() + em);
                popup.setVisible(false);
                inputField.requestFocus();
            });
            grid.add(btn);
        }
        popup.add(grid);
        popup.show(invoker, 0, -195);
    }

    private void sendFileAttachment() {
        if (currentChatTarget.equals(CHANNEL_MULTICAST) || currentChatTarget.equals(CHANNEL_BROADCAST)) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một người dùng cụ thể ở danh sách bên trái để gửi File!");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try {
                byte[] fileBytes = Files.readAllBytes(file.toPath());
                sendPacket(new Packet(Packet.Type.FILE, username, currentChatTarget, file.getName(), fileBytes));
                addMessageToConversation(currentChatTarget,
                        ChatItem.file(username, file.getName(), fileBytes, getCurrentTimestamp(), true));
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Không thể đọc tệp: " + ex.getMessage());
            }
        }
    }

    // Bấm 1 lần để Bắt đầu ghi âm -> Bấm lần 2 để Dừng và Gửi lên khung chat
    private void toggleVoiceRecording() {
        if (currentChatTarget.equals(CHANNEL_MULTICAST) || currentChatTarget.equals(CHANNEL_BROADCAST)) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một người dùng cụ thể bên trái để gửi tin nhắn thoại!");
            return;
        }

        if (!isRecording) {
            try {
                AudioFormat format = new AudioFormat(16000, 16, 1, true, true);
                activeMicLine = AudioSystem.getTargetDataLine(format);
                activeMicLine.open(format);
                activeMicLine.start();

                recordedAudioBuffer = new ByteArrayOutputStream();
                isRecording = true;
                recordStartTime = System.currentTimeMillis();

                btnMic.setForeground(new Color(220, 38, 38));
                btnMic.setText("Dừng & Gửi (00:00)");

                recordTimer = new Timer(500, e -> {
                    long sec = (System.currentTimeMillis() - recordStartTime) / 1000;
                    btnMic.setText(String.format("Dừng & Gửi (%02d:%02d)", sec / 60, sec % 60));
                });
                recordTimer.start();

                new Thread(() -> {
                    byte[] buffer = new byte[1024];
                    while (isRecording && activeMicLine != null) {
                        int count = activeMicLine.read(buffer, 0, buffer.length);
                        if (count > 0) recordedAudioBuffer.write(buffer, 0, count);
                    }
                }).start();

            } catch (Exception ex) {
                isRecording = false;
                JOptionPane.showMessageDialog(this, "Không tìm thấy Microphone trên máy: " + ex.getMessage());
            }
        } else {
            // Dừng ghi âm và gửi
            isRecording = false;
            if (recordTimer != null) recordTimer.stop();
            long durationSec = Math.max(1, (System.currentTimeMillis() - recordStartTime) / 1000);
            String durationText = String.format("%02d:%02d", durationSec / 60, durationSec % 60);

            if (activeMicLine != null) {
                activeMicLine.stop();
                activeMicLine.close();
            }

            btnMic.setForeground(TEXT_PRIMARY);
            btnMic.setText("Ghi âm");

            byte[] audioBytes = recordedAudioBuffer.toByteArray();
            if (audioBytes.length > 0) {
                sendPacket(new Packet(Packet.Type.AUDIO, username, currentChatTarget, durationText, audioBytes));
                addMessageToConversation(currentChatTarget,
                        ChatItem.audio(username, durationText, audioBytes, getCurrentTimestamp(), true));
            }
        }
    }

    private void playAudioData(byte[] audioBytes, JLabel playBadge, JLabel lblAudio, String durationText) {
        if (audioBytes == null || audioBytes.length == 0) return;
        new Thread(() -> {
            try {
                SwingUtilities.invokeLater(() -> {
                    playBadge.setText(" 🔊 ");
                    lblAudio.setText("Đang phát... (" + durationText + ")");
                });

                AudioFormat format = new AudioFormat(16000, 16, 1, true, true);
                SourceDataLine speaker = AudioSystem.getSourceDataLine(format);
                speaker.open(format);
                speaker.start();
                speaker.write(audioBytes, 0, audioBytes.length);
                speaker.drain();
                speaker.close();
            } catch (Exception ignored) {
            } finally {
                SwingUtilities.invokeLater(() -> {
                    playBadge.setText(" ▶ ");
                    lblAudio.setText("Tin nhắn thoại (" + durationText + ")  ılılıllı");
                });
            }
        }).start();
    }

    // =========================================================================
    // VIDEO CALL WEBCAM GIỐNG ZALO (2 CHIỀU QUA UDP + GIAO DIỆN CUỘC GỌI RIÊNG)
    // =========================================================================
    private void initiateVideoCall() {
        if (currentChatTarget.equals(CHANNEL_BROADCAST) || currentChatTarget.equals(CHANNEL_MULTICAST)) return;

        openVideoCallWindow(currentChatTarget, "Đang gọi video cho " + currentChatTarget + "...");
        sendPacket(new Packet(Packet.Type.VIDEO_CALL_REQ, username, currentChatTarget, "CALL"));
        addMessageToConversation(currentChatTarget,
                ChatItem.text(username, "📹 Bạn đã bắt đầu cuộc gọi video", getCurrentTimestamp(), true));
    }

    private void openVideoCallWindow(String partnerName, String initialStatus) {
        SwingUtilities.invokeLater(() -> {
            if (activeVideoDialog != null) activeVideoDialog.dispose();
            activeVideoDialog = new VideoCallDialog(this, partnerName, initialStatus);
            activeVideoDialog.setVisible(true);
        });
    }

    private void startWebcamStreamer(String targetIP) {
        isVideoCalling = true;
        new Thread(() -> {
            Object webcam = openWebcamReflectively();
            Robot fallbackRobot = null;
            if (webcam == null) {
                try { fallbackRobot = new Robot(); } catch (Exception ignored) {}
            }

            try (DatagramSocket udpSocket = new DatagramSocket()) {
                InetAddress addr = InetAddress.getByName(targetIP);
                while (isVideoCalling) {
                    BufferedImage rawFrame = captureFrame(webcam, fallbackRobot);
                    if (rawFrame != null) {
                        // Hiển thị camera của chính mình lên góc PiP kiểu Zalo
                        if (activeVideoDialog != null) {
                            activeVideoDialog.updateLocalPreview(rawFrame);
                        }

                        // Nén JPEG gọn nhẹ gửi sang máy đối phương
                        if (isCameraEnabled) {
                            byte[] jpegBytes = compressJpeg(resizeImage(rawFrame, 360, 270), 0.65f);
                            if (jpegBytes.length < 60000) {
                                udpSocket.send(new DatagramPacket(jpegBytes, jpegBytes.length, addr, VIDEO_UDP_PORT));
                            }
                        }
                    }
                    Thread.sleep(70); // ~14 FPS mượt mà qua mạng LAN
                }
            } catch (Exception ignored) {
            } finally {
                closeWebcamReflectively(webcam);
            }
        }).start();
    }

    private void startVideoReceiver() {
        new Thread(() -> {
            try (DatagramSocket udpSocket = new DatagramSocket(VIDEO_UDP_PORT)) {
                byte[] buf = new byte[65000];
                while (true) {
                    DatagramPacket dp = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(dp);
                    BufferedImage remoteImg = ImageIO.read(new ByteArrayInputStream(dp.getData(), 0, dp.getLength()));
                    if (remoteImg != null && activeVideoDialog != null && activeVideoDialog.isVisible()) {
                        activeVideoDialog.updateRemoteVideo(remoteImg);
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void endVideoCall(String partnerName, boolean notifyPartner) {
        isVideoCalling = false;
        if (notifyPartner && partnerName != null) {
            sendPacket(new Packet(Packet.Type.TEXT, username, partnerName, END_CALL_SIGNAL));
        }
        SwingUtilities.invokeLater(() -> {
            if (activeVideoDialog != null) {
                activeVideoDialog.dispose();
                activeVideoDialog = null;
            }
        });
    }

    // Truy xuất Webcam qua Reflection để chạy mượt cả khi có hoặc chưa có thư viện sarxos
    private Object openWebcamReflectively() {
        try {
            Class<?> webcamClass = Class.forName("com.github.sarxos.webcam.Webcam");
            Method getDefault = webcamClass.getMethod("getDefault");
            Object cam = getDefault.invoke(null);
            if (cam != null) {
                Method open = webcamClass.getMethod("open");
                open.invoke(cam);
                return cam;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private BufferedImage captureFrame(Object webcam, Robot robot) {
        try {
            if (webcam != null) {
                Method getImage = webcam.getClass().getMethod("getImage");
                return (BufferedImage) getImage.invoke(webcam);
            }
        } catch (Exception ignored) {}

        if (robot != null) {
            return robot.createScreenCapture(new Rectangle(0, 0, 480, 360));
        }
        return null;
    }

    private void closeWebcamReflectively(Object webcam) {
        try {
            if (webcam != null) {
                Method close = webcam.getClass().getMethod("close");
                close.invoke(webcam);
            }
        } catch (Exception ignored) {}
    }

    private BufferedImage resizeImage(BufferedImage src, int w, int h) {
        BufferedImage resized = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = resized.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(src, 0, 0, w, h, null);
        g2.dispose();
        return resized;
    }

    private byte[] compressJpeg(BufferedImage img, float quality) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        try (MemoryCacheImageOutputStream mcios = new MemoryCacheImageOutputStream(baos)) {
            writer.setOutput(mcios);
            writer.write(null, new IIOImage(img, null, null), param);
        }
        writer.dispose();
        return baos.toByteArray();
    }

    // =========================================================================
    // LẮNG NGHE MẠNG (MULTICAST & TCP SERVER)
    // =========================================================================
    private void startMulticastListener() {
        new Thread(() -> {
            try {
                multicastSocket = new MulticastSocket(MULTICAST_PORT);
                InetAddress group = InetAddress.getByName(MULTICAST_IP);
                multicastSocket.joinGroup(group);
                byte[] buf = new byte[4096];
                while (true) {
                    DatagramPacket dp = new DatagramPacket(buf, buf.length);
                    multicastSocket.receive(dp);
                    String raw = new String(dp.getData(), 0, dp.getLength(), StandardCharsets.UTF_8);
                    String[] parts = raw.split("\\|\\|", 2);
                    String sender = parts.length == 2 ? parts[0] : "Multicast";
                    String content = parts.length == 2 ? parts[1] : raw;
                    addMessageToConversation(CHANNEL_MULTICAST,
                            ChatItem.text(sender, content, getCurrentTimestamp(), sender.equals(username)));
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void listenToServer() {
        try {
            while (!socket.isClosed()) {
                Packet p = (Packet) in.readObject();
                switch (p.getType()) {
                    case USER_LIST -> SwingUtilities.invokeLater(() -> {
                        String selected = contactList.getSelectedValue();
                        contactListModel.clear();
                        contactListModel.addElement(CHANNEL_BROADCAST);
                        contactListModel.addElement(CHANNEL_MULTICAST);
                        for (String u : p.getContent().split(",")) {
                            if (!u.isEmpty() && !u.equals(username)) {
                                contactListModel.addElement(u);
                            }
                        }
                        if (selected != null && contactListModel.contains(selected)) {
                            contactList.setSelectedValue(selected, true);
                        } else {
                            contactList.setSelectedIndex(0);
                        }
                    });

                    case TEXT -> {
                        if (END_CALL_SIGNAL.equals(p.getContent())) {
                            endVideoCall(p.getSender(), false);
                            addMessageToConversation(p.getSender(),
                                    ChatItem.text(p.getSender(), "📵 Cuộc gọi video đã kết thúc", getCurrentTimestamp(), false));
                        } else {
                            addMessageToConversation(p.getSender(),
                                    ChatItem.text(p.getSender(), p.getContent(), getCurrentTimestamp(), false));
                        }
                    }

                    case BROADCAST -> addMessageToConversation(CHANNEL_BROADCAST,
                            ChatItem.text(p.getSender(), p.getContent(), getCurrentTimestamp(), false));

                    case FILE -> addMessageToConversation(p.getSender(),
                            ChatItem.file(p.getSender(), p.getContent(), p.getData(), getCurrentTimestamp(), false));

                    case AUDIO -> addMessageToConversation(p.getSender(),
                            ChatItem.audio(p.getSender(), p.getContent(), p.getData(), getCurrentTimestamp(), false));

                    case VIDEO_CALL_REQ -> {
                        String caller = p.getSender();
                        String callerIP = p.getContent();
                        if (isVideoCalling && activeVideoDialog != null) {
                            // Đối phương đã chấp nhận cuộc gọi của mình -> bắt đầu truyền luồng camera sang đối phương
                            activeVideoDialog.setStatusText("● Đang kết nối Video với " + caller);
                            startWebcamStreamer(callerIP);
                        } else {
                            // Có cuộc gọi Video đến -> Hiển thị hộp thoại Nghe máy / Từ chối kiểu Zalo
                            SwingUtilities.invokeLater(() -> {
                                int ans = JOptionPane.showConfirmDialog(this,
                                        "📹 " + caller + " đang gọi Video cho bạn.\nBạn có muốn trả lời không?",
                                        "Cuộc gọi Video Zalo đến",
                                        JOptionPane.YES_NO_OPTION,
                                        JOptionPane.INFORMATION_MESSAGE);
                                if (ans == JOptionPane.YES_OPTION) {
                                    switchConversation(caller);
                                    openVideoCallWindow(caller, "● Đang kết nối Video với " + caller);
                                    sendPacket(new Packet(Packet.Type.VIDEO_CALL_REQ, username, caller, "ACCEPT"));
                                    startWebcamStreamer(callerIP);
                                } else {
                                    sendPacket(new Packet(Packet.Type.TEXT, username, caller, END_CALL_SIGNAL));
                                }
                            });
                        }
                    }

                    case BAN -> {
                        JOptionPane.showMessageDialog(this, p.getContent(), "BỊ CẤM TRUY CẬP", JOptionPane.ERROR_MESSAGE);
                        System.exit(0);
                    }
                }
            }
        } catch (Exception e) {
            SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this, "Mất kết nối tới Server!", "Ngắt kết nối", JOptionPane.WARNING_MESSAGE));
        }
    }

    private synchronized void sendPacket(Packet p) {
        try {
            out.writeObject(p);
            out.flush();
            out.reset();
        } catch (IOException ignored) {}
    }

    private String getCurrentTimestamp() {
        return new SimpleDateFormat("HH:mm • dd/MM/yyyy").format(new Date());
    }

    private Color getAvatarColor(String name) {
        Color[] palette = {
                new Color(0, 104, 255), new Color(16, 185, 129), new Color(245, 158, 11),
                new Color(139, 92, 246), new Color(236, 72, 153), new Color(14, 165, 233)
        };
        return palette[Math.abs(name.hashCode()) % palette.length];
    }

    private String getFileExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx > 0 ? fileName.substring(idx + 1) : "";
    }

    private Color getFileBadgeColor(String ext) {
        return switch (ext) {
            case "PDF" -> new Color(220, 38, 38);
            case "DOC", "DOCX" -> new Color(37, 99, 235);
            case "XLS", "XLSX" -> new Color(22, 163, 74);
            case "PNG", "JPG", "JPEG", "GIF" -> new Color(147, 51, 234);
            case "ZIP", "RAR" -> new Color(217, 119, 6);
            default -> ZALO_BLUE;
        };
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    // =========================================================================
    // CÁC LỚP ĐỒ HỌA & MÔ HÌNH DỮ LIỆU NỘI BỘ
    // =========================================================================
    private static class ChatItem {
        enum Kind { TEXT, FILE, AUDIO }
        final Kind kind;
        final String sender;
        final String text;
        final String fileName;
        final byte[] binaryData;
        final String timestamp;
        final boolean isMe;

        private ChatItem(Kind kind, String sender, String text, String fileName, byte[] binaryData, String timestamp, boolean isMe) {
            this.kind = kind;
            this.sender = sender;
            this.text = text;
            this.fileName = fileName;
            this.binaryData = binaryData;
            this.timestamp = timestamp;
            this.isMe = isMe;
        }

        static ChatItem text(String sender, String text, String time, boolean isMe) {
            return new ChatItem(Kind.TEXT, sender, text, null, null, time, isMe);
        }

        static ChatItem file(String sender, String fileName, byte[] data, String time, boolean isMe) {
            return new ChatItem(Kind.FILE, sender, null, fileName, data, time, isMe);
        }

        static ChatItem audio(String sender, String duration, byte[] data, String time, boolean isMe) {
            return new ChatItem(Kind.AUDIO, sender, duration, null, data, time, isMe);
        }
    }

    // Bong bóng bo góc mềm mại kiểu Zalo
    private static class RoundedBubblePanel extends JPanel {
        private final Color bgColor;
        private final Color borderColor;
        private final int radius;

        public RoundedBubblePanel(Color bgColor, Color borderColor, int radius) {
            this.bgColor = bgColor;
            this.borderColor = borderColor;
            this.radius = radius;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bgColor);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, radius, radius));
            g2.setColor(borderColor);
            g2.draw(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, radius, radius));
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // Avatar hình tròn chữ cái đầu kiểu Zalo
    private static class AvatarBadge extends JComponent {
        private String initials;
        private Color bg;
        private final Color fg;
        private final int size;

        public AvatarBadge(String name, int size, Color bg, Color fg) {
            this.size = size;
            this.bg = bg;
            this.fg = fg;
            setPreferredSize(new Dimension(size, size));
            setMinimumSize(new Dimension(size, size));
            setMaximumSize(new Dimension(size, size));
            update(name, bg);
        }

        public void update(String name, Color newBg) {
            this.bg = newBg;
            String clean = name.replaceAll("[^a-zA-Z0-9À-ỹ]", "").trim();
            this.initials = clean.isEmpty() ? "?" : clean.substring(0, 1).toUpperCase();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bg);
            g2.fill(new Ellipse2D.Float(0, 0, size - 1, size - 1));
            g2.setColor(fg);
            g2.setFont(new Font("Segoe UI", Font.BOLD, size / 2 - 2));
            FontMetrics fm = g2.getFontMetrics();
            int x = (size - fm.stringWidth(initials)) / 2;
            int y = (size - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(initials, x, y);
            g2.dispose();
        }
    }

    // Renderer danh sách người dùng Online bên trái
    private class ContactCellRenderer extends JPanel implements ListCellRenderer<String> {
        private final AvatarBadge avatar = new AvatarBadge("U", 38, ZALO_BLUE, Color.WHITE);
        private final JLabel lblName = new JLabel();
        private final JLabel lblSub = new JLabel();
        private final JLabel lblUnread = new JLabel("", SwingConstants.CENTER);

        public ContactCellRenderer() {
            setLayout(new BorderLayout(10, 0));
            setBorder(new EmptyBorder(8, 14, 8, 14));

            JPanel center = new JPanel(new GridLayout(2, 1, 0, 2));
            center.setOpaque(false);
            lblName.setFont(new Font("Segoe UI", Font.BOLD, 13));
            lblName.setForeground(TEXT_PRIMARY);
            lblSub.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            lblSub.setForeground(TEXT_SECONDARY);
            center.add(lblName);
            center.add(lblSub);

            lblUnread.setOpaque(true);
            lblUnread.setBackground(new Color(220, 38, 38));
            lblUnread.setForeground(Color.WHITE);
            lblUnread.setFont(new Font("Segoe UI", Font.BOLD, 11));
            lblUnread.setPreferredSize(new Dimension(22, 22));

            add(avatar, BorderLayout.WEST);
            add(center, BorderLayout.CENTER);
            add(lblUnread, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends String> list, String value, int index, boolean isSelected, boolean cellHasFocus) {
            setBackground(isSelected ? SELECTED_BG : SIDEBAR_BG);
            lblName.setText(value);

            if (value.equals(CHANNEL_BROADCAST)) {
                avatar.update("ALL", new Color(255, 149, 0));
                lblSub.setText("Gửi thông báo toàn mạng");
            } else if (value.equals(CHANNEL_MULTICAST)) {
                avatar.update("MC", new Color(111, 66, 193));
                lblSub.setText("Nhóm chat LAN Multicast");
            } else {
                avatar.update(value, getAvatarColor(value));
                lblSub.setText("● Đang trực tuyến");
                lblSub.setForeground(new Color(40, 167, 69));
            }

            int unread = unreadCounts.getOrDefault(value, 0);
            lblUnread.setVisible(unread > 0);
            lblUnread.setText(String.valueOf(unread));
            return this;
        }
    }

    // Bộ Icon Biểu Cảm Vẽ Màu Vector 2D (Luôn có màu sắc nét trên mọi OS)
    private static class ColorEmojiIcon implements Icon {
        static final List<String> SUPPORTED_EMOJIS = List.of(
                "😀", "😂", "😍", "😎", "😭", "😡", "😘", "🤔",
                "❤️", "👍", "🔥", "🎉", "👏", "✨", "🚀", "💯"
        );

        private final String code;
        private final int size;

        public ColorEmojiIcon(String code, int size) {
            this.code = code;
            this.size = size;
        }

        public static boolean isSupportedEmoji(String s) {
            return SUPPORTED_EMOJIS.contains(s);
        }

        @Override
        public int getIconWidth() { return size; }

        @Override
        public int getIconHeight() { return size; }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(x, y);

            int pad = 2;
            int d = size - pad * 2;

            switch (code) {
                case "❤️" -> {
                    g2.setColor(new Color(239, 68, 68));
                    int w = d, h = d;
                    g2.fillOval(pad, pad + h / 8, w / 2, h / 2);
                    g2.fillOval(pad + w / 2, pad + h / 8, w / 2, h / 2);
                    Polygon tri = new Polygon(
                            new int[]{pad, pad + w, pad + w / 2},
                            new int[]{pad + h * 2 / 5, pad + h * 2 / 5, pad + h}, 3);
                    g2.fillPolygon(tri);
                }
                case "👍" -> {
                    g2.setColor(ZALO_BLUE);
                    g2.fillRoundRect(pad + d / 5, pad + d / 3, d * 3 / 5, d / 2, 6, 6);
                    g2.fillRoundRect(pad + d / 3, pad + d / 8, d / 4, d / 2, 5, 5);
                }
                case "🔥" -> {
                    g2.setColor(new Color(249, 115, 22));
                    g2.fillOval(pad + d / 6, pad + d / 5, d * 2 / 3, d * 3 / 4);
                    g2.setColor(new Color(250, 204, 21));
                    g2.fillOval(pad + d / 3, pad + d / 2, d / 3, d / 3);
                }
                case "🎉", "✨", "🚀", "👏", "💯" -> {
                    g2.setColor(new Color(139, 92, 246));
                    g2.fillRoundRect(pad, pad, d, d, 8, 8);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("Segoe UI Emoji", Font.BOLD, size / 2));
                    g2.drawString(code, pad + 3, pad + d * 3 / 4);
                }
                default -> {
                    // Các biểu cảm khuôn mặt màu vàng cam đặc trưng
                    Color faceColor = code.equals("😡") ? new Color(239, 68, 68) : new Color(255, 204, 51);
                    g2.setColor(faceColor);
                    g2.fillOval(pad, pad, d, d);
                    g2.setColor(new Color(217, 155, 20));
                    g2.drawOval(pad, pad, d, d);

                    // Mắt
                    g2.setColor(code.equals("😍") ? new Color(220, 38, 38) : new Color(60, 40, 20));
                    int eyeY = pad + d / 3;
                    if (code.equals("😎")) {
                        g2.setColor(Color.BLACK);
                        g2.fillRoundRect(pad + d / 5, eyeY - 2, d / 4, d / 5, 3, 3);
                        g2.fillRoundRect(pad + d * 11 / 20, eyeY - 2, d / 4, d / 5, 3, 3);
                        g2.drawLine(pad + d / 4, eyeY, pad + d * 3 / 4, eyeY);
                    } else {
                        g2.fillOval(pad + d / 4, eyeY, Math.max(3, d / 7), Math.max(3, d / 7));
                        g2.fillOval(pad + d * 3 / 5, eyeY, Math.max(3, d / 7), Math.max(3, d / 7));
                    }

                    // Giọt nước mắt xanh cho 😂 và 😭
                    if (code.equals("😂") || code.equals("😭")) {
                        g2.setColor(new Color(56, 189, 248));
                        g2.fillOval(pad + d / 7, eyeY + d / 6, d / 5, d / 4);
                        g2.fillOval(pad + d * 2 / 3, eyeY + d / 6, d / 5, d / 4);
                    }

                    // Miệng
                    g2.setColor(new Color(90, 45, 15));
                    if (code.equals("😭") || code.equals("😡")) {
                        g2.drawArc(pad + d / 4, pad + d * 3 / 5, d / 2, d / 4, 0, 180);
                    } else {
                        g2.fillArc(pad + d / 4, pad + d / 2, d / 2, d / 3, 180, 180);
                    }
                }
            }
            g2.dispose();
        }
    }

    // Cửa sổ Gọi Video Webcam kiểu Zalo (Màn hình đối phương + PiP Camera của mình)
    private class VideoCallDialog extends JDialog {
        private final String partner;
        private final JLabel lblStatus;
        private final JLabel lblRemoteFeed;
        private final JLabel lblLocalPip;

        public VideoCallDialog(JFrame owner, String partner, String status) {
            super(owner, "Zalo Video Call - " + partner, false);
            this.partner = partner;
            setSize(680, 520);
            setLocationRelativeTo(owner);
            setLayout(new BorderLayout());
            getContentPane().setBackground(new Color(20, 24, 32));

            // Header trạng thái cuộc gọi
            JPanel topBar = new JPanel(new BorderLayout());
            topBar.setBackground(new Color(15, 18, 25));
            topBar.setBorder(new EmptyBorder(10, 16, 10, 16));
            lblStatus = new JLabel(status);
            lblStatus.setFont(new Font("Segoe UI", Font.BOLD, 14));
            lblStatus.setForeground(Color.WHITE);
            topBar.add(lblStatus, BorderLayout.WEST);
            add(topBar, BorderLayout.NORTH);

            // Vùng video phân lớp (Màn hình lớn đối phương + Khung PiP camera của mình)
            JLayeredPane layeredPane = new JLayeredPane();
            layeredPane.setPreferredSize(new Dimension(660, 390));

            lblRemoteFeed = new JLabel("Đang chờ hình ảnh từ " + partner + "...", SwingConstants.CENTER);
            lblRemoteFeed.setForeground(Color.LIGHT_GRAY);
            lblRemoteFeed.setFont(new Font("Segoe UI", Font.PLAIN, 15));
            lblRemoteFeed.setBounds(10, 10, 640, 370);
            layeredPane.add(lblRemoteFeed, JLayeredPane.DEFAULT_LAYER);

            lblLocalPip = new JLabel("Camera của bạn", SwingConstants.CENTER);
            lblLocalPip.setOpaque(true);
            lblLocalPip.setBackground(new Color(35, 40, 52));
            lblLocalPip.setForeground(Color.WHITE);
            lblLocalPip.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            lblLocalPip.setBorder(BorderFactory.createLineBorder(ZALO_BLUE, 2));
            lblLocalPip.setBounds(480, 245, 160, 120);
            layeredPane.add(lblLocalPip, JLayeredPane.PALETTE_LAYER);

            add(layeredPane, BorderLayout.CENTER);

            // Thanh nút điều khiển cuộc gọi phía dưới
            JPanel bottomControls = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 12));
            bottomControls.setBackground(new Color(15, 18, 25));

            JButton btnToggleCam = new JButton("📷 Tắt Camera");
            btnToggleCam.setFont(new Font("Segoe UI", Font.BOLD, 13));
            btnToggleCam.setBackground(new Color(55, 65, 81));
            btnToggleCam.setForeground(Color.WHITE);
            btnToggleCam.setFocusPainted(false);
            btnToggleCam.addActionListener(e -> {
                isCameraEnabled = !isCameraEnabled;
                btnToggleCam.setText(isCameraEnabled ? "📷 Tắt Camera" : "🚫 Bật Camera");
                if (!isCameraEnabled) {
                    lblLocalPip.setIcon(null);
                    lblLocalPip.setText("Đã tắt Camera");
                }
            });

            JButton btnEnd = new JButton("📞 Kết thúc");
            btnEnd.setFont(new Font("Segoe UI", Font.BOLD, 13));
            btnEnd.setBackground(new Color(220, 38, 38));
            btnEnd.setForeground(Color.WHITE);
            btnEnd.setFocusPainted(false);
            btnEnd.addActionListener(e -> endVideoCall(partner, true));

            bottomControls.add(btnToggleCam);
            bottomControls.add(btnEnd);
            add(bottomControls, BorderLayout.SOUTH);
        }

        public void setStatusText(String text) {
            SwingUtilities.invokeLater(() -> lblStatus.setText(text));
        }

        public void updateRemoteVideo(BufferedImage img) {
            Image scaled = img.getScaledInstance(640, 370, Image.SCALE_FAST);
            SwingUtilities.invokeLater(() -> {
                lblRemoteFeed.setText("");
                lblRemoteFeed.setIcon(new ImageIcon(scaled));
            });
        }

        public void updateLocalPreview(BufferedImage img) {
            if (!isCameraEnabled) return;
            Image scaled = img.getScaledInstance(160, 120, Image.SCALE_FAST);
            SwingUtilities.invokeLater(() -> {
                lblLocalPip.setText("");
                lblLocalPip.setIcon(new ImageIcon(scaled));
            });
        }
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel"); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new ClientApp().setVisible(true));
    }
}