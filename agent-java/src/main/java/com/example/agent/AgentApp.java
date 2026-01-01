package com.example.agent;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;

public class AgentApp {
    private JFrame frame;
    private JTextField urlField;
    private JTextField roomField;
    private JTextField nameField;
    private JTextArea logArea;
    private JLabel wsStatus;
    private JLabel dcStatus;
    private JTextField stunField;
    private JTextField turnField;
    private JTextField turnUserField;
    private JTextField turnPassField;
    private JButton startBtn;
    private JButton stopBtn;

    private AgentClient client;
    private ControlHandler controlHandler;
    private WebRTCManager webRTCManager;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                new AgentApp().initUI();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void initUI() throws Exception {
        frame = new JFrame("WebRTC Agent");
        frame.setSize(520, 520);
        frame.setLayout(new BorderLayout());
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

        JPanel form = new JPanel(new GridLayout(8, 2, 8, 8));
        form.add(new JLabel("Signal WS URL:"));
        urlField = new JTextField("ws://localhost:8080/ws");
        form.add(urlField);
        form.add(new JLabel("Room ID:"));
        roomField = new JTextField("demo-room");
        form.add(roomField);
        form.add(new JLabel("Name:"));
        nameField = new JTextField("agent");
        form.add(nameField);

        form.add(new JLabel("STUN URLs (comma):"));
        stunField = new JTextField("stun:43.139.50.108:3478");
        form.add(stunField);
        form.add(new JLabel("TURN URLs (comma):"));
        turnField = new JTextField("turn:43.139.50.108:3478?transport=udp,turn:43.139.50.108:3478?transport=tcp");
        form.add(turnField);
        form.add(new JLabel("TURN User:"));
        turnUserField = new JTextField("admin");
        form.add(turnUserField);
        form.add(new JLabel("TURN Pass:"));
        turnPassField = new JTextField("123456");
        form.add(turnPassField);

        startBtn = new JButton("Start");
        stopBtn = new JButton("Stop");
        stopBtn.setEnabled(false);
        form.add(startBtn);
        form.add(stopBtn);

        JPanel statusPanel = new JPanel(new GridLayout(1, 2, 8, 8));
        wsStatus = new JLabel("WS: idle");
        dcStatus = new JLabel("DC: not implemented");
        statusPanel.add(wsStatus);
        statusPanel.add(dcStatus);

        logArea = new JTextArea();
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);

        frame.add(form, BorderLayout.NORTH);
        frame.add(statusPanel, BorderLayout.CENTER);
        frame.add(scrollPane, BorderLayout.SOUTH);

        startBtn.addActionListener(e -> start());
        stopBtn.addActionListener(e -> stop());
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stop();
            }
        });

        frame.setVisible(true);
    }

    private void start() {
        try {
            stop();
            controlHandler = new ControlHandler(this::log);
            URI uri = new URI(urlField.getText().trim());
            
            // 解析 STUN/TURN 配置
            String[] stunUrls = stunField.getText().trim().split(",");
            String[] turnUrls = turnField.getText().trim().split(",");
            String turnUser = turnUserField.getText().trim();
            String turnPass = turnPassField.getText().trim();
            
            client = new AgentClient(uri, roomField.getText().trim(), nameField.getText().trim(), controlHandler, msg -> {
                log(msg);
                setWsStatus(msg);
            });
            
            // 创建 WebRTC 管理器
            webRTCManager = new WebRTCManager(client, msg -> {
                log(msg);
                setDcStatus(msg);
            }, stunUrls, turnUrls, turnUser, turnPass, controlHandler);
            
            client.setWebRTCManager(webRTCManager);
            webRTCManager.init();
            
            client.connect();
            startBtn.setEnabled(false);
            stopBtn.setEnabled(true);
            setWsStatus("connecting...");
            setDcStatus("WebRTC 已初始化");
            log("正在连接...");
        } catch (Exception ex) {
            log("启动失败: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void stop() {
        if (webRTCManager != null) {
            try { webRTCManager.cleanup(); } catch (Exception ignored) {}
            webRTCManager = null;
        }
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
            client = null;
        }
        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);
        setWsStatus("已停止");
        setDcStatus("未连接");
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
        System.out.println(msg);
    }

    // 状态栏便捷方法
    private void setWsStatus(String s) {
        SwingUtilities.invokeLater(() -> wsStatus.setText("WS: " + s));
    }

    private void setDcStatus(String s) {
        SwingUtilities.invokeLater(() -> dcStatus.setText("DC: " + s));
    }
}
