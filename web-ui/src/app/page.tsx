"use client";

import { useEffect, useState, useCallback, useRef } from "react";
import { Shell } from "@/components/Shell";

const API = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

interface NodeInfo {
  nodeId: string; hostname: string; ip: string; port: number;
  status: string; cpuUsage: number | null; memoryUsage: number | null;
  cpuStale: boolean; memoryStale: boolean;
  cpuCores: number; totalMemoryBytes: number; heartbeatAgeMs: number;
}

interface Stats {
  totalNodes: number; aliveNodes: number; deadNodes: number;
  activeTasks: number; completedTasks: number; failedTasks: number;
  avgCpuPct: number; avgMemPct: number;
}

interface HistPoint { t: string; avgCpu: number; avgMem: number; }

function usageColor(pct: number) {
  if (pct < 60) return "low";
  if (pct < 85) return "medium";
  return "high";
}

function fmtBytes(b: number) {
  if (!b) return "—";
  if (b > 1e9) return `${(b / 1e9).toFixed(1)} GB`;
  return `${(b / 1e6).toFixed(0)} MB`;
}

// Tiny sparkline SVG built from data points
function Sparkline({ data, color, label }: { data: number[]; color: string; label: string }) {
  const h = 120, w = 500;
  const max = Math.max(...data, 1);
  const pts = data.map((v, i) => {
    const x = (i / (data.length - 1)) * w;
    const y = h - (v / max) * h * 0.85 - 10;
    return `${x},${y}`;
  }).join(" ");
  const areaBottom = `${data.length > 1 ? (data.length - 1) / (data.length - 1) * w : 0},${h} 0,${h}`;
  const areaPath = data.length > 1
    ? `M 0,${h - (data[0] / max) * h * 0.85 - 10} L ${pts} L ${areaBottom} Z`
    : `M 0,${h} L ${w},${h} Z`;

  return (
    <div className="card" style={{ marginBottom: 0 }}>
      <div className="card-header">
        <span className="card-title">{label}</span>
        <span style={{ fontSize: 11, color: "var(--text3)" }}>
          {data.length > 0 ? `${data[data.length - 1].toFixed(1)}%` : "—"}
        </span>
      </div>
      <div style={{ padding: "0 20px 20px", height: 140 }}>
        <svg viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" style={{ width: "100%", height: "100%", display: "block" }}>
          <defs>
            <linearGradient id={`grad-${color}`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={color} stopOpacity="0.3" />
              <stop offset="100%" stopColor={color} stopOpacity="0.02" />
            </linearGradient>
          </defs>
          {data.length > 1 && (
            <>
              <path d={areaPath} fill={`url(#grad-${color})`} />
              <polyline points={pts} className="chart-line" stroke={color} />
            </>
          )}
          {data.length === 0 && (
            <text x={w / 2} y={h / 2} textAnchor="middle" fill="var(--text3)" fontSize="14" fontFamily="Inter">
              Waiting for data…
            </text>
          )}
        </svg>
      </div>
    </div>
  );
}

export default function DashboardPage() {
  const [stats, setStats]   = useState<Stats | null>(null);
  const [nodes, setNodes]   = useState<NodeInfo[]>([]);
  const [error, setError]   = useState<string | null>(null);
  const [hist, setHist]     = useState<HistPoint[]>([]);
  const histRef = useRef<HistPoint[]>([]);

  const fetch_ = useCallback(async () => {
    try {
      const [sRes, nRes] = await Promise.all([
        fetch(`${API}/api/v1/cluster/stats`),
        fetch(`${API}/api/v1/nodes`),
      ]);
      if (!sRes.ok || !nRes.ok) throw new Error(`HTTP ${sRes.status}`);
      const s = await sRes.json();
      const n = await nRes.json();
      setStats(s);
      setNodes(n.nodes ?? []);
      setError(null);

      // Keep rolling 20-point history
      const newPt: HistPoint = {
        t: new Date().toLocaleTimeString(),
        avgCpu: +(s.avgCpuPct ?? 0).toFixed(1),
        avgMem: +(s.avgMemPct ?? 0).toFixed(1),
      };
      histRef.current = [...histRef.current.slice(-19), newPt];
      setHist([...histRef.current]);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Cannot reach API");
    }
  }, []);

  useEffect(() => { fetch_(); const id = setInterval(fetch_, 2000); return () => clearInterval(id); }, [fetch_]);

  const cpuHistory = hist.map(h => h.avgCpu);
  const memHistory = hist.map(h => h.avgMem);

  return (
    <Shell>
      <div className="page">
        <div className="page-header">
          <h1 className="page-title">Cluster Dashboard</h1>
          <p className="page-sub">Real-time overview of your distributed compute cluster.</p>
        </div>

        {error && (
          <div className="alert alert-error">
            <span className="alert-icon">⚠</span>
            <div>
              <strong>API unreachable</strong> — {error}
              <br /><span style={{ opacity: 0.8 }}>Start the Spring Boot server with <code>ROLE=server</code> and <code>spring.profiles.active=dev</code></span>
            </div>
          </div>
        )}

        {/* Stat grid */}
        <div className="stat-grid">
          {[
            { icon: "▣", label: "Total Nodes",   value: stats?.totalNodes     ?? "—" },
            { icon: "●", label: "Healthy",        value: stats?.aliveNodes     ?? "—" },
            { icon: "○", label: "Offline",        value: stats?.deadNodes      ?? "—" },
            { icon: "⚡", label: "Active Tasks",  value: stats?.activeTasks    ?? "—" },
            { icon: "✓", label: "Tasks Done",     value: stats?.completedTasks ?? "—" },
            { icon: "✕", label: "Tasks Failed",   value: stats?.failedTasks    ?? "—" },
          ].map(({ icon, label, value }) => (
            <div key={label} className="stat-card">
              <div className="stat-icon">{icon}</div>
              <div className="stat-value">{String(value)}</div>
              <div className="stat-label">{label}</div>
            </div>
          ))}
        </div>

        {/* Charts */}
        <div className="chart-row">
          <Sparkline data={cpuHistory}   color="var(--primary)" label="CPU Usage % (cluster avg)" />
          <Sparkline data={memHistory}   color="var(--cyan)"    label="Memory Usage % (cluster avg)" />
        </div>

        {/* Node grid */}
        <div className="card">
          <div className="card-header">
            <span className="card-title">Node Registry</span>
            <span style={{ fontSize: 11, color: "var(--text3)" }}>{nodes.length} nodes</span>
          </div>
          {nodes.length === 0 ? (
            <div className="empty-state">
              <div className="empty-icon">▣</div>
              <div className="empty-text">No nodes registered</div>
              <div className="empty-sub">Start a node agent to populate this grid.</div>
            </div>
          ) : (
            <div className="node-grid">
              {nodes.map(node => {
                const cpu = node.cpuUsage != null ? node.cpuUsage * 100 : null;
                const mem = node.memoryUsage != null ? node.memoryUsage * 100 : null;
                const isDead = node.status !== "ALIVE";
                return (
                  <div key={node.nodeId} className={`node-card${isDead ? " dead" : ""}`}>
                    <div className="node-top">
                      <span className={`node-dot ${isDead ? "dead" : "alive"}`} />
                      <span className="node-name">{node.hostname || node.nodeId.slice(0, 20)}</span>
                      <span className={`node-badge badge-${isDead ? "dead" : "alive"}`}>{node.status}</span>
                    </div>
                    <div className="node-ip">{node.ip}:{node.port}</div>
                    <div className="progress-row">
                      <div className="progress-label">
                        <span>CPU</span>
                        <span className="mono">{cpu != null ? `${cpu.toFixed(0)}%` : "—"}</span>
                      </div>
                      <div className="progress-track">
                        <div className={`progress-fill ${usageColor(cpu ?? 0)}`}
                          style={{ width: `${Math.min(cpu ?? 0, 100)}%` }} />
                      </div>
                    </div>
                    <div className="progress-row">
                      <div className="progress-label">
                        <span>RAM</span>
                        <span className="mono">{mem != null ? `${mem.toFixed(0)}%` : "—"}</span>
                      </div>
                      <div className="progress-track">
                        <div className={`progress-fill ${usageColor(mem ?? 0)}`}
                          style={{ width: `${Math.min(mem ?? 0, 100)}%` }} />
                      </div>
                    </div>
                    <div style={{ fontSize: 11, color: "var(--text3)", marginTop: 8 }}>
                      {node.cpuCores} cores · {fmtBytes(node.totalMemoryBytes)}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>
    </Shell>
  );
}
