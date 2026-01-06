# WebRTC 远程桌面控制系统

基于 WebRTC 的跨平台远程桌面控制系统，支持实时屏幕共享、远程控制和双向聊天。

## 🏗️ 系统架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              信令服务器 (Spring Boot)                         │
│                              ws://host:8080/ws                               │
└─────────────────────────────────────────────────────────────────────────────┘
                    ▲                                     ▲
          WebSocket │                                     │ WebSocket
           (信令)   │                                     │  (信令)
                    ▼                                     ▼
┌─────────────────────────────┐            ┌─────────────────────────────┐
│      Web 控制端 (浏览器)      │◄─────────►│      被控端 (Agent/Android)   │
│   • 查看远程屏幕              │   WebRTC   │   • 屏幕捕获推流              │
│   • 鼠标/键盘控制            │   (P2P)    │   • 接收控制指令              │
│   • 实时聊天                 │◄─────────►│   • 执行输入操作              │
└─────────────────────────────┘            └─────────────────────────────┘
```

**信令流程**:
1. 被控端连接 WebSocket，发送 `register` 注册到房间
2. Web 端进入房间，发送 `offer` 发起 WebRTC 连接
3. 被控端收到 `offer`，回复 `answer`
4. 双方交换 ICE Candidate 完成 P2P 连接
5. 被控端推送屏幕流，Web 端通过 DataChannel 发送控制指令

## ✨ 核心特性

### 🖥️ Web 控制端
- **零安装**：浏览器直接访问，无需客户端
- **实时控制**：鼠标（左/右/中键、滚轮、拖拽）+ 键盘（含快捷键）
- **智能码率**：自动/流畅/高清/原画四档可选
- **网络监控**：实时 FPS、码率、丢包率、延迟指标
- **自动播放**：视频流自动播放，无需手动点击

### 🖱️ Agent 被控端 (macOS/Windows)
- **跨平台**：支持 macOS (x86_64/ARM64) 和 Windows
- **现代化 UI**：JavaFX 界面，三标签页设计
- **高性能**：WebRTC 原生推流，低延迟高画质

### 📱 Android 客户端
- **屏幕共享**：实时推送 Android 屏幕
- **手势映射**：点击、滑动、长按、双击、返回、Home
- **键盘注入**：通过无障碍服务输入文本
- **网络稳定**：WiFi/4G 切换自动恢复，断线重连

### 🌐 网络稳定性
- **延迟发送 peer-left**：服务器端3秒缓冲，避免短暂断连误判
- **防抖处理**：网络变化事件防抖，避免频繁触发重连
- **ICE 重启**：自动 ICE 重启恢复 P2P 连接
- **双重保障**：服务器端 + 客户端双重延迟机制

## 🚀 快速开始

### 1. 启动信令服务器

```bash
git clone https://github.com/your-username/webrtc-test.git
cd webrtc-test

# 需要 JDK 1.8+
mvn spring-boot:run
# 或
mvn package && java -jar target/webrtc-demo-0.0.1-SNAPSHOT.jar
```

服务运行在 `http://localhost:8080`

### 2. 运行 Agent 被控端

详见 [agent-java/README.md](agent-java/README.md)

**快速运行**（需要 Java 17+）：
```bash
# macOS Intel
java -jar agent-java/target/webrtc-agent-macos-x86_64.jar

# macOS Apple Silicon
java -jar agent-java/target/webrtc-agent-macos-arm64.jar
```

> ⚠️ macOS 需授予「屏幕录制」和「辅助功能」权限

### 3. 运行 Android 客户端

详见 [android-remote-control/README.md](android-remote-control/README.md)

```bash
cd android-remote-control
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

> ⚠️ 需启用「无障碍服务」和「悬浮窗」权限

### 4. 建立连接

1. **Web 端**：访问 `http://localhost:8080`，输入房间号
2. **被控端**：填写信令 URL `ws://localhost:8080/ws` 和相同房间号
3. **启动连接**，等待 WebRTC 建立完成

## 🎯 远程控制说明

### 鼠标操作

| 操作 | Web 端 | Agent/Android 执行 |
|------|--------|-------------------|
| 左键单击 | 点击 | 点击 |
| 左键双击 | 双击 | 双击 |
| 右键 | 右键 | 右键菜单 / 长按 (Android) |
| 中键 | 中键 | 中键 |
| 滚轮 | 滚动 | 滚动 |
| 拖拽 | 按住拖动 | 拖拽 / 滑动 (Android) |

