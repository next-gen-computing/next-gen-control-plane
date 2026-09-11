// Stage RR: a Task-Manager-style cluster monitor. Cluster-wide tiles, one expandable card per node
// (live CPU/memory/battery/predictive-risk/RTT plus that node's real running tasks and containers —
// server-side lookups already exist for all of it, see ClusterTasksMonitoringService/
// DockerResourcesMonitoringService/NodeMonitoringService), and the cluster-wide performance charts
// below. Real data only throughout — an unavailable reading renders "n/a", never a substituted number.
window.NG = window.NG || {};
NG.views = NG.views || {};

NG.views.monitoring = {
    mount(container) {
        container.innerHTML = `
            <div class="ng-page">
                <h1 class="ng-page-title">Monitoring</h1>
                <p class="ng-page-subtitle">Live cluster telemetry — every node's real resources, and every real task and container running on it</p>
                <div class="ng-page-scroll">
                    <div class="ng-page-content">
                        <div class="ng-stat-row" id="ng-tiles"></div>

                        <h2 class="ng-section-heading">Nodes</h2>
                        <div id="ng-node-grid"></div>
                        <div class="ng-empty-state" id="ng-node-empty">
                            <div class="ng-empty-state-body">No nodes connected yet</div>
                        </div>

                        <h2 class="ng-section-heading">Cluster Performance</h2>
                        <div class="ng-chart-row">
                            <div class="ng-chart-card" id="ng-cpu-chart"></div>
                            <div class="ng-chart-card" id="ng-mem-chart"></div>
                        </div>
                    </div>
                </div>
            </div>`;

        const tilesEl = container.querySelector('#ng-tiles');
        const tiles = {
            nodes: NG.components.statTile.create('Nodes reporting'),
            cpu: NG.components.statTile.create('Cluster CPU'),
            memory: NG.components.statTile.create('Cluster memory'),
            health: NG.components.statTile.create('Cluster health'),
            tasks: NG.components.statTile.create('Tasks running'),
            containers: NG.components.statTile.create('Containers running')
        };
        Object.values(tiles).forEach((t) => tilesEl.appendChild(t.element));

        const cpuChart = NG.charts.timeSeries.create(container.querySelector('#ng-cpu-chart'),
            'CPU utilisation', 'Per node, sampled from the control plane. A break in a line means the node stopped reporting.');
        const memChart = NG.charts.timeSeries.create(container.querySelector('#ng-mem-chart'),
            'Memory utilisation', 'Per node. Values are real OS readings; unmeasurable nodes show a gap, never a substituted zero.');

        const nodeGridEl = container.querySelector('#ng-node-grid');
        const nodeEmptyEl = container.querySelector('#ng-node-empty');

        // Latest data from each of the three real streams this page composes. Rendering re-derives the
        // node grid from all three every time any one of them ticks, so a node's task/container counts
        // never lag behind a fresh nodes/stream tick or vice versa.
        let latestNodes = [];
        let latestTasks = [];
        let latestContainers = [];
        const expandedNodeIds = new Set(); // preserved across re-renders, keyed by node id

        const ACTIVE_TASK_STATES = new Set(['DISPATCHED', 'RUNNING', 'MIGRATING']);

        function fmtBytes(bytes) {
            if (!bytes || bytes <= 0) return 'n/a';
            const gb = bytes / 1_000_000_000;
            return gb >= 1 ? gb.toFixed(1) + ' GB' : (bytes / 1_000_000).toFixed(0) + ' MB';
        }

        function fmtRtt(seconds) {
            return seconds === null || seconds === undefined ? 'n/a' : Math.round(seconds * 1000) + ' ms';
        }

        function metricBar(value, stale, label) {
            const unavailable = stale || value === null || value === undefined;
            const pct = unavailable ? 0 : Math.min(100, Math.max(0, value));
            const color = unavailable ? NG.palette.STATUS_UNKNOWN : NG.palette.utilisationStatus(value, true);
            return `<div class="ng-metric-row">
                <div class="ng-metric-top"><span>${label}</span><span>${unavailable ? 'n/a' : NG.format.percent(value)}</span></div>
                <div class="ng-metric-bar-track"><div class="ng-metric-bar-fill" style="width:${pct}%;background:${color}"></div></div>
            </div>`;
        }

        function taskRow(t) {
            const color = ({
                COMPLETED: NG.palette.STATUS_GOOD,
                FAILED: NG.palette.STATUS_CRITICAL,
                MIGRATING: NG.palette.STATUS_WARNING,
                RUNNING: NG.palette.STATUS_WARNING,
                DISPATCHED: NG.palette.STATUS_WARNING,
                QUEUED: NG.palette.STATUS_UNKNOWN
            }[t.state] || NG.palette.STATUS_UNKNOWN);
            const kind = (t.kind || '').replace(/_/g, ' ').toLowerCase();
            const attempt = t.attempt > 1 ? ` · attempt ${t.attempt}` : '';
            return `<div class="ng-row" style="justify-content:space-between;font-size:var(--ng-font-size-2xs)">
                <span><span class="ng-status-dot" style="width:7px;height:7px;border-radius:50%;background:${color};display:inline-block;margin-right:6px"></span>${t.taskId} <span style="opacity:0.7">(${kind}${attempt})</span></span>
                <span style="color:${color}">${t.state}</span>
            </div>`;
        }

        function containerRow(c) {
            const running = (c.status || '').toLowerCase() === 'running';
            const cpuText = running ? c.cpuPercent.toFixed(1) + '%' : 'n/a';
            const memText = running && c.memoryLimitBytes > 0
                ? `${fmtBytes(c.memoryUsageBytes)} / ${fmtBytes(c.memoryLimitBytes)}` : 'n/a';
            return `<div class="ng-row" style="justify-content:space-between;font-size:var(--ng-font-size-2xs)">
                <span><span class="ng-status-dot" style="width:7px;height:7px;border-radius:50%;background:${running ? NG.palette.STATUS_GOOD : NG.palette.STATUS_UNKNOWN};display:inline-block;margin-right:6px"></span>${c.name || c.containerId}</span>
                <span>cpu ${cpuText} · mem ${memText}</span>
            </div>`;
        }

        function render() {
            nodeEmptyEl.hidden = latestNodes.length > 0;

            const tasksByNode = new Map();
            latestTasks.forEach((t) => {
                if (!tasksByNode.has(t.assignedNodeId)) tasksByNode.set(t.assignedNodeId, []);
                tasksByNode.get(t.assignedNodeId).push(t);
            });
            const containersByNode = new Map();
            latestContainers.forEach((c) => {
                if (!containersByNode.has(c.nodeId)) containersByNode.set(c.nodeId, []);
                containersByNode.get(c.nodeId).push(c);
            });

            nodeGridEl.innerHTML = latestNodes.map((n) => {
                const nodeTasks = (tasksByNode.get(n.id) || []).filter((t) => ACTIVE_TASK_STATES.has(t.state));
                const nodeContainers = containersByNode.get(n.id) || [];
                const isOpen = expandedNodeIds.has(n.id);
                const statusColor = NG.palette.nodeStatus(n.status);

                const powerText = n.batteryPercent === null
                    ? 'no battery reported'
                    : `${n.batteryPercent.toFixed(0)}% ${n.onAcPower ? '(plugged in)' : '(on battery)'}`;

                const riskBadge = n.atRisk
                    ? `<span style="color:${NG.palette.STATUS_CRITICAL};font-weight:600">⚠ AT RISK</span>${n.riskReasons && n.riskReasons.length ? ` <span style="opacity:0.7;font-size:var(--ng-font-size-2xs)">(${n.riskReasons[0]})</span>` : ''}`
                    : `<span style="opacity:0.7">risk ${(n.riskScore * 100).toFixed(0)}%</span>`;

                return `<div class="ng-card ng-monitor-node-card" style="margin-bottom:10px" data-node-id="${n.id}">
                    <div class="ng-row" style="justify-content:space-between;flex-wrap:wrap;gap:8px">
                        <span>
                            <span class="ng-status-dot" style="width:9px;height:9px;border-radius:50%;background:${statusColor};display:inline-block;margin-right:8px"></span>
                            <strong>${n.name || n.id}</strong>
                            <span style="opacity:0.7;margin-left:6px">${n.ip}:${n.port}</span>
                        </span>
                        ${riskBadge}
                    </div>
                    <div style="display:flex;gap:24px;flex-wrap:wrap;margin-top:8px">
                        <div style="flex:1;min-width:160px">
                            ${metricBar(n.cpuUsage, n.cpuStale, 'CPU')}
                            ${metricBar(n.memoryUsage, n.memoryStale, 'Memory')}
                        </div>
                        <div style="font-size:var(--ng-font-size-2xs);opacity:0.85;min-width:200px">
                            <div>Battery: ${powerText}</div>
                            <div>Heartbeat RTT: ${fmtRtt(n.previousRttSeconds)}</div>
                            <div>${n.cpuCores || 'n/a'} cores · ${fmtBytes(n.totalMemoryBytes)} RAM</div>
                        </div>
                    </div>
                    <div class="ng-row" style="margin-top:8px;gap:6px">
                        <button class="ng-collapsible-toggle ng-monitor-toggle" data-node-id="${n.id}">
                            ${isOpen ? '▾' : '▸'} ${nodeTasks.length} task${nodeTasks.length === 1 ? '' : 's'} · ${nodeContainers.length} container${nodeContainers.length === 1 ? '' : 's'}
                        </button>
                    </div>
                    <div class="ng-collapsible-body ${isOpen ? 'is-open' : ''}" style="gap:6px">
                        ${nodeTasks.length ? nodeTasks.map(taskRow).join('') : '<div style="font-size:var(--ng-font-size-2xs);opacity:0.6">No active tasks on this node</div>'}
                        ${nodeContainers.length ? '<div style="height:1px;background:var(--ng-border);margin:4px 0"></div>' + nodeContainers.map(containerRow).join('') : ''}
                    </div>
                </div>`;
            }).join('');

            nodeGridEl.querySelectorAll('.ng-monitor-toggle').forEach((btn) => {
                btn.addEventListener('click', () => {
                    const id = btn.dataset.nodeId;
                    if (expandedNodeIds.has(id)) expandedNodeIds.delete(id); else expandedNodeIds.add(id);
                    render();
                });
            });
        }

        function renderSummary(summary) {
            tiles.nodes.update(String(summary.totalNodes), null);
            tiles.nodes.setStatus(summary.offlineNodes === 0 ? 'all reporting' : summary.offlineNodes + ' not reporting',
                summary.offlineNodes === 0 ? NG.palette.STATUS_GOOD : NG.palette.STATUS_CRITICAL);
            tiles.cpu.update(NG.format.percent(summary.avgCpuUsage),
                NG.palette.utilisationStatus(summary.avgCpuUsage, summary.avgCpuUsage !== null));
            tiles.memory.update(NG.format.percent(summary.avgMemoryUsage),
                NG.palette.utilisationStatus(summary.avgMemoryUsage, summary.avgMemoryUsage !== null));

            if (summary.healthPercent !== null) {
                tiles.health.update(summary.healthPercent.toFixed(0) + '%',
                    summary.healthPercent >= 100 ? NG.palette.STATUS_GOOD
                        : summary.healthPercent >= 50 ? NG.palette.STATUS_WARNING : NG.palette.STATUS_CRITICAL);
                tiles.health.setStatus(`${summary.healthyNodes} of ${summary.totalNodes} healthy`, NG.palette.STATUS_GOOD);
            } else {
                tiles.health.update('n/a', null);
                tiles.health.setStatus('no nodes to assess', NG.palette.STATUS_UNKNOWN);
            }
        }

        let nodeNames = new Map();
        function nameFor(nodeId) {
            return nodeNames.get(nodeId) || nodeId;
        }

        const closeCluster = NG.sse.subscribe('/api/cluster/stream', renderSummary);
        const closeNodes = NG.sse.subscribe('/api/nodes/stream', (nodes) => {
            nodeNames = new Map(nodes.map((n) => [n.id, n.name || n.id]));
            latestNodes = nodes;
            render();
        });
        const closeClusterTasks = NG.sse.subscribe('/api/cluster-tasks/stream', (tasks) => {
            latestTasks = tasks;
            const active = tasks.filter((t) => ACTIVE_TASK_STATES.has(t.state));
            tiles.tasks.update(String(active.length), null);
            render();
        });
        const closeContainers = NG.sse.subscribe('/api/containers/stream', (containers) => {
            latestContainers = containers;
            const running = containers.filter((c) => (c.status || '').toLowerCase() === 'running');
            tiles.containers.update(String(running.length), null);
            render();
        });

        let backfilled = false;
        fetch('/api/metrics/history').then((r) => r.json()).then((samples) => {
            cpuChart.backfill(samples.filter((s) => s.metric === 'CPU'), nameFor);
            memChart.backfill(samples.filter((s) => s.metric === 'MEMORY'), nameFor);
            backfilled = true;
        });

        const closeMetrics = NG.sse.subscribe('/api/metrics/stream', (samples) => {
            if (!backfilled) return; // avoid a live point landing before backfill establishes trace order
            cpuChart.applyLive(samples.filter((s) => s.metric === 'CPU'), nameFor);
            memChart.applyLive(samples.filter((s) => s.metric === 'MEMORY'), nameFor);
        });

        fetch('/api/state').then((r) => r.json()).then((state) => {
            cpuChart.setDarkMode(state.darkMode);
            memChart.setDarkMode(state.darkMode);
        });
        const closeTheme = NG.sse.subscribe('/api/theme/stream', (theme) => {
            cpuChart.setDarkMode(theme.dark);
            memChart.setDarkMode(theme.dark);
        });

        return () => {
            closeCluster();
            closeNodes();
            closeClusterTasks();
            closeContainers();
            closeMetrics();
            closeTheme();
        };
    }
};
