// Real containers, across every Docker-capable node — backed by DockerStateCollector's actual
// `docker ps -a` output (see DockerResourcesMonitoringService), never simulated. Mirrors tasks.js's
// SSE-subscribe + status-color-mapped table pattern exactly.
window.NG = window.NG || {};
NG.views = NG.views || {};

NG.views.containers = {
    mount(container) {
        container.innerHTML = `
            <div class="ng-page">
                <h1 class="ng-page-title">Containers</h1>
                <p class="ng-page-subtitle">Every real container currently running (or recently exited) on any Docker-capable node</p>
                <div class="ng-page-scroll">
                    <div class="ng-page-content">
                        <div id="ng-container-list"></div>
                        <div class="ng-empty-state" id="ng-container-empty">
                            <div class="ng-empty-state-body">No containers reported by any node yet</div>
                        </div>
                    </div>
                </div>
            </div>`;

        const listEl = container.querySelector('#ng-container-list');
        const emptyEl = container.querySelector('#ng-container-empty');

        const statusColor = (status) => ({
            running: NG.palette.STATUS_GOOD,
            created: NG.palette.STATUS_WARNING,
            paused: NG.palette.STATUS_WARNING,
            restarting: NG.palette.STATUS_WARNING,
            removing: NG.palette.STATUS_WARNING,
            exited: NG.palette.STATUS_CRITICAL,
            dead: NG.palette.STATUS_CRITICAL
        }[(status || '').toLowerCase()] || NG.palette.STATUS_UNKNOWN);

        function sendAction(nodeId, containerId, action, button) {
            button.disabled = true;
            fetch('/api/containers/action', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ nodeId, containerId, action })
            })
                .then(async (response) => {
                    const body = await response.json();
                    if (!response.ok || body.ok === false) {
                        throw body;
                    }
                })
                .catch((err) => {
                    // eslint-disable-next-line no-alert
                    alert((err && err.message) || `Could not ${action.toLowerCase()} container`);
                })
                .finally(() => {
                    button.disabled = false;
                });
        }

        function actionButtons(c) {
            const running = (c.status || '').toLowerCase() === 'running';
            const buttons = [];
            if (running) {
                buttons.push(['Stop', 'STOP', 'ng-button-secondary']);
                buttons.push(['Restart', 'RESTART', 'ng-button-secondary']);
            } else {
                buttons.push(['Start', 'START', 'ng-button-primary']);
            }
            buttons.push(['Remove', 'REMOVE', 'ng-button-secondary']);
            return buttons.map(([label, action, cls]) =>
                `<button class="ng-button ${cls} ng-container-action" data-node="${c.nodeId}" data-id="${c.containerId}" data-action="${action}" style="padding:4px 10px;font-size:var(--ng-font-size-2xs)">${label}</button>`
            ).join(' ');
        }

        function render(list) {
            emptyEl.hidden = list.length > 0;
            listEl.innerHTML = list.map((c) => `
                <div class="ng-card" style="padding:12px 16px;margin-bottom:8px">
                    <div class="ng-row" style="justify-content:space-between;flex-wrap:wrap;gap:8px">
                        <span>
                            <span class="ng-status-dot" style="width:8px;height:8px;border-radius:50%;background:${statusColor(c.status)};display:inline-block;margin-right:8px"></span>
                            <span class="ng-task-id">${c.name || c.containerId}</span>
                            <span style="margin-left:8px;opacity:0.7">${c.image}</span>
                        </span>
                        <span>${c.stateText || c.status} · node ${c.nodeId}</span>
                    </div>
                    <div style="margin-top:8px;font-size:var(--ng-font-size-2xs);color:var(--ng-text-muted)">
                        ${c.command ? 'cmd: ' + c.command : ''}${c.ports && c.ports.length ? (c.command ? ' · ' : '') + 'ports: ' + c.ports.join(', ') : ''}
                    </div>
                    <div class="ng-row" style="margin-top:8px;gap:6px">${actionButtons(c)}</div>
                </div>`).join('');

            listEl.querySelectorAll('.ng-container-action').forEach((button) => {
                button.addEventListener('click', () => {
                    sendAction(button.dataset.node, button.dataset.id, button.dataset.action, button);
                });
            });
        }

        const closeStream = NG.sse.subscribe('/api/containers/stream', render);

        return () => {
            closeStream();
        };
    }
};
