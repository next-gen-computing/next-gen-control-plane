// Cursor-tracking interactive effects: a spotlight that follows the pointer, and a magnetic tilt on
// cards near it. Explicitly replaces a floating-particle system — the brief was "cursor moving
// animation", not more decoration that ignores the user.
window.NG = window.NG || {};

NG.cursorFx = {
    /** Attaches a pointer-following spotlight to `container`. Returns a detach function. */
    attachSpotlight(container) {
        if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            return () => {};
        }

        const spotlight = document.createElement('div');
        spotlight.className = 'ng-cursor-spotlight';
        container.appendChild(spotlight);

        let raf = null;
        function onMove(event) {
            const rect = container.getBoundingClientRect();
            const x = ((event.clientX - rect.left) / rect.width) * 100;
            const y = ((event.clientY - rect.top) / rect.height) * 100;
            if (raf) cancelAnimationFrame(raf);
            raf = requestAnimationFrame(() => {
                container.style.setProperty('--ng-pointer-x', x + '%');
                container.style.setProperty('--ng-pointer-y', y + '%');
                spotlight.classList.add('is-active');
            });
        }
        function onLeave() {
            spotlight.classList.remove('is-active');
        }

        container.addEventListener('pointermove', onMove);
        container.addEventListener('pointerleave', onLeave);

        return () => {
            container.removeEventListener('pointermove', onMove);
            container.removeEventListener('pointerleave', onLeave);
            spotlight.remove();
        };
    },

    /** Adds a subtle pointer-position tilt to `card`. Returns a detach function. */
    attachMagneticTilt(card, maxDegrees = 6) {
        if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            return () => {};
        }
        card.classList.add('ng-magnetic-card');

        let raf = null;
        function onMove(event) {
            const rect = card.getBoundingClientRect();
            const px = (event.clientX - rect.left) / rect.width - 0.5;
            const py = (event.clientY - rect.top) / rect.height - 0.5;
            if (raf) cancelAnimationFrame(raf);
            raf = requestAnimationFrame(() => {
                card.style.setProperty('--ng-tilt-x', (px * maxDegrees * 2) + 'deg');
                card.style.setProperty('--ng-tilt-y', (-py * maxDegrees * 2) + 'deg');
            });
        }
        function onLeave() {
            card.style.setProperty('--ng-tilt-x', '0deg');
            card.style.setProperty('--ng-tilt-y', '0deg');
        }

        card.addEventListener('pointermove', onMove);
        card.addEventListener('pointerleave', onLeave);

        return () => {
            card.removeEventListener('pointermove', onMove);
            card.removeEventListener('pointerleave', onLeave);
        };
    }
};
