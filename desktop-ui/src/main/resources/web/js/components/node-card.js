/**
 * node-card.js — Glass node card for the Dashboard grid.
 * Shows: device type icon, name, OS badge, status pill, CPU/memory bars,
 * heartbeat, and predicted-risk.  Feeds the 3D canvas via NG.networkCanvas
 * when the node list updates.
 *
 * Mirrors view/NodeCard.java: stale readings render "n/a" — never a bar
 * for a stale reading, and never a substituted zero.
 */
window.NG = window.NG || {};
NG.components = NG.components || {};

/* ── Device-type icons (SVG inline) ─────────────────────────────────── */
const DEVICE_ICONS = {
    windows: `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
        <path d="M0 3.449L9.75 2.1v9.451H0zM10.949 1.949L24 0v11.4H10.949zM0 12.6h9.75v9.451L0 20.699zM10.949 12.6H24V24l-13.051-1.949z"/>
    </svg>`,
    mac: `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
        <path d="M12.152 6.896c-.948 0-2.415-1.078-3.96-1.04-2.04.027-3.91 1.183-4.961 3.014-2.117 3.675-.546 9.103 1.519 12.09 1.013 1.454 2.208 3.09 3.792 3.039 1.52-.065 2.09-.987 3.935-.987 1.831 0 2.35.987 3.96.948 1.637-.026 2.676-1.48 3.676-2.948 1.156-1.688 1.636-3.325 1.662-3.415-.039-.013-3.182-1.221-3.22-4.857-.026-3.04 2.48-4.494 2.597-4.559-1.429-2.09-3.623-2.324-4.39-2.376-2-.156-3.675 1.09-4.61 1.09zM15.53 3.83c.843-1.012 1.4-2.427 1.245-3.83-1.207.052-2.662.805-3.532 1.818-.78.896-1.454 2.338-1.273 3.714 1.338.104 2.715-.688 3.559-1.701"/>
    </svg>`,
    linux: `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
        <path d="M12.504 0c-.155 0-.315.008-.48.021-4.226.333-3.105 4.807-3.17 6.298-.076 1.092-.3 1.953-1.05 3.02-.885 1.051-2.127 2.75-2.716 4.521-.278.832-.41 1.684-.287 2.489a.424.424 0 00-.11.135c-.26.268-.45.6-.663.839-.199.199-.485.267-.797.4-.313.136-.658.269-.864.68-.09.189-.136.394-.132.602 0 .199.027.4.055.536.058.399.116.728.04.97-.249.68-.28 1.145-.106 1.484.174.334.535.47.94.601.81.2 1.91.135 2.774.6.926.466 1.866.67 2.616.47.526-.116.97-.464 1.208-.946.587-.003 1.23-.269 2.26-.334.699-.058 1.574.267 2.577.2.025.134.063.198.114.333l.003.003c.359.95 1.353 1.363 2.432 1.033.723-.223 1.517-.742 2.382-.602.14.017.284.052.396.104.249-.05.446-.21.533-.426.061-.174.06-.357-.015-.5-.07-.14-.21-.25-.37-.33-.16-.08-.36-.11-.557-.131-.4-.036-.813-.051-1.175-.174-.318-.11-.552-.309-.677-.568-.243-.515-.214-1.086-.24-1.576.1-.24.295-.456.451-.682.295-.448.494-.982.44-1.579-.068-.756-.415-1.56-.93-2.233.258.094.513.198.756.309.237.11.476.229.712.349.23.112.456.228.683.35.229.12.45.245.675.375.225.126.448.26.675.395.223.136.446.277.672.417.226.14.45.286.68.431.23.143.46.293.69.44.23.147.46.296.69.447.235.15.47.305.703.46.232.153.463.31.694.47.23.157.46.317.69.477.232.161.462.32.694.484.233.16.464.32.695.484.23.16.459.32.69.482.234.163.468.328.7.494.233.166.465.334.7.5.236.166.47.334.705.504.236.17.47.342.705.512.236.172.473.344.71.518.238.173.476.348.714.523.24.176.48.353.72.53.241.177.482.356.724.535.242.178.483.357.727.538.243.181.486.362.73.543.243.183.488.366.733.55.244.184.49.369.735.555.245.186.49.373.737.56.247.188.493.377.74.566.247.189.496.38.743.57.247.19.496.382.744.573.25.192.5.385.748.578.25.193.5.388.75.582.25.194.5.39.752.586.25.196.5.393.752.59.25.197.5.396.754.595.25.197.5.398.754.597.25.198.5.4.753.6.25.2.5.4.753.602.25.2.5.4.75.604.25.2.5.402.75.606.25.203.5.405.75.61.25.203.5.407.75.61.25.204.5.408.75.612.25.205.5.41.75.615.25.205.5.41.75.615l.002.001c.44.28.875.555 1.303.828.428.273.849.543 1.264.81.416.267.824.53 1.226.79.402.26.797.516 1.187.77.39.252.774.5 1.152.746.378.245.75.487 1.117.727.367.24.729.477 1.086.712.357.235.71.467 1.057.697.349.23.692.457 1.03.682.338.225.671.447 1.0.668"/>
    </svg>`,
    raspi: `<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
        <path d="M12 0C5.373 0 0 5.373 0 12s5.373 12 12 12 12-5.373 12-12S18.627 0 12 0zm0 2c5.523 0 10 4.477 10 10S17.523 22 12 22 2 17.523 2 12 6.477 2 12 2zm0 3a7 7 0 100 14A7 7 0 0012 5zm0 2a5 5 0 110 10A5 5 0 0112 7z"/>
    </svg>`,
    server: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
        <rect x="2" y="2" width="20" height="8" rx="2"/>
        <rect x="2" y="14" width="20" height="8" rx="2"/>
        <line x1="6" y1="6" x2="6" y2="6" stroke-width="2" stroke-linecap="round"/>
        <line x1="6" y1="18" x2="6" y2="18" stroke-width="2" stroke-linecap="round"/>
    </svg>`,
};

function detectDeviceType(node) {
    const os = (node.os || node.platform || node.operatingSystem || '').toLowerCase();
    const name = (node.name || '').toLowerCase();
    if (os.includes('win') || name.includes('win'))   return 'windows';
    if (os.includes('mac') || name.includes('mac'))   return 'mac';
    if (os.includes('raspb') || name.includes('pi'))  return 'raspi';
    if (os.includes('linux') || name.includes('linux') || name.includes('ubuntu')) return 'linux';
    return 'server';
}

const OS_LABELS = { windows: 'Windows', mac: 'macOS', linux: 'Linux', raspi: 'Raspberry Pi', server: 'Server' };

NG.components.nodeCard = {
    /** @returns {{ element: HTMLElement, update(nodeDto): void }} */
    create(node) {
        const el = document.createElement('div');
        el.className = 'ng-node-card';

        el.innerHTML = `
            <div class="ng-node-card-header">
                <div style="display:flex;align-items:center;gap:10px;min-width:0">
                    <div class="ng-device-icon" style="flex-shrink:0;opacity:0.75"></div>
                    <div class="ng-node-card-identity" style="min-width:0">
                        <div class="ng-node-card-name"></div>
                        <div class="ng-node-card-host" style="display:flex;align-items:center;gap:5px">
                            <span class="ng-os-label"></span>
                        </div>
                    </div>
                </div>
                <div class="ng-status-pill">
                    <span class="ng-status-dot"></span>
                    <span class="ng-status-label"></span>
                </div>
            </div>
            <div class="ng-metric-row">
                <div class="ng-metric-top">
                    <span class="ng-metric-label">CPU</span>
                    <span class="ng-metric-value ng-cpu-value"></span>
                </div>
                <div class="ng-metric-bar-track"><div class="ng-metric-bar-fill ng-cpu-fill"></div></div>
            </div>
            <div class="ng-metric-row">
                <div class="ng-metric-top">
                    <span class="ng-metric-label">Memory</span>
                    <span class="ng-metric-value ng-mem-value"></span>
                </div>
                <div class="ng-metric-bar-track"><div class="ng-metric-bar-fill ng-mem-fill"></div></div>
            </div>
            <div class="ng-node-card-meta" style="margin-top:4px;gap:12px">
                <span class="ng-heartbeat" style="flex:1"></span>
                <span class="ng-risk"></span>
            </div>`;

        const refs = {
            deviceIcon:  el.querySelector('.ng-device-icon'),
            name:        el.querySelector('.ng-node-card-name'),
            osLabel:     el.querySelector('.ng-os-label'),
            hostRow:     el.querySelector('.ng-node-card-host'),
            statusDot:   el.querySelector('.ng-status-dot'),
            statusLabel: el.querySelector('.ng-status-label'),
            cpuValue:    el.querySelector('.ng-cpu-value'),
            cpuFill:     el.querySelector('.ng-cpu-fill'),
            memValue:    el.querySelector('.ng-mem-value'),
            memFill:     el.querySelector('.ng-mem-fill'),
            heartbeat:   el.querySelector('.ng-heartbeat'),
            risk:        el.querySelector('.ng-risk'),
        };

        function metric(value, stale, valueEl, fillEl) {
            if (stale || value === null || value === undefined) {
                valueEl.textContent = 'n/a';
                valueEl.style.color = NG.palette.STATUS_UNKNOWN;
                fillEl.style.width  = '0%';
                fillEl.style.background = NG.palette.STATUS_UNKNOWN;
                return;
            }
            const color = NG.palette.utilisationStatus(value, true);
            valueEl.textContent  = NG.format.percent(value);
            valueEl.style.color  = color;
            fillEl.style.width   = clamp(value, 0, 100) + '%';
            fillEl.style.background = color;
        }

        function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

        return {
            element: el,
            update(n) {
                const type  = detectDeviceType(n);
                const color = NG.palette.nodeStatus(n.status);

                // Device icon
                refs.deviceIcon.innerHTML = DEVICE_ICONS[type] || DEVICE_ICONS.server;
                refs.deviceIcon.style.color = color;

                // data-status for the CSS left-stripe colour
                el.dataset.status = n.status || 'UNKNOWN';

                refs.name.textContent = n.name || n.id;

                // OS label + IP
                refs.osLabel.textContent = OS_LABELS[type] || 'Unknown';
                refs.osLabel.style.color = 'var(--ng-text-subtle)';
                const addr = n.ip ? `${n.ip}:${n.port}` : '';
                if (addr) {
                    let addrSpan = refs.hostRow.querySelector('.ng-node-addr');
                    if (!addrSpan) {
                        addrSpan = document.createElement('span');
                        addrSpan.className = 'ng-node-addr ng-numeric';
                        addrSpan.style.cssText = 'font-size:11px;color:var(--ng-text-subtle)';
                        refs.hostRow.appendChild(addrSpan);
                    }
                    addrSpan.textContent = addr;
                }

                // Status pill
                refs.statusDot.style.background = color;
                refs.statusDot.style.boxShadow  = `0 0 5px ${color}`;
                refs.statusLabel.textContent     = n.status || 'UNKNOWN';
                refs.statusLabel.style.color     = color;

                metric(n.cpuUsage,    n.cpuStale,    refs.cpuValue, refs.cpuFill);
                metric(n.memoryUsage, n.memoryStale, refs.memValue, refs.memFill);

                refs.heartbeat.textContent = n.lastHeartbeat ? 'Last seen ' + n.lastHeartbeat : '';
                refs.heartbeat.style.color = 'var(--ng-text-subtle)';
                refs.heartbeat.style.fontSize = '11px';

                if (n.failureProbability != null && n.failureProbability !== 'N/A') {
                    const risk = parseFloat(n.failureProbability);
                    const riskColor = isNaN(risk) ? 'var(--ng-text-subtle)'
                        : risk > 0.7 ? NG.palette.STATUS_CRITICAL
                        : risk > 0.4 ? NG.palette.STATUS_WARNING
                        : NG.palette.STATUS_GOOD;
                    refs.risk.textContent = `Risk ${isNaN(risk) ? n.failureProbability : (risk * 100).toFixed(0) + '%'}`;
                    refs.risk.style.color = riskColor;
                    refs.risk.style.fontSize = '11px';
                } else {
                    refs.risk.textContent = '';
                }
            }
        };
    }
};
