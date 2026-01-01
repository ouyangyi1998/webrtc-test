## WebRTC 远程演示（Spring Boot + Thymeleaf）

功能定位（当前版本）：
- Web 端固定为"控制端"：加入房间、建立 DataChannel，在远端视频区域采集鼠标/键盘并发送指令；本页面不执行本地点击/滚轮/键盘。
- 信令：房间号匹配（两人房），WebSocket `/ws`。
- 媒体：Web 控制端不采集本地屏幕，只接收并显示被控端（Java Agent）的屏幕流。
- DataChannel：聊天、鼠标、键盘指令发送；支持 WebSocket 降级。
- 样式：Tailwind CDN，无需构建链路。
- UI 简化：移除角色切换选项，Web 端专注于控制功能。

### 快速开始
1. 安装 JDK 8+ 与 Maven。
2. 配置 STUN/TURN（可先用默认 STUN，弱网建议 TURN）：
   - `APP_STUN_URLS` 默认 `stun:stun.l.google.com:19302`
   - `APP_TURN_URLS` 如 `turn:your-turn-host:3478`
   - `APP_TURN_USERNAME` / `APP_TURN_PASSWORD`
3. 运行：
   ```bash
   mvn spring-boot:run
   ```
4. 打开浏览器访问 `http://localhost:8080`，控制端输入房间号、昵称后加入。被控端将由 Java Agent 负责加入同一房间。

### TURN 示例（coturn）
- 示例配置：`deploy/turn/turnserver.conf` 与 `deploy/turn/env.example`
- 启动示例（需自行安装 coturn 并替换主机名/证书/secret）：
  ```bash
  export STATIC_AUTH_SECRET=replace_with_random_secret
  turnserver -c deploy/turn/turnserver.conf --static-auth-secret=$STATIC_AUTH_SECRET
  ```
- 应用端环境变量：
  ```bash
  export APP_TURN_URLS="turn:your-turn-host:3478"
  export APP_TURN_USERNAME="turnuser"
  export APP_TURN_PASSWORD="turnpass"
  ```

### 指令协议（控制端发送 / Agent 接收）
- DataChannel 或 WebSocket 包裹：`{ type: "control", data: <payload> }`（前端已通过 WebSocket 复制发送 control，Agent 可通过 DataChannel 或 WebSocket 接收）  
- 鼠标：`{ kind: "mouse", action: "move"|"click"|"wheel", xRatio, yRatio, deltaY? }`
- 键盘：`{ kind: "keyboard", type: "keydown"|"keyup", key, code, altKey, ctrlKey, metaKey, shiftKey, repeat? }`
- 聊天：`{ kind: "chat", sender, text }`
- 坐标：控制端按远端视频的可见区域计算 0~1 比例；Agent 按屏幕分辨率换算真实坐标并执行。
- 点击执行：Agent 使用 Robot 执行 mouseMove -> mousePress -> mouseRelease。

### Java Agent 被控端（已实现 - JavaFX UI）
- 平台：macOS / Windows，Java 可执行 JAR（需本地 JRE 8+）。
- UI：JavaFX 现代化界面，标签页布局（配置、日志、状态监控三个独立标签页）。
- 配置持久化：自动保存和加载配置到 `~/.webrtc-agent/config.properties`。
- 日志管理：独立日志标签页，最多显示 500 条日志，支持导出到文件，配置信息始终可见。
- 职责：读取配置（信令 URL、房间号、昵称），加入 `/ws` 信令，与控制端建立 P2P 连接和 DataChannel，捕获本地屏幕并推流，接收控制指令并调用 OS 输入（Java Robot）。
- 运行：启动后在配置标签页填入与控制端一致的房间号，点击"启动连接"；停止时点击"停止连接"。
- 通信流程：  
  1) WebSocket `/ws` 加房间 -> 收到 offer -> 发送 answer -> 交换 ICE candidates -> 建立 P2P 连接和 DataChannel。  
  2) 屏幕捕获：使用 Java Robot 以 15 FPS 捕获屏幕，转换为 I420 格式，通过 WebRTC 视频轨道发送给控制端。  
  3) DataChannel 收到指令 JSON（协议同上），Agent 侧解析后：  
     - 鼠标：按屏幕尺寸换算真实坐标，使用 Robot 执行点击、移动、滚轮操作。  
     - 键盘：分发 keydown/keyup，使用 Robot 执行按键。  
     - 聊天：日志显示。  
  4) WebSocket 降级：当 DataChannel 未建立时，可通过 WebSocket 接收 `type: control` 指令。  
  5) 状态变更写入状态监控标签页（WS/DataChannel/ICE 连接状态实时显示）。
- 依赖：  
  - UI：JavaFX 17（现代化 UI 框架）。
  - WebRTC：dev.onvoid.webrtc（webrtc-java），支持 PeerConnection、DataChannel、视频推流。  
  - WebSocket：org.java-websocket 用于信令通信。  
  - 输入执行：Java Robot（跨平台基础）。  
