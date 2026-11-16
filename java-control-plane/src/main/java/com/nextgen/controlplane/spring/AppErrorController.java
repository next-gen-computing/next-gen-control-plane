package com.nextgen.controlplane.spring;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom ErrorController that intercepts all HTTP error statuses (404, 500, etc.)
 * across the Next-Gen Control Plane.
 *
 * <p>Prevents the default Spring Boot "Whitelabel Error Page" from ever appearing.
 * Provides:
 * <ul>
 *   <li>Structured JSON responses for /api/* and programmatic REST clients</li>
 *   <li>A top-tier Neumorphic 3D diagnostic page for browser clients, featuring live ping,
 *       auto-detected server LAN IP, one-click copyable worker join commands, and auto-retry countdown.</li>
 * </ul>
 */
@Controller
public class AppErrorController implements ErrorController {

    private static final Logger LOG = LoggerFactory.getLogger(AppErrorController.class);

    @RequestMapping("/error")
    @ResponseBody
    public ResponseEntity<?> handleError(HttpServletRequest request) {
        Object statusAttr = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        Object messageAttr = request.getAttribute(RequestDispatcher.ERROR_MESSAGE);
        Object uriAttr = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        Object exceptionAttr = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);

        int statusCode = 500;
        if (statusAttr != null) {
            try {
                statusCode = Integer.parseInt(statusAttr.toString());
            } catch (NumberFormatException ignored) {}
        }

        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        String requestUri = uriAttr != null ? uriAttr.toString() : request.getRequestURI();
        String message = messageAttr != null && !messageAttr.toString().isBlank()
                ? messageAttr.toString()
                : (status == HttpStatus.NOT_FOUND ? "The requested resource could not be found." : "An unexpected server error occurred.");

        String acceptHeader = request.getHeader("Accept");
        boolean isJsonRequest = (acceptHeader != null && acceptHeader.contains("application/json"))
                || (requestUri != null && requestUri.startsWith("/api/"));

