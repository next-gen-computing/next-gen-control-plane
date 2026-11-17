/**
 * Next-Gen Control Plane — 3D Green Avionics & Boat Sonar Radar Scanner
 *
 * Tactical CRT Phosphor Green Radar Engine with:
 * - Rotating radar sweep beam (45 RPM) with phosphorescent trailing decay tail
 * - Concentric range rings (10m, 25m, 40m, 50m LAN range) & 360° compass azimuth graduations
 * - Authentic vector hardware silhouettes (MacBook Pro, Windows 11 Laptop, Linux Workstation)
 * - Real-time sonar echo ping wave animations when the sweep beam sweeps targets
 * - Strict Zero-Trust Security HUD: Quarantine states, permission gates, and admission controls
 */

(function(global) {
    'use strict';

    class RadarScanner {
        constructor(canvasId, options = {}) {
            this.canvas = typeof canvasId === 'string' ? document.getElementById(canvasId) : canvasId;
            if (!this.canvas) return;

            this.ctx = this.canvas.getContext('2d');
            this.options = Object.assign({
                rpm: 42,
                rangeMeters: 50,
                zeroTrustEnforced: true,
                isServerMode: true,
                onTargetSelect: null,
                onApprove: null,
                onReject: null,
                onConnect: null
            }, options);

            this.angle = 0;
            this.targets = [];
            this.hoveredTarget = null;
            this.selectedTarget = null;
            this.echoWaves = [];
            this.lastFrameTime = performance.now();
            this.running = false;
            this.animId = null;

            this.initCanvasSize();
            this.bindEvents();
            this.start();
        }

        initCanvasSize() {
            const rect = this.canvas.getBoundingClientRect();
            const dpr = window.devicePixelRatio || 1;
            const w = rect.width || this.canvas.width || 560;
            const h = rect.height || this.canvas.height || 560;

            this.canvas.width = w * dpr;
            this.canvas.height = h * dpr;
            this.ctx.scale(dpr, dpr);

            this.width = w;
            this.height = h;
            this.cx = w / 2;
            this.cy = h / 2;
            this.radius = Math.min(this.cx, this.cy) - 26;
        }

        bindEvents() {
            window.addEventListener('resize', () => {
                this.initCanvasSize();
            });

            this.canvas.addEventListener('mousemove', (e) => {
                const rect = this.canvas.getBoundingClientRect();
                const x = e.clientX - rect.left;
                const y = e.clientY - rect.top;
                this.hoveredTarget = this.findTargetAt(x, y);
                this.canvas.style.cursor = this.hoveredTarget ? 'pointer' : 'crosshair';
            });

            this.canvas.addEventListener('click', (e) => {
                const rect = this.canvas.getBoundingClientRect();
                const x = e.clientX - rect.left;
                const y = e.clientY - rect.top;
                const hit = this.findTargetAt(x, y);
                if (hit) {
                    this.selectedTarget = hit;
                    if (this.options.onTargetSelect) {
                        this.options.onTargetSelect(hit);
                    }
                }
            });
        }

        setTargets(newTargets) {
            // Map targets with polar coordinates if not already present
            this.targets = (newTargets || []).map((t, idx) => {
                const existing = this.targets.find(old => old.nodeId === t.nodeId);
                const angle = existing ? existing.angle : this.deriveAngle(t, idx, newTargets.length);
                const distanceRatio = existing ? existing.distanceRatio : this.deriveDistance(t);
                return Object.assign({}, t, {
                    angle,
                    distanceRatio,
                    lastSwept: existing ? existing.lastSwept : 0,
                    deviceType: this.detectDeviceType(t)
                });
            });
        }

        detectDeviceType(t) {
            if (t.deviceType) return t.deviceType.toLowerCase();
            const text = ((t.os || '') + ' ' + (t.hostname || '') + ' ' + (t.nodeId || '')).toLowerCase();
            if (text.includes('mac') || text.includes('darwin') || text.includes('apple')) return 'mac';
            if (text.includes('nux') || text.includes('nix') || text.includes('ubuntu') || text.includes('debian')) return 'linux';
            return 'windows';
        }

        deriveAngle(t, index, total) {
            if (t.isLocal || t.status === 'HOST_CONTROLLER') return 0; // Center or 000°
            // Distribute across sectors deterministically based on IP hash
            let hash = 0;
            const str = (t.ip || t.nodeId || '') + index;
            for (let i = 0; i < str.length; i++) {
                hash = ((hash << 5) - hash) + str.charCodeAt(i);
                hash |= 0;
            }
            const deg = Math.abs(hash % 360);
            return (deg * Math.PI) / 180;
        }

        deriveDistance(t) {
            if (t.isLocal || t.status === 'HOST_CONTROLLER') return 0.05;
            const lat = Number(t.latencyMs) || 5;
            if (lat <= 2) return 0.32;
            if (lat <= 10) return 0.52;
            if (lat <= 40) return 0.72;
            return 0.86;
        }

        findTargetAt(x, y) {
            for (let t of this.targets) {
                const pos = this.getTargetScreenPos(t);
                const dist = Math.hypot(x - pos.x, y - pos.y);
                if (dist <= 26) return t;
            }
            return null;
        }

        getTargetScreenPos(t) {
            if (t.isLocal || t.status === 'HOST_CONTROLLER') {
                return { x: this.cx, y: this.cy };
            }
            const r = this.radius * t.distanceRatio;
            const x = this.cx + r * Math.cos(t.angle - Math.PI / 2);
            const y = this.cy + r * Math.sin(t.angle - Math.PI / 2);
            return { x, y };
        }

        start() {
            this.running = true;
            const loop = (now) => {
                if (!this.running) return;
                const dt = (now - this.lastFrameTime) / 1000;
                this.lastFrameTime = now;
                this.update(dt);
                this.draw();
                this.animId = requestAnimationFrame(loop);
            };
            this.animId = requestAnimationFrame(loop);
        }

        stop() {
            this.running = false;
            if (this.animId) cancelAnimationFrame(this.animId);
        }

        update(dt) {
            const radPerSec = (this.options.rpm * 2 * Math.PI) / 60;
            const prevAngle = this.angle;
            this.angle = (this.angle + radPerSec * dt) % (2 * Math.PI);

            // Check sweep hits on targets
            for (let t of this.targets) {
                if (t.isLocal || t.status === 'HOST_CONTROLLER') continue;
                let swept = false;
                if (prevAngle < this.angle) {
                    swept = t.angle >= prevAngle && t.angle <= this.angle;
                } else {
                    swept = t.angle >= prevAngle || t.angle <= this.angle;
                }
                if (swept) {
                    t.lastSwept = performance.now();
                    const pos = this.getTargetScreenPos(t);
                    this.echoWaves.push({
                        x: pos.x,
                        y: pos.y,
                        radius: 8,
                        maxRadius: 36,
                        alpha: 1.0,
                        isApproved: t.isApproved
                    });
                }
            }

            // Update echo waves
            for (let i = this.echoWaves.length - 1; i >= 0; i--) {
                const wave = this.echoWaves[i];
                wave.radius += dt * 45;
                wave.alpha -= dt * 1.5;
                if (wave.alpha <= 0 || wave.radius >= wave.maxRadius) {
                    this.echoWaves.splice(i, 1);
                }
            }
        }

        draw() {
            const ctx = this.ctx;
            const cx = this.cx;
            const cy = this.cy;
            const r = this.radius;

            ctx.clearRect(0, 0, this.width, this.height);

            // 1. Radar Circular CRT Display Background
            const bgGrad = ctx.createRadialGradient(cx, cy, 0, cx, cy, r + 15);
            bgGrad.addColorStop(0, '#03170c');
            bgGrad.addColorStop(0.7, '#021208');
            bgGrad.addColorStop(0.96, '#010904');
            bgGrad.addColorStop(1, '#000402');

            ctx.beginPath();
            ctx.arc(cx, cy, r + 8, 0, 2 * Math.PI);
            ctx.fillStyle = bgGrad;
            ctx.fill();

            // 2. Bezel & Metal Housing Border
            ctx.lineWidth = 4;
            ctx.strokeStyle = 'rgba(16, 185, 129, 0.45)';
            ctx.stroke();

            ctx.lineWidth = 1;
            ctx.strokeStyle = 'rgba(16, 185, 129, 0.85)';
            ctx.beginPath();
            ctx.arc(cx, cy, r + 6, 0, 2 * Math.PI);
            ctx.stroke();

            // 3. Compass Azimuth Graduations (0° - 360°)
            this.drawCompassGraduations(ctx, cx, cy, r);

            // 4. Concentric Range Rings (10m, 25m, 40m, 50m)
            this.drawRangeRings(ctx, cx, cy, r);

            // 5. Crosshair Reticles
            this.drawCrosshairs(ctx, cx, cy, r);

            // 6. Rotating Phosphorescent Sweep Beam & Trailing Arc
            this.drawSweepBeam(ctx, cx, cy, r);

            // 7. Echo Waves (Sonar Ping Ripples)
            this.drawEchoWaves(ctx);

            // 8. Detected Hardware Targets (Mac, Windows, Linux)
            this.drawTargets(ctx);

            // 9. HUD Status Overlays & Corner Telemetry
            this.drawHudOverlay(ctx, cx, cy, r);
        }

        drawCompassGraduations(ctx, cx, cy, r) {
            ctx.save();
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.font = '10px "JetBrains Mono", monospace';

            for (let deg = 0; deg < 360; deg += 10) {
                const rad = (deg * Math.PI) / 180 - Math.PI / 2;
                const isMajor = deg % 30 === 0;
                const isCardinal = deg % 90 === 0;

                const innerR = isCardinal ? r - 12 : (isMajor ? r - 8 : r - 4);
                const x1 = cx + innerR * Math.cos(rad);
                const y1 = cy + innerR * Math.sin(rad);
                const x2 = cx + r * Math.cos(rad);
                const y2 = cy + r * Math.sin(rad);

                ctx.strokeStyle = isCardinal ? '#10b981' : (isMajor ? 'rgba(16, 185, 129, 0.65)' : 'rgba(16, 185, 129, 0.3)');
                ctx.lineWidth = isCardinal ? 1.8 : 1;
                ctx.beginPath();
                ctx.moveTo(x1, y1);
                ctx.lineTo(x2, y2);
                ctx.stroke();

                if (isCardinal) {
                    const labelR = r - 20;
                    const lx = cx + labelR * Math.cos(rad);
                    const ly = cy + labelR * Math.sin(rad);
                    const labels = { 0: '000° N', 90: '090° E', 180: '180° S', 270: '270° W' };
                    ctx.fillStyle = '#22c55e';
                    ctx.font = 'bold 9px "JetBrains Mono", monospace';
                    ctx.fillText(labels[deg], lx, ly);
                } else if (isMajor) {
                    const labelR = r - 16;
                    const lx = cx + labelR * Math.cos(rad);
                    const ly = cy + labelR * Math.sin(rad);
                    ctx.fillStyle = 'rgba(16, 185, 129, 0.6)';
                    ctx.font = '8px "JetBrains Mono", monospace';
                    ctx.fillText(String(deg).padStart(3, '0') + '°', lx, ly);
                }
            }
            ctx.restore();
        }

        drawRangeRings(ctx, cx, cy, r) {
            const rings = [
                { ratio: 0.25, label: '10m LAN' },
                { ratio: 0.50, label: '25m LAN' },
                { ratio: 0.75, label: '40m LAN' },
                { ratio: 1.00, label: '50m MAX' }
            ];

            ctx.save();
            ctx.font = '9px "JetBrains Mono", monospace';
            ctx.fillStyle = 'rgba(16, 185, 129, 0.65)';
            ctx.textAlign = 'left';

            for (let ring of rings) {
                const ringR = r * ring.ratio;
                ctx.beginPath();
                ctx.arc(cx, cy, ringR, 0, 2 * Math.PI);
                ctx.strokeStyle = 'rgba(16, 185, 129, 0.22)';
                ctx.lineWidth = ring.ratio === 1.0 ? 1.5 : 1;
                ctx.setLineDash(ring.ratio === 1.0 ? [] : [3, 4]);
                ctx.stroke();

                // Range label on 45-degree radial
                if (ring.ratio < 1.0) {
                    const rad = Math.PI / 4;
                    const lx = cx + ringR * Math.cos(rad) + 4;
                    const ly = cy + ringR * Math.sin(rad) - 4;
                    ctx.fillText(ring.label, lx, ly);
                }
            }
            ctx.restore();
        }

        drawCrosshairs(ctx, cx, cy, r) {
            ctx.save();
            ctx.strokeStyle = 'rgba(16, 185, 129, 0.35)';
            ctx.lineWidth = 1;
            ctx.setLineDash([4, 4]);

            // Horizontal crosshair
            ctx.beginPath();
            ctx.moveTo(cx - r, cy);
            ctx.lineTo(cx + r, cy);
            ctx.stroke();

            // Vertical crosshair
            ctx.beginPath();
            ctx.moveTo(cx, cy - r);
            ctx.lineTo(cx, cy + r);
            ctx.stroke();

            // Center Host Radar Origin Station
            ctx.setLineDash([]);
            ctx.strokeStyle = '#22c55e';
            ctx.lineWidth = 2;
            ctx.beginPath();
            ctx.arc(cx, cy, 6, 0, 2 * Math.PI);
            ctx.stroke();

            ctx.fillStyle = '#10b981';
            ctx.beginPath();
            ctx.arc(cx, cy, 3, 0, 2 * Math.PI);
            ctx.fill();

            ctx.restore();
        }

        drawSweepBeam(ctx, cx, cy, r) {
            ctx.save();
            const beamAngle = this.angle - Math.PI / 2;

            // 1. Phosphorescent Decay Tail (fading sector arc behind the beam)
            const tailArc = (55 * Math.PI) / 180; // 55 degree phosphor tail
            const slices = 25;
            for (let i = 0; i < slices; i++) {
                const a1 = beamAngle - (i + 1) * (tailArc / slices);
                const a2 = beamAngle - i * (tailArc / slices);
                const alpha = (1 - (i / slices)) * 0.28;

                ctx.beginPath();
                ctx.moveTo(cx, cy);
                ctx.arc(cx, cy, r, a1, a2, false);
                ctx.closePath();
                ctx.fillStyle = `rgba(16, 185, 129, ${alpha})`;
                ctx.fill();
            }

            // 2. High-Intensity Sweep Beam Line
            const bx = cx + r * Math.cos(beamAngle);
            const by = cy + r * Math.sin(beamAngle);

            const beamGrad = ctx.createLinearGradient(cx, cy, bx, by);
            beamGrad.addColorStop(0, 'rgba(74, 222, 128, 0.95)');
            beamGrad.addColorStop(0.7, 'rgba(34, 197, 94, 0.9)');
            beamGrad.addColorStop(1, 'rgba(16, 185, 129, 0.3)');

            ctx.strokeStyle = beamGrad;
            ctx.lineWidth = 2.2;
            ctx.beginPath();
            ctx.moveTo(cx, cy);
            ctx.lineTo(bx, by);
            ctx.stroke();

            // Glow tip
            ctx.fillStyle = '#86efac';
            ctx.beginPath();
            ctx.arc(bx, by, 3, 0, 2 * Math.PI);
            ctx.fill();

            ctx.restore();
        }

        drawEchoWaves(ctx) {
            ctx.save();
            for (let wave of this.echoWaves) {
                ctx.beginPath();
                ctx.arc(wave.x, wave.y, wave.radius, 0, 2 * Math.PI);
                ctx.strokeStyle = wave.isApproved
                    ? `rgba(34, 197, 94, ${wave.alpha})`
                    : `rgba(245, 158, 11, ${wave.alpha})`;
                ctx.lineWidth = 1.8;
                ctx.stroke();
            }
            ctx.restore();
        }

        drawTargets(ctx) {
            const now = performance.now();

            for (let t of this.targets) {
                const pos = this.getTargetScreenPos(t);
                const isHovered = this.hoveredTarget && this.hoveredTarget.nodeId === t.nodeId;
                const isSelected = this.selectedTarget && this.selectedTarget.nodeId === t.nodeId;

                // Swept flash decay
                const timeSinceSwept = now - (t.lastSwept || 0);
                const isLit = timeSinceSwept < 1200;
                const litAlpha = isLit ? Math.max(0, 1 - (timeSinceSwept / 1200)) : 0;

                ctx.save();
                ctx.translate(pos.x, pos.y);

                // Target blip halo
                if (isLit) {
                    ctx.beginPath();
                    ctx.arc(0, 0, 18 + litAlpha * 6, 0, 2 * Math.PI);
                    ctx.fillStyle = t.isApproved
                        ? `rgba(34, 197, 94, ${litAlpha * 0.35})`
                        : `rgba(245, 158, 11, ${litAlpha * 0.4})`;
                    ctx.fill();
                }

                // Draw Hardware Silhouette (MacBook, Windows Laptop, Linux Workstation)
                this.drawHardwareSilhouette(ctx, t.deviceType, t.isApproved, isLit || isHovered || isSelected);

                // Target Reticle & Brackets
                if (isHovered || isSelected || isLit) {
                    this.drawTargetBrackets(ctx, t.isApproved);
                }

                // Floating Tactical HUD Tag
                this.drawTargetHudTag(ctx, t, isHovered || isSelected);

                ctx.restore();
            }
        }

        drawHardwareSilhouette(ctx, type, isApproved, isHighlighted) {
            ctx.save();
            const strokeColor = isApproved ? (isHighlighted ? '#4ade80' : '#10b981') : (isHighlighted ? '#fbbf24' : '#d97706');
            const fillColor = isApproved ? 'rgba(16, 185, 129, 0.25)' : 'rgba(217, 119, 6, 0.25)';

            if (type === 'mac') {
                // ── MacBook Pro Silhouette ──
                // Screen Lid
                ctx.fillStyle = fillColor;
                ctx.strokeStyle = strokeColor;
                ctx.lineWidth = 1.4;
                ctx.beginPath();
                ctx.roundRect(-12, -14, 24, 15, 2.5);
                ctx.fill();
                ctx.stroke();

                // Apple Logo on Lid
                ctx.fillStyle = strokeColor;
                ctx.beginPath();
                ctx.arc(0, -7, 2.2, 0, 2 * Math.PI);
                ctx.fill();

                // Base / Keyboard Deck
                ctx.beginPath();
                ctx.moveTo(-16, 2);
                ctx.lineTo(16, 2);
                ctx.lineTo(13, 5);
                ctx.lineTo(-13, 5);
                ctx.closePath();
                ctx.fill();
                ctx.stroke();

                // Trackpad notch
                ctx.strokeStyle = strokeColor;
                ctx.beginPath();
                ctx.moveTo(-3, 2);
                ctx.lineTo(3, 2);
                ctx.stroke();

            } else if (type === 'linux') {
                // ── Linux Workstation Silhouette ──
                // Rugged Display
                ctx.fillStyle = fillColor;
                ctx.strokeStyle = strokeColor;
                ctx.lineWidth = 1.5;
                ctx.beginPath();
                ctx.roundRect(-13, -15, 26, 16, 1.5);
                ctx.fill();
                ctx.stroke();

                // Terminal Prompt `>_`
                ctx.strokeStyle = strokeColor;
                ctx.lineWidth = 1.2;
                ctx.beginPath();
                ctx.moveTo(-6, -9);
                ctx.lineTo(-2, -6);
                ctx.lineTo(-6, -3);
                ctx.stroke();

                ctx.beginPath();
                ctx.moveTo(0, -3);
                ctx.lineTo(4, -3);
                ctx.stroke();

                // Rugged Heavy Base
                ctx.beginPath();
                ctx.moveTo(-17, 2);
                ctx.lineTo(17, 2);
                ctx.lineTo(15, 6);
                ctx.lineTo(-15, 6);
                ctx.closePath();
                ctx.fill();
                ctx.stroke();

            } else {
                // ── Windows 11 Laptop Silhouette ──
                // Slim 16:10 Screen Lid
                ctx.fillStyle = fillColor;
                ctx.strokeStyle = strokeColor;
                ctx.lineWidth = 1.4;
                ctx.beginPath();
                ctx.roundRect(-13, -14, 26, 15, 2);
                ctx.fill();
                ctx.stroke();

                // Windows 11 4-Tile Logo
                ctx.fillStyle = strokeColor;
                ctx.fillRect(-3, -9, 2.5, 2.5);
                ctx.fillRect(0.5, -9, 2.5, 2.5);
                ctx.fillRect(-3, -5.5, 2.5, 2.5);
                ctx.fillRect(0.5, -5.5, 2.5, 2.5);

                // Keyboard Deck & Chamfered Edge
                ctx.beginPath();
                ctx.moveTo(-16, 2);
                ctx.lineTo(16, 2);
                ctx.lineTo(14, 5);
                ctx.lineTo(-14, 5);
                ctx.closePath();
                ctx.fill();
                ctx.stroke();

                // Trackpad
                ctx.strokeStyle = strokeColor;
                ctx.lineWidth = 1;
                ctx.strokeRect(-4, 2.5, 8, 2);
            }

            ctx.restore();
        }

        drawTargetBrackets(ctx, isApproved) {
            ctx.save();
            ctx.strokeStyle = isApproved ? '#22c55e' : '#f59e0b';
            ctx.lineWidth = 1.4;
            const b = 18;
            const len = 5;

            // Top-left
            ctx.beginPath();
            ctx.moveTo(-b, -b + len); ctx.lineTo(-b, -b); ctx.lineTo(-b + len, -b);
            ctx.stroke();

            // Top-right
            ctx.beginPath();
            ctx.moveTo(b - len, -b); ctx.lineTo(b, -b); ctx.lineTo(b, -b + len);
            ctx.stroke();

            // Bottom-left
            ctx.beginPath();
            ctx.moveTo(-b, b - len); ctx.lineTo(-b, b); ctx.lineTo(-b + len, b);
            ctx.stroke();

            // Bottom-right
            ctx.beginPath();
            ctx.moveTo(b - len, b); ctx.lineTo(b, b); ctx.lineTo(b, b - len);
            ctx.stroke();

            ctx.restore();
        }

        drawTargetHudTag(ctx, t, isDetailed) {
            ctx.save();
            const isApproved = t.isApproved;
            const textColor = isApproved ? '#22c55e' : '#fbbf24';
            const badgeBg = isApproved ? 'rgba(16, 185, 129, 0.18)' : 'rgba(245, 158, 11, 0.2)';
            const borderCol = isApproved ? 'rgba(34, 197, 94, 0.4)' : 'rgba(245, 158, 11, 0.5)';

            // Leader line to floating tag
            ctx.strokeStyle = borderCol;
            ctx.lineWidth = 1;
            ctx.setLineDash([2, 2]);
            ctx.beginPath();
            ctx.moveTo(18, 0);
            ctx.lineTo(28, -14);
            ctx.lineTo(60, -14);
            ctx.stroke();
            ctx.setLineDash([]);

            // Hostname & hardware tag
            const name = t.hostname || t.nodeId || 'Worker Node';
            const hardwareLabel = t.deviceType === 'mac' ? 'MacBook' : (t.deviceType === 'linux' ? 'Linux Box' : 'Windows PC');
            const latencyStr = (t.latencyMs ? t.latencyMs + 'ms' : 'LAN');

            ctx.font = 'bold 10px "JetBrains Mono", monospace';
            ctx.textAlign = 'left';
            ctx.textBaseline = 'bottom';

            // Background tag box
            const tagW = Math.max(88, name.length * 6.5 + 16);
            ctx.fillStyle = 'rgba(7, 13, 10, 0.88)';
            ctx.strokeStyle = borderCol;
            ctx.beginPath();
            ctx.roundRect(28, -26, tagW, 24, 4);
            ctx.fill();
            ctx.stroke();

            ctx.fillStyle = textColor;
            ctx.fillText(name, 34, -14);

            ctx.font = '8px "JetBrains Mono", monospace';
            ctx.fillStyle = '#94a3b8';
            ctx.fillText(`${hardwareLabel} • ${latencyStr}`, 34, -5);

            // If selected or hovered, show security status badge
            if (isDetailed) {
                const secBadge = isApproved ? 'VERIFIED' : 'QUARANTINED';
                ctx.fillStyle = isApproved ? '#10b981' : '#f59e0b';
                ctx.fillText(`[ ${secBadge} ]`, 34, 8);
            }

            ctx.restore();
        }

        drawHudOverlay(ctx, cx, cy, r) {
            ctx.save();
            ctx.font = '10px "JetBrains Mono", monospace';
            ctx.fillStyle = '#10b981';

            // Top-Left: System Telemetry
            ctx.textAlign = 'left';
            ctx.textBaseline = 'top';
            ctx.fillText('AVIONICS SONAR // UDP 50055', 16, 16);
            ctx.fillStyle = '#64748b';
            ctx.font = '9px "JetBrains Mono", monospace';
            ctx.fillText(`BEARING: ${String(Math.round((this.angle * 180) / Math.PI)).padStart(3, '0')}° • SWEEP: 45 RPM`, 16, 30);

            // Top-Right: Zero-Trust Security Gate
            ctx.textAlign = 'right';
            ctx.fillStyle = this.options.zeroTrustEnforced ? '#22c55e' : '#f59e0b';
            ctx.font = 'bold 9px "JetBrains Mono", monospace';
            ctx.fillText('🛡️ ZERO-TRUST GATE: ACTIVE', this.width - 16, 16);
            ctx.fillStyle = '#94a3b8';
            ctx.font = '8px "JetBrains Mono", monospace';
            ctx.fillText('NO ANONYMOUS ACCESS', this.width - 16, 30);

            // Bottom-Left: Live Range and Status
            ctx.textAlign = 'left';
            ctx.textBaseline = 'bottom';
            const pulsing = (Math.sin(performance.now() / 250) + 1) / 2;
            ctx.fillStyle = `rgba(34, 197, 94, ${0.4 + pulsing * 0.6})`;
            ctx.beginPath();
            ctx.arc(20, this.height - 20, 4, 0, 2 * Math.PI);
            ctx.fill();

            ctx.fillStyle = '#10b981';
            ctx.font = '9px "JetBrains Mono", monospace';
            ctx.fillText('AIRSPACE SCANNING // LAN QUICK SHARE', 30, this.height - 16);

            // Bottom-Right: Target Count
            ctx.textAlign = 'right';
            const total = this.targets.length;
            const approved = this.targets.filter(t => t.isApproved).length;
            const pending = total - approved;
            ctx.fillStyle = '#cbd5e1';
            ctx.fillText(`TARGETS: ${approved} VERIFIED • ${pending} QUARANTINED`, this.width - 16, this.height - 16);

            ctx.restore();
        }
    }

    global.RadarScanner = RadarScanner;

})(typeof window !== 'undefined' ? window : this);

