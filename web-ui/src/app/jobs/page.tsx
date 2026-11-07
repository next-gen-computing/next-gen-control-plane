"use client";

import { useEffect, useState, useCallback } from "react";
import { Shell } from "@/components/Shell";

const API = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

interface Job {
  jobId: string; kind: string; state: string;
  taskCount: number; createdAtMs: number; updatedAtMs: number;
  supersedesJobId: string;
}

function badgeClass(state: string) {
  const m: Record<string,string> = {
    RUNNING:"badge-running", COMPLETED:"badge-completed",
    FAILED:"badge-failed",   PENDING:"badge-pending", QUEUED:"badge-queued",
  };
  return `node-badge ${m[state] ?? "badge-info"}`;
}

function fmtDuration(ms: number) {
  if (!ms || ms < 0) return "—";
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms/1000).toFixed(1)}s`;
  if (ms < 3600000) return `${(ms/60000).toFixed(1)}m`;
  return `${(ms/3600000).toFixed(1)}h`;
}

const STATES = ["", "PENDING", "RUNNING", "COMPLETED", "FAILED"];

export default function JobsPage() {
  const [jobs, setJobs]       = useState<Job[]>([]);
  const [total, setTotal]     = useState(0);
  const [page, setPage]       = useState(0);
  const [pages, setPages]     = useState(1);
  const [filter, setFilter]   = useState("");
  const [loading, setLoading] = useState(true);

  const fetch_ = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch(`${API}/api/v1/jobs?page=${page}&size=40${filter ? `&state=${filter}` : ""}`);
      if (!res.ok) return;
      const d = await res.json();
      setJobs(d.jobs ?? []); setTotal(d.total ?? 0); setPages(d.totalPages ?? 1);
    } finally { setLoading(false); }
  }, [page, filter]);

  useEffect(() => { fetch_(); }, [fetch_]);

  return (
    <Shell>
      <div className="page">
        <div className="page-header">
          <h1 className="page-title">Jobs</h1>
          <p className="page-sub">Submitted jobs — each contains one or more distributed tasks.</p>
        </div>

        {/* Filters */}
        <div className="filter-row">
          {STATES.map(s => (
            <button key={s} className={`pill${filter === s ? " active" : ""}`}
              onClick={() => { setFilter(s); setPage(0); }}>
              {s || "All"}
            </button>
          ))}
          <span style={{ marginLeft: "auto", fontSize: 11, color: "var(--text3)", alignSelf: "center" }}>
            {total.toLocaleString()} jobs
          </span>
        </div>

        {/* Table */}
        <div className="card">
          {loading ? (
            <div style={{ padding: 24 }}>
              {[1,2,3,4,5].map(i => (
                <div key={i} style={{ display: "flex", gap: 12, marginBottom: 10 }}>
                  {[140,80,80,50,100,80].map((w, j) => (
                    <div key={j} className="skeleton" style={{ height: 14, width: w, borderRadius: 4 }} />
                  ))}
                </div>
              ))}
            </div>
          ) : jobs.length === 0 ? (
            <div className="empty-state">
              <div className="empty-icon">◈</div>
              <div className="empty-text">No jobs found</div>
              <div className="empty-sub">Jobs appear when tasks are grouped and submitted.</div>
            </div>
          ) : (
            <>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      {["Job ID", "Kind", "State", "Tasks", "Created", "Duration"].map(h => (
                        <th key={h}>{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {jobs.map(job => (
                      <tr key={job.jobId}>
                        <td><span className="mono">{job.jobId.slice(0, 20)}…</span></td>
                        <td>
                          <span style={{ fontSize: 12, background: "var(--bg2)", padding: "2px 8px", borderRadius: 6 }}>
                            {job.kind}
                          </span>
                        </td>
                        <td><span className={badgeClass(job.state)}>{job.state}</span></td>
                        <td style={{ textAlign: "center" }}>
                          <span style={{ background: "var(--bg2)", padding: "2px 8px", borderRadius: 6, fontSize: 12 }}>
                            {job.taskCount}
                          </span>
                        </td>
                        <td style={{ color: "var(--text3)", fontSize: 12 }}>
                          {job.createdAtMs ? new Date(job.createdAtMs).toLocaleString() : "—"}
                        </td>
                        <td style={{ color: "var(--text3)", fontSize: 12 }}>
                          {job.updatedAtMs && job.createdAtMs
                            ? fmtDuration(job.updatedAtMs - job.createdAtMs)
                            : "—"}
                        </td>
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
