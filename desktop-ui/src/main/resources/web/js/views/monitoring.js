// Mirrors view/MonitoringView.java: cluster-wide tiles plus per-node CPU/Memory charts. CPU and
// memory are separate charts, not one dual-axis overlay — see plotly-timeseries.js's header comment.
window.NG = window.NG || {};
NG.views = NG.views || {};

NG.views.monitoring = {
    mount(container) {
        container.innerHTML = `
            <div class="ng-page">
                <h1 class="ng-page-title">Monitoring</h1>
                <p class="ng-page-subtitle">Live cluster telemetry, sourced entirely from node heartbeats</p>
                <div class="ng-page-scroll">
                    <div class="ng-page-content">
                        <div class="ng-stat-row" id="ng-tiles"></div>
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
            health: NG.components.statTile.create('Cluster health')
        };
        Object.values(tiles).forEach((t) => tilesEl.appendChild(t.element));

        const cpuChart = NG.charts.timeSeries.create(container.querySelector('#ng-cpu-chart'),
            'CPU utilisation', 'Per node, sampled from the control plane. A break in a line means the node stopped reporting.');
        const memChart = NG.charts.timeSeries.create(container.querySelector('#ng-mem-chart'),
            'Memory utilisation', 'Per node. Values are real OS readings; unmeasurable nodes show a gap, never a substituted zero.');

        let nodeNames = new Map();
        function nameFor(nodeId) {
            return nodeNames.get(nodeId) || nodeId;
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

        function updateNodeNames(nodes) {
            nodeNames = new Map(nodes.map((n) => [n.id, n.name || n.id]));
        }

        const closeCluster = NG.sse.subscribe('/api/cluster/stream', renderSummary);
        const closeNodes = NG.sse.subscribe('/api/nodes/stream', updateNodeNames);

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
            closeMetrics();
            closeTheme();
        };
    }
};
