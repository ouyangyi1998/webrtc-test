# Android 远程控制客户端

基于 WebRTC 的 Android 远程桌面控制应用，支持屏幕共享和远程控制。

## 功能特性

### 📡 屏幕共享
- 通过 WebRTC 实时共享 Android 屏幕到 Web 端
- 支持前台服务保持屏幕共享稳定

### 🖱️ 鼠标控制
- **点击** - Web 端鼠标点击 → Android 点击
- **拖动滑动** - Web 端拖动 → Android 滑动手势
- **右键** - Web 端右键 → Android 长按
- **双击** - Web 端双击 → Android 双击
- **滚轮** - Web 端滚轮 → Android 滑动

### ⌨️ 键盘控制
- **文本输入** - 通过 AccessibilityService 注入文本
- **退格删除** - Backspace 删除字符
- **回车** - Enter 确认

### ⚡ 快捷键
| 快捷键 | Android 操作 |
|--------|-------------|
| Escape | 返回 (Back) |
| Meta+H / Home | 主页 |
| Meta+Tab | 最近应用 |

### 🔄 连接
- 支持任意连接顺序（Android 先连或 Web 先连）
- 自动重连

## 环境要求

- Android 7.0 (API 24) 及以上
- Java 17 或更高版本（用于编译）
- Gradle 8.5+

## 快速开始

### 1. 构建 APK

```bash
cd android-remote-control
./gradlew assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`

### 2. 配置权限

安装后需要启用以下权限：

1. **无障碍服务**: 设置 → 无障碍 → 远程控制服务 → 启用
2. **悬浮窗权限**: 设置 → 应用 → 特殊权限 → 显示在其他应用上层

### 3. 使用

1. 启动信令服务器
2. 在 App 中配置服务器地址和房间号
3. 点击"启动连接"，授权屏幕录制
4. 在 Web 端打开相同房间即可查看和控制

## 项目结构

```
app/src/main/java/com/example/remotecontrol/
├── service/
│   ├── RemoteControlService.kt   # 无障碍服务（手势/键盘注入）
│   └── ScreenCaptureService.kt   # 屏幕共享前台服务
├── webrtc/
│   └── WebRTCManager.kt          # WebRTC PeerConnection 管理
├── manager/
│   ├── ConnectionManager.kt      # 连接状态管理
│   └── ConfigManager.kt          # 配置管理
└── ui/
    └── main/
        └── fragment/
            └── ConfigFragment.kt # 配置界面
```

## 依赖

- WebRTC: `org.webrtc:google-webrtc:1.0.32006`
- OkHttp: `com.squareup.okhttp3:okhttp:4.12.0`
- Gson: `com.google.code.gson:gson:2.10.1`

## License

MIT
