// Mirrors view/SettingsView.java's four sections: Connection, Monitoring, Appearance, Certificates.
window.NG = window.NG || {};
NG.views = NG.views || {};

NG.views.settings = {
    mount(container) {
        container.innerHTML = `
            <div class="ng-page">
                <div class="ng-page-header ng-animate-fade-in">
                    <div class="ng-page-header-left">
                        <h1 class="ng-page-title">Settings</h1>
                        <p class="ng-page-subtitle">Connection, monitoring, appearance and certificate management</p>
                    </div>
                </div>
                <div class="ng-page-scroll">
                    <div class="ng-settings-grid">

                        <!-- Account -->
                        <div class="ng-settings-section ng-animate-fade-in ng-stagger-1">
                            <div class="ng-settings-section-header">👤 Account</div>
                            <div style="padding:16px;display:flex;flex-direction:column;gap:12px">
                                <div id="ng-account-current"></div>
                                <div style="display:flex;gap:8px;justify-content:flex-end">
                                    <button class="ng-button ng-button-secondary" id="ng-account-switch-toggle" type="button">Switch account</button>
                                    <button class="ng-button ng-button-danger"  id="ng-account-logout"        type="button">Log out</button>
                                </div>
                                <div id="ng-account-switch-body" class="ng-collapsible-body"></div>
                            </div>
                        </div>

                        <!-- Connection -->
                        <div class="ng-settings-section ng-animate-fade-in ng-stagger-2">
                            <div class="ng-settings-section-header">🔌 Connection</div>
                            <div style="padding:0 16px 4px">
                                <p style="font-size:12px;color:var(--ng-text-muted);margin:12px 0">
                                    The control plane can live anywhere reachable — a LAN host, a cloud VM, or
                                    a home server with this port forwarded. Nodes always dial out.
                                </p>
                            </div>
                            <div class="ng-setting-row">
                                <div class="ng-setting-label-group">
                                    <span class="ng-setting-label">Control plane host</span>
                                </div>
                                <input class="ng-input" id="ng-set-host" style="max-width:240px;width:100%">
                            </div>
                            <div class="ng-setting-row">
                                <div class="ng-setting-label-group">
                                    <span class="ng-setting-label">Control plane port</span>
                                </div>
                                <input class="ng-input ng-numeric" id="ng-set-cp-port" style="max-width:120px">
                            </div>
                            <div class="ng-setting-row">
                                <div class="ng-setting-label-group">
                                    <span class="ng-setting-label">Predictor port</span>
                                </div>
                                <input class="ng-input ng-numeric" id="ng-set-pred-port" style="max-width:120px">
                            </div>
                            <div class="ng-setting-row">
                                <div class="ng-setting-label-group">
                                    <span class="ng-setting-label">Status</span>
                                </div>
                                <span id="ng-set-status" style="font-size:12px"></span>
                            </div>
                            <div style="padding:12px 16px;display:flex;justify-content:flex-end">
                                <button class="ng-button ng-button-primary" id="ng-set-apply">Apply &amp; Connect</button>
                            </div>
                        </div>

                        <!-- Monitoring -->
                        <div class="ng-settings-section ng-animate-fade-in ng-stagger-2">
                            <div class="ng-settings-section-header">📊 Monitoring</div>
                            <div class="ng-setting-row">
                                <div class="ng-setting-label-group">
                                    <span class="ng-setting-label">Refresh interval</span>
                                    <span class="ng-setting-hint">Heartbeat cadence set on control plane</span>
                                </div>
                                <div style="display:flex;align-items:center;gap:8px">
                                    <input type="range" id="ng-set-refresh" min="1000" max="10000" step="500"
                                           style="accent-color:var(--ng-accent)">
                                    <span id="ng-set-refresh-value" class="ng-numeric"
                                          style="min-width:52px;text-align:right;font-size:12px"></span>
                                </div>
                            </div>
                        </div>

                        <!-- Appearance -->
                        <div class="ng-settings-section ng-animate-fade-in ng-stagger-3">
                            <div class="ng-settings-section-header">🎨 Appearance</div>
                            <div class="ng-setting-row">
                                <div class="ng-setting-label-group">
                                    <span class="ng-setting-label">Theme</span>
                                    <span class="ng-setting-hint">Switch between dark and light mode</span>
                                </div>
                                <button class="ng-button ng-button-secondary" id="ng-set-theme"></button>
                            </div>
                            <div class="ng-setting-row">
                                <div class="ng-setting-label-group">
                                    <span class="ng-setting-label">Network background</span>
                                    <span class="ng-setting-hint">Animated 3D node mesh canvas</span>
                                </div>
                                <label class="ng-toggle">
                                    <input type="checkbox" id="ng-set-canvas" checked>
                                    <span class="ng-toggle-track"></span>
                                </label>
                            </div>
                        </div>

                        <!-- Saved setup -->
                        <div class="ng-settings-section ng-animate-fade-in ng-stagger-3">
                            <div class="ng-settings-section-header">💾 Saved Setup</div>
                            <div style="padding:12px 16px">
                                <p style="font-size:12px;color:var(--ng-text-muted);margin:0 0 12px">
                                    This device remembers its role and server so a launch can reconnect
                                    automatically. Forgetting only affects the next launch here.
                                </p>
                                <div style="display:flex;justify-content:flex-end">
                                    <button class="ng-button ng-button-secondary" id="ng-forget-device">Forget this device</button>
                                </div>
                            </div>
                        </div>

                        <!-- Certificates -->
                        <div class="ng-settings-section ng-animate-fade-in ng-stagger-4">
                            <div class="ng-settings-section-header">🔐 Certificates</div>
                            <div style="padding:12px 16px">
                                <p style="font-size:12px;color:var(--ng-text-muted);margin:0 0 12px">
                                    Mutual TLS between this machine and the control plane. A node proves
                                    itself once with an enrolment token and receives a signed certificate.
                                </p>
                            </div>
                            <div class="ng-setting-row">
                                <span class="ng-setting-label">Status</span>
                                <span id="ng-cert-status" style="font-size:12px"></span>
                            </div>
                            <div class="ng-setting-row">
                                <span class="ng-setting-label">Details</span>
                                <span id="ng-cert-detail" style="max-width:320px;text-align:right;font-size:11px;color:var(--ng-text-muted)"></span>
                            </div>
                            <div style="padding:12px 16px;display:flex;justify-content:flex-end">
                                <button class="ng-button ng-button-primary" id="ng-cert-refresh">Re-check</button>
                            </div>
                        </div>

                    </div>
                </div>
            </div>`;

        // ── Account ──
        const accountCurrentEl = container.querySelector('#ng-account-current');
        const accountSwitchToggle = container.querySelector('#ng-account-switch-toggle');
        const accountSwitchBody = container.querySelector('#ng-account-switch-body');
        const accountLogoutButton = container.querySelector('#ng-account-logout');

        function avatarHtml(account, sizeClass) {
            if (account.avatarUrl) {
                return `<img class="ng-avatar ${sizeClass || ''}" src="${account.avatarUrl}" alt="">`;
            }
            const initial = (account.displayName || account.email || '?').trim().charAt(0).toUpperCase();
            return `<div class="ng-avatar ${sizeClass || ''}">${initial}</div>`;
        }

        function loadCurrentAccount() {
            fetch('/api/account/current').then((r) => r.json()).then((account) => {
                if (!account.present) {
                    accountCurrentEl.innerHTML = `<div class="ng-status-message info">Not signed in</div>`;
                    return;
                }
                accountCurrentEl.innerHTML = `
                    <div class="ng-account-row" style="margin-bottom:12px;">
                        ${avatarHtml(account, 'ng-avatar-lg')}
                        <div class="ng-account-identity">
                            <span class="ng-account-name">${account.displayName || account.email || account.githubLogin}</span>
                            <span class="ng-account-detail">${account.githubLogin ? '@' + account.githubLogin + ' via GitHub' : account.email}</span>
                        </div>
                    </div>`;
            });
        }
        loadCurrentAccount();

        accountLogoutButton.addEventListener('click', () => {
            fetch('/api/account/logout', { method: 'POST' }).then(() => {
                window.location.reload();
            });
        });

        accountSwitchToggle.addEventListener('click', () => {
            const opening = !accountSwitchBody.classList.contains('is-open');
            accountSwitchBody.classList.toggle('is-open', opening);
            if (!opening) {
                accountSwitchBody.innerHTML = '';
                return;
            }
            fetch('/api/account/current').then((r) => r.json()).then((current) => {
                fetch('/api/account/list').then((r) => r.json()).then((list) => {
                    const others = list.accounts.filter((a) => !current.present || a.id !== current.id);
                    if (others.length === 0) {
                        accountSwitchBody.innerHTML = `<div class="ng-setting-hint">No other accounts on this device yet — log out and sign up to add one.</div>`;
                        return;
                    }
                    accountSwitchBody.innerHTML = `
                        <div class="ng-account-switch-list">
                            ${others.map((a) => `
                                <button class="ng-account-switch-item" data-account-id="${a.id}" type="button">
                                    ${avatarHtml(a)}
                                    <div class="ng-account-identity">
                                        <span class="ng-account-name">${a.displayName || a.email || a.githubLogin}</span>
                                        <span class="ng-account-detail">${a.githubLogin ? '@' + a.githubLogin + ' via GitHub' : a.email}</span>
                                    </div>
                                </button>`).join('')}
                        </div>
                        <div id="ng-account-switch-password" style="margin-top:8px;"></div>`;

                    accountSwitchBody.querySelectorAll('.ng-account-switch-item').forEach((item) => {
                        item.addEventListener('click', () => {
                            const account = others.find((a) => a.id === item.dataset.accountId);
                            const passwordSlot = accountSwitchBody.querySelector('#ng-account-switch-password');
                            if (account.githubLogin) {
                                switchAccount(account.id, null, passwordSlot);
                                return;
                            }
                            passwordSlot.innerHTML = `
                                <div class="ng-field">
                                    <label>Password for ${account.email}</label>
                                    <input class="ng-input" type="password" id="ng-account-switch-pw">
                                </div>
                                <div id="ng-account-switch-status"></div>
                                <button class="ng-button ng-button-primary" id="ng-account-switch-confirm" style="width:100%">Switch</button>`;
                            passwordSlot.querySelector('#ng-account-switch-confirm').addEventListener('click', () => {
                                const pw = passwordSlot.querySelector('#ng-account-switch-pw').value;
                                switchAccount(account.id, pw, passwordSlot);
                            });
                        });
                    });
                });
            });
        });

        function switchAccount(accountId, password, statusSlot) {
            fetch('/api/account/switch', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ accountId, password })
            })
                .then(async (response) => {
                    const body = await response.json();
                    if (!response.ok || body.ok === false) throw body;
                    window.location.reload();
                })
                .catch((err) => {
                    if (statusSlot) {
                        statusSlot.querySelector('#ng-account-switch-status')?.remove();
                        statusSlot.insertAdjacentHTML('beforeend',
                            `<div id="ng-account-switch-status" class="ng-status-message error">${(err && err.message) || 'Could not switch accounts'}</div>`);
                    }
                });
        }

        // ── Connection ──
        const hostInput = container.querySelector('#ng-set-host');
        const cpPortInput = container.querySelector('#ng-set-cp-port');
        const predPortInput = container.querySelector('#ng-set-pred-port');
        const statusEl = container.querySelector('#ng-set-status');

        fetch('/api/state').then((r) => r.json()).then((state) => {
            hostInput.value = state.connectionHost;
            cpPortInput.value = state.controlPlanePort;
            predPortInput.value = state.predictorPort;
            refreshSlider.value = state.refreshIntervalMs;
            refreshValue.textContent = state.refreshIntervalMs + ' ms';
        });

        const closeConnection = NG.sse.subscribe('/api/connection/stream', (state) => {
            statusEl.textContent = state.label;
            statusEl.style.color = state.colorHex;
        });

        container.querySelector('#ng-set-apply').addEventListener('click', () => {
            fetch('/api/connection/apply', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    host: hostInput.value.trim(),
                    controlPlanePort: cpPortInput.value.trim(),
                    predictorPort: predPortInput.value.trim()
                })
            });
        });

        // ── Monitoring ──
        const refreshSlider = container.querySelector('#ng-set-refresh');
        const refreshValue = container.querySelector('#ng-set-refresh-value');
        refreshSlider.addEventListener('input', () => {
            refreshValue.textContent = refreshSlider.value + ' ms';
        });
        refreshSlider.addEventListener('change', () => {
            fetch('/api/connection/refresh-interval', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ intervalMs: Number(refreshSlider.value) })
            });
        });

        // ── Appearance ──
        const themeButton = container.querySelector('#ng-set-theme');
        let darkMode = true;
        function renderThemeButton() {
            themeButton.textContent = darkMode ? 'Dark' : 'Light';
        }
        fetch('/api/theme').then((r) => r.json()).then((t) => { darkMode = t.dark; renderThemeButton(); });
        themeButton.addEventListener('click', () => {
            fetch('/api/theme', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ dark: !darkMode })
            });
        });
        const closeTheme = NG.sse.subscribe('/api/theme/stream', (t) => { darkMode = t.dark; renderThemeButton(); });

        // ── Saved setup ──
        const forgetButton = container.querySelector('#ng-forget-device');
        forgetButton.addEventListener('click', () => {
            forgetButton.disabled = true;
            fetch('/api/role/profile/clear', { method: 'POST' })
                .then(() => { forgetButton.textContent = 'Forgotten — next launch will ask again'; })
                .catch(() => { forgetButton.disabled = false; });
        });

        // ── Certificates ──
        const certStatusEl = container.querySelector('#ng-cert-status');
        const certDetailEl = container.querySelector('#ng-cert-detail');
        function loadCertificate() {
            fetch('/api/certificates').then((r) => r.json()).then((cert) => {
                certStatusEl.textContent = cert.state.replace('_', ' ');
                certStatusEl.style.color = cert.colorHex;
                certDetailEl.textContent = cert.detail;
            });
        }
        loadCertificate();
        container.querySelector('#ng-cert-refresh').addEventListener('click', loadCertificate);

        return () => {
            closeConnection();
            closeTheme();
        };
    }
};
