export function relativeTime(value?: string | null, now = Date.now()): string {
  if (!value) return "尚未上报";
  const seconds = Math.max(
    0,
    Math.floor((now - new Date(value).getTime()) / 1000),
  );
  if (!Number.isFinite(seconds)) return "—";
  if (seconds < 60) return `${seconds} 秒前`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`;
  return `${Math.floor(seconds / 86400)} 天前`;
}
export function dateTime(value?: string): string {
  return value
    ? new Date(value).toLocaleString("zh-CN", { hour12: false })
    : "—";
}
export function shortAddress(value: string): string {
  return value.length > 28 ? `${value.slice(0, 17)}…${value.slice(-8)}` : value;
}
export function fullDomain(rr: string, domain: string): string {
  return !rr || rr === "@" ? domain : `${rr}.${domain}`;
}
export function statusLabel(status?: string): string {
  return (
    (
      {
        SUCCESS: "同步成功",
        ERROR: "同步失败",
        FAILED: "同步失败",
        SKIPPED: "等待地址",
        NEVER: "尚未同步",
        UNCHANGED: "已是最新",
        CREATED: "已创建",
        UPDATED: "已更新",
      } as Record<string, string>
    )[status || ""] ||
    status ||
    "尚未同步"
  );
}
export function statusTone(status?: string): string {
  return ["SUCCESS", "UNCHANGED", "CREATED", "UPDATED"].includes(status || "")
    ? "green"
    : ["ERROR", "FAILED"].includes(status || "")
      ? "red"
      : "gray";
}
