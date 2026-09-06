// Mirrors view/NodeManagementView.java: the table view the categorical chart palette depends on for
// relief — every figure the Monitoring charts plot must be readable here as plain text.
window.NG = window.NG || {};
NG.views = NG.views || {};

NG.views.nodes = {
    mount(container) {
        container.innerHTML = `
            <div class="ng-page">
                <h1 class="ng-page-title">Nodes</h1>
                <p class="ng-page-subtitle">Every node the control plane knows about, including ones that have stopped reporting</p>
                <div class="ng-page-scroll">
                    <table class="ng-data-table">
                        <thead>
                            <tr>
                                <th>Node</th><th>Address</th><th>Status</th>
                                <th>CPU</th><th>Memory</th><th>Last heartbeat</th><th>Failure risk</th>
                            </tr>
                        </thead>
                        <tbody id="ng-nodes-body"></tbody>
                    </table>
                    <div class="ng-empty-state" id="ng-nodes-empty" hidden>
                        <div class="ng-empty-state-body">No nodes have reported yet</div>
                    </div>
                </div>
            </div>`;

        const body = container.querySelector('#ng-nodes-body');
        const emptyEl = container.querySelector('#ng-nodes-empty');

        function usageCell(value, stale) {
            if (stale) return `<td class="ng-numeric" style="color:${NG.palette.STATUS_UNKNOWN}">n/a</td>`;
            const color = NG.palette.utilisationStatus(value, true);
            return `<td class="ng-numeric" style="color:${color}">${NG.format.percent(value)}</td>`;
        }

        function render(nodes) {
            emptyEl.hidden = nodes.length > 0;
            body.innerHTML = nodes.map((n) => {
                const statusColor = NG.palette.nodeStatus(n.status);
                const address = n.ip ? `${n.ip}:${n.port}` : 'not reported';
                return `<tr>
                    <td>${n.name || n.id}</td>
                    <td class="ng-numeric">${address}</td>
                    <td><span class="ng-status-dot" style="display:inline-block;background:${statusColor};width:8px;height:8px;border-radius:50%;margin-right:6px;"></span><span style="color:${statusColor}">${n.status || 'UNKNOWN'}</span></td>
                    ${usageCell(n.cpuUsage, n.cpuStale)}
                    ${usageCell(n.memoryUsage, n.memoryStale)}
                    <td>${n.lastHeartbeat || 'Never'}</td>
                    <td>${n.failureProbability || 'N/A'}</td>
                </tr>`;
            }).join('');
        }

        const close = NG.sse.subscribe('/api/nodes/stream', render);
        return close;
    }
};
