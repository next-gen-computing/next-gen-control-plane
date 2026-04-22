/**
 * Next-Gen Control Plane — Dashboard Application
 *
 * Live real-time monitoring dashboard.
 * All values are REAL OS readings — never random or fake data.
 * Polls the /api/nodes endpoint every 2 seconds for live updates.
 */

// ═══════════════════════════════════════════════════
// Configuration
// ═══════════════════════════════════════════════════
const API_URL = '/api/nodes';
const POLL_INTERVAL = 2000;    // 2 seconds
const MAX_DATA_POINTS = 30;    // 60 seconds at 2s intervals

// ═══════════════════════════════════════════════════
// State
// ═══════════════════════════════════════════════════
const nodeHistory = {};        // nodeId → { cpu: [], mem: [], labels: [] }
const nodeColors = {};
const colorPalette = [
    '#58a6ff', '#3fb950', '#d29922', '#f85149', '#bc8cff',
    '#39d2c0', '#e87de8', '#79c0ff', '#56d364', '#e3b341'
];
let colorIndex = 0;
let cpuOverviewChart = null;
let memOverviewChart = null;
const perNodeCpuCharts = {};
const perNodeMemCharts = {};
let knownNodeIds = new Set();

// ═══════════════════════════════════════════════════
// Chart.js Global Defaults
// ═══════════════════════════════════════════════════
Chart.defaults.color = '#8b949e';
Chart.defaults.borderColor = 'rgba(48, 54, 61, 0.5)';
Chart.defaults.font.family = "'Inter', sans-serif";
Chart.defaults.font.size = 11;

// ═══════════════════════════════════════════════════
// Tab Navigation
// ═══════════════════════════════════════════════════
// Note: Tab navigation is now handled by separate HTML pages with href links
// This code is only for the single-page index.html if needed
document.querySelectorAll('.tab[data-tab]').forEach(tab => {
    tab.addEventListener('click', () => {
        document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
        document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
        tab.classList.add('active');
        document.getElementById('tab-' + tab.dataset.tab).classList.add('active');
    });
});

// ═══════════════════════════════════════════════════
// Chart Factory
// ═══════════════════════════════════════════════════
function createChartConfig(type, isOverview) {
    return {
        type: 'line',
        data: { labels: [], datasets: [] },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: { duration: 400, easing: 'easeOutQuart' },
            interaction: { mode: 'index', intersect: false },
            plugins: {
                legend: { display: false },
                tooltip: {
                    backgroundColor: 'rgba(22, 27, 34, 0.95)',
                    borderColor: 'rgba(48, 54, 61, 0.8)',
                    borderWidth: 1,
                    titleColor: '#e6edf3',
                    bodyColor: '#8b949e',
                    padding: 10,
                    cornerRadius: 8,
                    callbacks: {
                        label: function (ctx) {
                            return ctx.dataset.label + ': ' + ctx.parsed.y.toFixed(1) + '%';
                        }
                    }
                }
            },
            scales: {
                x: {
                    display: true,
                    grid: { display: false },
                    ticks: {
                        maxTicksLimit: 6,
                        color: '#5c6370',
                        font: { size: 10 }
                    }
                },
                y: {
                    display: true,
                    min: 0,
                    max: 100,
                    grid: {
                        color: 'rgba(48, 54, 61, 0.3)',
                        drawBorder: false,
                    },
                    ticks: {
                        stepSize: 25,
                        callback: (v) => v + '%',
                        color: '#5c6370',
                        font: { size: 10 }
                    }
                }
            },
            elements: {
                point: { radius: 0, hoverRadius: 4 },
                line: {
                    tension: 0.35,
                    borderWidth: isOverview ? 2 : 1.5,
                }
            }
        }
    };
}

function getNodeColor(nodeId) {
    if (!nodeColors[nodeId]) {
        nodeColors[nodeId] = colorPalette[colorIndex % colorPalette.length];
        colorIndex++;
    }
    return nodeColors[nodeId];
}

// ═══════════════════════════════════════════════════
// Initialize Overview Charts
// ═══════════════════════════════════════════════════
function initOverviewCharts() {
    const cpuCanvas = document.getElementById('cpuOverviewChart');
    const memCanvas = document.getElementById('memOverviewChart');
    
    if (cpuCanvas && memCanvas) {
        const cpuCtx = cpuCanvas.getContext('2d');
        cpuOverviewChart = new Chart(cpuCtx, createChartConfig('cpu', true));

        const memCtx = memCanvas.getContext('2d');
        memOverviewChart = new Chart(memCtx, createChartConfig('mem', true));
    }
}

