(function () {
  const API_BASE = "/api/v1";

  async function api(path, options = {}) {
    const response = await fetch(API_BASE + path, {
      ...options,
      headers: { "Content-Type": "application/json", ...(options.headers || {}) }
    });
    let body;
    try {
      body = await response.json();
    } catch (_) {
      throw new Error(`服务返回了无法解析的响应（HTTP ${response.status}）`);
    }
    if (!response.ok || body.code !== "0") {
      const error = new Error(body.message || `请求失败（HTTP ${response.status}）`);
      error.code = body.code;
      error.data = body.data;
      error.traceId = body.traceId;
      throw error;
    }
    return body.data;
  }

  function toast(message, type = "success") {
    let stack = document.querySelector(".toast-stack");
    if (!stack) {
      stack = document.createElement("div");
      stack.className = "toast-stack";
      document.body.appendChild(stack);
    }
    const node = document.createElement("div");
    node.className = `toast ${type === "error" ? "error" : ""}`;
    node.textContent = message;
    stack.appendChild(node);
    window.setTimeout(() => node.remove(), 3600);
  }

  function errorMessage(error) {
    const details = error.data && typeof error.data === "object"
      ? Object.entries(error.data).map(([key, value]) => `${key}: ${value}`).join("；")
      : "";
    return `${error.code && error.code !== "0" ? `[${error.code}] ` : ""}${error.message}${details ? `（${details}）` : ""}`;
  }

  function formatTime(value) {
    if (!value) return "—";
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat("zh-CN", {
      month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"
    }).format(date);
  }

  function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>'"]/g, char => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;"
    })[char]);
  }

  function queryString(params) {
    const search = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== "") search.set(key, value);
    });
    const value = search.toString();
    return value ? `?${value}` : "";
  }

  function setBusy(button, busy, label = "处理中…") {
    if (!button) return;
    if (busy) {
      button.dataset.previousText = button.textContent;
      button.textContent = label;
      button.disabled = true;
    } else {
      button.textContent = button.dataset.previousText || button.textContent;
      button.disabled = false;
    }
  }

  window.Station = { api, toast, errorMessage, formatTime, escapeHtml, queryString, setBusy };
})();
