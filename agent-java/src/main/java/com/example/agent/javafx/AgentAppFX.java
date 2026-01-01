package com.example.agent.javafx;

import com.example.agent.AgentClient;
import com.example.agent.ControlHandler;
import com.example.agent.WebRTCManager;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.net.URI;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * JavaFX 主应用 - WebRTC Agent
 */
public class AgentAppFX extends Application {
    // 管理器
    private ConfigManager configManager;
    private LogManager logManager;
    
    // 配置字段
    private TextField urlField;
    private TextField roomField;
    private TextField nameField;
    private TextField stunField;
    private TextField turnField;
    private TextField turnUserField;
    private PasswordField turnPassField;
    
    // 控制按钮
    private Button startBtn;
    private Button stopBtn;
    
    // 状态标签
    private Label wsStatusLabel;
    private Label dcStatusLabel;
    private Label iceStatusLabel;
    private Label connectionStatusLabel;
    
    // 日志显示
    private TextArea logTextArea;
    private ListView<String> logListView;
    
    // 聊天组件
    private TextArea chatArea;
    private TextField chatInput;
    private Button chatSendBtn;
    private Label chatStatusHint;
    private boolean dataChannelReady = false;
    
    // 流配置状态
    private Label streamConfigStatusLabel;
    
    // WebRTC 组件
    private AgentClient client;
    private ControlHandler controlHandler;
    private WebRTCManager webRTCManager;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        // 初始化管理器
        configManager = new ConfigManager();
        configManager.load();
        
        logManager = new LogManager();
        
        // 设置窗口
        primaryStage.setTitle("WebRTC Agent - JavaFX");
        primaryStage.setWidth(1000);
        primaryStage.setHeight(650);
        
        // 创建标签页
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        
        // 配置标签页
        Tab configTab = new Tab("配置");
        configTab.setContent(createConfigPane());
        
        // 日志标签页
        Tab logTab = new Tab("日志");
        logTab.setContent(createLogPane());
        
        // 状态标签页
        Tab statusTab = new Tab("状态监控");
        statusTab.setContent(createStatusPane());
        
        tabPane.getTabs().addAll(configTab, logTab, statusTab);
        
        // 主布局: 左侧TabPane + 右侧聊天面板
        HBox mainLayout = new HBox(10);
        mainLayout.setPadding(new Insets(10));
        mainLayout.setStyle("-fx-background-color: #1a202c;");
        
        // 左侧: TabPane (配置/日志/状态)
        VBox leftPane = new VBox(tabPane);
        HBox.setHgrow(leftPane, Priority.ALWAYS);
        leftPane.setPrefWidth(650);
        
        // 右侧: 聊天面板
        VBox chatPane = createChatPane();
        chatPane.setPrefWidth(300);
        chatPane.setMinWidth(280);
        
        mainLayout.getChildren().addAll(leftPane, chatPane);
        
        // 创建场景
        Scene scene = new Scene(mainLayout);
        primaryStage.setScene(scene);
        
        // 窗口关闭事件
        primaryStage.setOnCloseRequest(event -> {
            stop();
            Platform.exit();
        });
        
        primaryStage.show();
        
