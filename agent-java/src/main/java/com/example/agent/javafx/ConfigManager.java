package com.example.agent.javafx;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 配置管理器 - 负责保存和加载用户配置
 */
public class ConfigManager {
    private static final String CONFIG_DIR = ".webrtc-agent";
    private static final String CONFIG_FILE = "config.properties";
    private final Properties properties;
    private final Path configPath;

    // 配置键
    public static final String KEY_SIGNAL_URL = "signal.url";
    public static final String KEY_ROOM_ID = "room.id";
    public static final String KEY_NAME = "name";
    public static final String KEY_STUN_URLS = "stun.urls";
    public static final String KEY_TURN_URLS = "turn.urls";
    public static final String KEY_TURN_USER = "turn.user";
    public static final String KEY_TURN_PASSWORD = "turn.password";

    public ConfigManager() {
        this.properties = new Properties();
        
        // 获取用户主目录下的配置文件路径
        String userHome = System.getProperty("user.home");
        Path configDir = Paths.get(userHome, CONFIG_DIR);
        this.configPath = configDir.resolve(CONFIG_FILE);
        
        // 确保配置目录存在
        try {
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
        } catch (IOException e) {
            System.err.println("无法创建配置目录: " + e.getMessage());
        }
    }

    /**
     * 加载配置
     */
    public void load() {
        if (Files.exists(configPath)) {
            try (InputStream input = new FileInputStream(configPath.toFile())) {
                properties.load(input);
                System.out.println("配置已加载: " + configPath);
            } catch (IOException e) {
                System.err.println("加载配置失败: " + e.getMessage());
            }
        } else {
            System.out.println("配置文件不存在，将使用默认值");
            setDefaults();
        }
    }

    /**
     * 保存配置
     */
    public void save() {
        try (OutputStream output = new FileOutputStream(configPath.toFile())) {
            properties.store(output, "WebRTC Agent Configuration");
            System.out.println("配置已保存: " + configPath);
        } catch (IOException e) {
            System.err.println("保存配置失败: " + e.getMessage());
        }
    }

    /**
     * 设置默认值
     */
    private void setDefaults() {
        properties.setProperty(KEY_SIGNAL_URL, "ws://localhost:8080/ws");
        properties.setProperty(KEY_ROOM_ID, "demo-room");
        properties.setProperty(KEY_NAME, "agent");
        properties.setProperty(KEY_STUN_URLS, "stun:43.139.50.108:3478");
        properties.setProperty(KEY_TURN_URLS, "turn:43.139.50.108:3478?transport=udp,turn:43.139.50.108:3478?transport=tcp");
        properties.setProperty(KEY_TURN_USER, "admin");
        properties.setProperty(KEY_TURN_PASSWORD, "123456");
    }

    /**
     * 获取配置项
     */
    public String get(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    /**
     * 设置配置项
     */
    public void set(String key, String value) {
        properties.setProperty(key, value);
    }

    /**
     * 获取信令 URL
     */
    public String getSignalUrl() {
        return get(KEY_SIGNAL_URL, "ws://localhost:8080/ws");
    }

    /**
     * 设置信令 URL
     */
    public void setSignalUrl(String url) {
        set(KEY_SIGNAL_URL, url);
    }

    /**
     * 获取房间 ID
     */
    public String getRoomId() {
        return get(KEY_ROOM_ID, "demo-room");
    }

    /**
     * 设置房间 ID
     */
    public void setRoomId(String roomId) {
        set(KEY_ROOM_ID, roomId);
    }

    /**
     * 获取昵称
     */
    public String getName() {
        return get(KEY_NAME, "agent");
    }

    /**
     * 设置昵称
     */
    public void setName(String name) {
        set(KEY_NAME, name);
    }

    /**
     * 获取 STUN URLs
     */
    public String getStunUrls() {
        return get(KEY_STUN_URLS, "stun:43.139.50.108:3478");
    }

    /**
     * 设置 STUN URLs
     */
    public void setStunUrls(String urls) {
        set(KEY_STUN_URLS, urls);
    }

    /**
     * 获取 TURN URLs
     */
    public String getTurnUrls() {
        return get(KEY_TURN_URLS, "turn:43.139.50.108:3478?transport=udp,turn:43.139.50.108:3478?transport=tcp");
    }

    /**
     * 设置 TURN URLs
     */
    public void setTurnUrls(String urls) {
        set(KEY_TURN_URLS, urls);
    }

    /**
     * 获取 TURN 用户名
     */
    public String getTurnUser() {
        return get(KEY_TURN_USER, "admin");
    }

    /**
     * 设置 TURN 用户名
     */
    public void setTurnUser(String user) {
        set(KEY_TURN_USER, user);
    }

    /**
     * 获取 TURN 密码
     */
    public String getTurnPassword() {
        return get(KEY_TURN_PASSWORD, "123456");
    }

    /**
     * 设置 TURN 密码
     */
    public void setTurnPassword(String password) {
        set(KEY_TURN_PASSWORD, password);
    }
}
