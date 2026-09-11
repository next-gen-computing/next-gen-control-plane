// Every task/job ever submitted from this device, newest first — backed by DesktopHistoryStore, a
// plain local JSON file, so it survives closing and reopening the app (unlike the Tasks screen's
// live list, which is only ever this session's in-memory state).
window.NG = window.NG || {};
NG.views = NG.views || {};

NG.views.history = {
    mount(container) {
        container.innerHTML = `
            <div class="ng-page">
                <h1 class="ng-page-title">History</h1>
                <p class="ng-page-subtitle">Every task and job submitted from this device, across every cluster and every launch</p>
                <div class="ng-page-scroll">
                    <div class="ng-page-content">
                        <div id="ng-history-list"></div>
                        <div class="ng-empty-state" id="ng-history-empty">
                            <div class="ng-empty-state-body">Nothing submitted from this device yet</div>
                        </div>
                    </div>
                </div>
            </div>`;

        const listEl = container.querySelector('#ng-history-list');
        const emptyEl = container.querySelector('#ng-history-empty');

        const statusColor = (status) => ({
            COMPLETED: NG.palette.STATUS_GOOD,
            PARTIAL_FAILURE: NG.palette.STATUS_WARNING,
            FAILED: NG.palette.STATUS_CRITICAL,
            RUNNING: NG.palette.STATUS_WARNING,
            PENDING: NG.palette.STATUS_WARNING
        }[status] || NG.palette.STATUS_UNKNOWN);

        function formatTime(epochMillis) {
            return epochMillis ? new Date(epochMillis).toLocaleString() : 'in progress';
        }

        function render(entries) {
            emptyEl.hidden = entries.length > 0;
            listEl.innerHTML = entries.map((e) => `
                <div class="ng-card" style="padding:12px 16px;margin-bottom:8px">
                    <div class="ng-row" style="justify-content:space-between">
                        <span>
                            <span class="ng-status-dot" style="width:8px;height:8px;border-radius:50%;background:${statusColor(e.status)};display:inline-block;margin-right:8px"></span>
                            <span class="ng-task-id">${e.id}</span>
                            <span style="margin-left:8px;opacity:0.7">${e.kind === 'job' ? 'Job' : 'Task'} · ${e.typeLabel}</span>
                        </span>
                        <span>${e.status} · ${e.clusterLabel}</span>
                    </div>
                    <div class="ng-task-result" style="margin-top:8px">${e.summary || ''}</div>
                    <div style="margin-top:8px;font-size:var(--ng-font-size-2xs);color:var(--ng-text-muted)">
                        Submitted ${formatTime(e.submittedAtEpochMillis)} — Completed ${formatTime(e.completedAtEpochMillis)}
                    </div>
                </div>`).join('');
        }

        function refresh() {
            fetch('/api/history').then((r) => r.json()).then(render).catch(() => {});
        }

        refresh();
        const interval = setInterval(refresh, 3000);

        return () => {
            clearInterval(interval);
        };
    }
};
