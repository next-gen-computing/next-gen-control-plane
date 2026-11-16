"use client";

import { Shell } from "@/components/Shell";
import { useState } from "react";

export default function GlyphAnimalsPage() {
  const [isFullscreen, setIsFullscreen] = useState(false);

  return (
    <Shell>
      <div style={{ display: "flex", flexDirection: "column", height: "calc(100vh - 48px)", gap: "12px" }}>
        {/* Top Control Bar */}
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "0 4px" }}>
          <div>
            <h1 style={{ margin: 0, fontSize: "20px", fontWeight: 600, color: "#e8ebf7" }}>
              Glyph Animals — Ambient Canvas
            </h1>
            <p style={{ margin: "2px 0 0 0", fontSize: "13px", color: "rgba(232, 235, 247, 0.5)" }}>
              Procedural ASCII flocking simulation rendered entirely with keyboard characters. Click canvas to scatter.
            </p>
          </div>

          <div style={{ display: "flex", gap: "8px" }}>
            <a
              href="/glyph-animals.html"
              target="_blank"
              rel="noopener noreferrer"
              style={{
                height: "32px",
                padding: "0 14px",
                borderRadius: "999px",
                background: "rgba(255, 255, 255, 0.08)",
                color: "#e8ebf7",
                border: "1px solid rgba(255, 255, 255, 0.12)",
                fontSize: "12px",
                fontWeight: 500,
                display: "inline-flex",
                alignItems: "center",
                gap: "6px",
                textDecoration: "none",
              }}
            >
              Open Full Screen ↗
            </a>
            <button
              onClick={() => setIsFullscreen((prev) => !prev)}
              style={{
                height: "32px",
                padding: "0 14px",
                borderRadius: "999px",
                background: "#3b82f6",
                color: "#ffffff",
                border: "none",
                fontSize: "12px",
                fontWeight: 500,
                cursor: "pointer",
              }}
            >
              {isFullscreen ? "Exit Maximize" : "Maximize Canvas"}
            </button>
          </div>
        </div>

        {/* Canvas Frame */}
        <div
          style={{
            flex: 1,
            borderRadius: isFullscreen ? 0 : "20px",
            overflow: "hidden",
            position: isFullscreen ? "fixed" : "relative",
            inset: isFullscreen ? 0 : "auto",
            zIndex: isFullscreen ? 99999 : 1,
            border: isFullscreen ? "none" : "1px solid rgba(255, 255, 255, 0.12)",
            boxShadow: isFullscreen ? "none" : "0 16px 48px rgba(0, 0, 0, 0.55)",
            background: "#06070c",
          }}
        >
          {isFullscreen && (
            <button
              onClick={() => setIsFullscreen(false)}
              style={{
                position: "fixed",
                top: "16px",
                right: "120px",
                zIndex: 100000,
                height: "34px",
                padding: "0 14px",
                borderRadius: "999px",
                background: "rgba(10, 12, 20, 0.75)",
                backdropFilter: "blur(10px)",
                border: "1px solid rgba(255, 255, 255, 0.15)",
                color: "#e8ebf7",
                fontSize: "12px",
                fontWeight: 500,
                cursor: "pointer",
              }}
            >
              ✕ Exit Fullscreen
            </button>
          )}
          <iframe
            src="/glyph-animals.html"
            style={{
              width: "100%",
              height: "100%",
              border: "none",
              display: "block",
            }}
            title="Glyph Animals Ambient Simulation"
          />
        </div>
      </div>
    </Shell>
  );
}

