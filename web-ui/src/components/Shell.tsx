"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";

const API = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

const NAV = [
  { href: "/",      icon: "⬡", label: "Dashboard" },
  { href: "/nodes", icon: "▣", label: "Nodes"     },
  { href: "/tasks", icon: "⚡", label: "Tasks"     },
  { href: "/jobs",  icon: "◈", label: "Jobs"      },
  { href: "/audit", icon: "◻", label: "Audit Log" },
];

export function Shell({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  const [apiOk, setApiOk] = useState<boolean | null>(null);

  useEffect(() => {
    const check = () =>
      fetch(`${API}/api/v1/health`, { signal: AbortSignal.timeout(3000) })
        .then(() => setApiOk(true))
        .catch(() => setApiOk(false));
    check();
    const id = setInterval(check, 5000);
    return () => clearInterval(id);
  }, []);

  return (
    <div className="shell">
      {/* ── Sidebar ── */}
      <aside className="sidebar">
        <div className="sidebar-logo">
          <div className="logo-icon">⬡</div>
          <div className="logo-title">Control Plane</div>
          <div className="logo-sub">Next-Gen Management</div>
        </div>

        <nav className="sidebar-nav">
          <div className="nav-label">Navigation</div>
          {NAV.map(({ href, icon, label }) => (
            <Link
              key={href}
              href={href}
              className={`nav-item${path === href ? " active" : ""}`}
            >
              <span className="nav-icon">{icon}</span>
              {label}
            </Link>
          ))}
        </nav>

        <div className="sidebar-footer">
          <div className="conn-status">
            <span className={`conn-dot ${apiOk === true ? "alive" : apiOk === false ? "error" : ""}`} />
            <span>
              {apiOk === true ? "LiveREST + WS" : apiOk === false ? "API offline" : "Connecting…"}
            </span>
          </div>
        </div>
      </aside>

      {/* ── Main ── */}
      <main className="main">
        {children}
      </main>
    </div>
  );
}
