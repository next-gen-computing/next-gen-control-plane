import { type ClassValue, clsx } from "clsx";
import { twMerge } from "tailwind-merge";

/** Merge Tailwind class names safely */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/** Format bytes to human-readable (KB, MB, GB, TB) */
export function formatBytes(bytes: number, decimals = 1): string {
  if (bytes === 0) return "0 B";
  const k = 1024;
  const sizes = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(decimals))} ${sizes[i]}`;
}

/** Format a duration in ms to human-readable */
export function formatDuration(ms: number): string {
  if (ms <= 0) return "—";
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  if (ms < 3_600_000) return `${Math.floor(ms / 60_000)}m ${Math.floor((ms % 60_000) / 1000)}s`;
  return `${Math.floor(ms / 3_600_000)}h ${Math.floor((ms % 3_600_000) / 60_000)}m`;
}

/** Format timestamp ms as locale time string */
export function formatTime(ms: number): string {
  if (!ms) return "—";
  return new Date(ms).toLocaleTimeString();
}

/** Format timestamp ms as locale date+time string */
export function formatDateTime(ms: number): string {
  if (!ms) return "—";
  return new Date(ms).toLocaleString();
}

/** Tailwind classes for a CPU/memory usage percentage */
export function usageColorClass(pct: number): string {
  if (pct < 60) return "bg-gradient-to-r from-blue-500 to-cyan-400";
  if (pct < 85) return "bg-gradient-to-r from-amber-400 to-orange-400";
  return "bg-gradient-to-r from-red-500 to-rose-400";
}

/** CSS color for a recharts line based on usage */
export function usageColor(pct: number): string {
  if (pct < 60) return "#22d3ee"; // cyan-400
  if (pct < 85) return "#fbbf24"; // amber-400
  return "#f87171"; // red-400
}

/** Map task/job state → badge class */
export function stateBadge(state: string): string {
  switch (state) {
    case "QUEUED":     return "badge-info";
    case "DISPATCHED": return "badge-warning";
    case "RUNNING":    return "badge-running";
    case "COMPLETED":  return "badge-alive";
    case "FAILED":     return "badge-dead";
    default:           return "inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-muted/50 text-muted-foreground border border-border";
  }
}

/** Map node status → badge class */
export function nodeBadge(status: string): string {
  switch (status) {
    case "ALIVE":   return "badge-alive";
    case "DEAD":    return "badge-dead";
    case "AT_RISK": return "badge-warning";
    default:        return "badge-info";
  }
}

/** Relative time from now (e.g. "3s ago", "2m ago") */
export function relativeTime(ms: number): string {
  const age = Date.now() - ms;
  if (age < 5_000)     return "just now";
  if (age < 60_000)    return `${Math.floor(age / 1000)}s ago`;
  if (age < 3_600_000) return `${Math.floor(age / 60_000)}m ago`;
  return `${Math.floor(age / 3_600_000)}h ago`;
}
