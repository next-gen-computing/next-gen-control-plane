// The account gate shown before role-selection/reconnecting — see app.js's boot sequence. Accounts
// here are device-local only (see Account.java's Javadoc for the full scope statement): "Continue
// with GitHub" is a real OAuth device-flow login, but nothing here syncs to any other machine.
window.NG = window.NG || {};
NG.views = NG.views || {};

const GITHUB_MARK_SVG = '<svg viewBox="0 0 16 16" aria-hidden="true"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/></svg>';

// A real, accurate description of what this application actually accesses — not generic boilerplate.
// Kept in sync by hand with what SystemMetricsReader/PowerMetricsReader actually read; if that ever
// changes, this text needs to change with it, the same discipline the rest of this project applies to
// every other user-facing claim.
const TERMS_TEXT = `
    <p><strong>What this application accesses on this device.</strong> To show live cluster monitoring
    and to decide when a node needs its work moved elsewhere before it fails, this app reads:</p>
    <ul style="margin:8px 0; padding-left:20px; color:var(--ng-text-muted); font-size:var(--ng-font-size-xs);">
        <li>CPU and memory usage percentages</li>
        <li>Battery percentage and charging/AC-power state (laptops only)</li>
        <li>Heartbeat round-trip time to whichever control plane this device connects to</li>
    </ul>
    <p>None of this is sent anywhere except the control plane <em>you</em> configure this device to
    connect to — there is no third-party analytics, telemetry, or cloud service involved. Your account
    (email, password hash, or GitHub profile) is stored in one file on this machine only; see
    Settings → Account for details, and Log out / Switch account to remove it from active use at any
    time.</p>
    <p style="margin-bottom:0;">Signing in with GitHub requests only the <code>read:user</code> scope —
    your username, display name, and avatar. Nothing is ever written to your GitHub account, and no
    repository access is requested.</p>`;

