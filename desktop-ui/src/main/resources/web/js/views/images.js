// Real images, across every Docker-capable node — backed by DockerStateCollector's actual
// `docker images` output. List-only in this stage — see the plan's Stage T scope cuts for pull/rm/tag.
window.NG = window.NG || {};
NG.views = NG.views || {};

NG.views.images = {
    mount(container) {
        container.innerHTML = `
            <div class="ng-page">
                <h1 class="ng-page-title">Images</h1>
                <p class="ng-page-subtitle">Every real image currently present on any Docker-capable node</p>
                <div class="ng-page-scroll">
                    <div class="ng-page-content">
                        <div id="ng-image-list"></div>
                        <div class="ng-empty-state" id="ng-image-empty">
                            <div class="ng-empty-state-body">No images reported by any node yet</div>
                        </div>
                    </div>
                </div>
            </div>`;

        const listEl = container.querySelector('#ng-image-list');
        const emptyEl = container.querySelector('#ng-image-empty');

        function formatSize(bytes) {
            if (!bytes || bytes <= 0) return 'n/a';
            const units = ['B', 'KB', 'MB', 'GB', 'TB'];
            let value = bytes, i = 0;
            while (value >= 1024 && i < units.length - 1) { value /= 1024; i++; }
            return value.toFixed(1) + ' ' + units[i];
        }

        function render(list) {
            emptyEl.hidden = list.length > 0;
            listEl.innerHTML = list.map((img) => `
                <div class="ng-card" style="padding:12px 16px;margin-bottom:8px">
                    <div class="ng-row" style="justify-content:space-between;flex-wrap:wrap;gap:8px">
                        <span>
                            <span class="ng-task-id">${img.repository}:${img.tag}</span>
                            <span style="margin-left:8px;opacity:0.7">${img.imageId}</span>
                        </span>
                        <span>${formatSize(img.sizeBytes)} · node ${img.nodeId}</span>
                    </div>
                </div>`).join('');
        }

        const closeStream = NG.sse.subscribe('/api/images/stream', render);

        return () => {
            closeStream();
        };
    }
};
