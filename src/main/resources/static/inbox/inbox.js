(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);

  const state = {
    status: null,
    conversations: new Map(),
    messages: new Map(),
    activeId: null,
    filter: "all",
    search: "",
    sending: false,
  };

  const CHANNEL_LABEL = {
    wechat_kf: "微信用户",
    wecom_app: "企微成员",
    demo: "本地演示",
  };

  function toast(text) {
    const el = $("toast");
    el.textContent = text;
    el.classList.add("show");
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => el.classList.remove("show"), 2600);
  }

  async function api(path, options) {
    const response = await fetch(path, Object.assign({ headers: { "Content-Type": "application/json" } }, options));
    let body = null;
    const text = await response.text();
    if (text) {
      try {
        body = JSON.parse(text);
      } catch (e) {
        body = { error: text };
      }
    }
    if (!response.ok) {
      const message = (body && (body.error || body.message)) || `请求失败（${response.status}）`;
      throw new Error(message);
    }
    return body;
  }

  function formatTime(millis) {
    if (!millis) return "";
    const date = new Date(millis);
    const now = new Date();
    const hh = String(date.getHours()).padStart(2, "0");
    const mm = String(date.getMinutes()).padStart(2, "0");
    if (date.toDateString() === now.toDateString()) {
      return `${hh}:${mm}`;
    }
    return `${date.getMonth() + 1}-${String(date.getDate()).padStart(2, "0")} ${hh}:${mm}`;
  }

  function initials(name) {
    if (!name) return "?";
    const trimmed = String(name).trim();
    return trimmed ? trimmed.slice(0, 1) : "?";
  }

  function channelClass(channel) {
    if (channel === "wechat_kf") return "kf";
    if (channel === "demo") return "demo";
    return "";
  }

  /* —— 状态渲染 —— */

  /** 未读与会话数以本地会话为准，避免标记已读后右侧计数和标签页标题不刷新。 */
  function derivedCounts() {
    let unread = 0;
    state.conversations.forEach((conversation) => {
      unread += conversation.unread || 0;
    });
    return { unread: unread, conversations: state.conversations.size };
  }

  function renderStatus(status) {
    if (!status) return;
    state.status = status;

    const counts = derivedCounts();
    const kf = status.wechat_kf || {};
    const app = status.wecom_app || {};
    const pills = [];

    if (!status.enabled) {
      pills.push(pill("off", "官方通道未启用"));
    }
    pills.push(pill(kf.configured ? "ok" : "off", `微信客服 ${kf.configured ? "已接入" : "未配置"}`));
    pills.push(pill(app.configured ? "ok" : "off", `企微应用 ${app.configured ? "已接入" : "未配置"}`));
    if (status.demo_inbox) {
      pills.push(pill("off", "演示通道开启"));
    }
    $("channelStatus").innerHTML = pills.join("");

    const rows = [
      statusRow("总开关", status.enabled ? "已开启" : "未开启", status.enabled ? "ok" : "off"),
      statusRow("企业 ID", status.corp_id_configured ? "已配置" : "未配置", status.corp_id_configured ? "ok" : "off"),
      statusRow("微信客服", kf.configured ? "已配置" : "未配置", kf.configured ? "ok" : "off"),
      statusRow(
        "接待人员",
        kf.servicer_configured ? (kf.auto_take_over ? "自动接入人工" : "已配置") : "未配置",
        kf.servicer_configured ? "ok" : "warn"
      ),
      statusRow("企微应用", app.configured ? `AgentId ${app.agent_id}` : "未配置", app.configured ? "ok" : "off"),
      statusRow("会话数", String(counts.conversations), "ok"),
      statusRow("未读", String(counts.unread), counts.unread ? "warn" : "off"),
    ];
    $("statusDetail").innerHTML = rows.join("");

    const origin = location.origin;
    $("kfCallback").textContent = origin + (kf.callback_path || "/callback/wecom/kf");
    $("appCallback").textContent = origin + (app.callback_path || "/callback/wecom/app");
    $("demoCard").hidden = !status.demo_inbox;

    document.title = counts.unread ? `(${counts.unread}) 统一收件箱` : "统一收件箱 · 企微 / 微信客服";
  }

  function pill(kind, text) {
    return `<span class="pill ${kind}">${escapeHtml(text)}</span>`;
  }

  function statusRow(label, value, kind) {
    return `<div class="status-row"><span class="label">${escapeHtml(label)}</span>
      <span class="value ${kind}">${escapeHtml(value)}</span></div>`;
  }

  function escapeHtml(value) {
    return String(value == null ? "" : value)
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;");
  }

  /* —— 会话列表 —— */

  function upsertConversation(conversation) {
    if (!conversation || !conversation.id) return;
    state.conversations.set(conversation.id, conversation);
  }

  function visibleConversations() {
    const list = Array.from(state.conversations.values());
    const keyword = state.search.trim();
    return list
      .filter((item) => {
        if (state.filter === "unread") return (item.unread || 0) > 0;
        if (state.filter !== "all") return item.channel === state.filter;
        return true;
      })
      .filter((item) => {
        if (!keyword) return true;
        const haystack = `${item.peer_name || ""}${item.peer_id || ""}${item.last_preview || ""}`;
        return haystack.includes(keyword);
      })
      .sort((a, b) => (b.last_message_at || 0) - (a.last_message_at || 0));
  }

  function renderSessions() {
    const list = visibleConversations();
    const container = $("sessionList");
    if (!list.length) {
      container.innerHTML = `<div class="empty">暂无会话</div>`;
      return;
    }
    container.innerHTML = list
      .map((item) => {
        const name = item.peer_name || item.peer_id || "";
        const tagClass = item.channel === "wechat_kf" ? "kf" : item.channel === "wecom_app" ? "app" : "";
        return `<div class="session ${item.id === state.activeId ? "active" : ""}" data-id="${escapeHtml(item.id)}">
          <div class="avatar ${channelClass(item.channel)}">${escapeHtml(initials(name))}</div>
          <div class="session-main">
            <div class="session-name">${escapeHtml(name)}</div>
            <div class="session-preview">${escapeHtml(item.last_preview || "")}</div>
            <span class="tag ${tagClass}">${escapeHtml(CHANNEL_LABEL[item.channel] || item.channel)}</span>
          </div>
          <div class="session-side">
            <div class="session-time">${formatTime(item.last_message_at)}</div>
            ${item.unread ? `<div class="badge">${item.unread > 99 ? "99+" : item.unread}</div>` : ""}
          </div>
        </div>`;
      })
      .join("");
  }

  /* —— 消息区 —— */

  function messageMap(conversationId) {
    let map = state.messages.get(conversationId);
    if (!map) {
      map = new Map();
      state.messages.set(conversationId, map);
    }
    return map;
  }

  function upsertMessage(message) {
    if (!message || !message.conversation_id) return;
    messageMap(message.conversation_id).set(message.id, message);
  }

  function renderMessages() {
    const container = $("messages");
    if (!state.activeId) {
      container.innerHTML = `<div class="empty">还没有选中会话。收到新消息时左侧会自动出现并提醒。</div>`;
      return;
    }

    const list = Array.from(messageMap(state.activeId).values()).sort(
      (a, b) => (a.create_time || 0) - (b.create_time || 0)
    );
    if (!list.length) {
      container.innerHTML = `<div class="empty">这个会话还没有消息。</div>`;
      return;
    }

    const atBottom =
      container.scrollHeight - container.scrollTop - container.clientHeight < 80;

    container.innerHTML = list.map(renderRow).join("");

    if (atBottom) {
      container.scrollTop = container.scrollHeight;
    }
  }

  function renderRow(message) {
    const direction = message.direction || "inbound";
    if (direction === "system") {
      return `<div class="row sys"><div class="bubble">${escapeHtml(message.content)}
        <div class="meta">${formatTime(message.create_time)}</div></div></div>`;
    }

    const out = direction === "outbound";
    const name = out ? "我" : message.sender_name || message.sender_id || "";
    const stateLabel = out ? renderSendState(message) : "";
    const failure =
      out && message.send_state === "failed" && message.error_message
        ? `<div class="fail-reason">${escapeHtml(message.error_message)}</div>`
        : "";

    return `<div class="row ${out ? "out" : ""}">
      <div class="avatar ${out ? "" : channelClass(message.channel)}">${escapeHtml(initials(name))}</div>
      <div>
        <div class="bubble">${escapeHtml(message.content)}${failure}</div>
        <div class="meta">${formatTime(message.create_time)}${stateLabel}</div>
      </div>
    </div>`;
  }

  function renderSendState(message) {
    const map = {
      sent: ['<span class="state sent">已发送</span>'],
      failed: ['<span class="state failed">发送失败</span>'],
      local: ['<span class="state local">仅本地</span>'],
      none: ['<span class="state pending">发送中…</span>'],
    };
    const entry = map[message.send_state] || map.none;
    return entry[0];
  }

  function renderHeader() {
    const conversation = state.conversations.get(state.activeId);
    const notice = $("chatNotice");

    if (!conversation) {
      $("peerAvatar").textContent = "—";
      $("peerAvatar").className = "avatar";
      $("peerName").textContent = "请选择左侧会话";
      $("peerMeta").textContent = "收到新消息会自动出现在这里，无需刷新";
      $("btnTakeOver").hidden = true;
      notice.hidden = true;
      setComposerEnabled(false, "选择会话后即可直接回复，消息通过官方接口发出。");
      return;
    }

    const name = conversation.peer_name || conversation.peer_id || "";
    $("peerAvatar").textContent = initials(name);
    $("peerAvatar").className = `avatar ${channelClass(conversation.channel)}`;
    $("peerName").textContent = name;

    const metaParts = [CHANNEL_LABEL[conversation.channel] || conversation.channel];
    if (conversation.peer_id) metaParts.push(conversation.peer_id);
    if (conversation.service_state != null) {
      metaParts.push(serviceStateLabel(conversation.service_state));
    }
    $("peerMeta").textContent = metaParts.join(" · ");

    $("btnTakeOver").hidden = conversation.channel !== "wechat_kf";

    const warning = replyWarning(conversation);
    if (warning) {
      notice.hidden = false;
      notice.className = "notice";
      notice.textContent = warning;
    } else {
      notice.hidden = true;
    }

    if (conversation.channel === "demo") {
      setComposerEnabled(true, "演示通道：内容只记录在本地，不会发往真实用户。");
    } else if (!channelReady(conversation.channel)) {
      setComposerEnabled(false, "该通道尚未配置官方凭据，无法发送。请先在环境变量中配置后重启服务。");
    } else {
      setComposerEnabled(true, "Enter 发送，Shift+Enter 换行。消息通过官方接口直接发给对方。");
    }
  }

  function serviceStateLabel(value) {
    const map = {
      0: "未处理",
      1: "智能助手接待",
      2: "排队中",
      3: "人工接待中",
      4: "已结束",
    };
    return map[value] || `状态 ${value}`;
  }

  function channelReady(channel) {
    if (!state.status) return false;
    if (channel === "wechat_kf") return !!(state.status.wechat_kf && state.status.wechat_kf.configured);
    if (channel === "wecom_app") return !!(state.status.wecom_app && state.status.wecom_app.configured);
    return true;
  }

  function replyWarning(conversation) {
    if (conversation.channel !== "wechat_kf" || !state.status) return "";
    const hours = (state.status.wechat_kf && state.status.wechat_kf.reply_window_hours) || 48;
    const last = conversation.last_inbound_at || 0;
    if (!last) return "";
    const elapsedHours = (Date.now() - last) / 3600000;
    if (elapsedHours > hours) {
      return `客户最后一条消息已超过 ${hours} 小时，按官方规则暂不能主动发送，需等客户再次发起会话。`;
    }
    if (elapsedHours > hours - 6) {
      return `距官方 ${hours} 小时可回复窗口结束还有约 ${Math.max(0, Math.round(hours - elapsedHours))} 小时。`;
    }
    return "";
  }

  function setComposerEnabled(enabled, hint) {
    $("replyInput").disabled = !enabled;
    $("btnSend").disabled = !enabled || state.sending;
    $("composerHint").textContent = hint;
  }

  /* —— 交互 —— */

  async function selectConversation(id) {
    state.activeId = id;
    renderSessions();
    renderHeader();

    try {
      const messages = await api(`/api/inbox/conversations/${encodeURIComponent(id)}/messages?limit=300`);
      const map = messageMap(id);
      map.clear();
      (messages || []).forEach((message) => map.set(message.id, message));
      renderMessages();
      $("messages").scrollTop = $("messages").scrollHeight;
    } catch (e) {
      toast(e.message);
    }

    markRead(id);
  }

  async function markRead(id) {
    const conversation = state.conversations.get(id);
    if (!conversation || !conversation.unread) return;
    try {
      await api(`/api/inbox/conversations/${encodeURIComponent(id)}/read`, { method: "POST" });
      conversation.unread = 0;
      renderSessions();
      renderStatus(state.status);
    } catch (e) {
      // 忽略：不影响主流程
    }
  }

  async function send() {
    const input = $("replyInput");
    const text = input.value.trim();
    if (!text || !state.activeId || state.sending) return;

    state.sending = true;
    $("btnSend").disabled = true;

    try {
      const message = await api(`/api/inbox/conversations/${encodeURIComponent(state.activeId)}/reply`, {
        method: "POST",
        body: JSON.stringify({ text: text }),
      });
      input.value = "";
      upsertMessage(message);
      renderMessages();
      $("messages").scrollTop = $("messages").scrollHeight;
      if (message.send_state === "failed") {
        toast(message.error_message || "发送失败");
      }
    } catch (e) {
      toast(e.message);
    } finally {
      state.sending = false;
      renderHeader();
    }
  }

  /* —— 实时连接 —— */

  function connectWs() {
    const proto = location.protocol === "https:" ? "wss" : "ws";
    let ws;
    try {
      ws = new WebSocket(`${proto}://${location.host}/ws/inbox`);
    } catch (e) {
      setTimeout(connectWs, 3000);
      return;
    }

    ws.addEventListener("open", () => {
      $("connState").textContent = "实时已连接";
      $("connState").className = "conn live";
    });

    ws.addEventListener("message", (event) => {
      let data;
      try {
        data = JSON.parse(event.data);
      } catch (e) {
        return;
      }
      handleEvent(data);
    });

    ws.addEventListener("close", () => {
      $("connState").textContent = "连接断开，重连中…";
      $("connState").className = "conn down";
      setTimeout(connectWs, 2500);
    });

    ws.addEventListener("error", () => {
      $("connState").textContent = "连接异常";
      $("connState").className = "conn down";
    });

    const ping = setInterval(() => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send("ping");
      } else {
        clearInterval(ping);
      }
    }, 25000);
  }

  function handleEvent(event) {
    const payload = event.payload || {};

    if (event.type === "snapshot") {
      renderStatus(payload.status);
      state.conversations.clear();
      (payload.conversations || []).forEach(upsertConversation);
      renderSessions();
      if (!state.activeId) {
        const first = visibleConversations()[0];
        if (first) {
          selectConversation(first.id);
          return;
        }
      }
      renderHeader();
      renderMessages();
      return;
    }

    if (event.type === "message") {
      const message = payload.message;
      const conversation = payload.conversation;
      if (conversation) upsertConversation(conversation);
      if (message) upsertMessage(message);

      renderStatus(state.status);
      renderSessions();
      if (message && message.conversation_id === state.activeId) {
        renderMessages();
        renderHeader();
        if (message.direction === "inbound") {
          markRead(state.activeId);
        }
      } else if (message && message.direction === "inbound" && conversation) {
        toast(`${conversation.peer_name || conversation.peer_id}：${message.content}`.slice(0, 60));
      }
      return;
    }

    if (event.type === "conversation") {
      if (payload.conversation) {
        upsertConversation(payload.conversation);
        renderSessions();
        renderStatus(state.status);
        if (payload.conversation.id === state.activeId) {
          renderHeader();
        }
      }
    }
  }

  /* —— 绑定 —— */

  function bind() {
    $("sessionList").addEventListener("click", (event) => {
      const item = event.target.closest(".session");
      if (item) selectConversation(item.dataset.id);
    });

    $("filters").addEventListener("click", (event) => {
      const chip = event.target.closest(".chip");
      if (!chip) return;
      document.querySelectorAll("#filters .chip").forEach((el) => el.classList.remove("on"));
      chip.classList.add("on");
      state.filter = chip.dataset.filter;
      renderSessions();
    });

    $("searchInput").addEventListener("input", (event) => {
      state.search = event.target.value || "";
      renderSessions();
    });

    $("btnSend").addEventListener("click", send);

    $("replyInput").addEventListener("keydown", (event) => {
      if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        send();
      }
    });

    $("btnSync").addEventListener("click", async () => {
      try {
        const result = await api("/api/inbox/sync", { method: "POST" });
        toast(`同步完成，新增 ${result.imported || 0} 条`);
      } catch (e) {
        toast(e.message);
      }
    });

    $("btnTakeOver").addEventListener("click", async () => {
      if (!state.activeId) return;
      try {
        await api(`/api/inbox/conversations/${encodeURIComponent(state.activeId)}/take-over`, {
          method: "POST",
          body: JSON.stringify({}),
        });
        toast("已接入人工接待");
      } catch (e) {
        toast(e.message);
      }
    });

    document.querySelectorAll("[data-copy]").forEach((button) => {
      button.addEventListener("click", async () => {
        const text = $(button.dataset.copy).textContent;
        try {
          await navigator.clipboard.writeText(text);
          toast("已复制回调地址");
        } catch (e) {
          toast(text);
        }
      });
    });

    $("btnDemoInbound").addEventListener("click", async () => {
      const text = $("demoText").value.trim();
      try {
        await api("/api/inbox/demo/inbound", {
          method: "POST",
          body: JSON.stringify({ text: text || "这是一条演示消息" }),
        });
        $("demoText").value = "";
      } catch (e) {
        toast(e.message);
      }
    });
  }

  async function boot() {
    bind();
    try {
      const [status, conversations] = await Promise.all([
        api("/api/inbox/status"),
        api("/api/inbox/conversations"),
      ]);
      renderStatus(status);
      (conversations || []).forEach(upsertConversation);
      renderSessions();
      const first = visibleConversations()[0];
      if (first) {
        await selectConversation(first.id);
      } else {
        renderHeader();
        renderMessages();
      }
    } catch (e) {
      toast(e.message);
    }
    connectWs();
  }

  boot();
})();
