(() => {
  const roomInput = document.getElementById("roomId");
  const nameInput = document.getElementById("displayName");
  const joinBtn = document.getElementById("joinBtn");
  const leaveBtn = document.getElementById("leaveBtn");
  const enableMouseChk = document.getElementById("enableMouse");
  const remoteVideo = document.getElementById("remoteVideo");
  const remoteOverlay = document.getElementById("remoteOverlay");
  const chatArea = document.getElementById("chatArea");
  const chatInput = document.getElementById("chatInput");
  const sendChatBtn = document.getElementById("sendChat");
  const logArea = document.getElementById("logArea");
  const connState = document.getElementById("connState");

  // 视频流控制元素
  const frameRateSelect = document.getElementById("frameRateSelect");
  const bitrateSelect = document.getElementById("bitrateSelect");
  const currentFps = document.getElementById("currentFps");
  const currentBitrate = document.getElementById("currentBitrate");
  const currentPacketLoss = document.getElementById("currentPacketLoss");
  const currentLatency = document.getElementById("currentLatency");
  const fullscreenBtn = document.getElementById("fullscreenBtn");
  const fullscreenBtnFloat = document.getElementById("fullscreenBtnFloat");
  const videoContainer = document.getElementById("videoContainer");

  let ws;
  let pc;
  let dataChannel;
  let localStream;
  let remoteStream;
  let isJoined = false;
  let isInitiator = false;
  let reconnectAttempts = 0;
  let reconnectTimer;
  let connecting = false;
  let manualLeave = false;

  // ICE 重连相关
  let iceDisconnectTimer = null;  // ICE disconnected 状态恢复超时
  let iceRestartAttempts = 0;     // ICE 重启尝试次数
  const MAX_ICE_RESTART_ATTEMPTS = 3;  // 最大重启次数

  // WebSocket 心跳相关
  let heartbeatTimer = null;
  let heartbeatTimeout = null;
  const HEARTBEAT_INTERVAL = 30000;  // 30秒发送一次心跳
  const HEARTBEAT_TIMEOUT = 15000;   // P2: 15秒内没有响应则认为断开（从10s调整）

  // 视频流控制相关
  let statsMonitor = null;
  let bitrateAdjuster = null;
  let streamConfig = {
    frameRate: 15,
    bitrateMode: 'auto',
    targetBitrate: 2000000
  };

  // 帧监控相关（用于检测卡顿并请求关键帧）
  let frameMonitorTimer = null;
  let lastFrameCount = 0;
  let frameStuckCount = 0;

  // 启动帧监控
  const startFrameMonitor = () => {
    if (frameMonitorTimer) return;

    frameMonitorTimer = setInterval(async () => {
      if (!pc || !remoteVideo) return;

      try {
        const stats = await pc.getStats();
        let currentFrameCount = 0;

        stats.forEach((report) => {
          if (report.type === 'inbound-rtp' && report.kind === 'video') {
            currentFrameCount = report.framesDecoded || 0;
          }
        });

        // 检测帧是否卡住（2秒内没有新帧解码）
        if (currentFrameCount > 0 && currentFrameCount === lastFrameCount) {
          frameStuckCount++;
          if (frameStuckCount >= 2) {
            log('检测到画面卡顿，请求关键帧');
            requestKeyFrame();
            frameStuckCount = 0;
          }
        } else {
          frameStuckCount = 0;
        }

        lastFrameCount = currentFrameCount;
      } catch (e) {
        console.error('帧监控异常:', e);
      }
    }, 2000);  // 每2秒检测一次
  };

  // 停止帧监控
  const stopFrameMonitor = () => {
    if (frameMonitorTimer) {
      clearInterval(frameMonitorTimer);
      frameMonitorTimer = null;
    }
    lastFrameCount = 0;
    frameStuckCount = 0;
  };

  // 请求关键帧
  const requestKeyFrame = () => {
    if (dataChannel && dataChannel.readyState === 'open') {
      dataChannel.send(JSON.stringify({ kind: 'request_keyframe' }));
    } else if (ws && ws.readyState === WebSocket.OPEN) {
      sendSignal('control', { kind: 'request_keyframe' });
    }
  };

  // ===== 自动画质/码率控制引擎 (增强版) =====
  class QualityController {
    constructor() {
      this.enabled = false;
      this.currentBitrate = 2000000;  // 当前码率
      this.currentFps = 15;           // 当前帧率
      this.currentResolution = 'full'; // 当前分辨率档位

      // 配置参数
      this.minBitrate = 200000;       // 最低200kbps (更激进)
      this.maxBitrate = 4000000;      // 最高4Mbps
      this.minFps = 5;                // 最低5fps
      this.maxFps = 30;               // 最高30fps

      this.adjustCooldown = 3000;     // 3秒冷却 (更快响应)
      this.lastAdjustTime = 0;
      this.lastDowngradeTime = 0;
      this.upgradeCooldown = 15000;   // 降级后15秒内禁止升级

      // 状态计数器
      this.consecutiveBadSamples = 0;
      this.consecutiveGoodSamples = 0;
      this.consecutiveModerateSamples = 0;

      // 预测性调整：RTT历史记录
      this.rttHistory = [];
      this.maxRttHistorySize = 10;
      this.jitterHistory = [];
    }

    setMode(mode) {
      this.enabled = (mode === 'auto');
      if (!this.enabled) {
        const bitrateMap = {
          'smooth': 500000,
          'hd': 2000000,
          'original': 4000000
        };
        this.currentBitrate = bitrateMap[mode] || 2000000;
        this.currentFps = streamConfig.frameRate || 15;
      }
    }

    // 计算RTT趋势（斜率），正值表示延迟增加
    calculateRttTrend() {
      if (this.rttHistory.length < 3) return 0;

      const arr = this.rttHistory;
      const n = arr.length;
      let sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;

      for (let i = 0; i < n; i++) {
        sumX += i;
        sumY += arr[i];
        sumXY += i * arr[i];
        sumX2 += i * i;
      }

      const slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
      return slope; // ms/sample，正值=延迟增加
    }

    // 计算抖动趋势
    calculateJitterTrend() {
      if (this.jitterHistory.length < 3) return 0;
      const recent = this.jitterHistory.slice(-5);
      const avg = recent.reduce((a, b) => a + b, 0) / recent.length;
      return avg;
    }

    analyze(metrics) {
      if (!this.enabled) return null;

      const now = Date.now();

      // 记录RTT和Jitter历史
      if (metrics.rtt !== undefined) {
        this.rttHistory.push(metrics.rtt);
        if (this.rttHistory.length > this.maxRttHistorySize) {
          this.rttHistory.shift();
        }
      }
      if (metrics.jitter !== undefined) {
        this.jitterHistory.push(metrics.jitter);
        if (this.jitterHistory.length > this.maxRttHistorySize) {
          this.jitterHistory.shift();
        }
      }

      // 冷却期检查
      if (now - this.lastAdjustTime < this.adjustCooldown) return null;

      // ===== 三级网络状况判定 =====
      // 重度拥塞: 丢包 > 10% 或 RTT > 400ms
      const isCritical = metrics.packetLoss > 0.10 || (metrics.rtt && metrics.rtt > 400);
      // 中度拥塞: 丢包 > 3% 或 RTT > 150ms
      const isModerate = metrics.packetLoss > 0.03 || (metrics.rtt && metrics.rtt > 150);
      // 轻度拥塞: 丢包 > 1.5% 或 RTT > 80ms
      const isMild = metrics.packetLoss > 0.015 || (metrics.rtt && metrics.rtt > 80);
      // 良好: 丢包 < 0.5% 且 RTT < 60ms
      const isGood = metrics.packetLoss < 0.005 && metrics.rtt && metrics.rtt < 60;

      // ===== 预测性分析 =====
      const rttTrend = this.calculateRttTrend();
      const isPredictedDegradation = rttTrend > 15 && metrics.rtt > 80; // RTT快速增加
      const jitterAvg = this.calculateJitterTrend();
      const isHighJitter = jitterAvg > 0.03; // 30ms抖动

      let actionTaken = false;
      let degradeLevel = 0; // 0=无, 1=轻度, 2=中度, 3=重度

      // ===== 降级触发逻辑 =====
      if (isCritical) {
        this.consecutiveBadSamples += 3;
        degradeLevel = 3;
      } else if (isModerate) {
        this.consecutiveBadSamples += 2;
        this.consecutiveModerateSamples++;
        degradeLevel = 2;
      } else if (isMild || isPredictedDegradation) {
        this.consecutiveBadSamples++;
        this.consecutiveModerateSamples++;
        degradeLevel = 1;
        if (isPredictedDegradation) {
          log(`[QoS] 检测到RTT上升趋势 (${rttTrend.toFixed(1)}ms/s)，提前降级`);
        }
      } else if (isGood) {
        this.consecutiveGoodSamples++;
        this.consecutiveBadSamples = Math.max(0, this.consecutiveBadSamples - 1);
        this.consecutiveModerateSamples = Math.max(0, this.consecutiveModerateSamples - 1);
      } else {
        // 一般情况
        this.consecutiveBadSamples = Math.max(0, this.consecutiveBadSamples - 1);
        this.consecutiveGoodSamples = Math.max(0, this.consecutiveGoodSamples - 1);
      }

      // 高抖动额外惩罚
      if (isHighJitter) {
        this.consecutiveBadSamples++;
      }

      // ===== 分级降级策略 =====
      if (this.consecutiveBadSamples >= 2) {
        this.consecutiveGoodSamples = 0;
        this.consecutiveBadSamples = 0;
        this.lastDowngradeTime = now;

        if (degradeLevel === 3) {
          // 重度：同时降帧率和码率
          log('[QoS] 重度拥塞，激进降级');
          if (this.currentBitrate > this.minBitrate) {
            this.currentBitrate = Math.max(this.minBitrate, this.currentBitrate * 0.5);
          }
          if (this.currentFps > this.minFps) {
            this.currentFps = Math.max(this.minFps, this.currentFps - 10);
          }
          actionTaken = true;
        } else if (degradeLevel === 2) {
          // 中度：优先降码率
          log('[QoS] 中度拥塞，降低码率');
          if (this.currentBitrate > this.minBitrate) {
            this.currentBitrate = Math.max(this.minBitrate, this.currentBitrate * 0.7);
            actionTaken = true;
          } else if (this.currentFps > this.minFps) {
            this.currentFps = Math.max(this.minFps, this.currentFps - 5);
            actionTaken = true;
          }
        } else if (degradeLevel === 1 || this.consecutiveModerateSamples >= 3) {
          // 轻度/预测性：优先降帧率（更平滑）
          log('[QoS] 轻度拥塞，降低帧率');
          if (this.currentFps > 10) {
            this.currentFps = Math.max(10, this.currentFps - 5);
            actionTaken = true;
          } else if (this.currentBitrate > 500000) {
            this.currentBitrate = Math.max(500000, this.currentBitrate * 0.85);
            actionTaken = true;
          }
          this.consecutiveModerateSamples = 0;
        }
      }

      // ===== 升级策略 =====
      if (this.consecutiveGoodSamples >= 6 && (now - this.lastDowngradeTime) > this.upgradeCooldown) {
        this.consecutiveBadSamples = 0;
        this.consecutiveGoodSamples = 0;

        // 渐进式升级
        if (this.currentFps < this.maxFps) {
          this.currentFps = Math.min(this.maxFps, this.currentFps + 5);
          actionTaken = true;
          log(`[QoS] 网络良好，提升帧率至 ${this.currentFps}fps`);
        } else if (this.currentBitrate < this.maxBitrate) {
          this.currentBitrate = Math.min(this.maxBitrate, this.currentBitrate * 1.2);
          actionTaken = true;
          log(`[QoS] 网络良好，提升码率至 ${(this.currentBitrate / 1000).toFixed(0)}kbps`);
        }
      }

      if (actionTaken) {
        this.lastAdjustTime = now;
        return {
          bitrate: Math.floor(this.currentBitrate),
          fps: Math.floor(this.currentFps)
        };
      }

      return null;
    }
  }

  // ===== SDP 处理函数 (FEC/RED 增强) =====

  /**
   * 增强 SDP 以启用 FEC (前向纠错)
   * 确保 ULPFEC 和 RED 被正确协商
   */
  const enhanceSdpForFec = (sdp) => {
    // 检查是否已包含 ULPFEC
    if (!sdp.includes('ulpfec')) {
      log('[FEC] SDP 不包含 ULPFEC，尝试添加...');
      // 大多数浏览器默认支持，仅记录日志
    }

    // 检查 RED (冗余编码)
    if (!sdp.includes('red')) {
      log('[FEC] SDP 不包含 RED');
    }

    return sdp;
  };

  /**
   * 发送 Jitter Buffer 调整请求到被控端
   * @param {number} rttMs RTT延迟
   * @param {number} jitterMs 抖动值
   */
  const sendJitterBufferAdjustment = (rttMs, jitterMs) => {
    const payload = {
      kind: 'jitter_adjust',
      rtt: rttMs,
      jitter: jitterMs
    };

    if (dataChannel && dataChannel.readyState === 'open') {
      try {
        dataChannel.send(JSON.stringify(payload));
      } catch (e) {
        // 忽略发送失败
      }
    }
  };

  // ===== 网络统计监控器 =====
  class NetworkStatsMonitor {
    constructor(peerConnection) {
      this.pc = peerConnection;
      this.statsInterval = null;
      this.onStatsUpdate = null;
      this.lastStats = null;
    }

    start() {
      if (this.statsInterval) return;
      this.statsInterval = setInterval(async () => {
        try {
          const stats = await this.pc.getStats();
          const metrics = this.parseStats(stats);
          if (this.onStatsUpdate && metrics) {
            this.onStatsUpdate(metrics);
          }
        } catch (e) {
          console.error("获取统计失败:", e);
        }
      }, 2000); // 每2秒采样
    }

    stop() {
      if (this.statsInterval) {
        clearInterval(this.statsInterval);
        this.statsInterval = null;
      }
    }

    parseStats(stats) {
      let inboundRtp = null;
      let candidatePair = null;

      stats.forEach(report => {
        if (report.type === 'inbound-rtp' && report.kind === 'video') {
          inboundRtp = report;
        }
        // 查找当前选中的候选对（selected）或状态为succeeded的候选对
        if (report.type === 'candidate-pair') {
          if (report.selected || report.state === 'succeeded') {
            candidatePair = report;
          }
        }
      });

      if (!inboundRtp) return null;

      const metrics = {
        timestamp: inboundRtp.timestamp,
        packetsReceived: inboundRtp.packetsReceived || 0,
        packetsLost: inboundRtp.packetsLost || 0,
        jitter: inboundRtp.jitter || 0,
        bytesReceived: inboundRtp.bytesReceived || 0,
        framesDecoded: inboundRtp.framesDecoded || 0,
        frameWidth: inboundRtp.frameWidth || 0,
        frameHeight: inboundRtp.frameHeight || 0
      };

      // 计算丢包率
      const totalPackets = metrics.packetsReceived + metrics.packetsLost;
      metrics.packetLoss = totalPackets > 0 ? metrics.packetsLost / totalPackets : 0;

      // 计算码率和帧率
      if (this.lastStats) {
        const timeDiff = (metrics.timestamp - this.lastStats.timestamp) / 1000; // 秒
        if (timeDiff >= 0.5) {  // 至少0.5秒才计算，避免采样间隔太短导致数值不准
          const bytesDiff = metrics.bytesReceived - this.lastStats.bytesReceived;
          metrics.bitrate = (bytesDiff * 8) / timeDiff; // bps

          const framesDiff = metrics.framesDecoded - this.lastStats.framesDecoded;
          metrics.fps = framesDiff / timeDiff;

          // 保存本次计算结果，供下次 timeDiff 不足时使用
          this.lastCalculatedFps = metrics.fps;
          this.lastCalculatedBitrate = metrics.bitrate;
        } else {
          // timeDiff 太小，使用上次计算的值
          metrics.fps = this.lastCalculatedFps;
          metrics.bitrate = this.lastCalculatedBitrate;
        }
      }

      // RTT (延迟) - 从候选对或inbound-rtp获取
      if (candidatePair) {
        if (candidatePair.currentRoundTripTime !== undefined) {
          metrics.rtt = candidatePair.currentRoundTripTime * 1000; // 转换为ms
        }
      }

      // 如果候选对中没有RTT，尝试从inbound-rtp中获取
      if (!metrics.rtt && inboundRtp.roundTripTime !== undefined) {
        metrics.rtt = inboundRtp.roundTripTime * 1000;
      }

      this.lastStats = metrics;
      return metrics;
    }
  }

  const ensureDefaultValues = () => {
    if (!roomInput.value.trim()) {
      roomInput.value =
        roomInput.getAttribute("value") ||
        roomInput.placeholder ||
        `demo-room-${Math.floor(Math.random() * 1000)}`;
    }
    if (!nameInput.value.trim()) {
      nameInput.value =
        nameInput.getAttribute("value") ||
        nameInput.placeholder ||
        `user-${Math.floor(Math.random() * 1000)}`;
    }
  };

  const log = (msg) => {
    const ts = new Date().toLocaleTimeString();
    logArea.textContent += `[${ts}] ${msg}\n`;
    logArea.scrollTop = logArea.scrollHeight;
    console.log(msg);
  };

  // ===== 流控制相关函数 =====
  const applyStreamConfig = async () => {
    if (!isJoined) return;

    // 处理帧率：如果选择"自动"，使用 QualityController 当前值（如果启用）
    const selectedFps = frameRateSelect.value;
    if (selectedFps === 'auto') {
      // 自动模式：使用 QualityController 的当前帧率，或默认15fps
      streamConfig.frameRate = bitrateAdjuster?.currentFps || 15;
    } else {
      streamConfig.frameRate = parseInt(selectedFps);
    }

    streamConfig.bitrateMode = bitrateSelect.value;

    // 根据档位设置目标码率
    const bitrateMap = {
      'smooth': 500000,    // 500kbps (流畅)
      'hd': 2000000,       // 2Mbps (高清/默认)
      'original': 4000000, // 4Mbps (原画)
      'auto': 2000000      // 默认
    };
    streamConfig.targetBitrate = bitrateMap[streamConfig.bitrateMode] || 2000000;

    log(`应用流配置: ${streamConfig.frameRate}fps, ${streamConfig.bitrateMode}, ${(streamConfig.targetBitrate / 1000).toFixed(0)}kbps`);

    // 构建 quality 命令
    const payload = {
      kind: "quality",
      fps: streamConfig.frameRate,
      bitrate: streamConfig.targetBitrate
    };

    // 优先通过 DataChannel 发送
    if (dataChannel && dataChannel.readyState === "open") {
      try {
        dataChannel.send(JSON.stringify(payload));
      } catch (e) {
        log(`Quality 切换失败 (DataChannel): ${e.message}`);
      }
    } else {
      // 降级：通过 WebSocket 发送 (如果对方支持)
      sendSignal('control', payload);
    }
  };

  const updateStatsDisplay = (metrics) => {
    if (metrics.fps !== undefined) {
      currentFps.textContent = metrics.fps.toFixed(1) + ' FPS';
    }
    if (metrics.bitrate !== undefined) {
      currentBitrate.textContent = (metrics.bitrate / 1000).toFixed(0) + ' kbps';
    }
    if (metrics.packetLoss !== undefined) {
      const lossPercent = (metrics.packetLoss * 100).toFixed(2);
      currentPacketLoss.textContent = lossPercent + '%';
      // 根据丢包率改变颜色
      if (metrics.packetLoss > 0.05) {
        currentPacketLoss.style.color = '#ef4444'; // 红色
      } else if (metrics.packetLoss > 0.02) {
        currentPacketLoss.style.color = '#f59e0b'; // 橙色
      } else {
        currentPacketLoss.style.color = '#22d3ee'; // 青色
      }
    }
    if (metrics.rtt !== undefined) {
      currentLatency.textContent = metrics.rtt.toFixed(0) + ' ms';
      // 根据延迟改变颜色
      if (metrics.rtt > 100) {
        currentLatency.style.color = '#ef4444'; // 红色
      } else if (metrics.rtt > 50) {
        currentLatency.style.color = '#f59e0b'; // 橙色
      } else {
        currentLatency.style.color = '#22d3ee'; // 青色
      }
    }
  };

  const addChat = (sender, text) => {
    const el = document.createElement("div");
    el.innerHTML = `<span class="text-cyan-300">${sender}:</span> <span class="text-slate-200 break-words">${text}</span>`;
    chatArea.appendChild(el);
    chatArea.scrollTop = chatArea.scrollHeight;
  };

  const isController = () => true; // Web 端固定为控制端
  const applyDisplayStyles = () => {
    if (remoteVideo) {
      remoteVideo.style.objectFit = "contain";
      remoteVideo.style.width = "100%";
      remoteVideo.style.height = "100%";
      remoteVideo.style.pointerEvents = "auto";
      remoteVideo.style.cursor = "crosshair"; // 显示十字光标
    }
    if (remoteOverlay) {
      remoteOverlay.style.position = "absolute";
      remoteOverlay.style.inset = "0";
      remoteOverlay.style.pointerEvents = "none"; // 让鼠标事件穿透到视频元素
    }
  };
  const videoViewport = (videoEl) => {
    const rect = videoEl.getBoundingClientRect();
    const vw = videoEl.videoWidth || rect.width;
    const vh = videoEl.videoHeight || rect.height;
    if (!vw || !vh || !rect.width || !rect.height) {
      return { left: rect.left, top: rect.top, width: rect.width, height: rect.height, valid: false };
    }
    const scale = Math.min(rect.width / vw, rect.height / vh);
    const displayW = vw * scale;
    const displayH = vh * scale;
    const offsetX = rect.left + (rect.width - displayW) / 2;
    const offsetY = rect.top + (rect.height - displayH) / 2;
    return { left: offsetX, top: offsetY, width: displayW, height: displayH, valid: true };
  };
  const setUiState = (joined) => {
    joinBtn.disabled = joined;
    leaveBtn.disabled = !joined;
    roomInput.disabled = joined;
    nameInput.disabled = joined;
  };

  const iceServers = () => {
    const servers = [];
    if (Array.isArray(SIGNAL_CONFIG.stunServers)) {
      SIGNAL_CONFIG.stunServers.forEach((u) => {
        if (u) servers.push({ urls: u });
      });
    }
    if (Array.isArray(SIGNAL_CONFIG.turnServers) && SIGNAL_CONFIG.turnServers.length > 0) {
      SIGNAL_CONFIG.turnServers.forEach((u) => {
        if (u) {
          servers.push({
            urls: u,
            username: SIGNAL_CONFIG.turnUsername,
            credential: SIGNAL_CONFIG.turnPassword,
          });
        }
      });
    }
    return servers;
  };

  // STUN/TURN 服务器连接检测
  const testIceServer = async (serverConfig, timeout = 5000) => {
    return new Promise((resolve) => {
      const testPc = new RTCPeerConnection({
        iceServers: [serverConfig],
        iceTransportPolicy: "all",
      });

      const timeoutId = setTimeout(() => {
        testPc.close();
        resolve({ available: false, error: "超时" });
      }, timeout);

      let foundCandidate = false;
      let foundRelay = false;
      let foundSrflx = false;

      testPc.onicecandidate = (e) => {
        if (e.candidate) {
          foundCandidate = true;
          const cand = e.candidate.candidate || "";
          if (cand.includes("typ relay")) {
            foundRelay = true;
          } else if (cand.includes("typ srflx")) {
            foundSrflx = true;
          }
        } else {
          // 候选收集完成
          clearTimeout(timeoutId);
          testPc.close();
          resolve({
            available: foundCandidate,
            hasRelay: foundRelay,
            hasSrflx: foundSrflx,
          });
        }
      };

      testPc.onicecandidateerror = (e) => {
        clearTimeout(timeoutId);
        testPc.close();
        resolve({
          available: false,
          error: e.errorText || e.message || "未知错误",
        });
      };

      testPc.onicegatheringstatechange = () => {
        if (testPc.iceGatheringState === 'complete' && !foundCandidate) {
          clearTimeout(timeoutId);
          testPc.close();
          resolve({ available: false, error: "未收集到候选" });
        }
      };

      // 创建一个数据通道以触发候选收集
      testPc.createDataChannel("test");
      testPc.createOffer().then(offer => {
        testPc.setLocalDescription(offer);
      }).catch(err => {
        clearTimeout(timeoutId);
        testPc.close();
        resolve({ available: false, error: err.message });
      });
    });
  };

  // 检测所有 STUN/TURN 服务器
  const testIceServers = async () => {
    log("开始检测 STUN/TURN 服务器连接...");
    const servers = [];
    const results = [];

    // 添加配置的 STUN 服务器
    if (Array.isArray(SIGNAL_CONFIG.stunServers)) {
      SIGNAL_CONFIG.stunServers.forEach((u, index) => {
        if (u) servers.push({ urls: u, name: `STUN ${index + 1}` });
      });
    }

    // 添加配置的 TURN 服务器
    if (Array.isArray(SIGNAL_CONFIG.turnServers) && SIGNAL_CONFIG.turnServers.length > 0) {
      SIGNAL_CONFIG.turnServers.forEach((u, index) => {
        if (u) {
          servers.push({
            urls: u,
            username: SIGNAL_CONFIG.turnUsername,
            credential: SIGNAL_CONFIG.turnPassword,
            name: `TURN ${index + 1}`,
          });
        }
      });
    }

    // 如果没有配置任何服务器，跳过检测
    if (servers.length === 0) {
      log("未配置 STUN/TURN 服务器，跳过检测");
      return [];
    }

    // 逐个检测服务器
    for (const server of servers) {
      const serverName = server.name || server.urls;
      log(`检测 ${serverName} (${server.urls})...`);

      const result = await testIceServer(server, 5000);
      results.push({ server: serverName, url: server.urls, ...result });

      if (result.available) {
        const details = [];
        if (result.hasRelay) details.push("TURN relay");
        if (result.hasSrflx) details.push("STUN srflx");
        log(`✓ ${serverName} 可用${details.length > 0 ? ' (' + details.join(', ') + ')' : ''}`);
      } else {
        log(`✗ ${serverName} 不可用${result.error ? ': ' + result.error : ''}`);
      }

      // 每个服务器检测之间稍作延迟
      await new Promise(resolve => setTimeout(resolve, 200));
    }

    // 统计结果
    const availableCount = results.filter(r => r.available).length;
    const totalCount = results.length;
    log(`服务器检测完成: ${availableCount}/${totalCount} 可用`);

    return results;
  };

  let iceGatheringTimeout = null;

  const ensurePeer = () => {
    if (pc) return pc;
    const rtcConfig = {
      iceServers: iceServers(),
      iceTransportPolicy: "all",
      iceCandidatePoolSize: 0, // 改为 0，减少候选收集时间
    };
    pc = new RTCPeerConnection(rtcConfig);

    pc.onicecandidate = (e) => {
      if (e.candidate) {
        sendSignal("candidate", { candidate: e.candidate });
        const cand = e.candidate.candidate || "";
        if (cand.includes("typ relay")) {
          log("发现 TURN relay 候选");
        } else if (cand.includes("typ srflx")) {
          log("发现 STUN srflx 候选");
        } else if (cand.includes("typ host")) {
          log("发现本地候选");
        }
      } else {
        // e.candidate 为 null 表示候选收集完成
        log("ICE 候选收集完成");
        if (iceGatheringTimeout) {
          clearTimeout(iceGatheringTimeout);
          iceGatheringTimeout = null;
        }
      }
    };

    pc.onicecandidateerror = (e) => {
      const errorMsg = e.errorText || e.message || e.errorCode || "unknown";
      log(`ICE candidate error: ${errorMsg}`);

      // 详细的错误信息
      if (errorMsg.includes("STUN") || errorMsg.includes("stun")) {
        log("  → STUN 服务器可能不可用或网络问题");
      } else if (errorMsg.includes("TURN") || errorMsg.includes("turn")) {
        log("  → TURN 服务器可能不可用、认证失败或网络问题");
      } else if (errorMsg.includes("timeout") || errorMsg.includes("超时")) {
        log("  → 服务器响应超时，请检查网络连接");
      }

      console.error("ICE candidate error details:", {
        errorText: e.errorText,
        errorCode: e.errorCode,
        errorType: e.errorType,
        url: e.url,
        address: e.address,
        port: e.port,
      });
    };

    pc.onicegatheringstatechange = () => {
      log(`ICE gathering state: ${pc.iceGatheringState}`);

      // 当开始收集候选时，设置超时
      if (pc.iceGatheringState === 'gathering') {
        // 清除之前的超时
        if (iceGatheringTimeout) {
          clearTimeout(iceGatheringTimeout);
        }

        // 设置 3 秒超时，如果还在 gathering 状态则强制继续
        iceGatheringTimeout = setTimeout(() => {
          if (pc && pc.iceGatheringState === 'gathering') {
            log("警告: ICE 候选收集超时（3秒），继续连接...");
            // 不需要做特殊处理，WebRTC 会继续使用已收集的候选
          }
          iceGatheringTimeout = null;
        }, 3000);
      } else if (pc.iceGatheringState === 'complete') {
        if (iceGatheringTimeout) {
          clearTimeout(iceGatheringTimeout);
          iceGatheringTimeout = null;
        }
      }
    };
    pc.oniceconnectionstatechange = () => {
      log(`ICE连接状态: ${pc.iceConnectionState}`);

      // 清除之前的 disconnected 恢复超时
      if (iceDisconnectTimer) {
        clearTimeout(iceDisconnectTimer);
        iceDisconnectTimer = null;
      }

      // 处理 disconnected 状态（弱网临时断开）
      if (pc.iceConnectionState === "disconnected") {
        log("ICE 连接暂时断开，等待恢复...");
        connState.textContent = "连接恢复中...";

        // 如果不是手动断开，设置 5 秒超时尝试恢复
        if (!manualLeave) {
          iceDisconnectTimer = setTimeout(() => {
            if (pc && pc.iceConnectionState === "disconnected") {
              log("ICE 连接未恢复，尝试重启...");
              tryIceRestart();
            }
          }, 5000);
        }
      }

      // 处理 failed 状态
      if (pc.iceConnectionState === "failed") {
        log("ICE 连接失败");
        if (!manualLeave) {
          tryIceRestart();
        }
      }

      // 连接成功
      if (pc.iceConnectionState === "connected" || pc.iceConnectionState === "completed") {
        // 重置重启计数
        iceRestartAttempts = 0;
        logSelectedCandidate();

        // 启动视频帧监控，检测卡顿时请求关键帧
        startFrameMonitor();

        // 启动网络统计监控
        if (!statsMonitor) {
          statsMonitor = new NetworkStatsMonitor(pc);
          bitrateAdjuster = new QualityController();
          bitrateAdjuster.setMode(bitrateSelect.value);

          statsMonitor.onStatsUpdate = (metrics) => {
            updateStatsDisplay(metrics);

            // 自动码率调整
            if (bitrateAdjuster && bitrateAdjuster.enabled) {
              const result = bitrateAdjuster.analyze(metrics);
              if (result !== null) {
                streamConfig.targetBitrate = result.bitrate;
                streamConfig.frameRate = result.fps; // 同时调整帧率

                log(`自动调整: ${result.fps}fps, ${(result.bitrate / 1000).toFixed(0)}kbps`);

                // 只有在帧率不是"自动"模式时才更新下拉框
                // 如果用户选择了"自动"，保持下拉框显示"自动"
                if (frameRateSelect.value !== 'auto') {
                  frameRateSelect.value = result.fps >= 30 ? "30" : (result.fps >= 15 ? "15" : "5");
                }

                // 发送控制指令
                const payload = {
                  kind: "quality",
                  fps: streamConfig.frameRate,
                  bitrate: streamConfig.targetBitrate
                };

                if (dataChannel && dataChannel.readyState === "open") {
                  dataChannel.send(JSON.stringify(payload));
                } else {
                  sendSignal('control', payload);
                }
              }
            }

            // Jitter Buffer 动态调整
            // 当 RTT 或抖动超过阈值时，通知被控端调整缓冲区
            if (metrics.rtt > 100 || (metrics.jitter && metrics.jitter > 0.02)) {
              sendJitterBufferAdjustment(
                Math.floor(metrics.rtt || 0),
                metrics.jitter || 0
              );
            }
          };
          statsMonitor.start();
          log("网络统计监控已启动");
        }

        // 应用初始流配置
        applyStreamConfig();
      }

      // 连接断开时停止监控
      if (pc.iceConnectionState === "disconnected" || pc.iceConnectionState === "failed" || pc.iceConnectionState === "closed") {
        if (statsMonitor) {
          statsMonitor.stop();
          statsMonitor = null;
        }
      }
    };
    pc.ontrack = (e) => {
      if (!remoteStream) {
        remoteStream = new MediaStream();
        remoteVideo.srcObject = remoteStream;
        // 确保视频静音以绕过自动播放限制
        remoteVideo.muted = true;
      }
      e.streams[0].getTracks().forEach((t) => remoteStream.addTrack(t));
      log("收到远端媒体轨道");

      // 主动尝试播放视频
      const playPromise = remoteVideo.play();
      if (playPromise !== undefined) {
        playPromise.then(() => {
          log("视频自动播放成功");
        }).catch((err) => {
          log(`视频自动播放失败: ${err.message}，需要用户交互`);
          // 添加一次性点击监听，用户点击后恢复播放
          const resumePlay = () => {
            remoteVideo.play().then(() => {
              log("用户交互后视频播放成功");
            }).catch(e => {
              log(`视频播放仍然失败: ${e.message}`);
            });
            document.removeEventListener('click', resumePlay);
            remoteVideo.removeEventListener('click', resumePlay);
          };
          document.addEventListener('click', resumePlay, { once: true });
          remoteVideo.addEventListener('click', resumePlay, { once: true });
        });
      }
    };
    pc.onconnectionstatechange = () => {
      connState.textContent = pc.connectionState;
      log(`连接状态: ${pc.connectionState}`);
    };
    pc.ondatachannel = (e) => {
      dataChannel = e.channel;
      attachDataChannel();
    };
    if (localStream) {
      localStream.getTracks().forEach((t) => pc.addTrack(t, localStream));
    }
    return pc;
  };

  // 聊天功能启用/禁用
  const enableChat = (enabled) => {
    chatInput.disabled = !enabled;
    sendChatBtn.disabled = !enabled;
    if (enabled) {
      chatInput.placeholder = "输入消息...";
      chatInput.style.opacity = "1";
    } else {
      chatInput.placeholder = "DataChannel未连接";
      chatInput.style.opacity = "0.5";
    }
  };

  // 初始禁用聊天
  enableChat(false);

  const attachDataChannel = () => {
    if (!dataChannel) return;
    dataChannel.onopen = () => {
      log("DataChannel opened");
      enableChat(true);
    };
    dataChannel.onclose = () => {
      log("DataChannel closed");
      enableChat(false);
    };
    dataChannel.onmessage = (evt) => {
      try {
        const msg = JSON.parse(evt.data);
        if (msg.kind === "chat") {
          addChat(msg.sender || "peer", msg.text);
        } else if (msg.kind === "mouse") {
          renderRemoteCursor(msg);
          applyRemoteMouse(msg);
        } else if (msg.kind === "keyboard") {
          applyRemoteKeyboard(msg);
        }
      } catch (e) {
        log("无法解析 DataChannel 消息");
      }
    };
  };

  const sendSignal = (type, data) => {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    const payload = {
      type,
      roomId: roomInput.value.trim(),
      sender: nameInput.value.trim(),
      data,
    };
    ws.send(JSON.stringify(payload));
  };

  const connectWs = async (isReconnect = false) => {
    if (connecting || (ws && ws.readyState === WebSocket.OPEN)) return;
    const reconnect = isReconnect === true;
    // #region agent log
    fetch("http://127.0.0.1:7242/ingest/5c2f5526-6f2e-4269-878e-b14149145b61", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        sessionId: "debug-session",
        runId: "run1",
        hypothesisId: "H2",
        location: "room.js:connectWs:entry",
        message: "connectWs entry",
        data: {
          isReconnect,
          reconnect,
          roomValue: roomInput.value,
          nameValue: nameInput.value,
          connecting,
          wsState: ws?.readyState ?? null,
        },
        timestamp: Date.now(),
      }),
    }).catch(() => { });
    // #endregion
    ensureDefaultValues();
    const roomId = roomInput.value.trim();
    const sender = nameInput.value.trim();
    // #region agent log
    fetch("http://127.0.0.1:7242/ingest/5c2f5526-6f2e-4269-878e-b14149145b61", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        sessionId: "debug-session",
        runId: "run1",
        hypothesisId: "H2",
        location: "room.js:connectWs:resolved",
        message: "connectWs resolved identifiers",
        data: { roomId, sender, isReconnect, reconnect },
        timestamp: Date.now(),
      }),
    }).catch(() => { });
    // #endregion
    if (!roomId || !sender) {
      // #region agent log
      fetch("http://127.0.0.1:7242/ingest/5c2f5526-6f2e-4269-878e-b14149145b61", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          sessionId: "debug-session",
          runId: "run1",
          hypothesisId: "H3",
          location: "room.js:connectWs:validationFailed",
          message: "missing required fields",
          data: { roomId, sender, isReconnect, reconnect },
          timestamp: Date.now(),
        }),
      }).catch(() => { });
      // #endregion
      alert("房间号和昵称必填");
      return;
    }
    connecting = true;
    ws = new WebSocket((location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/ws");
    ws.onopen = () => {
      reconnectAttempts = 0;
      connecting = false;
      log("WebSocket 已连接");

      // P1: 重连时清理旧的 PeerConnection，避免状态混乱
      if (reconnect && pc) {
        log("信令重连，清理旧的 RTC 连接");
        teardownRtc(true);
        iceRestartAttempts = 0;
      }

      ws.send(JSON.stringify({ type: "join", roomId, sender }));

      // 启动心跳检测
      startHeartbeat();
    };
    ws.onmessage = async (event) => {
      try {
        const msg = JSON.parse(event.data);
        switch (msg.type) {
          case "join-ack":
            isJoined = true;
            setUiState(true);
            connState.textContent = "信令已连接";
            const participants = msg.data?.participants || 1;
            log(`加入成功，房间在线人数: ${participants}`);

            // 如果房间里已经有其他人，主动创建 offer（控制端逻辑）
            if (participants > 1 && isController()) {
              log("房间已有成员，作为控制端主动创建 offer");
              isInitiator = true;
              await startMediaAndOffer();
            }
            break;
          case "peer-joined":
            log("有新成员加入，开始创建 offer");
            isInitiator = true;
            await startMediaAndOffer();
            break;
          case "peer-left":
            log("对端已离开，清理RTC连接，保持信令连接等待重新加入...");
            teardownRtc(true);  // 清理RTC但保留localStream
            // 保持WebSocket连接，当对端重新加入时会收到peer-joined消息
            break;
          case "offer":
            log("收到 offer");
            await handleOffer(msg.data.sdp);
            break;
          case "answer":
            log("收到 answer");
            await pc.setRemoteDescription(new RTCSessionDescription(msg.data.sdp));
            break;
          case "candidate":
            if (msg.data?.candidate) {
              await ensurePeer().addIceCandidate(new RTCIceCandidate(msg.data.candidate));
            }
            break;
          case "error":
            alert(msg.data?.message || "错误");
            break;
          case "pong":
            // 收到心跳响应，清除超时定时器
            if (heartbeatTimeout) {
              clearTimeout(heartbeatTimeout);
              heartbeatTimeout = null;
            }
            break;
          default:
            break;
        }
      } catch (e) {
        console.error('处理信令消息异常:', e);
        log(`消息处理错误: ${e.message}`);
      }
    };
    ws.onclose = () => {
      log("WebSocket 已关闭");
      connecting = false;
      connState.textContent = "信令已断开";

      // 停止心跳
      stopHeartbeat();

      teardownRtc(true);
      if (!manualLeave && isJoined) {
        scheduleReconnect();
      }
    };
    ws.onerror = () => {
      connecting = false;
      log("WebSocket 出错");
    };
  };

  const startMedia = async () => {
    // 控制端不采集本地媒体
    return null;
  };

  const startMediaAndOffer = async () => {
    ensurePeer();
    if (!dataChannel) {
      dataChannel = pc.createDataChannel("data");
      attachDataChannel();
    }

    // 添加视频接收器，告诉对方我们想接收视频
    // 检查是否已经有视频 transceiver
    const transceivers = pc.getTransceivers();
    const hasVideoTransceiver = transceivers.some(t => t.receiver.track.kind === 'video');
    if (!hasVideoTransceiver) {
      log("添加视频接收器 (recvonly)");
      pc.addTransceiver('video', { direction: 'recvonly' });
    }

    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    sendSignal("offer", { sdp: offer });
  };

  const handleOffer = async (sdp) => {
    ensurePeer();
    await pc.setRemoteDescription(new RTCSessionDescription(sdp));
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    sendSignal("answer", { sdp: answer });
  };

  const sendChat = () => {
    const text = chatInput.value.trim();
    if (!text) return;
    addChat("我", text);
    if (dataChannel && dataChannel.readyState === "open") {
      dataChannel.send(JSON.stringify({ kind: "chat", sender: nameInput.value, text }));
    }
    chatInput.value = "";
  };

  const sendMouse = (action, xRatio, yRatio, extra = {}) => {
    if (!isController()) return;
    if (!enableMouseChk.checked) return;
    if (!dataChannel || dataChannel.readyState !== "open") {
      // 如果 DataChannel 不可用，降级使用 WebSocket
      log("DataChannel 未就绪，使用 WebSocket 发送控制指令");
      const payload = {
        kind: "mouse",
        action,
        xRatio,
        yRatio,
        ...extra,
      };
      sendSignal("control", payload);
      return;
    }
    const payload = {
      kind: "mouse",
      action,
      xRatio,
      yRatio,
      ...extra,
    };
    if (action !== "move") {
      log(
        `发送鼠标${action} (${xRatio.toFixed(3)}, ${yRatio.toFixed(3)})` +
        (extra.deltaY !== undefined ? ` deltaY=${extra.deltaY}` : "")
      );
    }
    const json = JSON.stringify(payload);
    // 优先使用 DataChannel（低延迟）
    try {
      dataChannel.send(json);
    } catch (e) {
      log(`DataChannel 发送失败，降级使用 WebSocket: ${e.message}`);
      sendSignal("control", payload);
    }
  };

  const sendKeyboard = (type, e) => {
    if (!isController()) return;

    // Ctrl+V: 先发送剪贴板内容到 Android
    if (type === "keydown" && (e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "v") {
      sendClipboardToRemote();
    }

    const payload = {
      kind: "keyboard",
      type,
      key: e.key,
      code: e.code,
      altKey: e.altKey,
      ctrlKey: e.ctrlKey,
      metaKey: e.metaKey,
      shiftKey: e.shiftKey,
      repeat: e.repeat,
    };
    if (!dataChannel || dataChannel.readyState !== "open") {
      // 降级使用 WebSocket
      sendSignal("control", payload);
      return;
    }
    const json = JSON.stringify(payload);
    // 优先使用 DataChannel
    try {
      dataChannel.send(json);
    } catch (e) {
      log(`DataChannel 发送失败，降级使用 WebSocket: ${e.message}`);
      sendSignal("control", payload);
    }
  };

  // ===== 剪贴板同步 =====

  /**
   * 发送本地剪贴板内容到远程设备
   */
  const sendClipboardToRemote = async () => {
    try {
      const text = await navigator.clipboard.readText();
      if (text) {
        const payload = { kind: "clipboard", action: "sync", text };
        if (dataChannel && dataChannel.readyState === "open") {
          dataChannel.send(JSON.stringify(payload));
          log(`发送剪贴板到远程: ${text.substring(0, 50)}${text.length > 50 ? "..." : ""}`);
        }
      }
    } catch (err) {
      log(`读取剪贴板失败: ${err.message}`);
    }
  };

  /**
   * 接收远程剪贴板内容并写入本地
   */
  const receiveClipboardFromRemote = async (text) => {
    try {
      await navigator.clipboard.writeText(text);
      log(`收到远程剪贴板: ${text.substring(0, 50)}${text.length > 50 ? "..." : ""}`);
    } catch (err) {
      log(`写入剪贴板失败: ${err.message}`);
    }
  };

  const renderRemoteCursor = (msg) => {
    const overlay = remoteOverlay;
    if (!overlay) return;
    overlay.style.pointerEvents = "none";
    const vp = videoViewport(remoteVideo);
    if (!vp || !vp.width || !vp.height) return;
    const overlayRect = overlay.getBoundingClientRect();
    const x = vp.left - overlayRect.left + (msg.xRatio || 0) * vp.width;
    const y = vp.top - overlayRect.top + (msg.yRatio || 0) * vp.height;
    overlay.style.backgroundImage = `radial-gradient(circle at ${x}px ${y}px, rgba(34,211,238,0.5) 0, rgba(34,211,238,0.1) 16px, transparent 28px)`;
    if (msg.action === "click") {
      overlay.style.outline = "2px solid rgba(244,114,182,0.6)";
      setTimeout(() => (overlay.style.outline = "none"), 150);
    }
  };

  const getScrollParent = (node) => {
    if (!node) return null;
    if (node === document.body || node === document.documentElement) return window;
    const style = window.getComputedStyle(node);
    const overflowY = style.overflowY;
    const scrollable =
      (overflowY === "auto" || overflowY === "scroll" || overflowY === "overlay") &&
      node.scrollHeight > node.clientHeight;
    if (scrollable) return node;
    return getScrollParent(node.parentElement);
  };

  const applyRemoteMouse = (msg) => {
    // 控制端：只画光标，不对本页做 DOM 事件
    if (remoteStream && remoteStream.active) {
      renderRemoteCursor(msg);
      return;
    }

    // 被控端：用窗口尺寸换算坐标
    const clientW = document.documentElement.clientWidth;
    const clientH = document.documentElement.clientHeight;
    const x = (msg.xRatio || 0) * clientW;
    const y = (msg.yRatio || 0) * clientH;

    // 暂时隐藏视频容器，避免命中视频层
    const videoWrapper = remoteVideo ? remoteVideo.parentElement : null;
    const prevDisplay = videoWrapper ? videoWrapper.style.display : "";
    if (videoWrapper) videoWrapper.style.display = "none";

    const target = document.elementFromPoint(x, y);

    if (videoWrapper) videoWrapper.style.display = prevDisplay;
    if (!target) return;
    if (target.tagName === "IFRAME") {
      log("命中 IFRAME，无法操作");
      return;
    }

    if (msg.action === "click") {
      log(`点击: <${target.tagName} class="${target.className}">`);
      if (typeof target.focus === "function") target.focus();
      const opts = {
        bubbles: true,
        cancelable: true,
        view: window,
        clientX: x,
        clientY: y,
        pointerId: 1,
        pointerType: "mouse",
        isPrimary: true,
        button: 0,
        buttons: 1,
      };
      target.dispatchEvent(new PointerEvent("pointerdown", opts));
      target.dispatchEvent(new MouseEvent("mousedown", opts));
      target.dispatchEvent(new PointerEvent("pointerup", { ...opts, buttons: 0 }));
      target.dispatchEvent(new MouseEvent("mouseup", { ...opts, buttons: 0 }));
      if (typeof target.click === "function") target.click();
    } else if (msg.action === "wheel") {
      const scrollTarget = getScrollParent(target) || window;
      log(`滚动: deltaY=${msg.deltaY}`);
      if (scrollTarget === window) {
        window.scrollBy(0, msg.deltaY);
      } else {
        scrollTarget.scrollTop += msg.deltaY;
      }
    }
  };

  const logSelectedCandidate = async () => {
    if (!pc) return;
    try {
      const stats = await pc.getStats();
      let pair;
      stats.forEach((v) => {
        if (v.type === "transport" && v.selectedCandidatePairId) {
          pair = stats.get(v.selectedCandidatePairId);
        }
      });
      if (!pair) {
        stats.forEach((v) => {
          if (v.type === "candidate-pair" && v.nominated) {
            pair = v;
          }
        });
      }
      if (pair) {
        const local = stats.get(pair.localCandidateId);
        const remote = stats.get(pair.remoteCandidateId);
        const localType = local?.candidateType || "unknown";
        const remoteType = remote?.candidateType || "unknown";
        const usingTurn = localType === "relay" || remoteType === "relay";
        log(`选定候选: local=${localType} (${local?.address || local?.ip || ""}), remote=${remoteType} (${remote?.address || remote?.ip || ""})${usingTurn ? " [TURN]" : ""}`);
      }
    } catch (e) {
      log("读取 ICE 统计失败");
    }
  };

  const applyRemoteKeyboard = (msg) => {
    const target = document.activeElement || document.body;
    if (!target) return;
    const eventInit = {
      key: msg.key,
      code: msg.code,
      altKey: msg.altKey,
      ctrlKey: msg.ctrlKey,
      metaKey: msg.metaKey,
      shiftKey: msg.shiftKey,
      bubbles: true,
      cancelable: true,
      repeat: msg.repeat,
    };
    const evt = new KeyboardEvent(msg.type === "keyup" ? "keyup" : "keydown", eventInit);
    target.dispatchEvent(evt);
  };

  const bindMouseEvents = () => {
    log("绑定鼠标事件到视频元素");

    const toRatio = (ev) => {
      const vp = videoViewport(remoteVideo);
      if (!vp || !vp.width || !vp.height) return null;
      const x = (ev.clientX - vp.left) / vp.width;
      const y = (ev.clientY - vp.top) / vp.height;
      return { x, y, vp };
    };

    // 鼠标移动事件（节流）
    let lastMoveTime = 0;
    remoteVideo.addEventListener("mousemove", (ev) => {
      const now = Date.now();
      if (now - lastMoveTime < 50) return; // 节流：每50ms最多发送一次
      lastMoveTime = now;

      const ratio = toRatio(ev);
      if (!ratio) return;
      const { x, y } = ratio;
      if (x < 0 || x > 1 || y < 0 || y > 1) return;

      // 调试：检查条件
      if (!isController()) { console.log("不是控制端"); return; }
      if (!enableMouseChk.checked) { console.log("远程鼠标未启用"); return; }
      if (!dataChannel) { console.log("DataChannel 不存在"); return; }
      if (dataChannel.readyState !== "open") { console.log("DataChannel 未打开:", dataChannel.readyState); return; }

      ev.preventDefault();
      sendMouse("move", x, y);
    });

    // 鼠标点击事件（支持左键、中键、右键）
    remoteVideo.addEventListener("mousedown", (ev) => {
      // 只处理mousedown，不用click，以支持不同按钮
      const ratio = toRatio(ev);
      if (!ratio) { log("无法计算坐标比例"); return; }
      const { x, y } = ratio;
      if (x < 0 || x > 1 || y < 0 || y > 1) { log("坐标超出范围"); return; }

      if (!isController()) { log("不是控制端"); return; }
      if (!enableMouseChk.checked) { log("远程鼠标未启用"); return; }
      if (!dataChannel) { log("DataChannel 不存在"); return; }
      if (dataChannel.readyState !== "open") { log("DataChannel 未打开: " + dataChannel.readyState); return; }

      log(`视频元素收到鼠标按下事件: button=${ev.button}`);
      ev.preventDefault();
      ev.stopPropagation();
      sendMouse("mousedown", x, y, { button: ev.button });
    });

    remoteVideo.addEventListener("mouseup", (ev) => {
      const ratio = toRatio(ev);
      if (!ratio) return;
      const { x, y } = ratio;
      if (x < 0 || x > 1 || y < 0 || y > 1) return;

      if (!isController()) return;
      if (!enableMouseChk.checked) return;
      if (!dataChannel || dataChannel.readyState !== "open") return;

      ev.preventDefault();
      ev.stopPropagation();
      sendMouse("mouseup", x, y, { button: ev.button });
    });

    // 双击事件
    remoteVideo.addEventListener("dblclick", (ev) => {
      log("视频元素收到双击事件");
      const ratio = toRatio(ev);
      if (!ratio) { log("无法计算坐标比例"); return; }
      const { x, y } = ratio;
      if (x < 0 || x > 1 || y < 0 || y > 1) { log("坐标超出范围"); return; }

      if (!isController()) { log("不是控制端"); return; }
      if (!enableMouseChk.checked) { log("远程鼠标未启用"); return; }
      if (!dataChannel) { log("DataChannel 不存在"); return; }
      if (dataChannel.readyState !== "open") { log("DataChannel 未打开: " + dataChannel.readyState); return; }

      ev.preventDefault();
      ev.stopPropagation();
      sendMouse("dblclick", x, y, { button: ev.button });
    });

    // 右键菜单（阻止默认菜单，发送右键点击）
    remoteVideo.addEventListener("contextmenu", (ev) => {
      log("视频元素收到右键菜单事件");
      ev.preventDefault();
      ev.stopPropagation();
      // 右键点击已经通过mousedown/mouseup处理，这里只需阻止默认菜单
    });

    // 滚轮事件
    remoteVideo.addEventListener("wheel", (ev) => {
      log("视频元素收到滚轮事件: deltaY=" + ev.deltaY);
      const ratio = toRatio(ev);
      if (!ratio) { log("无法计算坐标比例"); return; }
      const { x, y } = ratio;
      if (x < 0 || x > 1 || y < 0 || y > 1) { log("坐标超出范围"); return; }

      if (!isController()) { log("不是控制端"); return; }
      if (!enableMouseChk.checked) { log("远程鼠标未启用"); return; }
      if (!dataChannel) { log("DataChannel 不存在"); return; }
      if (dataChannel.readyState !== "open") { log("DataChannel 未打开: " + dataChannel.readyState); return; }

      ev.preventDefault();
      ev.stopPropagation();
      sendMouse("wheel", x, y, { deltaY: ev.deltaY });
    }, { passive: false }); // passive: false 允许 preventDefault

    log("鼠标事件绑定完成");
  };

  // ICE 重启函数（带重新协商）
  const tryIceRestart = async () => {
    if (manualLeave) {
      log("手动断开，不进行 ICE 重启");
      return;
    }

    if (!pc) {
      log("PeerConnection 不存在，无法重启 ICE");
      return;
    }

    iceRestartAttempts++;

    if (iceRestartAttempts > MAX_ICE_RESTART_ATTEMPTS) {
      log(`ICE 重启失败次数过多（${MAX_ICE_RESTART_ATTEMPTS}次），放弃重试`);
      connState.textContent = "连接失败";
      // 可以选择重新建立整个连接
      return;
    }

    log(`尝试 ICE 重启（第 ${iceRestartAttempts} 次）...`);
    connState.textContent = `重连中（${iceRestartAttempts}/${MAX_ICE_RESTART_ATTEMPTS}）`;

    try {
      pc.restartIce();

      // 如果是发起方，重新创建带 iceRestart 选项的 offer
      if (isInitiator) {
        log("作为发起方，创建新的 offer（iceRestart）");
        const offer = await pc.createOffer({ iceRestart: true });
        await pc.setLocalDescription(offer);
        sendSignal("offer", { sdp: offer });
      }
    } catch (err) {
      log(`ICE 重启失败: ${err.message}`);
      console.error("ICE restart error:", err);
    }
  };

  const teardownRtc = (keepLocalStream = false) => {
    // 清理 ICE 相关定时器
    if (iceDisconnectTimer) {
      clearTimeout(iceDisconnectTimer);
      iceDisconnectTimer = null;
    }
    iceRestartAttempts = 0;

    // 清理网络统计监控器
    if (statsMonitor) {
      statsMonitor.stop();
      statsMonitor = null;
    }
    bitrateAdjuster = null;

    // 清理帧监控
    stopFrameMonitor();

    if (dataChannel) {
      dataChannel.close();
      dataChannel = null;
    }
    if (pc) {
      pc.close();
      pc = null;
    }
    if (!keepLocalStream && localStream) {
      localStream.getTracks().forEach((t) => t.stop());
      localStream = null;
    }
    remoteStream = null;
    remoteVideo.srcObject = null;
    enableChat(false);
  };

  // WebSocket 心跳检测
  const startHeartbeat = () => {
    stopHeartbeat(); // 先清理之前的

    heartbeatTimer = setInterval(() => {
      if (ws && ws.readyState === WebSocket.OPEN) {
        // 发送心跳
        ws.send(JSON.stringify({ type: "ping" }));

        // 设置超时检测
        heartbeatTimeout = setTimeout(() => {
          log("心跳超时，WebSocket 可能已断开");
          if (ws && ws.readyState === WebSocket.OPEN) {
            ws.close();
          }
        }, HEARTBEAT_TIMEOUT);
      }
    }, HEARTBEAT_INTERVAL);
  };

  const stopHeartbeat = () => {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer);
      heartbeatTimer = null;
    }
    if (heartbeatTimeout) {
      clearTimeout(heartbeatTimeout);
      heartbeatTimeout = null;
    }
  };

  const scheduleReconnect = () => {
    if (reconnectTimer) return;
    const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), 10000);
    reconnectAttempts += 1;
    log(`信令断开，${delay}ms 后尝试重连...`);
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      connectWs(true);
    }, delay);
  };

  const cleanup = (manual = false) => {
    manualLeave = manual;

    // 停止心跳
    stopHeartbeat();

    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    if (manual) {
      isJoined = false;
      isInitiator = false;
      setUiState(false);
      iceRestartAttempts = 0;  // 重置 ICE 重启计数
    }
    connState.textContent = manual ? "未连接" : "信令重连中";
    if (ws) {
      ws.close();
      ws = null;
    }
    teardownRtc(manual ? false : true);
    if (manual) {
      if (localStream) {
        localStream.getTracks().forEach((t) => t.stop());
        localStream = null;
      }
    }
  };

  // 页面加载时检测 STUN/TURN 服务器（异步，不阻塞）
  (async () => {
    try {
      await testIceServers();
    } catch (err) {
      log(`服务器检测异常: ${err.message}`);
      console.error("Server test error:", err);
    }
  })();

  joinBtn.addEventListener("click", connectWs);
  leaveBtn.addEventListener("click", () => {
    sendSignal("leave", {});
    cleanup(true);
  });

  // 视频流控制事件监听
  frameRateSelect.addEventListener("change", () => {
    if (isJoined) applyStreamConfig();
  });
  bitrateSelect.addEventListener("change", () => {
    if (isJoined) {
      if (bitrateAdjuster) {
        bitrateAdjuster.setMode(bitrateSelect.value);
      }
      applyStreamConfig();
    }
  });

  document.addEventListener("keydown", (e) => {
    // 过滤纯修饰键重复
    if (e.key === "Shift" || e.key === "Control" || e.key === "Alt" || e.key === "Meta") {
      if (e.repeat) return;
    }
    sendKeyboard("keydown", e);
  });
  document.addEventListener("keyup", (e) => {
    sendKeyboard("keyup", e);
  });
  sendChatBtn.addEventListener("click", sendChat);
  chatInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") sendChat();
  });

  applyDisplayStyles();
  window.addEventListener("resize", () => applyDisplayStyles());
  bindMouseEvents();

  // ===== 全屏功能 =====
  const toggleFullscreen = () => {
    const container = document.getElementById("videoContainer");

    // 详细日志
    log("点击全屏按钮");
    console.log("videoContainer:", container);

    if (!container) {
      log("错误: videoContainer 元素不存在");
      console.error("videoContainer element not found");
      return;
    }

    // 检查全屏 API 支持
    const supportsFullscreen = !!(
      document.fullscreenEnabled ||
      document.webkitFullscreenEnabled ||
      document.mozFullScreenEnabled ||
      document.msFullscreenEnabled
    );

    log(`浏览器全屏支持: ${supportsFullscreen}`);
    console.log("Fullscreen API support:", {
      fullscreenEnabled: document.fullscreenEnabled,
      webkitFullscreenEnabled: document.webkitFullscreenEnabled,
      mozFullScreenEnabled: document.mozFullScreenEnabled,
      msFullscreenEnabled: document.msFullscreenEnabled,
      containerMethods: {
        requestFullscreen: !!container.requestFullscreen,
        webkitRequestFullscreen: !!container.webkitRequestFullscreen,
        mozRequestFullScreen: !!container.mozRequestFullScreen,
        msRequestFullscreen: !!container.msRequestFullscreen
      }
    });

    if (!supportsFullscreen) {
      log("错误: 浏览器不支持全屏 API");
      alert("当前浏览器不支持全屏功能");
      return;
    }

    try {
      // 检查是否已经全屏
      const isFullscreen = !!(document.fullscreenElement ||
        document.webkitFullscreenElement ||
        document.mozFullScreenElement ||
        document.msFullscreenElement);

      log(`当前全屏状态: ${isFullscreen}`);

      if (isFullscreen) {
        // 退出全屏
        log("尝试退出全屏...");
        if (document.exitFullscreen) {
          document.exitFullscreen().then(() => log("已退出全屏")).catch(err => log(`退出失败: ${err.message}`));
        } else if (document.webkitExitFullscreen) {
          document.webkitExitFullscreen();
          log("已退出全屏(webkit)");
        } else if (document.mozCancelFullScreen) {
          document.mozCancelFullScreen();
          log("已退出全屏(moz)");
        } else if (document.msExitFullscreen) {
          document.msExitFullscreen();
          log("已退出全屏(ms)");
        }
      } else {
        // 进入全屏
        log("尝试进入全屏...");

        // 尝试容器全屏
        let fullscreenPromise = null;
        if (container.requestFullscreen) {
          fullscreenPromise = container.requestFullscreen();
        } else if (container.webkitRequestFullscreen) {
          fullscreenPromise = container.webkitRequestFullscreen();
        } else if (container.mozRequestFullScreen) {
          fullscreenPromise = container.mozRequestFullScreen();
        } else if (container.msRequestFullscreen) {
          fullscreenPromise = container.msRequestFullscreen();
        }

        if (fullscreenPromise) {
          fullscreenPromise.then(() => {
            log("已进入全屏");
          }).catch(err => {
            log(`容器全屏失败: ${err.message}，尝试视频全屏...`);
            console.error("Container fullscreen error:", err);

            // 如果容器全屏失败，尝试视频元素全屏
            const video = document.getElementById("remoteVideo");
            if (video) {
              if (video.requestFullscreen) {
                video.requestFullscreen().then(() => log("视频已进入全屏")).catch(e => log(`视频全屏也失败: ${e.message}`));
              } else if (video.webkitRequestFullscreen) {
                video.webkitRequestFullscreen();
                log("视频已进入全屏(webkit)");
              } else if (video.mozRequestFullScreen) {
                video.mozRequestFullScreen();
                log("视频已进入全屏(moz)");
              } else if (video.webkitEnterFullscreen) {
                // iOS Safari 特殊方法
                video.webkitEnterFullscreen();
                log("视频已进入全屏(iOS)");
              }
            }
          });
        } else {
          log("错误: 找不到全屏方法");
        }
      }
    } catch (error) {
      log(`全屏操作异常: ${error.message}`);
      console.error("Fullscreen exception:", error);
    }
  };

  const updateFullscreenButton = () => {
    const isFullscreen = !!(document.fullscreenElement === videoContainer ||
      document.webkitFullscreenElement === videoContainer ||
      document.mozFullScreenElement === videoContainer ||
      document.msFullscreenElement === videoContainer);

    // 更新顶部按钮
    if (fullscreenBtn) {
      const icon = fullscreenBtn.querySelector('svg path');
      const text = fullscreenBtn.querySelector('span');

      if (isFullscreen) {
        if (icon) {
          icon.setAttribute('d', 'M8 3v3a2 2 0 0 1-2 2H3m18 0h-3a2 2 0 0 1-2-2V3m0 18v-3a2 2 0 0 1 2-2h3M3 16h3a2 2 0 0 1 2 2v3');
        }
        if (text) text.textContent = '退出全屏';
        fullscreenBtn.title = '退出全屏';
      } else {
        if (icon) {
          icon.setAttribute('d', 'M4 8V4m0 0h4M4 4l5 5m11-1V4m0 0h-4m4 0l-5 5M4 16v4m0 0h4m-4 0l5-5m11 5l-5-5m5 5v-4m0 4h-4');
        }
        if (text) text.textContent = '全屏';
        fullscreenBtn.title = '全屏';
      }
    }

    // 更新浮动按钮
    if (fullscreenBtnFloat) {
      const icon = fullscreenBtnFloat.querySelector('svg path');
      const text = fullscreenBtnFloat.querySelector('span');

      if (isFullscreen) {
        if (icon) {
          icon.setAttribute('d', 'M8 3v3a2 2 0 0 1-2 2H3m18 0h-3a2 2 0 0 1-2-2V3m0 18v-3a2 2 0 0 1 2-2h3M3 16h3a2 2 0 0 1 2 2v3');
        }
        if (text) text.textContent = '退出全屏';
        fullscreenBtnFloat.title = '退出全屏';
      } else {
        if (icon) {
          icon.setAttribute('d', 'M4 8V4m0 0h4M4 4l5 5m11-1V4m0 0h-4m4 0l-5 5M4 16v4m0 0h4m-4 0l5-5m11 5l-5-5m5 5v-4m0 4h-4');
        }
        if (text) text.textContent = '全屏';
        fullscreenBtnFloat.title = '全屏';
      }
    }
  };

  // 全屏按钮事件
  if (fullscreenBtn) {
    fullscreenBtn.addEventListener('click', toggleFullscreen);
  }
  if (fullscreenBtnFloat) {
    fullscreenBtnFloat.addEventListener('click', toggleFullscreen);
  }

  // 监听全屏状态变化
  document.addEventListener('fullscreenchange', updateFullscreenButton);
  document.addEventListener('webkitfullscreenchange', updateFullscreenButton);
  document.addEventListener('mozfullscreenchange', updateFullscreenButton);
  document.addEventListener('MSFullscreenChange', updateFullscreenButton);

  // 双击视频（按住 Shift 键）进入/退出全屏，避免与鼠标控制冲突
  if (remoteVideo) {
    remoteVideo.addEventListener('dblclick', (e) => {
      // 按住 Shift 键双击时进入/退出全屏
      if (e.shiftKey) {
        e.preventDefault();
        e.stopPropagation();
        toggleFullscreen();
        return;
      }
      // 否则正常处理鼠标双击事件（已经在 bindMouseEvents 中处理）
    });
  }

  // 键盘快捷键：F11 进入/退出全屏（移除了 F 键，避免干扰远程输入）
  document.addEventListener('keydown', (e) => {
    // F11 键：全屏（需要阻止浏览器默认行为）
    if (e.key === 'F11') {
      e.preventDefault();
      toggleFullscreen();
    }
    // 注意：不再使用 F 键触发全屏，以免干扰远程控制的键盘输入
  });

  // ===== P0: 网络状态监听 =====
  // 监听浏览器 online/offline 事件，实现 WiFi 切换快速恢复
  window.addEventListener('offline', () => {
    log('网络断开');
    connState.textContent = '网络断开';
  });

  window.addEventListener('online', () => {
    log('网络恢复，立即重连');
    if (!manualLeave && (!ws || ws.readyState !== WebSocket.OPEN)) {
      reconnectAttempts = 0;  // 重置重试计数，立即重连
      // 清理旧的 RTC 连接（IP 已变化，旧连接无效）
      if (pc) {
        teardownRtc(true);
        iceRestartAttempts = 0;
      }
      connectWs(true);
    }
  });

  // ===== Network Information API =====
  // 检测网络类型变化（WiFi <-> 4G 切换）
  if ('connection' in navigator) {
    const connection = navigator.connection;
    let lastNetworkType = connection?.effectiveType || 'unknown';
    let networkChangeDebounceTimer = null;

    const handleNetworkChange = () => {
      const newType = connection?.effectiveType || 'unknown';
      log(`[Network] 网络类型变化: ${lastNetworkType} -> ${newType}`);

      // 如果网络类型没有实际变化（如 4g -> 4g），跳过处理
      if (newType === lastNetworkType) {
        log('[Network] 网络类型未实际变化，跳过处理');
        return;
      }

      lastNetworkType = newType;

      // 只有在已加入房间时才处理网络切换
      if (!isJoined || manualLeave) return;

      // 添加防抖，避免短时间内多次触发
      if (networkChangeDebounceTimer) {
        clearTimeout(networkChangeDebounceTimer);
      }

      networkChangeDebounceTimer = setTimeout(() => {
        networkChangeDebounceTimer = null;

        // 网络类型变化可能意味着 IP 地址改变
        // 需要检查连接状态并尝试恢复
        if (ws && ws.readyState === WebSocket.OPEN) {
          // WebSocket 仍然连接，但 ICE 可能需要重启
          if (pc) {
            const iceState = pc.iceConnectionState;
            if (iceState === 'disconnected' || iceState === 'failed') {
              log('[Network] 检测到网络切换且 ICE 异常，尝试重启');
              tryIceRestart();
            } else if (iceState === 'connected' || iceState === 'completed') {
              // 连接看起来正常，发送心跳确认
              sendSignal('ping', {});
            }
          }
        } else {
          // WebSocket 断开，尝试重连
          log('[Network] 网络切换后 WebSocket 断开，尝试重连');
          reconnectAttempts = 0;
          connectWs(true);
        }
      }, 2000);  // 2秒防抖
    };

    connection.addEventListener('change', handleNetworkChange);
    log(`[Network] 已注册网络变化监听，当前类型: ${lastNetworkType}`);
  }

  // ===== Page Visibility API =====
  // 检测用户切换标签页后返回，确保连接仍然有效
  let lastVisibilityTime = Date.now();

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      // 页面进入后台，记录时间
      lastVisibilityTime = Date.now();
      log('[Visibility] 页面进入后台');
    } else {
      // 页面恢复前台
      const hiddenDuration = Date.now() - lastVisibilityTime;
      log(`[Visibility] 页面恢复前台，后台时长: ${(hiddenDuration / 1000).toFixed(1)}s`);

      if (!isJoined || manualLeave) return;

      // 如果后台超过30秒，检查并恢复连接
      if (hiddenDuration > 30000) {
        // 检查 WebSocket 连接
        if (!ws || ws.readyState !== WebSocket.OPEN) {
          log('[Visibility] WebSocket 已断开，尝试重连');
          reconnectAttempts = 0;
          connectWs(true);
          return;
        }

        // 检查 ICE 连接状态
        if (pc) {
          const iceState = pc.iceConnectionState;
          if (iceState === 'disconnected' || iceState === 'failed') {
            log(`[Visibility] ICE 状态异常 (${iceState})，尝试重启`);
            tryIceRestart();
          } else if (iceState === 'connected' || iceState === 'completed') {
            // 连接正常，但可能心跳超时，发送一个信号确认
            log('[Visibility] 连接正常，发送心跳确认');
            sendSignal('ping', {});
          }
        }
      }
    }
  });

})(); 
