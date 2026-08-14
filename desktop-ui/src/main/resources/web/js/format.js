// Shared formatting helpers. Mirrors the "never substitute a fake number" rule already enforced
// server-side (NodeDto/ClusterSummaryDto send null, not 0, for anything unmeasured) — this module's
// job is just to render that null as "n/a", never as "0" or a blank.
window.NG = window.NG || {};

NG.format = {
    percent(value) {
        return value === null || value === undefined ? 'n/a' : value.toFixed(1) + '%';
    },

    glyphFor(category) {
        const glyphs = {
            NOT_FOUND: '\u{1F50D}',
            NETWORK: '\u{1F4E1}',
            OVERLOAD: '⏳',
            AUTHENTICATION: '\u{1F512}',
            SERVER_ERROR: '⚠',
            UNKNOWN: '❓'
        };
        return glyphs[category] || glyphs.UNKNOWN;
    }
};
