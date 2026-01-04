# Android 被控端 (Agent) 极简开发手册

本文档专为 **Android 入门开发者** 设计，指导你如何开发一个 **被控端 (Agent)** 应用，使你的 Android 设备可以被远程控制。

如果你想开发的是 **控制端** (即通过手机控制其他设备)，请参考 [Android 接入 WebRTC 远程控制手册](./ANDROID_INTEGRATION.md)。

---

## 🚀 核心概念 (一分钟读懂)

作为一个 **被控端**，你的 App 主要做两件事：
1. **推流**：把手机屏幕画面录下来，通过 WebRTC 发送给对方。（就像“屏幕共享”）。
2. **执行**：接收对方发来的鼠标/触摸指令，模拟点击屏幕。（就像“按键精灵”）。

---

## 🛠️ 第一步：准备工作

在 `app/build.gradle` 中添加必须的库：

```gradle
dependencies {
    // Google WebRTC 官方库
    implementation 'org.webrtc:google-webrtc:1.0.32006'

    // WebSocket (用于信令交互)
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'

    // JSON 解析
    implementation 'com.google.code.gson:gson:2.10.1'
}
```

在 `AndroidManifest.xml` 中添加权限：

```xml
<!-- 网络权限 -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- 录屏服务权限 (Android 10+ 必须) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" /> <!-- Android 14+ -->

<!-- 辅助功能 (用于模拟点击) -->
<uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE" />
```

---

## 📱 第二步：实现屏幕捕获 (推流)

Android 5.0+ 提供了 `MediaProjection` API 来录屏。

### 1. 申请录屏权限

在你的 Activity 中：

```kotlin
private val mediaProjectionManager by lazy {
    getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
}

// 启动录屏请求
fun startScreenCapture() {
    startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_CODE_CAPTURE)
}

override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode == REQUEST_CODE_CAPTURE && resultCode == Activity.RESULT_OK) {
        // 用户同意录屏，data 是关键凭证
        startWebRTC(data!!)
    }
}
```

### 2. 创建 WebRTC VideoSource

拿到 `data` (Intent) 后，我们告诉 WebRTC 使用屏幕作为视频源。

```kotlin
fun createScreenCapturer(intent: Intent): VideoCapturer {
    return ScreenCapturerAndroid(intent, object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            // 录屏停止
        }
    })
}
```

**关键点**：`ScreenCapturerAndroid` 是 WebRTC 库自带的，它会把屏幕内容转换成 WebRTC 可用的 VideoTrack。

---

## 📡 第三步：WebRTC 连接 (简版)

流程和控制端几乎一样，区别在于 **我们是视频发送方**。

### 1. 初始化 PeerConnection

```kotlin
// 初始化工厂
PeerConnectionFactory.initialize(...)
val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()

// 创建视频轨道 (Track)
val videoCapturer = createScreenCapturer(permissionIntent)
val videoSource = factory.createVideoSource(videoCapturer.isScreencast)
val videoTrack = factory.createVideoTrack("ScreenVideoTrack", videoSource)

// 启动捕获 (分辨率: 720p, 帧率: 30)
videoCapturer.startCapture(720, 1280, 30)

// 创建连接对象
val peerConnection = factory.createPeerConnection(rtcConfig, observer)

// 🔥 重点：把视频轨道加进去！
peerConnection.addTrack(videoTrack)
```

### 2. 信令交换 (SDP)

这一步和 [Android 接入手册](./ANDROID_INTEGRATION.md) 中的信令流程完全一致：
1.  **WebSocket 连接** 服务器。
2.  **Join 房间**。
3.  **发送 Offer** (如果你是后加入的) 或 **接收 Offer** (如果你先在)。
4.  **交换 ICE Candidate**。

> **初学者提示**：既然你是“被控端”，通常是你先在线等待，控制端后加入。所以大概率你会收到 `peer-joined` 消息，然后你创建一个 `Offer` 发给对方。

---

## 👆 第四步：实现远程控制 (模拟点击)

要让对方能控制你的手机，必须使用 **AccessibilityService (无障碍服务)**。这是 Android 官方允许模拟全局触摸的唯一免 Root 方案。

### 1. 创建服务类

新建一个类 `ControlService.kt`：

```kotlin
class ControlService : AccessibilityService() {

    companion object {
        var instance: ControlService? = null
    }

    override fun onServiceConnected() {
        instance = this
        Log.d("Control", "无障碍服务已连接！")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // 执行点击
    fun performClick(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)

        // 创建点击手势 (按下持续 100ms)
        val builder = GestureDescription.Builder()
        builder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))

        dispatchGesture(builder.build(), null, null)
    }
}
```

### 2. 注册服务 (Manifest)

在 `AndroidManifest.xml` 中注册：

```xml
<service
    android:name=".ControlService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_config" />
</service>
```

创建 `res/xml/accessibility_config.xml`：

```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/accessibility_desc"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="100"
    android:canPerformGestures="true" /> <!-- 关键：允许执行手势 -->
```

### 3. 处理控制指令

在 WebRTC 的 `DataChannel` 收到消息后，调用服务执行点击：

```kotlin
// 假设收到了 JSON: { "type": "mouse", "action": "click", "xRatio": 0.5, "yRatio": 0.5 }
val x = screenWidth * xRatio
val y = screenHeight * yRatio

// 调用无障碍服务点击
ControlService.instance?.performClick(x, y)
```

---

## 💡 常见问题 (FAQ)

### Q1: 必须 Root 吗？
**不需要**。使用 Android 5.0+ 的 MediaProjection (录屏) 和 AccessibilityService (辅助功能) 即可实现免 Root 控制。

### Q2: 为什么我看不到画面？
检查两点：
1. `videoCapturer.startCapture` 是否调用成功？
2. `peerConnection.addTrack(videoTrack)` 是否执行？
3. `ScreenCapturerAndroid` 需要在前台服务中运行（Android 10+ 限制）。

### Q3: 为什么无法点击？
1. 确保用户在设置里**手动开启**了你的 App 的无障碍权限。
2. 确保 `dispatchGesture` 的坐标是屏幕绝对坐标 (WebRTC 发来的是 0.0-1.0 的比例，需乘以 `DisplayMetrics.widthPixels`)。

---

## 🔗 参考代码

- **信令部分**：完全复用 [Android 接入手册](./ANDROID_INTEGRATION.md) 中的 `RemoteControlManager` 代码。
- **差异点**：
  - 不需要 `SurfaceViewRenderer` 显示对方画面（除非你想看对方摄像头）。
  - 不需要发送鼠标事件，而是**接收**并执行。

祝你开发顺利！
