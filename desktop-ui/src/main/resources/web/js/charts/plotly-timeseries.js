// Mirrors view/components/TimeSeriesChart.java's design rules, ported onto Plotly:
//   - A node that stops reporting produces a GAP, never a flat line. Java splits samples into
//     per-run series to get this on a JavaFX LineChart; Plotly does it natively when a y-value is
//     null (connectgaps defaults to false) — one trace per node is enough here, no run-splitting.
//   - Identity never rests on colour alone: a direct-labelled legend always accompanies the chart.
//   - Colour follows the node's stable categorical slot (from MetricSampleDto.slot), not position.
//   - One axis, pinned 0-100 for a percentage metric.
window.NG = window.NG || {};
NG.charts = NG.charts || {};

const MAX_POINTS_PER_NODE = 300; // mirrors MetricsHistory's ring-buffer capacity

NG.charts.timeSeries = {
    /**
     * @param container element to render into
     * @param title, caption shown above the plot
     * @returns a controller: backfill(samples, nameFn), applyLive(samples, nameFn), setDarkMode(bool)
     */
    create(container, title, caption) {
        container.innerHTML = `
            <div class="ng-chart-card-title">${title}</div>
            <div class="ng-chart-card-caption">${caption}</div>
            <div class="ng-chart-plot"></div>
            <div class="ng-chart-legend"></div>
            <div class="ng-chart-empty" hidden>No data yet</div>`;

        const plotDiv = container.querySelector('.ng-chart-plot');
        const legendEl = container.querySelector('.ng-chart-legend');
        const emptyEl = container.querySelector('.ng-chart-empty');

        const nodeState = new Map(); // nodeId -> {traceIndex, lastT, latest, stale, slot}
        let darkMode = true;
        let plotted = false;

        function colors() {
            return darkMode
                ? { grid: 'rgba(255,255,255,0.06)', axis: 'rgba(255,255,255,0.14)', text: '#94A3B8', bg: 'rgba(0,0,0,0)' }
                : { grid: 'rgba(15,23,42,0.07)', axis: 'rgba(15,23,42,0.18)', text: '#475569', bg: 'rgba(0,0,0,0)' };
        }

        function baseLayout() {
            const c = colors();
            return {
                margin: { l: 36, r: 8, t: 4, b: 28 },
                paper_bgcolor: c.bg,
                plot_bgcolor: c.bg,
                showlegend: false,
                xaxis: { type: 'date', gridcolor: c.grid, linecolor: c.axis, tickfont: { color: c.text, size: 10 } },
                yaxis: { range: [0, 100], dtick: 25, gridcolor: c.grid, linecolor: c.axis, tickfont: { color: c.text, size: 10 } },
                font: { color: c.text }
            };
        }

        function ensurePlot() {
            if (plotted) return;
            Plotly.newPlot(plotDiv, [], baseLayout(), { displayModeBar: false, responsive: true });
            plotted = true;
        }

        function ensureTrace(nodeId, slot) {
            ensurePlot();
            let entry = nodeState.get(nodeId);
            if (entry) return entry;

            const color = NG.palette.categorical(slot, darkMode);
            Plotly.addTraces(plotDiv, {
                x: [], y: [],
                mode: 'lines',
                line: { color, width: 2 },
                connectgaps: false,
                hoverinfo: 'skip'
            });
            entry = { traceIndex: plotDiv.data.length - 1, lastT: -Infinity, latest: null, stale: true, slot };
            nodeState.set(nodeId, entry);
            return entry;
        }

        function appendPoint(nodeId, slot, t, v) {
            const entry = ensureTrace(nodeId, slot);
            if (t <= entry.lastT) return; // already plotted, or out of order — never double-append
            entry.lastT = t;
            entry.latest = v;
            entry.stale = v === null;
            Plotly.extendTraces(plotDiv, { x: [[new Date(t)]], y: [[v]] }, [entry.traceIndex], MAX_POINTS_PER_NODE);
        }

        function refreshLegend(nameFn) {
            const items = [...nodeState.entries()].map(([nodeId, entry]) => {
                const color = NG.palette.categorical(entry.slot, darkMode);
                const name = nameFn ? nameFn(nodeId) : nodeId;
                const value = entry.latest === null ? 'n/a' : NG.format.percent(entry.latest);
                const label = entry.stale ? value + ' (no signal)' : value;
                const valueClass = entry.stale ? 'ng-chart-legend-value-stale' : '';
                return `<span class="ng-chart-legend-item">
                    <span class="ng-chart-legend-swatch" style="background:${color}"></span>
                    <span>${name}</span>
                    <span class="${valueClass}">${label}</span>
                </span>`;
            });
            legendEl.innerHTML = items.join('');

            const hasData = nodeState.size > 0;
            emptyEl.hidden = hasData;
            plotDiv.style.display = hasData ? '' : 'none';
        }

        return {
            backfill(samples, nameFn) {
                const byNode = new Map();
                for (const s of samples) {
                    if (!byNode.has(s.nodeId)) byNode.set(s.nodeId, []);
                    byNode.get(s.nodeId).push(s);
                }
                for (const [nodeId, nodeSamples] of byNode) {
                    nodeSamples.sort((a, b) => a.t - b.t);
                    const slot = nodeSamples[nodeSamples.length - 1].slot;
                    const entry = ensureTrace(nodeId, slot);
                    const xs = nodeSamples.map((s) => new Date(s.t));
                    const ys = nodeSamples.map((s) => s.v);
                    Plotly.extendTraces(plotDiv, { x: [xs], y: [ys] }, [entry.traceIndex], MAX_POINTS_PER_NODE);
                    const last = nodeSamples[nodeSamples.length - 1];
                    entry.lastT = last.t;
                    entry.latest = last.v;
                    entry.stale = last.v === null;
                }
                refreshLegend(nameFn);
            },

            applyLive(samples, nameFn) {
                for (const s of samples) {
                    appendPoint(s.nodeId, s.slot, s.t, s.v);
                }
                refreshLegend(nameFn);
            },

            setDarkMode(dark) {
                darkMode = dark;
                if (plotted) {
                    Plotly.relayout(plotDiv, baseLayout());
                    for (const [, entry] of nodeState) {
                        Plotly.restyle(plotDiv, { 'line.color': NG.palette.categorical(entry.slot, darkMode) }, [entry.traceIndex]);
                    }
                }
            }
        };
    }
};
