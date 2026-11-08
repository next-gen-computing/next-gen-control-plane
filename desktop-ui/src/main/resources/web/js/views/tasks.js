/**
 * tasks.js — Premium Tasks/Jobs screen. Submit distributed work,
 * watch live progress and job cards with animated progress bars.
 */
window.NG = window.NG || {};
NG.views = NG.views || {};

NG.views.tasks = {
    mount(container) {
        container.innerHTML = `
            <div class="ng-page">
                <div class="ng-page-header ng-animate-fade-in">
                    <div class="ng-page-header-left">
                        <h1 class="ng-page-title">Tasks &amp; Jobs</h1>
                        <p class="ng-page-subtitle">Submit distributed work — the control plane chooses the compute node(s)</p>
                    </div>
                </div>

                <div class="ng-page-scroll">
                    <div class="ng-page-content">

                        <!-- Submission panel -->
                        <div style="display:grid;grid-template-columns:1fr 1fr;gap:16px" class="ng-animate-fade-in ng-stagger-1">

                            <!-- Single task -->
                            <div class="ng-card ng-card-hero">
                                <div style="display:flex;align-items:center;gap:8px;margin-bottom:4px">
                                    <span style="font-size:20px">⚡</span>
                                    <h2 style="margin:0;font-size:15px;font-weight:700">Single Task</h2>
                                </div>
                                <p style="margin:0 0 12px;font-size:12px;color:var(--ng-text-muted)">
                                    Count primes in a range — dispatched to one node.
                                </p>
                                <div style="display:grid;grid-template-columns:1fr 1fr auto;gap:8px;align-items:end">
                                    <div class="ng-field">
                                        <label for="ng-task-start">Range start</label>
                                        <input class="ng-input ng-numeric" id="ng-task-start" type="number" placeholder="0">
                                    </div>
                                    <div class="ng-field">
                                        <label for="ng-task-end">Range end</label>
                                        <input class="ng-input ng-numeric" id="ng-task-end" type="number" placeholder="100000">
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
                                    <h2 style="margin:0;font-size:15px;font-weight:700">Distributed Job</h2>
                                </div>
                                <p style="margin:0 0 12px;font-size:12px;color:var(--ng-text-muted)">
                                    Split across N sub-tasks dispatched to multiple nodes.
                                </p>
                                <div style="display:grid;grid-template-columns:1fr 1fr 90px auto;gap:8px;align-items:end">
                                    <div class="ng-field">
                                        <label for="ng-job-start">Start</label>
                                        <input class="ng-input ng-numeric" id="ng-job-start" type="number" placeholder="0">
                                    </div>
                                    <div class="ng-field">
                                        <label for="ng-job-end">End</label>
                                        <input class="ng-input ng-numeric" id="ng-job-end" type="number" placeholder="1000000">
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
                        <div class="ng-section-heading ng-animate-fade-in ng-stagger-2">
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

        // ── Element refs ─────────────────────────────────────────────────────
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

        // ── Warning helpers ──────────────────────────────────────────────────
        function showWarning(el, msg, type) {
            el.style.display = '';
            el.className = `ng-status-message ${type || 'error'}`;
            el.textContent = msg;
        }
        function clearWarning(el) { el.style.display = 'none'; }

        // ── Submit range ─────────────────────────────────────────────────────
        function submitRange(startEl, endEl, warnEl, btn, path, extra) {
            const rangeStart = Number(startEl.value);
            const rangeEnd   = Number(endEl.value);
            if (!Number.isFinite(rangeStart) || !Number.isFinite(rangeEnd) || rangeEnd < rangeStart) {
                showWarning(warnEl, 'Enter a valid range (end ≥ start)'); return;
            }
            if (!clusterHasHealthyNode) {
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
                if (res.ok || res.id) { clearWarning(warnEl); startEl.value = ''; endEl.value = ''; }
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

        // ── Status colours ───────────────────────────────────────────────────
        const taskColor = s => ({ COMPLETED: NG.palette.STATUS_GOOD, FAILED: NG.palette.STATUS_CRITICAL, RUNNING: NG.palette.STATUS_WARNING }[s] || NG.palette.STATUS_UNKNOWN);
        const jobColor  = s => ({ COMPLETED: NG.palette.STATUS_GOOD, PARTIAL_FAILURE: NG.palette.STATUS_WARNING, FAILED: NG.palette.STATUS_CRITICAL, RUNNING: NG.palette.STATUS_WARNING }[s] || NG.palette.STATUS_UNKNOWN);

        // ── Render tasks ─────────────────────────────────────────────────────
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

        // ── Render jobs ───────────────────────────────────────────────────────
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

        const closeTasks = NG.sse.subscribe('/api/tasks/stream', renderTasks);
        const closeJobs  = NG.sse.subscribe('/api/jobs/stream',  renderJobs);

        return () => { closeCluster(); closeTasks(); closeJobs(); };
    }
};
