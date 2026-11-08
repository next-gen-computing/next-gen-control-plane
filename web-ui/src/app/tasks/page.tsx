"use client";

import { useEffect, useState, useCallback } from "react";
import { Shell } from "@/components/Shell";

const API = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

interface Task {
  taskId: string; jobId: string; kind: string; state: string;
  assignedNodeId: string; createdAtMs: number;
  dispatchedAtMs: number; completedAtMs: number; attempt: number;
  error: string;
}

interface TaskStats { queued: number; running: number; completed: number; failed: number; }

const STATE_FILTERS = ["", "QUEUED", "DISPATCHED", "RUNNING", "COMPLETED", "FAILED", "MIGRATING"];

function badgeClass(state: string) {
  const map: Record<string, string> = {
    COMPLETED: "badge-completed", RUNNING: "badge-running", DISPATCHED: "badge-running",
    QUEUED: "badge-queued", FAILED: "badge-failed", MIGRATING: "badge-risk", PENDING: "badge-pending",
  };
  return `node-badge ${map[state] ?? "badge-info"}`;
}

function fmtDuration(ms: number) {
  if (!ms || ms < 0) return "—";
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms/1000).toFixed(1)}s`;
  return `${(ms/60000).toFixed(1)}m`;
}

function fmtTime(ms: number) {
  if (!ms) return "—";
  return new Date(ms).toLocaleTimeString();
}

export default function TasksPage() {
  const [tasks, setTasks]     = useState<Task[]>([]);
  const [stats, setStats]     = useState<TaskStats | null>(null);
  const [total, setTotal]     = useState(0);
  const [page, setPage]       = useState(0);
  const [pages, setPages]     = useState(1);
  const [filter, setFilter]   = useState("");
  const [loading, setLoading] = useState(true);

  const fetch_ = useCallback(async () => {
    setLoading(true);
    try {
      const [tRes, sRes] = await Promise.all([
        fetch(`${API}/api/v1/tasks?page=${page}&size=40${filter ? `&state=${filter}` : ""}`),
        fetch(`${API}/api/v1/tasks/stats`),
      ]);
      if (tRes.ok) {
        const d = await tRes.json();
        setTasks(d.tasks ?? []); setTotal(d.total ?? 0); setPages(d.totalPages ?? 1);
      }
      if (sRes.ok) setStats(await sRes.json());
    } finally { setLoading(false); }
  }, [page, filter]);

  useEffect(() => { fetch_(); }, [fetch_]);

  return (
    <Shell>
      <div className="page">
        <div className="page-header">
          <h1 className="page-title">Tasks</h1>
          <p className="page-sub">All distributed tasks across the cluster.</p>
        </div>

        {/* Stat tiles */}
        {stats && (
          <div className="stat-grid" style={{ marginBottom: 20 }}>
            {[
              { label: "Queued",    value: stats.queued,    state: "QUEUED",    color: "var(--primary2)" },
              { label: "Running",   value: stats.running,   state: "RUNNING",   color: "var(--cyan)" },
              { label: "Completed", value: stats.completed, state: "COMPLETED", color: "var(--green)" },
              { label: "Failed",    value: stats.failed,    state: "FAILED",    color: "var(--red)" },
            ].map(({ label, value, state, color }) => (
              <div
                key={label}
                className="stat-card"
                style={{ cursor: "pointer", borderColor: filter === state ? color : undefined }}
                onClick={() => { setFilter(filter === state ? "" : state); setPage(0); }}
              >
                <div className="stat-value" style={{ color, fontSize: 26 }}>{value ?? "—"}</div>
                <div className="stat-label">{label}</div>
              </div>
            ))}
          </div>
        )}

        {/* Filter pills */}
        <div className="filter-row">
          {STATE_FILTERS.map(s => (
            <button key={s} className={`pill${filter === s ? " active" : ""}`}
              onClick={() => { setFilter(s); setPage(0); }}>
              {s || "All"}
            </button>
          ))}
          <span style={{ marginLeft: "auto", fontSize: 11, color: "var(--text3)", alignSelf: "center" }}>
            {total.toLocaleString()} tasks
          </span>
        </div>

        {/* Table */}
        <div className="card">
          {loading ? (
            <div style={{ padding: 24 }}>
              {[1,2,3,4,5].map(i => (
                <div key={i} style={{ display: "flex", gap: 12, marginBottom: 10 }}>
                  {[100,140,80,80,80].map((w, j) => (
                    <div key={j} className="skeleton" style={{ height: 14, width: w, borderRadius: 4 }} />
                  ))}
                </div>
              ))}
            </div>
          ) : tasks.length === 0 ? (
            <div className="empty-state">
              <div className="empty-icon">⚡</div>
              <div className="empty-text">No tasks found</div>
              <div className="empty-sub">Submit a job to create tasks.</div>
            </div>
          ) : (
            <>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      {["Task ID", "Kind", "State", "Node", "Created", "Duration", "#"].map(h => (
                        <th key={h}>{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {tasks.map(t => {
                      const dur = t.completedAtMs && t.dispatchedAtMs
                        ? t.completedAtMs - t.dispatchedAtMs
                        : t.dispatchedAtMs ? Date.now() - t.dispatchedAtMs : 0;
                      return (
                        <tr key={t.taskId}>
                          <td><span className="mono">{t.taskId.slice(0, 16)}…</span></td>
                          <td><span style={{ fontSize: 12, background: "var(--bg2)", padding: "2px 8px", borderRadius: 6 }}>{t.kind}</span></td>
                          <td><span className={badgeClass(t.state)}>{t.state}</span></td>
                          <td><span className="mono" style={{ fontSize: 10 }}>{t.assignedNodeId ? t.assignedNodeId.slice(0, 12) + "…" : "—"}</span></td>
                          <td style={{ color: "var(--text3)" }}>{fmtTime(t.createdAtMs)}</td>
                          <td style={{ color: "var(--text3)" }}>{fmtDuration(dur)}</td>
                          <td style={{ color: "var(--text3)" }}>{t.attempt}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
              {pages > 1 && (
                <div className="pagination">
                  <span className="page-info">Page {page + 1} of {pages}</span>
                  <div style={{ display: "flex", gap: 8 }}>
                    <button className="page-btn" disabled={page === 0} onClick={() => setPage(p => p - 1)}>← Prev</button>
                    <button className="page-btn" disabled={page >= pages - 1} onClick={() => setPage(p => p + 1)}>Next →</button>
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </Shell>
  );
}
