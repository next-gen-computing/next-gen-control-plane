// Real networks, across every Docker-capable node — backed by DockerStateCollector's actual
// `docker network ls` output. List-only in this stage — see the plan's Stage T scope cuts for create/rm.
window.NG = window.NG || {};
NG.views = NG.views || {};

NG.views.networks = {
    mount(container) {
        container.innerHTML = `
            <div class="ng-page">
                <h1 class="ng-page-title">Networks</h1>
                <p class="ng-page-subtitle">Every real network currently present on any Docker-capable node</p>
                <div class="ng-page-scroll">
                    <div class="ng-page-content">
                        <div id="ng-network-list"></div>
                        <div class="ng-empty-state" id="ng-network-empty">
                            <div class="ng-empty-state-body">No networks reported by any node yet</div>
                        </div>
                    </div>
                </div>
            </div>`;

        const listEl = container.querySelector('#ng-network-list');
        const emptyEl = container.querySelector('#ng-network-empty');

        function render(list) {
            emptyEl.hidden = list.length > 0;
            listEl.innerHTML = list.map((n) => `
                <div class="ng-card" style="padding:12px 16px;margin-bottom:8px">
                    <div class="ng-row" style="justify-content:space-between;flex-wrap:wrap;gap:8px">
                        <span>
                            <span class="ng-task-id">${n.name}</span>
                            <span style="margin-left:8px;opacity:0.7">${n.networkId}</span>
                        </span>
                        <span>${n.driver} · ${n.scope} · node ${n.nodeId}</span>
                    </div>
                </div>`).join('');
        }

        const closeStream = NG.sse.subscribe('/api/networks/stream', render);

        return () => {
            closeStream();
        };
    }
};
