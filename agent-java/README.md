# WebRTC Agent (Java Desktop)

基于 WebRTC 的桌面端被控程序，支持 macOS (x86_64/ARM64) 和 Windows。

## ✨ 特性

- **跨平台**：macOS (Intel/Apple Silicon) 和 Windows
- **现代化 UI**：JavaFX 界面，配置/日志/状态三标签页
- **高性能推流**：基于 webrtc-java 原生库，低延迟高画质
- **智能重连**：WebSocket + WebRTC 双重断线重连
- **配置持久化**：自动保存到本地
- **网络稳定**：ICE 重启机制，自动恢复 P2P 连接

## 📋 系统要求

- **Java 17+**（因使用 JavaFX 17）
- **macOS 权限**：
  - 屏幕录制（必需）
  - 辅助功能（必需，用于输入控制）
- **Windows**：管理员权限（用于输入注入）

## 🚀 快速开始

### 下载预编译 JAR

根据架构选择对应版本：

| 平台 | 文件名 |
|------|--------|
| macOS Intel | `webrtc-agent-macos-x86_64.jar` |
| macOS Apple Silicon | `webrtc-agent-macos-arm64.jar` |
| Windows | `webrtc-agent-windows.jar` |

### 运行

```bash
# 安装 Java 17 (macOS)
brew install openjdk@17

# macOS Intel
java -jar webrtc-agent-macos-x86_64.jar

# macOS Apple Silicon
java -jar webrtc-agent-macos-arm64.jar

# Windows (管理员权限运行)
java -jar webrtc-agent-windows.jar
```

### macOS 权限设置

首次运行后需授予权限：

1. **屏幕录制**：系统设置 → 隐私与安全性 → 屏幕录制 → 添加 Java/Terminal
2. **辅助功能**：系统设置 → 隐私与安全性 → 辅助功能 → 添加 Java/Terminal

> ⚠️ 修改权限后需**重启 Agent** 生效

## 🔧 从源码构建

### 单架构构建

```bash
cd agent-java

# macOS x86_64 (Intel)
mvn clean package -DskipTests -P mac-x86

# macOS ARM64 (Apple Silicon)
mvn clean package -DskipTests -P mac-arm
```

输出文件：`target/webrtc-agent-0.0.1-SNAPSHOT.jar`

### 双架构一键构建

```bash
./build-all.sh
```

输出文件：
- `target/webrtc-agent-macos-x86_64.jar`
- `target/webrtc-agent-macos-arm64.jar`

## 📁 项目结构

```
agent-java/
├── src/main/java/com/example/agent/
│   ├── javafx/
│   │   ├── AgentAppFX.java       # JavaFX 应用入口
│   │   ├── AgentController.java  # UI 控制器
│   │   └── agent_view.fxml       # FXML 布局
│   ├── Launcher.java             # 主入口 (解决模块化问题)
│   ├── AgentClient.java          # WebSocket 信令客户端
│   ├── WebRTCManager.java        # WebRTC 连接管理
│   ├── ScreenCaptureSource.java  # 屏幕捕获
│   └── ControlHandler.java       # 鼠标/键盘控制
├── build-all.sh                  # 双架构打包脚本
└── pom.xml
```

## 🛠️ 技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| JavaFX | 17.0.9 | 现代化 UI 框架 |
| webrtc-java | 0.14.0 | WebRTC 原生绑定 |
| Java-WebSocket | 1.5.5 | WebSocket 客户端 |
| Jackson | 2.17.1 | JSON 序列化 |
| Maven Shade | 3.5.1 | Fat JAR 打包 |

## 🔄 工作流程

```
┌─────────────────┐
│  启动 Agent     │
└────────┬────────┘
         ▼
┌─────────────────┐
│ 连接 WebSocket  │ ──► 信令服务器
└────────┬────────┘
         ▼
┌─────────────────┐
│ 注册到房间      │ ──► register 消息
└────────┬────────┘
         ▼
┌─────────────────┐
│ 等待 Offer      │ ◄── Web 端发起
└────────┬────────┘
         ▼
┌─────────────────┐
│ 创建 Answer     │ ──► 回复 Web 端
└────────┬────────┘
         ▼
┌─────────────────┐
│ 建立 P2P 连接   │ ◄─► ICE Candidates
└────────┬────────┘
         ▼
┌─────────────────────────────────┐
│ 推流 + 接收控制                  │
│ • MediaTrack: 屏幕视频流         │
│ • DataChannel: 控制指令 (JSON)   │
└─────────────────────────────────┘
```

## ❓ 常见问题

### 1. 启动时提示 "Graphics Device initialization failed"
**原因**：JavaFX 原生库架构不匹配  
**解决**：确认 JAR 与 CPU 架构对应（Intel 用 x86_64，M1/M2/M3 用 arm64）

### 2. 屏幕捕获黑屏
**原因**：未授予屏幕录制权限  
**解决**：系统设置 → 隐私与安全性 → 屏幕录制 → 添加 Java

### 3. 鼠标/键盘控制无效
**原因**：未授予辅助功能权限  
**解决**：系统设置 → 隐私与安全性 → 辅助功能 → 添加 Java，重启 Agent

### 4. 无法连接信令服务器
**解决**：
- 检查服务器地址格式：`ws://host:port/ws`
- 确认服务器已启动
- 检查防火墙设置

## 📄 许可证

MIT License