- 稳定性优化：
  - 屏幕捕获添加重试机制（最多连续失败 10 次后停止）。
  - WebRTC 资源清理顺序优化（DataChannel -> ScreenCapture -> PeerConnection -> Factory）。
  - 所有异常都有详细日志记录。
  - 使用守护线程避免阻塞应用退出。
- 坐标与输入执行要点：  
  - 控制端发送 0~1 比例坐标（基于远端视频可见区域）。Agent 侧用 `x = ratioX * screenWidth`、`y = ratioY * screenHeight` 计算屏幕像素。  
  - 点击：mouseMove -> mousePress -> mouseRelease。  
  - 滚轮：`deltaY` 直接映射到 Robot.mouseWheel。  
  - 键盘：keyPress / keyRelease（支持的按键由 KeyEvent.getExtendedKeyCodeForChar 确定）。  
- UI 特性：  
  - 配置标签页：信令服务 URL、房间号、昵称、STUN URLs、TURN URLs、TURN 用户名/密码；启动/停止/保存配置按钮。  
  - 日志标签页：滚动日志列表（最多 500 条），清空显示和导出日志按钮。  
  - 状态监控标签页：WebSocket 状态、DataChannel 状态、ICE 连接状态、总体连接状态（颜色指示：绿色=成功，橙色=进行中，红色=失败）。
- 打包/运行：  
  - 构建：`mvn package` 生成可执行 JAR（含依赖，使用 maven-assembly-plugin），需本机 JRE 8+。  
  - 启动示例：`java -jar target/webrtc-agent-0.0.1-SNAPSHOT-jar-with-dependencies.jar`（在 UI 中填写配置）。  
  - 平台权限：macOS 需开启"辅助功能/输入监控/屏幕录制"；Windows 需允许发送输入。  
  - 平台依赖：需要对应平台的 WebRTC 原生库（macOS arm64：webrtc-java-macos-aarch64）。  

### Agent 本仓库实现（JavaFX 现代化 UI）
- 路径：`agent-java/`，可直接 `mvn package`（已配置 jar-with-dependencies）。  
- UI 技术：JavaFX 17（取代旧版 Swing），现代化界面设计。
- 当前实现：  
  - 标签页布局：配置标签页、日志标签页、状态监控标签页，配置信息始终可见。  
  - 配置持久化：自动保存到 `~/.webrtc-agent/config.properties`，下次启动自动加载。  
  - 日志管理：独立标签页显示最多 500 条日志，超过自动清理旧日志，支持导出到文件。  
  - WebSocket：加入房间后接收信令（offer/answer/candidate），支持降级接收 `type: control` 的指令（mouse/keyboard/chat）。  
  - WebRTC：使用 dev.onvoid.webrtc，完整实现 PeerConnection、ICE 协商、视频轨道推流、DataChannel 通信。  
  - 屏幕捕获：使用 Java Robot 以 15 FPS 捕获屏幕，转换为 I420 格式并通过视频轨道推送给控制端，添加重试机制。  
  - DataChannel：接收并执行控制指令（mouse/keyboard/chat），优先使用 DataChannel，未建立时降级到 WebSocket。  
  - 输入执行：使用 Java Robot 执行鼠标和键盘操作，坐标按屏幕分辨率换算。  
  - 稳定性增强：异常处理完善，资源清理优化，减少 macOS 闪退问题。
- 运行示例：  
  ```bash
  cd agent-java
  mvn package
  java -jar target/webrtc-agent-0.0.1-SNAPSHOT-jar-with-dependencies.jar
  ```  
  在 UI 填写 `ws://<控制端主机>:8080/ws` 与房间号，与控制端一致后点击"启动连接"。  
- 注意事项：  
  - 需要对应平台的 WebRTC 原生库（pom.xml 中默认配置为 macOS aarch64，其他平台需修改 `webrtc.java.classifier`）。  
  - macOS 需授予"屏幕录制"和"辅助功能"权限。  
  - Windows 可能需要管理员权限执行输入操作。  
  - JavaFX 需要 JRE 8+ 支持，推荐使用 JDK 11+ 以获得更好的 JavaFX 集成。

### 常见问题
- Web 控制端需通过 HTTPS 或 localhost 访问才能使用 WebRTC 功能（getUserMedia 和 RTCPeerConnection 的浏览器安全要求）。
- Java Agent 需要屏幕录制和输入控制权限（macOS："屏幕录制"+"辅助功能"；Windows：管理员权限）。
- TURN 未配置时，某些 NAT 环境下可能无法建立 P2P 连接。
- WebRTC 原生库需要匹配运行平台（macOS arm64、macOS x64、Windows、Linux 等），修改 agent-java/pom.xml 中的 `webrtc.java.classifier` 属性。
