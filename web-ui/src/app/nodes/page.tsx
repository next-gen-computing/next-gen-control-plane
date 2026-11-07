"use client";

import { useEffect, useState, useCallback } from "react";
import { Shell } from "@/components/Shell";

const API = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

interface Node {
  nodeId: string; hostname: string; ip: string; port: number;
  status: string; agentVersion: string; osName: string;
  cpuCores: number; totalMemoryBytes: number;
  cpuUsage: number | null; memoryUsage: number | null;
  cpuStale: boolean; memoryStale: boolean; heartbeatAgeMs: number;
}

function fmtBytes(b: number) {
  if (!b) return "—";
  return b > 1e9 ? `${(b / 1e9).toFixed(1)} GB` : `${(b / 1e6).toFixed(0)} MB`;
}

function usageColor(pct: number) {
  if (pct < 60) return "low"; if (pct < 85) return "medium"; return "high";
}

function relTime(ms: number) {
  if (ms < 2000) return "just now";
  if (ms < 60000) return `${(ms / 1000).toFixed(0)}s ago`;
  return `${(ms / 60000).toFixed(0)}m ago`;
}

export default function NodesPage() {
  const [nodes, setNodes]     = useState<Node[]>([]);
  const [loading, setLoading] = useState(true);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [error, setError]     = useState<string | null>(null);

  const fetch_ = useCallback(async () => {
    try {
      const res = await fetch(`${API}/api/v1/nodes`);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      setNodes(data.nodes ?? []);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "error");
    } finally { setLoading(false); }
  }, []);

  useEffect(() => { fetch_(); const id = setInterval(fetch_, 2000); return () => clearInterval(id); }, [fetch_]);

  const alive = nodes.filter(n => n.status === "ALIVE").length;
  const dead  = nodes.filter(n => n.status !== "ALIVE").length;

  return (
    <Shell>
      <div className="page">
        <div className="page-header">
          <h1 className="page-title">Nodes</h1>
          <p className="page-sub">All registered worker nodes with live metrics.</p>
        </div>

        {error && (
          <div className="alert alert-error">
            <span className="alert-icon">⚠</span>
            <span>API unreachable — {error}</span>
          </div>
        )}

        {/* Summary pills */}
        <div className="filter-row" style={{ marginBottom: 20 }}>
          <span className="pill">{nodes.length} Total</span>
          <span className="pill" style={{ color: "var(--green)", borderColor: "rgba(74,222,128,0.3)", background: "rgba(74,222,128,0.06)" }}>{alive} Healthy</span>
          <span className="pill" style={{ color: "var(--red)", borderColor: "rgba(248,113,113,0.3)", background: "rgba(248,113,113,0.06)" }}>{dead} Offline</span>
        </div>

        {/* Node list */}
        {loading ? (
          <div>
            {[1,2,3,4].map(i => (
              <div key={i} className="skeleton" style={{ height: 64, borderRadius: 10, marginBottom: 8 }} />
            ))}
          </div>
        ) : nodes.length === 0 ? (
          <div className="card">
            <div className="empty-state">
              <div className="empty-icon">▣</div>
              <div className="empty-text">No nodes registered</div>
              <div className="empty-sub">Start a node agent to see data here.</div>
            </div>
          </div>
        ) : (
          <div>
            {nodes.map(node => {
              const cpu = node.cpuUsage != null ? node.cpuUsage * 100 : null;
              const mem = node.memoryUsage != null ? node.memoryUsage * 100 : null;
              const isDead = node.status !== "ALIVE";
              const isOpen = expanded === node.nodeId;

              return (
                <div key={node.nodeId}>
                  <button
                    className={`node-row-btn${isDead ? " dead" : ""}${isOpen ? " open" : ""}`}
                    onClick={() => setExpanded(isOpen ? null : node.nodeId)}
                  >
                    {/* Status dot */}
                    <span className={`node-dot ${isDead ? "dead" : "alive"}`} />

                    {/* Name + IP */}
                    <div className="node-row-info">
                      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                        <span className="node-row-name">{node.hostname || node.nodeId.slice(0, 24)}</span>
                        <span className={`node-badge badge-${isDead ? "dead" : "alive"}`}>{node.status}</span>
                      </div>
                      <div className="node-row-ip">{node.ip}:{node.port}</div>
                    </div>

                    {/* CPU meter */}
                    <div className="mini-meter">
                      <div className="mini-meter-label">
                        <span>CPU</span>
                        <span className="mini-meter-val">{cpu != null ? `${cpu.toFixed(0)}%` : "—"}</span>
                      </div>
                      <div className="progress-track">
                        <div className={`progress-fill ${usageColor(cpu ?? 0)}`} style={{ width: `${Math.min(cpu ?? 0, 100)}%` }} />
                      </div>
                    </div>

                    {/* RAM meter */}
                    <div className="mini-meter" style={{ marginLeft: 8 }}>
                      <div className="mini-meter-label">
                        <span>RAM</span>
                        <span className="mini-meter-val">{mem != null ? `${mem.toFixed(0)}%` : "—"}</span>
                      </div>
                      <div className="progress-track">
                        <div className={`progress-fill ${usageColor(mem ?? 0)}`} style={{ width: `${Math.min(mem ?? 0, 100)}%` }} />
                      </div>
                    </div>

                    {/* Heartbeat */}
                    <span style={{ fontSize: 11, color: "var(--text3)", width: 60, textAlign: "right", flexShrink: 0 }}>
                      {relTime(node.heartbeatAgeMs)}
                    </span>

                    <span className={`chevron${isOpen ? " open" : ""}`}>▾</span>
                  </button>

                  {/* Expanded detail */}
                  {isOpen && (
                    <div className="node-detail">
                      {[
                        ["Node ID",    node.nodeId, true],
                        ["OS",         node.osName || "—",         false],
                        ["Agent Ver",  node.agentVersion || "—",   true],
                        ["CPU Cores",  String(node.cpuCores),       false],
                        ["Total RAM",  fmtBytes(node.totalMemoryBytes), false],
                        ["Port",       String(node.port),           false],
                        ["Heartbeat",  relTime(node.heartbeatAgeMs), false],
                        ["Status",     node.status,                 false],
                      ].map(([label, value, isMono]) => (
                        <div key={label as string} className="detail-item">
                          <label>{label as string}</label>
                          <span style={isMono ? { fontFamily: "'JetBrains Mono', monospace", fontSize: 11, color: "var(--primary2)", wordBreak: "break-all" } : {}}>
                            {value as string}
                          </span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </Shell>
  );
}
