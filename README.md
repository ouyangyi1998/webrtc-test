## WebRTC 远程演示（Spring Boot + Thymeleaf）

功能定位（当前版本）：
- Web 端只做“控制端”：加入房间、建立 DataChannel，在远端视频区域采集鼠标/键盘并发送指令；本页面不执行本地点击/滚轮/键盘。
- 信令：房间号匹配（两人房），WebSocket `/ws`。
- 媒体：可观看对端流（用于瞄准），不再采集本地屏幕。
- DataChannel：聊天、鼠标、键盘指令发送。
- 样式：Tailwind CDN，无需构建链路。

### 快速开始
1. 安装 JDK 17+ 与 Maven。
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
- DataChannel 或（临时）WebSocket 包裹：`{ type: "control", data: <payload> }`（前端已通过 WebSocket 复制发送 control，Agent 可直接收 WS）  
- 鼠标：`{ kind: "mouse", action: "move"|"click"|"wheel", xRatio, yRatio, deltaY? }`
- 键盘：`{ kind: "keyboard", type: "keydown"|"keyup", key, code, altKey, ctrlKey, metaKey, shiftKey, repeat? }`
- 聊天：`{ kind: "chat", sender, text }`
- 坐标：控制端按远端视频的可见区域计算 0~1 比例；Agent 按屏幕分辨率换算真实坐标并执行。
- 事件序列（点击）：PointerDown -> MouseDown -> PointerUp -> MouseUp -> click（由 Agent 侧完成）。

### Java 被控端（待实现）
- 平台：macOS / Windows，Java 可执行 JAR（需本地 JRE）。
- 职责：读取配置（信令 URL、房间号、昵称），加入 `/ws` 信令，与控制端建立 DataChannel，接收指令并调用 OS 输入（mac: Robot/CGEvent；Windows: Robot/JNA SendInput）。
- UI：简单窗口，含配置输入、状态显示、Start/Stop。
- 运行：启动后填入与控制端一致的房间号，点击 Start 加入；停止时关闭连接。
- 通信流程（建议）：  
  1) WebSocket `/ws` 加房间 -> 收到 offer/answer/candidate -> 建立 DataChannel。  
  2) DataChannel 收到指令 JSON（协议同上），Agent 侧解析后：  
     - 鼠标：按屏幕尺寸换算真实坐标，Pointer/Mouse 序列执行点击，滚轮滚动目标窗口。  
     - 键盘：分发 keydown/keyup，必要时直接修改聚焦控件的文本（输入框）。  
     - 聊天：控制台/窗口日志显示。  
  3) 状态变更写入 UI（已连接/断开）。
- 依赖示例（可选）：  
  - WebRTC/DataChannel：libdatachannel Java 绑定或其他轻量实现；若前期简化，可用 WebSocket 直接收发指令，跳过 P2P。  
  - 输入执行：Java Robot（跨平台基础）；macOS 可用 CGEvent/JNA 提升兼容；Windows 可用 JNA 调用 SendInput 提升可靠性。  
- 坐标与输入执行要点：  
  - 控制端发送 0~1 比例坐标（基于远端视频可见区域）。Agent 侧用 `x = ratioX * screenWidth`、`y = ratioY * screenHeight` 计算屏幕像素。  
  - 点击序列：PointerDown -> MouseDown -> PointerUp -> MouseUp -> click。  
  - 滚轮：`deltaY` 直接映射到垂直滚动（如 Robot.mouseWheel / CGEventScroll / SendInput wheel）。  
  - 键盘：先分发 keydown/keyup；对输入框可在必要时直接改 value 并触发 input 事件（视安全策略而定）。  
- UI/配置（建议）：  
  - 字段：信令服务 URL、房间号、昵称；启动/停止按钮；状态栏（WS 连接、P2P 状态、DataChannel 状态）。  
  - 日志区域：收到的指令简要、错误提示。  
  - 保存/加载：可选简单 properties 文件保存上次配置。  
- 打包/运行建议：  
  - 构建：`mvn package` 生成可执行 JAR（含依赖可用 shade/assembly），需本机 JRE。  
  - 启动示例：`java -jar agent.jar --signalUrl=ws://host:8080/ws --roomId=demo --name=agent1`（也可在 UI 中填写）。  
  - 平台权限：macOS 需开启“辅助功能/输入监控”；Windows 需允许发送输入。  

### Agent 本仓库原型（WS 收指令，预留 DC）
- 路径：`agent-java/`，可直接 `mvn package`（已配置 jar-with-dependencies）。  
- 当前实现：  
  - Swing UI：填写 `ws://host:8080/ws`、房间号、昵称；Start/Stop、日志；可填写 STUN/TURN 信息（尚未用于 DC）。  
  - WebSocket：加入房间后接收 `type: control` 的指令（mouse/keyboard/chat），直接执行本地输入（Java Robot），坐标按屏幕分辨率换算。  
  - 数据通道：未集成（libdatachannel/org.webrtc 未接入），UI 显示 DC: not implemented；STUN/TURN 配置预留，后续切换为 DC 可复用。  
- 运行示例：  
  ```bash
  cd agent-java
  mvn package
  java -jar target/webrtc-agent-0.0.1-SNAPSHOT-jar-with-dependencies.jar
  ```  
  在 UI 填写 `ws://<控制端主机>:8080/ws` 与房间号，与控制端一致后 Start。  

### 常见问题
- 浏览器需 HTTPS/localhost 才能使用屏幕共享。
- TURN 未配置时，某些 NAT 环境下可能无法互联。