        logManager.addLog("应用已启动");
    }

    /**
     * 创建配置面板
     */
    private VBox createConfigPane() {
        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        
        // 标题
        Label titleLabel = new Label("连接配置");
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        
        // 配置表单
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        
        int row = 0;
        
        // 信令 URL
        grid.add(new Label("信令 URL:"), 0, row);
        urlField = new TextField(configManager.getSignalUrl());
        urlField.setPrefWidth(400);
        grid.add(urlField, 1, row++);
        
        // 房间 ID
        grid.add(new Label("房间 ID:"), 0, row);
        roomField = new TextField(configManager.getRoomId());
        grid.add(roomField, 1, row++);
        
        // 昵称
        grid.add(new Label("昵称:"), 0, row);
        nameField = new TextField(configManager.getName());
        grid.add(nameField, 1, row++);
        
        // 分隔线
        grid.add(new Separator(), 0, row++, 2, 1);
        
        // STUN URLs
        grid.add(new Label("STUN URLs:"), 0, row);
        stunField = new TextField(configManager.getStunUrls());
        stunField.setPromptText("多个用逗号分隔");
        grid.add(stunField, 1, row++);
        
        // TURN URLs
        grid.add(new Label("TURN URLs:"), 0, row);
        turnField = new TextField(configManager.getTurnUrls());
        turnField.setPromptText("多个用逗号分隔");
        grid.add(turnField, 1, row++);
        
        // TURN 用户名
        grid.add(new Label("TURN 用户名:"), 0, row);
        turnUserField = new TextField(configManager.getTurnUser());
        grid.add(turnUserField, 1, row++);
        
        // TURN 密码
        grid.add(new Label("TURN 密码:"), 0, row);
        turnPassField = new PasswordField();
        turnPassField.setText(configManager.getTurnPassword());
        grid.add(turnPassField, 1, row++);
        
        // 按钮区域
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_LEFT);
        
        startBtn = new Button("启动连接");
        startBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 10 20;");
        startBtn.setOnAction(e -> startConnection());
        
        stopBtn = new Button("停止连接");
        stopBtn.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 10 20;");
        stopBtn.setDisable(true);
        stopBtn.setOnAction(e -> stopConnection());
        
        Button saveBtn = new Button("保存配置");
        saveBtn.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 10 20;");
        saveBtn.setOnAction(e -> saveConfig());
        
        buttonBox.getChildren().addAll(startBtn, stopBtn, saveBtn);
        
        // 提示信息
        Label hintLabel = new Label("提示: 配置会在启动连接时自动保存");
        hintLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: gray;");
        
        root.getChildren().addAll(titleLabel, grid, buttonBox, hintLabel);
        
        return root;
    }

    /**
     * 创建日志面板
     */
    private VBox createLogPane() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(20));
        
        // 工具栏
        HBox toolbar = new HBox(10);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        
        Label titleLabel = new Label("日志信息");
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        Button clearBtn = new Button("清空显示");
        clearBtn.setOnAction(e -> logManager.clearDisplayLogs());
        
        Button exportBtn = new Button("导出日志");
        exportBtn.setOnAction(e -> {
            boolean success = logManager.exportLogs(null);
            if (success) {
                showAlert(Alert.AlertType.INFORMATION, "导出成功", "日志已导出到 ~/.webrtc-agent/logs/");
            } else {
                showAlert(Alert.AlertType.ERROR, "导出失败", "无法导出日志文件");
            }
        });
        
        toolbar.getChildren().addAll(titleLabel, spacer, clearBtn, exportBtn);
        
        // 日志列表视图
        logListView = new ListView<>(logManager.getLogEntries());
        logListView.setStyle("-fx-font-family: 'Courier New', monospace; -fx-font-size: 12px;");
        VBox.setVgrow(logListView, Priority.ALWAYS);
        
        // 统计信息
        Label statsLabel = new Label();
        statsLabel.textProperty().bind(
            logListView.itemsProperty().asString("显示 %s 条日志")
        );
        statsLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: gray;");
        
        root.getChildren().addAll(toolbar, logListView, statsLabel);
        
        return root;
    }

    /**
     * 创建状态面板
     */
    private VBox createStatusPane() {
        VBox root = new VBox(20);
        root.setPadding(new Insets(20));
        
        Label titleLabel = new Label("连接状态监控");
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        
        // 状态网格
        GridPane grid = new GridPane();
        grid.setHgap(20);
        grid.setVgap(15);
        grid.setPadding(new Insets(10));
        
        int row = 0;
        
        // WebSocket 状态
        grid.add(createStatusLabel("WebSocket:"), 0, row);
        wsStatusLabel = createValueLabel("未连接");
        grid.add(wsStatusLabel, 1, row++);
        
        // DataChannel 状态
        grid.add(createStatusLabel("DataChannel:"), 0, row);
        dcStatusLabel = createValueLabel("未连接");
        grid.add(dcStatusLabel, 1, row++);
        
        // ICE 连接状态
        grid.add(createStatusLabel("ICE 连接:"), 0, row);
        iceStatusLabel = createValueLabel("未初始化");
        grid.add(iceStatusLabel, 1, row++);
        
        // 总体连接状态
        grid.add(createStatusLabel("总体状态:"), 0, row);
        connectionStatusLabel = createValueLabel("空闲");
        connectionStatusLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        grid.add(connectionStatusLabel, 1, row++);
        
        // 流配置状态
        grid.add(createStatusLabel("流配置:"), 0, row);
        streamConfigStatusLabel = createValueLabel("未配置");
        grid.add(streamConfigStatusLabel, 1, row++);
        
        root.getChildren().addAll(titleLabel, grid);
        
        return root;
    }
    
    /**
     * 创建聊天面板
     */
    private VBox createChatPane() {
        VBox pane = new VBox(10);
        pane.setPadding(new Insets(15));
        pane.setStyle("-fx-background-color: #2d3748; -fx-background-radius: 8;");
        
        // 标题
        Label title = new Label("💬 聊天");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #e2e8f0;");
        
        // 聊天记录区域
        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatArea.setWrapText(true);
        chatArea.setPrefHeight(400);
        chatArea.setStyle("-fx-control-inner-background: #1a202c; -fx-text-fill: #e2e8f0; " +
                          "-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px;");
        VBox.setVgrow(chatArea, Priority.ALWAYS);
        
        // 输入区域
        HBox inputBox = new HBox(8);
        inputBox.setAlignment(Pos.CENTER_LEFT);
        
        chatInput = new TextField();
        chatInput.setPromptText("输入消息...");
        chatInput.setDisable(true); // 初始禁用
        chatInput.setStyle("-fx-background-color: #4a5568; -fx-text-fill: #e2e8f0; -fx-prompt-text-fill: #a0aec0;");
        HBox.setHgrow(chatInput, Priority.ALWAYS);
        
        chatSendBtn = new Button("发送");
        chatSendBtn.setDisable(true); // 初始禁用
        chatSendBtn.setStyle("-fx-background-color: #4299e1; -fx-text-fill: white; -fx-font-weight: bold;");
        chatSendBtn.setOnAction(e -> sendChatMessage());
        
        chatInput.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                sendChatMessage();
            }
        });
        
        inputBox.getChildren().addAll(chatInput, chatSendBtn);
        
        // 状态提示
        chatStatusHint = new Label("⚠ DataChannel未连接，无法聊天");
        chatStatusHint.setStyle("-fx-text-fill: #fc8181; -fx-font-size: 11px;");
        
        pane.getChildren().addAll(title, chatArea, inputBox, chatStatusHint);
        return pane;
    }
    
    /**
     * 发送聊天消息
     */
    private void sendChatMessage() {
        if (!dataChannelReady) return;
        String text = chatInput.getText().trim();
        if (text.isEmpty()) return;
        
        // 显示自己的消息
        appendChat("我", text);
        chatInput.clear();
        
        // 通过DataChannel发送
        if (webRTCManager != null) {
            webRTCManager.sendChatMessage(nameField.getText().trim(), text);
        }
    }
    
    /**
     * 添加聊天消息到显示区域
     */
    private void appendChat(String sender, String message) {
        Platform.runLater(() -> {
            String timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            chatArea.appendText(String.format("[%s] %s: %s\n", timestamp, sender, message));
        });
    }
    
    /**
     * 更新聊天功能启用状态
     */
    private void updateChatEnabled(boolean enabled) {
        Platform.runLater(() -> {
            dataChannelReady = enabled;
            chatInput.setDisable(!enabled);
            chatSendBtn.setDisable(!enabled);
            
            if (enabled) {
                chatStatusHint.setText("✓ 已连接，可以聊天");
                chatStatusHint.setStyle("-fx-text-fill: #68d391; -fx-font-size: 11px;");
                chatInput.setStyle("-fx-background-color: #4a5568; -fx-text-fill: #e2e8f0; -fx-prompt-text-fill: #a0aec0;");
            } else {
                chatStatusHint.setText("⚠ DataChannel未连接，无法聊天");
                chatStatusHint.setStyle("-fx-text-fill: #fc8181; -fx-font-size: 11px;");
            }
        });
    }
    
    /**
     * 更新流配置状态
     */
    private void updateStreamConfigStatus(String config) {
        Platform.runLater(() -> {
            streamConfigStatusLabel.setText(config);
            streamConfigStatusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: green;");
        });
    }

    private Label createStatusLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        return label;
    }

    private Label createValueLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 14px;");
        return label;
    }

    /**
     * 启动连接
     */
    private void startConnection() {
        // 先停止现有连接
        stopConnection();
        
        // 保存配置
        saveConfig();
        
        // 更新 UI（在主线程）
        startBtn.setDisable(true);
        stopBtn.setDisable(false);
        updateConnectionStatus("连接中...");
        logManager.addLog("正在启动连接...");
        
        // 在后台线程执行连接操作
        new Thread(() -> {
            try {
                // 创建控制处理器
                controlHandler = new ControlHandler(logManager::addLog);
                
                // 解析配置
                URI uri = new URI(urlField.getText().trim());
                String[] stunUrls = stunField.getText().trim().split(",");
                String[] turnUrls = turnField.getText().trim().split(",");
                String turnUser = turnUserField.getText().trim();
                String turnPass = turnPassField.getText();
                
                // 创建 WebSocket 客户端
                client = new AgentClient(
                    uri, 
                    roomField.getText().trim(), 
                    nameField.getText().trim(), 
                    controlHandler, 
                    msg -> {
                        logManager.addLog(msg);
                        Platform.runLater(() -> updateWsStatus(msg));
                    }
                );
                
                // 创建 WebRTC 管理器
                webRTCManager = new WebRTCManager(
                    client, 
                    msg -> {
                        logManager.addLog(msg);
                        Platform.runLater(() -> {
                            updateDcStatus(msg);
                            // 检查流配置应用成功消息
                            if (msg.contains("流配置已应用") || msg.contains("码率已更新")) {
                                updateStreamConfigStatus(msg);
                            }
                        });
                    }, 
                    stunUrls, 
                    turnUrls, 
                    turnUser, 
                    turnPass, 
                    controlHandler
                );
                
                // 设置聊天消息监听器
                webRTCManager.setChatListener(new WebRTCManager.ChatListener() {
                    @Override
                    public void onChatMessage(String sender, String message) {
                        appendChat(sender, message);
                    }
                    
                    @Override
                    public void onDataChannelStateChange(boolean isOpen) {
                        updateChatEnabled(isOpen);
                    }
                });
                
                client.setWebRTCManager(webRTCManager);
                webRTCManager.init();
                
                // 连接（这是阻塞操作）
                client.connect();
                
                logManager.addLog("连接已启动");
                
            } catch (Exception ex) {
                logManager.addLog("启动失败: " + ex.getMessage());
                ex.printStackTrace();
                Platform.runLater(() -> {
                    showAlert(Alert.AlertType.ERROR, "启动失败", ex.getMessage());
                    startBtn.setDisable(false);
                    stopBtn.setDisable(true);
                    updateConnectionStatus("未连接");
                });
            }
        }, "Connection-Thread").start();
    }

    /**
     * 停止连接
     */
    private void stopConnection() {
        if (webRTCManager != null) {
            try {
                webRTCManager.cleanup();
                logManager.addLog("WebRTC 已清理");
            } catch (Exception ignored) {}
            webRTCManager = null;
        }
        
        if (client != null) {
            try {
                // 使用stopAndDisconnect禁用自动重连
                client.stopAndDisconnect();
                logManager.addLog("WebSocket 已关闭");
            } catch (Exception ignored) {}
            client = null;
        }
        
        // 更新 UI
        startBtn.setDisable(false);
        stopBtn.setDisable(true);
        updateWsStatus("已停止");
        updateDcStatus("未连接");
        updateConnectionStatus("已停止");
        updateChatEnabled(false);
    }

    /**
     * 保存配置
     */
    private void saveConfig() {
        configManager.setSignalUrl(urlField.getText().trim());
        configManager.setRoomId(roomField.getText().trim());
        configManager.setName(nameField.getText().trim());
        configManager.setStunUrls(stunField.getText().trim());
        configManager.setTurnUrls(turnField.getText().trim());
        configManager.setTurnUser(turnUserField.getText().trim());
        configManager.setTurnPassword(turnPassField.getText());
        configManager.save();
        
        logManager.addLog("配置已保存");
    }

    /**
     * 更新 WebSocket 状态
     */
    private void updateWsStatus(String status) {
        // 只更新真正的 WebSocket 连接状态，忽略其他消息
        if (status.contains("WebSocket") || status.contains("WS") || status.contains("已停止") 
                || status.contains("重连") || status.contains("断开")) {
            wsStatusLabel.setText(status);
            if (status.contains("已连接") || status.contains("connected")) {
                wsStatusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: green;");
            } else if (status.contains("失败") || status.contains("错误") || status.contains("closed")) {
                wsStatusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: red;");
            } else if (status.contains("重连") || status.contains("正在")) {
                // 重连中显示橙色
                wsStatusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: orange;");
            } else {
                wsStatusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: orange;");
            }
        }
        
        // 更新总体状态
        if (status.contains("重连")) {
            updateConnectionStatus("重连中...");
        } else if (status.contains("等待") && status.contains("控制端")) {
            updateConnectionStatus("等待控制端连接...");
        }
    }

    /**
     * 更新 DataChannel 状态
     */
    private void updateDcStatus(String status) {
        dcStatusLabel.setText(status);
        String statusLower = status.toLowerCase();
        if (statusLower.contains("open") || statusLower.contains("成功") || statusLower.contains("已连接")) {
            dcStatusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: green;");
        } else if (statusLower.contains("fail") || statusLower.contains("错误") || statusLower.contains("closed")) {
            dcStatusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: red;");
        } else {
            dcStatusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: orange;");
        }
        
        // 同时更新 ICE 状态（从 DC 状态推断）
        if (status.contains("ICE")) {
            updateIceStatus(status);
        }
    }

    /**
     * 更新 ICE 状态
     */
    private void updateIceStatus(String status) {
        iceStatusLabel.setText(status);
        String statusLower = status.toLowerCase();
        if (statusLower.contains("connected") || statusLower.contains("completed")) {
            iceStatusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: green;");
            updateConnectionStatus("已连接");
        } else if (statusLower.contains("failed") || statusLower.contains("失败")) {
            iceStatusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: red;");
            updateConnectionStatus("连接失败");
        } else {
            iceStatusLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: orange;");
        }
    }

    /**
     * 更新总体连接状态
     */
    private void updateConnectionStatus(String status) {
        connectionStatusLabel.setText(status);
        if (status.contains("已连接") || status.contains("成功")) {
            connectionStatusLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: green;");
        } else if (status.contains("失败") || status.contains("错误")) {
            connectionStatusLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: red;");
        } else {
            connectionStatusLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: orange;");
        }
    }

    /**
     * 显示提示框
     */
    private void showAlert(Alert.AlertType type, String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }

    @Override
    public void stop() {
        stopConnection();
        logManager.addLog("应用正在关闭");
    }
}
