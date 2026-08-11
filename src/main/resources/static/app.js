const $ = (id) => document.getElementById(id);

const state = {
  messages: [],
  groups: [],
  moments: [],
  interactions: [],
  mediaRecorder: null,
  chunks: [],
  recording: false,
  recordStartedAt: 0,
  momentImageFile: null,
  chatType: "private",
  groupId: "group001",
  replyTo: null,
};

function fmtTime(ts) {
  const d = new Date(ts * 1000);
  return d.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

function escapeHtml(str) {
  return String(str ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function visibleMessages() {
  return state.messages.filter((m) => {
    if (state.chatType === "group") {
      return m.chat_type === "group" && m.group_id === state.groupId;
    }
    return !m.chat_type || m.chat_type === "private";
  });
}

function renderMessages() {
  const root = $("chatScroll");
  const list = visibleMessages();
  if (!list.length) {
    root.innerHTML = `
      <div class="empty-state">
        <strong>${state.chatType === "group" ? "群聊联调" : "私聊联调"}</strong>
        支持文字 / 语音 / 图片 / 文件；群聊可点「回复」指定某人。
      </div>`;
    return;
  }

  root.innerHTML = list
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
      } else if (m.msgtype === "image") {
        body = `
          <div>🖼 图片消息</div>
          <img class="media-thumb" src="${escapeHtml(m.image_url || "")}" alt="图片" />
          <div class="msg-meta">media_id: ${escapeHtml(m.media_id || "")}</div>`;
      } else if (m.msgtype === "file") {
        body = `
          <div>📎 文件消息</div>
          <a class="file-link" href="${escapeHtml(m.file_url || "")}" target="_blank" rel="noreferrer">
            ${escapeHtml(m.file_name || "下载文件")}
          </a>
          <div class="msg-meta">size: ${escapeHtml(String(m.file_size ?? ""))} · media_id: ${escapeHtml(m.media_id || "")}</div>`;
      } else {
        body = escapeHtml(m.content || "");
      }

      const quote =
        m.reply_to_user || m.reply_to_content
          ? `<div class="quote">回复 ${escapeHtml(m.reply_to_user_name || m.reply_to_user || "")}
              ${m.reply_to_content ? `：${escapeHtml(m.reply_to_content)}` : ""}</div>`
          : "";
      const mentions =
        m.mention_user_ids && m.mention_user_ids.length
          ? `<div class="msg-meta">@ ${escapeHtml(m.mention_user_ids.join(" "))}</div>`
          : "";
      const scope =
        m.chat_type === "group"
          ? `群:${escapeHtml(m.group_name || m.group_id || "")}`
          : "私聊";

      return `
        <div class="msg-row ${role}" data-msgid="${escapeHtml(m.msgid)}">
          <div class="bubble">
            ${quote}
            ${body}
            ${mentions}
            <div class="msg-meta">${escapeHtml(m.from_user_name || m.from_user)} · ${fmtTime(m.create_time)} · ${escapeHtml(m.msgtype)} · ${scope}</div>
            <div class="bubble-actions">
              <button type="button" class="icon-btn btn-reply-msg"
                data-msgid="${escapeHtml(m.msgid)}"
                data-user="${escapeHtml(m.from_user)}"
                data-name="${escapeHtml(m.from_user_name || m.from_user)}"
                data-content="${escapeHtml(m.content || m.file_name || m.msgtype)}">回复此人</button>
            </div>
          </div>
        </div>`;
    })
    .join("");

  root.scrollTop = root.scrollHeight;
}

function updateScopeUi() {
  $("scopePrivate").classList.toggle("active", state.chatType === "private");
  $("scopeGroup").classList.toggle("active", state.chatType === "group");
  $("groupSelect").disabled = state.chatType !== "group";
  if (state.chatType === "group") {
    const g = state.groups.find((x) => x.group_id === state.groupId);
    $("scopeHint").textContent = `当前：群聊 ${g?.name || state.groupId}`;
  } else {
    $("scopeHint").textContent = "当前：私聊 user001";
  }
  renderReplyBar();
  renderMessages();
}

function renderGroups() {
  const select = $("groupSelect");
  if (!state.groups.length) return;
  select.innerHTML = state.groups
    .map((g) => `<option value="${escapeHtml(g.group_id)}">${escapeHtml(g.name)} (${escapeHtml(g.group_id)})</option>`)
    .join("");
  if (!state.groups.some((g) => g.group_id === state.groupId)) {
    state.groupId = state.groups[0].group_id;
  }
  select.value = state.groupId;
}

