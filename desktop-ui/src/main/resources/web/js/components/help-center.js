// A persistent Help Center: mounted once by app.js (appended to <body>, not #content), so it's
// reachable from every screen and survives router navigation. Content is written to be honest about
// this project's real limitations (the predictor is a documented stub; there is no live support
// team) rather than presenting a polished fiction — consistent with the app's zero-fake-data rule
// extending to zero-fake-interactions.
window.NG = window.NG || {};

const REPO_URL = 'https://github.com/next-gen-computing/next-gen-control-plane';

const TABS = {
    start: {
        label: 'Getting Started',
        render: () => `
            <h3>What this app does</h3>
            <p>One machine runs as the <strong>Server</strong> — it hosts the control plane that
            tracks every node and schedules work. Every other machine runs as a <strong>Node</strong> —
            it joins that server and contributes its CPU to the cluster.</p>

            <h3>Finding a server to join</h3>
            <p>Ask whoever is running the server for its address. If you started the server on this
            network, its LAN address is shown on the Server Setup screen right after you launch it —
            it's a real IP address the operating system detected, not a placeholder.</p>

            <h3>Connecting as a node</h3>
            <p>On the Node screen, enter the server's address as <code>host</code> or
            <code>host:port</code> (port defaults to 50051 if omitted). If the server requires secure
            enrolment, ask its operator for an enrolment token and paste it into the token field —
            otherwise leave it blank and the app connects in plaintext.</p>

            <h3>After connecting</h3>
            <p>Once connected, this window becomes the dashboard: live node health, CPU/memory charts,
            and — on the server — task submission. Every number you see traces back to a real reading;
            anything the app could not measure is shown as "n/a", never a substituted zero.</p>`
    },
    faq: {
        label: 'FAQ',
        render: () => `
            ${faqItem('I get "Not found" when connecting',
                'Double-check the address and port. This means nothing answered at all — often a typo, '
                + 'or the server isn\'t running yet.')}
            ${faqItem('I get "Network problem"',
                'The address resolved but the connection didn\'t complete in time — check a firewall '
                + 'between the two machines, or a VPN that might be blocking the port.')}
            ${faqItem('I get "Not authorized"',
                'The server rejected the enrolment token — it may be wrong, expired, or already used '
                + 'once (tokens are single-use). Ask the server operator for a fresh one.')}
            ${faqItem('I get "Server busy"',
                'The server is deliberately rate-limiting connection attempts right now. Wait a '
                + 'moment and try again.')}
            ${faqItem('A metric shows "n/a" or a chart has a gap',
                'That node\'s reading was genuinely unavailable at that moment — the app never '
                + 'substitutes a fake zero for a measurement it doesn\'t have.')}
            ${faqItem('Is the "failure risk" / prediction number real?',
                'Honestly: today it comes from a documented Phase-1 stub predictor service that '
                + 'returns a fixed placeholder value rather than a trained model\'s output. The app '
                + 'still shows "N/A" whenever the predictor is unreachable or the underlying reading '
                + 'is stale, rather than inventing a number — but a reachable predictor\'s answer '
                + 'is not yet a real prediction. This is called out here rather than left to be '
                + 'discovered.')}`
    },
    about: {
        label: 'About',
        render: () => `
            <h3>Next-Gen Control Plane</h3>
            <p>A distributed compute cluster you build from real machines on your network or across
            the internet, orchestrated by this desktop app. One control plane, any number of nodes,
            connected over authenticated gRPC.</p>
            <div class="ng-note">Version 1.0.0 &middot; Java 21 / JavaFX / gRPC</div>
            <h3>Source</h3>
            <p><a href="${REPO_URL}" target="_blank" rel="noopener">${REPO_URL}</a></p>`
    },
    feedback: {
        label: 'Feedback',
        render: () => `
            <h3>Send feedback</h3>
            <p>There's no live support team behind this app, so feedback submitted here is saved to a
            real file on this machine (under <code>~/.nextgen/feedback/</code>) — nothing is sent
            anywhere automatically. For a bug someone will actually see, use the GitHub link in the
            Support tab.</p>
            <div class="ng-field">
                <label for="ng-feedback-category">Category</label>
                <select class="ng-help-select" id="ng-feedback-category">
                    <option value="bug">Bug</option>
                    <option value="feature">Feature request</option>
                    <option value="general">General</option>
                </select>
            </div>
            <div class="ng-field" style="margin-top: var(--ng-space-3);">
                <label for="ng-feedback-message">Message</label>
                <textarea class="ng-help-textarea" id="ng-feedback-message" placeholder="What happened, or what would help?"></textarea>
            </div>
            <div class="ng-field" style="margin-top: var(--ng-space-3);">
                <label for="ng-feedback-contact">Contact (optional)</label>
                <input class="ng-input" id="ng-feedback-contact" type="text" placeholder="Only if you want a way to be reached">
            </div>
            <div id="ng-feedback-status" style="margin-top: var(--ng-space-3);"></div>
            <button class="ng-button ng-button-primary" id="ng-feedback-submit" style="margin-top: var(--ng-space-3);">Save feedback</button>`
    },
    support: {
        label: 'Support',
        render: () => `
            <h3>Support</h3>
            <p>This is a small project without a staffed support desk. The most reliable way to get
            help with a real bug is to file it where the maintainers actually look:</p>
            <p><a href="${REPO_URL}/issues" target="_blank" rel="noopener">${REPO_URL}/issues</a></p>
            <h3>Documentation</h3>
            <p>The project README covers setup, architecture, and configuration in more depth than
            fits here: <a href="${REPO_URL}#readme" target="_blank" rel="noopener">${REPO_URL}#readme</a></p>`
    }
};

