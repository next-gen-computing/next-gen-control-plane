// A submission form plus a live list of real outcomes — a task/job the control plane could not
// place, or that failed after real execution, is shown as failed with its reason, never as silently
// completed. Both tasks (one sub-task) and jobs (N sub-tasks, reduced server-side) submit a
// prime-count-range over [rangeStart, rangeEnd) — the one real, checkable workload this cluster runs.
window.NG = window.NG || {};
NG.views = NG.views || {};

NG.views.tasks = {
    mount(container) {
        container.innerHTML = `
            <div class="ng-page">
                <h1 class="ng-page-title">Tasks</h1>
                <p class="ng-page-subtitle">Submit real, distributed work; the control plane chooses the node(s)</p>
                <div class="ng-page-scroll">
                    <div class="ng-page-content">
                        <div class="ng-card">
                            <h2 class="ng-section-heading" style="margin-top:0">Single task</h2>
                            <div class="ng-row">
                                <input class="ng-input" id="ng-task-start" type="number" placeholder="Range start" style="width:140px">
                                <input class="ng-input" id="ng-task-end" type="number" placeholder="Range end" style="width:140px">
                                <button class="ng-button ng-button-primary" id="ng-task-submit">Submit task</button>
                            </div>
                            <div id="ng-task-warning" class="ng-status-message" style="display:none"></div>
                        </div>

                        <div class="ng-card">
                            <h2 class="ng-section-heading" style="margin-top:0">Job (split across N sub-tasks)</h2>
                            <div class="ng-row">
                                <input class="ng-input" id="ng-job-start" type="number" placeholder="Range start" style="width:140px">
                                <input class="ng-input" id="ng-job-end" type="number" placeholder="Range end" style="width:140px">
                                <input class="ng-input" id="ng-job-subtasks" type="number" placeholder="Sub-tasks" min="1" value="4" style="width:110px">
                                <button class="ng-button ng-button-primary" id="ng-job-submit">Submit job</button>
                            </div>
                            <div id="ng-job-warning" class="ng-status-message" style="display:none"></div>
                        </div>

                        <h2 class="ng-section-heading">Jobs</h2>
                        <div id="ng-job-list"></div>
                        <div class="ng-empty-state" id="ng-job-empty">
                            <div class="ng-empty-state-body">No jobs submitted yet</div>
                        </div>

                        <h2 class="ng-section-heading">Submitted tasks</h2>
                        <div id="ng-task-list"></div>
                        <div class="ng-empty-state" id="ng-task-empty">
                            <div class="ng-empty-state-body">No tasks submitted yet</div>
                        </div>
                    </div>
                </div>
            </div>`;

        const taskStartInput = container.querySelector('#ng-task-start');
        const taskEndInput = container.querySelector('#ng-task-end');
        const taskSubmitButton = container.querySelector('#ng-task-submit');
        const taskWarningEl = container.querySelector('#ng-task-warning');
        const taskListEl = container.querySelector('#ng-task-list');
        const taskEmptyEl = container.querySelector('#ng-task-empty');

        const jobStartInput = container.querySelector('#ng-job-start');
        const jobEndInput = container.querySelector('#ng-job-end');
        const jobSubTasksInput = container.querySelector('#ng-job-subtasks');
        const jobSubmitButton = container.querySelector('#ng-job-submit');
        const jobWarningEl = container.querySelector('#ng-job-warning');
        const jobListEl = container.querySelector('#ng-job-list');
        const jobEmptyEl = container.querySelector('#ng-job-empty');

        let clusterHasHealthyNode = false;
        const closeCluster = NG.sse.subscribe('/api/cluster/stream', (summary) => {
            clusterHasHealthyNode = summary.healthyNodes > 0;
        });

        function showWarning(el, message) {
            el.style.display = '';
            el.className = 'ng-status-message error';
            el.textContent = message;
        }

        function submitRange(startInput, endInput, warningEl, path, extraBody, onDone) {
            const rangeStart = Number(startInput.value);
            const rangeEnd = Number(endInput.value);
            if (!Number.isFinite(rangeStart) || !Number.isFinite(rangeEnd) || rangeEnd < rangeStart) {
                showWarning(warningEl, 'Enter a valid range (end ≥ start)');
                return;
            }
            if (!clusterHasHealthyNode) {
                showWarning(warningEl, 'No healthy node is currently available; this will be rejected.');
            } else {
                warningEl.style.display = 'none';
            }

            fetch(path, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ rangeStart, rangeEnd, ...extraBody })
            }).then(onDone).catch(() => showWarning(warningEl, 'Could not reach the local service'));
        }

        taskSubmitButton.addEventListener('click', () => {
            submitRange(taskStartInput, taskEndInput, taskWarningEl, '/api/tasks', {});
        });

        jobSubmitButton.addEventListener('click', () => {
            const subTaskCount = Math.max(1, Number(jobSubTasksInput.value) || 1);
            submitRange(jobStartInput, jobEndInput, jobWarningEl, '/api/jobs', { subTaskCount });
        });

        const taskStatusColor = (status) => ({
            COMPLETED: NG.palette.STATUS_GOOD,
            FAILED: NG.palette.STATUS_CRITICAL,
            RUNNING: NG.palette.STATUS_WARNING
        }[status] || NG.palette.STATUS_UNKNOWN);

        function renderTasks(tasks) {
            taskEmptyEl.hidden = tasks.length > 0;
            // Newest first — the server returns submission order, oldest first.
            const ordered = [...tasks].reverse();
            taskListEl.innerHTML = ordered.map((t) => {
                const assigned = !t.assignedNode || t.assignedNode === 'NONE' ? 'unassigned' : 'on ' + t.assignedNode;
                return `<div class="ng-task-row">
                    <span class="ng-status-dot" style="width:8px;height:8px;border-radius:50%;background:${taskStatusColor(t.status)}"></span>
                    <span class="ng-task-id">${t.id}</span>
                    <span class="ng-task-type">${t.typeDisplayName}</span>
                    <span>${assigned}</span>
                    <span class="ng-task-result">${t.result || ''}</span>
                </div>`;
            }).join('');
        }

        const jobStatusColor = (status) => ({
            COMPLETED: NG.palette.STATUS_GOOD,
            PARTIAL_FAILURE: NG.palette.STATUS_WARNING,
            FAILED: NG.palette.STATUS_CRITICAL,
            RUNNING: NG.palette.STATUS_WARNING
        }[status] || NG.palette.STATUS_UNKNOWN);

        function renderJobs(jobs) {
            jobEmptyEl.hidden = jobs.length > 0;
            const ordered = [...jobs].reverse();
            jobListEl.innerHTML = ordered.map((j) => {
                const doneCount = j.completedCount + j.failedCount;
                const pct = j.subTaskCount > 0 ? Math.round(100 * doneCount / j.subTaskCount) : 0;
                return `<div class="ng-card" style="padding:12px 16px;margin-bottom:8px">
                    <div class="ng-row" style="justify-content:space-between">
                        <span>
                            <span class="ng-status-dot" style="width:8px;height:8px;border-radius:50%;background:${jobStatusColor(j.status)};display:inline-block;margin-right:8px"></span>
                            <span class="ng-task-id">${j.id}</span>
                            <span style="margin-left:8px;opacity:0.7">[${j.rangeStart}, ${j.rangeEnd})</span>
                        </span>
                        <span>${j.statusDisplayName} — ${doneCount}/${j.subTaskCount} sub-tasks</span>
                    </div>
                    <div style="height:6px;border-radius:3px;background:var(--ng-border,#333);margin-top:8px;overflow:hidden">
                        <div style="height:100%;width:${pct}%;background:${jobStatusColor(j.status)};transition:width 0.3s"></div>
                    </div>
                    <div class="ng-task-result" style="margin-top:8px">${j.combinedResult || ''}</div>
                </div>`;
            }).join('');
        }

        const closeTasks = NG.sse.subscribe('/api/tasks/stream', renderTasks);
        const closeJobs = NG.sse.subscribe('/api/jobs/stream', renderJobs);

        return () => {
            closeCluster();
            closeTasks();
            closeJobs();
        };
    }
};
