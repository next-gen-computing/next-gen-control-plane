// Mirrors view/MainWindow.java + view/Sidebar.java: role-aware navigation around a swappable content
// area. Nodes/Tasks are absent (not present-but-broken) in node mode, matching Sidebar's own comment.
window.NG = window.NG || {};

NG.shell = {
    mount(container, props) {
        const role = (props && props.role) || 'server';
        const serverId = props && props.serverId;
        const collapsed = window.localStorage.getItem('ng-sidebar-collapsed') === '1';

        container.innerHTML = `
            <div class="ng-shell ${collapsed ? 'ng-sidebar-collapsed' : ''}" id="ng-shell-root">
                <div class="ng-sidebar">
                    <div class="ng-sidebar-brand">
                        <div class="ng-sidebar-brand-mark">N</div>
                        <div class="ng-sidebar-brand-text">
                            <div class="ng-sidebar-brand-title">NextGen</div>
                            <div class="ng-sidebar-brand-sub">Control Plane</div>
                        </div>
                    </div>
                    <div class="ng-nav" id="ng-shell-nav"></div>
                    <button class="ng-sidebar-collapse-btn" id="ng-shell-collapse" type="button" title="Collapse sidebar">
                        ${NG.icons.chevronLeft}<span>Collapse</span>
                    </button>
                </div>
                <div class="ng-shell-main">
                    <div class="ng-topbar">
                        <div class="ng-topbar-title" id="ng-shell-title"></div>
                        <div class="ng-topbar-actions">
                            <button class="ng-topbar-icon-btn" id="ng-shell-theme" type="button" title="Toggle theme"></button>
                            <button class="ng-topbar-account" id="ng-shell-account" type="button" title="Account &amp; settings"></button>
                        </div>
                    </div>
                    <div class="ng-shell-content" id="ng-shell-content"></div>
                    <div class="ng-statusbar" id="ng-shell-statusbar"></div>
                </div>
            </div>`;

        const shellRoot = container.querySelector('#ng-shell-root');
        const navEl = container.querySelector('#ng-shell-nav');
        const mainEl = container.querySelector('#ng-shell-content');
        const titleEl = container.querySelector('#ng-shell-title');
        const statusbarEl = container.querySelector('#ng-shell-statusbar');
        const collapseBtn = container.querySelector('#ng-shell-collapse');
        const themeBtn = container.querySelector('#ng-shell-theme');
        const accountBtn = container.querySelector('#ng-shell-account');

        statusbarEl.innerHTML = `
            <span class="ng-statusbar-item">${role === 'server' ? 'Server mode' : 'Node mode'}</span>
            ${serverId ? `<span class="ng-statusbar-item ng-numeric">${serverId}</span>` : ''}
            <span style="flex:1"></span>
            <span class="ng-statusbar-item">v1.0.0</span>`;

        // ── Sections mirror Docker Desktop's own grouping convention: a short umbrella label over a
        // handful of related items, not one long flat list — cheap to scan even as the list grows.
        const sections = [
            ['OVERVIEW', [['dashboard', 'Overview', NG.icons.dashboard, NG.views.dashboard]]]
        ];
        if (role === 'server') {
            sections.push(['CLUSTER', [
                ['nodes', 'Nodes', NG.icons.nodes, NG.views.nodes],
                ['tasks', 'Tasks', NG.icons.tasks, NG.views.tasks],
                ['history', 'History', NG.icons.history, NG.views.history]
            ]]);
            sections.push(['RESOURCES', [
                ['containers', 'Containers', NG.icons.containers, NG.views.containers],
                ['images', 'Images', NG.icons.images, NG.views.images],
                ['volumes', 'Volumes', NG.icons.volumes, NG.views.volumes],
                ['networks', 'Networks', NG.icons.networks, NG.views.networks]
            ]]);
        }
        sections.push(['SYSTEM', [
            ['monitoring', 'Monitoring', NG.icons.monitoring, NG.views.monitoring],
            ['settings', 'Settings', NG.icons.settings, NG.views.settings]
        ]]);
        const items = sections.flatMap(([, entries]) => entries);

        let currentCleanup = null;
        let activeId = null;

        function navigate(id) {
            if (id === activeId) return;
            activeId = id;
            navEl.querySelectorAll('.ng-nav-button').forEach((btn) => {
                btn.classList.toggle('is-active', btn.dataset.id === id);
            });
            if (currentCleanup) currentCleanup();
            mainEl.innerHTML = '';
            const [, label, , view] = items.find(([itemId]) => itemId === id);
            titleEl.textContent = label;
            currentCleanup = view.mount(mainEl) || null;
        }

        sections.forEach(([sectionLabel, entries]) => {
            const heading = document.createElement('div');
            heading.className = 'ng-nav-section-label';
            heading.textContent = sectionLabel;
            navEl.appendChild(heading);
            entries.forEach(([id, label, icon]) => {
                const btn = document.createElement('button');
                btn.className = 'ng-nav-button';
                btn.dataset.id = id;
                btn.title = label;
                btn.innerHTML = `<span class="ng-nav-button-icon">${icon}</span><span class="ng-nav-button-label">${label}</span>`;
                btn.addEventListener('click', () => navigate(id));
                navEl.appendChild(btn);
            });
        });

        navigate('dashboard');

        // ── Sidebar collapse — icon-only, state remembered across launches (a display preference,
        // not cluster identity, so plain localStorage is the right amount of persistence for it). ──
        collapseBtn.addEventListener('click', () => {
            const nowCollapsed = shellRoot.classList.toggle('ng-sidebar-collapsed');
            window.localStorage.setItem('ng-sidebar-collapsed', nowCollapsed ? '1' : '0');
            collapseBtn.innerHTML = (nowCollapsed ? NG.icons.chevronRight : NG.icons.chevronLeft)
                + '<span>Collapse</span>';
        });
        collapseBtn.innerHTML = (collapsed ? NG.icons.chevronRight : NG.icons.chevronLeft) + '<span>Collapse</span>';

        // ── Theme toggle, same endpoint settings.js already drives — kept in the topbar too since
        // that is where a developer tool like this conventionally puts it, not buried a click deep. ──
        let darkMode = true;
        function renderThemeButton() {
            themeBtn.innerHTML = darkMode ? NG.icons.moon : NG.icons.sun;
        }
        fetch('/api/theme').then((r) => r.json()).then((t) => { darkMode = t.dark; renderThemeButton(); });
        themeBtn.addEventListener('click', () => {
            fetch('/api/theme', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ dark: !darkMode })
            });
        });
        const closeTheme = NG.sse.subscribe('/api/theme/stream', (t) => { darkMode = t.dark; renderThemeButton(); });

        // ── Account badge — avatar + name, opens Settings (where the full account card already
        // lives) rather than duplicating switch/logout controls in a second place. ──
        function renderAccountButton(account) {
            if (!account || !account.present) {
                accountBtn.innerHTML = `<span class="ng-avatar">${NG.icons.user}</span>`;
                return;
            }
            const label = account.displayName || account.email || account.githubLogin || 'Account';
            const initial = label.trim().charAt(0).toUpperCase();
            accountBtn.innerHTML = account.avatarUrl
                ? `<img class="ng-avatar" src="${account.avatarUrl}" alt="">`
                : `<span class="ng-avatar">${initial}</span>`;
            accountBtn.title = label;
        }
        fetch('/api/account/current').then((r) => r.json()).then(renderAccountButton).catch(() => {});
        accountBtn.addEventListener('click', () => navigate('settings'));

        return () => {
            if (currentCleanup) currentCleanup();
            closeTheme();
        };
    }
};