        if (isJsonRequest) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("timestamp", Instant.now().toEpochMilli());
            body.put("status", statusCode);
            body.put("error", status.getReasonPhrase());
            body.put("message", message);
            body.put("path", requestUri);
            if (exceptionAttr instanceof Throwable t) {
                body.put("exception", t.getClass().getSimpleName());
            }
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        }

        // Render top-tier Neumorphic Error page with 3D Canvas, live diagnostics, and copy-paste join instructions
        String lanIp = detectLanIp();
        String html = buildNeumorphicErrorHtml(statusCode, status.getReasonPhrase(), message, requestUri, lanIp);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "html", StandardCharsets.UTF_8));
        headers.setCacheControl("no-cache, no-store, must-revalidate");
        return new ResponseEntity<>(html.getBytes(StandardCharsets.UTF_8), headers, status);
    }

    private static String detectLanIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp() || iface.isVirtual()) continue;
                String name = iface.getName().toLowerCase();
                if (name.contains("docker") || name.contains("veth") || name.contains("br-")) continue;

                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr.getAddress().length == 4) {
                        return addr.getHostAddress();
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private static String buildNeumorphicErrorHtml(int status, String reason, String message, String path, String lanIp) {
        String safePath = path != null ? path.replace("<", "&lt;").replace(">", "&gt;") : "/";
        String safeMsg = message != null ? message.replace("<", "&lt;").replace(">", "&gt;") : "Operation failed.";

        return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Next-Gen Control Plane — Error %d</title>
  <style>
    :root {
      --bg: #090e18;
      --card-bg: linear-gradient(145deg, #0e1526, #090e1a);
      --card-shadow: 12px 12px 28px rgba(0,0,0,0.65), -8px -8px 24px rgba(255,255,255,0.03);
      --inset-shadow: inset 4px 4px 8px rgba(0,0,0,0.7), inset -3px -3px 6px rgba(255,255,255,0.03);
      --btn-shadow: 6px 6px 14px rgba(0,0,0,0.5), -4px -4px 10px rgba(255,255,255,0.04);
      --btn-active: inset 3px 3px 6px rgba(0,0,0,0.7), inset -2px -2px 5px rgba(255,255,255,0.05);
      --cyan: #00d4ff;
      --green: #00ff88;
      --amber: #ffb700;
      --red: #ff3366;
      --purple: #8b5cf6;
      --text: #f0f6fc;
      --text-muted: #8b9bb4;
      --font-mono: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
      --font-sans: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background: var(--bg);
      color: var(--text);
      font-family: var(--font-sans);
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 24px;
      overflow-x: hidden;
      position: relative;
    }
    #errorCanvas {
      position: fixed;
      inset: 0;
      width: 100vw;
      height: 100vh;
      pointer-events: none;
      z-index: 0;
    }
    .error-card {
      position: relative;
      z-index: 10;
      width: 100%%;
      max-width: 680px;
      background: var(--card-bg);
      box-shadow: var(--card-shadow);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 24px;
      padding: 36px;
      backdrop-filter: blur(14px);
    }
    .header-bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 24px;
    }
    .status-badge {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 6px 16px;
      border-radius: 30px;
      background: rgba(255, 51, 102, 0.12);
      border: 1px solid rgba(255, 51, 102, 0.35);
      color: var(--red);
      font-family: var(--font-mono);
      font-size: 0.8rem;
      font-weight: 700;
      letter-spacing: 1px;
    }
    .pulse-dot {
      width: 8px; height: 8px; border-radius: 50%;
      background: currentColor; box-shadow: 0 0 10px currentColor;
      animation: pulse 1.8s infinite;
    }
    @keyframes pulse { 0%%, 100%% { opacity: 1; transform: scale(1); } 50%% { opacity: 0.4; transform: scale(0.85); } }
    .status-code {
      font-family: var(--font-mono);
      font-size: 3.5rem;
      font-weight: 900;
      line-height: 1;
      margin-bottom: 8px;
      background: linear-gradient(135deg, #ff3366, #ff8800);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
    }
    .error-title {
      font-size: 1.4rem;
      font-weight: 700;
      margin-bottom: 12px;
      color: var(--text);
    }
    .error-desc {
      font-size: 0.92rem;
      color: var(--text-muted);
      line-height: 1.55;
      margin-bottom: 24px;
    }
    .diagnostic-well {
      background: #060912;
      box-shadow: var(--inset-shadow);
      border: 1px solid rgba(0, 212, 255, 0.12);
      border-radius: 14px;
      padding: 18px;
      font-family: var(--font-mono);
      font-size: 0.82rem;
      margin-bottom: 24px;
    }
    .diag-row {
      display: grid;
      grid-template-columns: 140px 1fr;
      gap: 12px;
      margin-bottom: 8px;
    }
    .diag-row:last-child { margin-bottom: 0; }
    .diag-label { color: var(--text-muted); }
    .diag-val { color: var(--cyan); word-break: break-all; }
    .command-box {
      background: #04060c;
      border: 1px solid rgba(255, 255, 255, 0.06);
      border-radius: 10px;
      padding: 12px 14px;
      margin-top: 14px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
    }
    .command-text {
      color: var(--green);
      font-family: var(--font-mono);
      font-size: 0.78rem;
      overflow-x: auto;
      white-space: nowrap;
    }
    .btn-copy {
      background: rgba(0, 212, 255, 0.15);
      border: 1px solid rgba(0, 212, 255, 0.3);
      color: var(--cyan);
      border-radius: 6px;
      padding: 4px 10px;
      font-size: 0.72rem;
      font-weight: 600;
      cursor: pointer;
      flex-shrink: 0;
      transition: all 0.2s;
    }
    .btn-copy:hover { background: rgba(0, 212, 255, 0.3); }
    .btn-copy:active { box-shadow: var(--btn-active); transform: scale(0.96); }
    .actions-row {
      display: flex;
      flex-wrap: wrap;
      gap: 14px;
      align-items: center;
      justify-content: space-between;
    }
    .btn {
      background: linear-gradient(145deg, #131c31, #0c1322);
      box-shadow: var(--btn-shadow);
      border: 1px solid rgba(255, 255, 255, 0.08);
      color: var(--text);
      padding: 10px 20px;
      border-radius: 12px;
      font-size: 0.88rem;
      font-weight: 600;
      text-decoration: none;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      gap: 8px;
      transition: all 0.2s;
    }
    .btn:hover {
      border-color: rgba(0, 212, 255, 0.4);
      color: var(--cyan);
      transform: translateY(-2px);
    }
    .btn:active {
      box-shadow: var(--btn-active);
      transform: translateY(1px);
    }
    .btn-primary {
      background: linear-gradient(135deg, #00d4ff, #0077ff);
      color: #050811;
      border: none;
      box-shadow: 0 4px 16px rgba(0, 212, 255, 0.4);
    }
    .btn-primary:hover {
      background: linear-gradient(135deg, #33ddff, #3388ff);
      box-shadow: 0 6px 20px rgba(0, 212, 255, 0.6);
      color: #050811;
    }
    .retry-timer {
      font-size: 0.82rem;
      color: var(--text-muted);
      display: flex;
      align-items: center;
      gap: 8px;
    }
  </style>
</head>
<body>
  <canvas id="errorCanvas"></canvas>

  <div class="error-card">
    <div class="header-bar">
      <div class="status-badge">
        <span class="pulse-dot"></span>
        <span>CONTROL PLANE DIAGNOSTIC</span>
      </div>
      <span style="font-family: var(--font-mono); font-size: 0.78rem; color: var(--text-muted);">
        gRPC: 50051 • REST: 8080
      </span>
    </div>

    <div class="status-code">%d</div>
    <div class="error-title">%s</div>
    <p class="error-desc">%s</p>

    <div class="diagnostic-well">
      <div class="diag-row">
        <span class="diag-label">Requested Path:</span>
        <span class="diag-val">%s</span>
      </div>
      <div class="diag-row">
        <span class="diag-label">Server Host IP:</span>
        <span class="diag-val">%s</span>
      </div>
      <div class="diag-row">
        <span class="diag-label">Control Plane:</span>
        <span class="diag-val">Next-Gen Distributed Engine (JDK 21)</span>
      </div>
      <div class="diag-row">
        <span class="diag-label">Diagnostic Ping:</span>
        <span id="pingStatus" class="diag-val" style="color: var(--amber);">Ready to test</span>
      </div>

      <div class="command-box">
        <span class="command-text" id="cmdText">$env:ROLE="node"; $env:SERVER_HOST="%s"; java -jar control-plane-1.0-SNAPSHOT-all.jar</span>
        <button class="btn-copy" onclick="copyJoinCmd()">Copy PowerShell</button>
      </div>
    </div>

    <div class="actions-row">
      <div style="display: flex; gap: 10px;">
        <a href="/" class="btn btn-primary">
          <span>⚡ Return to Dashboard</span>
        </a>
        <button id="pingBtn" class="btn" onclick="testPing()">
          <span>📡 Ping API</span>
        </button>
      </div>

      <div class="retry-timer">
        <span>Auto-retry in <b id="countdown" style="color: var(--cyan);">5</b>s</span>
      </div>
    </div>
  </div>

  <script>
    // ── Diagnostic Ping ──
    async function testPing() {
      const pingEl = document.getElementById('pingStatus');
      pingEl.textContent = 'Pinging /api/v1/health...';
      pingEl.style.color = 'var(--cyan)';
      const t0 = performance.now();
      try {
        const res = await fetch('/api/v1/health', { signal: AbortSignal.timeout(3000) });
        const latency = Math.round(performance.now() - t0);
        if (res.ok) {
          pingEl.textContent = `Online (${latency}ms) — HTTP 200 OK`;
          pingEl.style.color = 'var(--green)';
        } else {
          pingEl.textContent = `Error ${res.status}: ${res.statusText}`;
          pingEl.style.color = 'var(--red)';
        }
      } catch (e) {
        const latency = Math.round(performance.now() - t0);
        pingEl.textContent = `Failed (${latency}ms): ${e.message || 'Connection refused'}`;
        pingEl.style.color = 'var(--red)';
      }
    }

    function copyJoinCmd() {
      const txt = document.getElementById('cmdText').innerText;
      navigator.clipboard.writeText(txt).then(() => {
        const btn = event.target;
        btn.textContent = 'Copied!';
        setTimeout(() => btn.textContent = 'Copy PowerShell', 2000);
      });
    }

    // ── Auto-retry countdown ──
    let count = 5;
    const cdEl = document.getElementById('countdown');
    setInterval(() => {
      count--;
      if (count <= 0) {
        window.location.href = '/';
      } else {
        cdEl.textContent = count;
      }
    }, 1000);

    // ── 3D Wireframe Canvas in Background ──
    const canvas = document.getElementById('errorCanvas');
    const ctx = canvas.getContext('2d');
    let W, H;
    function resize() {
      W = canvas.width = window.innerWidth;
      H = canvas.height = window.innerHeight;
    }
    window.addEventListener('resize', resize);
    resize();

    // Procedural 3D floating nodes
    const nodes = [];
    for (let i = 0; i < 40; i++) {
      nodes.push({
        x: (Math.random() - 0.5) * 800,
        y: (Math.random() - 0.5) * 600,
        z: Math.random() * 600 - 100,
        vx: (Math.random() - 0.5) * 0.8,
        vy: (Math.random() - 0.5) * 0.8,
        vz: (Math.random() - 0.5) * 0.8
      });
    }

    function render3D() {
      ctx.clearRect(0, 0, W, H);
      const fov = 400;
      const cx = W / 2, cy = H / 2;

      for (let i = 0; i < nodes.length; i++) {
        const n = nodes[i];
        n.x += n.vx; n.y += n.vy; n.z += n.vz;
        if (n.x < -400) n.x = 400; if (n.x > 400) n.x = -400;
        if (n.y < -300) n.y = 300; if (n.y > 300) n.y = -300;
        if (n.z < -100) n.z = 500; if (n.z > 500) n.z = -100;

        const scale = fov / (fov + n.z);
        const px = cx + n.x * scale;
        const py = cy + n.y * scale;

        // Draw connections
        for (let j = i + 1; j < nodes.length; j++) {
          const m = nodes[j];
          const dx = n.x - m.x, dy = n.y - m.y, dz = n.z - m.z;
          const dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
          if (dist < 140) {
            const mScale = fov / (fov + m.z);
            const mx = cx + m.x * mScale;
            const my = cy + m.y * mScale;
            ctx.beginPath();
            ctx.moveTo(px, py);
            ctx.lineTo(mx, my);
            ctx.strokeStyle = `rgba(0, 212, 255, ${0.2 * (1 - dist / 140)})`;
            ctx.lineWidth = 1;
            ctx.stroke();
          }
        }

        ctx.beginPath();
        ctx.arc(px, py, Math.max(1, 3 * scale), 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(255, 51, 102, 0.6)';
        ctx.fill();
      }
      requestAnimationFrame(render3D);
    }
    render3D();
  </script>
</body>
</html>
        """.formatted(status, status, reason, safeMsg, safePath, lanIp, lanIp);
    }
}

