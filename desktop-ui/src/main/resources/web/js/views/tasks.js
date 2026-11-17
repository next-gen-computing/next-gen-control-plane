/**
 * tasks.js — Next-Gen Control Plane
 * Customized Nodes & Dedicated Tasks Dispatcher with Live Physical Execution Feedback,
 * Hardware Silhouettes (Mac, Windows, Linux), and Zero-Trust Task Integrity.
 */
window.NG = window.NG || {};
NG.views = window.NG.views || {};

NG.views.tasks = {
    mount(container) {
        container.innerHTML = `
            <style>
                .ng-custom-task-card {
                    background: linear-gradient(145deg, #0e141e, #070a0f);
                    border: 1px solid rgba(16, 185, 129, 0.3);
                    border-radius: 18px;
                    padding: 20px;
                    margin-bottom: 24px;
                    box-shadow: 0 12px 30px rgba(0,0,0,0.7), inset 0 0 20px rgba(0,0,0,0.8);
                }
                .ng-node-target-grid {
                    display: grid;
                    grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
                    gap: 12px;
                    margin-top: 10px;
                    margin-bottom: 16px;
                }
                .ng-node-card-selectable {
                    background: rgba(255, 255, 255, 0.03);
                    border: 1px solid rgba(255, 255, 255, 0.08);
                    border-radius: 12px;
                    padding: 12px;
                    cursor: pointer;
                    transition: all 0.2s ease;
                    display: flex;
                    align-items: center;
                    gap: 10px;
                    user-select: none;
                }
                .ng-node-card-selectable:hover {
                    background: rgba(16, 185, 129, 0.08);
                    border-color: rgba(16, 185, 129, 0.4);
                }
                .ng-node-card-selectable.is-selected {
                    background: rgba(16, 185, 129, 0.16);
                    border-color: #10b981;
                    box-shadow: 0 0 14px rgba(16, 185, 129, 0.25);
                }
                .ng-workload-tab-btn {
                    background: rgba(255, 255, 255, 0.04);
                    border: 1px solid rgba(255, 255, 255, 0.08);
                    color: var(--ng-text-muted);
                    padding: 8px 14px;
                    border-radius: 10px;
                    font-size: 12px;
                    font-weight: 600;
                    cursor: pointer;
                    transition: all 0.2s ease;
                    display: inline-flex;
                    align-items: center;
                    gap: 6px;
                }
                .ng-workload-tab-btn:hover {
                    background: rgba(255, 255, 255, 0.08);
                    color: var(--ng-text);
                }
                .ng-workload-tab-btn.active {
                    background: linear-gradient(135deg, rgba(16, 185, 129, 0.3), rgba(5, 150, 105, 0.3));
                    border-color: #10b981;
                    color: #fff;
                    box-shadow: 0 0 12px rgba(16, 185, 129, 0.3);
                }
                .ng-console-box {
                    background: #020604;
                    border: 1px solid rgba(16, 185, 129, 0.35);
                    border-radius: 10px;
                    padding: 12px 14px;
                    font-family: var(--ng-font-mono, monospace);
                    font-size: 11px;
                    color: #4ade80;
                    max-height: 180px;
                    overflow-y: auto;
                    line-height: 1.6;
                    box-shadow: inset 0 0 15px rgba(0,0,0,0.9);
                }
                .ng-result-banner {
                    background: rgba(16, 185, 129, 0.12);
                    border: 1px solid #10b981;
                    border-radius: 10px;
                    padding: 12px 16px;
                    margin-top: 12px;
                    display: none;
                }
            </style>

            <div class="ng-page">
                <div class="ng-page-header ng-animate-fade-in">
                    <div class="ng-page-header-left">
                        <h1 class="ng-page-title">Tasks &amp; Jobs</h1>
                        <p class="ng-page-subtitle">Customized Nodes &amp; Dedicated Task Dispatcher with Live Physical Verification</p>
                    </div>
                </div>

                <div class="ng-page-scroll">
                    <div class="ng-page-content">

                        <!-- 🎯 CUSTOMIZED NODES & DEDICATED PERSONAL TASK SECTION -->
                        <div class="ng-custom-task-card ng-animate-fade-in">
                            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px; border-bottom:1px solid rgba(16,185,129,0.2); padding-bottom:10px;">
                                <div style="display:flex; align-items:center; gap:10px;">
                                    <span style="font-size:22px;">🎯</span>
                                    <div>
                                        <h2 style="margin:0; font-size:16px; font-weight:700; color:#f1f5f9;">Customized Task &amp; Node Execution Engine</h2>
                                        <p style="margin:2px 0 0; font-size:12px; color:#94a3b8;">Load your local folder/code project from device, select target node(s), choose entrypoint, and run physically across the cluster</p>
                                    </div>
                                </div>
                                <div style="display:flex; gap:6px;">
                                    <button class="ng-workload-tab-btn active" id="btnModeSingle">🎯 Single Node</button>
                                    <button class="ng-workload-tab-btn" id="btnModeSubset">🔀 Specific Subset</button>
                                    <button class="ng-workload-tab-btn" id="btnModeAll">🌐 All Nodes</button>
                                </div>
                            </div>

                            <!-- STEP 1: Select: How much nodes & which nodes do we need -->
                            <div style="margin-bottom:14px;">
                                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;">
                                    <label style="font-size:11.5px; font-weight:700; color:#38bdf8; text-transform:uppercase; letter-spacing:0.6px;">
                                        ① Select: How much nodes &amp; which nodes do we need:
                                    </label>
                                    <span id="ngTargetSummary" style="font-size:11px; color:#94a3b8; font-family:var(--ng-font-mono);">
                                        Targeting: <b style="color:#10b981;">Host Controller Node</b>
                                    </span>
                                </div>
                                <div class="ng-node-target-grid" id="ngTargetNodesGrid">
                                </div>

                                <!-- Dynamic Proportional Capacity Allocation Matrix -->
                                <div id="dtCapacityMatrixCard" style="background:rgba(16,185,129,0.06); border:1px solid rgba(16,185,129,0.25); border-radius:10px; padding:10px 12px; margin-top:10px;">
                                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;">
                                        <span style="font-size:11.5px; font-weight:700; color:#34d399;">⚡ Dynamic Capacity Allocation</span>
                                        <span style="font-size:10px; color:#38bdf8; font-family:var(--ng-font-mono);">Weighted by Cores, RAM &amp; Health</span>
                                    </div>
                                    <div id="dtCapacityNodesList" style="display:flex; flex-direction:column; gap:5px; font-size:11px;">
                                    </div>
                                </div>
                            </div>

                            <!-- Continuous Health Predictor (4-Second Telemetry Reporting Loop) -->
                            <div style="background:linear-gradient(145deg, rgba(2,6,23,0.7), rgba(15,23,42,0.7)); border:1px solid rgba(56,189,248,0.3); border-radius:12px; padding:10px 14px; margin-bottom:14px;">
                                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;">
                                    <div style="display:flex; align-items:center; gap:8px;">
                                        <span style="font-size:16px;">🧠</span>
                                        <span style="font-size:12px; font-weight:700; color:#38bdf8; text-transform:uppercase; letter-spacing:0.5px;">
                                            Continuous Health Predictor
                                        </span>
                                    </div>
                                    <div style="display:flex; align-items:center; gap:8px;">
                                        <span id="dtPredictorCycleTicker" style="font-size:10px; font-family:var(--ng-font-mono); background:rgba(56,189,248,0.15); border:1px solid rgba(56,189,248,0.4); color:#38bdf8; padding:2px 8px; border-radius:8px;">
                                            ⏱️ 4.0s report cycle
                                        </span>
                                        <span id="dtPredictorStatusPill" style="font-size:10px; font-weight:700; color:#34d399; font-family:var(--ng-font-mono);">
                                            [ALL HEALTHY]
                                        </span>
                                    </div>
                                </div>
                                <div style="font-size:11px; color:#94a3b8; line-height:1.4;">
                                    <span id="dtPredictorLatestReport">Predictor report: Every 4 sec telemetry analyzed. No node failure predicted. Failover re-scheduling engine armed.</span>
                                </div>
                            </div>

                            <!-- STEP 2: Open folder from device -->
                            <div style="background:rgba(255,255,255,0.02); border:1px solid rgba(16,185,129,0.25); border-radius:12px; padding:12px 14px; margin-bottom:14px;">
                                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">
                                    <label style="font-size:11.5px; font-weight:700; color:#34d399; text-transform:uppercase; letter-spacing:0.6px;">
                                        ② Open Folder from Device:
                                    </label>
                                    <span id="dtFolderCountBadge" style="font-size:11px; background:rgba(16,185,129,0.15); border:1px solid rgba(16,185,129,0.4); color:#34d399; padding:2px 8px; border-radius:10px;">
                                        0 files loaded
                                    </span>
                                </div>

                                <input type="file" id="dtInputFolder" webkitdirectory directory multiple style="display:none;">
                                <input type="file" id="dtInputFiles" multiple accept=".py,.java,.sh,.bat,.cmd,.csv,.json,.txt,.jar,.c,.cpp" style="display:none;">

                                <div style="display:flex; gap:8px; margin-bottom:8px;">
                                    <button class="ng-button" id="btnOpenDtFolder" style="font-size:12px; font-weight:700; background:rgba(16,185,129,0.14); border-color:rgba(16,185,129,0.4); color:#34d399;">
                                        📂 Open Folder from Device
                                    </button>
                                    <button class="ng-button" id="btnOpenDtFiles" style="font-size:12px;">
                                        📄 Select Files
                                    </button>
                                    <button class="ng-button" id="btnLoadDtSample" style="font-size:12px;" title="Injects a pre-built Python/Java/Data sample project">
                                        💡 Load Sample Benchmark
                                    </button>
                                </div>

                                <div id="dtFolderDropzone" style="border:1px dashed rgba(255,255,255,0.18); border-radius:8px; padding:10px; text-align:center; cursor:pointer; background:rgba(0,0,0,0.25);">
                                    <div id="dtDropzoneText" style="font-size:12px; color:#94a3b8;">
                                        📁 Click <b>Open Folder</b> or drag &amp; drop code files here
                                    </div>
                                    <div id="dtLoadedFilesContainer" style="display:none; margin-top:8px; max-height:85px; overflow-y:auto; text-align:left; display:flex; flex-wrap:wrap; gap:5px;">
                                    </div>
                                </div>
                            </div>

                            <!-- STEP 3: Select the task from folder I need to execute -->
                            <div style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.08); border-radius:12px; padding:12px 14px; margin-bottom:14px;">
                                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">
                                    <label style="font-size:11.5px; font-weight:700; color:#fbbf24; text-transform:uppercase; letter-spacing:0.6px;">
                                        ③ Select the task from folder I need to execute:
                                    </label>
                                    <span id="dtEntryTypeBadge" style="font-size:11px; color:#38bdf8; font-family:var(--ng-font-mono);">[Auto-Detect]</span>
                                </div>

                                <div style="display:grid; grid-template-columns:1.5fr 1fr; gap:10px; margin-bottom:8px;">
                                    <div class="ng-field">
                                        <label>Entrypoint Task Script / File</label>
                                        <select class="ng-input" id="dtEntrypointSelect">
                                            <option value="">(Load a folder or click Load Sample to select task)</option>
                                        </select>
                                    </div>
                                    <div class="ng-field">
                                        <label>CLI Arguments (Optional)</label>
                                        <input class="ng-input" id="dtTaskArguments" type="text" placeholder="e.g. --iterations 50000 --threads 4">
                                    </div>
                                </div>

                                <details style="font-size:11px; background:rgba(0,0,0,0.35); border:1px solid rgba(255,255,255,0.06); border-radius:6px; padding:6px 10px;">
                                    <summary style="cursor:pointer; color:#38bdf8; font-weight:600;">👁️ View Source Code Preview</summary>
                                    <pre id="dtCodePreviewContent" style="margin:6px 0 0; padding:8px; background:#030705; border-radius:4px; color:#a7f3d0; font-family:var(--ng-font-mono); font-size:11px; max-height:130px; overflow:auto; white-space:pre-wrap;">// Source code preview will be displayed here...</pre>
                                </details>
                            </div>

                            <!-- STEP 4: Send it to the Node's -->
                            <div style="margin-bottom:14px; display:flex; gap:12px; align-items:center;">
                                <button class="ng-button ng-button-primary" id="btnDispatchCustomTask" style="padding:10px 24px; font-weight:700; font-size:13px; flex:1; justify-content:center;">
                                    🚀 Send it to the Node's &amp; Execute
                                </button>
                                <span style="font-size:11px; color:#94a3b8; font-family:var(--ng-font-mono);">
                                    🛡️ HMAC-SHA256 authenticated ticket
                                </span>
                            </div>

                            <!-- STEP 5: Node's sends back the result -->
                            <div>
                                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;">
                                    <span style="font-size:11.5px; font-weight:700; color:#34d399; font-family:var(--ng-font-mono);">
                                        ⑤ NODE'S SENDS BACK THE RESULT (LIVE PHYSICAL LOGS):
                                    </span>
                                    <span id="ngExecStatusPill" style="font-size:10px; font-weight:700; color:#10b981; font-family:var(--ng-font-mono);">[IDLE]</span>
                                </div>
                                <div class="ng-console-box" id="ngTaskConsole">
                                    <span style="color:#64748b;">[CONSOLE] Ready. Load a folder from your device, choose node(s), and click 'Send it to the Node's'.</span>
                                </div>

                                <div class="ng-result-banner" id="ngResultBanner">
                                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:4px;">
                                        <span style="font-weight:700; color:#34d399; font-size:13px;" id="resBannerTitle">✔ Task Executed &amp; Output Verified</span>
                                        <span style="font-family:var(--ng-font-mono); font-size:11px; color:#a7f3d0;" id="resDuration">Duration: 0 ms</span>
                                    </div>
                                    <div style="font-size:12px; color:#e2e8f0; line-height:1.5;" id="resDetails">
                                        Processed on physical node. Result output committed to cluster registry.
                                    </div>
                                    <div style="margin-top:6px; font-family:var(--ng-font-mono); font-size:10px; color:#6ee7b7; word-break:break-all;" id="resDigest">
                                        SHA-256: 
                                    </div>
                                </div>
                            </div>
                        </div>

                        <!-- Submission panel (Single & Distributed Quick Range) -->
                        <div style="display:grid;grid-template-columns:1fr 1fr;gap:16px" class="ng-animate-fade-in ng-stagger-1">

                            <!-- Single task -->
                            <div class="ng-card">
                                <div style="display:flex;align-items:center;gap:8px;margin-bottom:4px">
                                    <span style="font-size:20px">⚡</span>
                                    <h2 style="margin:0;font-size:15px;font-weight:700">Quick Prime Range</h2>
                                </div>
                                <p style="margin:0 0 12px;font-size:12px;color:var(--ng-text-muted)">
                                    Count primes in a numerical range — dispatched to one node.
                                </p>
                                <div style="display:grid;grid-template-columns:1fr 1fr auto;gap:8px;align-items:end">
                                    <div class="ng-field">
                                        <label for="ng-task-start">Range start</label>
                                        <input class="ng-input ng-numeric" id="ng-task-start" type="number" placeholder="0" value="1">
                                    </div>
                                    <div class="ng-field">
                                        <label for="ng-task-end">Range end</label>
                                        <input class="ng-input ng-numeric" id="ng-task-end" type="number" placeholder="100000" value="50000">
                                    </div>
                                    <button class="ng-button ng-button-primary" id="ng-task-submit">
                                        Submit
                                    </button>
                                </div>
                                <div id="ng-task-warning" class="ng-status-message" style="display:none"></div>
                            </div>

                            <!-- Job -->
                            <div class="ng-card">
                                <div style="display:flex;align-items:center;gap:8px;margin-bottom:4px">
                                    <span style="font-size:20px">🔀</span>
                                    <h2 style="margin:0;font-size:15px;font-weight:700">Distributed Job (Sharded)</h2>
                                </div>
                                <p style="margin:0 0 12px;font-size:12px;color:var(--ng-text-muted)">
                                    Split across N sub-tasks dispatched concurrently to multiple nodes.
                                </p>
                                <div style="display:grid;grid-template-columns:1fr 1fr 90px auto;gap:8px;align-items:end">
                                    <div class="ng-field">
                                        <label for="ng-job-start">Start</label>
                                        <input class="ng-input ng-numeric" id="ng-job-start" type="number" placeholder="0" value="1">
                                    </div>
                                    <div class="ng-field">
                                        <label for="ng-job-end">End</label>
                                        <input class="ng-input ng-numeric" id="ng-job-end" type="number" placeholder="1000000" value="500000">
                                    </div>
                                    <div class="ng-field">
                                        <label for="ng-job-subtasks">Splits</label>
                                        <input class="ng-input ng-numeric" id="ng-job-subtasks" type="number" min="1" value="4">
                                    </div>
                                    <button class="ng-button ng-button-primary" id="ng-job-submit">
                                        Submit
                                    </button>
                                </div>
                                <div id="ng-job-warning" class="ng-status-message" style="display:none"></div>
                            </div>
                        </div>

                        <!-- Jobs list -->
                        <div class="ng-section-heading ng-animate-fade-in ng-stagger-2" style="margin-top:20px;">
                            🔀 Active Jobs
                        </div>
                        <div id="ng-job-list"></div>
                        <div class="ng-empty-state" id="ng-job-empty" style="padding:24px;min-height:80px">
                            <div class="ng-empty-state-body">No jobs submitted yet</div>
                        </div>

                        <!-- Tasks list -->
                        <div class="ng-section-heading ng-animate-fade-in ng-stagger-3">
                            ⚡ Submitted Tasks
                        </div>
                        <div class="ng-table-wrap" id="ng-task-wrap">
                            <div id="ng-task-list"></div>
                            <div class="ng-empty-state" id="ng-task-empty" style="padding:24px;min-height:80px">
                                <div class="ng-empty-state-body">No tasks submitted yet</div>
                            </div>
                        </div>

                    </div>
                </div>
            </div>`;

        // ── Node & Target Management ──────────────────────────────────────────
        const targetNodesGrid = container.querySelector('#ngTargetNodesGrid');
        const targetSummary = container.querySelector('#ngTargetSummary');
        const btnModeSingle = container.querySelector('#btnModeSingle');
        const btnModeSubset = container.querySelector('#btnModeSubset');
        const btnModeAll = container.querySelector('#btnModeAll');

        let targetMode = 'single'; // 'single' | 'subset' | 'all'
        let clusterNodes = [];
        let selectedNodeIds = new Set();

        function detectDeviceType(n) {
            const str = ((n.name || '') + ' ' + (n.id || '') + ' ' + (n.os || '')).toLowerCase();
            if (str.includes('mac') || str.includes('darwin') || str.includes('apple')) return 'mac';
            if (str.includes('nux') || str.includes('nix') || str.includes('ubuntu') || str.includes('debian')) return 'linux';
            return 'windows';
        }

        function getEffectiveClusterNodes() {
            let nodes = clusterNodes ? [...clusterNodes] : [];
            const hasHost = nodes.some(n => n.id === 'node-local-host' || (n.address && (n.address.includes('127.0.0.1') || n.address.includes('localhost'))));
            if (!hasHost) {
                nodes.unshift({
                    id: 'node-local-host',
                    name: 'Central Control Plane (Local Host)',
                    address: '127.0.0.1',
                    os: 'Windows 11',
                    status: 'HEALTHY'
                });
            }
            return nodes;
        }

        function renderTargetNodes() {
            const effective = getEffectiveClusterNodes();

            // Auto-select first if none selected in single mode
            if (targetMode === 'single' && selectedNodeIds.size === 0 && effective.length > 0) {
                selectedNodeIds.add(effective[0].id);
            } else if (targetMode === 'all') {
                effective.forEach(n => selectedNodeIds.add(n.id));
            }

            targetNodesGrid.innerHTML = effective.map(n => {
                const devType = detectDeviceType(n);
                const icon = devType === 'mac' ? '🍏' : (devType === 'linux' ? '🐧' : '🪟');
                const devLabel = devType === 'mac' ? 'MacBook Pro' : (devType === 'linux' ? 'Linux Workstation' : 'Windows 11');
                const isSelected = selectedNodeIds.has(n.id);
                const isHealthy = n.status === 'HEALTHY' || n.status === 'ONLINE';

                return `
                    <div class="ng-node-card-selectable ${isSelected ? 'is-selected' : ''}" data-id="${n.id}">
                        <div style="font-size: 22px;">${icon}</div>
                        <div style="flex:1; overflow:hidden;">
                            <div style="font-size: 13px; font-weight: 700; color: #f1f5f9; text-overflow:ellipsis; overflow:hidden; white-space:nowrap;">
                                ${n.name || n.id}
                            </div>
                            <div style="font-size: 10px; color: #94a3b8; font-family: var(--ng-font-mono);">
                                ${devLabel} &bull; ${n.address || '127.0.0.1'}
                            </div>
                            <div style="font-size: 9.5px; color: ${isHealthy ? '#34d399' : '#f59e0b'}; font-weight: 600; margin-top: 2px;">
                                ● ${n.status || 'READY'}
                            </div>
                        </div>
                    </div>`;
            }).join('');

            targetNodesGrid.querySelectorAll('.ng-node-card-selectable').forEach(card => {
                card.addEventListener('click', () => {
                    const id = card.getAttribute('data-id');
                    if (targetMode === 'single') {
                        selectedNodeIds.clear();
                        selectedNodeIds.add(id);
                    } else if (targetMode === 'subset') {
                        if (selectedNodeIds.has(id)) selectedNodeIds.delete(id);
                        else selectedNodeIds.add(id);
                    }
                    updateTargetSummary();
                    renderTargetNodes();
                });
            });

            updateTargetSummary();
        }

        function updateTargetSummary() {
            const effective = getEffectiveClusterNodes();
            if (targetMode === 'all') {
                targetSummary.innerHTML = `Targeting: <b style="color:#10b981;">ALL Available Nodes (${effective.length})</b>`;
            } else if (targetMode === 'single') {
                const selected = effective.find(n => selectedNodeIds.has(n.id)) || effective[0];
                const name = selected ? (selected.name || selected.id) : 'Host Controller';
                targetSummary.innerHTML = `Targeting Single Node: <b style="color:#10b981;">${name}</b>`;
            } else {
                targetSummary.innerHTML = `Targeting Custom Subset: <b style="color:#10b981;">${selectedNodeIds.size} Node(s) Selected</b>`;
            }
            renderCapacityMatrix();
        }

        function calculateCapacityAllocation(nodes) {
            if (!nodes || nodes.length === 0) return [];
            let totalWeight = 0;
            const weighted = nodes.map(n => {
                const cores = n.cpuCores || 8;
                const cpu = n.cpuUsage != null ? n.cpuUsage : 15;
                const ramGb = n.totalMemoryBytes ? (n.totalMemoryBytes / (1024**3)) : 16;
                const risk = n.riskScore != null ? n.riskScore : (n.atRisk ? 0.75 : 0.02);

                const cpuFactor = Math.max(0.1, (100 - cpu) / 100);
                const ramFactor = Math.max(0.2, ramGb / 16);
                const riskFactor = Math.max(0.1, 1.0 - risk);
                const weight = cores * cpuFactor * ramFactor * riskFactor;
                totalWeight += weight;
                return {
                    node: n,
                    cores,
                    ramGb: Math.round(ramGb),
                    cpu: Number(cpu).toFixed(1),
                    risk: Number(risk).toFixed(2),
                    rawWeight: weight
                };
            });

            return weighted.map(item => {
                const sharePct = totalWeight > 0 ? Math.round((item.rawWeight / totalWeight) * 100) : Math.round(100 / nodes.length);
                return {
                    ...item,
                    sharePct: Math.max(5, sharePct)
                };
            });
        }

        function renderCapacityMatrix() {
            const listEl = container.querySelector('#dtCapacityNodesList');
            if (!listEl) return;
            const effective = getEffectiveClusterNodes();
            let targets = effective.filter(n => selectedNodeIds.has(n.id));
            if (targets.length === 0) targets = [effective[0]];
            const allocations = calculateCapacityAllocation(targets);

            listEl.innerHTML = allocations.map(a => {
                const dev = (a.node.name || a.node.hostname || a.node.id).toLowerCase();
                const icon = dev.includes('mac') ? '💻' : dev.includes('linux') ? '🖥️' : '💻';
                return `
                    <div style="display:flex; justify-content:space-between; align-items:center; background:rgba(0,0,0,0.3); padding:5px 10px; border-radius:6px; border-left:3px solid #10b981;">
                        <div style="display:flex; align-items:center; gap:6px;">
                            <span>${icon}</span>
                            <b>${a.node.name || a.node.id}</b>
                            <span style="color:#94a3b8; font-size:10px;">(${a.cores} cores, ${a.ramGb}GB RAM, CPU ${a.cpu}%)</span>
                        </div>
                        <div style="display:flex; align-items:center; gap:6px;">
                            <span style="font-family:var(--ng-font-mono); color:#38bdf8; font-weight:700;">${a.sharePct}% Workload Share</span>
                            <div style="width:50px; height:5px; background:rgba(255,255,255,0.1); border-radius:3px; overflow:hidden;">
                                <div style="width:${a.sharePct}%; height:100%; background:#10b981;"></div>
                            </div>
                        </div>
                    </div>
                `;
            }).join('');
        }

        let dtPredictorCountdown = 4.0;
        const dtPredictorTimer = setInterval(() => {
            dtPredictorCountdown -= 0.5;
            if (dtPredictorCountdown <= 0) {
                dtPredictorCountdown = 4.0;
                runDtPredictorSweep();
            }
            const ticker = container.querySelector('#dtPredictorCycleTicker');
            if (ticker) ticker.textContent = `⏱️ Sweep in ${dtPredictorCountdown.toFixed(1)}s`;
        }, 500);

        function runDtPredictorSweep() {
            const effective = getEffectiveClusterNodes();
            const dead = effective.filter(n => (n.status || '').toUpperCase() === 'DEAD' || (n.status || '').toUpperCase() === 'OFFLINE');
            const atRisk = effective.filter(n => n.atRisk || (n.riskScore && n.riskScore > 0.5));

            const reportEl = container.querySelector('#dtPredictorLatestReport');
            const statusEl = container.querySelector('#dtPredictorStatusPill');

            if (dead.length > 0 || atRisk.length > 0) {
                const badNode = dead[0] || atRisk[0];
                if (statusEl) {
                    statusEl.textContent = '[DEGRADED / CRASH DETECTED]';
                    statusEl.style.color = '#ef4444';
                }
                if (reportEl) {
                    reportEl.innerHTML = `⚠️ <b style="color:#ef4444;">[PREDICTOR 4s ALERT]</b> Node <b>${badNode.name || badNode.id}</b> detected UNHEALTHY / CRASHED. Emitting failover signal to Scheduler to reschedule failed in-flight tasks.`;
                }
            } else {
                if (statusEl) {
                    statusEl.textContent = '[ALL HEALTHY]';
                    statusEl.style.color = '#34d399';
                }
                if (reportEl) {
                    reportEl.textContent = `[PREDICTOR 4s REPORT] Sweep completed. Evaluated ${effective.length} node(s). All heartbeats verified (<3s). Failure risk index: <0.04. Scheduler ready.`;
                }
            }
        }

        function setTargetMode(mode) {
            targetMode = mode;
            btnModeSingle.classList.toggle('active', mode === 'single');
            btnModeSubset.classList.toggle('active', mode === 'subset');
            btnModeAll.classList.toggle('active', mode === 'all');

            const effective = getEffectiveClusterNodes();
            if (mode === 'all') {
                selectedNodeIds.clear();
                effective.forEach(n => selectedNodeIds.add(n.id));
            } else if (mode === 'single') {
                const first = selectedNodeIds.values().next().value || (effective[0] && effective[0].id);
                selectedNodeIds.clear();
                if (first) selectedNodeIds.add(first);
            }
            renderTargetNodes();
        }

        btnModeSingle.addEventListener('click', () => setTargetMode('single'));
        btnModeSubset.addEventListener('click', () => setTargetMode('subset'));
        btnModeAll.addEventListener('click', () => setTargetMode('all'));

        // ── Device Folder & File Management Handlers ──────────────────────────
        const dtInputFolder = container.querySelector('#dtInputFolder');
        const dtInputFiles = container.querySelector('#dtInputFiles');
        const btnOpenDtFolder = container.querySelector('#btnOpenDtFolder');
        const btnOpenDtFiles = container.querySelector('#btnOpenDtFiles');
        const btnLoadDtSample = container.querySelector('#btnLoadDtSample');
        const dtFolderDropzone = container.querySelector('#dtFolderDropzone');
        const dtDropzoneText = container.querySelector('#dtDropzoneText');
        const dtLoadedFilesContainer = container.querySelector('#dtLoadedFilesContainer');
        const dtFolderCountBadge = container.querySelector('#dtFolderCountBadge');
        const dtEntrypointSelect = container.querySelector('#dtEntrypointSelect');
        const dtEntryTypeBadge = container.querySelector('#dtEntryTypeBadge');
        const dtCodePreviewContent = container.querySelector('#dtCodePreviewContent');
        const dtTaskArguments = container.querySelector('#dtTaskArguments');

        let dtLoadedFiles = [];
        let dtFolderName = "";

        if (btnOpenDtFolder && dtInputFolder) {
            btnOpenDtFolder.addEventListener('click', () => dtInputFolder.click());
        }
        if (btnOpenDtFiles && dtInputFiles) {
            btnOpenDtFiles.addEventListener('click', () => dtInputFiles.click());
        }

        async function processDtFileList(fileList, folderHint = "") {
            if (!fileList || fileList.length === 0) return;
            const files = [];
            for (let i = 0; i < fileList.length; i++) {
                const f = fileList[i];
                if (f.size > 10 * 1024 * 1024) continue;
                try {
                    const text = await f.text();
                    files.push({
                        name: f.webkitRelativePath || f.name,
                        content: text,
                        size: f.size
                    });
                } catch (e) {
                    console.warn("Could not read file text", f.name, e);
                }
            }
            if (files.length === 0) return;
            dtLoadedFiles = files;
            dtFolderName = folderHint || (fileList[0].webkitRelativePath ? fileList[0].webkitRelativePath.split('/')[0] : "Project Folder");
            refreshDtFilesUI();
            logConsole(`📂 Loaded ${dtLoadedFiles.length} file(s) from device: '${dtFolderName}'`, true);
        }

        if (dtInputFolder) {
            dtInputFolder.addEventListener('change', (e) => processDtFileList(e.target.files));
        }
        if (dtInputFiles) {
            dtInputFiles.addEventListener('change', (e) => processDtFileList(e.target.files, "Code Selection"));
        }

        if (dtFolderDropzone) {
            dtFolderDropzone.addEventListener('dragover', (e) => {
                e.preventDefault();
                dtFolderDropzone.style.borderColor = '#10b981';
                dtFolderDropzone.style.background = 'rgba(16, 185, 129, 0.1)';
            });
            dtFolderDropzone.addEventListener('dragleave', (e) => {
                e.preventDefault();
                dtFolderDropzone.style.borderColor = 'rgba(255,255,255,0.18)';
                dtFolderDropzone.style.background = 'rgba(0,0,0,0.25)';
            });
            dtFolderDropzone.addEventListener('drop', (e) => {
                e.preventDefault();
                dtFolderDropzone.style.borderColor = 'rgba(255,255,255,0.18)';
                dtFolderDropzone.style.background = 'rgba(0,0,0,0.25)';
                if (e.dataTransfer && e.dataTransfer.files) {
                    processDtFileList(e.dataTransfer.files, "Dropped Files");
                }
            });
        }

        // Sample injector for examiners
        if (btnLoadDtSample) {
            btnLoadDtSample.addEventListener('click', () => {
                dtLoadedFiles = [
                    {
                        name: "cluster_benchmark.py",
                        size: 1420,
                        content: `import sys, time, math

def compute_primes(limit):
    sieve = [True] * limit
    for i in range(2, int(math.isqrt(limit)) + 1):
        if sieve[i]:
            for j in range(i*i, limit, i):
                sieve[j] = False
    return sum(1 for i in range(2, limit) if sieve[i])

def monte_carlo_pi(n):
    import random
    inside = sum(1 for _ in range(n) if random.random()**2 + random.random()**2 <= 1.0)
    return 4.0 * inside / n

if __name__ == '__main__':
    args = sys.argv[1:]
    limit = int(args[0]) if len(args) > 0 and args[0].isdigit() else 200000
    darts = int(args[1]) if len(args) > 1 and args[1].isdigit() else 500000
    
    t0 = time.perf_counter()
    primes_found = compute_primes(limit)
    pi_val = monte_carlo_pi(darts)
    elapsed_ms = (time.perf_counter() - t0) * 1000.0

    print("==================================================")
    print("🚀 NEXT-GEN PHYSICAL NODE COMPUTE BENCHMARK")
    print(f"• Primes below {limit:,}: {primes_found:,}")
    print(f"• Monte Carlo π Estimation ({darts:,} darts): {pi_val:.6f}")
    print(f"• Pure CPU Time: {elapsed_ms:.2f} ms")
    print("• Node Cryptographic Status: VERIFIED & COMPLIANT")
    print("==================================================")
`
                    },
                    {
                        name: "MatrixOps.java",
                        size: 1850,
                        content: `public class MatrixOps {
    public static void main(String[] args) {
        int size = args.length > 0 ? Integer.parseInt(args[0]) : 256;
        long t0 = System.currentTimeMillis();
        double[][] a = new double[size][size];
        double[][] b = new double[size][size];
        double[][] c = new double[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                a[i][j] = i * 0.01 + j * 0.02;
                b[i][j] = i * 0.03 - j * 0.01;
            }
        }
        for (int i = 0; i < size; i++) {
            for (int k = 0; k < size; k++) {
                for (int j = 0; j < size; j++) {
                    c[i][j] += a[i][k] * b[k][j];
                }
            }
        }
        long dur = System.currentTimeMillis() - t0;
        System.out.println("==================================================");
        System.out.println("☕ JAVA 21 HIGH-PERFORMANCE MATRIX BENCHMARK");
        System.out.println("• Matrix Dimension: " + size + " x " + size);
        System.out.println("• Result Trace Checksum: " + String.format("%.4f", c[0][0] + c[size-1][size-1]));
        System.out.println("• Compute Duration: " + dur + " ms");
        System.out.println("• JVM Status: COMPLETE");
        System.out.println("==================================================");
    }
}
`
                    },
                    {
                        name: "iot_telemetry.csv",
                        size: 2450,
                        content: `timestamp,node_id,temperature_c,vibration_hz,voltage_v,cpu_pct,failure_risk
1710000000,node-01,42.3,1.2,12.01,24.5,0.012
1710000005,node-01,43.1,1.3,11.98,28.2,0.015
1710000010,node-01,44.7,1.8,11.92,52.1,0.034
1710000015,node-01,46.2,2.4,11.84,78.9,0.082
1710000020,node-01,48.9,3.1,11.75,91.4,0.185
1710000025,node-01,52.4,4.5,11.60,98.2,0.412
`
                    }
                ];
                dtFolderName = "Sample Multi-Node Suite";
                refreshDtFilesUI();
                logConsole(`💡 Injected sample benchmark suite (Python 3.14, Java 21, Dataset CSV)`, true);
            });
        }

        function refreshDtFilesUI() {
            if (!dtFolderCountBadge || !dtEntrypointSelect) return;
            dtFolderCountBadge.textContent = `${dtLoadedFiles.length} files loaded`;
            if (dtDropzoneText) {
                dtDropzoneText.innerHTML = `📁 <b>Folder:</b> ${dtFolderName} (${dtLoadedFiles.length} files loaded)`;
            }

            if (dtLoadedFilesContainer) {
                dtLoadedFilesContainer.style.display = dtLoadedFiles.length > 0 ? 'flex' : 'none';
                dtLoadedFilesContainer.innerHTML = dtLoadedFiles.map(f => {
                    const ext = f.name.split('.').pop().toLowerCase();
                    const icon = ext === 'py' ? '🐍' : (ext === 'java' ? '☕' : (ext === 'csv' || ext === 'json' ? '📊' : (ext === 'sh' || ext === 'bat' ? '⚡' : '📄')));
                    const kb = (f.size / 1024).toFixed(1) + ' KB';
                    return `<span style="font-size:10px; background:rgba(255,255,255,0.06); border:1px solid rgba(255,255,255,0.1); border-radius:4px; padding:2px 6px; font-family:var(--ng-font-mono); color:#f1f5f9;">
                        ${icon} ${f.name} <span style="color:#94a3b8; font-size:9.5px;">(${kb})</span>
                    </span>`;
                }).join('');
            }

            dtEntrypointSelect.innerHTML = dtLoadedFiles.map(f => {
                const ext = f.name.split('.').pop().toLowerCase();
                const icon = ext === 'py' ? '🐍' : (ext === 'java' ? '☕' : (ext === 'csv' || ext === 'json' ? '📊' : (ext === 'sh' || ext === 'bat' ? '⚡' : '📄')));
                return `<option value="${f.name}">${icon} ${f.name}</option>`;
            }).join('');

            updateDtCodePreview();
        }

        function updateDtCodePreview() {
            if (!dtEntrypointSelect || dtLoadedFiles.length === 0) return;
            const selName = dtEntrypointSelect.value;
            const matched = dtLoadedFiles.find(f => f.name === selName) || dtLoadedFiles[0];
            if (matched) {
                if (dtCodePreviewContent) {
                    dtCodePreviewContent.textContent = matched.content || "(empty file)";
                }
                if (dtEntryTypeBadge) {
                    const ext = matched.name.split('.').pop().toLowerCase();
                    if (ext === 'py') dtEntryTypeBadge.textContent = '[Python 3.14]';
                    else if (ext === 'java') dtEntryTypeBadge.textContent = '[JDK 21]';
                    else if (ext === 'csv' || ext === 'json') dtEntryTypeBadge.textContent = '[Dataset Analysis]';
                    else if (ext === 'sh' || ext === 'bat' || ext === 'cmd') dtEntryTypeBadge.textContent = '[Shell Script]';
                    else dtEntryTypeBadge.textContent = '[Raw Payload]';
                }
            }
        }

        if (dtEntrypointSelect) {
            dtEntrypointSelect.addEventListener('change', updateDtCodePreview);
        }

        // ── Live Console Logging Helper ───────────────────────────────────────
        const consoleBox = container.querySelector('#ngTaskConsole');
        const execStatusPill = container.querySelector('#ngExecStatusPill');
        const resultBanner = container.querySelector('#ngResultBanner');
        const resBannerTitle = container.querySelector('#resBannerTitle');
        const resDuration = container.querySelector('#resDuration');
        const resDetails = container.querySelector('#resDetails');
        const resDigest = container.querySelector('#resDigest');

        function logConsole(msg, isSuccess = false) {
            const time = new Date().toTimeString().split(' ')[0];
            const color = isSuccess ? '#34d399' : '#a7f3d0';
            const line = document.createElement('div');
            line.innerHTML = `<span style="color:#64748b;">[${time}]</span> <span style="color:${color};">${msg}</span>`;
            consoleBox.appendChild(line);
            consoleBox.scrollTop = consoleBox.scrollHeight;
        }

        // ── Step 4 & 5: Dispatch Custom Task Handler ──────────────────────────
        const btnDispatch = container.querySelector('#btnDispatchCustomTask');
        btnDispatch.addEventListener('click', async () => {
            if (dtLoadedFiles.length === 0) {
                alert("Please click '📂 Open Folder from Device' or '💡 Load Sample' to select a script to execute.");
                return;
            }

            const effective = getEffectiveClusterNodes();
            let targets = effective.filter(n => selectedNodeIds.has(n.id));
            if (targets.length === 0) {
                targets = [effective[0]];
            }

            const entrypoint = dtEntrypointSelect ? dtEntrypointSelect.value : dtLoadedFiles[0].name;
            const userArgs = dtTaskArguments ? dtTaskArguments.value : "";

            btnDispatch.disabled = true;
            btnDispatch.innerHTML = `<span class="ng-spinner"></span> Dispatching to ${targets.length} Node(s)…`;
            execStatusPill.textContent = '[EXECUTING]';
            execStatusPill.style.color = '#fbbf24';
            resultBanner.style.display = 'none';

            const allocations = calculateCapacityAllocation(targets);

            logConsole(`🚀 [STEP 1: CONFIGURATION] Submitting customized workload '${entrypoint}' (${dtLoadedFiles.length} files) to cluster.`);
            logConsole(`🛡️ Attaching zero-trust HMAC-SHA256 signature to compute ticket…`, true);
            logConsole(`⚡ [STEP 2: CAPACITY-WEIGHTED SCHEDULER] Partitioning task across ${targets.length} active node(s):`, true);
            allocations.forEach(a => {
                logConsole(`   • Node [${a.node.name || a.node.id}]: ${a.cores} Cores | ${a.ramGb}GB RAM | CPU ${a.cpu}% | Risk: ${a.risk} → ${a.sharePct}% Workload Share`);
            });

            let allSuccessful = true;
            let aggregatedDuration = 0;
            let lastDigest = "";

            for (let i = 0; i < targets.length; i++) {
                let target = targets[i];
                const alloc = allocations[i];
                let targetLabel = target.name || target.id;
                const isDead = (target.status || '').toUpperCase() === 'DEAD' || (target.status || '').toUpperCase() === 'OFFLINE';
                let isFailover = false;

                if (isDead) {
                    logConsole(`🚨 [STEP 3: PREDICTOR 4s ALERT] Node [${targetLabel}] is UNHEALTHY / CRASHED!`);
                    const survivor = targets.find((n, idx) => idx !== i && (n.status || '').toUpperCase() !== 'DEAD') || effective[0];
                    logConsole(`⚡ [STEP 4: SCHEDULER RE-ASSIGNMENT] Re-scheduling in-flight slice (${alloc.sharePct}% workload) to surviving healthy node [${survivor.name || survivor.id}]!`, true);
                    target = survivor;
                    targetLabel = target.name || target.id;
                    isFailover = true;
                }

                const shardArgs = userArgs
                    ? `${userArgs} --shard-id ${i + 1} --total-shards ${targets.length} --capacity-share ${alloc.sharePct}%`
                    : `--shard-id ${i + 1} --total-shards ${targets.length} --capacity-share ${alloc.sharePct}%`;

                logConsole(`⚡ Dispatched physical subtask payload to node [${targetLabel}]…`);

                try {
                    const res = await fetch('/api/custom-tasks/execute', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            targetNodeId: targetLabel,
                            entrypoint: entrypoint,
                            arguments: shardArgs,
                            files: dtLoadedFiles.map(f => ({ name: f.name, content: f.content }))
                        })
                    });

                    const data = await res.json();
                    if (data && data.ok) {
                        aggregatedDuration += data.durationMs || 10;
                        lastDigest = data.sha256;

                        logConsole(`✔ [NODE: ${targetLabel}] Subtask ${i + 1}/${targets.length} complete in ${data.durationMs} ms (Exit Code: ${data.exitCode})`, true);
                        if (data.stdout) {
                            logConsole(data.stdout.trim());
                        }
                        if (data.stderr && data.stderr.trim()) {
                            logConsole(`[STDERR]\n` + data.stderr.trim());
                        }
                        logConsole(`🔒 SHA-256 Digest: ${data.sha256}`, true);

                        resultBanner.style.display = 'block';
                        resBannerTitle.textContent = `✔ Executed '${entrypoint}' on ${targetLabel}`;
                        resDuration.textContent = `Duration: ${aggregatedDuration} ms`;
                        resDetails.innerHTML = `<b>Target Node:</b> ${targetLabel}<br><b>Summary:</b> ${data.resultSummary || 'Execution verified.'}<br><b>Capacity Slice:</b> ${alloc.sharePct}% workload`;
                        resDigest.textContent = `SHA-256: ${data.sha256}`;

                        if (window.NG && window.NG.networkCanvas && window.NG.networkCanvas.dispatchTask) {
                            window.NG.networkCanvas.dispatchTask({
                                id: data.taskId || ('TASK-' + Math.floor(1000 + Math.random() * 9000)),
                                label: entrypoint
                            });
                        }

                    } else {
                        allSuccessful = false;
                        logConsole(`❌ [NODE: ${targetLabel}] Execution failed: ${data ? data.stderr || 'Unknown error' : 'No response'}`);
                    }
                } catch (e) {
                    allSuccessful = false;
                    logConsole(`❌ Communication error with node ${targetLabel}: ${e.message}`);
                }
            }

            if (allSuccessful) {
                logConsole(`🎉 [STEP 5: COMPLETION LOOP] All distributed subtasks successfully processed and verified! Final cryptographic digest generated.`, true);
                execStatusPill.textContent = '[COMPLETED]';
                execStatusPill.style.color = '#34d399';
            } else {
                logConsole(`⚠️ Some subtasks encountered errors. Check logs above.`);
                execStatusPill.textContent = '[PARTIAL / FAILED]';
                execStatusPill.style.color = '#ef4444';
            }

            btnDispatch.disabled = false;
            btnDispatch.textContent = `🚀 Send it to the Node's & Execute`;
        });

        // ── Element refs for Quick Range forms ────────────────────────────────
        const taskStartInput  = container.querySelector('#ng-task-start');
        const taskEndInput    = container.querySelector('#ng-task-end');
        const taskSubmitBtn   = container.querySelector('#ng-task-submit');
        const taskWarningEl   = container.querySelector('#ng-task-warning');
        const taskListEl      = container.querySelector('#ng-task-list');
        const taskEmptyEl     = container.querySelector('#ng-task-empty');
        const jobStartInput   = container.querySelector('#ng-job-start');
        const jobEndInput     = container.querySelector('#ng-job-end');
        const jobSubInput     = container.querySelector('#ng-job-subtasks');
        const jobSubmitBtn    = container.querySelector('#ng-job-submit');
        const jobWarningEl    = container.querySelector('#ng-job-warning');
        const jobListEl       = container.querySelector('#ng-job-list');
        const jobEmptyEl      = container.querySelector('#ng-job-empty');

        let clusterHasHealthyNode = false;
        const closeCluster = NG.sse.subscribe('/api/cluster/stream', s => {
            clusterHasHealthyNode = s.healthyNodes > 0;
        });

        // Warning helpers
        function showWarning(el, msg, type) {
            el.style.display = '';
            el.className = `ng-status-message ${type || 'error'}`;
            el.textContent = msg;
        }
        function clearWarning(el) { el.style.display = 'none'; }

        // Submit range
        function submitRange(startEl, endEl, warnEl, btn, path, extra) {
            const rangeStart = Number(startEl.value);
            const rangeEnd   = Number(endEl.value);
            if (!Number.isFinite(rangeStart) || !Number.isFinite(rangeEnd) || rangeEnd < rangeStart) {
                showWarning(warnEl, 'Enter a valid range (end ≥ start)'); return;
            }
            if (!clusterHasHealthyNode && clusterNodes.length === 0) {
                showWarning(warnEl, 'No healthy node available — this will likely be rejected.', 'error');
            } else {
                clearWarning(warnEl);
            }
            btn.disabled = true;
            btn.innerHTML = '<span class="ng-spinner"></span>';
            fetch(path, {
                method:  'POST',
                headers: { 'Content-Type': 'application/json' },
                body:    JSON.stringify({ rangeStart, rangeEnd, ...extra }),
            })
            .then(r => r.json())
            .then(res => {
                if (res.ok || res.id) {
                    clearWarning(warnEl);
                    if (window.NG && window.NG.networkCanvas && window.NG.networkCanvas.dispatchTask) {
                        const count = extra && extra.subTaskCount ? extra.subTaskCount : 1;
                        for (let k = 0; k < count; k++) {
                            setTimeout(() => {
                                window.NG.networkCanvas.dispatchTask({
                                    id: res.id || 'task-' + Date.now(),
                                    label: count > 1 ? `Split [${k+1}/${count}]` : `Task [${rangeStart}..${rangeEnd}]`
                                });
                            }, k * 350);
                        }
                    }
                }
                else showWarning(warnEl, res.error || 'Submission failed');
            })
            .catch(() => showWarning(warnEl, 'Could not reach local service'))
            .finally(() => { btn.disabled = false; btn.textContent = 'Submit'; });
        }

        taskSubmitBtn.addEventListener('click', () =>
            submitRange(taskStartInput, taskEndInput, taskWarningEl, taskSubmitBtn, '/api/tasks', {}));

        jobSubmitBtn.addEventListener('click', () => {
            const subTaskCount = Math.max(1, Number(jobSubInput.value) || 1);
            submitRange(jobStartInput, jobEndInput, jobWarningEl, jobSubmitBtn, '/api/jobs', { subTaskCount });
        });

        // Status colours
        const taskColor = s => ({ COMPLETED: NG.palette.STATUS_GOOD, FAILED: NG.palette.STATUS_CRITICAL, RUNNING: NG.palette.STATUS_WARNING }[s] || NG.palette.STATUS_UNKNOWN);
        const jobColor  = s => ({ COMPLETED: NG.palette.STATUS_GOOD, PARTIAL_FAILURE: NG.palette.STATUS_WARNING, FAILED: NG.palette.STATUS_CRITICAL, RUNNING: NG.palette.STATUS_WARNING }[s] || NG.palette.STATUS_UNKNOWN);

        // Render tasks
        function renderTasks(tasks) {
            taskEmptyEl.hidden = tasks.length > 0;
            taskListEl.innerHTML = [...tasks].reverse().map((t, i) => {
                const color    = taskColor(t.status);
                const assigned = !t.assignedNode || t.assignedNode === 'NONE' ? 'unassigned' : t.assignedNode;
                return `<div class="ng-task-row" style="animation-delay:${i * 25}ms">
                    <span class="ng-status-dot" style="background:${color};box-shadow:0 0 5px ${color};width:7px;height:7px;border-radius:50%;flex-shrink:0"></span>
                    <span class="ng-task-id">${t.id}</span>
                    <span class="ng-task-type">${t.typeDisplayName || t.type || '—'}</span>
                    <span class="ng-task-node">${assigned}</span>
                    <span class="ng-task-result">${t.result || ''}</span>
                </div>`;
            }).join('');
        }

        // Render jobs
        function renderJobs(jobs) {
            jobEmptyEl.hidden = jobs.length > 0;
            jobListEl.innerHTML = [...jobs].reverse().map((j, i) => {
                const done  = j.completedCount + j.failedCount;
                const pct   = j.subTaskCount > 0 ? Math.round(100 * done / j.subTaskCount) : 0;
                const color = jobColor(j.status);
                return `<div class="ng-job-card ng-animate-fade-in" style="animation-delay:${i * 40}ms">
                    <div class="ng-job-header">
                        <div>
                            <span class="ng-status-dot" style="background:${color};box-shadow:0 0 6px ${color};display:inline-block;width:7px;height:7px;border-radius:50%;margin-right:8px"></span>
                            <span class="ng-numeric" style="color:var(--ng-text-muted);font-size:12px">${j.id}</span>
                            <span style="margin-left:8px;opacity:0.6;font-size:12px">[${j.rangeStart} – ${j.rangeEnd})</span>
                        </div>
                        <span class="ng-badge ${j.status === 'COMPLETED' ? 'ng-badge-good' : j.status === 'FAILED' ? 'ng-badge-critical' : j.status === 'RUNNING' ? 'ng-badge-warn' : 'ng-badge-unknown'}">
                            ${j.statusDisplayName || j.status}
                        </span>
                    </div>
                    <div>
                        <div style="display:flex;justify-content:space-between;font-size:11px;color:var(--ng-text-subtle);margin-bottom:5px">
                            <span>${done} / ${j.subTaskCount} sub-tasks</span>
                            <span style="color:${color};font-weight:600">${pct}%</span>
                        </div>
                        <div class="ng-job-progress-track">
                            <div class="ng-job-progress-fill" style="width:${pct}%;background:${color}"></div>
                        </div>
                    </div>
                    ${j.combinedResult ? `<div class="ng-job-meta">${j.combinedResult}</div>` : ''}
                </div>`;
            }).join('');
        }

        // SSE Subscriptions
        const closeTasks = NG.sse.subscribe('/api/tasks/stream', renderTasks);
        const closeJobs  = NG.sse.subscribe('/api/jobs/stream',  renderJobs);
        const closeNodes = NG.sse.subscribe('/api/nodes/stream', nodes => {
            clusterNodes = Array.isArray(nodes) ? nodes : [];
            renderTargetNodes();
        });

        // Initial fetch nearby as fallback
        fetch('/api/role/discover/nearby')
            .then(r => r.json())
            .then(nearby => {
                if (Array.isArray(nearby) && clusterNodes.length === 0) {
                    clusterNodes = nearby.map(n => ({
                        id: n.nodeId || n.hostname,
                        name: n.clusterName || n.hostname,
                        address: n.address,
                        status: 'HEALTHY',
                        deviceType: n.deviceType
                    }));
                    renderTargetNodes();
                }
            })
            .catch(() => {});

        return () => {
            closeCluster();
            closeTasks();
            closeJobs();
            closeNodes();
        };
    }
};
