window.NG = window.NG || {};
NG.views = NG.views || {};

NG.views.roleSelection = {
    mount(container) {
        container.innerHTML = `
            <div class="ng-screen ng-onboarding-bg">
                <div class="ng-screen-content">
                    <div class="ng-brand">
                        <h1 class="ng-brand-title">Next-Gen Control Plane</h1>
                        <p class="ng-brand-subtitle">One cluster, any machine, anywhere.</p>
                    </div>
                    <div class="ng-role-grid">
                        <button class="ng-role-card" data-role="server">
                            <div class="ng-role-card-icon">🖥️</div>
                            <h2 class="ng-role-card-title">Server — Host a Cluster</h2>
                            <p class="ng-role-card-desc">Start the control plane on this machine and let other machines join it.</p>
                        </button>
                        <button class="ng-role-card" data-role="node">
                            <div class="ng-role-card-icon">🔗</div>
                            <h2 class="ng-role-card-title">Node — Join a Server</h2>
                            <p class="ng-role-card-desc">Connect this machine's compute to a cluster already running elsewhere.</p>
                        </button>
                    </div>
                </div>
            </div>`;

        const screen = container.querySelector('.ng-screen');
        const detachSpotlight = NG.cursorFx.attachSpotlight(screen);
        const detachTilts = [...container.querySelectorAll('.ng-role-card')]
            .map((card) => NG.cursorFx.attachMagneticTilt(card));

        container.querySelectorAll('.ng-role-card').forEach((card) => {
            card.addEventListener('click', () => {
                const role = card.dataset.role;
                NG.router.show(role === 'server' ? NG.views.serverSetup : NG.views.nodeJoin);
            });
        });

        return () => {
            detachSpotlight();
            detachTilts.forEach((fn) => fn());
        };
    }
};
