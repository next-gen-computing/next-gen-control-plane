window.NG = window.NG || {};
NG.views = NG.views || {};

NG.views.serverSetup = {
    mount(container) {
        container.innerHTML = `
            <div class="ng-screen ng-onboarding-bg">
                <div class="ng-screen-content">
                    <div class="ng-brand">
                        <h1 class="ng-brand-title">Server Setup</h1>
                        <p class="ng-brand-subtitle">This machine will host the control plane.</p>
                    </div>
                    <div class="ng-card">
                        <div id="ng-server-meta">
                            <div class="ng-meta-row"><span>LAN address</span><span class="ng-meta-value">…</span></div>
                        </div>
                        <div class="ng-meta-row">
                            <span>Launch mode</span>
                            <span class="ng-row" style="gap:8px;">
                                <button class="ng-button ng-button-primary" id="ng-mode-native" type="button">Native</button>
                                <button class="ng-button ng-button-secondary" id="ng-mode-docker" type="button">Docker</button>
                            </span>
                        </div>
                        <div id="ng-mode-hint" class="ng-status-message info"></div>
                        <div id="ng-server-status"></div>
                        <div class="ng-row">
                            <button class="ng-button ng-button-secondary" id="ng-back">← Back</button>
                            <button class="ng-button ng-button-primary" id="ng-launch">Launch Server</button>
                        </div>
                    </div>
                </div>
            </div>`;

        const screen = container.querySelector('.ng-screen');
        const detachSpotlight = NG.cursorFx.attachSpotlight(screen);

        const meta = container.querySelector('#ng-server-meta');
        const status = container.querySelector('#ng-server-status');
        const launchButton = container.querySelector('#ng-launch');
        const nativeButton = container.querySelector('#ng-mode-native');
        const dockerButton = container.querySelector('#ng-mode-docker');
        const modeHint = container.querySelector('#ng-mode-hint');

        let serverId = null;
        let mode = 'native';
        let dockerAvailable = false;

        function selectMode(next) {
            if (next === 'docker' && !dockerAvailable) {
                return;
            }
            mode = next;
            nativeButton.className = 'ng-button ' + (mode === 'native' ? 'ng-button-primary' : 'ng-button-secondary');
            dockerButton.className = 'ng-button ' + (mode === 'docker' ? 'ng-button-primary' : 'ng-button-secondary');
        }

        nativeButton.addEventListener('click', () => selectMode('native'));
        dockerButton.addEventListener('click', () => selectMode('docker'));

        fetch('/api/role/server-info')
            .then((r) => r.json())
            .then((info) => {
                serverId = info.serverId;
                meta.innerHTML = `
                    <div class="ng-meta-row"><span>LAN address</span><span class="ng-meta-value">${info.lanIp}:${info.grpcPort}</span></div>
                    <div class="ng-meta-row"><span>Encryption</span><span class="ng-meta-value">${info.tlsEnabled ? 'mTLS enabled' : 'Disabled (plaintext)'}</span></div>
                    <div class="ng-meta-row"><span>Server ID</span><span class="ng-meta-value">${info.serverId}</span></div>`;
            })
            .catch(() => {
                meta.innerHTML = `<div class="ng-status-message error">Could not read server info</div>`;
            });

        // Docker mode is only ever offered when a real daemon is actually reachable — never a guessed
        // capability. See DockerCapabilityDetector's own "never fabricate a capability" discipline.
        fetch('/api/role/docker-info')
            .then((r) => r.json())
            .then((info) => {
                dockerAvailable = !!info.available;
                dockerButton.disabled = !dockerAvailable;
                modeHint.textContent = dockerAvailable
                    ? `Docker detected (${info.dockerVersion}) — either mode works.`
                    : 'Docker is not running on this machine — Native mode only.';
            })
            .catch(() => {
                dockerAvailable = false;
                dockerButton.disabled = true;
                modeHint.textContent = '';
            });

        container.querySelector('#ng-back').addEventListener('click', () => {
            NG.router.show(NG.views.roleSelection);
        });

        launchButton.addEventListener('click', () => {
            launchButton.disabled = true;
            const startingLabel = mode === 'docker' ? 'Building &amp; starting Docker containers…' : 'Starting…';
            launchButton.innerHTML = `<span class="ng-spinner"></span> ${startingLabel}`;
            status.innerHTML = '';

            fetch('/api/role/server/launch', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ mode })
            })
                .then(async (response) => {
                    const body = await response.json();
                    if (!response.ok || body.ok === false) {
                        throw body;
                    }
                    status.innerHTML = `<div class="ng-status-message info">Connected — loading dashboard…</div>`;
                    NG.app.enterDashboard(body.role, body.serverId);
                })
                .catch((err) => {
                    launchButton.disabled = false;
                    launchButton.textContent = 'Launch Server';
                    const glyph = NG.format.glyphFor(err && err.category);
                    const message = (err && err.message) || 'Could not start the server';
                    status.innerHTML = `<div class="ng-status-message error">${glyph} ${message}</div>`;
                });
        });

        return () => {
            detachSpotlight();
        };
    }
};
