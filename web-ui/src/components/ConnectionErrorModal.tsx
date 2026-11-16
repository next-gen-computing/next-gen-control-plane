"use client";

import React, { useState, useEffect, useCallback } from "react";

interface ConnectionErrorModalProps {
  apiBase: string;
  isOpen: boolean;
  onRetry: () => void;
  onDismiss: () => void;
}

export function ConnectionErrorModal({
  apiBase,
  isOpen,
  onRetry,
  onDismiss,
}: ConnectionErrorModalProps) {
  const [countdown, setCountdown] = useState(5);
  const [autoRetry, setAutoRetry] = useState(true);
  const [isChecking, setIsChecking] = useState(false);
  const [pingResult, setPingResult] = useState<string | null>(null);
  const [isOnline, setIsOnline] = useState(true);

  // Monitor browser network connectivity
  useEffect(() => {
    setIsOnline(typeof navigator !== "undefined" ? navigator.onLine : true);
    const handleOnline = () => setIsOnline(true);
    const handleOffline = () => setIsOnline(false);
    window.addEventListener("online", handleOnline);
    window.addEventListener("offline", handleOffline);
    return () => {
      window.removeEventListener("online", handleOnline);
      window.removeEventListener("offline", handleOffline);
    };
  }, []);

  // Countdown timer for automatic retry
  useEffect(() => {
    if (!isOpen || !autoRetry || isChecking) return;
    const interval = setInterval(() => {
      setCountdown((prev) => {
        if (prev <= 1) {
          triggerRetry();
          return 5;
        }
        return prev - 1;
      });
    }, 1000);
    return () => clearInterval(interval);
  }, [isOpen, autoRetry, isChecking]);

  const triggerRetry = useCallback(async () => {
    setIsChecking(true);
    setPingResult(null);
    try {
      const res = await fetch(`${apiBase}/api/v1/health`, {
        signal: AbortSignal.timeout(3500),
      });
      if (res.ok) {
        setPingResult("Connected (HTTP 200 OK)");
        onRetry();
      } else {
        setPingResult(`HTTP Error: ${res.status} ${res.statusText}`);
      }
    } catch (err: any) {
      setPingResult(err.message || "Connection refused (Server unreachable)");
    } finally {
      setIsChecking(false);
    }
  }, [apiBase, onRetry]);

  const testPing = async () => {
    setIsChecking(true);
    setPingResult("Pinging...");
    const start = performance.now();
    try {
      const res = await fetch(`${apiBase}/api/v1/health`, {
        signal: AbortSignal.timeout(2500),
      });
      const ms = Math.round(performance.now() - start);
      setPingResult(`Latency: ${ms}ms (${res.status} ${res.statusText})`);
    } catch (err: any) {
      const ms = Math.round(performance.now() - start);
      setPingResult(`Failed in ${ms}ms: ${err.message || "ERR_CONNECTION_REFUSED"}`);
    } finally {
      setIsChecking(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div
      style={{
        position: "fixed",
        inset: 0,
        zIndex: 9999,
        background: "rgba(6, 7, 12, 0.82)",
        backdropFilter: "blur(12px) saturate(180%)",
        WebkitBackdropFilter: "blur(12px) saturate(180%)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: "20px",
        fontFamily: "var(--font-geist-sans, sans-serif)",
      }}
    >
      <div
        style={{
          width: "100%",
          maxWidth: "640px",
          borderRadius: "24px",
          background: "linear-gradient(135deg, rgba(255, 255, 255, 0.05) 0%, rgba(255, 255, 255, 0.01) 100%)",
          border: "1px solid rgba(255, 255, 255, 0.14)",
          boxShadow: "0 24px 64px rgba(0, 0, 0, 0.7), inset 0 1px 0 rgba(255, 255, 255, 0.2)",
          padding: "28px",
          color: "#e8ebf7",
          position: "relative",
          isolation: "isolate",
        }}
      >
        {/* Header Badge */}
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: "18px" }}>
          <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
            <span
              style={{
                display: "inline-block",
                width: "10px",
                height: "10px",
                borderRadius: "50%",
                background: isOnline ? "#ef4444" : "#f59e0b",
                boxShadow: `0 0 12px ${isOnline ? "#ef4444" : "#f59e0b"}`,
                animation: "pulse 1.8s infinite",
              }}
            />
            <span
              style={{
                fontFamily: "var(--font-geist-mono, monospace)",
                fontWeight: 600,
                fontSize: "12px",
                letterSpacing: "0.08em",
                textTransform: "uppercase",
                color: isOnline ? "#f87171" : "#fbbf24",
              }}
            >
              {isOnline ? "Server Disconnected" : "Internet Offline"}
            </span>
          </div>

          <button
            onClick={onDismiss}
            style={{
              background: "transparent",
              border: "none",
              color: "rgba(232, 235, 247, 0.5)",
              fontSize: "20px",
              cursor: "pointer",
              padding: "4px 8px",
              borderRadius: "8px",
            }}
            title="Dismiss to Offline View"
          >
            ×
          </button>
        </div>

        {/* Title & Description */}
        <h2 style={{ margin: "0 0 8px 0", fontSize: "22px", fontWeight: 600, letterSpacing: "-0.02em" }}>
          Control Plane Unreachable
        </h2>
        <p style={{ margin: "0 0 20px 0", fontSize: "14px", color: "rgba(232, 235, 247, 0.65)", lineHeight: 1.5 }}>
          The web dashboard cannot establish a connection with the Next-Gen Control Plane backend. Telemetry streams and job submissions are temporarily paused.
        </p>

        {/* Diagnostic Card */}
        <div
          style={{
            background: "rgba(0, 0, 0, 0.35)",
            border: "1px solid rgba(255, 255, 255, 0.08)",
            borderRadius: "16px",
            padding: "16px",
            marginBottom: "20px",
            fontFamily: "var(--font-geist-mono, monospace)",
            fontSize: "12px",
          }}
        >
          <div style={{ display: "grid", gridTemplateColumns: "130px 1fr", gap: "8px", marginBottom: "6px" }}>
            <span style={{ color: "rgba(232, 235, 247, 0.4)" }}>Endpoint:</span>
            <span style={{ color: "#7aa2ff" }}>{apiBase}/api/v1/health</span>
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "130px 1fr", gap: "8px", marginBottom: "6px" }}>
            <span style={{ color: "rgba(232, 235, 247, 0.4)" }}>Network State:</span>
            <span style={{ color: isOnline ? "#4ade80" : "#f87171" }}>
              {isOnline ? "Online (Physical Link OK)" : "Disconnected (No Local Internet)"}
            </span>
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "130px 1fr", gap: "8px", marginBottom: "6px" }}>
            <span style={{ color: "rgba(232, 235, 247, 0.4)" }}>Transport:</span>
            <span>HTTP/2 gRPC (50051) & Spring REST (8080)</span>
          </div>
          {pingResult && (
            <div style={{ display: "grid", gridTemplateColumns: "130px 1fr", gap: "8px", marginTop: "8px", paddingTop: "8px", borderTop: "1px dashed rgba(255, 255, 255, 0.1)" }}>
              <span style={{ color: "rgba(232, 235, 247, 0.4)" }}>Probe Result:</span>
              <span style={{ color: pingResult.includes("Connected") ? "#4ade80" : "#fca5a5" }}>{pingResult}</span>
            </div>
          )}
        </div>

        {/* Action Buttons */}
        <div style={{ display: "flex", flexWrap: "wrap", gap: "10px", alignItems: "center" }}>
          <button
            onClick={triggerRetry}
            disabled={isChecking}
            style={{
              height: "40px",
              padding: "0 18px",
              borderRadius: "999px",
              background: "#3b82f6",
              color: "#ffffff",
              border: "none",
              fontWeight: 500,
              fontSize: "13px",
              display: "inline-flex",
              alignItems: "center",
              gap: "8px",
              cursor: isChecking ? "wait" : "pointer",
              boxShadow: "0 2px 10px rgba(59, 130, 246, 0.3)",
              transition: "background 0.2s",
            }}
          >
            {isChecking ? "Testing Connection..." : `Retry Connection (${countdown}s)`}
          </button>

          <button
            onClick={testPing}
            disabled={isChecking}
            style={{
              height: "40px",
              padding: "0 16px",
              borderRadius: "999px",
              background: "rgba(255, 255, 255, 0.08)",
              color: "#e8ebf7",
              border: "1px solid rgba(255, 255, 255, 0.12)",
              fontSize: "13px",
              fontWeight: 500,
              cursor: "pointer",
            }}
          >
            Ping Probe
          </button>

          <button
            onClick={() => setAutoRetry((prev) => !prev)}
            style={{
              height: "40px",
              padding: "0 14px",
              borderRadius: "999px",
              background: "transparent",
              color: "rgba(232, 235, 247, 0.6)",
              border: "1px solid rgba(255, 255, 255, 0.08)",
              fontSize: "12px",
              cursor: "pointer",
            }}
          >
            Auto-retry: {autoRetry ? "ON" : "PAUSED"}
          </button>

          <button
            onClick={onDismiss}
            style={{
              height: "40px",
              padding: "0 16px",
              borderRadius: "999px",
              background: "transparent",
              color: "#94a3b8",
              border: "none",
              fontSize: "13px",
              cursor: "pointer",
              marginLeft: "auto",
            }}
          >
            Continue Offline →
          </button>
        </div>

        {/* Troubleshooting Quick Help */}
        <div style={{ marginTop: "20px", paddingTop: "16px", borderTop: "1px solid rgba(255, 255, 255, 0.08)", fontSize: "12px", color: "rgba(232, 235, 247, 0.5)" }}>
          <span>Troubleshooting tip: Run </span>
          <code style={{ background: "rgba(255, 255, 255, 0.08)", padding: "2px 6px", borderRadius: "4px", color: "#e8ebf7", fontFamily: "var(--font-geist-mono, monospace)" }}>
            docker compose -f docker-compose.full-stack.yml up -d
          </code>
          <span> to start the full stack backend.</span>
        </div>
      </div>
    </div>
  );
}

