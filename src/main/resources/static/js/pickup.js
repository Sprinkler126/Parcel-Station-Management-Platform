(function () {
  const { api, toast, errorMessage, formatTime, escapeHtml, queryString, setBusy } = Station;
  const operator = document.querySelector("#pickupOperator");
  const results = document.querySelector("#pickupResults");
  let mode = "code";
  let parcels = [];
  operator.value = localStorage.getItem("station.operator") || "站员A";
  operator.addEventListener("change", () => localStorage.setItem("station.operator", operator.value.trim()));

  function setMode(next) {
    mode = next; parcels = [];
    document.querySelectorAll(".pickup-mode button").forEach(button => button.classList.toggle("active", button.dataset.mode === next));
    document.querySelector("#codeSearchPane").hidden = next !== "code";
    document.querySelector("#suffixSearchPane").hidden = next !== "suffix";
    clearResults();
    document.querySelector(next === "code" ? "#pickupCodeInput" : "#suffixInput").focus();
  }

  function clearResults(message = "输入取件信息后，这里会显示待核对包裹") {
    results.innerHTML = "";
    document.querySelector("#pickupEmpty").textContent = message;
    document.querySelector("#pickupEmpty").hidden = false;
    document.querySelector("#pickupActions").hidden = true;
    document.querySelector("#resultCount").textContent = "0 件";
    document.querySelector("#resultHeading").textContent = "待核对包裹";
    document.querySelector("#companionHint").hidden = true;
  }

  function overdue(item) {
    if (item.overdueLevel === "ALERT") return `<span class="tag tag-alert">严重滞留</span>`;
    if (item.overdueLevel === "WARN") return `<span class="tag tag-warn">滞留</span>`;
    return `<span class="tag tag-ok">正常</span>`;
  }

  function render(items, primaryId = null, companionIds = new Set()) {
    parcels = items;
    if (!items.length) { clearResults("没有找到仍在库的匹配包裹，请核对输入或改用运单号查询"); return; }
    document.querySelector("#pickupEmpty").hidden = true;
    document.querySelector("#resultCount").textContent = `${items.length} 件`;
    document.querySelector("#resultHeading").textContent = companionIds.size ? "本件与同客户包裹" : "待核对包裹";
    document.querySelector("#companionHint").hidden = companionIds.size === 0;
    results.innerHTML = items.map(item => {
      const isCompanion = companionIds.has(item.id);
      const checked = primaryId === null || item.id === primaryId;
      return `<label class="pickup-item"><input type="checkbox" data-id="${item.id}" ${checked ? "checked" : ""}><span class="pickup-check">✓</span><span class="pickup-main"><span><strong class="pickup-code">${escapeHtml(item.pickupCode)}</strong>${isCompanion ? `<span class="tag">同客户追加</span>` : ""}${overdue(item)}${item.reuseForced ? `<span class="tag tag-alert">提前复用码</span>` : ""}</span><span class="pickup-person">${escapeHtml(item.receiverName || "未填姓名")} · ${escapeHtml(item.contactMasked)}</span><span class="muted small">${escapeHtml(item.courier)} · 运单尾号 ${escapeHtml(item.trackingTail)} · ${formatTime(item.inboundAt)} · ${escapeHtml(item.overdueText)}</span></span></label>`;
    }).join("");
    document.querySelector("#pickupActions").hidden = false;
    updateSelection();
  }

  function selectedIds() { return [...results.querySelectorAll("input:checked")].map(input => Number(input.dataset.id)); }
  function updateSelection() {
    const count = selectedIds().length;
    document.querySelector("#selectionSummary").textContent = `已选择 ${count} 件`;
    document.querySelector("#confirmPickup").disabled = count === 0;
  }

  async function find(channel, keyword, button) {
    setBusy(button, true, "查询中…");
    try {
      const page = await api(`/parcels${queryString({ keyword, channel, status: "PENDING", page: 0, size: 200 })}`);
      if (channel === "PICKUP_CODE" && page.content.length === 1) {
        const primary = page.content[0];
        const companions = await api(`/parcels/${primary.id}/pickup-companions`);
        render([primary, ...companions], primary.id, new Set(companions.map(item => item.id)));
      } else {
        render(page.content);
      }
    } catch (error) { clearResults("查询失败"); toast(errorMessage(error), "error"); }
    finally { setBusy(button, false); }
  }

  document.querySelector("#codeForm").addEventListener("submit", event => { event.preventDefault(); find("PICKUP_CODE", document.querySelector("#pickupCodeInput").value.trim(), document.querySelector("#codeSearchButton")); });
  document.querySelector("#suffixForm").addEventListener("submit", event => { event.preventDefault(); find("SUFFIX", document.querySelector("#suffixInput").value.trim(), document.querySelector("#suffixSearchButton")); });
  document.querySelector(".pickup-mode").addEventListener("click", event => { const button = event.target.closest("button[data-mode]"); if (button) setMode(button.dataset.mode); });
  results.addEventListener("change", updateSelection);

  document.querySelector("#demoCode").addEventListener("click", async event => {
    setBusy(event.currentTarget, true, "查找演示件…");
    try {
      const page = await api("/parcels?status=PENDING&page=0&size=1");
      if (!page.content.length) throw new Error("当前没有待取演示件");
      document.querySelector("#pickupCodeInput").value = page.content[0].pickupCode;
      toast(`已填入 ${page.content[0].pickupCode}，点击查询即可演示`);
    } catch (error) { toast(errorMessage(error), "error"); }
    finally { setBusy(event.currentTarget, false); }
  });
  document.querySelector("#demoSuffix").addEventListener("click", () => { document.querySelector("#suffixInput").value = "5678"; toast("已填入演示尾号 5678，默认可聚合多件包裹"); });

  document.querySelector("#confirmPickup").addEventListener("click", async event => {
    const ids = selectedIds();
    if (!ids.length || !confirm(`确认已核对并交付 ${ids.length} 件包裹？`)) return;
    const name = operator.value.trim() || null;
    localStorage.setItem("station.operator", name || "");
    setBusy(event.currentTarget, true, "正在交付…");
    try {
      if (ids.length === 1) await api(`/parcels/${ids[0]}/pickup`, { method: "POST", body: JSON.stringify({ operator: name }) });
      else {
        const batch = await api("/parcels/pickup-batch", { method: "POST", body: JSON.stringify({ ids, operator: name }) });
        if (batch.failed) toast(`${batch.succeeded} 件成功，${batch.failed} 件失败`, "error");
      }
      toast(`已完成 ${ids.length} 件交付`);
      clearResults("交付完成，可以继续处理下一位客户");
      document.querySelector(mode === "code" ? "#pickupCodeInput" : "#suffixInput").value = "";
      document.querySelector(mode === "code" ? "#pickupCodeInput" : "#suffixInput").focus();
    } catch (error) { toast(errorMessage(error), "error"); }
    finally { setBusy(event.currentTarget, false); }
  });
})();
