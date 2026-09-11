window.NG = window.NG || {};

(function () {
    function applyTheme(dark) {
        document.documentElement.classList.toggle('theme-dark', dark);
        document.documentElement.classList.toggle('theme-light', !dark);
    }

    fetch('/api/state')
        .then((r) => r.json())
        .then((state) => applyTheme(state.darkMode))
        .catch(() => {}); // theme.css already defaults to dark — a failed fetch just keeps that

    NG.sse.subscribe('/api/theme/stream', (theme) => applyTheme(theme.dark));

    // Mounted once, directly on <body> (not #content), so it survives every router navigation and
    // is reachable from any screen — including before a connection exists, which is exactly when
    // "how do I find and connect to a server" questions come up.
    NG.helpCenter.mount();

    const connectionBanner = NG.connectionBanner.mount();

    // The desktop app's own onboarding-success handlers (server-setup.js, node-join.js) call this
    // directly once /api/role/server/launch or /api/role/node/join returns ok:true — no polling or
    // Java-side Scene manipulation needed, since the WebView never has to change: it's the same page,
    // just a different screen the router shows.
    NG.app = {
        enterDashboard(role, serverId) {
            connectionBanner.show();
            NG.router.show(NG.shell, { role, serverId });
        },

        // Called once an account is confirmed signed in — both at initial boot (already logged in
        // from a previous launch) and right after login.js's signup/login/GitHub flow succeeds. A
        // *previous* launch's onboarding choice may also be remembered (see DesktopProfileStore), in
        // which case this reconnects silently instead of asking "Server or Node?" all over again —
        // account identity and cluster role/profile are deliberately separate systems (see
        // Account.java's Javadoc), checked one after the other, not conflated into one.
        afterLogin() {
            fetch('/api/role/profile')
                .then((r) => r.json())
                .then((profile) => {
                    if (profile.present) {
                        NG.router.show(NG.views.reconnecting, { profile });
                    } else {
                        NG.router.show(NG.views.roleSelection);
                    }
                })
                .catch(() => NG.router.show(NG.views.roleSelection));
        }
    };

    // The WebView loads once, at process start. Before anything cluster-related is even considered,
    // this device needs a signed-in account (see login.js) — device-local, not a cloud account, but
    // still the gate every launch passes through first.
    fetch('/api/account/current')
        .then((r) => r.json())
        .then((account) => {
            if (account.present) {
                NG.app.afterLogin();
            } else {
                NG.router.show(NG.views.login);
            }
        })
        .catch(() => NG.router.show(NG.views.login));
})();
