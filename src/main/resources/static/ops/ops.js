(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);

  const state = {
    momentMediaIds: [],
    groupMediaIds: [],
    selectedChatIds: new Set(),
  };

  function toast(text) {
    const el = $("toast");
    el.textContent = text;
    el.classList.add("show");
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => el.classList.remove("show"), 3200);
  }

  function escapeHtml(value) {
    return String(value == null ? "" : value)
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;");
  }

  async function api(path, options) {
    const response = await fetch(path, options);
    const text = await response.text();
    let body = null;
    if (text) {
      try {
        body = JSON.parse(text);
      } catch (e) {
        body = { error: text };
      }
    }
    if (response.status === 401 && body && body.login_required) {
      location.href = "/login";
      throw new Error("登录已过期");
    }
    if (!response.ok) {
      throw new Error((body && (body.error || body.message)) || `请求失败（${response.status}）`);
    }
    return body;
  }

  function postJson(path, payload) {
    return api(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
  }

  function formatTime(seconds) {
    if (!seconds) return "";
    const date = new Date(seconds < 100000000000 ? seconds * 1000 : seconds);
    const pad = (n) => String(n).padStart(2, "0");
    return `${date.getMonth() + 1}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
  }

  function splitList(value) {
    return String(value || "")
      .split(/[,，\s]+/)
      .map((item) => item.trim())
      .filter(Boolean);
  }

  /* —— 自检 —— */

  async function loadPreflight() {
    $("preflightSummary").innerHTML = "检查中…";
    $("preflightList").innerHTML = "";
    try {
      const result = await api("/api/ops/preflight?probe=true");
      const pills = [];
      pills.push(
        result.ready
          ? `<span class="summary-pill ready">全部通过，可以进真实环境</span>`
          : `<span class="summary-pill blocked">${result.failed} 项阻塞</span>`
      );
      if (result.warned) {
        pills.push(`<span class="summary-pill dry">${result.warned} 项提醒</span>`);
      }
      if (result.dry_run) {
        pills.push(`<span class="summary-pill dry">演练模式开启中</span>`);
      }
      $("preflightSummary").innerHTML = pills.join("");

      $("preflightList").innerHTML = (result.checks || [])
        .map((check) => {
          const icon = check.level === "pass" ? "✓" : check.level === "warn" ? "!" : "×";
          const action = check.action
            ? `<div class="action">怎么办：${escapeHtml(check.action)}</div>`
            : "";
          return `<div class="check ${check.level}">
            <div class="icon">${icon}</div>
            <div>
              <div class="name">${escapeHtml(check.name)}</div>
              <div class="detail">${escapeHtml(check.detail)}</div>
              ${action}
            </div>
          </div>`;
        })
        .join("");
    } catch (e) {
      $("preflightSummary").innerHTML = `<span class="summary-pill blocked">检查失败</span>`;
      $("preflightList").innerHTML = `<div class="check fail"><div class="icon">×</div><div>
        <div class="name">无法完成自检</div><div class="detail">${escapeHtml(e.message)}</div></div></div>`;
    }
  }

  async function loadCapabilities() {
    try {
      const caps = await api("/api/ops/capabilities");
      const yes = '<span class="yes">支持</span>';
      const no = '<span class="no">不支持</span>';
      const flag = (value) => (value ? yes : no);

      $("capabilityBar").innerHTML = caps.dry_run
        ? '<span class="pill off">演练模式：出站只记录不真发</span>'
        : '<span class="pill ok">实发模式</span>';

      $("capabilityTable").innerHTML = `
        <table class="caps">
          <thead><tr><th>动作</th><th>个人微信（OpenClaw）</th><th>企业微信（官方 API）</th></tr></thead>
          <tbody>
            <tr><td>一对一私聊收发</td><td>${flag(caps.wechat.direct_message)}</td><td>${flag(caps.wecom.direct_message)}</td></tr>
            <tr><td>发图片 / 文件</td><td>${flag(caps.wechat.media)}</td><td>${flag(caps.wecom.group_message)}</td></tr>
            <tr><td>群里发图文</td><td>${flag(caps.wechat.group_message)}</td><td>${flag(caps.wecom.group_message)}</td></tr>
            <tr><td>发朋友圈</td><td>${flag(caps.wechat.moments)}</td><td>${flag(caps.wecom.moments)}</td></tr>
            <tr><td>朋友圈点赞/评论数</td><td>${flag(caps.wechat.moments)}</td><td>${flag(caps.wecom.moment_stats)}</td></tr>
          </tbody>
        </table>
        <p class="caps-note">个人微信：${escapeHtml(caps.wechat.note)}</p>
        <p class="caps-note">企业微信：${escapeHtml(caps.wecom.note)}</p>`;
    } catch (e) {
      $("capabilityTable").innerHTML = `<p class="caps-note">读取能力信息失败：${escapeHtml(e.message)}</p>`;
    }
  }

  /* —— 素材上传 —— */

  async function uploadImages(files, bucket, listId) {
    const container = $(listId);
    for (const file of files) {
      const form = new FormData();
      form.append("file", file);
      try {
        const result = await api("/api/ops/moments/media", { method: "POST", body: form });
        bucket.push(result.media_id);
        container.insertAdjacentHTML(
          "beforeend",
          `<span class="media-chip">${escapeHtml(file.name)} → ${escapeHtml(result.media_id.slice(0, 18))}…</span>`
        );
      } catch (e) {
        container.insertAdjacentHTML(
          "beforeend",
          `<span class="media-chip failed">${escapeHtml(file.name)} 上传失败：${escapeHtml(e.message)}</span>`
        );
      }
    }
  }

  /* —— 朋友圈 —— */

  async function createMoment() {
    // 服务端 JSON 统一 snake_case，字段名写错会被静默忽略，务必与后端保持一致
    const payload = {
      text: $("momentText").value.trim(),
      image_media_ids: state.momentMediaIds,
      link_title: $("momentLinkTitle").value.trim(),
      link_url: $("momentLinkUrl").value.trim(),
      sender_user_ids: splitList($("momentSenders").value),
      customer_tag_ids: splitList($("momentTags").value),
    };
    const box = $("momentTaskResult");
    box.hidden = false;
    box.className = "result-box";
    box.textContent = "创建中…";

    try {
      const created = await postJson("/api/ops/moments/tasks", payload);
      if (created.dry_run) {
        box.textContent = "演练模式：任务未真正创建。关闭 dry-run 后再试。";
        return;
      }
      box.textContent = `任务已提交，jobid=${created.job_id}，查询创建结果中…`;
      const status = await pollMomentTask(created.job_id);
      box.innerHTML = `任务状态：${escapeHtml(status.status_text)}${
        status.moment_id ? `，moment_id=<code>${escapeHtml(status.moment_id)}</code>` : ""
      }<br />成员需在企业微信客户端确认后才会真正发表。`;
      if (status.invalid_senders && status.invalid_senders.length) {
        box.innerHTML += `<br />不合法的执行者：${escapeHtml(status.invalid_senders.join(", "))}`;
      }
    } catch (e) {
      box.className = "result-box error";
      box.textContent = e.message;
    }
  }

  async function pollMomentTask(jobId) {
    let status = null;
    for (let i = 0; i < 10; i++) {
      status = await api(`/api/ops/moments/tasks/${encodeURIComponent(jobId)}`);
      // status=3 表示创建完成
      if (status.status === 3) return status;
      await new Promise((resolve) => setTimeout(resolve, 1500));
    }
    return status;
  }

  /* —— 互动数据 —— */

  async function loadStats(refresh) {
    const listEl = $("statsList");
    listEl.innerHTML = "加载中…";
    try {
      const data = refresh
        ? await postJson("/api/ops/moments/stats/refresh", {})
        : await api("/api/ops/moments/stats");
      const overview = data.overview || {};
      const items = refresh ? (await api("/api/ops/moments/stats")).items : data.items;

      $("statsOverview").innerHTML = [
        statBox("朋友圈条数", overview.moment_count),
        statBox("点赞合计", overview.like_count),
        statBox("评论合计", overview.comment_count),
        statBox("客户点赞", overview.customer_like_count),
        statBox("客户评论", overview.customer_comment_count),
      ].join("");

      if (!items || !items.length) {
        listEl.innerHTML = `<div class="empty">最近 ${overview.lookback_days || 7} 天没有发表记录，或客户联系未配置。</div>`;
        return;
      }

      listEl.innerHTML = items.map(renderMoment).join("");
    } catch (e) {
      listEl.innerHTML = `<div class="result-box error">${escapeHtml(e.message)}</div>`;
    }
  }

  function statBox(label, value) {
    return `<div class="stat-box"><div class="label">${escapeHtml(label)}</div>
      <div class="value">${value == null ? 0 : value}</div></div>`;
  }

  function renderMoment(item) {
    const comments = (item.comments || [])
      .slice(0, 8)
      .map(
        (comment) =>
          `<div class="comment-item"><span class="who">${escapeHtml(
            comment.external_user_id || comment.user_id || "未知"
          )}</span>：${escapeHtml(comment.content)}　<span>${formatTime(comment.create_time)}</span></div>`
      )
      .join("");

    const customerNote =
      item.customer_like_count || item.customer_comment_count
        ? `<span class="time">其中客户 赞 ${item.customer_like_count} · 评论 ${item.customer_comment_count}</span>`
        : "";

    return `<div class="moment-row">
      <div class="head">
        <span class="creator">${escapeHtml(item.creator || "未知成员")}</span>
        <span class="time">${formatTime(item.create_time)}</span>
        <span class="counts">
          <span class="like">赞 ${item.like_count}</span>
          <span class="comment">评论 ${item.comment_count}</span>
        </span>
      </div>
      <div class="text">${escapeHtml(item.text || "（无文案）")}</div>
      ${customerNote ? `<div class="text">${customerNote}</div>` : ""}
      ${item.error ? `<div class="err">${escapeHtml(item.error)}</div>` : ""}
      ${comments ? `<div class="comments">${comments}</div>` : ""}
    </div>`;
  }

  /* —— 客户群群发 —— */

  async function loadGroups() {
    const listEl = $("groupList");
    listEl.innerHTML = "加载中…";
    try {
      const groups = await api("/api/ops/groups?limit=100");
      if (!groups.length) {
        listEl.innerHTML = `<div class="empty">没有取到客户群。检查应用是否有客户联系权限、群主是否在可见范围内。</div>`;
        return;
      }
      listEl.innerHTML = groups
        .map(
          (group) => `<label class="group-item">
            <input type="checkbox" value="${escapeHtml(group.chat_id)}" />
            <span>${escapeHtml(group.name || "（未命名群聊）")}</span>
            <span class="chat-id">${escapeHtml(group.chat_id)}${
            group.member_count ? ` · ${group.member_count} 人` : ""
          }</span>
          </label>`
        )
        .join("");
    } catch (e) {
      listEl.innerHTML = `<div class="result-box error">${escapeHtml(e.message)}</div>`;
    }
  }

  async function sendToGroups() {
    const chatIds = Array.from($("groupList").querySelectorAll("input[type=checkbox]:checked")).map(
      (input) => input.value
    );
    const box = $("groupResult");
    box.hidden = false;
    box.className = "result-box";

    if (!chatIds.length) {
      box.className = "result-box error";
      box.textContent = "请至少勾选一个客户群";
      return;
    }

    box.textContent = "提交中…";
    try {
      const result = await postJson("/api/ops/groups/messages", {
        chat_ids: chatIds,
        text: $("groupText").value.trim(),
        images: state.groupMediaIds.map((mediaId) => ({ media_id: mediaId })),
        link_title: $("groupLinkTitle").value.trim(),
        link_url: $("groupLinkUrl").value.trim(),
        link_description: $("groupLinkDesc").value.trim(),
        sender: $("groupSender").value.trim(),
      });
      let html = escapeHtml(result.note);
      if (result.msg_id) html += `<br />msgid=<code>${escapeHtml(result.msg_id)}</code>`;
      if (result.fail_list && result.fail_list.length) {
        html += `<br />失败列表：${escapeHtml(result.fail_list.join(", "))}`;
      }
      box.innerHTML = html;
    } catch (e) {
      box.className = "result-box error";
      box.textContent = e.message;
    }
  }

  /* —— 绑定 —— */

  function bind() {
    $("opsTabs").addEventListener("click", (event) => {
      const button = event.target.closest("button[data-tab]");
      if (!button) return;
      document.querySelectorAll("#opsTabs button").forEach((el) => el.classList.remove("on"));
      button.classList.add("on");
      document.querySelectorAll(".ops-pane").forEach((pane) => pane.classList.remove("on"));
      $(`pane-${button.dataset.tab}`).classList.add("on");
      if (button.dataset.tab === "stats") loadStats(false);
    });

    $("btnPreflight").addEventListener("click", loadPreflight);

    $("momentImages").addEventListener("change", (event) =>
      uploadImages(Array.from(event.target.files || []), state.momentMediaIds, "momentMediaList")
    );
    $("btnCreateMoment").addEventListener("click", createMoment);

    $("btnRefreshStats").addEventListener("click", async () => {
      toast("正在刷新，条数多时需要一点时间…");
      await loadStats(true);
      toast("互动数据已刷新");
    });

    $("btnLoadGroups").addEventListener("click", loadGroups);
    $("groupImages").addEventListener("change", (event) =>
      uploadImages(Array.from(event.target.files || []), state.groupMediaIds, "groupMediaList")
    );
    $("btnSendGroups").addEventListener("click", sendToGroups);
  }

  bind();
  loadCapabilities();
  loadPreflight();
})();
