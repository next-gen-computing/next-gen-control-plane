// Mirrors view/theme/Palette.java exactly — same values, same rules. No cross-language shared-constants
// mechanism exists in this stack, so this is a mechanical, intentional duplicate. Keep it in sync by
// hand if Palette.java changes.
window.NG = window.NG || {};

const CATEGORICAL_LIGHT = [
    '#2a78d6', '#eb6834', '#1baf7a', '#eda100',
    '#e87ba4', '#008300', '#4a3aa7', '#e34948'
];
const CATEGORICAL_DARK = [
    '#3987e5', '#d95926', '#199e70', '#c98500',
    '#d55181', '#008300', '#9085e9', '#e66767'
];
const OTHER = '#898781';

NG.palette = {
    STATUS_GOOD: '#0ca30c',
    STATUS_WARNING: '#fab219',
    STATUS_SERIOUS: '#ec835a',
    STATUS_CRITICAL: '#d03b3b',
    STATUS_UNKNOWN: '#898781',

    /** @param slot zero-based, assigned per entity and held stable for that entity's lifetime */
    categorical(slot, darkMode) {
        if (slot < 0 || slot >= 8) {
            return OTHER;
        }
        return darkMode ? CATEGORICAL_DARK[slot] : CATEGORICAL_LIGHT[slot];
    },

    /** Returns STATUS_UNKNOWN for an unavailable reading — never green for an unmeasured node. */
    utilisationStatus(percent, available) {
        if (!available) return this.STATUS_UNKNOWN;
        if (percent >= 90) return this.STATUS_CRITICAL;
        if (percent >= 75) return this.STATUS_SERIOUS;
        if (percent >= 60) return this.STATUS_WARNING;
        return this.STATUS_GOOD;
    },

    nodeStatus(status) {
        switch (status) {
            case 'HEALTHY': return this.STATUS_GOOD;
            case 'WARNING': return this.STATUS_WARNING;
            case 'OFFLINE': return this.STATUS_CRITICAL;
            default: return this.STATUS_UNKNOWN;
        }
    }
};