function renderReplyBar() {
  const bar = $("replyBar");
  if (!state.replyTo) {
    bar.classList.add("hidden");
    return;
  }
  bar.classList.remove("hidden");
  $("replyPreview").textContent = `${state.replyTo.name}：${state.replyTo.content || ""}`;
}

function chatPayloadExtras() {
  const extras = {
    chat_type: state.chatType,
    from_user: state.chatType === "group" ? "user002" : "user001",
    from_user_name: state.chatType === "group" ? "群成员乙" : "用户甲",
    agent_id: "1000001",
  };
  if (state.chatType === "group") extras.group_id = state.groupId;
  if (state.replyTo) {
    extras.reply_to_msgid = state.replyTo.msgid;
    extras.reply_to_user = state.replyTo.user;
    extras.mention_user_ids = [state.replyTo.user];
  }
  return extras;
}

function appendChatFormData(fd) {
  const extras = chatPayloadExtras();
  Object.entries(extras).forEach(([k, v]) => {
    if (Array.isArray(v)) fd.append(k, v.join(","));
    else if (v != null) fd.append(k, v);
  });
}

function renderMoments() {
  const root = $("momentsFeed");
  if (!state.moments.length) {
    root.innerHTML = `<div class="empty-state" style="margin: 4vh auto"><strong>还没有朋友圈</strong>上传图片并填写文案/方案后发布。</div>`;
    return;
  }
  root.innerHTML = state.moments
    .map((m) => {
      const likes = (m.likes || []).map((l) => escapeHtml(l.user_name)).join("、") || "暂无";
      const comments = (m.comments || [])
        .map((c) => `<div><strong>${escapeHtml(c.user_name)}</strong>：${escapeHtml(c.content)}</div>`)
        .join("") || "<div>暂无评论</div>";
      return `
        <article class="moment-card" data-id="${escapeHtml(m.moment_id)}">
          <div><strong>${escapeHtml(m.author_name)}</strong> · ${fmtTime(m.create_time)}</div>
          <div style="margin-top:8px;white-space:pre-wrap">${escapeHtml(m.content)}</div>
          ${m.plan ? `<p class="plan">方案：${escapeHtml(m.plan)}</p>` : ""}
          <img src="${escapeHtml(m.image_url)}" alt="朋友圈图片" />
          <div class="like-list">👍 ${likes}</div>
          <div class="comment-list">${comments}</div>
          <div class="moment-actions">
            <input class="input like-user" type="text" value="lead001" placeholder="user_id" />
            <input class="input like-name" type="text" value="潜在客户甲" placeholder="昵称" />
            <button type="button" class="icon-btn btn-like">点赞</button>
            <input class="input comment-text" type="text" placeholder="写评论，如：多少钱？" />
            <button type="button" class="icon-btn btn-comment">评论</button>
          </div>
        </article>`;
    })
    .join("");
}

function renderInteractions() {
  const root = $("interactionsList");
  if (!state.interactions.length) {
    root.innerHTML = `<p class="muted">暂无点赞/评论动态</p>`;
    return;
  }
  root.innerHTML = state.interactions
    .slice()
    .reverse()
    .map((i) => {
      const synced = i.synced_to_crm
        ? `<span class="tag synced">已写入 CRM</span>`
        : `<span class="tag">未同步</span>`;
      return `
        <div class="interaction-item">
          ${synced}
          <span class="tag">${escapeHtml(i.type)}</span>
          <strong>${escapeHtml(i.user_name)}</strong>
          （${escapeHtml(i.user_id)}）
          · ${escapeHtml(i.content || "")}
          <div class="msg-meta">moment: ${escapeHtml(i.moment_id)} · ${fmtTime(i.create_time)}
          ${i.crm_sync_result ? ` · ${escapeHtml(i.crm_sync_result)}` : ""}</div>
        </div>`;
    })
    .join("");
}

function applySession(session) {
  $("sessionId").textContent = session.session_id || "—";
  $("demoBotEnabled").checked = !!session.demo_bot_enabled;
  $("webhookEnabled").checked = !!session.webhook_enabled;
  $("webhookUrl").value = session.webhook_url || "";
  state.messages = session.messages || [];
  state.groups = session.groups || state.groups || [];
  renderGroups();
  updateScopeUi();
}

function upsertMessage(message) {
  const idx = state.messages.findIndex((m) => m.msgid === message.msgid);
  if (idx >= 0) state.messages[idx] = message;
  else state.messages.push(message);
  renderMessages();
}

function upsertMoment(moment) {
  const idx = state.moments.findIndex((m) => m.moment_id === moment.moment_id);
  if (idx >= 0) state.moments[idx] = moment;
  else state.moments.unshift(moment);
  renderMoments();
}

