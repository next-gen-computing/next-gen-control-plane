"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { ConnectionErrorModal } from "./ConnectionErrorModal";
import { getApiBase } from "@/lib/utils";

const NAV = [
  { href: "/", icon: "⬡", label: "Dashboard" },
  { href: "/nodes", icon: "▣", label: "Nodes" },
  { href: "/tasks", icon: "⚡", label: "Tasks" },
  { href: "/jobs", icon: "◈", label: "Jobs" },
  { href: "/audit", icon: "◻", label: "Audit Log" },
  { href: "/animals", icon: "✦", label: "Glyph Animals" },
];

export function Shell({ children }: { children: React.ReactNode }) {
  const path = usePathname();
  const [apiOk, setApiOk] = useState<boolean | null>(null);
  const [showErrorModal, setShowErrorModal] = useState(false);
  const [hasDismissed, setHasDismissed] = useState(false);
  const apiBase = getApiBase();

  const checkHealth = () =>
    fetch(`${apiBase}/api/v1/health`, { signal: AbortSignal.timeout(3000) })
      .then((res) => {
        if (res.ok) {
          setApiOk(true);
          setShowErrorModal(false);
        } else {
          setApiOk(false);
          if (!hasDismissed) setShowErrorModal(true);
        }
      })
      .catch(() => {
        setApiOk(false);
        if (!hasDismissed) setShowErrorModal(true);
      });

  useEffect(() => {
    checkHealth();
    const id = setInterval(checkHealth, 5000);
    return () => clearInterval(id);
  }, [hasDismissed]);

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
          <div
            className="conn-status"
            onClick={() => {
              if (apiOk === false) setShowErrorModal(true);
            }}
            style={{ cursor: apiOk === false ? "pointer" : "default" }}
            title={apiOk === false ? "Click to open Connection Diagnostics" : undefined}
          >
            <span className={`conn-dot ${apiOk === true ? "alive" : apiOk === false ? "error" : ""}`} />
            <span>
              {apiOk === true ? "LiveREST + WS" : apiOk === false ? "API offline (Inspect)" : "Connecting…"}
            </span>
          </div>
        </div>
      </aside>

      {/* ── Main ── */}
      <main className="main">
        {children}
      </main>

      {/* ── Network & Server Connection Error Modal ── */}
      <ConnectionErrorModal
        apiBase={apiBase}
        isOpen={showErrorModal}
        onRetry={() => {
          checkHealth();
        }}
        onDismiss={() => {
          setShowErrorModal(false);
          setHasDismissed(true);
        }}
      />
    </div>
  );
}
