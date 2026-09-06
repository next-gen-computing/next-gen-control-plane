// Real volumes, across every Docker-capable node — backed by DockerStateCollector's actual
// `docker volume ls` output. List-only in this stage — see the plan's Stage T scope cuts for create/rm.
window.NG = window.NG || {};
NG.views = NG.views || {};

NG.views.volumes = {
    mount(container) {
        container.innerHTML = `
            <div class="ng-page">
                <h1 class="ng-page-title">Volumes</h1>
                <p class="ng-page-subtitle">Every real volume currently present on any Docker-capable node</p>
                <div class="ng-page-scroll">
                    <div class="ng-page-content">
                        <div id="ng-volume-list"></div>
                        <div class="ng-empty-state" id="ng-volume-empty">
                            <div class="ng-empty-state-body">No volumes reported by any node yet</div>
                        </div>
                    </div>
                </div>
            </div>`;

        const listEl = container.querySelector('#ng-volume-list');
        const emptyEl = container.querySelector('#ng-volume-empty');

        function render(list) {
            emptyEl.hidden = list.length > 0;
            listEl.innerHTML = list.map((v) => `
                <div class="ng-card" style="padding:12px 16px;margin-bottom:8px">
                    <div class="ng-row" style="justify-content:space-between;flex-wrap:wrap;gap:8px">
                        <span class="ng-task-id">${v.name}</span>
                        <span>${v.driver} · node ${v.nodeId}</span>
                    </div>
                    <div style="margin-top:6px;font-size:var(--ng-font-size-2xs);color:var(--ng-text-muted)">${v.mountpoint}</div>
                </div>`).join('');
        }

        const closeStream = NG.sse.subscribe('/api/volumes/stream', render);

        return () => {
            closeStream();
        };
    }
};
