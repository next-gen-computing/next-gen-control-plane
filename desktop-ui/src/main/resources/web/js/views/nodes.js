/**
 * nodes.js — Premium Nodes management screen with glass table and status pills.
 */
window.NG = window.NG || {};
NG.views = NG.views || {};

NG.views.nodes = {
    mount(container) {
        container.innerHTML = `
            <div class="ng-page">
                <div class="ng-page-header ng-animate-fade-in">
                    <div class="ng-page-header-left">
                        <h1 class="ng-page-title">Nodes</h1>
                        <p class="ng-page-subtitle">Every compute node the control plane knows about</p>
                    </div>
                    <div id="ng-nodes-header-actions" style="display:flex;gap:8px;align-items:center;"></div>
                </div>

                <div class="ng-resource-toolbar ng-animate-fade-in ng-stagger-1">
                    <input class="ng-search-input" id="ng-nodes-search"
                           type="text" placeholder="🔍  Filter nodes…" autocomplete="off">
                    <div class="ng-pill-group" id="ng-nodes-filter"></div>
                </div>

                <div class="ng-page-scroll ng-animate-fade-in ng-stagger-2">
                    <div class="ng-table-wrap">
                        <table class="ng-data-table" id="ng-nodes-table">
                            <thead>
                                <tr>
                                    <th>Node</th>
                                    <th>Address</th>
                                    <th>Status</th>
                                    <th>CPU</th>
                                    <th>Memory</th>
                                    <th>Last heartbeat</th>
                                    <th>Failure risk</th>
                                </tr>
                            </thead>
                            <tbody id="ng-nodes-body"></tbody>
                        </table>
                        <div class="ng-empty-state" id="ng-nodes-empty" hidden>
                            <div class="ng-empty-state-icon">🖥️</div>
                            <div class="ng-empty-state-title">No nodes found</div>
                            <div class="ng-empty-state-body">No nodes match your current filter, or none have reported yet.</div>
                        </div>
                        <div class="ng-pagination" id="ng-nodes-pagination"></div>
                    </div>
                </div>
            </div>`;

        const body      = container.querySelector('#ng-nodes-body');
        const emptyEl   = container.querySelector('#ng-nodes-empty');
        const searchEl  = container.querySelector('#ng-nodes-search');
        const filterEl  = container.querySelector('#ng-nodes-filter');
        const tableEl   = container.querySelector('#ng-nodes-table');
        const paginEl   = container.querySelector('#ng-nodes-pagination');

        let allNodes    = [];
        let activeFilter = 'ALL';
        let searchQuery  = '';

        // ── Status filter pills ───────────────────────────────────────────────
        const statuses = ['ALL', 'HEALTHY', 'ONLINE', 'OFFLINE', 'DEGRADED'];
        statuses.forEach(s => {
            const pill = document.createElement('button');
            pill.className   = `ng-pill${s === 'ALL' ? ' is-active' : ''}`;
            pill.textContent = s === 'ALL' ? 'All' : s.charAt(0) + s.slice(1).toLowerCase();
            pill.addEventListener('click', () => {
                activeFilter = s;
                filterEl.querySelectorAll('.ng-pill').forEach(p => p.classList.remove('is-active'));
                pill.classList.add('is-active');
                render();
            });
            filterEl.appendChild(pill);
        });

        searchEl.addEventListener('input', () => { searchQuery = searchEl.value.toLowerCase(); render(); });

        // ── Metric cell helper ────────────────────────────────────────────────
        function usageCell(value, stale) {
            if (stale) return `<td class="ng-numeric" style="color:var(--ng-text-subtle)">—</td>`;
            const color = NG.palette.utilisationStatus(value, true);
            const pct   = NG.format.percent(value);
            const bar   = Math.max(0, Math.min(100, value || 0));
            return `<td>
                <div style="display:flex;align-items:center;gap:8px;">
                    <span class="ng-numeric" style="color:${color};min-width:38px">${pct}</span>
                    <div style="flex:1;height:4px;border-radius:2px;background:var(--ng-track);overflow:hidden;min-width:50px">
                        <div style="height:100%;width:${bar}%;background:${color};border-radius:2px;transition:width .3s ease"></div>
                    </div>
                </div>
            </td>`;
        }

        function statusPill(status) {
            const colors = {
                HEALTHY:  { dot: 'var(--ng-status-good)',     text: 'var(--ng-status-good)' },
                ONLINE:   { dot: 'var(--ng-status-good)',     text: 'var(--ng-status-good)' },
                OFFLINE:  { dot: 'var(--ng-status-critical)', text: 'var(--ng-status-critical)' },
                DEGRADED: { dot: 'var(--ng-status-warning)',  text: 'var(--ng-status-warning)' },
            };
            const c = colors[status] || { dot: 'var(--ng-status-unknown)', text: 'var(--ng-text-subtle)' };
            return `<span class="ng-status-pill">
                <span class="ng-status-dot" style="background:${c.dot};box-shadow:0 0 5px ${c.dot}"></span>
                <span style="color:${c.text}">${status || 'UNKNOWN'}</span>
            </span>`;
        }

        function riskBadge(risk) {
            if (risk == null || risk === 'N/A' || risk === '') return '<td>—</td>';
            const val = parseFloat(risk);
            const cls = isNaN(val) ? 'ng-badge-unknown'
                : val > 0.7 ? 'ng-badge-critical'
                : val > 0.4 ? 'ng-badge-warn'
                : 'ng-badge-good';
            const label = isNaN(val) ? risk : (val * 100).toFixed(0) + '%';
            return `<td><span class="ng-badge ${cls}">${label}</span></td>`;
        }

        // ── Render ────────────────────────────────────────────────────────────
        function render() {
            let nodes = allNodes;
            if (activeFilter !== 'ALL') nodes = nodes.filter(n => n.status === activeFilter);
            if (searchQuery)            nodes = nodes.filter(n =>
                (n.name || '').toLowerCase().includes(searchQuery) ||
                (n.id   || '').toLowerCase().includes(searchQuery) ||
                (n.ip   || '').toLowerCase().includes(searchQuery));

            emptyEl.hidden   = nodes.length > 0;
            tableEl.hidden   = nodes.length === 0;
            paginEl.hidden   = nodes.length === 0;

            body.innerHTML = nodes.map((n, i) => {
                const address = n.ip ? `${n.ip}:${n.port}` : '—';
                return `<tr style="animation:ng-fade-in 220ms ease ${i * 30}ms both">
                    <td>
                        <div style="font-weight:600;color:var(--ng-text)">${n.name || n.id}</div>
                        <div style="font-family:var(--ng-font-mono);font-size:11px;color:var(--ng-text-subtle)">${n.id}</div>
                    </td>
                    <td class="ng-numeric" style="color:var(--ng-text-muted)">${address}</td>
                    <td>${statusPill(n.status)}</td>
                    ${usageCell(n.cpuUsage,    n.cpuStale)}
                    ${usageCell(n.memoryUsage, n.memoryStale)}
                    <td style="color:var(--ng-text-muted);font-size:12px">${n.lastHeartbeat || 'Never'}</td>
                    ${riskBadge(n.failureProbability)}
                </tr>`;
            }).join('');

            paginEl.innerHTML = `
                <span>${nodes.length} node${nodes.length !== 1 ? 's' : ''}</span>
                <span style="flex:1"></span>
                <span style="color:var(--ng-text-subtle)">
                    ${allNodes.length - nodes.length > 0
                        ? `${allNodes.length - nodes.length} hidden by filter` : ''}
                </span>`;
        }

        const close = NG.sse.subscribe('/api/nodes/stream', nodes => {
            allNodes = nodes;
            render();
        });

        return close;
    }
};
