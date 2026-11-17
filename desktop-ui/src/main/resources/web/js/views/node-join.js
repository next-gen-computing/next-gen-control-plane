window.NG = window.NG || {};
NG.views = NG.views || {};

NG.views.nodeJoin = {
    mount(container) {
        function decodeLanServerId(value) {
            if (!value || !/^NGX-[A-F0-9]{4}-[A-F0-9]{4}$/i.test(value)) {
                return value;
            }
            const upper = parseInt(value.slice(4, 8), 16);
            const lower = parseInt(value.slice(9, 13), 16);
            const o1 = (upper >> 8) & 0xFF;
            const o2 = upper & 0xFF;
            const o3 = (lower >> 8) & 0xFF;
            const o4 = lower & 0xFF;
            return `${o1}.${o2}.${o3}.${o4}:50051`;
        }

        container.innerHTML = `
            <style>
                .ng-discovery-tabs {
                    display: flex;
                    gap: 8px;
                    margin-bottom: 20px;
                    border-bottom: 1px solid rgba(255, 255, 255, 0.08);
                    padding-bottom: 12px;
                    width: 100%;
                }
                .ng-tab-btn {
                    background: rgba(255, 255, 255, 0.04);
                    border: 1px solid rgba(255, 255, 255, 0.08);
                    color: var(--ng-text-muted);
                    padding: 8px 16px;
                    border-radius: 20px;
                    font-size: 13px;
                    font-weight: 600;
                    cursor: pointer;
                    transition: all 0.2s ease;
                    display: inline-flex;
                    align-items: center;
                    gap: 6px;
                }
                .ng-tab-btn:hover {
                    background: rgba(255, 255, 255, 0.08);
                    color: var(--ng-text);
                }
                .ng-tab-btn.active {
                    background: linear-gradient(135deg, rgba(0, 212, 255, 0.2), rgba(0, 119, 255, 0.2));
                    border-color: var(--ng-accent);
                    color: #fff;
                    box-shadow: 0 0 14px rgba(0, 212, 255, 0.35);
                }

                /* Quick Share Radar Animation */
                .ng-radar-box {
                    position: relative;
                    width: 100%;
                    padding: 24px;
                    background: radial-gradient(circle at 50% 50%, rgba(14, 28, 54, 0.6) 0%, rgba(6, 10, 20, 0.8) 100%);
                    border: 1px solid rgba(0, 212, 255, 0.2);
                    border-radius: 16px;
                    margin-bottom: 20px;
                    text-align: center;
                    overflow: hidden;
                    box-shadow: inset 3px 3px 10px rgba(0,0,0,0.7), inset -2px -2px 6px rgba(255,255,255,0.03);
                }
                .ng-radar-ripples {
                    position: absolute;
                    top: 50%;
                    left: 50%;
                    transform: translate(-50%, -50%);
                    width: 200px;
                    height: 200px;
                    pointer-events: none;
                }
                .ng-radar-circle {
                    position: absolute;
                    inset: 0;
                    border: 1.5px solid rgba(0, 212, 255, 0.4);
                    border-radius: 50%;
                    animation: ng-radar-pulse 2.8s cubic-bezier(0.2, 0.8, 0.4, 1) infinite;
                }
                .ng-radar-circle:nth-child(2) { animation-delay: 0.9s; }
                .ng-radar-circle:nth-child(3) { animation-delay: 1.8s; }
                @keyframes ng-radar-pulse {
                    0% { transform: scale(0.2); opacity: 0.9; }
                    100% { transform: scale(1.6); opacity: 0; }
                }

                /* Discovered Devices Grid */
                .ng-devices-grid {
                    display: flex;
                    flex-direction: column;
                    gap: 12px;
                    width: 100%;
                    margin-bottom: 20px;
                }
                .ng-device-card {
                    background: linear-gradient(145deg, #161b26, #10141e);
                    border: 1px solid rgba(255, 255, 255, 0.08);
                    box-shadow: 6px 6px 16px rgba(0,0,0,0.6), -4px -4px 12px rgba(255,255,255,0.03);
                    border-radius: 14px;
                    padding: 16px 20px;
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    gap: 16px;
                    transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
                }
                .ng-device-card:hover {
                    border-color: rgba(56, 189, 248, 0.4);
                    box-shadow: 8px 8px 22px rgba(0,0,0,0.7), 0 0 16px rgba(56, 189, 248, 0.15);
                    transform: translateY(-2px);
                }
                .ng-device-info {
                    display: flex;
                    align-items: center;
                    gap: 14px;
                }
                .ng-device-icon {
                    width: 44px;
                    height: 44px;
                    border-radius: 12px;
                    background: linear-gradient(135deg, rgba(0, 212, 255, 0.15), rgba(0, 255, 136, 0.15));
                    border: 1px solid rgba(0, 212, 255, 0.3);
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 22px;
                    flex-shrink: 0;
                    box-shadow: 0 0 12px rgba(0, 212, 255, 0.2);
                }
                .ng-device-title {
                    font-size: 15px;
                    font-weight: 700;
                    color: #fff;
                    margin-bottom: 3px;
                }
                .ng-device-sub {
                    font-size: 12px;
                    color: var(--ng-text-muted);
                    font-family: var(--ng-font-mono, monospace);
                    display: flex;
                    align-items: center;
                    gap: 8px;
                }
                .ng-ping-badge {
                    display: inline-flex;
                    align-items: center;
                    gap: 4px;
                    padding: 2px 7px;
                    border-radius: 10px;
                    font-size: 11px;
                    font-weight: 600;
                    background: rgba(0, 255, 136, 0.12);
                    border: 1px solid rgba(0, 255, 136, 0.3);
                    color: #00ff88;
                }
                .ng-ping-badge.slow {
                    background: rgba(255, 183, 0, 0.12);
                    border-color: rgba(255, 183, 0, 0.3);
                    color: #ffb700;
                }
                .ng-ping-badge.offline {
                    background: rgba(255, 51, 102, 0.15);
                    border-color: rgba(255, 51, 102, 0.35);
                    color: #ff3366;
                }
            </style>

            <div class="ng-screen ng-onboarding-bg">
                <div class="ng-screen-content">
                    <div class="ng-brand">
                        <h1 class="ng-brand-title">Join a Control Plane Cluster</h1>
                        <p class="ng-brand-subtitle">Nearby auto-discovery like Google Quick Share, or worldwide WAN clustering.</p>
                    </div>

                    <div class="ng-card ng-card-hero" style="max-width: 680px; width: 100%;">
                        <!-- Discovery Tabs -->
                        <div class="ng-discovery-tabs">
                            <button class="ng-tab-btn active" id="tabNearby">
                                <span>📡 Nearby (Wi-Fi / Hotspot)</span>
                            </button>
                            <button class="ng-tab-btn" id="tabWan">
                                <span>🌐 Worldwide / WAN</span>
                            </button>
                            <button class="ng-tab-btn" id="tabManual">
                                <span>⌨ Manual IP</span>
                            </button>
                        </div>

                        <!-- 1. Nearby Radar Section -->
                        <div id="sectionNearby">
                            <div class="ng-radar-box">
                                <div class="ng-radar-ripples">
                                    <div class="ng-radar-circle"></div>
                                    <div class="ng-radar-circle"></div>
                                    <div class="ng-radar-circle"></div>
                                </div>
                                <div style="position: relative; z-index: 2;">
                                    <div style="font-size: 28px; margin-bottom: 6px;">⚡</div>
                                    <h3 style="font-size: 15px; font-weight: 700; color: #fff; margin-bottom: 4px;">Quick Share Scanning Active</h3>
                                    <p style="font-size: 12px; color: #94a3b8; max-width: 440px; margin: 0 auto;">
                                        Broadcasting &amp; scanning local Wi-Fi, mobile hotspots, and LAN for active Next-Gen Control Plane Servers.
                                    </p>
                                </div>
                            </div>

                            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px;">
                                <span style="font-size: 12px; font-weight: 700; color: #94a3b8; text-transform: uppercase; letter-spacing: 0.8px;">Detected Servers Nearby:</span>
                                <button class="ng-button ng-button-secondary" id="btnRefreshNearby" style="padding: 4px 10px; font-size: 11px;">🔄 Scan Again</button>
                            </div>

                            <div id="nearbyDevicesList" class="ng-devices-grid">
                                <div style="text-align: center; color: #94a3b8; padding: 24px; background: rgba(0,0,0,0.2); border-radius: 12px; font-size: 13px;">
                                    <span class="ng-spinner" style="display: inline-block; margin-right: 6px;"></span> Scanning for nearby servers...
                                </div>
                            </div>
                        </div>

                        <!-- 2. Worldwide / WAN Clusters Section -->
                        <div id="sectionWan" style="display: none;">
                            <p style="font-size: 13px; color: #94a3b8; margin-bottom: 16px;">
                                Public clusters and edge backbones reachable across the open internet. Real TCP ping latency measured live:
                            </p>

                            <div id="wanClustersList" class="ng-devices-grid">
                                <div style="text-align: center; color: #94a3b8; padding: 24px;">Loading WAN cluster directory…</div>
                            </div>

                            <div class="ng-field" style="margin-top: 14px;">
                                <label for="ng-wan-custom-address">Test or Join Custom WAN Domain / IP</label>
                                <div style="display: flex; gap: 8px;">
                                    <input class="ng-input" id="ng-wan-custom-address" type="text" placeholder="cluster.domain.com:50051 or 103.21.45.67:50051">
                                    <button class="ng-button ng-button-secondary" id="btnPingCustomWan" style="white-space: nowrap;">Ping &amp; Join</button>
                                </div>
                            </div>
                            <div id="wanPingResult" style="margin-top: 6px; font-size: 12px;"></div>
                        </div>

                        <!-- 3. Manual IP Section -->
                        <div id="sectionManual" style="display: none;">
                            <div class="ng-field">
                                <label for="ng-address">Server address or LAN ID</label>
                                <div class="ng-inline-helper-row">
                                    <input class="ng-input" id="ng-address" type="text" placeholder="192.168.1.42:50051 or NGX-C0A8-012A" autocomplete="off">
                                </div>
                            </div>
                            <div class="ng-field">
                                <label for="ng-token">Enrolment token (optional — leave blank for plaintext connection)</label>
                                <input class="ng-input" id="ng-token" type="password" placeholder="Only needed if the server requires mTLS enrolment" autocomplete="off">
                            </div>
                            <div class="ng-row" style="margin-top: 16px;">
                                <button class="ng-button ng-button-primary" id="ng-connect-manual" style="width: 100%; justify-content: center;">Connect to Server</button>
                            </div>
                        </div>

                        <div id="ng-join-status" style="margin-top: 14px;"></div>

                        <div class="ng-row" style="margin-top: 18px; justify-content: space-between;">
                            <button class="ng-button ng-button-secondary" id="ng-back">← Back to Roles</button>
                        </div>
                    </div>
                </div>
            </div>`;

        const screen = container.querySelector('.ng-screen');
        const detachSpotlight = NG.cursorFx.attachSpotlight(screen);

        const tabNearby = container.querySelector('#tabNearby');
        const tabWan = container.querySelector('#tabWan');
        const tabManual = container.querySelector('#tabManual');

        const secNearby = container.querySelector('#sectionNearby');
        const secWan = container.querySelector('#sectionWan');
        const secManual = container.querySelector('#sectionManual');

        const nearbyList = container.querySelector('#nearbyDevicesList');
        const wanList = container.querySelector('#wanClustersList');
        const status = container.querySelector('#ng-join-status');
        const backButton = container.querySelector('#ng-back');

        const addressInput = container.querySelector('#ng-address');
        const tokenInput = container.querySelector('#ng-token');
        const manualConnectBtn = container.querySelector('#ng-connect-manual');

        let scanInterval = null;

        function switchTab(target) {
            tabNearby.classList.toggle('active', target === 'nearby');
            tabWan.classList.toggle('active', target === 'wan');
            tabManual.classList.toggle('active', target === 'manual');

            secNearby.style.display = target === 'nearby' ? 'block' : 'none';
            secWan.style.display = target === 'wan' ? 'block' : 'none';
            secManual.style.display = target === 'manual' ? 'block' : 'none';

            if (target === 'nearby') {
                pollNearby();
            } else if (target === 'wan') {
                loadWanClusters();
            }
        }

        tabNearby.addEventListener('click', () => switchTab('nearby'));
        tabWan.addEventListener('click', () => switchTab('wan'));
        tabManual.addEventListener('click', () => switchTab('manual'));

        backButton.addEventListener('click', () => {
            if (scanInterval) clearInterval(scanInterval);
            NG.router.show(NG.views.roleSelection);
        });

        function showError(category, message) {
            const glyph = NG.format.glyphFor(category);
            status.innerHTML = `<div class="ng-status-message error">${glyph} ${message}</div>`;
        }

        function showInfo(message) {
            status.innerHTML = `<div class="ng-status-message info"><span class="ng-spinner"></span> ${message}</div>`;
        }

        // ── Direct Join Worker Action ───────────────────────────────────────────
        function performJoin(address, enrollmentToken = '', btnElement = null) {
            status.innerHTML = '';
            if (btnElement) {
                btnElement.disabled = true;
                btnElement.innerHTML = `<span class="ng-spinner"></span> Connecting…`;
            }
            showInfo(`Probing ${address}…`);

            fetch('/api/role/probe?address=' + encodeURIComponent(address))
                .then((r) => r.json())
                .then((probe) => {
                    if (!probe.reachable) {
                        if (btnElement) {
                            btnElement.disabled = false;
                            btnElement.textContent = '⚡ Connect';
                        }
                        showError(probe.category, probe.message || 'Server is not reachable on ' + address);
                        return;
                    }

                    showInfo(`Connecting to control plane at ${address}…`);
                    return fetch('/api/role/node/join', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ address, enrollmentToken })
                    })
                        .then(async (response) => {
                            const body = await response.json();
                            if (!response.ok || body.ok === false) {
                                throw body;
                            }
                            if (scanInterval) clearInterval(scanInterval);
                            status.innerHTML = `<div class="ng-status-message info">✅ Connected! Loading cluster dashboard…</div>`;
                            NG.app.enterDashboard(body.role, body.serverId);
                        })
                        .catch((err) => {
                            if (btnElement) {
                                btnElement.disabled = false;
                                btnElement.textContent = '⚡ Connect';
                            }
                            showError(err && err.category, (err && err.message) || 'Could not connect to cluster');
                        });
                })
                .catch(() => {
                    if (btnElement) {
                        btnElement.disabled = false;
                        btnElement.textContent = '⚡ Connect';
                    }
                    showError('UNKNOWN', 'Local service error checking server reachability');
                });
        }

        // ── Nearby Scanner Polling ──────────────────────────────────────────────
        function pollNearby() {
            fetch('/api/role/discover/nearby')
                .then((r) => r.json())
                .then((servers) => {
                    if (!Array.isArray(servers) || servers.length === 0) {
                        nearbyList.innerHTML = `
                            <div style="text-align: center; color: #94a3b8; padding: 24px; background: rgba(0,0,0,0.25); border-radius: 12px; font-size: 13px;">
                                <div style="font-size: 24px; margin-bottom: 6px;">📡</div>
                                <b>No servers detected yet on this Wi-Fi.</b><br>
                                <span style="font-size: 11px; color: #64748b;">Ensure the other laptop started "Server" on the same Wi-Fi, hotspot, or LAN.</span>
                            </div>`;
                        return;
                    }

                    nearbyList.innerHTML = servers.map((s, idx) => `
                        <div class="ng-device-card" id="deviceCard-${idx}">
                            <div class="ng-device-info">
                                <div class="ng-device-icon">💻</div>
                                <div>
                                    <div class="ng-device-title">${s.clusterName || 'Next-Gen Server'}</div>
                                    <div class="ng-device-sub">
                                        <span>${s.address}</span>
                                        <span>•</span>
                                        <span class="ng-ping-badge ${s.latencyMs > 50 ? 'slow' : ''}">
                                            ● ${s.latencyMs}ms
                                        </span>
                                        <span>•</span>
                                        <span style="color:var(--ng-accent);">${s.networkType || 'LAN'}</span>
                                    </div>
                                </div>
                            </div>
                            <div>
                                <button class="ng-button ng-button-primary btn-join-device" data-addr="${s.address}">
                                    ⚡ 1-Click Connect
                                </button>
                            </div>
                        </div>
                    `).join('');

                    nearbyList.querySelectorAll('.btn-join-device').forEach((btn) => {
                        btn.addEventListener('click', (e) => {
                            const addr = btn.getAttribute('data-addr');
                            performJoin(addr, '', btn);
                        });
                    });
                })
                .catch(() => {
                    nearbyList.innerHTML = `
                        <div style="text-align: center; color: #f87171; padding: 18px;">
                            Could not communicate with local discovery scanner.
                        </div>`;
                });
        }

        // ── Worldwide / WAN Directory ───────────────────────────────────────────
        function loadWanClusters() {
            wanList.innerHTML = `<div style="text-align: center; color: #94a3b8; padding: 20px;"><span class="ng-spinner"></span> Measuring live WAN latencies…</div>`;

            fetch('/api/role/discover/wan')
                .then((r) => r.json())
                .then((servers) => {
                    if (!Array.isArray(servers) || servers.length === 0) {
                        wanList.innerHTML = `<div style="text-align: center; color: #94a3b8; padding: 20px;">No public WAN clusters registered in community seed.</div>`;
                        return;
                    }

                    wanList.innerHTML = servers.map((s, idx) => `
                        <div class="ng-device-card">
                            <div class="ng-device-info">
                                <div class="ng-device-icon" style="background: rgba(139, 92, 246, 0.15); border-color: rgba(139, 92, 246, 0.4);">🌐</div>
                                <div>
                                    <div class="ng-device-title">${s.clusterName}</div>
                                    <div class="ng-device-sub">
                                        <span>${s.address}</span>
                                        <span>•</span>
                                        <span class="ng-ping-badge ${!s.reachable ? 'offline' : s.latencyMs > 80 ? 'slow' : ''}">
                                            ${s.reachable ? '● ' + s.latencyMs + 'ms' : '✕ Unreachable'}
                                        </span>
                                        <span>•</span>
                                        <span>${s.region}</span>
                                    </div>
                                </div>
                            </div>
                            <div style="display: flex; gap: 8px;">
                                ${s.reachable ? `
                                    <button class="ng-button ng-button-primary btn-join-wan" data-addr="${s.address}">
                                        ⚡ Connect
                                    </button>
                                ` : `
                                    <button class="ng-button ng-button-secondary" disabled style="opacity: 0.5;">
                                        Firewalled
                                    </button>
                                `}
                            </div>
                        </div>
                    `).join('');

                    wanList.querySelectorAll('.btn-join-wan').forEach((btn) => {
                        btn.addEventListener('click', () => {
                            const addr = btn.getAttribute('data-addr');
                            performJoin(addr, '', btn);
                        });
                    });
                })
                .catch(() => {
                    wanList.innerHTML = `<div style="text-align: center; color: #f87171; padding: 18px;">Failed to load WAN clusters.</div>`;
                });
        }

        // Custom WAN address ping
        const customWanInput = container.querySelector('#ng-wan-custom-address');
        const pingCustomBtn = container.querySelector('#btnPingCustomWan');
        const pingResult = container.querySelector('#wanPingResult');

        pingCustomBtn.addEventListener('click', () => {
            const addr = customWanInput.value.trim();
            if (!addr) return;
            pingResult.innerHTML = `<span class="ng-spinner"></span> Pinging socket at ${addr}…`;

            fetch('/api/role/discover/ping', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ address: addr })
            })
                .then((r) => r.json())
                .then((res) => {
                    if (res.reachable) {
                        pingResult.innerHTML = `
                            <span style="color: #00ff88; font-weight: bold;">🟢 Reached in ${res.latencyMs}ms!</span>
                            <button class="ng-button ng-button-primary" id="btnConnectPinged" style="margin-left: 10px; padding: 4px 12px; font-size: 11px;">Connect Now</button>
                        `;
                        container.querySelector('#btnConnectPinged')?.addEventListener('click', () => {
                            performJoin(res.address, '');
                        });
                    } else {
                        pingResult.innerHTML = `<span style="color: #f87171; font-weight: bold;">🔴 Host Unreachable:</span> ${res.message || 'Connection timed out'}. Check router firewall &amp; port 50051 forwarding.`;
                    }
                })
                .catch((e) => {
                    pingResult.innerHTML = `<span style="color: #f87171;">Ping probe failed: ${e.message}</span>`;
                });
        });

        // ── Manual Connect Action ──────────────────────────────────────────────
        manualConnectBtn.addEventListener('click', () => {
            const raw = addressInput.value.trim();
            const decoded = decodeLanServerId(raw);
            const addr = decoded.trim();
            const token = tokenInput.value.trim();
            if (!addr) {
                showError('UNKNOWN', 'Please enter a server address');
                return;
            }
            performJoin(addr, token, manualConnectBtn);
        });

        // Trigger initial poll and periodic refresh every 2.5s
        pollNearby();
        scanInterval = setInterval(() => {
            if (secNearby.style.display !== 'none') {
                pollNearby();
            }
        }, 2500);

        container.querySelector('#btnRefreshNearby')?.addEventListener('click', pollNearby);

        return () => {
            if (scanInterval) clearInterval(scanInterval);
            detachSpotlight();
        };
    }
};
