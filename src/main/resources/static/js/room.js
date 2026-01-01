(() => {
  const roomInput = document.getElementById("roomId");
  const nameInput = document.getElementById("displayName");
  const joinBtn = document.getElementById("joinBtn");
  const leaveBtn = document.getElementById("leaveBtn");
  const enableMouseChk = document.getElementById("enableMouse");
  const roleControlled = document.getElementById("roleControlled");
  const roleController = document.getElementById("roleController");
  const remoteVideo = document.getElementById("remoteVideo");
  const remoteOverlay = document.getElementById("remoteOverlay");
  const chatArea = document.getElementById("chatArea");
  const chatInput = document.getElementById("chatInput");
  const sendChatBtn = document.getElementById("sendChat");
  const logArea = document.getElementById("logArea");
  const connState = document.getElementById("connState");

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

  const addChat = (sender, text) => {
    const el = document.createElement("div");
    el.innerHTML = `<span class="text-cyan-300">${sender}:</span> <span class="text-slate-200 break-words">${text}</span>`;
    chatArea.appendChild(el);
    chatArea.scrollTop = chatArea.scrollHeight;
  };

  const isController = () => true; // Web 端仅作为控制端
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

  const attachDataChannel = () => {
    if (!dataChannel) return;
    dataChannel.onopen = () => log("DataChannel opened");
    dataChannel.onclose = () => log("DataChannel closed");
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
          log("对端已离开，清理连接等待重新加入");
          teardownRtc(true);
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
    
    // 鼠标点击事件
    remoteVideo.addEventListener("click", (ev) => {
      log("视频元素收到点击事件");
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
      sendMouse("click", x, y);
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

  const applyRolePointerMode = () => {
    const ctrl = isController();
    if (ctrl) {
      if (remoteVideo) remoteVideo.classList.remove("pass-through");
      if (remoteOverlay) remoteOverlay.classList.remove("pass-through");
    } else {
      if (remoteVideo) remoteVideo.classList.add("pass-through");
      if (remoteOverlay) remoteOverlay.classList.add("pass-through");
    }
  };

  applyDisplayStyles();
  window.addEventListener("resize", () => applyDisplayStyles());
  bindMouseEvents();

  const onRoleChange = () => {
    log(`角色切换为: ${isController() ? "控制端" : "被控端"}`);
    applyRolePointerMode();
  };
  if (roleController) roleController.addEventListener("change", onRoleChange);
  if (roleControlled) roleControlled.addEventListener("change", onRoleChange);
  onRoleChange();
})(); 
