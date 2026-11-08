/**
 * shell.js — Premium app shell: role-aware sidebar navigation + topbar.
 * Integrates with NG.networkCanvas to feed live node data into the 3D viz.
 */
window.NG = window.NG || {};

NG.shell = {
    mount(container, props) {
        const role      = (props && props.role) || 'server';
        const serverId  = props && props.serverId;
        const collapsed = window.localStorage.getItem('ng-sidebar-collapsed') === '1';

        container.innerHTML = `
            <div class="ng-shell ${collapsed ? 'ng-sidebar-collapsed' : ''}" id="ng-shell-root">
                <!-- ── Sidebar ── -->
                <div class="ng-sidebar" role="navigation" aria-label="Main navigation">
                    <div class="ng-sidebar-brand">
                        <div class="ng-sidebar-brand-mark">N</div>
                        <div class="ng-sidebar-brand-text">
                            <div class="ng-sidebar-brand-title">NextGen</div>
                            <div class="ng-sidebar-brand-sub">Control Plane</div>
                        </div>
                    </div>

                    <div class="ng-nav" id="ng-shell-nav" role="list"></div>

                    <button class="ng-sidebar-collapse-btn" id="ng-shell-collapse"
                            type="button" title="Collapse sidebar" aria-label="Collapse sidebar">
                        ${NG.icons.chevronLeft}<span>Collapse</span>
                    </button>
                </div>

                <!-- ── Main area ── -->
                <div class="ng-shell-main">
                    <!-- Top bar -->
                    <div class="ng-topbar">
                        <div class="ng-topbar-title" id="ng-shell-title">
                            <span class="ng-topbar-title-dot"></span>
                            <span id="ng-shell-title-text">Overview</span>
                        </div>
                        <div class="ng-topbar-actions">
                            <button class="ng-topbar-icon-btn" id="ng-shell-theme"
                                    type="button" title="Toggle theme" aria-label="Toggle theme">
                            </button>
                            <button class="ng-topbar-account" id="ng-shell-account"
                                    type="button" title="Account & settings" aria-label="Account">
                            </button>
                        </div>
                    </div>

                    <!-- Content -->
                    <div class="ng-shell-content" id="ng-shell-content"></div>

                    <!-- Status bar -->
                    <div class="ng-statusbar" id="ng-shell-statusbar"></div>
                </div>
            </div>`;

        const shellRoot    = container.querySelector('#ng-shell-root');
        const navEl        = container.querySelector('#ng-shell-nav');
        const mainEl       = container.querySelector('#ng-shell-content');
        const titleTextEl  = container.querySelector('#ng-shell-title-text');
        const statusbarEl  = container.querySelector('#ng-shell-statusbar');
        const collapseBtn  = container.querySelector('#ng-shell-collapse');
        const themeBtn     = container.querySelector('#ng-shell-theme');
        const accountBtn   = container.querySelector('#ng-shell-account');

        // ── Status bar content ───────────────────────────────────────────────
        statusbarEl.innerHTML = `
            <span class="ng-statusbar-item">
                <span class="ng-statusbar-dot"></span>
                ${role === 'server' ? 'Server mode' : 'Node mode'}
            </span>
            ${serverId ? `<span class="ng-statusbar-item ng-numeric">${serverId}</span>` : ''}
            <span style="flex:1"></span>
            <span class="ng-statusbar-item" id="ng-shell-node-count" style="opacity:0.6"></span>
            <span class="ng-statusbar-item" style="opacity:0.5">v1.0.0</span>`;

        const nodeCountEl = statusbarEl.querySelector('#ng-shell-node-count');

        // ── Nav sections (mirror Docker Desktop grouping convention) ─────────
        const sections = [
            ['OVERVIEW', [
                ['dashboard', 'Overview',   NG.icons.dashboard, NG.views.dashboard]
            ]]
        ];

        if (role === 'server') {
            sections.push(['CLUSTER', [
                ['nodes',   'Nodes',   NG.icons.nodes,   NG.views.nodes],
                ['tasks',   'Tasks',   NG.icons.tasks,   NG.views.tasks],
                ['history', 'History', NG.icons.history, NG.views.history],
            ]]);
            sections.push(['RESOURCES', [
                ['containers', 'Containers', NG.icons.containers, NG.views.containers],
                ['images',     'Images',     NG.icons.images,     NG.views.images],
                ['volumes',    'Volumes',    NG.icons.volumes,    NG.views.volumes],
                ['networks',   'Networks',   NG.icons.networks,   NG.views.networks],
            ]]);
        }

        sections.push(['SYSTEM', [
            ['monitoring', 'Monitoring', NG.icons.monitoring, NG.views.monitoring],
            ['settings',   'Settings',  NG.icons.settings,   NG.views.settings],
        ]]);

        const items = sections.flatMap(([, entries]) => entries);

        // ── Router ───────────────────────────────────────────────────────────
        let currentCleanup = null;
        let activeId = null;

        function navigate(id) {
            if (id === activeId) return;
            activeId = id;

            navEl.querySelectorAll('.ng-nav-button').forEach((btn) =>
                btn.classList.toggle('is-active', btn.dataset.id === id));

            if (currentCleanup) { currentCleanup(); currentCleanup = null; }

            // Fade-out old, fade-in new
            mainEl.style.opacity = '0';
            mainEl.style.transform = 'translateY(4px)';
            setTimeout(() => {
                mainEl.innerHTML = '';
                const entry = items.find(([itemId]) => itemId === id);
                if (!entry) return;
                const [, label, , view] = entry;
                titleTextEl.textContent = label;
                currentCleanup = view.mount(mainEl) || null;
                requestAnimationFrame(() => {
                    mainEl.style.transition = 'opacity 220ms ease, transform 220ms ease';
                    mainEl.style.opacity    = '1';
                    mainEl.style.transform  = 'translateY(0)';
                });
            }, 80);
        }

        // Set initial styles for transition
        mainEl.style.opacity   = '1';
        mainEl.style.transform = 'translateY(0)';

        // ── Build nav DOM ────────────────────────────────────────────────────
        sections.forEach(([sectionLabel, entries]) => {
            const heading = document.createElement('div');
            heading.className   = 'ng-nav-section-label';
            heading.textContent = sectionLabel;
            heading.setAttribute('role', 'separator');
            navEl.appendChild(heading);

            entries.forEach(([id, label, icon]) => {
                const btn = document.createElement('button');
                btn.className  = 'ng-nav-button';
                btn.dataset.id = id;
                btn.title      = label;
                btn.setAttribute('role', 'listitem');
                btn.innerHTML  = `
                    <span class="ng-nav-button-icon">${icon}</span>
                    <span class="ng-nav-button-label">${label}</span>`;
                btn.addEventListener('click', () => navigate(id));
                navEl.appendChild(btn);
            });
        });

        navigate('dashboard');

        // ── Sidebar collapse ─────────────────────────────────────────────────
        function setCollapseIcon(isNowCollapsed) {
            collapseBtn.innerHTML = (isNowCollapsed ? NG.icons.chevronRight : NG.icons.chevronLeft)
                + '<span>Collapse</span>';
        }
        setCollapseIcon(collapsed);

        collapseBtn.addEventListener('click', () => {
            const nowCollapsed = shellRoot.classList.toggle('ng-sidebar-collapsed');
            window.localStorage.setItem('ng-sidebar-collapsed', nowCollapsed ? '1' : '0');
            setCollapseIcon(nowCollapsed);
        });

        // ── Theme toggle ─────────────────────────────────────────────────────
        let darkMode = true;
        function renderThemeBtn() {
            themeBtn.innerHTML = darkMode ? NG.icons.moon : NG.icons.sun;
        }
        fetch('/api/theme').then(r => r.json()).then(t => { darkMode = t.dark; renderThemeBtn(); }).catch(() => {});
        themeBtn.addEventListener('click', () => {
            darkMode = !darkMode;
            renderThemeBtn();
            fetch('/api/theme', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ dark: darkMode }),
            }).catch(() => {});
        });
        const closeTheme = NG.sse.subscribe('/api/theme/stream', t => { darkMode = t.dark; renderThemeBtn(); });

        // ── Account badge ────────────────────────────────────────────────────
        function renderAccountBtn(account) {
            if (!account || !account.present) {
                accountBtn.innerHTML = `<span class="ng-avatar">${NG.icons.user}</span>`;
                return;
            }
            const label   = account.displayName || account.email || account.githubLogin || 'Account';
            const initial = label.trim().charAt(0).toUpperCase();
            accountBtn.innerHTML = account.avatarUrl
                ? `<img class="ng-avatar" src="${account.avatarUrl}" alt="${initial}">`
                : `<span class="ng-avatar">${initial}</span>`;
            accountBtn.title = label;
        }
        fetch('/api/account/current').then(r => r.json()).then(renderAccountBtn).catch(() => {});
        accountBtn.addEventListener('click', () => navigate('settings'));

        // ── Feed live node data into 3D canvas ───────────────────────────────
        const closeNodes = NG.sse.subscribe('/api/nodes/stream', (nodes) => {
            if (NG.networkCanvas) NG.networkCanvas.updateNodes(nodes);
            if (nodeCountEl) {
                const healthy = nodes.filter(n => n.status === 'HEALTHY' || n.status === 'ONLINE' || n.status === 'ALIVE').length;
                nodeCountEl.textContent = `${healthy}/${nodes.length} nodes healthy`;
            }
        });

        return () => {
            if (currentCleanup) currentCleanup();
            closeTheme();
            closeNodes();
        };
    }
};
