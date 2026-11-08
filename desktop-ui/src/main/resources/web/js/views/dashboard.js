/**
 * dashboard.js — Premium Overview screen.
 * Shows live stat tiles, busiest-node callout, dual charts, and node grid.
 */
window.NG = window.NG || {};
NG.views = NG.views || {};

NG.views.dashboard = {
    mount(container) {
        container.innerHTML = `
            <div class="ng-page">
                <div class="ng-page-header ng-animate-fade-in">
                    <div class="ng-page-header-left">
                        <h1 class="ng-page-title">Cluster Overview</h1>
                        <p class="ng-page-subtitle">Live telemetry from every node heartbeat</p>
                    </div>
                    <div id="ng-dashboard-badge"></div>
                </div>

                <div class="ng-page-scroll">
                    <div class="ng-page-content">
                        <!-- Stat tiles -->
                        <div class="ng-stat-row" id="ng-tiles"></div>

                        <!-- Busiest node alert -->
                        <div class="ng-dashboard-callout" id="ng-busiest" hidden></div>

                        <!-- Charts row -->
                        <div class="ng-chart-row" id="ng-charts" hidden>
                            <div class="ng-chart-card ng-animate-fade-in ng-stagger-2">
                                <div class="ng-chart-card-header">
                                    <div>
                                        <div class="ng-chart-card-title">CPU Usage</div>
                                        <div class="ng-chart-card-caption">Per-node avg over time</div>
                                    </div>
                                </div>
                                <div class="ng-chart-plot" id="ng-chart-cpu"></div>
                                <div class="ng-chart-legend" id="ng-legend-cpu"></div>
                            </div>
                            <div class="ng-chart-card ng-animate-fade-in ng-stagger-3">
                                <div class="ng-chart-card-header">
                                    <div>
                                        <div class="ng-chart-card-title">Memory Usage</div>
                                        <div class="ng-chart-card-caption">Per-node avg over time</div>
                                    </div>
                                </div>
                                <div class="ng-chart-plot" id="ng-chart-mem"></div>
                                <div class="ng-chart-legend" id="ng-legend-mem"></div>
                            </div>
                        </div>

                        <!-- Node grid -->
                        <div class="ng-section-heading ng-animate-fade-in ng-stagger-2">
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                <rect x="2" y="3" width="20" height="14" rx="2"/>
                                <line x1="8" y1="21" x2="16" y2="21"/>
                                <line x1="12" y1="17" x2="12" y2="21"/>
                            </svg>
                            Compute Nodes
                        </div>
                        <div class="ng-node-grid" id="ng-node-grid"></div>
                        <div class="ng-empty-state" id="ng-empty" hidden>
                            <div class="ng-empty-state-icon">🌐</div>
                            <div class="ng-empty-state-title">No nodes connected yet</div>
                            <div class="ng-empty-state-body">
                                Start a node agent pointing at this control plane,
                                or use the <strong>Nodes</strong> screen to join one.
                            </div>
                        </div>
                    </div>
                </div>
            </div>`;

        // ── Stat tiles ────────────────────────────────────────────────────────
        const tilesEl = container.querySelector('#ng-tiles');
        const tiles = {
            total:   NG.components.statTile.create('Nodes'),
            healthy: NG.components.statTile.create('Healthy'),
            offline: NG.components.statTile.create('Not reporting'),
            tasks:   NG.components.statTile.create('Active tasks'),
            cpu:     NG.components.statTile.create('Avg CPU'),
            memory:  NG.components.statTile.create('Avg memory'),
        };
        Object.values(tiles).forEach((t, i) => {
            t.element.classList.add(`ng-stagger-${i + 1}`);
            tilesEl.appendChild(t.element);
        });

        const busiestEl = container.querySelector('#ng-busiest');
        const gridEl    = container.querySelector('#ng-node-grid');
        const emptyEl   = container.querySelector('#ng-empty');
        const chartsEl  = container.querySelector('#ng-charts');
        const badgeEl   = container.querySelector('#ng-dashboard-badge');

        const cards = new Map(); // nodeId → {element, update}

        // ── Render node cards ────────────────────────────────────────────────
        function renderNodes(nodes) {
            const seen = new Set();
            nodes.forEach((node, i) => {
                seen.add(node.id);
                let entry = cards.get(node.id);
                if (!entry) {
                    const card = NG.components.nodeCard.create(node);
                    card.element.style.animationDelay = `${i * 40}ms`;
                    gridEl.appendChild(card.element);
                    entry = card;
                    cards.set(node.id, entry);
                }
                entry.update(node);
            });
            for (const [nodeId, entry] of cards) {
                if (!seen.has(nodeId)) { entry.element.remove(); cards.delete(nodeId); }
            }

            const empty = nodes.length === 0;
            emptyEl.hidden = !empty;
            gridEl.hidden  = empty;
            chartsEl.hidden = nodes.length === 0;

            updateBusiest(nodes);

            // Update 3D canvas
            if (NG.networkCanvas) NG.networkCanvas.updateNodes(nodes);
        }

        function updateBusiest(nodes) {
            const measurable = nodes.filter(n => !n.cpuStale);
            const unmeasured = nodes.length - measurable.length;
            if (nodes.length === 0) { busiestEl.hidden = true; return; }
            if (measurable.length === 0) {
                busiestEl.hidden = false;
                busiestEl.textContent = 'No node is currently reporting a usable CPU reading.';
                return;
            }
            const busiest = measurable.reduce((a, b) => b.cpuUsage > a.cpuUsage ? b : a);
            let text = `Busiest node: ${busiest.name} at ${busiest.cpuUsage.toFixed(1)}% CPU`;
            if (unmeasured > 0) text += `  ·  ${unmeasured} node${unmeasured === 1 ? '' : 's'} not reporting CPU`;
            busiestEl.hidden = false;
            busiestEl.textContent = text;
        }

        // ── Render summary tiles ─────────────────────────────────────────────
        function renderSummary(summary) {
            tiles.total.update(String(summary.totalNodes), null);
            tiles.healthy.update(String(summary.healthyNodes), NG.palette.STATUS_GOOD);
            tiles.offline.update(
                String(summary.offlineNodes),
                summary.offlineNodes === 0 ? NG.palette.STATUS_GOOD : NG.palette.STATUS_CRITICAL
            );
            tiles.tasks.update(
                String(summary.activeTasks != null ? summary.activeTasks : '—'),
                summary.activeTasks > 0 ? NG.palette.STATUS_WARNING : null
            );
            tiles.cpu.update(
                NG.format.percent(summary.avgCpuUsage),
                NG.palette.utilisationStatus(summary.avgCpuUsage, summary.avgCpuUsage !== null)
            );
            tiles.memory.update(
                NG.format.percent(summary.avgMemoryUsage),
                NG.palette.utilisationStatus(summary.avgMemoryUsage, summary.avgMemoryUsage !== null)
            );

            // Health badge in header
            const healthy   = summary.healthyNodes || 0;
            const total     = summary.totalNodes   || 0;
            const pct       = total > 0 ? Math.round(100 * healthy / total) : 0;
            const badgeClass = pct >= 80 ? 'ng-badge-good' : pct >= 50 ? 'ng-badge-warn' : 'ng-badge-critical';
            badgeEl.innerHTML = total > 0
                ? `<span class="ng-badge ${badgeClass}">${pct}% healthy</span>` : '';
        }

        // ── Charts ───────────────────────────────────────────────────────────
        const cpuPlot  = container.querySelector('#ng-chart-cpu');
        const memPlot  = container.querySelector('#ng-chart-mem');
        const cpuLeg   = container.querySelector('#ng-legend-cpu');
        const memLeg   = container.querySelector('#ng-legend-mem');

        let cpuChart   = null;
        let memChart   = null;

        function initCharts(nodes) {
            if (!window.Plotly || nodes.length === 0) return;
            if (cpuChart) return; // already initialised

            // NG.components.plotlyTimeseries will be used if available
            if (NG.components && NG.components.plotlyTimeseries) {
                cpuChart = NG.components.plotlyTimeseries.create(cpuPlot, {
                    title: 'CPU %', yRange: [0, 100], cpuLeg
                });
                memChart = NG.components.plotlyTimeseries.create(memPlot, {
                    title: 'Memory %', yRange: [0, 100], memLeg
                });
            }
        }

        const closeNodes   = NG.sse.subscribe('/api/nodes/stream', (nodes) => {
            renderNodes(nodes);
            initCharts(nodes);
        });
        const closeCluster = NG.sse.subscribe('/api/cluster/stream', renderSummary);

        return () => { closeNodes(); closeCluster(); };
    }
};
