(function () {
  const { api, toast, errorMessage, queryString, setBusy, formatTime } = Station;
  const form = document.querySelector("#inboundForm");
  const trackingNo = document.querySelector("#trackingNo");
  const operator = document.querySelector("#operator");
  const prefix = document.querySelector("#codePrefix");
  const scope = document.querySelector("#scope");
  const prefixField = document.querySelector("#prefixField");
  const pickupCode = document.querySelector("#pickupCode");
  const submit = document.querySelector("#submitButton");
  let mode = "AUTO";
  let lastParcel = null;
  let count = 0;

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
    prefix.disabled = !auto || scope.value === "FULL";
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
    prefix.disabled = full || mode !== "AUTO";
    prefix.required = !full && mode === "AUTO";
    if (value === "ROW") {
      if (!prefix.value.includes("-")) prefix.value = `${prefix.value || "15"}-1`;
      document.querySelector("#prefixLabel").textContent = "指定排前缀 *";
      document.querySelector("#prefixHelp").textContent = `前两段固定为 ${prefix.value}，仅最后序号由系统推荐`;
      document.querySelector("#scopeHelp").textContent = "固定前两段，系统只推荐最后的序号。";
      document.querySelector("#previewLabel").textContent = "指定排预览（前两段固定，不占位）";
      prefix.placeholder = "例如 15-1";
    } else if (value === "SHELF") {
      prefix.value = (prefix.value.split("-")[0] || "15");
      document.querySelector("#prefixLabel").textContent = "指定货架号 *";
      document.querySelector("#prefixHelp").textContent = `首段固定为 ${prefix.value}，系统在该货架各排中选排并推荐序号`;
      document.querySelector("#scopeHelp").textContent = "只固定第一段货架号，排号和最后序号由系统选择。";
      document.querySelector("#previewLabel").textContent = "指定货架预览（仅首段固定，不占位）";
      prefix.placeholder = "例如 15";
    } else {
      document.querySelector("#scopeHelp").textContent = "不接受任何前缀，系统从全站启用货架排中自动选择。";
      document.querySelector("#previewLabel").textContent = "全站预览（无固定前缀，不占位）";
    }
    if (refresh && mode === "AUTO") loadPreview();
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
      lastParcel = await api("/parcels", { method: "POST", body: JSON.stringify(payload) });
      count += 1;
      document.querySelector("#emptyResult").hidden = true;
      document.querySelector("#result").classList.add("show");
      document.querySelector("#resultCode").textContent = lastParcel.pickupCode;
      document.querySelector("#resultMeta").textContent = `${lastParcel.courier} · 运单尾号 ${lastParcel.trackingTail} · ${formatTime(lastParcel.inboundAt)}`;
      document.querySelector("#sessionCount").textContent = `本班次 ${count} 件`;
      toast(`入库成功：${lastParcel.pickupCode}`);
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
    if (!lastParcel || !confirm(`确认撤销 ${lastParcel.pickupCode} 的入库？`)) return;
    setBusy(event.currentTarget, true, "撤销中…");
    try {
      await api(`/parcels/${lastParcel.id}/undo-inbound`, { method: "POST", body: JSON.stringify({ operator: operator.value || null }) });
      toast(`已撤销 ${lastParcel.pickupCode}`);
      lastParcel = null; count = Math.max(0, count - 1);
      document.querySelector("#result").classList.remove("show");
      document.querySelector("#emptyResult").hidden = false;
      document.querySelector("#sessionCount").textContent = `本班次 ${count} 件`;
      loadPreview();
    } catch (error) { toast(errorMessage(error), "error"); }
    finally { setBusy(event.currentTarget, false); }
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
      updateScopeUi();
      toast("已填入一件模拟包裹，可直接提交体验完整入库流程");
    }
  };
  document.querySelector("#refreshPreview").onclick = loadPreview;
  document.querySelector("#resetButton").addEventListener("click", () => setTimeout(() => { operator.value = localStorage.getItem("station.operator") || ""; trackingNo.focus(); }, 0));
  prefix.addEventListener("change", () => updateScopeUi());
  scope.addEventListener("change", () => updateScopeUi());
  pickupCode.addEventListener("input", updateManualEcho);
  document.addEventListener("keydown", event => { if ((event.ctrlKey || event.metaKey) && event.key === "Enter") form.requestSubmit(); });
  setMode("AUTO"); loadPreview();
})();
