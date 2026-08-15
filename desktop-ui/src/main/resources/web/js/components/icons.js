// One consistent icon set for the whole app shell — same stroke weight, size and style everywhere,
// instead of ad-hoc emoji per screen. Plain inline SVG (line-icon style), coloured via `currentColor`
// so every icon automatically follows its container's text colour (nav item, button, status), and
// therefore the active theme, with zero per-icon colour logic.
window.NG = window.NG || {};

(function () {
    function svg(inner) {
        return `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" `
            + `stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${inner}</svg>`;
    }

    NG.icons = {
        dashboard: svg('<rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect>'
            + '<rect x="14" y="14" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect>'),
        nodes: svg('<rect x="2" y="3" width="20" height="7" rx="1.5"></rect><rect x="2" y="14" width="20" height="7" rx="1.5"></rect>'
            + '<line x1="6" y1="6.5" x2="6.01" y2="6.5"></line><line x1="6" y1="17.5" x2="6.01" y2="17.5"></line>'),
        tasks: svg('<polyline points="9 11 12 14 22 4"></polyline>'
            + '<path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"></path>'),
        containers: svg('<path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"></path>'
            + '<polyline points="3.27 6.96 12 12.01 20.73 6.96"></polyline><line x1="12" y1="22.08" x2="12" y2="12"></line>'),
        images: svg('<polygon points="12 2 2 7 12 12 22 7 12 2"></polygon>'
            + '<polyline points="2 17 12 22 22 17"></polyline><polyline points="2 12 12 17 22 12"></polyline>'),
        volumes: svg('<ellipse cx="12" cy="5" rx="9" ry="3"></ellipse>'
            + '<path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"></path><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"></path>'),
        networks: svg('<circle cx="18" cy="5" r="3"></circle><circle cx="6" cy="12" r="3"></circle><circle cx="18" cy="19" r="3"></circle>'
            + '<line x1="8.59" y1="13.51" x2="15.42" y2="17.49"></line><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"></line>'),
        history: svg('<circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline>'),
        monitoring: svg('<polyline points="22 12 18 12 15 21 9 3 6 12 2 12"></polyline>'),
        settings: svg('<line x1="4" y1="21" x2="4" y2="14"></line><line x1="4" y1="10" x2="4" y2="3"></line>'
            + '<line x1="12" y1="21" x2="12" y2="12"></line><line x1="12" y1="8" x2="12" y2="3"></line>'
            + '<line x1="20" y1="21" x2="20" y2="16"></line><line x1="20" y1="12" x2="20" y2="3"></line>'
            + '<line x1="1" y1="14" x2="7" y2="14"></line><line x1="9" y1="8" x2="15" y2="8"></line><line x1="17" y1="16" x2="23" y2="16"></line>'),
        chevronLeft: svg('<polyline points="15 18 9 12 15 6"></polyline>'),
        chevronRight: svg('<polyline points="9 18 15 12 9 6"></polyline>'),
        user: svg('<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle>'),
        sun: svg('<circle cx="12" cy="12" r="5"></circle><line x1="12" y1="1" x2="12" y2="3"></line>'
            + '<line x1="12" y1="21" x2="12" y2="23"></line><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line>'
            + '<line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line><line x1="1" y1="12" x2="3" y2="12"></line>'
            + '<line x1="21" y1="12" x2="23" y2="12"></line><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line>'
            + '<line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line>'),
        moon: svg('<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path>'),
        plug: svg('<path d="M18 8V4m0 4a2 2 0 0 1 2 2v2a8 8 0 0 1-8 8 8 8 0 0 1-8-8v-2a2 2 0 0 1 2-2m12 0H6"></path><path d="M6 8V4"></path>')
    };
})();
