// Minimal SPA router: swaps #content's children cleanly and calls the outgoing view's cleanup
// function (closes SSE subscriptions, detaches cursor-fx listeners) before mounting the next one.
window.NG = window.NG || {};

NG.router = (() => {
    const content = document.getElementById('content');
    let currentCleanup = null;

    /**
     * @param view an object with a `mount(container)` method returning an optional cleanup function
     */
    function show(view, props) {
        if (currentCleanup) {
            try { currentCleanup(); } catch (e) { console.warn("View cleanup error:", e); }
            currentCleanup = null;
        }

        // Remove all previous content immediately to avoid lingering blocking screens
        while (content.firstChild) {
            content.removeChild(content.firstChild);
        }

        const wrapper = document.createElement('div');
        wrapper.className = 'ng-enter';
        content.appendChild(wrapper);
        try {
            currentCleanup = (view && typeof view.mount === 'function') ? view.mount(wrapper, props) : null;
        } catch (e) {
            console.error("View mount error:", e);
            wrapper.innerHTML = `<div class="ng-status-message error">Failed to render view: ${e.message}</div>`;
        }
    }

    return { show };
})();
