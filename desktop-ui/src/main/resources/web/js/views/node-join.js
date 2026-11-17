window.NG = window.NG || {};
NG.views = window.NG.views || {};

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
                    margin-bottom: 18px;
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
                    background: linear-gradient(135deg, rgba(16, 185, 129, 0.25), rgba(5, 150, 105, 0.25));
                    border-color: #10b981;
                    color: #fff;
                    box-shadow: 0 4px 14px rgba(16, 185, 129, 0.25), var(--ng-btn-shadow);
                }

                /* 3D Green Avionics & Sonar Radar Chassis */
                .ng-radar-chassis {
                    position: relative;
                    width: 100%;
                    padding: 16px;
                    background: linear-gradient(145deg, #0d1219, #06090e);
                    border: 1px solid rgba(16, 185, 129, 0.28);
                    border-radius: 18px;
                    margin-bottom: 18px;
                    overflow: hidden;
                    box-shadow: 12px 12px 28px rgba(0,0,0,0.85), -6px -6px 16px rgba(255,255,255,0.02), inset 0 0 20px rgba(0,0,0,0.9);
                }
                .ng-radar-topbar {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    margin-bottom: 12px;
                    padding-bottom: 10px;
                    border-bottom: 1px solid rgba(16, 185, 129, 0.15);
                }
                .ng-radar-live-badge {
                    display: inline-flex;
                    align-items: center;
                    gap: 8px;
                    font-family: var(--ng-font-mono, monospace);
                    font-size: 11px;
                    font-weight: 700;
                    color: #10b981;
                    letter-spacing: 0.8px;
                }
                .ng-radar-beacon-dot {
                    width: 8px;
                    height: 8px;
                    border-radius: 50%;
                    background: #22c55e;
                    box-shadow: 0 0 10px #22c55e;
                    animation: ng-beacon-pulse 1.8s infinite;
                }
                @keyframes ng-beacon-pulse {
                    0% { transform: scale(0.8); opacity: 0.5; }
                    50% { transform: scale(1.3); opacity: 1; box-shadow: 0 0 14px #4ade80; }
                    100% { transform: scale(0.8); opacity: 0.5; }
                }
                .ng-zero-trust-badge {
                    display: inline-flex;
                    align-items: center;
                    gap: 5px;
                    background: rgba(16, 185, 129, 0.12);
                    border: 1px solid rgba(16, 185, 129, 0.35);
                    border-radius: 20px;
                    padding: 3px 10px;
                    font-size: 10px;
                    font-weight: 700;
                    color: #34d399;
                    letter-spacing: 0.5px;
                }
                .ng-radar-viewport {
                    position: relative;
                    width: 100%;
                    height: 380px;
                    border-radius: 14px;
                    background: #020b06;
                    border: 1px solid rgba(16, 185, 129, 0.2);
                    box-shadow: inset 0 0 25px rgba(0,0,0,0.85);
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    overflow: hidden;
                }
                .ng-radar-viewport canvas {
                    width: 100% !important;
                    height: 100% !important;
                    display: block;
                }

                /* Target Inspector HUD Card */
                .ng-radar-target-inspector {
                    background: linear-gradient(145deg, #121822, #0b0f16);
                    border: 1px solid rgba(16, 185, 129, 0.35);
                    border-radius: 14px;
                    padding: 14px 18px;
                    margin-bottom: 18px;
                    box-shadow: 6px 6px 18px rgba(0,0,0,0.7), 0 0 16px rgba(16, 185, 129, 0.12);
                    animation: ng-inspector-pop 0.25s cubic-bezier(0.16, 1, 0.3, 1);
                }
                @keyframes ng-inspector-pop {
                    from { opacity: 0; transform: translateY(-4px); }
                    to { opacity: 1; transform: translateY(0); }
                }
                .ng-inspector-header {
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    gap: 12px;
                    margin-bottom: 10px;
                }
                .ng-inspector-icon {
                    width: 42px;
                    height: 42px;
                    border-radius: 10px;
                    background: rgba(16, 185, 129, 0.12);
                    border: 1px solid rgba(16, 185, 129, 0.3);
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 20px;
                    flex-shrink: 0;
                }
                .ng-inspector-title {
                    font-size: 15px;
                    font-weight: 700;
                    color: #f1f5f9;
                }
                .ng-inspector-sub {
                    font-size: 11px;
                    color: #94a3b8;
                    font-family: var(--ng-font-mono, monospace);
                }
                .ng-security-pill {
                    font-size: 10px;
                    font-weight: 700;
                    padding: 3px 8px;
                    border-radius: 12px;
                    letter-spacing: 0.6px;
                }
                .ng-security-pill.approved {
                    background: rgba(16, 185, 129, 0.15);
                    border: 1px solid rgba(16, 185, 129, 0.4);
                    color: #34d399;
                }
                .ng-security-pill.quarantine {
                    background: rgba(245, 158, 11, 0.15);
                    border: 1px solid rgba(245, 158, 11, 0.4);
                    color: #fbbf24;
                }
                .ng-inspector-specs-row {
                    display: flex;
                    flex-wrap: wrap;
                    gap: 8px;
                    margin-bottom: 12px;
                }
                .ng-spec-chip {
                    font-size: 11px;
                    font-family: var(--ng-font-mono, monospace);
                    background: rgba(255, 255, 255, 0.04);
                    border: 1px solid rgba(255, 255, 255, 0.08);
                    padding: 3px 8px;
                    border-radius: 6px;
                    color: #cbd5e1;
                }
                .ng-inspector-actions {
                    display: flex;
                    justify-content: flex-end;
                    gap: 10px;
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
                    padding: 14px 18px;
                    display: flex;
                    align-items: center;
                    justify-content: space-between;
                    gap: 16px;
                    transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
                }
                .ng-device-card:hover {
                    border-color: rgba(16, 185, 129, 0.5);
                    box-shadow: 8px 8px 22px rgba(0,0,0,0.7), 0 0 16px rgba(16, 185, 129, 0.15);
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
                    background: linear-gradient(135deg, rgba(16, 185, 129, 0.15), rgba(59, 130, 246, 0.15));
                    border: 1px solid rgba(16, 185, 129, 0.3);
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 22px;
                    flex-shrink: 0;
                    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.4);
                }
                .ng-device-title {
                    font-size: 14px;
                    font-weight: 700;
                    color: #fff;
                    margin-bottom: 3px;
                }
                .ng-device-sub {
                    font-size: 11px;
                    color: var(--ng-text-muted);
                    font-family: var(--ng-font-mono, monospace);
                    display: flex;
                    flex-wrap: wrap;
                    align-items: center;
                    gap: 6px;
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
                        <p class="ng-brand-subtitle">3D Avionics &amp; Sonar Radar Scanning with Zero-Trust Security Gate.</p>
                    </div>

                    <div class="ng-card ng-card-hero" style="max-width: 720px; width: 100%;">
                        <!-- Discovery Tabs -->
                        <div class="ng-discovery-tabs">
                            <button class="ng-tab-btn active" id="tabNearby">
                                <span>📡 3D Green Radar (LAN)</span>
                            </button>
                            <button class="ng-tab-btn" id="tabWan">
                                <span>🌐 Worldwide / WAN</span>
                            </button>
                            <button class="ng-tab-btn" id="tabManual">
                                <span>⌨ Manual IP</span>
                            </button>
                        </div>

                        <!-- 1. Nearby 3D Radar Section -->
                        <div id="sectionNearby">
                            <div class="ng-radar-chassis">
                                <div class="ng-radar-topbar">
                                    <div class="ng-radar-live-badge">
                                        <span class="ng-radar-beacon-dot"></span>
                                        <span>3D AVIONICS &amp; SONAR RADAR SCANNER</span>
                                    </div>
                                    <div class="ng-zero-trust-badge">
                                        <span>🛡️ ZERO-TRUST: NO ANONYMOUS ACCESS</span>
                                    </div>
                                </div>
                                <div class="ng-radar-viewport">
                                    <canvas id="nearbyRadarCanvas" width="620" height="400"></canvas>
                                </div>
                            </div>

                            <!-- Selected Target Inspector HUD -->
                            <div id="radarTargetInspector" class="ng-radar-target-inspector" style="display: none;">
                                <div class="ng-inspector-header">
                                    <div class="ng-inspector-icon" id="inspectorDeviceIcon">💻</div>
                                    <div style="flex: 1;">
                                        <div class="ng-inspector-title" id="inspectorDeviceName">MacBook Pro M2</div>
                                        <div class="ng-inspector-sub" id="inspectorDeviceSub">192.168.1.18:50051 • 2ms Latency</div>
                                    </div>
                                    <div id="inspectorSecurityBadge" class="ng-security-pill quarantine">QUARANTINED</div>
                                </div>
                                <div class="ng-inspector-specs-row" id="inspectorSpecsRow">
                                    <span class="ng-spec-chip" id="specCores">12 Cores</span>
                                    <span class="ng-spec-chip" id="specRam">32.0 GB RAM</span>
                                    <span class="ng-spec-chip" id="specOs">macOS</span>
                                </div>
                                <div class="ng-inspector-actions">
                                    <button class="ng-button ng-button-primary" id="btnInspectorConnect">⚡ Request Admission &amp; Connect</button>
                                </div>
                            </div>

                            <div style="display: flex; justify-content: space-between; align-items: center; margin-top: 14px; margin-bottom: 10px;">
                                <span style="font-size: 12px; font-weight: 700; color: #94a3b8; text-transform: uppercase; letter-spacing: 0.8px;">Detected Airspace Targets:</span>
                                <button class="ng-button ng-button-secondary" id="btnRefreshNearby" style="padding: 4px 10px; font-size: 11px;">🔄 Ping Airspace</button>
                            </div>

                            <div id="nearbyDevicesList" class="ng-devices-grid">
                                <div style="text-align: center; color: #94a3b8; padding: 24px; background: rgba(0,0,0,0.2); border-radius: 12px; font-size: 13px;">
                                    <span class="ng-spinner" style="display: inline-block; margin-right: 6px;"></span> Sweeping local Wi-Fi, hotspot &amp; LAN...
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

        // Target Inspector Elements
        const targetInspector = container.querySelector('#radarTargetInspector');
        const inspectorIcon = container.querySelector('#inspectorDeviceIcon');
        const inspectorName = container.querySelector('#inspectorDeviceName');
        const inspectorSub = container.querySelector('#inspectorDeviceSub');
        const inspectorBadge = container.querySelector('#inspectorSecurityBadge');
        const specCores = container.querySelector('#specCores');
        const specRam = container.querySelector('#specRam');
        const specOs = container.querySelector('#specOs');
        const btnInspectorConnect = container.querySelector('#btnInspectorConnect');

        let selectedTargetData = null;
        let radarScanner = null;
        let scanInterval = null;
        let pollStatusTimer = null;

        // Initialize 3D Green Radar Scanner
        const radarCanvas = container.querySelector('#nearbyRadarCanvas');
        if (radarCanvas && window.RadarScanner) {
            radarScanner = new window.RadarScanner(radarCanvas, {
                rpm: 42,
                rangeMeters: 50,
                zeroTrustEnforced: true,
                isServerMode: false,
                onTargetSelect: (t) => {
                    selectTarget(t);
                }
            });
        }

        function selectTarget(t) {
            selectedTargetData = t;
            if (!t) {
                targetInspector.style.display = 'none';
                return;
            }
            targetInspector.style.display = 'block';

            const devType = t.deviceType || 'windows';
            if (devType === 'mac') {
                inspectorIcon.textContent = '🍏';
                specOs.textContent = t.osName || 'macOS Darwin';
            } else if (devType === 'linux') {
                inspectorIcon.textContent = '🐧';
                specOs.textContent = t.osName || 'Linux Workstation';
            } else {
                inspectorIcon.textContent = '🪟';
                specOs.textContent = t.osName || 'Windows 11';
            }

            inspectorName.textContent = t.clusterName || t.hostname || 'Control Plane Server';
            inspectorSub.textContent = `${t.address} • ${t.latencyMs || 2}ms Latency • ${t.networkType || 'Wi-Fi/LAN'}`;

            if (t.isApproved || !t.zeroTrustEnforced) {
                inspectorBadge.className = 'ng-security-pill approved';
                inspectorBadge.textContent = 'ZERO-TRUST ADMITTED';
            } else {
                inspectorBadge.className = 'ng-security-pill quarantine';
                inspectorBadge.textContent = 'ADMISSION REQUIRED';
            }

            specCores.textContent = `${t.cores || 8} CPU Cores`;
            specRam.textContent = `${t.ramGb ? t.ramGb + ' GB RAM' : '16.0 GB RAM'}`;

            btnInspectorConnect.onclick = () => {
                performJoin(t.address, '', btnInspectorConnect);
            };
        }

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
            if (pollStatusTimer) clearInterval(pollStatusTimer);
            if (radarScanner) radarScanner.stop();
            NG.router.show(NG.views.roleSelection);
        });

        function showError(category, message) {
            const glyph = NG.format.glyphFor(category);
            status.innerHTML = `<div class="ng-status-message error">${glyph} ${message}</div>`;
        }

        function showInfo(message) {
            status.innerHTML = `<div class="ng-status-message info"><span class="ng-spinner"></span> ${message}</div>`;
        }

        // ── Direct Join Worker Action with Zero-Trust Handshake ──────────────────
        function performJoin(address, enrollmentToken = '', btnElement = null) {
            status.innerHTML = '';
            if (btnElement) {
                btnElement.disabled = true;
                btnElement.innerHTML = `<span class="ng-spinner"></span> Probing…`;
            }
            showInfo(`Probing ${address}…`);

            fetch('/api/role/probe?address=' + encodeURIComponent(address))
                .then((r) => r.json())
                .then(async (probe) => {
                    if (!probe.reachable) {
                        if (btnElement) {
                            btnElement.disabled = false;
                            btnElement.textContent = '⚡ Connect';
                        }
                        showError(probe.category, probe.message || 'Server is not reachable on ' + address);
                        return;
                    }

                    // Read local machine hardware specs
                    let hw = {};
                    try {
                        const hwRes = await fetch('/api/role/hardware');
                        if (hwRes.ok) hw = await hwRes.json();
                    } catch (ignored) {}

                    showInfo(`Submitting Zero-Trust admission request to ${address}…`);

                    // Send admission request
                    fetch('/api/role/discover/request-join', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            serverAddress: address,
                            nodeId: hw.hostname || 'worker-laptop',
                            hostname: hw.hostname || 'Worker Laptop',
                            ip: hw.lanIp || 'unknown',
                            os: hw.osName || 'Windows 11',
                            osArch: hw.osArch || 'x86_64',
                            cores: hw.cores || 8,
                            ramGb: hw.ramGb || 16.0
                        })
                    })
                    .then(async (reqRes) => {
                        const reqData = await reqRes.json();
                        const nodeId = reqData.nodeId || hw.hostname || 'worker-laptop';

                        showInfo(`🛡️ Admission request held in quarantine. Awaiting administrator approval on server Radar Console...`);

                        // Poll for operator approval
                        if (pollStatusTimer) clearInterval(pollStatusTimer);
                        let pollCount = 0;
                        pollStatusTimer = setInterval(async () => {
                            pollCount++;
                            try {
                                const stRes = await fetch(`/api/role/discover/join-status?nodeId=${encodeURIComponent(nodeId)}&serverAddress=${encodeURIComponent(address)}`);
                                if (stRes.ok) {
                                    const stData = await stRes.json();
                                    if (stData.status === 'APPROVED') {
                                        clearInterval(pollStatusTimer);
                                        showInfo(`✅ Operator APPROVED admission! Authenticating gRPC connection...`);

                                        // Connect to gRPC
                                        fetch('/api/role/node/join', {
                                            method: 'POST',
                                            headers: { 'Content-Type': 'application/json' },
                                            body: JSON.stringify({ address, enrollmentToken: stData.token || enrollmentToken })
                                        })
                                        .then(async (response) => {
                                            const body = await response.json();
                                            if (!response.ok || body.ok === false) throw body;
                                            if (scanInterval) clearInterval(scanInterval);
                                            if (radarScanner) radarScanner.stop();
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
                                    } else if (stData.status === 'REJECTED') {
                                        clearInterval(pollStatusTimer);
                                        if (btnElement) {
                                            btnElement.disabled = false;
                                            btnElement.textContent = '⚡ Connect';
                                        }
                                        showError('AUTHENTICATION', 'Cluster operator declined admission for this machine.');
                                    }
                                }
                            } catch (e) {
                                // silent retry
                            }

                            if (pollCount > 60) { // 90 seconds timeout
                                clearInterval(pollStatusTimer);
                                if (btnElement) {
                                    btnElement.disabled = false;
                                    btnElement.textContent = '⚡ Connect';
                                }
                                showError('TIMEOUT', 'Admission request timed out waiting for server operator approval.');
                            }
                        }, 1500);
                    })
                    .catch(() => {
                        // If direct join request endpoint not on server, fallback to direct node join
                        return fetch('/api/role/node/join', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ address, enrollmentToken })
                        })
                        .then(async (response) => {
                            const body = await response.json();
                            if (!response.ok || body.ok === false) throw body;
                            if (scanInterval) clearInterval(scanInterval);
                            if (radarScanner) radarScanner.stop();
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
                    if (radarScanner && Array.isArray(servers)) {
                        radarScanner.setTargets(servers);
                    }

                    if (!Array.isArray(servers) || servers.length === 0) {
                        nearbyList.innerHTML = `
                            <div style="text-align: center; color: #94a3b8; padding: 24px; background: rgba(0,0,0,0.25); border-radius: 12px; font-size: 13px;">
                                <div style="font-size: 24px; margin-bottom: 6px;">📡</div>
                                <b>No servers detected yet on this Wi-Fi.</b><br>
                                <span style="font-size: 11px; color: #64748b;">Ensure the other laptop started "Server" on the same Wi-Fi, hotspot, or LAN.</span>
                            </div>`;
                        return;
                    }

                    // Auto-select first target if none selected
                    if (!selectedTargetData && servers.length > 0) {
                        selectTarget(servers[0]);
                    }

                    nearbyList.innerHTML = servers.map((s, idx) => {
                        const devType = s.deviceType || 'windows';
                        const icon = devType === 'mac' ? '🍏' : (devType === 'linux' ? '🐧' : '🪟');
                        const devLabel = devType === 'mac' ? 'Apple MacBook' : (devType === 'linux' ? 'Linux Workstation' : 'Windows 11');
                        const cores = s.cores || 8;
                        const ram = s.ramGb ? s.ramGb + ' GB' : '16 GB';

                        return `
                        <div class="ng-device-card" id="deviceCard-${idx}" style="cursor: pointer;">
                            <div class="ng-device-info">
                                <div class="ng-device-icon">${icon}</div>
                                <div>
                                    <div class="ng-device-title">${s.clusterName || 'Next-Gen Server'} <span style="font-size: 11px; color: #10b981; font-weight: normal;">[${devLabel}]</span></div>
                                    <div class="ng-device-sub">
                                        <span>${s.address}</span>
                                        <span>•</span>
                                        <span class="ng-ping-badge ${s.latencyMs > 50 ? 'slow' : ''}">
                                            ● ${s.latencyMs}ms
                                        </span>
                                        <span>•</span>
                                        <span>${cores} Cores &bull; ${ram}</span>
                                        <span>•</span>
                                        <span style="color: #34d399;">🛡️ ZERO-TRUST</span>
                                    </div>
                                </div>
                            </div>
                            <div>
                                <button class="ng-button ng-button-primary btn-join-device" data-addr="${s.address}">
                                    ⚡ 1-Click Connect
                                </button>
                            </div>
                        </div>`;
                    }).join('');

                    nearbyList.querySelectorAll('.ng-device-card').forEach((card, idx) => {
                        card.addEventListener('click', (e) => {
                            if (e.target.classList.contains('btn-join-device')) return;
                            selectTarget(servers[idx]);
                        });
                    });

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
            if (pollStatusTimer) clearInterval(pollStatusTimer);
            if (radarScanner) radarScanner.stop();
            detachSpotlight();
        };
    }
};
