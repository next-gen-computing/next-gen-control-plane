window.NG = window.NG || {};
NG.views = NG.views || {};

NG.views.animals = {
    mount(container) {
        container.innerHTML = `
            <div style="display:flex; flex-direction:column; height:100%; gap:12px; padding:0 4px 12px;">
                <div style="display:flex; justify-content:space-between; align-items:center;">
                    <div>
                        <h2 style="margin:0; font-size:18px; font-weight:600; color:var(--ng-text, #e8ebf7);">Glyph Animals — Ambient Canvas</h2>
                        <p style="margin:2px 0 0; font-size:12px; color:var(--ng-text-secondary, rgba(232,235,247,0.5));">
                            Procedural ASCII flocking simulation. Click canvas to scatter animals or drop food.
                        </p>
                    </div>
                    <div style="display:flex; gap:8px;">
                        <button id="ng-animals-fullscreen" class="ng-button ng-button-secondary" style="height:30px; font-size:12px; border-radius:999px;">
                            Toggle Maximize
                        </button>
                    </div>
                </div>
                <div id="ng-animals-frame-container" style="flex:1; border-radius:16px; overflow:hidden; border:1px solid rgba(255,255,255,0.12); background:#06070c; position:relative;">
                    <iframe src="glyph-animals.html" style="width:100%; height:100%; border:none; display:block;" title="Glyph Animals"></iframe>
                </div>
            </div>`;

        const frameContainer = container.querySelector('#ng-animals-frame-container');
        const fsBtn = container.querySelector('#ng-animals-fullscreen');
        let isMax = false;

        fsBtn.addEventListener('click', () => {
            isMax = !isMax;
            if (isMax) {
                frameContainer.style.position = 'fixed';
                frameContainer.style.inset = '0';
                frameContainer.style.zIndex = '9999';
                frameContainer.style.borderRadius = '0';
                fsBtn.textContent = 'Exit Maximize';
                fsBtn.style.position = 'fixed';
                fsBtn.style.top = '16px';
                fsBtn.style.right = '120px';
                fsBtn.style.zIndex = '10000';
            } else {
                frameContainer.style.position = 'relative';
                frameContainer.style.inset = 'auto';
                frameContainer.style.zIndex = '1';
                frameContainer.style.borderRadius = '16px';
                fsBtn.textContent = 'Toggle Maximize';
                fsBtn.style.position = 'static';
                fsBtn.style.zIndex = 'auto';
            }
        });

        return () => {};
    }
};