### 键盘操作

- 所有字母、数字、符号键
- 功能键 F1-F12
- 修饰键 Ctrl/Alt/Shift/Command
- 特殊键 Enter/Backspace/Delete/Tab/Esc
- 组合快捷键 Cmd+C, Ctrl+V 等

### Android 快捷键

| 快捷键 | 操作 |
|--------|------|
| Escape | 返回 |
| Meta+H / Home | 主页 |
| Meta+Tab | 最近应用 |

### 画质/帧率

| 画质 | 码率 | 场景 |
|------|------|------|
| 自动 | 动态 | 网络不稳定 |
| 流畅 | 500kbps | 弱网 |
| 高清 | 2Mbps | 正常网络 |
| 原画 | 4Mbps | 高速网络 |

帧率支持 15/20/30 FPS

## 📁 项目结构

```
webrtc-test/
├── src/                          # 信令服务器 (Spring Boot)
│   └── main/
│       ├── java/.../webrtc/
│       │   ├── config/           # WebSocket 配置
│       │   ├── model/            # 信令消息模型
│       │   ├── web/              # HTTP 控制器
│       │   └── websocket/        # WebSocket 处理
│       └── resources/
│           ├── static/js/        # 前端 JS
│           └── templates/        # Thymeleaf 页面
├── agent-java/                   # Java Agent 被控端
│   ├── src/main/java/.../agent/
│   │   ├── javafx/               # JavaFX UI
│   │   ├── AgentClient.java      # WebSocket 客户端
│   │   ├── WebRTCManager.java    # WebRTC 管理
│   │   ├── ScreenCaptureSource.java
│   │   └── ControlHandler.java   # 输入控制
│   ├── build-all.sh              # 双架构打包脚本
│   └── pom.xml
├── android-remote-control/       # Android 客户端
│   └── app/src/main/.../
│       ├── service/              # 无障碍 + 屏幕共享服务
│       ├── webrtc/               # WebRTC 管理
│       ├── manager/              # 连接/配置管理
│       └── ui/                   # 界面
├── pom.xml                       # 根 Maven 配置
└── README.md
```

## 🛠️ 技术栈

| 组件 | 技术 |
|------|------|
| **信令服务器** | Spring Boot 2.2.2, Spring WebSocket, Thymeleaf |
| **Web 前端** | Vanilla JS, WebRTC API, Tailwind CSS |
| **Agent (桌面)** | JavaFX 17, webrtc-java 0.14.0, Java Robot |
| **Android** | Kotlin, google-webrtc 1.0.32006, OkHttp |

## 🔧 高级配置

### STUN/TURN 服务器

编辑 `src/main/resources/application.yml`：

```yaml
app:
  stun-urls: stun:stun.l.google.com:19302
  turn-urls: turn:your-turn-server:3478
  turn-username: username
  turn-password: password
```

或使用环境变量：
```bash
export APP_TURN_URLS="turn:your-turn-host:3478"
export APP_TURN_USERNAME="turnuser"
export APP_TURN_PASSWORD="turnpass"
```

## ❓ 常见问题

### 1. Web 端无法使用 WebRTC
**原因**：浏览器安全策略要求 HTTPS 或 localhost  
**解决**：使用 `localhost` 访问，或配置 HTTPS

### 2. macOS Agent 无法捕获屏幕
**原因**：未授予权限  
**解决**：系统设置 → 隐私与安全性 → 屏幕录制 → 添加 Java

### 3. macOS Agent 无法控制输入
**原因**：未授予辅助功能权限  
**解决**：系统设置 → 隐私与安全性 → 辅助功能 → 添加 Java，然后重启 Agent

### 4. 跨网络无法连接
**原因**：NAT 穿透失败，需要 TURN 中转  
**解决**：配置 TURN 服务器（推荐 coturn）

### 5. Android 无法输入文本
**原因**：无障碍服务未启用  
**解决**：设置 → 无障碍 → 远程控制服务 → 启用

## 📄 许可证

MIT License

## 🔗 相关链接

- [WebRTC 官方文档](https://webrtc.org/)
- [webrtc-java](https://github.com/devopvoid/webrtc-java)
- [JavaFX](https://openjfx.io/)
- [coturn (TURN Server)](https://github.com/coturn/coturn)
