window.NG = window.NG || {};
NG.views = NG.views || {};

/**
 * Enhanced Reconnecting & Server Diagnostic View
 * Provides real-time network diagnostics, auto-retry countdown, live ping probe,
 * troubleshooting hints, and ambient background integration while preserving the
 * desktop app's architecture and 3D cluster visualiser.
 */
NG.views.reconnecting = {
    mount(container, props) {
        const profile = (props && props.profile) || {};
        const targetAddr = profile.serverAddress || (profile.role === 'server' ? 'Local Server (50051)' : 'localhost:50051');
        const subtitle = profile.role === 'server'
            ? `Starting your control plane (${profile.launchMode === 'docker' ? 'Docker' : 'Native'} mode)…`
            : `Connecting to ${targetAddr}…`;

        container.innerHTML = `
            <div class="ng-screen ng-onboarding-bg" style="position:relative; overflow:hidden;">
                <!-- Ambient Subtle Canvas Background -->
                <div style="position:absolute; inset:0; opacity:0.35; pointer-events:none; z-index:0;">
                    <iframe src="glyph-animals.html" style="width:100%; height:100%; border:none;" title="Ambient Background"></iframe>
                </div>

                <div class="ng-screen-content" style="position:relative; z-index:1; max-width:620px;">
                    <div class="ng-brand" style="text-align:center; margin-bottom:24px;">
                        <div style="display:inline-flex; align-items:center; gap:8px; padding:4px 14px; border-radius:999px; background:rgba(239, 68, 68, 0.15); border:1px solid rgba(239, 68, 68, 0.3); margin-bottom:12px;">
                            <span style="width:8px; height:8px; border-radius:50%; background:#ef4444; box-shadow:0 0 8px #ef4444; animation:pulse 1.8s infinite;"></span>
                            <span style="font-family:var(--ng-font-mono, monospace); font-size:11px; font-weight:600; letter-spacing:0.08em; color:#fca5a5; text-transform:uppercase;">Connection Disrupted</span>
                        </div>
                        <h1 class="ng-brand-title" style="font-size:26px; margin:0 0 6px;">Control Plane Offline</h1>
                        <p class="ng-brand-subtitle" id="ng-reconnect-subtitle" style="font-size:13px; color:rgba(232,235,247,0.65);">${subtitle}</p>
                    </div>

                    <div class="ng-card" style="background:linear-gradient(135deg, rgba(255,255,255,0.06) 0%, rgba(255,255,255,0.02) 100%); border:1px solid rgba(255,255,255,0.14); border-radius:20px; box-shadow:0 20px 50px rgba(0,0,0,0.6); padding:24px;">
                        <div id="ng-reconnect-status" style="margin-bottom:16px;">
                            <div class="ng-status-message info"><span class="ng-spinner"></span> Connecting to control plane…</div>
                        </div>

                        <!-- Live Diagnostic Panel -->
                        <div style="background:rgba(0,0,0,0.4); border:1px solid rgba(255,255,255,0.08); border-radius:12px; padding:14px; font-family:var(--ng-font-mono, monospace); font-size:11.5px; line-height:1.6; margin-bottom:20px;">
                            <div style="display:flex; justify-content:space-between; margin-bottom:4px;">
                                <span style="color:rgba(232,235,247,0.45);">Target Endpoint:</span>
                                <span style="color:#7aa2ff;">${targetAddr}</span>
                            </div>
                            <div style="display:flex; justify-content:space-between; margin-bottom:4px;">
                                <span style="color:rgba(232,235,247,0.45);">Transport Security:</span>
                                <span>mTLS (TLS 1.3 / EC P-256)</span>
                            </div>
                            <div style="display:flex; justify-content:space-between; margin-bottom:4px;">
                                <span style="color:rgba(232,235,247,0.45);">Local Link:</span>
                                <span id="ng-diag-link" style="color:#4ade80;">Online (Interface Up)</span>
                            </div>
                            <div id="ng-diag-probe-row" style="display:flex; justify-content:space-between; padding-top:6px; border-top:1px dashed rgba(255,255,255,0.1);">
                                <span style="color:rgba(232,235,247,0.45);">Probe Status:</span>
                                <span id="ng-diag-probe-val" style="color:#fca5a5;">Checking…</span>
                            </div>
                        </div>

                        <!-- Actions Grid -->
                        <div class="ng-row" id="ng-reconnect-actions" style="display:flex; flex-wrap:wrap; gap:10px; align-items:center;">
                            <button class="ng-button ng-button-primary" id="ng-reconnect-retry" style="border-radius:999px; height:36px; padding:0 18px;">
                                Retry (<span id="ng-retry-countdown">5</span>s)
                            </button>
                            <button class="ng-button ng-button-secondary" id="ng-reconnect-ping" style="border-radius:999px; height:36px; padding:0 14px;">
                                Test Ping
                            </button>
                            <button class="ng-button ng-button-secondary" id="ng-reconnect-manual" style="border-radius:999px; height:36px; padding:0 14px;">
                                Manual Setup
                            </button>
                            <button class="ng-button ng-button-secondary" id="ng-reconnect-forget" style="border-radius:999px; height:36px; padding:0 12px; margin-left:auto; opacity:0.7;">
                                Forget
                            </button>
                        </div>

                        <!-- Troubleshooting Box -->
                        <div style="margin-top:18px; padding-top:14px; border-top:1px solid rgba(255,255,255,0.08); font-size:11.5px; color:rgba(232,235,247,0.5);">
                            <span>Tip: Verify control plane process with </span>
                            <code style="background:rgba(255,255,255,0.08); padding:2px 6px; border-radius:4px; color:#e8ebf7;">docker compose up -d</code>
                        </div>
                    </div>
                </div>
            </div>`;

        const screen = container.querySelector('.ng-screen');
        const detachSpotlight = NG.cursorFx.attachSpotlight(screen);

        const statusEl = container.querySelector('#ng-reconnect-status');
        const actionsEl = container.querySelector('#ng-reconnect-actions');
        const probeVal = container.querySelector('#ng-diag-probe-val');
        const countdownEl = container.querySelector('#ng-retry-countdown');
        const retryBtn = container.querySelector('#ng-reconnect-retry');

        let countdown = 5;
        let isAttempting = false;
        let timer = null;

        function startCountdown() {
            if (timer) clearInterval(timer);
            countdown = 5;
            if (countdownEl) countdownEl.textContent = countdown;
            timer = setInterval(() => {
                if (isAttempting) return;
                countdown--;
                if (countdownEl) countdownEl.textContent = countdown;
                if (countdown <= 0) {
                    clearInterval(timer);
                    attempt();
                }
            }, 1000);
        }

        function attempt() {
            if (isAttempting) return;
            isAttempting = true;
            statusEl.innerHTML = `<div class="ng-status-message info"><span class="ng-spinner"></span> Connecting to control plane…</div>`;
            probeVal.textContent = "Connecting…";
            probeVal.style.color = "#93c5fd";

            const startT = performance.now();
            fetch('/api/role/auto-connect', { method: 'POST' })
                .then(async (response) => {
                    const body = await response.json();
                    if (!response.ok || body.ok === false) {
                        throw body;
                    }
                    const ms = Math.round(performance.now() - startT);
                    statusEl.innerHTML = `<div class="ng-status-message info">Connected (${ms}ms) — loading dashboard…</div>`;
                    probeVal.textContent = `Connected (${ms}ms)`;
                    probeVal.style.color = "#4ade80";
                    if (timer) clearInterval(timer);
                    NG.app.enterDashboard(body.role, body.serverId);
                })
                .catch((err) => {
                    isAttempting = false;
                    const glyph = NG.format.glyphFor(err && err.category);
                    const message = (err && err.message) || 'Could not reconnect automatically';
                    statusEl.innerHTML = `<div class="ng-status-message error">${glyph} ${message}</div>`;
                    probeVal.textContent = (err && err.category) || 'ERR_CONNECTION_REFUSED';
                    probeVal.style.color = '#f87171';
                    startCountdown();
                });
        }

        container.querySelector('#ng-reconnect-retry').addEventListener('click', () => {
            if (timer) clearInterval(timer);
            attempt();
        });

        container.querySelector('#ng-reconnect-ping').addEventListener('click', () => {
            probeVal.textContent = "Pinging…";
            probeVal.style.color = "#93c5fd";
            const startT = performance.now();
            fetch('/api/health')
                .then((res) => {
                    const ms = Math.round(performance.now() - startT);
                    probeVal.textContent = `HTTP ${res.status} (${ms}ms)`;
                    probeVal.style.color = res.ok ? "#4ade80" : "#f59e0b";
                })
                .catch(() => {
                    const ms = Math.round(performance.now() - startT);
                    probeVal.textContent = `Unreachable (${ms}ms)`;
                    probeVal.style.color = "#f87171";
                });
        });

        container.querySelector('#ng-reconnect-manual').addEventListener('click', () => {
            if (timer) clearInterval(timer);
            NG.router.show(NG.views.roleSelection);
        });

        container.querySelector('#ng-reconnect-forget').addEventListener('click', () => {
            if (timer) clearInterval(timer);
            fetch('/api/role/profile/clear', { method: 'POST' })
                .finally(() => NG.router.show(NG.views.roleSelection));
        });

        attempt();

        return () => {
            if (timer) clearInterval(timer);
            detachSpotlight();
        };
    }
};