NG.views.login = {
    mount(container) {
        let mode = 'login'; // 'login' | 'signup' | 'reset'
        let detachSpotlight = null;
        let githubPollTimer = null;
        let githubHandle = null;

        function stopGithubPolling() {
            if (githubPollTimer) {
                clearTimeout(githubPollTimer);
                githubPollTimer = null;
            }
            githubHandle = null;
        }

        function proceedAfterAuth() {
            stopGithubPolling();
            NG.app.afterLogin();
        }

        /** Shown once, right after signup or a password reset — never re-shown, never persisted
         * anywhere in a readable form (see Account.java: only its hash is stored). */
        function showRecoveryCodeScreen(recoveryCode) {
            if (detachSpotlight) {
                detachSpotlight();
                detachSpotlight = null;
            }
            container.innerHTML = `
                <div class="ng-screen ng-onboarding-bg">
                    <div class="ng-screen-content">
                        <div class="ng-brand">
                            <h1 class="ng-brand-title">Save your recovery code</h1>
                            <p class="ng-brand-subtitle">This is shown once. If you forget your password, this
                            code is the only way back in — there is no email to reset it with.</p>
                        </div>
                        <div class="ng-card" style="max-width:420px; width:100%; margin:0 auto;">
                            <div class="ng-github-code">${recoveryCode}</div>
                            <div class="ng-status-message info">⚠ Write this down or save it in a password
                                manager now. Closing this screen without saving it means losing access to this
                                account if you ever forget your password.</div>
                            <button class="ng-button ng-button-primary" id="ng-recovery-continue" style="width:100%">
                                I've saved it — continue
                            </button>
                        </div>
                    </div>
                </div>`;
            const screen = container.querySelector('.ng-screen');
            detachSpotlight = NG.cursorFx.attachSpotlight(screen);
            container.querySelector('#ng-recovery-continue').addEventListener('click', proceedAfterAuth);
        }

        function render() {
            if (detachSpotlight) {
                detachSpotlight();
                detachSpotlight = null;
            }

            if (mode === 'reset') {
                renderResetPassword();
                return;
            }

            container.innerHTML = `
                <div class="ng-screen ng-onboarding-bg">
                    <div class="ng-screen-content">
                        <div class="ng-brand">
                            <h1 class="ng-brand-title">Next-Gen Control Plane</h1>
                            <p class="ng-brand-subtitle">Sign in to set up this device.</p>
                        </div>
                        <div class="ng-card" style="max-width:420px; width:100%; margin:0 auto;">
                            <div class="ng-auth-tabs">
                                <button class="ng-auth-tab ${mode === 'login' ? 'is-active' : ''}" data-mode="login" type="button">Log in</button>
                                <button class="ng-auth-tab ${mode === 'signup' ? 'is-active' : ''}" data-mode="signup" type="button">Sign up</button>
                            </div>

                            <button class="ng-button ng-button-github" id="ng-github-btn" type="button">
                                ${GITHUB_MARK_SVG}<span>Continue with GitHub</span>
                            </button>
                            <div id="ng-github-panel"></div>

                            <div class="ng-auth-divider">or</div>

                            <div id="ng-auth-name-field" class="ng-field" style="${mode === 'signup' ? '' : 'display:none'}">
                                <label for="ng-auth-name">Name</label>
                                <input class="ng-input" id="ng-auth-name" type="text" placeholder="Ada Lovelace" autocomplete="name">
                            </div>
                            <div class="ng-field">
                                <label for="ng-auth-email">Email</label>
                                <input class="ng-input" id="ng-auth-email" type="email" placeholder="you@example.com" autocomplete="email">
                            </div>
                            <div class="ng-field">
                                <label for="ng-auth-password">Password</label>
                                <input class="ng-input" id="ng-auth-password" type="password"
                                    placeholder="${mode === 'signup' ? 'At least 8 characters' : '••••••••'}"
                                    autocomplete="${mode === 'signup' ? 'new-password' : 'current-password'}">
                            </div>
                            <div id="ng-auth-confirm-field" class="ng-field" style="${mode === 'signup' ? '' : 'display:none'}">
                                <label for="ng-auth-confirm">Confirm password</label>
                                <input class="ng-input" id="ng-auth-confirm" type="password"
                                    placeholder="Re-enter your password" autocomplete="new-password">
                            </div>

                            <div id="ng-auth-terms-field" style="${mode === 'signup' ? '' : 'display:none'}">
                                <label style="display:flex; align-items:flex-start; gap:8px; font-size:var(--ng-font-size-xs); color:var(--ng-text-muted); cursor:pointer;">
                                    <input type="checkbox" id="ng-auth-terms-checkbox" style="margin-top:2px;">
                                    <span>I agree to the
                                        <button type="button" id="ng-auth-terms-link" class="ng-link-button" style="display:inline; font-size:inherit;">Terms &amp; data access</button>
                                    </span>
                                </label>
                                <div id="ng-auth-terms-body" class="ng-collapsible-body" style="font-size:var(--ng-font-size-xs); color:var(--ng-text-muted);">${TERMS_TEXT}</div>
                            </div>

                            <div id="ng-auth-status"></div>
                            <button class="ng-button ng-button-primary" id="ng-auth-submit" style="width:100%">
                                ${mode === 'signup' ? 'Create account' : 'Log in'}
                            </button>
                            <div style="${mode === 'login' ? '' : 'display:none'}; text-align:center;">
                                <button type="button" id="ng-auth-forgot" class="ng-link-button" style="display:inline;">Forgot password?</button>
                            </div>
                        </div>
                    </div>
                </div>`;

            const screen = container.querySelector('.ng-screen');
            detachSpotlight = NG.cursorFx.attachSpotlight(screen);
            wire();
        }

        function renderResetPassword() {
            container.innerHTML = `
                <div class="ng-screen ng-onboarding-bg">
                    <div class="ng-screen-content">
                        <div class="ng-brand">
                            <h1 class="ng-brand-title">Reset your password</h1>
                            <p class="ng-brand-subtitle">Use the recovery code you saved when you created this account.</p>
                        </div>
                        <div class="ng-card" style="max-width:420px; width:100%; margin:0 auto;">
                            <div class="ng-field">
                                <label for="ng-reset-email">Email</label>
                                <input class="ng-input" id="ng-reset-email" type="email" placeholder="you@example.com" autocomplete="email">
                            </div>
                            <div class="ng-field">
                                <label for="ng-reset-code">Recovery code</label>
                                <input class="ng-input" id="ng-reset-code" type="text" placeholder="XXXX-XXXX-XXXX" autocomplete="off" style="font-family:var(--ng-font-mono); text-transform:uppercase;">
                            </div>
                            <div class="ng-field">
                                <label for="ng-reset-password">New password</label>
                                <input class="ng-input" id="ng-reset-password" type="password" placeholder="At least 8 characters" autocomplete="new-password">
                            </div>
                            <div class="ng-field">
                                <label for="ng-reset-confirm">Confirm new password</label>
                                <input class="ng-input" id="ng-reset-confirm" type="password" placeholder="Re-enter your new password" autocomplete="new-password">
                            </div>
                            <div id="ng-reset-status"></div>
                            <button class="ng-button ng-button-primary" id="ng-reset-submit" style="width:100%">Reset password</button>
                            <div style="text-align:center;">
                                <button type="button" id="ng-reset-back" class="ng-link-button" style="display:inline;">← Back to log in</button>
                            </div>
                        </div>
                    </div>
                </div>`;

            const screen = container.querySelector('.ng-screen');
            detachSpotlight = NG.cursorFx.attachSpotlight(screen);

            const emailInput = container.querySelector('#ng-reset-email');
            const codeInput = container.querySelector('#ng-reset-code');
            const passwordInput = container.querySelector('#ng-reset-password');
            const confirmInput = container.querySelector('#ng-reset-confirm');
            const status = container.querySelector('#ng-reset-status');
            const submitButton = container.querySelector('#ng-reset-submit');

            container.querySelector('#ng-reset-back').addEventListener('click', () => {
                mode = 'login';
                render();
            });

            submitButton.addEventListener('click', () => {
                status.innerHTML = '';
                submitButton.disabled = true;
                submitButton.innerHTML = '<span class="ng-spinner"></span> Resetting…';

                fetch('/api/account/reset-password', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        email: emailInput.value.trim(),
                        recoveryCode: codeInput.value.trim(),
                        newPassword: passwordInput.value,
                        confirmPassword: confirmInput.value
                    })
                })
                    .then(async (response) => {
                        const body = await response.json();
                        if (!response.ok || body.ok === false) {
                            throw body;
                        }
                        showRecoveryCodeScreen(body.recoveryCode);
                    })
                    .catch((err) => {
                        submitButton.disabled = false;
                        submitButton.textContent = 'Reset password';
                        status.innerHTML = `<div class="ng-status-message error">⚠ ${(err && err.message) || 'Could not reset password'}</div>`;
                    });
            });
        }

        function wire() {
            container.querySelectorAll('.ng-auth-tab').forEach((tab) => {
                tab.addEventListener('click', () => {
                    mode = tab.dataset.mode;
                    render();
                });
            });

            const forgotLink = container.querySelector('#ng-auth-forgot');
            if (forgotLink) {
                forgotLink.addEventListener('click', () => {
                    mode = 'reset';
                    render();
                });
            }

            const nameInput = container.querySelector('#ng-auth-name');
            const emailInput = container.querySelector('#ng-auth-email');
            const passwordInput = container.querySelector('#ng-auth-password');
            const confirmInput = container.querySelector('#ng-auth-confirm');
            const termsCheckbox = container.querySelector('#ng-auth-terms-checkbox');
            const termsLink = container.querySelector('#ng-auth-terms-link');
            const termsBody = container.querySelector('#ng-auth-terms-body');
            const status = container.querySelector('#ng-auth-status');
            const submitButton = container.querySelector('#ng-auth-submit');
            const githubButton = container.querySelector('#ng-github-btn');
            const githubPanel = container.querySelector('#ng-github-panel');

            if (termsLink) {
                termsLink.addEventListener('click', () => {
                    termsBody.classList.toggle('is-open');
                });
            }

            function showError(message) {
                status.innerHTML = `<div class="ng-status-message error">⚠ ${message}</div>`;
            }

            function setBusy(label) {
                submitButton.disabled = true;
                submitButton.innerHTML = `<span class="ng-spinner"></span> ${label}`;
            }

            function setIdle() {
                submitButton.disabled = false;
                submitButton.textContent = mode === 'signup' ? 'Create account' : 'Log in';
            }

            function submit() {
                const email = emailInput.value.trim();
                const password = passwordInput.value;
                if (!email || !password) {
                    showError('Enter your email and password');
                    return;
                }
                if (mode === 'signup') {
                    if (password !== confirmInput.value) {
                        showError('Passwords do not match');
                        return;
                    }
                    if (!termsCheckbox.checked) {
                        showError('You need to agree to the terms & data access to create an account');
                        return;
                    }
                }
                status.innerHTML = '';
                setBusy(mode === 'signup' ? 'Creating account…' : 'Logging in…');

                if (mode === 'signup') {
                    fetch('/api/account/signup', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            email, password, confirmPassword: confirmInput.value,
                            displayName: nameInput.value.trim()
                        })
                    })
                        .then(async (response) => {
                            const body = await response.json();
                            if (!response.ok || body.ok === false) {
                                throw body;
                            }
                            showRecoveryCodeScreen(body.recoveryCode);
                        })
                        .catch((err) => {
                            setIdle();
                            showError((err && err.message) || 'Something went wrong');
                        });
                } else {
                    fetch('/api/account/login', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ email, password })
                    })
                        .then(async (response) => {
                            const body = await response.json();
                            if (!response.ok || body.ok === false) {
                                throw body;
                            }
                            proceedAfterAuth();
                        })
                        .catch((err) => {
                            setIdle();
                            showError((err && err.message) || 'Something went wrong');
                        });
                }
            }

            submitButton.addEventListener('click', submit);
            passwordInput.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') submit();
            });

            // Never fabricated as available — the button starts disabled and only enables once the
            // backend confirms GITHUB_OAUTH_CLIENT_ID is actually set, matching server-setup.js's own
            // "never guess a capability" handling of Docker mode.
            githubButton.disabled = true;
            fetch('/api/account/github/available')
                .then((r) => r.json())
                .then((info) => {
                    if (info.available) {
                        githubButton.disabled = false;
                    } else {
                        githubButton.title = 'GitHub login is not configured on this device (set GITHUB_OAUTH_CLIENT_ID)';
                        githubButton.querySelector('span').textContent = 'GitHub login not configured';
                    }
                })
                .catch(() => {
                    githubButton.title = 'Could not check GitHub login availability';
                });

            githubButton.addEventListener('click', () => {
                githubButton.disabled = true;
                fetch('/api/account/github/start', { method: 'POST' })
                    .then(async (response) => {
                        const body = await response.json();
                        if (!response.ok || body.ok === false) {
                            throw body;
                        }
                        return body;
                    })
                    .then((start) => {
                        githubButton.style.display = 'none';
                        githubHandle = start.handle;
                        renderGithubPanel(start);
                        pollGithub(start.intervalSeconds);
                    })
                    .catch((err) => {
                        githubButton.disabled = false;
                        showError((err && err.message) || 'Could not start GitHub login');
                    });
            });

            function renderGithubPanel(start) {
                githubPanel.innerHTML = `
                    <div class="ng-github-code">${start.userCode}</div>
                    <p style="text-align:center;font-size:var(--ng-font-size-xs);color:var(--ng-text-muted);margin:8px 0 0;">
                        Enter this code at
                        <a href="#" id="ng-github-link">${start.verificationUri.replace(/^https?:\/\//, '')}</a>
                        — opening it in your browser now.
                    </p>
                    <div class="ng-status-message info" style="margin-top:8px;">
                        <span class="ng-spinner"></span> Waiting for you to approve in the browser…
                    </div>
                    <button class="ng-button ng-button-secondary" id="ng-github-cancel" style="width:100%;margin-top:8px;">Cancel</button>`;

                openExternal(start.verificationUri);
                githubPanel.querySelector('#ng-github-link').addEventListener('click', (e) => {
                    e.preventDefault();
                    openExternal(start.verificationUri);
                });
                githubPanel.querySelector('#ng-github-cancel').addEventListener('click', () => {
                    stopGithubPolling();
                    githubPanel.innerHTML = '';
                    githubButton.style.display = '';
                    githubButton.disabled = false;
                });
            }

            function pollGithub(intervalSeconds) {
                githubPollTimer = setTimeout(() => {
                    if (!githubHandle) return; // cancelled
                    fetch('/api/account/github/poll?handle=' + encodeURIComponent(githubHandle))
                        .then((r) => r.json())
                        .then((result) => {
                            if (result.status === 'success') {
                                proceedAfterAuth();
                            } else if (result.status === 'failed') {
                                githubPanel.innerHTML = `<div class="ng-status-message error">⚠ ${result.message}</div>`;
                                githubButton.style.display = '';
                                githubButton.disabled = false;
                            } else {
                                pollGithub(result.intervalSeconds || intervalSeconds);
                            }
                        })
                        .catch(() => pollGithub(intervalSeconds));
                }, intervalSeconds * 1000);
            }
        }

        function openExternal(url) {
            if (window.javaOpenExternal) {
                window.javaOpenExternal.open(url);
            } else {
                window.open(url, '_blank');
            }
        }

        render();

        return () => {
            stopGithubPolling();
            if (detachSpotlight) detachSpotlight();
        };
    }
};
