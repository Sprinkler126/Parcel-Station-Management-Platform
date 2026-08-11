(function () {
  const { api, toast, errorMessage, formatTime, escapeHtml, setBusy } = Station;
  async function load() {
    const button = document.querySelector("#refresh");
    setBusy(button, true, "刷新中…");
    try {
      const data = await api("/stats/today");
      document.querySelector("#statAt").textContent = `${data.date} · 数据统计于 ${formatTime(data.statAt)}`;
      [["inbound", data.inboundToday], ["outbound", data.outboundToday], ["stock", data.inStock], ["warn", data.overdueWarn], ["alert", data.overdueAlert]].forEach(([id, value]) => document.querySelector(`#${id}`).textContent = value);
      document.querySelector("#spaces").innerHTML = data.spaces.length ? data.spaces.map(space => `<div class="space-row"><strong>${escapeHtml(space.prefix)}</strong><div><div class="progress"><span style="width:${Math.max(0, Math.min(100, space.availableRatio * 100))}%"></span></div><span class="muted small">可用 ${space.available} / ${space.capacity}</span></div><span class="tag ${space.tier === "NORMAL" ? "tag-ok" : space.tier === "TIGHT" ? "tag-warn" : "tag-alert"}">${escapeHtml(space.tier)}</span><span class="muted small">冷却 ${space.cooldownDays} 天</span></div>`).join("") : `<div class="empty">暂无启用的码空间</div>`;
      const max = Math.max(1, ...data.couriers.map(item => item.count));
      document.querySelector("#couriers").innerHTML = data.couriers.length ? data.couriers.map(item => `<div class="courier-row"><strong>${escapeHtml(item.courier)}</strong><div class="progress"><span style="width:${item.count / max * 100}%"></span></div><span>${item.count}</span></div>`).join("") : `<div class="empty">暂无在库包裹</div>`;
    } catch (error) { toast(errorMessage(error), "error"); }
    finally { setBusy(button, false); }
  }
  document.querySelector("#refresh").onclick = load;
  load();
  window.setInterval(load, 60000);
})();
