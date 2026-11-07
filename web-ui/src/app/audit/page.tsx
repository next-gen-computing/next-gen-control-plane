"use client";

import { useEffect, useState, useCallback } from "react";
import { Shell } from "@/components/Shell";

const API = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

interface AuditEvent {
  id: number; occurred_at: string;
  actor_id: string; action: string;
  entity_type: string; entity_id: string;
}

export default function AuditPage() {
  const [events, setEvents]  = useState<AuditEvent[]>([]);
  const [total, setTotal]    = useState(0);
  const [page, setPage]      = useState(0);
  const [pages, setPages]    = useState(1);
  const [loading, setLoading] = useState(true);

  const fetch_ = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch(`${API}/api/v1/audit?page=${page}&size=50`);
      if (!res.ok) return;
      const d = await res.json();
      setEvents(d.events ?? []); setTotal(d.total ?? 0); setPages(d.totalPages ?? 1);
    } finally { setLoading(false); }
  }, [page]);

  useEffect(() => { fetch_(); const id = setInterval(fetch_, 5000); return () => clearInterval(id); }, [fetch_]);

  return (
    <Shell>
      <div className="page">
        <div className="page-header">
          <h1 className="page-title">Audit Log</h1>
          <p className="page-sub">Immutable event trail — every cluster state change, newest first.</p>
        </div>

        <div className="alert alert-info" style={{ marginBottom: 20 }}>
          <span className="alert-icon">ℹ</span>
          <div>
            Audit events are written when tasks/jobs/nodes change state.
            Events appear here once the Spring Boot server is running with a database connected.
            <span style={{ float: "right", opacity: 0.7 }}>{total.toLocaleString()} events</span>
          </div>
        </div>

        <div className="card">
          {loading ? (
            <div style={{ padding: 24 }}>
              {[1,2,3,4,5,6,7,8].map(i => (
                <div key={i} style={{ display: "flex", gap: 12, marginBottom: 10 }}>
                  {[120,100,140,80,160].map((w, j) => (
                    <div key={j} className="skeleton" style={{ height: 12, width: w, borderRadius: 4 }} />
                  ))}
                </div>
              ))}
            </div>
          ) : events.length === 0 ? (
            <div className="empty-state">
              <div className="empty-icon">◻</div>
              <div className="empty-text">No audit events yet</div>
              <div className="empty-sub">Events appear when tasks, jobs, or nodes change state.</div>
            </div>
          ) : (
            <>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      {["Time", "Actor", "Action", "Entity", "ID"].map(h => <th key={h}>{h}</th>)}
                    </tr>
                  </thead>
                  <tbody>
                    {events.map((ev, i) => (
                      <tr key={ev.id ?? i}>
                        <td style={{ color: "var(--text3)", fontSize: 11, whiteSpace: "nowrap" }}>
                          {new Date(ev.occurred_at).toLocaleString()}
                        </td>
                        <td><span className="mono" style={{ color: "var(--text3)" }}>{ev.actor_id || "system"}</span></td>
                        <td>
                          <span className={`action-${ev.action}`} style={{ fontWeight: 600, fontSize: 12 }}>
                            {ev.action}
                          </span>
                        </td>
                        <td>
                          <span style={{ fontSize: 11, background: "var(--bg2)", padding: "2px 7px", borderRadius: 5, color: "var(--text3)" }}>
                            {ev.entity_type}
                          </span>
                        </td>
                        <td><span className="mono" style={{ maxWidth: 180, overflow: "hidden", textOverflow: "ellipsis", display: "inline-block", verticalAlign: "middle" }}>{ev.entity_id || "—"}</span></td>
                      </tr>
                    ))}
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