function upsertInteraction(interaction) {
  const idx = state.interactions.findIndex((i) => i.interaction_id === interaction.interaction_id);
  if (idx >= 0) state.interactions[idx] = interaction;
  else state.interactions.push(interaction);
  renderInteractions();
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

async function refreshCrmConfig() {
  const cfg = await api("/api/moments/crm/config");
  $("crmUrl").value = cfg.url || "";
  $("crmEnabled").checked = !!cfg.enabled;
  $("crmAutoSync").checked = cfg.auto_sync !== false;
  $("crmHint").textContent = cfg.auth_configured ? "已配置 Authorization" : "";
}

async function refreshMoments() {
  state.moments = await api("/api/moments");
  renderMoments();
}

async function refreshInteractions() {
  state.interactions = await api("/api/moments/interactions");
  renderInteractions();
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
    } else if (data.type === "moment" && data.moment) {
      upsertMoment(data.moment);
    } else if (data.type === "moment_interaction") {
      if (data.moment) upsertMoment(data.moment);
      if (data.interaction) upsertInteraction(data.interaction);
    } else if (data.type === "moments_cleared") {
      state.moments = [];
      state.interactions = [];
      renderMoments();
      renderInteractions();
    } else if (data.type === "crm_sync_item" && data.interaction) {
      upsertInteraction(data.interaction);
    } else if (data.type === "crm_sync_result" && data.result?.items) {
      data.result.items.forEach(upsertInteraction);
      $("crmHint").textContent = `同步完成：成功 ${data.result.success} / 失败 ${data.result.failed}`;
    }
  });

  setInterval(() => {
    if (ws.readyState === WebSocket.OPEN) ws.send("ping");
  }, 25000);
}

