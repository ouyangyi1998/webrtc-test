# WebRTC 远程桌面控制系统

基于 WebRTC 的跨平台远程桌面控制系统，支持实时屏幕共享、远程控制和双向聊天。

## ✨ 核心特性

### 🖥️ Web 控制端
- **零安装**：浏览器直接访问，无需安装客户端
- **实时控制**：支持鼠标（左键/右键/中键/滚轮/拖拽）和键盘（包括快捷键）
- **智能码率**：自动/流畅/高清/原画四档可选，自适应网络状况
- **实时聊天**：基于 DataChannel 的低延迟聊天
- **网络监控**：实时显示 FPS、码率、丢包率、延迟等指标

### 🖱️ Agent 被控端
- **跨平台**：支持 macOS (x86_64/ARM64) 和 Windows
- **现代化 UI**：JavaFX 界面，配置、日志、状态三标签页设计
- **配置持久化**：自动保存配置到本地
- **高性能**：使用 WebRTC 原生推流，低延迟高画质
- **智能重连**：WebSocket 和 WebRTC 双重断线重连机制

### 📱 Android 客户端
- **屏幕共享**：将 Android 屏幕实时共享到 Web 端
- **远程控制**：支持点击、拖动、滑动、长按、滚轮
- **键盘输入**：通过无障碍服务注入文本
- **快捷键**：Escape=返回, Meta+H=主页, Meta+Tab=最近应用
- **前台服务**：保持屏幕共享稳定运行

## 🚀 快速开始

### 1. 启动 Web 服务端

```bash
# 克隆项目
git clone https://github.com/your-username/webrtc-test.git
cd webrtc-test

# 启动服务（需要 JDK 1.8+）
mvn spring-boot:run

# 或使用打包后的 JAR
mvn package
java -jar target/webrtc-demo-0.0.1-SNAPSHOT.jar
```

服务默认运行在 `http://localhost:8080`

### 2. 运行 Agent 被控端

#### macOS

**下载对应架构的 JAR 包**：
- Intel Mac (x86_64): `agent-java/target/webrtc-agent-macos-x86_64.jar`
- Apple Silicon (M1/M2/M3): `agent-java/target/webrtc-agent-macos-arm64.jar`

**运行**（需要 Java 17+）：
```bash
# Intel Mac
java -jar webrtc-agent-macos-x86_64.jar

# Apple Silicon
java -jar webrtc-agent-macos-arm64.jar
```

**授予权限** (必需)：
1. **屏幕录制**：系统设置 → 隐私与安全性 → 屏幕录制 → 添加 Java
2. **辅助功能**：系统设置 → 隐私与安全性 → 辅助功能 → 添加 Java/Terminal

**Java 17 安装**（所有 macOS 平台必需）：
```bash
brew install openjdk@17
```

#### Windows

```bash
# 需要 Java 17+
java -jar webrtc-agent-windows.jar
```

需要管理员权限以执行输入操作。

### 3. 建立连接

1. **Web 端**：浏览器访问 `http://localhost:8080`，输入房间号和昵称
2. **Agent 端**：在配置标签页填写信令服务器 URL (`ws://localhost:8080/ws`) 和相同的房间号
3. **点击"启动连接"**，等待连接建立
4. **开始控制**：在 Web 端可以看到被控端屏幕并进行远程控制

## 🎯 功能详解

### 画质控制

| 模式 | 码率 | 适用场景 |
|------|------|----------|
| 自动 | 动态调整 | 网络状况不稳定时 |
| 流畅 | 500kbps | 弱网环境 |
| 高清 | 2Mbps | 正常网络 |
| 原画 | 4Mbps | 高速网络，需要清晰画质 |

### 帧率控制

支持 15/20/30 FPS 可选，默认 15 FPS

### 远程控制

**鼠标**：
- 左键单击/双击
- 右键菜单
- 中键点击
- 滚轮缩放
- 拖拽操作

**键盘**：
- 所有字母、数字、符号键
- 功能键 (F1-F12)
- 修饰键 (Ctrl/Alt/Shift/Command)
- 特殊键 (Enter/Backspace/Delete/Tab/Esc 等)
- 组合快捷键 (如 Cmd+C, Ctrl+V 等)

### 聊天功能

- 实时双向文字聊天
- 基于 DataChannel，低延迟
- DataChannel 断开时自动禁用

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

### Agent 编译

#### 为不同平台编译

