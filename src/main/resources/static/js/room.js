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
  let manualLeave = false;
  let connecting = false;
  let lastRoomId = "";
  let lastDisplayName = "";

  const ensureDefaultValues = () => {
    // #region agent log
    fetch("http://127.0.0.1:7242/ingest/5c2f5526-6f2e-4269-878e-b14149145b61", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        sessionId: "debug-session",
        runId: "run1",
        hypothesisId: "H1",
        location: "room.js:ensureDefaultValues:before",
        message: "ensureDefaultValues input",
        data: { roomRaw: roomInput.value, nameRaw: nameInput.value },
        timestamp: Date.now(),
      }),
    }).catch(() => {});
    // #endregion
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
    // #region agent log
    fetch("http://127.0.0.1:7242/ingest/5c2f5526-6f2e-4269-878e-b14149145b61", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        sessionId: "debug-session",
        runId: "run1",
        hypothesisId: "H1",
        location: "room.js:ensureDefaultValues:after",
        message: "ensureDefaultValues output",
        data: { roomFinal: roomInput.value, nameFinal: nameInput.value },
        timestamp: Date.now(),
      }),
    }).catch(() => {});
    // #endregion
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

  const isController = () => !!roleController && roleController.checked;
  const applyDisplayStyles = () => {
    if (remoteVideo) {
      remoteVideo.style.objectFit = "contain";
      remoteVideo.style.width = "100%";
      remoteVideo.style.height = "100%";
    }
    if (remoteOverlay) {
      remoteOverlay.style.position = "absolute";
      remoteOverlay.style.inset = "0";
      remoteOverlay.style.pointerEvents = "none";
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
    const reconnect = isReconnect === true; // 避免事件对象被当成 true
    if (!reconnect && !isController()) {
      try {
        await startMedia(true); // 在用户点击加入的手势下请求屏幕权限
      } catch (e) {
        log("屏幕共享被取消或失败: " + e.message);
        alert("屏幕共享被取消或失败，请重新选择屏幕");
        return;
      }
    }
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
    const roomId = reconnect ? lastRoomId : roomInput.value.trim();
    const sender = reconnect ? lastDisplayName : nameInput.value.trim();
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
        data: { roomId, sender, lastRoomId, lastDisplayName, isReconnect, reconnect },
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
    lastRoomId = roomId;
    lastDisplayName = sender;
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
          log(`加入成功，房间在线人数: ${msg.data?.participants || 1}`);
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

  const startMedia = async (mustRequest = false) => {
    if (localStream) return localStream;
    if (!mustRequest) {
      const err = new Error("screen_not_ready");
      // #region agent log
      fetch("http://127.0.0.1:7242/ingest/5c2f5526-6f2e-4269-878e-b14149145b61", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          sessionId: "debug-session",
          runId: "run2",
          hypothesisId: "H6",
          location: "room.js:startMedia:skipNoStream",
          message: "screen not captured yet",
          data: {},
          timestamp: Date.now(),
        }),
      }).catch(() => {});
      // #endregion
      throw err;
    }
    try {
      localStream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false });
      if (pc) {
        localStream.getTracks().forEach((t) => pc.addTrack(t, localStream));
      }
      // #region agent log
      fetch("http://127.0.0.1:7242/ingest/5c2f5526-6f2e-4269-878e-b14149145b61", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          sessionId: "debug-session",
          runId: "run2",
          hypothesisId: "H6",
          location: "room.js:startMedia:success",
          message: "screen captured",
          data: { tracks: localStream.getTracks().map((t) => ({ kind: t.kind, label: t.label })) },
          timestamp: Date.now(),
        }),
      }).catch(() => {});
      // #endregion
      return localStream;
    } catch (e) {
      // #region agent log
      fetch("http://127.0.0.1:7242/ingest/5c2f5526-6f2e-4269-878e-b14149145b61", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          sessionId: "debug-session",
          runId: "run2",
          hypothesisId: "H6",
          location: "room.js:startMedia:error",
          message: "media error",
          data: { name: e.name, message: e.message, stack: (e && e.stack) || null },
          timestamp: Date.now(),
        }),
      }).catch(() => {});
      // #endregion
      throw e;
    }
  };

  const startMediaAndOffer = async () => {
    ensurePeer();
    if (!isController()) {
      try {
        await startMedia(false);
      } catch (e) {
        log("屏幕共享被取消或失败: " + e.message);
        alert("请先重新点击“加入”并在弹窗中选择屏幕");
        return;
      }
    }
    if (!dataChannel) {
      dataChannel = pc.createDataChannel("data");
      attachDataChannel();
    }
    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    sendSignal("offer", { sdp: offer });
  };

  const handleOffer = async (sdp) => {
    ensurePeer();
    if (!isController()) {
      try {
        await startMedia(false);
      } catch (e) {
        log("屏幕共享被取消或失败: " + e.message);
        alert("请先重新点击“加入”并在弹窗中选择屏幕");
        return;
      }
    }
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
    if (!dataChannel || dataChannel.readyState !== "open") return;
    if (action !== "move") {
      log(`发送鼠标${action} (${xRatio.toFixed(3)}, ${yRatio.toFixed(3)})`);
    }
    dataChannel.send(
      JSON.stringify({
        kind: "mouse",
        action,
        xRatio,
        yRatio,
        ...extra,
      })
    );
  };

  const sendKeyboard = (type, e) => {
    if (!isController()) return;
    if (!dataChannel || dataChannel.readyState !== "open") return;
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
    dataChannel.send(JSON.stringify(payload));
  };

  const renderRemoteCursor = (msg) => {
    const overlay = remoteOverlay;
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

  const applyRemoteMouse = (msg) => {
    const overlay = remoteOverlay;
    if (!overlay) return;
    const rect = overlay.getBoundingClientRect();
    const x = rect.left + (msg.xRatio || 0) * rect.width;
    const y = rect.top + (msg.yRatio || 0) * rect.height;
    const common = { clientX: x, clientY: y, bubbles: true, cancelable: true };
    const target = document.elementFromPoint(x, y) || overlay;
    if (!target) return;
    if (msg.action === "move") {
      target.dispatchEvent(new MouseEvent("mousemove", common));
    } else if (msg.action === "click") {
      log(`接收鼠标click (${(msg.xRatio || 0).toFixed(3)}, ${(msg.yRatio || 0).toFixed(3)})`);
      target.dispatchEvent(new MouseEvent("mousedown", common));
      target.dispatchEvent(new MouseEvent("mouseup", common));
      target.dispatchEvent(new MouseEvent("click", common));
    } else if (msg.action === "wheel") {
      log(`接收鼠标wheel (${(msg.xRatio || 0).toFixed(3)}, ${(msg.yRatio || 0).toFixed(3)})`);
      target.dispatchEvent(new WheelEvent("wheel", { ...common, deltaY: msg.deltaY || 0 }));
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
    if (!isController()) return;
    const toRatio = (ev) => {
      const vp = videoViewport(remoteVideo);
       if (!vp || !vp.width || !vp.height) return null;
      const x = (ev.clientX - vp.left) / vp.width;
      const y = (ev.clientY - vp.top) / vp.height;
      return { x, y };
    };
    remoteVideo.addEventListener("mousemove", (ev) => {
      const ratio = toRatio(ev);
      if (!ratio) return;
      const { x, y } = ratio;
      if (x < 0 || x > 1 || y < 0 || y > 1) return;
      sendMouse("move", x, y);
    });
    remoteVideo.addEventListener("click", (ev) => {
      const ratio = toRatio(ev);
      if (!ratio) return;
      const { x, y } = ratio;
      if (x < 0 || x > 1 || y < 0 || y > 1) return;
      sendMouse("click", x, y);
    });
    remoteVideo.addEventListener("wheel", (ev) => {
      const ratio = toRatio(ev);
      if (!ratio) return;
      const { x, y } = ratio;
      if (x < 0 || x > 1 || y < 0 || y > 1) return;
      sendMouse("wheel", x, y, { deltaY: ev.deltaY });
    });
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

  applyDisplayStyles();
  window.addEventListener("resize", () => applyDisplayStyles());
  bindMouseEvents();
})(); 
