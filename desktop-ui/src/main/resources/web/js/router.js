// Minimal SPA router: swaps #content's children with a fade, and calls the outgoing view's cleanup
// function (closes SSE subscriptions, detaches cursor-fx listeners) before mounting the next one —
// the JS equivalent of the JavaFX version's per-view shutdown() methods.
window.NG = window.NG || {};

NG.router = (() => {
    const content = document.getElementById('content');
    let currentCleanup = null;

    /**
     * @param view an object with a `mount(container)` method returning an optional cleanup function
     */
    function show(view, props) {
        if (currentCleanup) {
            currentCleanup();
            currentCleanup = null;
        }

        const outgoing = content.firstElementChild;
        if (outgoing) {
            outgoing.classList.add('ng-exit');
            outgoing.addEventListener('animationend', () => outgoing.remove(), { once: true });
        }

        const wrapper = document.createElement('div');
        wrapper.className = 'ng-enter';
        content.appendChild(wrapper);
        currentCleanup = view.mount(wrapper, props) || null;
    }

    return { show };
})();
