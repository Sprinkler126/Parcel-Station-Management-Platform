(function () {
  const { api, toast, errorMessage, queryString, setBusy, formatTime, escapeHtml } = Station;
  const form = document.querySelector("#inboundForm");
  const trackingNo = document.querySelector("#trackingNo");
  const operator = document.querySelector("#operator");
  const prefix = document.querySelector("#codePrefix");
  const scope = document.querySelector("#scope");
  const prefixField = document.querySelector("#prefixField");
  const pickupCode = document.querySelector("#pickupCode");
  const submit = document.querySelector("#submitButton");
  let mode = "AUTO";
  const resultStack = [];
  let codeSpaces = [];
  let selectedShelf = "15";

  operator.value = localStorage.getItem("station.operator") || "";

  function setMode(next) {
    mode = next;
    const auto = mode === "AUTO";
    document.querySelector("#autoMode").classList.toggle("active", auto);
    document.querySelector("#manualMode").classList.toggle("active", !auto);
    document.querySelector("#scopeField").classList.toggle("is-hidden", !auto);
    prefixField.classList.toggle("is-hidden", !auto || scope.value === "FULL");
    document.querySelector("#manualField").classList.toggle("is-hidden", auto);
    document.querySelector("#preview").classList.toggle("is-hidden", !auto);
    document.querySelector("#manualSummary").classList.toggle("is-hidden", auto);
    scope.disabled = !auto;
    prefix.required = auto && scope.value !== "FULL";
    pickupCode.disabled = auto;
    pickupCode.required = !auto;
    if (auto) {
      updateScopeUi(false);
    } else {
      updateManualEcho();
      pickupCode.focus();
    }
  }

  function updateScopeUi(refresh = true) {
    const value = scope.value;
    const full = value === "FULL";
    prefixField.classList.toggle("is-hidden", full || mode !== "AUTO");
    prefix.required = !full && mode === "AUTO";
    if (value === "ROW") {
      selectDefaultRow();
      document.querySelector("#prefixLabel").textContent = "指定货架与排 *";
      document.querySelector("#prefixHelp").textContent = prefix.value
        ? `已选择 ${prefix.value}，前两段固定，仅最后序号由系统推荐`
        : "先选择货架，再选择该货架下的排号。";
      document.querySelector("#scopeHelp").textContent = "固定前两段，系统只推荐最后的序号。";
      document.querySelector("#previewLabel").textContent = "指定排预览（前两段固定，不占位）";
    } else if (value === "SHELF") {
      selectDefaultShelf();
      document.querySelector("#prefixLabel").textContent = "指定货架 *";
      document.querySelector("#prefixHelp").textContent = `首段固定为 ${prefix.value}，系统在该货架各排中选排并推荐序号`;
      document.querySelector("#scopeHelp").textContent = "只固定第一段货架号，排号和最后序号由系统选择。";
      document.querySelector("#previewLabel").textContent = "指定货架预览（仅首段固定，不占位）";
    } else {
      document.querySelector("#scopeHelp").textContent = "不接受任何前缀，系统从全站启用货架排中自动选择。";
      document.querySelector("#previewLabel").textContent = "全站预览（无固定前缀，不占位）";
    }
    renderLocationPicker();
    if (refresh && mode === "AUTO") loadPreview();
  }

  function shelfOf(space) {
    return space.prefix.slice(0, space.prefix.lastIndexOf("-"));
  }

  function rowOf(space) {
    return space.prefix.slice(space.prefix.lastIndexOf("-") + 1);
  }

  function shelves() {
    return [...new Set(codeSpaces.map(shelfOf))].sort((a, b) => a.localeCompare(b, "zh-CN", { numeric: true }));
  }

  function rowsFor(shelf) {
    return codeSpaces.filter(item => shelfOf(item) === shelf)
      .sort((a, b) => rowOf(a).localeCompare(rowOf(b), "zh-CN", { numeric: true }));
  }

  function selectDefaultShelf() {
    const available = shelves();
    const fromPrefix = prefix.value.split("-")[0];
    selectedShelf = available.includes(selectedShelf) ? selectedShelf
      : available.includes(fromPrefix) ? fromPrefix : (available[0] || "");
    prefix.value = selectedShelf;
  }

  function selectDefaultRow() {
    const available = shelves();
    const fromPrefix = prefix.value.split("-")[0];
    selectedShelf = available.includes(selectedShelf) ? selectedShelf
      : available.includes(fromPrefix) ? fromPrefix : (available[0] || "");
    const rows = rowsFor(selectedShelf);
    if (!rows.some(item => item.prefix === prefix.value)) {
      prefix.value = rows[0] ? rows[0].prefix : "";
    }
  }

  function renderLocationPicker() {
    const picker = document.querySelector("#locationPicker");
    if (scope.value === "FULL" || mode !== "AUTO") return;
    const shelfItems = shelves();
    if (!shelfItems.length) {
      picker.innerHTML = `<div class="location-picker-empty">没有可用货架，请先到站点设置中新增或启用货架排。</div>`;
      return;
    }
    const shelfGrid = shelfItems.map(value => `<button class="location-cell ${value === selectedShelf ? "is-selected" : ""}" type="button" data-shelf="${value}" aria-pressed="${value === selectedShelf}">${value}</button>`).join("");
    let html = `<div class="location-picker-group"><span>01 / 货架</span><div class="location-grid">${shelfGrid}</div></div>`;
    if (scope.value === "ROW") {
      const rowGrid = rowsFor(selectedShelf).map(item => {
        const value = rowOf(item);
        const active = item.prefix === prefix.value;
        return `<button class="location-cell ${active ? "is-selected" : ""}" type="button" data-row="${value}" aria-pressed="${active}">${value}</button>`;
      }).join("");
      html += `<div class="location-picker-group"><span>02 / 排号</span><div class="location-grid">${rowGrid}</div></div>`;
    }
    picker.innerHTML = html;
  }

  async function loadCodeSpaces() {
    try {
      codeSpaces = await api("/code-spaces");
      updateScopeUi(false);
      await loadPreview();
    } catch (error) {
      document.querySelector("#locationPicker").innerHTML = `<div class="location-picker-empty">货架配置加载失败，请刷新页面重试。</div>`;
      document.querySelector("#nextCode").textContent = "预览不可用";
    }
  }

  function renderResultStack() {
    const container = document.querySelector("#resultStack");
    const items = [...resultStack].reverse();
    document.querySelector("#emptyResult").hidden = items.length > 0;
    document.querySelector("#stackActions").classList.toggle("is-hidden", items.length === 0);
    document.querySelector("#sessionCount").textContent = `本班次 ${items.length} 件`;
    container.innerHTML = items.map((item, index) => {
      const latest = index === 0;
      return `<article class="session-result-item ${latest ? "is-latest" : ""}"><div class="session-result-head"><span class="session-result-index">${latest ? "STACK TOP / 最新" : "已完成入库"}</span><span class="session-result-position">${items.length - index}</span></div><div class="session-result-code">${escapeHtml(item.pickupCode)}</div><p class="session-result-meta">${escapeHtml(item.courier)} · 运单尾号 ${escapeHtml(item.trackingTail)} · ${escapeHtml(formatTime(item.inboundAt))}</p></article>`;
    }).join("");
  }

  function updateManualEcho() {
    document.querySelector("#manualCodeEcho").textContent = pickupCode.value.trim() || "等待输入";
  }

  async function loadPreview() {
    if (mode !== "AUTO") return;
    const codePrefix = scope.value === "FULL" ? "" : prefix.value.trim();
    if (!codePrefix && scope.value !== "FULL") {
      document.querySelector("#nextCode").textContent = "请填写范围";
      return;
    }
    try {
      const data = await api(`/pickup-codes/preview${queryString({ scope: scope.value, codePrefix })}`);
      document.querySelector("#nextCode").textContent = data.exhausted ? "空间已满" : data.nextCode;
    } catch (error) {
      document.querySelector("#nextCode").textContent = "预览不可用";
    }
  }

  form.addEventListener("submit", async event => {
    event.preventDefault();
    const data = Object.fromEntries(new FormData(form));
    const payload = {
      trackingNo: data.trackingNo.trim(), courier: data.courier.trim(), contactNo: data.contactNo.trim(),
      receiverName: data.receiverName.trim() || null, codeMode: mode,
      scope: mode === "AUTO" ? scope.value : null,
      codePrefix: mode === "AUTO" && scope.value !== "FULL" ? prefix.value.trim() : null,
      pickupCode: mode === "MANUAL" ? pickupCode.value.trim() : null,
      operator: data.operator.trim() || null, remark: data.remark.trim() || null
    };
    localStorage.setItem("station.operator", payload.operator || "");
    setBusy(submit, true, "正在入库…");
    try {
      const parcel = await api("/parcels", { method: "POST", body: JSON.stringify(payload) });
      resultStack.push(parcel);
      renderResultStack();
      toast(`入库成功：${parcel.pickupCode}`);
      ["trackingNo", "contactNo", "receiverName", "remark", "pickupCode"].forEach(name => { const el = form.elements[name]; if (el) el.value = ""; });
      trackingNo.focus();
      loadPreview();
    } catch (error) {
      toast(errorMessage(error), "error");
      trackingNo.select();
    } finally {
      setBusy(submit, false);
    }
  });

  document.querySelector("#undoButton").addEventListener("click", async event => {
    const lastParcel = resultStack.at(-1);
    if (!lastParcel || !confirm(`确认撤销栈顶 ${lastParcel.pickupCode} 的入库？`)) return;
    const button = event.currentTarget;
    setBusy(button, true, "撤销中…");
    try {
      await api(`/parcels/${lastParcel.id}/undo-inbound`, { method: "POST", body: JSON.stringify({ operator: operator.value || null }) });
      toast(`已撤销 ${lastParcel.pickupCode}`);
      resultStack.pop();
      renderResultStack();
      loadPreview();
    } catch (error) { toast(errorMessage(error), "error"); }
    finally { setBusy(button, false); }
  });

  document.querySelector("#autoMode").onclick = () => { setMode("AUTO"); loadPreview(); };
  document.querySelector("#manualMode").onclick = () => setMode("MANUAL");
  document.querySelector("#demoInbound").onclick = () => {
    const samples = [
      { courier: "SF", contactNo: "138****5678", receiverName: "张", remark: "演示：隐私面单" },
      { courier: "JD", contactNo: "13912341111", receiverName: "李", remark: "演示：真实手机号" },
      { courier: "YT", contactNo: "17012345678,8462", receiverName: "王", remark: "演示：AXB 虚拟号，待补录尾号" }
    ];
    const sample = samples[Math.floor(Math.random() * samples.length)];
    form.elements.trackingNo.value = `DEMO${Date.now()}`;
    form.elements.courier.value = sample.courier;
    form.elements.contactNo.value = sample.contactNo;
    form.elements.receiverName.value = sample.receiverName;
    form.elements.remark.value = sample.remark;
    if (!form.elements.operator.value) form.elements.operator.value = "演示站员";
    if (mode === "MANUAL") {
      pickupCode.value = `15-1-${8000 + Math.floor(Math.random() * 1000)}`;
      updateManualEcho();
      toast("已填入模拟包裹和手动完整码，提交时不会使用自动推荐");
    } else {
      scope.value = "ROW";
      prefix.value = "15-1";
      selectedShelf = "15";
      updateScopeUi();
      toast("已填入一件模拟包裹，可直接提交体验完整入库流程");
    }
  };
  document.querySelector("#refreshPreview").onclick = loadPreview;
  document.querySelector("#resetButton").addEventListener("click", () => setTimeout(() => { operator.value = localStorage.getItem("station.operator") || ""; trackingNo.focus(); }, 0));
  document.querySelector("#locationPicker").addEventListener("click", event => {
    const shelfButton = event.target.closest("button[data-shelf]");
    const rowButton = event.target.closest("button[data-row]");
    if (shelfButton) {
      selectedShelf = shelfButton.dataset.shelf;
      prefix.value = scope.value === "ROW" ? (rowsFor(selectedShelf)[0]?.prefix || "") : selectedShelf;
      updateScopeUi();
    } else if (rowButton) {
      prefix.value = `${selectedShelf}-${rowButton.dataset.row}`;
      updateScopeUi();
    }
  });
  scope.addEventListener("change", () => updateScopeUi());
  pickupCode.addEventListener("input", updateManualEcho);
  document.addEventListener("keydown", event => { if ((event.ctrlKey || event.metaKey) && event.key === "Enter") form.requestSubmit(); });
  setMode("AUTO"); loadCodeSpaces();
})();
