# Android 远程控制客户端

基于 WebRTC 的 Android 远程桌面控制应用，作为控制端接入现有的 WebRTC 远程控制系统。

## 功能特性

- 📱 **移动控制端**：在 Android 设备上远程控制桌面
- 🎥 **实时视频流**：通过 WebRTC 接收远程屏幕画面
- 🖱️ **触摸控制**：
  - 单指点击 = 鼠标左键
  - 单指长按 = 鼠标右键
  - 单指滑动 = 鼠标移动
  - 双指滑动 = 鼠标滚轮
  - 双击 = 双击
- 🔄 **自动重连**：网络断开后自动重连

## 环境要求

- Android 7.0 (API 24) 及以上
- Java 17 或更高版本（用于编译）
- Android Studio Hedgehog 或更高版本

## 快速开始

### 1. 使用 Android Studio 打开项目

```bash
cd android-remote-control
# 用 Android Studio 打开此目录
```

### 2. 构建 APK

```bash
./gradlew assembleDebug
```

APK 文件位于 `app/build/outputs/apk/debug/app-debug.apk`

### 3. 运行

1. 确保信令服务器已启动（`http://your-server:8080`）
2. 确保 Agent 被控端已运行
3. 在 App 中输入：
   - 服务器地址：`ws://your-server:8080/ws`
   - 房间号：与 Agent 相同的房间号
   - 昵称：任意
4. 点击"连接"

## 项目结构

```
app/src/main/java/com/example/remotecontrol/
├── signaling/
│   ├── SignalingClient.kt    # WebSocket 信令客户端
│   └── SignalMessage.kt      # 信令消息模型
├── webrtc/
│   └── WebRTCManager.kt      # WebRTC PeerConnection 管理
├── control/
│   ├── RemoteControlManager.kt # 核心控制器
│   └── ControlPayload.kt      # 控制消息载荷
└── ui/
    ├── connect/
    │   └── ConnectActivity.kt # 连接配置界面
    └── remote/
        ├── RemoteActivity.kt  # 远程控制界面
        └── TouchOverlayView.kt # 触摸手势处理
```

## 依赖

- WebRTC: `org.webrtc:google-webrtc:1.0.32006`
- OkHttp: `com.squareup.okhttp3:okhttp:4.12.0`
- Gson: `com.google.code.gson:gson:2.10.1`
- Kotlin Coroutines

## 与服务端对接

本 App 基于 [ANDROID_INTEGRATION.md](../ANDROID_INTEGRATION.md) 实现，与现有的 WebRTC 远程控制系统完全兼容。

## License

MIT
