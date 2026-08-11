const $ = (id) => document.getElementById(id);

const state = {
  messages: [],
  mediaRecorder: null,
  chunks: [],
  recording: false,
  recordStartedAt: 0,
};

function fmtTime(ts) {
  const d = new Date(ts * 1000);
  return d.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

function escapeHtml(str) {
  return String(str)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function renderMessages() {
  const root = $("chatScroll");
  if (!state.messages.length) {
    root.innerHTML = `
      <div class="empty-state">
        <strong>从这里开始联调</strong>
        发送文字或语音；左侧可开启演示 Bot / Webhook，或用 API 主动回复。
      </div>`;
    return;
  }

  root.innerHTML = state.messages
    .map((m) => {
      const role = m.role === "user" ? "user" : m.role === "bot" ? "bot" : "system";
      let body = "";
      if (m.msgtype === "voice") {
        body = `
          <div class="voice-card">
            <div>🎤 语音消息</div>
            <audio controls preload="metadata" src="${escapeHtml(m.voice_url || "")}"></audio>
            <div>${escapeHtml(m.recognition || m.content || "")}</div>
            <div class="msg-meta">media_id: ${escapeHtml(m.media_id || "")}</div>
          </div>`;
      } else {
        body = escapeHtml(m.content || "");
      }
      return `
        <div class="msg-row ${role}">
          <div class="bubble">
            ${body}
            <div class="msg-meta">${escapeHtml(m.from_user)} · ${fmtTime(m.create_time)} · ${escapeHtml(m.msgtype)}</div>
          </div>
        </div>`;
    })
    .join("");

  root.scrollTop = root.scrollHeight;
}

function applySession(session) {
  $("sessionId").textContent = session.session_id || "—";
  $("demoBotEnabled").checked = !!session.demo_bot_enabled;
  $("webhookEnabled").checked = !!session.webhook_enabled;
  $("webhookUrl").value = session.webhook_url || "";
  state.messages = session.messages || [];
  renderMessages();
}

function upsertMessage(message) {
  const idx = state.messages.findIndex((m) => m.msgid === message.msgid);
  if (idx >= 0) state.messages[idx] = message;
  else state.messages.push(message);
  renderMessages();
}

async function api(path, options = {}) {
  const resp = await fetch(path, options);
  if (!resp.ok) {
    const text = await resp.text();
    throw new Error(text || `HTTP ${resp.status}`);
  }
  if (resp.status === 204) return null;
  return resp.json();
}

async function refreshSession() {
  const session = await api("/api/session");
  applySession(session);
}

function setConn(online, text) {
  $("liveDot").classList.toggle("online", online);
  $("connStatus").textContent = text;
}

function connectWs() {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  const ws = new WebSocket(`${proto}://${location.host}/ws`);

  ws.addEventListener("open", () => setConn(true, "WebSocket 已连接"));
  ws.addEventListener("close", () => {
    setConn(false, "连接断开，2 秒后重连…");
    setTimeout(connectWs, 2000);
  });
  ws.addEventListener("message", (ev) => {
    const data = JSON.parse(ev.data);
    if (data.type === "snapshot" && data.session) {
      applySession(data.session);
    } else if (data.type === "message" && data.message) {
      upsertMessage(data.message);
    } else if (data.type === "cleared") {
      state.messages = [];
      $("sessionId").textContent = data.session_id || "—";
      renderMessages();
    }
  });

  setInterval(() => {
    if (ws.readyState === WebSocket.OPEN) ws.send("ping");
  }, 25000);
}

async function sendText(content) {
  await api("/api/messages/text", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ content, from_user: "user001", agent_id: "1000001" }),
  });
}

async function uploadVoice(blob, filename, durationMs) {
  const fd = new FormData();
  fd.append("file", blob, filename);
  fd.append("from_user", "user001");
  fd.append("agent_id", "1000001");
  const recognition = $("voiceRecognition").value.trim();
  if (recognition) fd.append("recognition", recognition);
  if (durationMs != null) fd.append("duration_ms", String(durationMs));
  await api("/api/messages/voice", { method: "POST", body: fd });
}

async function startRecording() {
  if (state.recording) return;
  if (!navigator.mediaDevices?.getUserMedia) {
    alert("当前环境不支持麦克风录音，请改用「上传语音」。");
    return;
  }
  const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  const recorder = new MediaRecorder(stream);
  state.chunks = [];
  state.mediaRecorder = recorder;
  state.recording = true;
  state.recordStartedAt = Date.now();
  $("btnRecord").classList.add("recording");
  $("recordHint").textContent = "录音中… 再次点击结束";

  recorder.ondataavailable = (e) => {
    if (e.data.size > 0) state.chunks.push(e.data);
  };
  recorder.onstop = async () => {
    stream.getTracks().forEach((t) => t.stop());
    const blob = new Blob(state.chunks, { type: recorder.mimeType || "audio/webm" });
    const durationMs = Date.now() - state.recordStartedAt;
    try {
      await uploadVoice(blob, `record-${Date.now()}.webm`, durationMs);
      $("recordHint").textContent = "语音已发送";
    } catch (err) {
      console.error(err);
      $("recordHint").textContent = "语音发送失败";
      alert(err.message || String(err));
    }
  };
  recorder.start();
}

function stopRecording() {
  if (!state.recording || !state.mediaRecorder) return;
  state.recording = false;
  $("btnRecord").classList.remove("recording");
  state.mediaRecorder.stop();
  state.mediaRecorder = null;
}

function bindEvents() {
  $("textForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const input = $("textInput");
    const content = input.value.trim();
    if (!content) return;
    input.value = "";
    try {
      await sendText(content);
    } catch (err) {
      alert(err.message || String(err));
    }
  });

  $("textInput").addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      $("textForm").requestSubmit();
    }
  });

  $("btnPickVoice").addEventListener("click", () => $("voiceFile").click());
  $("voiceFile").addEventListener("change", async () => {
    const file = $("voiceFile").files?.[0];
    if (!file) return;
    try {
      await uploadVoice(file, file.name, null);
      $("recordHint").textContent = `已上传 ${file.name}`;
    } catch (err) {
      alert(err.message || String(err));
    } finally {
      $("voiceFile").value = "";
    }
  });

  $("btnRecord").addEventListener("click", async () => {
    try {
      if (state.recording) stopRecording();
      else await startRecording();
    } catch (err) {
      console.error(err);
      alert("无法开始录音：" + (err.message || String(err)));
      state.recording = false;
      $("btnRecord").classList.remove("recording");
    }
  });

  $("btnClear").addEventListener("click", async () => {
    await api("/api/session", { method: "DELETE" });
  });

  $("demoBotEnabled").addEventListener("change", async () => {
    await api("/api/config/demo-bot", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enabled: $("demoBotEnabled").checked }),
    });
  });

  $("btnSaveWebhook").addEventListener("click", async () => {
    await api("/api/config/webhook", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        url: $("webhookUrl").value.trim(),
        enabled: $("webhookEnabled").checked,
      }),
    });
    $("recordHint").textContent = "Webhook 已保存";
  });

  $("btnManualReply").addEventListener("click", async () => {
    const content = $("manualReply").value.trim();
    if (!content) return;
    await api("/api/reply/text", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ content, to_user: "user001", agent_id: "1000001" }),
    });
    $("manualReply").value = "";
  });
}

bindEvents();
refreshSession().catch(console.error);
connectWs();
