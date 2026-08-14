// Mirrors view/MainWindow.java + view/Sidebar.java: role-aware navigation around a swappable content
// area. Nodes/Tasks are absent (not present-but-broken) in node mode, matching Sidebar's own comment.
window.NG = window.NG || {};

NG.shell = {
    mount(container, props) {
        const role = (props && props.role) || 'server';
        const serverId = props && props.serverId;

        container.innerHTML = `
            <div class="ng-shell">
                <div class="ng-sidebar">
                    <div class="ng-sidebar-brand">
                        <div class="ng-sidebar-brand-title">Next-Gen</div>
                        <div class="ng-sidebar-brand-sub">Control Plane</div>
                    </div>
                    <div class="ng-nav" id="ng-shell-nav"></div>
                    <div class="ng-sidebar-footer" id="ng-shell-footer"></div>
                </div>
                <div class="ng-shell-main" id="ng-shell-main"></div>
            </div>`;

        const navEl = container.querySelector('#ng-shell-nav');
        const mainEl = container.querySelector('#ng-shell-main');
        const footerEl = container.querySelector('#ng-shell-footer');

        footerEl.innerHTML = `<div>${role === 'server' ? 'Server mode' : 'Node mode'}</div>`
            + (serverId ? `<div>${serverId}</div>` : '');

        const items = [['dashboard', 'Overview', NG.views.dashboard]];
        if (role === 'server') {
            items.push(['nodes', 'Nodes', NG.views.nodes]);
            items.push(['tasks', 'Tasks', NG.views.tasks]);
            items.push(['containers', 'Containers', NG.views.containers]);
            items.push(['images', 'Images', NG.views.images]);
            items.push(['volumes', 'Volumes', NG.views.volumes]);
            items.push(['networks', 'Networks', NG.views.networks]);
            items.push(['history', 'History', NG.views.history]);
        }
        items.push(['monitoring', 'Monitoring', NG.views.monitoring]);
        items.push(['settings', 'Settings', NG.views.settings]);

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
            const [, , view] = items.find(([itemId]) => itemId === id);
            currentCleanup = view.mount(mainEl) || null;
        }

        items.forEach(([id, label]) => {
            const btn = document.createElement('button');
            btn.className = 'ng-nav-button';
            btn.dataset.id = id;
            btn.textContent = label;
            btn.addEventListener('click', () => navigate(id));
            navEl.appendChild(btn);
        });

        navigate('dashboard');

        return () => {
            if (currentCleanup) currentCleanup();
        };
    }
};
