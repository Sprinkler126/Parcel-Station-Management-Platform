(function () {
  const { api, toast, errorMessage, formatTime, escapeHtml, queryString, setBusy } = Station;
  const form = document.querySelector("#searchForm");
  const rows = document.querySelector("#rows");
  let page = 0;
  let totalPages = 0;

  function overdueTag(item) {
    if (item.overdueLevel === "ALERT") return `<span class="tag tag-alert">严重滞留</span>`;
    if (item.overdueLevel === "WARN") return `<span class="tag tag-warn">滞留</span>`;
    return "";
  }

  function render(items) {
    rows.innerHTML = items.map(item => `<tr>
      <td><strong>${escapeHtml(item.pickupCode)}</strong><br><span class="muted small">${escapeHtml(item.codeSource)}</span></td>
      <td>${escapeHtml(item.contactMasked)} · ${escapeHtml(item.receiverName || "未填姓名")}<br><span class="muted small">${escapeHtml(item.courier)} · 运单尾号 ${escapeHtml(item.trackingTail)}</span></td>
      <td>${formatTime(item.inboundAt)}<br><span class="muted small">${escapeHtml(item.overdueText || "—")}</span> ${overdueTag(item)}</td>
      <td><span class="tag ${item.status === "PENDING" ? "tag-ok" : ""}">${item.status === "PENDING" ? "待取件" : escapeHtml(item.status)}</span></td>
      <td>${item.status === "PENDING" ? `<button class="btn btn-primary btn-sm" data-action="pickup" data-id="${item.id}">确认取件</button> <button class="btn btn-sm" data-action="urge" data-id="${item.id}">催取</button>` : "—"}${item.needsSuffixPatch ? ` <button class="btn btn-sm" data-action="suffix" data-id="${item.id}">补录尾号</button>` : ""}</td>
    </tr>`).join("");
    document.querySelector("#empty").hidden = items.length > 0;
  }

  async function search() {
    const button = document.querySelector("#searchButton");
    setBusy(button, true, "查询中…");
    try {
      const data = await api(`/parcels${queryString({ keyword: document.querySelector("#keyword").value.trim(), status: document.querySelector("#status").value, overdue: document.querySelector("#overdue").value, codePrefix: document.querySelector("#codePrefix").value.trim(), page, size: 20 })}`);
      totalPages = data.totalPages;
      render(data.content);
      document.querySelector("#pageInfo").textContent = `第 ${data.page + 1} / ${Math.max(1, data.totalPages)} 页 · 共 ${data.total} 件`;
      document.querySelector("#prev").disabled = data.page <= 0;
      document.querySelector("#next").disabled = data.page + 1 >= data.totalPages;
    } catch (error) { toast(errorMessage(error), "error"); }
    finally { setBusy(button, false); }
  }

  form.addEventListener("submit", event => { event.preventDefault(); page = 0; search(); });
  document.querySelector(".quick-demos").addEventListener("click", event => {
    const button = event.target.closest("button[data-demo]");
    if (!button) return;
    document.querySelector("#keyword").value = button.dataset.demo;
    document.querySelector("#status").value = button.dataset.status ?? "PENDING";
    document.querySelector("#overdue").value = button.dataset.overdue ?? "";
    document.querySelector("#codePrefix").value = "";
    page = 0;
    search();
  });
  rows.addEventListener("click", async event => {
    const button = event.target.closest("button[data-action]");
    if (!button) return;
    const id = button.dataset.id;
    const action = button.dataset.action;
    let path, body = { operator: localStorage.getItem("station.operator") || null };
    if (action === "pickup") {
      if (!confirm("确认该包裹已交付？")) return;
      path = `/parcels/${id}/pickup`;
    } else if (action === "urge") {
      path = `/parcels/${id}/urge`;
    } else {
      const suffix = prompt("请输入真实后四位：");
      if (suffix === null) return;
      path = `/parcels/${id}/suffix`; body = { realSuffix: suffix.trim(), operator: body.operator };
    }
    setBusy(button, true);
    try {
      await api(path, { method: action === "suffix" ? "PATCH" : "POST", body: JSON.stringify(body) });
      toast(action === "pickup" ? "取件成功" : action === "urge" ? "已记录催取" : "尾号补录成功");
      search();
    } catch (error) { toast(errorMessage(error), "error"); setBusy(button, false); }
  });
  document.querySelector("#prev").onclick = () => { if (page > 0) { page -= 1; search(); } };
  document.querySelector("#next").onclick = () => { if (page + 1 < totalPages) { page += 1; search(); } };
  search();
})();
