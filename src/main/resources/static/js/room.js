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
  
  // 视频流控制相关
  let statsMonitor = null;
  let bitrateAdjuster = null;
  let streamConfig = {
    frameRate: 15,
    bitrateMode: 'auto',
    targetBitrate: 2000000
  };

  // ===== 自动码率调整引擎 =====
  class BitrateAdjuster {
    constructor() {
      this.enabled = false;
      this.currentBitrate = 2000000;  // 当前码率
      this.minBitrate = 300000;       // 最低300kbps
      this.maxBitrate = 4000000;      // 最高4Mbps
      this.adjustCooldown = 5000;     // 5秒冷却
      this.lastAdjustTime = 0;
      this.consecutiveBadSamples = 0;
      this.consecutiveGoodSamples = 0;
    }
    
    setMode(mode) {
      this.enabled = (mode === 'auto');
      if (!this.enabled) {
        // 手动模式下直接设置目标码率
        const bitrateMap = {
          'smooth': 500000,
          'hd': 2000000,
          'original': 4000000
        };
        this.currentBitrate = bitrateMap[mode] || 2000000;
      }
    }
    
    analyze(metrics) {
      if (!this.enabled) return null;
      
      const now = Date.now();
      if (now - this.lastAdjustTime < this.adjustCooldown) return null;
      
      // 综合判断网络状况
      const isBad = (metrics.packetLoss > 0.05) || (metrics.rtt && metrics.rtt > 100);
      const isGood = (metrics.packetLoss < 0.02) && (metrics.rtt && metrics.rtt < 50);
      
      if (isBad) {
        this.consecutiveBadSamples++;
        this.consecutiveGoodSamples = 0;
        
        // 连续3次不佳则降低码率
        if (this.consecutiveBadSamples >= 3) {
          const newBitrate = Math.max(this.minBitrate, this.currentBitrate * 0.7);
          if (newBitrate !== this.currentBitrate) {
            this.currentBitrate = newBitrate;
            this.lastAdjustTime = now;
            this.consecutiveBadSamples = 0;
            return newBitrate;
          }
        }
      } else if (isGood) {
        this.consecutiveGoodSamples++;
        this.consecutiveBadSamples = 0;
        
        // 连续5次良好则提高码率
        if (this.consecutiveGoodSamples >= 5) {
          const newBitrate = Math.min(this.maxBitrate, this.currentBitrate * 1.3);
          if (newBitrate !== this.currentBitrate) {
            this.currentBitrate = newBitrate;
            this.lastAdjustTime = now;
            this.consecutiveGoodSamples = 0;
            return newBitrate;
          }
        }
      }
      
      return null;
    }
  }

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
        if (timeDiff > 0) {
          const bytesDiff = metrics.bytesReceived - this.lastStats.bytesReceived;
          metrics.bitrate = (bytesDiff * 8) / timeDiff; // bps

          const framesDiff = metrics.framesDecoded - this.lastStats.framesDecoded;
          metrics.fps = framesDiff / timeDiff;
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
    if (!pc || !isJoined) return;
    
    streamConfig.frameRate = parseInt(frameRateSelect.value);
    streamConfig.bitrateMode = bitrateSelect.value;
    
    // 根据档位设置目标码率
    const bitrateMap = {
      'smooth': 500000,    // 500kbps
      'hd': 2000000,       // 2Mbps
      'original': 4000000, // 4Mbps
      'auto': 2000000      // 默认2Mbps，后续由自动调整
    };
    streamConfig.targetBitrate = bitrateMap[streamConfig.bitrateMode] || 2000000;
    
    log(`应用流配置: ${streamConfig.frameRate}fps, ${streamConfig.bitrateMode}, ${(streamConfig.targetBitrate/1000).toFixed(0)}kbps`);
    
    // 通过信令发送配置到Agent
    sendSignal('stream_config', streamConfig);
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

  const ensurePeer = () => {
    if (pc) return pc;
    const rtcConfig = {
      iceServers: iceServers(),
      iceTransportPolicy: "all",
      iceCandidatePoolSize: 2,
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
        }
      }
    };
    pc.onicecandidateerror = (e) => {
      log(`ICE candidate error: ${e.errorText || e.message || "unknown"}`);
    };
    pc.onicegatheringstatechange = () => {
      log(`ICE gathering state: ${pc.iceGatheringState}`);
    };
    pc.oniceconnectionstatechange = () => {
      log(`ICE连接状态: ${pc.iceConnectionState}`);
      if (pc.iceConnectionState === "failed") {
        log("ICE 失败，尝试 restartIce()");
        pc.restartIce();
      }
      if (pc.iceConnectionState === "connected" || pc.iceConnectionState === "completed") {
        logSelectedCandidate();
        
        // 启动网络统计监控
        if (!statsMonitor) {
          statsMonitor = new NetworkStatsMonitor(pc);
          bitrateAdjuster = new BitrateAdjuster();
          bitrateAdjuster.setMode(bitrateSelect.value);
          
          statsMonitor.onStatsUpdate = (metrics) => {
            updateStatsDisplay(metrics);
            
            // 自动码率调整
            if (bitrateAdjuster && bitrateAdjuster.enabled) {
              const newBitrate = bitrateAdjuster.analyze(metrics);
              if (newBitrate !== null) {
                streamConfig.targetBitrate = newBitrate;
                log(`自动调整码率: ${(newBitrate/1000).toFixed(0)}kbps`);
                sendSignal('stream_config', streamConfig);
              }
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
      }
      e.streams[0].getTracks().forEach((t) => remoteStream.addTrack(t));
      log("收到远端媒体轨道");
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
    }).catch(() => {});
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
    }).catch(() => {});
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
      }).catch(() => {});
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
      ws.send(JSON.stringify({ type: "join", roomId, sender }));
    };
    ws.onmessage = async (event) => {
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
        default:
          break;
      }
    };
    ws.onclose = () => {
      log("WebSocket 已关闭");
      connecting = false;
      connState.textContent = "信令已断开";
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

  const teardownRtc = (keepLocalStream = false) => {
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
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    if (manual) {
      isJoined = false;
      isInitiator = false;
      setUiState(false);
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
  
})(); 
