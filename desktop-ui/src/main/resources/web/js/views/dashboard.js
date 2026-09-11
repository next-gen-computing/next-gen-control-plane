// Mirrors view/DashboardView.java: five headline tiles, a busiest-node callout, and a card per node.
window.NG = window.NG || {};
NG.views = NG.views || {};

NG.views.dashboard = {
    mount(container) {
        container.innerHTML = `
            <div class="ng-page">
                <h1 class="ng-page-title">Cluster overview</h1>
                <p class="ng-page-subtitle">Every figure below is a live reading from a node heartbeat</p>
                <div class="ng-page-scroll">
                    <div class="ng-page-content">
                        <div class="ng-stat-row" id="ng-tiles"></div>
                        <div class="ng-dashboard-callout" id="ng-busiest" hidden></div>
                        <h2 class="ng-section-heading">Nodes</h2>
                        <div class="ng-node-grid" id="ng-node-grid"></div>
                        <div class="ng-empty-state" id="ng-empty" hidden>
                            <div class="ng-empty-state-title">No nodes have joined yet</div>
                            <div class="ng-empty-state-body">Start a node agent pointing at this control
                                plane, or use the Nodes screen to connect one. Nothing is shown here
                                until a node actually reports.</div>
                        </div>
                    </div>
                </div>
            </div>`;

        const tilesEl = container.querySelector('#ng-tiles');
        const tiles = {
            total: NG.components.statTile.create('Nodes'),
            healthy: NG.components.statTile.create('Healthy'),
            offline: NG.components.statTile.create('Not reporting'),
            cpu: NG.components.statTile.create('Avg CPU'),
            memory: NG.components.statTile.create('Avg memory')
        };
        Object.values(tiles).forEach((t) => tilesEl.appendChild(t.element));

        const busiestEl = container.querySelector('#ng-busiest');
        const gridEl = container.querySelector('#ng-node-grid');
        const emptyEl = container.querySelector('#ng-empty');

        const cards = new Map(); // nodeId -> {element, controller}

        function renderNodes(nodes) {
            const seen = new Set();
            for (const node of nodes) {
                seen.add(node.id);
                let entry = cards.get(node.id);
                if (!entry) {
                    const card = NG.components.nodeCard.create(node);
                    gridEl.appendChild(card.element);
                    entry = card;
                    cards.set(node.id, entry);
                }
                entry.update(node);
            }
            for (const [nodeId, entry] of cards) {
                if (!seen.has(nodeId)) {
                    entry.element.remove();
                    cards.delete(nodeId);
                }
            }

            const empty = nodes.length === 0;
            emptyEl.hidden = !empty;
            gridEl.hidden = empty;

            updateBusiest(nodes);
        }

        function updateBusiest(nodes) {
            const measurable = nodes.filter((n) => !n.cpuStale);
            const unmeasured = nodes.length - measurable.length;

            if (nodes.length === 0) {
                busiestEl.hidden = true;
                return;
            }
            if (measurable.length === 0) {
                busiestEl.hidden = false;
                busiestEl.textContent = 'No node is currently reporting a usable CPU reading.';
                return;
            }
            const busiest = measurable.reduce((a, b) => (b.cpuUsage > a.cpuUsage ? b : a));
            let text = `Busiest node: ${busiest.name} at ${busiest.cpuUsage.toFixed(1)}% CPU`;
            if (unmeasured > 0) {
                text += `  ·  ${unmeasured} node${unmeasured === 1 ? '' : 's'} not reporting a CPU reading`;
            }
            busiestEl.hidden = false;
            busiestEl.textContent = text;
        }

        function renderSummary(summary) {
            tiles.total.update(String(summary.totalNodes), null);
            tiles.healthy.update(String(summary.healthyNodes), NG.palette.STATUS_GOOD);
            tiles.offline.update(String(summary.offlineNodes),
                summary.offlineNodes === 0 ? NG.palette.STATUS_GOOD : NG.palette.STATUS_CRITICAL);
            tiles.cpu.update(NG.format.percent(summary.avgCpuUsage),
                NG.palette.utilisationStatus(summary.avgCpuUsage, summary.avgCpuUsage !== null));
            tiles.memory.update(NG.format.percent(summary.avgMemoryUsage),
                NG.palette.utilisationStatus(summary.avgMemoryUsage, summary.avgMemoryUsage !== null));
        }

        const closeNodes = NG.sse.subscribe('/api/nodes/stream', renderNodes);
        const closeCluster = NG.sse.subscribe('/api/cluster/stream', renderSummary);

        return () => {
            closeNodes();
            closeCluster();
        };
    }
};
