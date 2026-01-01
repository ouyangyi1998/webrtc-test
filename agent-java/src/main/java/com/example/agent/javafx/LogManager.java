package com.example.agent.javafx;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 日志管理器 - 负责管理和显示日志条目
 */
public class LogManager {
    private static final int MAX_LOG_ENTRIES = 500; // 最大日志条数
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    
    private final ObservableList<String> logEntries;
    private final List<String> allLogs; // 保存所有日志用于导出

    public LogManager() {
        this.logEntries = FXCollections.observableArrayList();
        this.allLogs = new ArrayList<>();
    }

    /**
     * 添加日志条目（带时间戳）
     */
    public void addLog(String message) {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        String logEntry = String.format("[%s] %s", timestamp, message);
        
        // 保存到完整日志
        allLogs.add(logEntry);
        
        // 在 JavaFX 线程中更新 UI
        Platform.runLater(() -> {
            logEntries.add(logEntry);
            
            // 如果超过最大条数，删除最早的日志
            if (logEntries.size() > MAX_LOG_ENTRIES) {
                logEntries.remove(0);
            }
        });
        
        // 同时输出到控制台
        System.out.println(logEntry);
    }

    /**
     * 清空显示的日志（不清空 allLogs，用于导出）
     */
    public void clearDisplayLogs() {
        Platform.runLater(() -> logEntries.clear());
    }

    /**
     * 清空所有日志（包括导出缓存）
     */
    public void clearAllLogs() {
        Platform.runLater(() -> logEntries.clear());
        allLogs.clear();
    }

    /**
     * 获取日志条目列表（用于绑定到 UI）
     */
    public ObservableList<String> getLogEntries() {
        return logEntries;
    }

    /**
     * 导出日志到文件
     */
    public boolean exportLogs(String filename) {
        try {
            // 如果没有指定文件名，使用默认文件名
            if (filename == null || filename.isEmpty()) {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                filename = String.format("webrtc-agent-log_%s.txt", timestamp);
            }
            
            // 确保日志目录存在
            String userHome = System.getProperty("user.home");
            Path logDir = Paths.get(userHome, ".webrtc-agent", "logs");
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
            }
            
            Path logFile = logDir.resolve(filename);
            
            // 写入所有日志
            try (FileWriter writer = new FileWriter(logFile.toFile())) {
                writer.write("WebRTC Agent Log Export\n");
                writer.write("=======================\n");
                writer.write("Export Time: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\n\n");
                
                for (String log : allLogs) {
                    writer.write(log + "\n");
                }
            }
            
            System.out.println("日志已导出到: " + logFile);
            return true;
        } catch (IOException e) {
            System.err.println("导出日志失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取日志总数
     */
    public int getTotalLogCount() {
        return allLogs.size();
    }

    /**
     * 获取显示的日志数
     */
    public int getDisplayLogCount() {
        return logEntries.size();
    }
}
