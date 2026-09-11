window.NG = window.NG || {};
NG.views = NG.views || {};

// Shown instead of role-selection when a previous launch's setup was remembered (see
// DesktopProfileStore) — replays that setup automatically so the operator doesn't have to click
// through "Server or Node?" and re-type a server address on every single launch.
NG.views.reconnecting = {
    mount(container, props) {
        const profile = (props && props.profile) || {};
        const subtitle = profile.role === 'server'
            ? `Starting your control plane (${profile.launchMode === 'docker' ? 'Docker' : 'Native'} mode)…`
            : `Reconnecting to ${profile.serverAddress || 'your saved server'}…`;

        container.innerHTML = `
            <div class="ng-screen ng-onboarding-bg">
                <div class="ng-screen-content">
                    <div class="ng-brand">
                        <h1 class="ng-brand-title">Welcome back</h1>
                        <p class="ng-brand-subtitle" id="ng-reconnect-subtitle">${subtitle}</p>
                    </div>
                    <div class="ng-card">
                        <div id="ng-reconnect-status">
                            <div class="ng-status-message info"><span class="ng-spinner"></span> Connecting…</div>
                        </div>
                        <div class="ng-row" id="ng-reconnect-actions" style="display:none">
                            <button class="ng-button ng-button-secondary" id="ng-reconnect-forget">Forget this device</button>
                            <button class="ng-button ng-button-secondary" id="ng-reconnect-manual">Set up manually</button>
                            <button class="ng-button ng-button-primary" id="ng-reconnect-retry">Retry</button>
                        </div>
                    </div>
                </div>
            </div>`;

        const screen = container.querySelector('.ng-screen');
        const detachSpotlight = NG.cursorFx.attachSpotlight(screen);

        const statusEl = container.querySelector('#ng-reconnect-status');
        const actionsEl = container.querySelector('#ng-reconnect-actions');

        function attempt() {
            actionsEl.style.display = 'none';
            statusEl.innerHTML = `<div class="ng-status-message info"><span class="ng-spinner"></span> Connecting…</div>`;

            fetch('/api/role/auto-connect', { method: 'POST' })
                .then(async (response) => {
                    const body = await response.json();
                    if (!response.ok || body.ok === false) {
                        throw body;
                    }
                    statusEl.innerHTML = `<div class="ng-status-message info">Connected — loading dashboard…</div>`;
                    NG.app.enterDashboard(body.role, body.serverId);
                })
                .catch((err) => {
                    const glyph = NG.format.glyphFor(err && err.category);
                    const message = (err && err.message) || 'Could not reconnect automatically';
                    statusEl.innerHTML = `<div class="ng-status-message error">${glyph} ${message}</div>`;
                    actionsEl.style.display = 'flex';
                });
        }

        container.querySelector('#ng-reconnect-retry').addEventListener('click', attempt);
        container.querySelector('#ng-reconnect-manual').addEventListener('click', () => {
            NG.router.show(NG.views.roleSelection);
        });
        container.querySelector('#ng-reconnect-forget').addEventListener('click', () => {
            fetch('/api/role/profile/clear', { method: 'POST' })
                .finally(() => NG.router.show(NG.views.roleSelection));
        });

        attempt();

        return () => {
            detachSpotlight();
        };
    }
};