```bash
cd agent-java

# macOS x86_64
mvn clean package -DskipTests -P mac-x86

# macOS ARM64
mvn clean package -DskipTests -P mac-arm

# Windows (需在 pom.xml 中配置 windows classifier)
mvn clean package -DskipTests -P windows
```

#### 一键生成所有平台

使用提供的脚本：
```bash
cd agent-java
./build-all.sh
```

生成的文件在 `agent-java/target/` 目录下。

## 📁 项目结构

```
webrtc-test/
├── src/                          # Web 服务端（Spring Boot）
│   ├── main/java/
│   │   └── com/example/webrtc/
│   │       ├── config/           # WebSocket 配置
│   │       ├── model/            # 信令消息模型
│   │       ├── web/              # Web 控制器
│   │       └── websocket/        # WebSocket 处理器
│   └── main/resources/
│       ├── static/               # 静态资源
│       │   └── js/room.js        # 前端控制逻辑
│       └── templates/room.html   # 控制端页面
├── agent-java/                   # Java Agent 被控端
│   ├── src/main/java/
│   │   └── com/example/agent/
│   │       ├── javafx/              # JavaFX UI
│   │       ├── AgentClient.java     # WebSocket 客户端
│   │       ├── WebRTCManager.java   # WebRTC 管理
│   │       ├── ScreenCaptureSource.java # 屏幕捕获
│   │       └── ControlHandler.java  # 输入控制
│   └── target/
│       ├── webrtc-agent-macos-x86_64.jar
│       └── webrtc-agent-macos-arm64.jar
├── android-remote-control/       # Android 远程控制客户端
│   ├── app/src/main/java/.../
│   │   ├── service/
│   │   │   ├── RemoteControlService.kt  # 无障碍服务
│   │   │   └── ScreenCaptureService.kt  # 屏幕共享
│   │   ├── webrtc/WebRTCManager.kt      # WebRTC 管理
│   │   └── manager/ConnectionManager.kt # 连接管理
│   └── README.md
└── README.md
```

## 🛠️ 技术栈

### Web 服务端
- **Spring Boot 2.2.2** - Web 框架
- **Spring WebSocket** - WebSocket 支持
- **Thymeleaf** - 模板引擎
- **Tailwind CSS** - UI 样式

### Agent 被控端
- **JavaFX 17** - 现代化 UI 框架
- **webrtc-java (dev.onvoid)** - WebRTC Java 绑定
- **Java-WebSocket** - WebSocket 客户端
- **Java Robot** - 系统输入控制
- **Maven Shade** - 打包插件

### 前端
- **WebRTC API** - P2P 连接和媒体流
- **Vanilla JavaScript** - 无框架依赖
- **Tailwind CSS** - 响应式设计

## ⚠️ 注意事项

### macOS 权限

Agent 首次运行时需要授予以下权限：

1. **屏幕录制**：允许捕获屏幕
   - 系统设置 → 隐私与安全性 → 屏幕录制
   - 添加 Java 或 Terminal

2. **辅助功能**：允许控制鼠标和键盘
   - 系统设置 → 隐私与安全性 → 辅助功能
   - 添加 Java 或 Terminal
   - **重启 Agent 使权限生效**

### 浏览器要求

- 必须使用 HTTPS 或 localhost
- 推荐使用 Chrome/Edge (WebRTC 支持最完善)

### 网络要求

- 同一局域网内可直接连接（无需 TURN）
- 跨网络需要配置 TURN 服务器

### 已知问题

- Agent 需要 Java 17+（因为使用 JavaFX 17）
- Windows 某些场景需要管理员权限

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📄 许可证

MIT License

## 🔗 相关链接

- [WebRTC 官方文档](https://webrtc.org/)
- [webrtc-java](https://github.com/devopvoid/webrtc-java)
- [JavaFX](https://openjfx.io/)

---

### 常见问题
- Web 控制端需通过 HTTPS 或 localhost 访问才能使用 WebRTC 功能（getUserMedia 和 RTCPeerConnection 的浏览器安全要求）。
- Java Agent 需要屏幕录制和输入控制权限（macOS："屏幕录制"+"辅助功能"；Windows：管理员权限）。
- TURN 未配置时，某些 NAT 环境下可能无法建立 P2P 连接。
- WebRTC 原生库需要匹配运行平台（macOS arm64、macOS x64、Windows、Linux 等），修改 agent-java/pom.xml 中的 `webrtc.java.classifier` 属性。
