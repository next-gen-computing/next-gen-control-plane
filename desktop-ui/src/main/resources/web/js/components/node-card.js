// Mirrors view/NodeCard.java: identity header, status pill, CPU/Memory bars (never a bar for a stale
// reading — the track stays empty and the value reads "n/a"), heartbeat + predicted-risk lines.
window.NG = window.NG || {};

NG.components = NG.components || {};

NG.components.nodeCard = {
    /** @returns {element, update(nodeDto)} */
    create(node) {
        const el = document.createElement('div');
        el.className = 'ng-node-card';
        el.innerHTML = `
            <div class="ng-node-card-header">
                <div>
                    <div class="ng-node-card-name"></div>
                    <div class="ng-node-card-host"></div>
                </div>
                <div class="ng-status-pill">
                    <span class="ng-status-dot"></span>
                    <span class="ng-status-label"></span>
                </div>
            </div>
            <div class="ng-metric-row">
                <div class="ng-metric-top"><span>CPU</span><span class="ng-cpu-value"></span></div>
                <div class="ng-metric-bar-track"><div class="ng-metric-bar-fill ng-cpu-fill"></div></div>
            </div>
            <div class="ng-metric-row">
                <div class="ng-metric-top"><span>Memory</span><span class="ng-mem-value"></span></div>
                <div class="ng-metric-bar-track"><div class="ng-metric-bar-fill ng-mem-fill"></div></div>
            </div>
            <div class="ng-node-card-meta ng-heartbeat"></div>
            <div class="ng-node-card-meta ng-risk"></div>`;

        const refs = {
            name: el.querySelector('.ng-node-card-name'),
            host: el.querySelector('.ng-node-card-host'),
            statusDot: el.querySelector('.ng-status-dot'),
            statusLabel: el.querySelector('.ng-status-label'),
            cpuValue: el.querySelector('.ng-cpu-value'),
            cpuFill: el.querySelector('.ng-cpu-fill'),
            memValue: el.querySelector('.ng-mem-value'),
            memFill: el.querySelector('.ng-mem-fill'),
            heartbeat: el.querySelector('.ng-heartbeat'),
            risk: el.querySelector('.ng-risk')
        };

        function metric(value, stale, valueEl, fillEl) {
            if (stale || value === null) {
                valueEl.textContent = 'n/a';
                fillEl.style.width = '0%';
                fillEl.style.background = NG.palette.STATUS_UNKNOWN;
                return;
            }
            valueEl.textContent = NG.format.percent(value);
            fillEl.style.width = Math.min(100, Math.max(0, value)) + '%';
            fillEl.style.background = NG.palette.utilisationStatus(value, true);
        }

        return {
            element: el,
            update(node) {
                refs.name.textContent = node.name || node.id;
                refs.host.textContent = node.ip ? `${node.ip}:${node.port}` : 'address not reported';

                const statusColor = NG.palette.nodeStatus(node.status);
                refs.statusDot.style.background = statusColor;
                refs.statusLabel.style.color = statusColor;
                refs.statusLabel.textContent = node.status || 'UNKNOWN';

                metric(node.cpuUsage, node.cpuStale, refs.cpuValue, refs.cpuFill);
                metric(node.memoryUsage, node.memoryStale, refs.memValue, refs.memFill);

                refs.heartbeat.textContent = 'Last heartbeat ' + (node.lastHeartbeat || 'Never');
                refs.risk.textContent = 'Predicted failure risk ' + (node.failureProbability || 'N/A');
            }
        };
    }
};