// ═══════════════════════════════════════════════════
// Per-Node Chart Cards (dynamically created)
// ═══════════════════════════════════════════════════
function ensurePerNodeCharts(nodeId) {
    if (perNodeCpuCharts[nodeId]) return;

    const color = getNodeColor(nodeId);

    // CPU card
    const cpuGrid = document.getElementById('cpu-perf-grid');
    if (!cpuGrid) return;
    const cpuCard = document.createElement('div');
    cpuCard.className = 'node-chart-card';
    cpuCard.id = 'cpu-card-' + nodeId;
    cpuCard.innerHTML = `
        <div class="node-label">
            <div class="node-name">
                <span class="node-dot" style="background:${color}"></span>
                ${nodeId}
            </div>
            <span class="node-val" id="cpu-val-${nodeId}" style="color:${color}">—%</span>
        </div>
        <div class="node-mini-chart">
            <canvas id="cpu-chart-${nodeId}"></canvas>
        </div>
    `;
    cpuGrid.appendChild(cpuCard);

    const cpuCtx = document.getElementById('cpu-chart-' + nodeId).getContext('2d');
    const cpuCfg = createChartConfig('cpu', false);
    cpuCfg.data.datasets = [{
        label: nodeId + ' CPU',
        data: [],
        borderColor: color,
        backgroundColor: hexToRgba(color, 0.08),
        fill: true,
    }];
    perNodeCpuCharts[nodeId] = new Chart(cpuCtx, cpuCfg);

    // Memory card
    const memGrid = document.getElementById('mem-perf-grid');
    const memCard = document.createElement('div');
    memCard.className = 'node-chart-card';
    memCard.id = 'mem-card-' + nodeId;
    memCard.innerHTML = `
        <div class="node-label">
            <div class="node-name">
                <span class="node-dot" style="background:${color}"></span>
                ${nodeId}
            </div>
            <span class="node-val" id="mem-val-${nodeId}" style="color:${color}">—%</span>
        </div>
        <div class="node-mini-chart">
            <canvas id="mem-chart-${nodeId}"></canvas>
        </div>
    `;
    memGrid.appendChild(memCard);

    const memCtx = document.getElementById('mem-chart-' + nodeId).getContext('2d');
    const memCfg = createChartConfig('mem', false);
    memCfg.data.datasets = [{
        label: nodeId + ' Memory',
        data: [],
        borderColor: color,
        backgroundColor: hexToRgba(color, 0.08),
        fill: true,
    }];
    perNodeMemCharts[nodeId] = new Chart(memCtx, memCfg);
}

