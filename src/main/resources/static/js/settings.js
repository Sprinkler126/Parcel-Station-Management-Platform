(function () {
  const { api, toast, errorMessage, escapeHtml, formatTime, setBusy } = Station;
  const operator = document.querySelector("#settingsOperator");
  const rows = document.querySelector("#spaceRows");
  let spaces = [];
  let selected = null;
  let policy = null;
  operator.value = localStorage.getItem("station.operator") || "站长李";
  operator.addEventListener("change", () => localStorage.setItem("station.operator", operator.value.trim()));

  function tierTag(tier) {
    const css = tier === "NORMAL" ? "tag-ok" : tier === "TIGHT" ? "tag-warn" : "tag-alert";
    return `<span class="tag ${css}">${escapeHtml(tier)}</span>`;
  }

  function render() {
    document.querySelector("#spaceTotal").textContent = spaces.length;
    document.querySelector("#spaceEnabled").textContent = spaces.filter(item => item.enabled).length;
    document.querySelector("#spaceManual").textContent = spaces.filter(item => item.cooldownMode === "MANUAL").length;
    document.querySelector("#spaceRisk").textContent = spaces.filter(item => item.tier !== "NORMAL").length;
    rows.innerHTML = spaces.map(item => `<tr class="${item.enabled ? "" : "disabled-row"}"><td><strong>${escapeHtml(item.prefix)}</strong><br><span class="muted small">下一码 ${escapeHtml(item.nextCode || "—")}</span></td><td>${item.inStock + item.cooling} / ${item.capacity}<br><span class="muted small">可用 ${item.available}</span></td><td><strong>${item.cooldownDays} 天</strong><br><span class="muted small">${escapeHtml(item.cooldownMode)}</span></td><td>${tierTag(item.tier)}</td><td><span class="tag ${item.enabled ? "tag-ok" : ""}">${item.enabled ? "启用" : "停用"}</span></td><td><button class="btn btn-sm" data-edit="${escapeHtml(item.prefix)}">配置</button></td></tr>`).join("");
    document.querySelector("#spaceEmpty").hidden = spaces.length > 0;
  }

  async function loadSpaces(selectPrefix) {
    try {
      spaces = await api("/code-spaces/all");
      render();
      const prefix = selectPrefix || (selected && selected.prefix);
      if (prefix) selectSpace(prefix);
    } catch (error) { toast(errorMessage(error), "error"); }
  }

  function selectSpace(prefix) {
    selected = spaces.find(item => item.prefix === prefix);
    if (!selected) return;
    document.querySelector("#editorEmpty").hidden = true;
    document.querySelector("#editor").hidden = false;
    document.querySelector("#editorPrefix").textContent = selected.prefix;
    document.querySelector("#editorState").textContent = selected.enabled ? "启用中" : "已停用";
    document.querySelector("#editorState").className = `tag ${selected.enabled ? "tag-ok" : ""}`;
    document.querySelector("#editCapacity").value = selected.capacity;
    document.querySelector("#editEnabled").checked = selected.enabled;
    document.querySelector("#editCooldownMode").value = selected.cooldownMode;
    document.querySelector("#editCooldownDays").value = selected.cooldownDays;
    if (policy) {
      document.querySelector("#editCooldownDays").min = policy.minDays;
      document.querySelector("#editCooldownDays").max = policy.maxDays;
    }
    toggleCooldownField();
    renderCalculation();
    loadLogs();
  }

  function fillPolicyForm() {
    document.querySelector("#policyMinDays").value = policy.minDays;
    document.querySelector("#policyMaxDays").value = policy.maxDays;
    document.querySelector("#policyDefaultDays").value = policy.defaultDays;
    document.querySelector("#policyBufferDays").value = policy.bufferDays;
    document.querySelector("#policyTight").value = Math.round(policy.tightThreshold * 100);
    document.querySelector("#policyEmergency").value = Math.round(policy.emergencyThreshold * 100);
    document.querySelector("#policyWindow").value = policy.statWindowDays;
    document.querySelector("#policyAlpha").value = policy.ewmaAlpha;
    document.querySelector("#newCooldownDays").min = policy.minDays;
    document.querySelector("#newCooldownDays").max = policy.maxDays;
    document.querySelector("#newCooldownDays").value = policy.defaultDays;
    document.querySelector("#policySavedAt").textContent = policy.updatedAt
      ? `${formatTime(policy.updatedAt)} · ${policy.operator || "系统"}` : "当前生效规则";
  }

  async function loadPolicy() {
    policy = await api("/settings/cooldown");
    fillPolicyForm();
    renderCalculation();
  }

  function renderCalculation() {
    const box = document.querySelector("#liveCalculation");
    if (!selected || !policy) { box.innerHTML = ""; return; }
    const ratio = selected.availableRatio;
    const buffer = Math.ceil(selected.dailyInbound * policy.bufferDays);
    const numerator = selected.capacity - selected.inStock - buffer;
    const raw = Math.floor(numerator / Math.max(selected.dailyPickup, 1));
    const target = Math.max(policy.minDays, Math.min(policy.maxDays, raw));
    let result;
    if (ratio < policy.emergencyThreshold) result = `${policy.minDays} 天（可用率低于紧急线）`;
    else if (ratio < policy.tightThreshold) result = `${policy.minDays} 天（可用率低于紧张线）`;
    else if (target < selected.cooldownDays) result = `${target} 天（容量收紧，立即下调）`;
    else {
      const hysteresis = Math.max(2, Math.floor(selected.cooldownDays * 0.2));
      result = target - selected.cooldownDays < hysteresis
        ? `${selected.cooldownDays} 天（处于 ${hysteresis} 天滞回带，保持）`
        : `${Math.min(selected.cooldownDays + 1, policy.maxDays)} 天（理论 ${target} 天，本次只 +1）`;
    }
    box.innerHTML = `<h4>${escapeHtml(selected.prefix)} 本次推导</h4><div class="calculation-grid"><span>容量 ${selected.capacity}</span><span>在库 ${selected.inStock}</span><span>日均入库 ${selected.dailyInbound}</span><span>日均取件 ${selected.dailyPickup}</span><span>当前可用率 ${(ratio * 100).toFixed(1)}%</span><span>当前 ${selected.cooldownDays} 天</span></div><code>buffer = ceil(${selected.dailyInbound} × ${policy.bufferDays}) = ${buffer}</code><code>raw = floor((${selected.capacity} − ${selected.inStock} − ${buffer}) ÷ max(${selected.dailyPickup}, 1)) = ${raw}</code><strong>自动计算结果：${result}</strong>${selected.cooldownMode === "MANUAL" ? `<div class="muted">当前为手动模式，此结果只作参考，不会覆盖手动值。</div>` : ""}`;
  }

  function toggleCooldownField() {
    const manual = document.querySelector("#editCooldownMode").value === "MANUAL";
    document.querySelector("#editCooldownField").classList.toggle("is-hidden", !manual);
    document.querySelector("#editCooldownDays").required = manual;
  }

  async function loadLogs() {
    if (!selected) return;
    const container = document.querySelector("#policyLogs");
    container.innerHTML = `<div class="muted small">加载中…</div>`;
    try {
      const logs = await api(`/code-spaces/${encodeURIComponent(selected.prefix)}/policy-logs?limit=8`);
      container.innerHTML = logs.length ? logs.map(log => `<article><div><strong>${log.oldDays} → ${log.newDays} 天</strong>${tierTag(log.tier)}</div><p>${escapeHtml(log.reason)}</p><span>${formatTime(log.decidedAt)} · 可用 ${log.available}/${log.capacity}</span></article>`).join("") : `<div class="muted small">暂无策略日志</div>`;
    } catch (error) { container.innerHTML = `<div class="muted small">日志加载失败</div>`; }
  }

  document.querySelector("#showCreate").onclick = () => { document.querySelector("#createSpaceForm").classList.remove("is-hidden"); document.querySelector("#newShelf").focus(); };
  document.querySelector("#cancelCreate").onclick = () => document.querySelector("#createSpaceForm").classList.add("is-hidden");
  document.querySelector("#newCooldownMode").onchange = event => document.querySelector("#newCooldownField").classList.toggle("is-hidden", event.target.value !== "MANUAL");
  rows.addEventListener("click", event => { const button = event.target.closest("button[data-edit]"); if (button) selectSpace(button.dataset.edit); });

  document.querySelector("#createSpaceForm").addEventListener("submit", async event => {
    event.preventDefault();
    const button = document.querySelector("#createSpaceButton");
    const manual = document.querySelector("#newCooldownMode").value === "MANUAL";
    const payload = { shelfNo: document.querySelector("#newShelf").value.trim(), rowNo: document.querySelector("#newRow").value.trim(), capacity: Number(document.querySelector("#newCapacity").value), cooldownDays: manual ? Number(document.querySelector("#newCooldownDays").value) : null, operator: operator.value.trim() || null };
    setBusy(button, true, "创建中…");
    try {
      const created = await api("/code-spaces", { method: "POST", body: JSON.stringify(payload) });
      toast(`货架排 ${created.prefix} 已创建，现在入库可选择货架 ${payload.shelfNo}`);
      document.querySelector("#createSpaceForm").reset();
      document.querySelector("#newRow").value = "1"; document.querySelector("#newCapacity").value = "9999";
      document.querySelector("#createSpaceForm").classList.add("is-hidden");
      await loadSpaces(created.prefix);
    } catch (error) { toast(errorMessage(error), "error"); }
    finally { setBusy(button, false); }
  });

  document.querySelector("#basicSettingsForm").addEventListener("submit", async event => {
    event.preventDefault(); if (!selected) return;
    const button = document.querySelector("#saveBasic"); setBusy(button, true, "保存中…");
    try {
      await api(`/code-spaces/${encodeURIComponent(selected.prefix)}/settings`, { method: "PUT", body: JSON.stringify({ capacity: Number(document.querySelector("#editCapacity").value), enabled: document.querySelector("#editEnabled").checked }) });
      toast(`${selected.prefix} 的容量与状态已更新`); await loadSpaces(selected.prefix);
    } catch (error) { toast(errorMessage(error), "error"); }
    finally { setBusy(button, false); }
  });

  document.querySelector("#editCooldownMode").onchange = toggleCooldownField;
  document.querySelector("#policySettingsForm").addEventListener("submit", async event => {
    event.preventDefault();
    const button = document.querySelector("#savePolicy");
    const payload = {
      minDays: Number(document.querySelector("#policyMinDays").value),
      maxDays: Number(document.querySelector("#policyMaxDays").value),
      defaultDays: Number(document.querySelector("#policyDefaultDays").value),
      bufferDays: Number(document.querySelector("#policyBufferDays").value),
      tightThreshold: Number(document.querySelector("#policyTight").value) / 100,
      emergencyThreshold: Number(document.querySelector("#policyEmergency").value) / 100,
      statWindowDays: Number(document.querySelector("#policyWindow").value),
      ewmaAlpha: Number(document.querySelector("#policyAlpha").value),
      operator: operator.value.trim() || null
    };
    setBusy(button, true, "保存并重算中…");
    try {
      policy = await api("/settings/cooldown", { method: "PUT", body: JSON.stringify(payload) });
      fillPolicyForm();
      await loadSpaces(selected && selected.prefix);
      toast("全局冷却规则已保存，自动模式货架已完成重算");
    } catch (error) { toast(errorMessage(error), "error"); }
    finally { setBusy(button, false); }
  });
  document.querySelector("#cooldownForm").addEventListener("submit", async event => {
    event.preventDefault(); if (!selected) return;
    const button = document.querySelector("#saveCooldown"); const manual = document.querySelector("#editCooldownMode").value === "MANUAL";
    setBusy(button, true, "保存中…");
    try {
      await api(`/code-spaces/${encodeURIComponent(selected.prefix)}/cooldown`, { method: "PUT", body: JSON.stringify({ days: manual ? Number(document.querySelector("#editCooldownDays").value) : null, operator: operator.value.trim() || null }) });
      toast(`${selected.prefix} 已切换为 ${manual ? "手动冷却" : "自动冷却"}`); await loadSpaces(selected.prefix);
    } catch (error) { toast(errorMessage(error), "error"); }
    finally { setBusy(button, false); }
  });

  document.querySelector("#recompute").onclick = async event => {
    if (!selected) return;
    const button = event.currentTarget;
    const resultBox = document.querySelector("#recomputeResult");
    setBusy(button, true, "重算中…");
    resultBox.hidden = true;
    try {
      const result = await api(`/code-spaces/${encodeURIComponent(selected.prefix)}/recompute`, { method: "POST" });
      resultBox.textContent = `${result.changed ? "已调整" : "保持不变"}：${result.newDays} 天，${result.reason}`;
      resultBox.hidden = false;
      await loadSpaces(selected.prefix);
    }
    catch (error) { toast(errorMessage(error), "error"); }
    finally { setBusy(button, false); }
  };
  document.querySelector("#refreshLogs").onclick = loadLogs;
  Promise.all([loadPolicy(), loadSpaces()]).catch(error => toast(errorMessage(error), "error"));
})();
