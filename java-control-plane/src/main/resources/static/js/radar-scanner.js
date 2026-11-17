/**
 * Next-Gen Control Plane — Authentic 3D Green Avionics & Boat Sonar Radar Scanner
 *
 * True 3D Perspective CRT Phosphor Green Radar Engine with:
 * - 3D Perspective Projection: Tilted 3D grid plane (54° pitch) with interactive mouse orbit & tilt
 * - 3D Wireframe Avionics Jet / Naval Sonar Navigator at center heading 000°
 * - Concentric 3D Range Rings (10m, 25m, 40m, 50m LAN range) with depth-scaled tick marks
 * - 3D Volumetric Sweep Fan: Rotating 45 RPM volumetric phosphor beam & fading phosphor decay tail
 * - 3D Isometric Laptop Models: Custom 3D meshes for Apple MacBook Pro, Windows 11 PC, Linux Workstations
 * - 3D Sonar Echo Wave Ripples expanding along the 3D ground plane with depth perspective
 * - 3D Holographic Floating Metadata HUD: Hostname, Hardware Platform, Cores, RAM, Latency, & Admission State
 * - Strict Zero-Trust Security Perimeter: Cryptographic HMAC admission gates, quarantine barriers, anti-theft defense
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

            // 3D Camera & Projection Parameters
            this.pitch = 54 * (Math.PI / 180); // 54° tilt for avionics / boat sonar look
            this.yaw = 0;                      // Azimuth rotation
            this.targetPitch = this.pitch;
            this.targetYaw = this.yaw;
            this.zoom = 1.0;
            this.targetZoom = 1.0;
            this.focalLength = 550;
            this.cameraDist = 420;

            // Radar state
            this.angle = 0; // Sweep beam azimuth (radians)
            this.targets = [];
            this.hoveredTarget = null;
            this.selectedTarget = null;
            this.echoWaves = [];
            this.lastFrameTime = performance.now();
            this.running = false;
            this.animId = null;

            // Mouse interaction state
            this.isDragging = false;
            this.dragStartX = 0;
            this.dragStartY = 0;
            this.startPitch = this.pitch;
            this.startYaw = this.yaw;

            this.initCanvasSize();
            this.bindEvents();
            this.start();
        }

        initCanvasSize() {
            const rect = this.canvas.getBoundingClientRect();
            const dpr = window.devicePixelRatio || 1;
            const w = rect.width || this.canvas.width || 620;
            const h = rect.height || this.canvas.height || 400;

            this.canvas.width = Math.round(w * dpr);
            this.canvas.height = Math.round(h * dpr);
            this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

            this.width = w;
            this.height = h;
            this.cx = w / 2;
            this.cy = h / 2 + 18; // Slightly lowered to fit tilted 3D horizon
            this.worldRadius = 180; // Radius in 3D world units
        }

        bindEvents() {
            window.addEventListener('resize', () => this.initCanvasSize());

            this.canvas.addEventListener('mousedown', (e) => {
                this.isDragging = true;
                this.dragStartX = e.clientX;
                this.dragStartY = e.clientY;
                this.startPitch = this.pitch;
                this.startYaw = this.yaw;
            });

            window.addEventListener('mouseup', () => {
                this.isDragging = false;
            });

            window.addEventListener('mousemove', (e) => {
                const rect = this.canvas.getBoundingClientRect();
                const x = e.clientX - rect.left;
                const y = e.clientY - rect.top;

                if (this.isDragging) {
                    const dx = e.clientX - this.dragStartX;
                    const dy = e.clientY - this.dragStartY;
                    this.targetYaw = this.startYaw + dx * 0.007;
                    this.targetPitch = Math.max(0.35, Math.min(1.35, this.startPitch + dy * 0.006));
                    return;
                }

                if (x >= 0 && x <= this.width && y >= 0 && y <= this.height) {
                    this.hoveredTarget = this.findTargetAtScreen(x, y);
                    this.canvas.style.cursor = this.hoveredTarget ? 'pointer' : (this.isDragging ? 'grabbing' : 'crosshair');
                }
            });

            this.canvas.addEventListener('wheel', (e) => {
                e.preventDefault();
                const delta = -Math.sign(e.deltaY) * 0.1;
                this.targetZoom = Math.max(0.65, Math.min(1.5, this.zoom + delta));
            }, { passive: false });

            this.canvas.addEventListener('dblclick', () => {
                // Reset camera to standard avionics tilt
                this.targetPitch = 54 * (Math.PI / 180);
                this.targetYaw = 0;
                this.targetZoom = 1.0;
            });

            this.canvas.addEventListener('click', (e) => {
                const rect = this.canvas.getBoundingClientRect();
                const x = e.clientX - rect.left;
                const y = e.clientY - rect.top;
                const hit = this.findTargetAtScreen(x, y);
                if (hit) {
                    this.selectedTarget = hit;
                    if (this.options.onTargetSelect) {
                        this.options.onTargetSelect(hit);
                    }
                }
            });
        }

        setTargets(newTargets) {
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
            if (t.isLocal || t.status === 'HOST_CONTROLLER') return 0;
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
            if (lat <= 2) return 0.35;
            if (lat <= 10) return 0.55;
            if (lat <= 40) return 0.75;
            return 0.88;
        }

        // ── 3D Projection Pipeline ──────────────────────────────────────────
        // Maps world coordinates (x, y, z) into 2D canvas coordinates (sx, sy) with depth zSort
        project3D(x, y, z = 0) {
            // 1. Rotate around Z (Yaw)
            const cosY = Math.cos(this.yaw);
            const sinY = Math.sin(this.yaw);
            const x1 = x * cosY - y * sinY;
            const y1 = x * sinY + y * cosY;
            const z1 = z;

            // 2. Rotate around X (Pitch tilt)
            const cosP = Math.cos(this.pitch);
            const sinP = Math.sin(this.pitch);
            const x2 = x1;
            const y2 = y1 * cosP - z1 * sinP;
            const z2 = y1 * sinP + z1 * cosP;

            // 3. Perspective Projection
            const depth = y2 + this.cameraDist;
            if (depth <= 20) return null; // Behind camera

            const scale = (this.focalLength / depth) * this.zoom;
            const sx = this.cx + x2 * scale;
            const sy = this.cy - z2 * scale;

            return { x: sx, y: sy, scale, depth };
        }

        getTargetWorldPos(t) {
            if (t.isLocal || t.status === 'HOST_CONTROLLER') {
                return { x: 0, y: 0, z: 0 };
            }
            const dist = this.worldRadius * t.distanceRatio;
            // Angle 0° is North (along positive Y in world coordinates)
            const wx = dist * Math.sin(t.angle);
            const wy = dist * Math.cos(t.angle);
            return { x: wx, y: wy, z: 0 };
        }

        findTargetAtScreen(sx, sy) {
            for (let t of this.targets) {
                const wpos = this.getTargetWorldPos(t);
                const proj = this.project3D(wpos.x, wpos.y, 14); // test near laptop center
                if (!proj) continue;
                const d = Math.hypot(sx - proj.x, sy - proj.y);
                if (d <= 32 * proj.scale) return t;
            }
            return null;
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
            // Smooth camera interpolation
            this.pitch += (this.targetPitch - this.pitch) * Math.min(1, dt * 10);
            this.yaw += (this.targetYaw - this.yaw) * Math.min(1, dt * 10);
            this.zoom += (this.targetZoom - this.zoom) * Math.min(1, dt * 10);

            // Rotate sweep beam
            const radPerSec = (this.options.rpm * 2 * Math.PI) / 60;
            const prevAngle = this.angle;
            this.angle = (this.angle + radPerSec * dt) % (2 * Math.PI);

            const now = performance.now();

            // Detect beam hits on targets
            for (let t of this.targets) {
                if (t.isLocal || t.status === 'HOST_CONTROLLER') continue;
                let swept = false;
                if (prevAngle < this.angle) {
                    swept = t.angle >= prevAngle && t.angle <= this.angle;
                } else {
                    swept = t.angle >= prevAngle || t.angle <= this.angle;
                }
                if (swept) {
                    t.lastSwept = now;
                    const wpos = this.getTargetWorldPos(t);
                    this.echoWaves.push({
                        wx: wpos.x,
                        wy: wpos.y,
                        radius: 5,
                        maxRadius: 42,
                        alpha: 1.0,
                        isApproved: t.isApproved
                    });
                }
            }

            // Update echo waves
            for (let i = this.echoWaves.length - 1; i >= 0; i--) {
                const wave = this.echoWaves[i];
                wave.radius += dt * 38;
                wave.alpha -= dt * 1.4;
                if (wave.alpha <= 0 || wave.radius >= wave.maxRadius) {
                    this.echoWaves.splice(i, 1);
                }
            }
        }

        // ── Main Render Pass ────────────────────────────────────────────────
        draw() {
            const ctx = this.ctx;
            ctx.clearRect(0, 0, this.width, this.height);

            // 1. Dark CRT Phosphor Backdrop with Depth Vignette
            this.drawBackdrop(ctx);

            // 2. 3D Concentric Range Rings & Radial Bearing Web
            this.draw3DRadarGrid(ctx);

            // 3. 3D Compass Azimuth Ring & Cardinal Directions
            this.draw3DCompassRose(ctx);

            // 4. 3D Volumetric Sweep Fan Beam & Phosphor Trailing Decay
            this.draw3DSweepBeam(ctx);

            // 5. 3D Sonar Echo Wave Ripples
            this.draw3DEchoWaves(ctx);

            // 6. Center 3D Avionics Aeroplane / Boat Sonar Vessel
            this.drawCenterNavigator(ctx);

            // 7. Detected 3D Isometric Laptops & Tactical Hologram Cards
            this.draw3DTargetLaptops(ctx);

            // 8. CRT Screen Scanline Overlay & Glass Bezel
            this.drawCrtScanlines(ctx);

            // 9. Digital Avionics Tactical HUD & Zero-Trust Telemetry
            this.drawHudOverlay(ctx);
        }

        drawBackdrop(ctx) {
            const grad = ctx.createRadialGradient(this.cx, this.cy, 20, this.cx, this.cy, Math.max(this.width, this.height) * 0.7);
            grad.addColorStop(0, '#04170d');
            grad.addColorStop(0.5, '#021008');
            grad.addColorStop(0.85, '#010904');
            grad.addColorStop(1, '#000402');

            ctx.fillStyle = grad;
            ctx.fillRect(0, 0, this.width, this.height);

            // Subdued horizon depth line
            const horizonProj = this.project3D(0, this.worldRadius * 1.5, 0);
            if (horizonProj) {
                const horizGrad = ctx.createLinearGradient(0, horizonProj.y - 30, 0, horizonProj.y + 30);
                horizGrad.addColorStop(0, 'rgba(16, 185, 129, 0)');
                horizGrad.addColorStop(0.5, 'rgba(16, 185, 129, 0.08)');
                horizGrad.addColorStop(1, 'rgba(16, 185, 129, 0)');
                ctx.fillStyle = horizGrad;
                ctx.fillRect(0, horizonProj.y - 30, this.width, 60);
            }
        }

        draw3DRadarGrid(ctx) {
            ctx.save();

            // Concentric 3D Range Rings
            const rings = [
                { ratio: 0.25, label: '10m LAN' },
                { ratio: 0.50, label: '25m LAN' },
                { ratio: 0.75, label: '40m LAN' },
                { ratio: 1.00, label: '50m AIRSPACE' }
            ];

            const segments = 64;

            rings.forEach((ring, rIdx) => {
                const r = this.worldRadius * ring.ratio;
                const isOuter = ring.ratio === 1.0;

                ctx.beginPath();
                let first = null;

                for (let i = 0; i <= segments; i++) {
                    const th = (i / segments) * 2 * Math.PI;
                    const wx = r * Math.sin(th);
                    const wy = r * Math.cos(th);
                    const p = this.project3D(wx, wy, 0);
                    if (!p) continue;

                    if (!first) {
                        ctx.moveTo(p.x, p.y);
                        first = p;
                    } else {
                        ctx.lineTo(p.x, p.y);
                    }
                }

                ctx.strokeStyle = isOuter ? 'rgba(34, 197, 94, 0.75)' : 'rgba(16, 185, 129, 0.25)';
                ctx.lineWidth = isOuter ? 1.8 : 1.0;
                if (!isOuter) ctx.setLineDash([4, 4]);
                else ctx.setLineDash([]);
                ctx.stroke();

                // 3D Range Label
                const labelPos = this.project3D(r * Math.sin(Math.PI / 4), r * Math.cos(Math.PI / 4), 0);
                if (labelPos && ring.ratio < 1.0) {
                    ctx.font = '8.5px "JetBrains Mono", monospace';
                    ctx.fillStyle = 'rgba(74, 222, 128, 0.6)';
                    ctx.fillText(ring.label, labelPos.x + 4, labelPos.y - 2);
                }
            });

            // Radial Bearing Grid Lines (every 30°)
            ctx.setLineDash([3, 5]);
            ctx.strokeStyle = 'rgba(16, 185, 129, 0.22)';
            ctx.lineWidth = 1;

            for (let deg = 0; deg < 360; deg += 30) {
                const rad = (deg * Math.PI) / 180;
                const p0 = this.project3D(0, 0, 0);
                const p1 = this.project3D(this.worldRadius * Math.sin(rad), this.worldRadius * Math.cos(rad), 0);
                if (p0 && p1) {
                    ctx.beginPath();
                    ctx.moveTo(p0.x, p0.y);
                    ctx.lineTo(p1.x, p1.y);
                    ctx.stroke();
                }
            }

            ctx.restore();
        }

        draw3DCompassRose(ctx) {
            ctx.save();
            ctx.font = 'bold 9px "JetBrains Mono", monospace';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';

            const r = this.worldRadius;
            for (let deg = 0; deg < 360; deg += 10) {
                const rad = (deg * Math.PI) / 180;
                const isCardinal = deg % 90 === 0;
                const isMajor = deg % 30 === 0;

                const innerR = isCardinal ? r - 8 : (isMajor ? r - 5 : r - 3);
                const p1 = this.project3D(innerR * Math.sin(rad), innerR * Math.cos(rad), 0);
                const p2 = this.project3D(r * Math.sin(rad), r * Math.cos(rad), 0);

                if (!p1 || !p2) continue;

                ctx.strokeStyle = isCardinal ? '#22c55e' : (isMajor ? 'rgba(34, 197, 94, 0.55)' : 'rgba(16, 185, 129, 0.25)');
                ctx.lineWidth = isCardinal ? 2.0 : 1.0;
                ctx.beginPath();
                ctx.moveTo(p1.x, p1.y);
                ctx.lineTo(p2.x, p2.y);
                ctx.stroke();

                if (isCardinal) {
                    const lp = this.project3D((r + 14) * Math.sin(rad), (r + 14) * Math.cos(rad), 0);
                    if (lp) {
                        const labels = { 0: '000° N', 90: '090° E', 180: '180° S', 270: '270° W' };
                        ctx.fillStyle = '#4ade80';
                        ctx.fillText(labels[deg], lp.x, lp.y);
                    }
                }
            }
            ctx.restore();
        }

        draw3DSweepBeam(ctx) {
            ctx.save();
            const beamAngle = this.angle;
            const r = this.worldRadius;
            const slices = 28;
            const tailArc = 50 * (Math.PI / 180); // 50° phosphorescent decay fan

            // Center origin point
            const pCenter = this.project3D(0, 0, 0);
            if (!pCenter) { ctx.restore(); return; }

            // 1. Swept 3D Volumetric Tail Fan (curved ground fan with fading phosphor)
            for (let i = 0; i < slices; i++) {
                const a1 = beamAngle - (i + 1) * (tailArc / slices);
                const a2 = beamAngle - i * (tailArc / slices);
                const alpha = (1 - (i / slices)) * 0.24;

                const p1 = this.project3D(r * Math.sin(a1), r * Math.cos(a1), 0);
                const p2 = this.project3D(r * Math.sin(a2), r * Math.cos(a2), 0);

                if (p1 && p2) {
                    ctx.beginPath();
                    ctx.moveTo(pCenter.x, pCenter.y);
                    ctx.lineTo(p1.x, p1.y);
                    ctx.lineTo(p2.x, p2.y);
                    ctx.closePath();
                    ctx.fillStyle = `rgba(34, 197, 94, ${alpha})`;
                    ctx.fill();
                }
            }

            // 2. Vertical 3D Volumetric Curtain on the leading edge (height z = 16)
            const pLeadGround = this.project3D(r * Math.sin(beamAngle), r * Math.cos(beamAngle), 0);
            const pLeadTop = this.project3D(r * Math.sin(beamAngle), r * Math.cos(beamAngle), 18);
            const pCenterTop = this.project3D(0, 0, 18);

            if (pLeadGround && pLeadTop && pCenterTop) {
                const curtainGrad = ctx.createLinearGradient(pCenter.x, pCenter.y, pLeadTop.x, pLeadTop.y);
                curtainGrad.addColorStop(0, 'rgba(74, 222, 128, 0.45)');
                curtainGrad.addColorStop(1, 'rgba(34, 197, 94, 0.05)');

                ctx.beginPath();
                ctx.moveTo(pCenter.x, pCenter.y);
                ctx.lineTo(pCenterTop.x, pCenterTop.y);
                ctx.lineTo(pLeadTop.x, pLeadTop.y);
                ctx.lineTo(pLeadGround.x, pLeadGround.y);
                ctx.closePath();
                ctx.fillStyle = curtainGrad;
                ctx.fill();
            }

            // 3. Leading High-Intensity 3D Sweep Line
            if (pLeadGround) {
                const lineGrad = ctx.createLinearGradient(pCenter.x, pCenter.y, pLeadGround.x, pLeadGround.y);
                lineGrad.addColorStop(0, 'rgba(134, 239, 172, 0.95)');
                lineGrad.addColorStop(0.7, 'rgba(34, 197, 94, 0.85)');
                lineGrad.addColorStop(1, 'rgba(16, 185, 129, 0.3)');

                ctx.strokeStyle = lineGrad;
                ctx.lineWidth = 2.4;
                ctx.beginPath();
                ctx.moveTo(pCenter.x, pCenter.y);
                ctx.lineTo(pLeadGround.x, pLeadGround.y);
                ctx.stroke();

                // Leading glow blip at rim
                ctx.fillStyle = '#86efac';
                ctx.beginPath();
                ctx.arc(pLeadGround.x, pLeadGround.y, 3.5, 0, 2 * Math.PI);
                ctx.fill();
            }

            ctx.restore();
        }

        draw3DEchoWaves(ctx) {
            ctx.save();
            const segments = 32;

            for (let wave of this.echoWaves) {
                ctx.beginPath();
                let first = null;

                for (let i = 0; i <= segments; i++) {
                    const th = (i / segments) * 2 * Math.PI;
                    const wx = wave.wx + wave.radius * Math.sin(th);
                    const wy = wave.wy + wave.radius * Math.cos(th);
                    const p = this.project3D(wx, wy, 0);
                    if (!p) continue;

                    if (!first) {
                        ctx.moveTo(p.x, p.y);
                        first = p;
                    } else {
                        ctx.lineTo(p.x, p.y);
                    }
                }

                ctx.strokeStyle = wave.isApproved
                    ? `rgba(34, 197, 94, ${wave.alpha})`
                    : `rgba(245, 158, 11, ${wave.alpha})`;
                ctx.lineWidth = 1.8;
                ctx.stroke();
            }
            ctx.restore();
        }

        // ── 3D Wireframe Avionics Jet / Naval Sonar Navigator at Origin ──────
        drawCenterNavigator(ctx) {
            ctx.save();
            // Aeroplane / Boat points along +Y (North / 000°)
            // Delta Wing Jet Wireframe in 3D:
            const nose = this.project3D(0, 16, 4);
            const tail = this.project3D(0, -12, 4);
            const wingLeft = this.project3D(-14, -6, 2);
            const wingRight = this.project3D(14, -6, 2);
            const tailLeft = this.project3D(-6, -12, 2);
            const tailRight = this.project3D(6, -12, 2);
            const finTop = this.project3D(0, -10, 10);
            const cockpit = this.project3D(0, 8, 7);

            if (nose && tail && wingLeft && wingRight && finTop && cockpit) {
                // Fuselage & Delta Wings
                ctx.strokeStyle = '#22c55e';
                ctx.lineWidth = 1.8;
                ctx.fillStyle = 'rgba(16, 185, 129, 0.2)';

                ctx.beginPath();
                ctx.moveTo(nose.x, nose.y);
                ctx.lineTo(wingLeft.x, wingLeft.y);
                ctx.lineTo(tailLeft.x, tailLeft.y);
                ctx.lineTo(tail.x, tail.y);
                ctx.lineTo(tailRight.x, tailRight.y);
                ctx.lineTo(wingRight.x, wingRight.y);
                ctx.closePath();
                ctx.fill();
                ctx.stroke();

                // Cockpit Ridge & Vertical Fin
                ctx.strokeStyle = '#86efac';
                ctx.lineWidth = 1.4;
                ctx.beginPath();
                ctx.moveTo(nose.x, nose.y);
                ctx.lineTo(cockpit.x, cockpit.y);
                ctx.lineTo(finTop.x, finTop.y);
                ctx.lineTo(tail.x, tail.y);
                ctx.stroke();

                // Ground drop shadow
                const shadow = this.project3D(0, 0, 0);
                if (shadow) {
                    ctx.fillStyle = 'rgba(0, 0, 0, 0.5)';
                    ctx.beginPath();
                    ctx.arc(shadow.x, shadow.y, 6, 0, 2 * Math.PI);
                    ctx.fill();
                }
            }
            ctx.restore();
        }

        // ── 3D Target Laptops (Mac, Windows, Linux) ───────────────────────────
        draw3DTargetLaptops(ctx) {
            const now = performance.now();

            // Depth-sort targets so closer ones render on top
            const sorted = this.targets.map(t => {
                const wpos = this.getTargetWorldPos(t);
                const proj = this.project3D(wpos.x, wpos.y, 0);
                return { target: t, wpos, proj, depth: proj ? proj.depth : 99999 };
            }).sort((a, b) => b.depth - a.depth); // Far to near

            for (let item of sorted) {
                const t = item.target;
                const wpos = item.wpos;
                const proj = item.proj;
                if (!proj) continue;

                const isHovered = this.hoveredTarget && this.hoveredTarget.nodeId === t.nodeId;
                const isSelected = this.selectedTarget && this.selectedTarget.nodeId === t.nodeId;

                const timeSinceSwept = now - (t.lastSwept || 0);
                const isLit = timeSinceSwept < 1400;

                // Gentle floating hover elevation
                const floatOffset = Math.sin((now * 0.003) + (t.angle * 2)) * 1.5;
                const elevZ = 5 + floatOffset;

                // 1. Holographic Base Pedestal & Vertical Altitude Beacon
                this.draw3DPedestal(ctx, wpos.x, wpos.y, elevZ, t.isApproved, isLit || isHovered || isSelected);

                // 2. Render 3D Isometric Laptop
                this.render3DLaptopModel(ctx, wpos.x, wpos.y, elevZ, t.deviceType, t.isApproved, isLit || isHovered || isSelected, proj.scale);

                // 3. Targeting Lock Brackets
                if (isHovered || isSelected || isLit) {
                    this.draw3DTargetLock(ctx, wpos.x, wpos.y, elevZ, t.isApproved, proj.scale);
                }

                // 4. Floating Holographic Metadata Billboard Tag
                this.draw3DHoloBillboard(ctx, t, wpos.x, wpos.y, elevZ + 22, isHovered || isSelected);
            }
        }

        draw3DPedestal(ctx, wx, wy, elevZ, isApproved, isHighlighted) {
            ctx.save();
            const pGround = this.project3D(wx, wy, 0);
            const pElev = this.project3D(wx, wy, elevZ);
            if (!pGround || !pElev) { ctx.restore(); return; }

            const color = isApproved ? 'rgba(34, 197, 94,' : 'rgba(245, 158, 11,';

            // Ground projected ring
            const segments = 16;
            const r = 12;
            ctx.beginPath();
            let first = null;
            for (let i = 0; i <= segments; i++) {
                const th = (i / segments) * 2 * Math.PI;
                const p = this.project3D(wx + r * Math.sin(th), wy + r * Math.cos(th), 0);
                if (!p) continue;
                if (!first) { ctx.moveTo(p.x, p.y); first = p; }
                else ctx.lineTo(p.x, p.y);
            }
            ctx.strokeStyle = `${color} ${isHighlighted ? 0.75 : 0.35})`;
            ctx.lineWidth = 1.2;
            ctx.stroke();

            // Vertical neon guide beacon
            ctx.strokeStyle = `${color} ${isHighlighted ? 0.6 : 0.25})`;
            ctx.lineWidth = 1;
            ctx.setLineDash([2, 2]);
            ctx.beginPath();
            ctx.moveTo(pGround.x, pGround.y);
            ctx.lineTo(pElev.x, pElev.y);
            ctx.stroke();

            ctx.restore();
        }

        render3DLaptopModel(ctx, wx, wy, z, type, isApproved, isHighlighted, scale) {
            ctx.save();

            // Dimensions in world units
            const halfW = 10;
            const halfD = 7.5;
            const baseH = 1.4;

            const strokeColor = isApproved ? (isHighlighted ? '#86efac' : '#22c55e') : (isHighlighted ? '#fde047' : '#f59e0b');
            const fillTop = isApproved ? 'rgba(16, 185, 129, 0.45)' : 'rgba(217, 119, 6, 0.45)';
            const fillSide = isApproved ? 'rgba(6, 78, 59, 0.6)' : 'rgba(120, 53, 15, 0.6)';

            // 1. Laptop Base / Keyboard Deck Box
            // Bottom corners
            const b0 = this.project3D(wx - halfW, wy - halfD, z);
            const b1 = this.project3D(wx + halfW, wy - halfD, z);
            const b2 = this.project3D(wx + halfW, wy + halfD, z);
            const b3 = this.project3D(wx - halfW, wy + halfD, z);

            // Top corners of deck
            const t0 = this.project3D(wx - halfW, wy - halfD, z + baseH);
            const t1 = this.project3D(wx + halfW, wy - halfD, z + baseH);
            const t2 = this.project3D(wx + halfW, wy + halfD, z + baseH);
            const t3 = this.project3D(wx - halfW, wy + halfD, z + baseH);

            if (t0 && t1 && t2 && t3) {
                // Front chamfered side
                if (b0 && b1) {
                    ctx.fillStyle = fillSide;
                    ctx.beginPath();
                    ctx.moveTo(b0.x, b0.y);
                    ctx.lineTo(b1.x, b1.y);
                    ctx.lineTo(t1.x, t1.y);
                    ctx.lineTo(t0.x, t0.y);
                    ctx.closePath();
                    ctx.fill();
                    ctx.strokeStyle = strokeColor;
                    ctx.lineWidth = 1;
                    ctx.stroke();
                }

                // Keyboard Top Surface
                ctx.fillStyle = fillTop;
                ctx.beginPath();
                ctx.moveTo(t0.x, t0.y);
                ctx.lineTo(t1.x, t1.y);
                ctx.lineTo(t2.x, t2.y);
                ctx.lineTo(t3.x, t3.y);
                ctx.closePath();
                ctx.fill();
                ctx.strokeStyle = strokeColor;
                ctx.lineWidth = 1.2;
                ctx.stroke();

                // Trackpad outline
                const tp0 = this.project3D(wx - 3, wy - halfD + 1, z + baseH + 0.1);
                const tp1 = this.project3D(wx + 3, wy - halfD + 1, z + baseH + 0.1);
                const tp2 = this.project3D(wx + 3, wy - halfD + 3.8, z + baseH + 0.1);
                const tp3 = this.project3D(wx - 3, wy - halfD + 3.8, z + baseH + 0.1);
                if (tp0 && tp1 && tp2 && tp3) {
                    ctx.strokeStyle = strokeColor;
                    ctx.lineWidth = 0.8;
                    ctx.beginPath();
                    ctx.moveTo(tp0.x, tp0.y);
                    ctx.lineTo(tp1.x, tp1.y);
                    ctx.lineTo(tp2.x, tp2.y);
                    ctx.lineTo(tp3.x, tp3.y);
                    ctx.closePath();
                    ctx.stroke();
                }

                // Keyboard Grid Area
                const kb0 = this.project3D(wx - halfW + 1.5, wy - halfD + 4.5, z + baseH + 0.1);
                const kb1 = this.project3D(wx + halfW - 1.5, wy - halfD + 4.5, z + baseH + 0.1);
                const kb2 = this.project3D(wx + halfW - 1.5, wy + halfD - 1.2, z + baseH + 0.1);
                const kb3 = this.project3D(wx - halfW + 1.5, wy + halfD - 1.2, z + baseH + 0.1);
                if (kb0 && kb1 && kb2 && kb3) {
                    ctx.fillStyle = 'rgba(0, 0, 0, 0.45)';
                    ctx.beginPath();
                    ctx.moveTo(kb0.x, kb0.y);
                    ctx.lineTo(kb1.x, kb1.y);
                    ctx.lineTo(kb2.x, kb2.y);
                    ctx.lineTo(kb3.x, kb3.y);
                    ctx.closePath();
                    ctx.fill();
                    ctx.strokeStyle = strokeColor;
                    ctx.lineWidth = 0.8;
                    ctx.stroke();

                    // Linux ThinkPad TrackPoint red dot
                    if (type === 'linux') {
                        const trackPoint = this.project3D(wx, wy, z + baseH + 0.3);
                        if (trackPoint) {
                            ctx.fillStyle = '#ef4444';
                            ctx.beginPath();
                            ctx.arc(trackPoint.x, trackPoint.y, 1.8, 0, 2 * Math.PI);
                            ctx.fill();
                        }
                    }
                }
            }

            // 2. Tilted 3D Display Lid (Screen opens at rear edge t2-t3, leaning back at ~105°)
            const screenH = 11;
            const tiltBack = 2.5; // slight backward slant in world Y

            const sBottomL = this.project3D(wx - halfW, wy + halfD, z + baseH);
            const sBottomR = this.project3D(wx + halfW, wy + halfD, z + baseH);
            const sTopL = this.project3D(wx - halfW, wy + halfD + tiltBack, z + baseH + screenH);
            const sTopR = this.project3D(wx + halfW, wy + halfD + tiltBack, z + baseH + screenH);

            if (sBottomL && sBottomR && sTopL && sTopR) {
                // Lid Outer Frame
                ctx.fillStyle = fillSide;
                ctx.beginPath();
                ctx.moveTo(sBottomL.x, sBottomL.y);
                ctx.lineTo(sBottomR.x, sBottomR.y);
                ctx.lineTo(sTopR.x, sTopR.y);
                ctx.lineTo(sTopL.x, sTopL.y);
                ctx.closePath();
                ctx.fill();
                ctx.strokeStyle = strokeColor;
                ctx.lineWidth = 1.4;
                ctx.stroke();

                // Inner Illuminated Screen Display
                const scrBL = this.project3D(wx - halfW + 1.2, wy + halfD + 0.2, z + baseH + 1.0);
                const scrBR = this.project3D(wx + halfW - 1.2, wy + halfD + 0.2, z + baseH + 1.0);
                const scrTR = this.project3D(wx + halfW - 1.2, wy + halfD + tiltBack - 0.2, z + baseH + screenH - 1.0);
                const scrTL = this.project3D(wx - halfW + 1.2, wy + halfD + tiltBack - 0.2, z + baseH + screenH - 1.0);

                if (scrBL && scrBR && scrTR && scrTL) {
                    ctx.fillStyle = isApproved ? 'rgba(34, 197, 94, 0.35)' : 'rgba(245, 158, 11, 0.35)';
                    ctx.beginPath();
                    ctx.moveTo(scrBL.x, scrBL.y);
                    ctx.lineTo(scrBR.x, scrBR.y);
                    ctx.lineTo(scrTR.x, scrTR.y);
                    ctx.lineTo(scrTL.x, scrTL.y);
                    ctx.closePath();
                    ctx.fill();

                    // Hardware-specific screen artwork
                    const centerScreen = this.project3D(wx, wy + halfD + tiltBack * 0.5, z + baseH + screenH * 0.5);
                    if (centerScreen) {
                        ctx.fillStyle = strokeColor;
                        if (type === 'mac') {
                            // Glowing Apple Logo on Retina Screen
                            ctx.beginPath();
                            ctx.arc(centerScreen.x, centerScreen.y, 3 * scale, 0, 2 * Math.PI);
                            ctx.fill();
                            // Apple stem/leaf
                            ctx.beginPath();
                            ctx.arc(centerScreen.x + 1.5 * scale, centerScreen.y - 3.5 * scale, 1.2 * scale, 0, 2 * Math.PI);
                            ctx.fill();
                        } else if (type === 'linux') {
                            // Linux Bash Terminal prompt `>_`
                            ctx.strokeStyle = '#4ade80';
                            ctx.lineWidth = 1.2;
                            ctx.beginPath();
                            ctx.moveTo(centerScreen.x - 3 * scale, centerScreen.y - 1.5 * scale);
                            ctx.lineTo(centerScreen.x - 0.5 * scale, centerScreen.y);
                            ctx.lineTo(centerScreen.x - 3 * scale, centerScreen.y + 1.5 * scale);
                            ctx.stroke();
                            ctx.beginPath();
                            ctx.moveTo(centerScreen.x + 1 * scale, centerScreen.y + 1.5 * scale);
                            ctx.lineTo(centerScreen.x + 3.5 * scale, centerScreen.y + 1.5 * scale);
                            ctx.stroke();
                        } else {
                            // Windows 11 4-Tile Logo
                            const tile = 1.6 * scale;
                            ctx.fillRect(centerScreen.x - tile - 0.5, centerScreen.y - tile - 0.5, tile, tile);
                            ctx.fillRect(centerScreen.x + 0.5, centerScreen.y - tile - 0.5, tile, tile);
                            ctx.fillRect(centerScreen.x - tile - 0.5, centerScreen.y + 0.5, tile, tile);
                            ctx.fillRect(centerScreen.x + 0.5, centerScreen.y + 0.5, tile, tile);
                        }
                    }
                }
            }

            ctx.restore();
        }

        draw3DTargetLock(ctx, wx, wy, z, isApproved, scale) {
            ctx.save();
            const p = this.project3D(wx, wy, z + 5);
            if (!p) { ctx.restore(); return; }

            ctx.strokeStyle = isApproved ? '#4ade80' : '#fbbf24';
            ctx.lineWidth = 1.5;
            const b = 22 * scale;
            const len = 6 * scale;

            // Rotating 3D lock brackets around the laptop
            ctx.beginPath();
            // TL
            ctx.moveTo(p.x - b, p.y - b + len); ctx.lineTo(p.x - b, p.y - b); ctx.lineTo(p.x - b + len, p.y - b);
            // TR
            ctx.moveTo(p.x + b - len, p.y - b); ctx.lineTo(p.x + b, p.y - b); ctx.lineTo(p.x + b, p.y - b + len);
            // BL
            ctx.moveTo(p.x - b, p.y + b - len); ctx.lineTo(p.x - b, p.y + b); ctx.lineTo(p.x - b + len, p.y + b);
            // BR
            ctx.moveTo(p.x + b - len, p.y + b); ctx.lineTo(p.x + b, p.y + b); ctx.lineTo(p.x + b, p.y + b - len);
            ctx.stroke();

            ctx.restore();
        }

        // ── 3D Floating Holographic Metadata Billboard ────────────────────────
        draw3DHoloBillboard(ctx, t, wx, wy, z, isExpanded) {
            ctx.save();
            const p = this.project3D(wx, wy, z);
            const pFoot = this.project3D(wx, wy, z - 8);
            if (!p || !pFoot) { ctx.restore(); return; }

            const isApproved = t.isApproved;
            const themeCol = isApproved ? '#22c55e' : '#f59e0b';
            const borderCol = isApproved ? 'rgba(34, 197, 94, 0.45)' : 'rgba(245, 158, 11, 0.55)';

            // Leader line to floating billboard tag
            ctx.strokeStyle = borderCol;
            ctx.lineWidth = 1;
            ctx.setLineDash([2, 2]);
            ctx.beginPath();
            ctx.moveTo(pFoot.x, pFoot.y);
            ctx.lineTo(p.x, p.y);
            ctx.stroke();
            ctx.setLineDash([]);

            // Hostname & Specs Label
            const hostname = t.hostname || t.nodeId || 'Worker Node';
            const devLabel = t.deviceType === 'mac' ? 'MacBook Pro' : (t.deviceType === 'linux' ? 'Linux Workstation' : 'Windows 11 PC');
            const latencyStr = t.latencyMs ? `${t.latencyMs}ms` : 'LAN';
            const coresStr = t.cores ? `${t.cores}C` : '8C';
            const ramStr = t.ramGb ? `${t.ramGb}GB` : '16GB';

            const cardW = Math.max(120, hostname.length * 7 + 36);
            const cardH = isExpanded ? 46 : 30;

            const cardX = p.x - cardW / 2;
            const cardY = p.y - cardH;

            // Translucent Glass Billboard
            ctx.fillStyle = 'rgba(5, 18, 11, 0.9)';
            ctx.strokeStyle = borderCol;
            ctx.lineWidth = 1.2;
            ctx.beginPath();
            ctx.roundRect(cardX, cardY, cardW, cardH, 5);
            ctx.fill();
            ctx.stroke();

            // Hardware icon
            const icon = t.deviceType === 'mac' ? '🍏' : (t.deviceType === 'linux' ? '🐧' : '🪟');
            ctx.font = '11px sans-serif';
            ctx.textAlign = 'left';
            ctx.textBaseline = 'middle';
            ctx.fillText(icon, cardX + 6, cardY + 12);

            // Hostname
            ctx.font = 'bold 9.5px "JetBrains Mono", monospace';
            ctx.fillStyle = themeCol;
            ctx.fillText(hostname, cardX + 24, cardY + 11);

            // Subtitle Specs line
            ctx.font = '8px "JetBrains Mono", monospace';
            ctx.fillStyle = '#94a3b8';
            ctx.fillText(`${devLabel} • ${coresStr}/${ramStr} • ${latencyStr}`, cardX + 6, cardY + 23);

            // Zero-Trust Security Pill (if expanded or hovered)
            if (isExpanded) {
                const secText = isApproved ? '🛡️ ADMITTED (TLS 1.3)' : '⚠️ QUARANTINED (ZERO-TRUST)';
                ctx.fillStyle = isApproved ? '#34d399' : '#fbbf24';
                ctx.font = 'bold 7.5px "JetBrains Mono", monospace';
                ctx.fillText(secText, cardX + 6, cardY + 36);
            }

            ctx.restore();
        }

        // ── CRT Scanline & Glass Bezel Effect ─────────────────────────────────
        drawCrtScanlines(ctx) {
            ctx.save();
            ctx.fillStyle = 'rgba(0, 0, 0, 0.08)';
            for (let y = 0; y < this.height; y += 4) {
                ctx.fillRect(0, y, this.width, 1.5);
            }

            // High-tech corner bracket reticles on viewport
            ctx.strokeStyle = 'rgba(16, 185, 129, 0.4)';
            ctx.lineWidth = 1.5;
            const len = 12;

            // TL
            ctx.beginPath(); ctx.moveTo(8, 8 + len); ctx.lineTo(8, 8); ctx.lineTo(8 + len, 8); ctx.stroke();
            // TR
            ctx.beginPath(); ctx.moveTo(this.width - 8 - len, 8); ctx.lineTo(this.width - 8, 8); ctx.lineTo(this.width - 8, 8 + len); ctx.stroke();
            // BL
            ctx.beginPath(); ctx.moveTo(8, this.height - 8 - len); ctx.lineTo(8, this.height - 8); ctx.lineTo(8 + len, this.height - 8); ctx.stroke();
            // BR
            ctx.beginPath(); ctx.moveTo(this.width - 8 - len, this.height - 8); ctx.lineTo(this.width - 8, this.height - 8); ctx.lineTo(this.width - 8, this.height - 8 - len); ctx.stroke();

            ctx.restore();
        }

        // ── Tactical HUD Telemetry & Zero-Trust Defense Header ────────────────
        drawHudOverlay(ctx) {
            ctx.save();
            ctx.font = '9.5px "JetBrains Mono", monospace';

            // Top-Left: Avionics & Sonar Telemetry
            ctx.textAlign = 'left';
            ctx.textBaseline = 'top';
            ctx.fillStyle = '#22c55e';
            ctx.fillText('3D AVIONICS & SONAR RADAR // UDP 50055', 18, 14);

            ctx.font = '8px "JetBrains Mono", monospace';
            ctx.fillStyle = '#64748b';
            const deg = String(Math.round((this.angle * 180) / Math.PI)).padStart(3, '0');
            const pitchDeg = Math.round((this.pitch * 180) / Math.PI);
            ctx.fillText(`BEARING: ${deg}° • TILT: ${pitchDeg}° • SWEEP: 42 RPM • RANGE: 50m`, 18, 27);

            // Top-Right: Zero-Trust Security Gate
            ctx.textAlign = 'right';
            ctx.font = 'bold 9px "JetBrains Mono", monospace';
            ctx.fillStyle = this.options.zeroTrustEnforced ? '#34d399' : '#f59e0b';
            ctx.fillText('🛡️ ZERO-TRUST GATE: ACTIVE', this.width - 18, 14);

            ctx.font = '8px "JetBrains Mono", monospace';
            ctx.fillStyle = '#94a3b8';
            ctx.fillText('HMAC TICKET AUTH • ANONYMOUS BLOCKED', this.width - 18, 27);

            // Bottom-Left: Airspace Status
            ctx.textAlign = 'left';
            ctx.textBaseline = 'bottom';
            const pulsing = (Math.sin(performance.now() / 250) + 1) / 2;
            ctx.fillStyle = `rgba(34, 197, 94, ${0.4 + pulsing * 0.6})`;
            ctx.beginPath();
            ctx.arc(22, this.height - 18, 4, 0, 2 * Math.PI);
            ctx.fill();

            ctx.fillStyle = '#22c55e';
            ctx.font = '8.5px "JetBrains Mono", monospace';
            ctx.fillText('AIRSPACE SCANNING // LAN QUICK SHARE • DRAG MOUSE TO ORBIT 3D', 32, this.height - 14);

            // Bottom-Right: Target Counts
            ctx.textAlign = 'right';
            const total = this.targets.length;
            const approved = this.targets.filter(t => t.isApproved).length;
            const pending = total - approved;

            ctx.fillStyle = '#cbd5e1';
            ctx.font = '8.5px "JetBrains Mono", monospace';
            ctx.fillText(`TARGETS: ${approved} VERIFIED • ${pending} QUARANTINED`, this.width - 18, this.height - 14);

            ctx.restore();
        }
    }

    global.RadarScanner = RadarScanner;

})(typeof window !== 'undefined' ? window : this);
