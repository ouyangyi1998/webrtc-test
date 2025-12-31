## WebRTC 远程演示（Spring Boot + Thymeleaf）

功能点：
- 房间号匹配（两人房），WebSocket 信令。
- WebRTC 媒体流（屏幕共享为默认，可切换摄像头）。
- DataChannel 文本聊天、鼠标坐标/点击传递（演示型控制）。
- Tailwind 通过 CDN，无需构建链路。

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
4. 打开浏览器访问 `http://localhost:8080`，两个标签页输入相同 roomId 体验。

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

### 远程鼠标说明
- 鼠标事件通过 DataChannel 传输相对坐标，对端显示光标高亮和点击效果；不执行系统级操作，仅用于演示。

### 路由与端口
- 信令 WebSocket：`/ws`
- 页面：`/` 或 `/room`

### 常见问题
- 浏览器需 HTTPS/localhost 才能使用屏幕共享。
- TURN 未配置时，某些 NAT 环境下可能无法互联。
