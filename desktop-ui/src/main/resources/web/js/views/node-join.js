window.NG = window.NG || {};
NG.views = NG.views || {};

NG.views.nodeJoin = {
    mount(container) {
        function decodeLanServerId(value) {
            if (!value || !/^NGX-[A-F0-9]{4}-[A-F0-9]{4}$/i.test(value)) {
                return value;
            }
            const upper = parseInt(value.slice(4, 8), 16);
            const lower = parseInt(value.slice(9, 13), 16);
            const o1 = (upper >> 8) & 0xFF;
            const o2 = upper & 0xFF;
            const o3 = (lower >> 8) & 0xFF;
            const o4 = lower & 0xFF;
            return `${o1}.${o2}.${o3}.${o4}`;
        }

        container.innerHTML = `
            <div class="ng-screen ng-onboarding-bg">
                <div class="ng-screen-content">
                    <div class="ng-brand">
                        <h1 class="ng-brand-title">Join a Server</h1>
                        <p class="ng-brand-subtitle">Connect this machine to a cluster on the same LAN or anywhere else.</p>
                    </div>
                    <div class="ng-card ng-card-hero">
                        <div class="ng-field">
                            <label for="ng-address">Server address or LAN ID</label>
                            <div class="ng-inline-helper-row">
                                <input class="ng-input" id="ng-address" type="text" placeholder="192.168.1.42:50051 or NGX-C0A8-0164" autocomplete="off">
                            </div>
                        </div>
                        <div class="ng-field">
                            <label for="ng-token">Enrolment token (optional — leave blank for a plaintext connection)</label>
                            <input class="ng-input" id="ng-token" type="password" placeholder="Only needed if the server requires mTLS enrolment" autocomplete="off">
                        </div>
                        <div id="ng-join-status"></div>
                        <div class="ng-row">
                            <button class="ng-button ng-button-secondary" id="ng-back">← Back</button>
                            <button class="ng-button ng-button-primary" id="ng-connect">Connect</button>
                        </div>
                    </div>
                </div>
            </div>`;

        const screen = container.querySelector('.ng-screen');
        const detachSpotlight = NG.cursorFx.attachSpotlight(screen);

        const addressInput = container.querySelector('#ng-address');
        const tokenInput = container.querySelector('#ng-token');
        const status = container.querySelector('#ng-join-status');
        const connectButton = container.querySelector('#ng-connect');
        const backButton = container.querySelector('#ng-back');

        backButton.addEventListener('click', () => {
            NG.router.show(NG.views.roleSelection);
        });

        function setBusy(label) {
            connectButton.disabled = true;
            connectButton.innerHTML = `<span class="ng-spinner"></span> ${label}`;
        }

        function setIdle() {
            connectButton.disabled = false;
            connectButton.textContent = 'Connect';
        }

        function showError(category, message) {
            const glyph = NG.format.glyphFor(category);
            status.innerHTML = `<div class="ng-status-message error">${glyph} ${message}</div>`;
        }

        connectButton.addEventListener('click', () => {
            const rawAddress = addressInput.value.trim();
            const decodedAddress = decodeLanServerId(rawAddress);
            const address = decodedAddress.trim();
            const enrollmentToken = tokenInput.value.trim();
            if (!address) {
                showError('UNKNOWN', 'Enter a server address first');
                return;
            }

            if (decodedAddress !== rawAddress) {
                addressInput.value = decodedAddress;
            }

            status.innerHTML = '';
            setBusy('Checking…');

            fetch('/api/role/probe?address=' + encodeURIComponent(address))
                .then((r) => r.json())
                .then((probe) => {
                    if (!probe.reachable) {
                        setIdle();
                        showError(probe.category, probe.message);
                        return;
                    }

                    setBusy('Connecting…');
                    return fetch('/api/role/node/join', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ address, enrollmentToken })
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
                            setIdle();
                            showError(err && err.category, (err && err.message) || 'Could not connect');
                        });
                })
                .catch(() => {
                    setIdle();
                    showError('UNKNOWN', 'Could not reach the local service');
                });
        });

        return () => {
            detachSpotlight();
        };
    }
};