function faqItem(q, a) {
    return `<div class="ng-faq-item"><div class="ng-faq-q">${q}</div><div class="ng-faq-a">${a}</div></div>`;
}

NG.helpCenter = {
    mount() {
        const fab = document.createElement('button');
        fab.className = 'ng-help-fab';
        fab.setAttribute('aria-label', 'Help');
        fab.textContent = '?';
        document.body.appendChild(fab);

        const overlay = document.createElement('div');
        overlay.className = 'ng-help-overlay';
        overlay.hidden = true;
        overlay.innerHTML = `
            <div class="ng-help-panel">
                <div class="ng-help-header">
                    <h2>Help</h2>
                    <button class="ng-help-close" aria-label="Close">✕</button>
                </div>
                <div class="ng-help-tabs"></div>
                <div class="ng-help-body"></div>
            </div>`;
        document.body.appendChild(overlay);

        const tabsEl = overlay.querySelector('.ng-help-tabs');
        const bodyEl = overlay.querySelector('.ng-help-body');
        let activeTab = 'start';

        function renderTab(key) {
            activeTab = key;
            tabsEl.querySelectorAll('.ng-help-tab').forEach((btn) => {
                btn.classList.toggle('is-active', btn.dataset.tab === key);
            });
            bodyEl.innerHTML = TABS[key].render();

            if (key === 'feedback') {
                wireFeedbackForm(bodyEl);
            }
        }

        Object.keys(TABS).forEach((key) => {
            const btn = document.createElement('button');
            btn.className = 'ng-help-tab';
            btn.dataset.tab = key;
            btn.textContent = TABS[key].label;
            btn.addEventListener('click', () => renderTab(key));
            tabsEl.appendChild(btn);
        });

        function open() {
            overlay.hidden = false;
            renderTab(activeTab);
        }
        function close() {
            overlay.hidden = true;
        }

        fab.addEventListener('click', open);
        overlay.querySelector('.ng-help-close').addEventListener('click', close);
        overlay.addEventListener('click', (event) => {
            if (event.target === overlay) close();
        });
        document.addEventListener('keydown', (event) => {
            if (event.key === 'Escape' && !overlay.hidden) close();
        });
    }
};

function wireFeedbackForm(bodyEl) {
    const submit = bodyEl.querySelector('#ng-feedback-submit');
    const status = bodyEl.querySelector('#ng-feedback-status');

    submit.addEventListener('click', () => {
        const category = bodyEl.querySelector('#ng-feedback-category').value;
        const message = bodyEl.querySelector('#ng-feedback-message').value.trim();
        const contact = bodyEl.querySelector('#ng-feedback-contact').value.trim();

        if (!message) {
            status.innerHTML = '<div class="ng-status-message error">Write something first</div>';
            return;
        }

        submit.disabled = true;
        fetch('/api/feedback', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ category, message, contact })
        })
            .then(async (response) => {
                const body = await response.json();
                if (!response.ok || body.ok === false) throw body;
                status.innerHTML = `<div class="ng-status-message info">Saved to ${body.savedTo}</div>`;
                bodyEl.querySelector('#ng-feedback-message').value = '';
            })
            .catch((err) => {
                status.innerHTML = `<div class="ng-status-message error">${(err && err.message) || 'Could not save feedback'}</div>`;
            })
            .finally(() => {
                submit.disabled = false;
            });
    });
}
