// Mirrors view/ConnectionBanner.java: a persistent strip showing live connectivity, mounted once so
// every screen reports it identically and none can quietly present stale data as live.
window.NG = window.NG || {};

NG.connectionBanner = {
    mount() {
        const root = document.createElement('div');
        root.className = 'ng-connection-banner';
        root.hidden = true; // hidden until the dashboard shell is entered — see show()/hide()
        root.innerHTML = `
            <span class="ng-connection-dot"></span>
            <span class="ng-connection-state"></span>
            <span class="ng-connection-detail"></span>
            <span style="flex:1"></span>
            <span class="ng-connection-freshness"></span>`;
        document.body.prepend(root);

        const dot = root.querySelector('.ng-connection-dot');
        const stateEl = root.querySelector('.ng-connection-state');
        const detailEl = root.querySelector('.ng-connection-detail');
        const freshnessEl = root.querySelector('.ng-connection-freshness');

        NG.sse.subscribe('/api/connection/stream', (state) => {
            dot.style.background = state.colorHex;
            dot.classList.toggle('ng-pulse', state.state === 'RECONNECTING');
            stateEl.textContent = state.label;
            stateEl.style.color = state.colorHex;
            detailEl.textContent = state.detail;
            freshnessEl.textContent = 'Updated ' + state.lastSuccessDescription;
        });

        return {
            show: () => { root.hidden = false; },
            hide: () => { root.hidden = true; }
        };
    }
};