// ═══════════════════════════════════════════════════
// Time Formatting Utilities
// ═══════════════════════════════════════════════════
function timeLabel() {
    const d = new Date();
    return d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function formatAge(ms) {
    if (ms < 1000) return ms + 'ms';
    const sec = (ms / 1000).toFixed(1);
    if (sec < 60) return sec + 's ago';
    const min = Math.floor(ms / 60000);
    return min + 'm ago';
}

function ageClass(ms) {
    if (ms > 6000) return 'dead';
    if (ms > 4000) return 'stale';
    return '';
}

// ═══════════════════════════════════════════════════
// Legend Updates
// ═══════════════════════════════════════════════════
function updateLegends(nodes) {
    const cpuLegend = document.getElementById('cpuLegend');
    const memLegend = document.getElementById('memLegend');
    let html = '';
    nodes.forEach(n => {
        const c = getNodeColor(n.nodeId);
        html += `<div class="legend-item"><span class="legend-dot" style="background:${c}"></span>${n.nodeId}</div>`;
    });
    cpuLegend.innerHTML = html;
    memLegend.innerHTML = html;
}

// ═══════════════════════════════════════════════════
// Main Fetch & Update Loop
// ═══════════════════════════════════════════════════
async function fetchAndUpdate() {
    try {
        console.log('Fetching from:', API_URL);
        const resp = await fetch(API_URL);
        console.log('Response status:', resp.status);
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        const data = await resp.json();
        console.log('Received data:', data);

        // Update connection status
        const badge = document.getElementById('connectionStatus');
        if (badge) {
            badge.querySelector('span:last-child').textContent = 'Connected';
            badge.style.borderColor = 'rgba(63, 185, 80, 0.2)';
            badge.style.color = 'var(--accent-green)';
        }
        // Update summary cards
        const s = data.summary;
        const avgCpuEl = document.getElementById('avg-cpu');
        if (avgCpuEl) {
            document.getElementById('avg-cpu').innerHTML = s.avgCpu.toFixed(1) + '<span class="unit">%</span>';
            document.getElementById('avg-mem').innerHTML = s.avgMemory.toFixed(1) + '<span class="unit">%</span>';
            document.getElementById('total-nodes').textContent = s.totalNodes;
            document.getElementById('alive-nodes').textContent = s.aliveNodes;
            document.getElementById('dead-nodes').textContent = s.deadNodes;
            document.getElementById('cpu-live').textContent = s.avgCpu.toFixed(1) + '%';
            document.getElementById('mem-live').textContent = s.avgMemory.toFixed(1) + '%';
        }
        const now = timeLabel();

        // Process each node
        const nodes = data.nodes || [];
        nodes.sort((a, b) => a.nodeId.localeCompare(b.nodeId));

        nodes.forEach(node => {
            const id = node.nodeId;

            // Init history if new node
            if (!nodeHistory[id]) {
                nodeHistory[id] = { cpu: [], mem: [], labels: [] };
                if (typeof knownNodeIds !== 'undefined') knownNodeIds.add(id);
            }

            // Push real data
            nodeHistory[id].cpu.push(node.cpuUsage);
            nodeHistory[id].mem.push(node.memoryUsage);
            nodeHistory[id].labels.push(now);

            // Trim to rolling window
            if (nodeHistory[id].cpu.length > MAX_DATA_POINTS) {
                nodeHistory[id].cpu.shift();
                nodeHistory[id].mem.shift();
                nodeHistory[id].labels.shift();
            }

            // Ensure per-node charts exist
            ensurePerNodeCharts(id);
        });

        // Update all chart views
        // updateOverviewCharts(nodes);
        // updatePerNodeCharts(nodes);
        // updateLegends(nodes);
        // updateOverviewTable(nodes);
        // updateProcessesTable(nodes);

        // 3. Conditional View Updates (CRITICAL)
        // Only update charts if the chart objects exist
        if (cpuOverviewChart && memOverviewChart) {
            updateOverviewCharts(nodes);
        }

        // Only update per-node charts if we are on the performance page
        if (Object.keys(perNodeCpuCharts).length > 0) {
            updatePerNodeCharts(nodes);
        }

        // Only update tables/legends if their specific containers exist
        if (document.getElementById('cpuLegend')) updateLegends(nodes);
        if (document.getElementById('overview-nodes-body')) updateOverviewTable(nodes);
        if (document.getElementById('processes-nodes-body')) updateProcessesTable(nodes);

        // Footer timestamp
        //document.getElementById('last-update').textContent = 'Last update: ' + now;

        // 4. Footer timestamp (Shared)
        const lastUpdateEl = document.getElementById('last-update');
        if (lastUpdateEl) lastUpdateEl.textContent = 'Last update: ' + now;

    } catch (err) {
        console.error('Fetch error:', err);
        console.error('Error details:', err.message);
        const badge = document.getElementById('connectionStatus');
        if (badge) {
            badge.querySelector('span:last-child').textContent = 'Disconnected';
            badge.style.borderColor = 'rgba(248, 81, 73, 0.2)';
            badge.style.color = 'var(--accent-red)';
        }
    }
}

// ═══════════════════════════════════════════════════
// Chart Update Functions
// ═══════════════════════════════════════════════════
function updateOverviewCharts(nodes) {
    const cpuDatasets = [];
    const memDatasets = [];
    let labels = [];

    nodes.forEach(node => {
        const id = node.nodeId;
        const h = nodeHistory[id];
        if (!h) return;
        const color = getNodeColor(id);

        if (h.labels.length > labels.length) labels = h.labels;

        cpuDatasets.push({
            label: id,
            data: h.cpu,
            borderColor: color,
            backgroundColor: hexToRgba(color, 0.06),
            fill: true,
        });
        memDatasets.push({
            label: id,
            data: h.mem,
            borderColor: color,
            backgroundColor: hexToRgba(color, 0.06),
            fill: true,
        });
    });

    cpuOverviewChart.data.labels = labels;
    cpuOverviewChart.data.datasets = cpuDatasets;
    cpuOverviewChart.update('none');

    memOverviewChart.data.labels = labels;
    memOverviewChart.data.datasets = memDatasets;
    memOverviewChart.update('none');
}

function updatePerNodeCharts(nodes) {
    nodes.forEach(node => {
        const id = node.nodeId;
        const h = nodeHistory[id];

        // CPU per-node
        const cpuChart = perNodeCpuCharts[id];
        cpuChart.data.labels = h.labels;
        cpuChart.data.datasets[0].data = h.cpu;
        cpuChart.update('none');
        document.getElementById('cpu-val-' + id).textContent = node.cpuUsage.toFixed(1) + '%';

        // Memory per-node
        const memChart = perNodeMemCharts[id];
        memChart.data.labels = h.labels;
        memChart.data.datasets[0].data = h.mem;
        memChart.update('none');
        document.getElementById('mem-val-' + id).textContent = node.memoryUsage.toFixed(1) + '%';
    });
}

// ═══════════════════════════════════════════════════
// Table Update Functions
// ═══════════════════════════════════════════════════
function updateOverviewTable(nodes) {
    const tbody = document.getElementById('overview-nodes-body');
    if (nodes.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" class="empty-state"><p>Waiting for nodes...</p></td></tr>';
        return;
    }

    let html = '';
    nodes.forEach(n => {
        const statusClass = n.status === 'ALIVE' ? 'alive' : 'dead';
        const ageCls = ageClass(n.heartbeatAgeMs);

        html += `<tr>
            <td>
                <div class="node-id-cell">
                    <span class="node-status-dot ${statusClass}"></span>
                    ${n.nodeId}
                </div>
            </td>
            <td><span class="status-tag ${statusClass}">${n.status}</span></td>
            <td>
                <div class="usage-bar-container">
                    <div class="usage-bar"><div class="usage-bar-fill cpu" style="width:${n.cpuUsage}%"></div></div>
                    <span class="usage-val" style="color:var(--cpu-color)">${n.cpuUsage.toFixed(1)}%</span>
                </div>
            </td>
            <td>
                <div class="usage-bar-container">
                    <div class="usage-bar"><div class="usage-bar-fill mem" style="width:${n.memoryUsage}%"></div></div>
                    <span class="usage-val" style="color:var(--mem-color)">${n.memoryUsage.toFixed(1)}%</span>
                </div>
            </td>
            <td><span class="heartbeat-age ${ageCls}">${formatAge(n.heartbeatAgeMs)}</span></td>
        </tr>`;
    });
    tbody.innerHTML = html;
}

function updateProcessesTable(nodes) {
    const tbody = document.getElementById('processes-nodes-body');
    if (nodes.length === 0) {
        tbody.innerHTML = '<tr><td colspan="8" class="empty-state"><p>Waiting for nodes to register...</p></td></tr>';
        return;
    }

    let html = '';
    nodes.forEach(n => {
        const statusClass = n.status === 'ALIVE' ? 'alive' : 'dead';
        const ageCls = ageClass(n.heartbeatAgeMs);

        html += `<tr>
            <td>
                <div class="node-id-cell">
                    <span class="node-status-dot ${statusClass}"></span>
                    ${n.nodeId}
                </div>
            </td>
            <td style="color:var(--text-secondary)">${n.ip}</td>
            <td style="color:var(--text-secondary)">${n.hostname}</td>
            <td style="color:var(--text-secondary)">${n.port}</td>
            <td>
                <div class="usage-bar-container">
                    <div class="usage-bar"><div class="usage-bar-fill cpu" style="width:${n.cpuUsage}%"></div></div>
                    <span class="usage-val" style="color:var(--cpu-color)">${n.cpuUsage.toFixed(1)}%</span>
                </div>
            </td>
            <td>
                <div class="usage-bar-container">
                    <div class="usage-bar"><div class="usage-bar-fill mem" style="width:${n.memoryUsage}%"></div></div>
                    <span class="usage-val" style="color:var(--mem-color)">${n.memoryUsage.toFixed(1)}%</span>
                </div>
            </td>
            <td><span class="status-tag ${statusClass}">${n.status}</span></td>
            <td><span class="heartbeat-age ${ageCls}">${formatAge(n.heartbeatAgeMs)}</span></td>
        </tr>`;
    });
    tbody.innerHTML = html;
}

// ═══════════════════════════════════════════════════
// Utility
// ═══════════════════════════════════════════════════
function hexToRgba(hex, alpha) {
    if (hex.startsWith('#')) {
        const r = parseInt(hex.slice(1, 3), 16);
        const g = parseInt(hex.slice(3, 5), 16);
        const b = parseInt(hex.slice(5, 7), 16);
        return `rgba(${r}, ${g}, ${b}, ${alpha})`;
    }
    return hex;
}

// ═══════════════════════════════════════════════════
// Bootstrap — Start the dashboard
// ═══════════════════════════════════════════════════
initOverviewCharts();
fetchAndUpdate();
setInterval(fetchAndUpdate, POLL_INTERVAL);
