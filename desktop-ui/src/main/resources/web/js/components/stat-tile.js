// Mirrors view/components/StatTile.java: one headline figure, no numeric overload — the caller
// decides "n/a" vs a real value, this component never invents one.
window.NG = window.NG || {};

NG.components = NG.components || {};

NG.components.statTile = {
    /** @returns {element, update(display, statusHex), setStatus(text, colorHex)} */
    create(caption) {
        const el = document.createElement('div');
        el.className = 'ng-stat-tile';
        el.innerHTML = `
            <div class="ng-stat-tile-caption">${caption}</div>
            <div class="ng-stat-tile-value">n/a</div>
            <div class="ng-stat-tile-status" hidden>
                <span class="ng-stat-tile-status-dot"></span>
                <span class="ng-stat-tile-status-text"></span>
            </div>`;

        const valueEl = el.querySelector('.ng-stat-tile-value');
        const statusRow = el.querySelector('.ng-stat-tile-status');
        const statusDot = el.querySelector('.ng-stat-tile-status-dot');
        const statusText = el.querySelector('.ng-stat-tile-status-text');
        let lastValue = null;

        return {
            element: el,
            update(display, statusHex) {
                const changed = lastValue !== null && lastValue !== display;
                lastValue = display;

                valueEl.textContent = display;
                valueEl.classList.toggle('is-unavailable', display === 'n/a');
                el.style.borderColor = statusHex ? statusHex + '40' : '';

                if (changed) {
                    valueEl.style.opacity = '0.45';
                    requestAnimationFrame(() => { valueEl.style.opacity = '1'; });
                }
            },
            setStatus(text, colorHex) {
                const show = !!text;
                statusRow.hidden = !show;
                if (show) {
                    statusText.textContent = text;
                    statusDot.style.background = colorHex;
                }
            }
        };
    }
};