async function sendText(content) {
  const body = { content, ...chatPayloadExtras() };
  await api("/api/messages/text", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  state.replyTo = null;
  renderReplyBar();
}

async function uploadVoice(blob, filename, durationMs) {
  const fd = new FormData();
  fd.append("file", blob, filename);
  appendChatFormData(fd);
  const recognition = $("voiceRecognition").value.trim();
  if (recognition) fd.append("recognition", recognition);
  if (durationMs != null) fd.append("duration_ms", String(durationMs));
  await api("/api/messages/voice", { method: "POST", body: fd });
  state.replyTo = null;
  renderReplyBar();
}

async function uploadChatMedia(path, file) {
  const fd = new FormData();
  fd.append("file", file, file.name);
  appendChatFormData(fd);
  await api(path, { method: "POST", body: fd });
  state.replyTo = null;
  renderReplyBar();
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

function switchView(view) {
  $("tabChat").classList.toggle("active", view === "chat");
  $("tabMoments").classList.toggle("active", view === "moments");
  $("viewChat").classList.toggle("active", view === "chat");
  $("viewMoments").classList.toggle("active", view === "moments");
  if (view === "moments") {
    refreshMoments().catch(console.error);
    refreshInteractions().catch(console.error);
  }
}

function bindEvents() {
  $("tabChat").addEventListener("click", () => switchView("chat"));
  $("tabMoments").addEventListener("click", () => switchView("moments"));

  $("scopePrivate").addEventListener("click", () => {
    state.chatType = "private";
    updateScopeUi();
  });
  $("scopeGroup").addEventListener("click", () => {
    state.chatType = "group";
    updateScopeUi();
  });
  $("groupSelect").addEventListener("change", () => {
    state.groupId = $("groupSelect").value;
    updateScopeUi();
  });
  $("btnCancelReply").addEventListener("click", () => {
    state.replyTo = null;
    renderReplyBar();
  });
  $("chatScroll").addEventListener("click", (e) => {
    const btn = e.target.closest(".btn-reply-msg");
    if (!btn) return;
    state.replyTo = {
      msgid: btn.dataset.msgid,
      user: btn.dataset.user,
      name: btn.dataset.name,
      content: btn.dataset.content,
    };
    renderReplyBar();
  });
  $("btnPickChatImage").addEventListener("click", () => $("chatImageFile").click());
  $("chatImageFile").addEventListener("change", async () => {
    const file = $("chatImageFile").files?.[0];
    if (!file) return;
    try {
      await uploadChatMedia("/api/messages/image", file);
      $("recordHint").textContent = `已发送图片 ${file.name}`;
    } catch (err) {
      alert(err.message || String(err));
    } finally {
      $("chatImageFile").value = "";
    }
  });
  $("btnPickChatFile").addEventListener("click", () => $("chatFile").click());
  $("chatFile").addEventListener("change", async () => {
    const file = $("chatFile").files?.[0];
    if (!file) return;
    try {
      await uploadChatMedia("/api/messages/file", file);
      $("recordHint").textContent = `已发送文件 ${file.name}`;
    } catch (err) {
      alert(err.message || String(err));
    } finally {
      $("chatFile").value = "";
    }
  });

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
    state.moments = [];
    state.interactions = [];
    renderMoments();
    renderInteractions();
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

  $("btnSaveCrm").addEventListener("click", async () => {
    try {
      const cfg = await api("/api/moments/crm/config", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          url: $("crmUrl").value.trim(),
          enabled: $("crmEnabled").checked,
          auto_sync: $("crmAutoSync").checked,
          auth_header: $("crmAuth").value.trim() || null,
        }),
      });
      $("crmHint").textContent = cfg.enabled ? "CRM 配置已保存并启用" : "CRM 配置已保存（未启用）";
    } catch (err) {
      alert(err.message || String(err));
    }
  });

  $("btnSyncCrm").addEventListener("click", async () => {
    try {
      const result = await api("/api/moments/crm/sync", { method: "POST" });
      $("crmHint").textContent = `同步完成：成功 ${result.success} / 失败 ${result.failed}`;
      await refreshInteractions();
    } catch (err) {
      alert(err.message || String(err));
    }
  });

  $("btnManualReply").addEventListener("click", async () => {
    const content = $("manualReply").value.trim();
    if (!content) return;
    const payload = {
      content,
      agent_id: "1000001",
      chat_type: state.chatType,
    };
    if (state.chatType === "group") {
      payload.group_id = state.groupId;
      if (state.replyTo) {
        payload.reply_to_user = state.replyTo.user;
        payload.reply_to_msgid = state.replyTo.msgid;
        payload.mention_user_ids = [state.replyTo.user];
      }
    } else {
      payload.to_user = "user001";
      if (state.replyTo) payload.reply_to_msgid = state.replyTo.msgid;
    }
    await api("/api/reply/text", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    $("manualReply").value = "";
    state.replyTo = null;
    renderReplyBar();
  });

  $("btnPickImage").addEventListener("click", () => $("momentImage").click());
  $("momentImage").addEventListener("change", () => {
    const file = $("momentImage").files?.[0] || null;
    state.momentImageFile = file;
    $("momentImageName").textContent = file ? file.name : "未选择图片";
  });

  $("btnPublishMoment").addEventListener("click", async () => {
    const content = $("momentContent").value.trim();
    const plan = $("momentPlan").value.trim();
    if (!content) {
      alert("请填写朋友圈文案");
      return;
    }
    if (!state.momentImageFile) {
      alert("请选择图片");
      return;
    }
    const fd = new FormData();
    fd.append("image", state.momentImageFile, state.momentImageFile.name);
    fd.append("content", content);
    if (plan) fd.append("plan", plan);
    fd.append("author_id", "seller001");
    fd.append("author_name", "销售顾问");
    try {
      await api("/api/moments", { method: "POST", body: fd });
      $("momentContent").value = "";
      $("momentPlan").value = "";
      $("momentImage").value = "";
      state.momentImageFile = null;
      $("momentImageName").textContent = "未选择图片";
      await refreshMoments();
    } catch (err) {
      alert(err.message || String(err));
    }
  });

  $("momentsFeed").addEventListener("click", async (e) => {
    const card = e.target.closest(".moment-card");
    if (!card) return;
    const momentId = card.dataset.id;
    try {
      if (e.target.classList.contains("btn-like")) {
        const userId = card.querySelector(".like-user").value.trim() || "lead001";
        const userName = card.querySelector(".like-name").value.trim() || "潜在客户甲";
        await api(`/api/moments/${momentId}/likes`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ user_id: userId, user_name: userName }),
        });
        await refreshMoments();
        await refreshInteractions();
      }
      if (e.target.classList.contains("btn-comment")) {
        const userId = card.querySelector(".like-user").value.trim() || "lead001";
        const userName = card.querySelector(".like-name").value.trim() || "潜在客户甲";
        const content = card.querySelector(".comment-text").value.trim();
        if (!content) {
          alert("请输入评论");
          return;
        }
        await api(`/api/moments/${momentId}/comments`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ user_id: userId, user_name: userName, content }),
        });
        card.querySelector(".comment-text").value = "";
        await refreshMoments();
        await refreshInteractions();
      }
    } catch (err) {
      alert(err.message || String(err));
    }
  });

  $("btnRefreshInteractions").addEventListener("click", () => refreshInteractions().catch(console.error));
}

async function refreshGroups() {
  state.groups = await api("/api/groups");
  renderGroups();
  updateScopeUi();
}

bindEvents();
refreshSession().catch(console.error);
refreshGroups().catch(console.error);
refreshCrmConfig().catch(console.error);
connectWs();
