(function () {
  const { api, formatTime } = Station;
  const stages = {
    inbound: ["INBOUND · 入库", "两条生命周期同时开始", "运单活动标记与取件码槽位标记同时置为 1；数据库唯一索引负责并发下的最终裁决。", "占用", "占用"],
    pending: ["PENDING · 在库", "取件码定位真实货位", "包裹保持待取状态，查询结果同时提供脱敏联系号、运单尾号、快递公司和滞留时长用于二次核对。", "占用", "占用"],
    pickup: ["PICKUP · 取件", "运单完结，码仍被保留", "确认交付后释放运单活动槽位，但取件码进入冷却期，避免旧通知仍有效时被过早复用。", "释放", "占用"],
    cooling: ["COOLING · 冷却", "架上已空，号码暂不可用", "系统按该排当前冷却策略实时判断可复用边界；缩短策略立即生效，无需修改历史包裹。", "释放", "冷却中"],
    reusable: ["REUSABLE · 回炉", "槽位释放，进入下一轮", "冷却到期后取件码槽位释放，next-fit 分配器会在后续入库中再次找到它。历史包裹和流水仍完整保留。", "释放", "可复用"]
  };
  document.querySelector("#lifecycle").addEventListener("click", event => {
    const button = event.target.closest("button[data-stage]");
    if (!button) return;
    document.querySelectorAll("#lifecycle button").forEach(item => item.classList.toggle("active", item === button));
    const stage = stages[button.dataset.stage];
    ["stageLabel", "stageTitle", "stageText", "trackingFlag", "codeFlag"].forEach((id, index) => document.querySelector(`#${id}`).textContent = stage[index]);
  });

  Promise.all([api("/stats/today"), api("/pickup-codes/preview?scope=ROW&codePrefix=15-1")])
    .then(([stats, preview]) => {
      document.querySelector("#liveText").textContent = "站点服务在线";
      document.querySelector("#liveDot").classList.add("online");
      document.querySelector("#snapshotTime").textContent = formatTime(stats.statAt);
      document.querySelector("#heroNextCode").textContent = preview.nextCode || "空间已满";
      document.querySelector("#heroInbound").textContent = stats.inboundToday;
      document.querySelector("#heroStock").textContent = stats.inStock;
      document.querySelector("#heroOverdue").textContent = stats.overdueTotal;
    })
    .catch(() => { document.querySelector("#liveText").textContent = "服务暂未连接"; });
})();
