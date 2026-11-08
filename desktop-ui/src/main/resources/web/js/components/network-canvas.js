/**
 * network-canvas.js — 3D Distributed Cluster Visualiser
 *
 * Renders an interactive, depth-perspective scene:
 *   • Central 3D server rack chassis (not a circle) with rack blades & blinking LEDs
 *   • Compute nodes arranged in 3D orbit, each drawn as its authentic device type:
 *     (Windows PC, MacBook, Linux terminal tower, Raspberry Pi, generic rack server)
 *   • Accurate Dead Node isolation: severed/dashed red connection line, frozen drift
 *   • Idle state: elegant connection lines with NO random pulse spam
 *   • Task Lifecycle:
 *     1. Server -> Node: Work packets visibly dispatch along edge
 *     2. Node Computing: Spinning progress ring, active sparkles, load indicator
 *     3. Node -> Server: Emerald result packet returns, server ripple absorption
 *
 * Public API:
 *   NG.networkCanvas.mount()
 *   NG.networkCanvas.destroy()
 *   NG.networkCanvas.updateNodes(nodes)
 *   NG.networkCanvas.dispatchTask(task)
 */
(function () {
    'use strict';

    window.NG = window.NG || {};

    /* ─── Config ─────────────────────────────────────────────── */
    const CFG = {
        FPS:            60,
        ORBIT_RX:       0.32,
        ORBIT_RY:       0.26,
        NODE_ICON_W:    56,
        NODE_ICON_H:    46,
        SERVER_W:       72,
        SERVER_H:       94,
        STAR_COUNT:     85,
        PARALLAX:       0.008,
        AMBIENT_COUNT:  16,
        AMBIENT_SPEED:  0.25,
        GLOW_LAYERS:    3,
    };

    /* ─── State ───────────────────────────────────────────────── */
    let canvas, ctx, W, H, dpr;
    let stars         = [];
    let ambients      = [];
    let realNodes     = [];     // Node array with positions and status
    let activeTasks   = [];     // [{ taskId, nodeId, phase: 'DISPATCH'|'PROCESSING'|'RETURN', t, label }]
    let serverRipples = [];     // [{ r, alpha }]
    let mouse         = { x: 0, y: 0 };
    let raf           = 0;
    let lastTs        = 0;
    let serverPos     = { x: 0, y: 0 };
    let running       = false;
    let spinAngle     = 0;      // slow server rotation indicator

    /* ─── Helpers ─────────────────────────────────────────────── */
    const rand  = (a, b) => a + Math.random() * (b - a);
    const lerp  = (a, b, t) => a + (b - a) * t;

    function deviceType(node) {
        const os = (node.os || node.platform || node.operatingSystem || node.osName || '').toLowerCase();
        const name = (node.name || node.hostname || node.id || '').toLowerCase();
        if (os.includes('win') || name.includes('win'))   return 'windows';
        if (os.includes('mac') || name.includes('mac'))   return 'mac';
        if (os.includes('raspb') || name.includes('pi'))  return 'raspi';
        if (os.includes('linux') || name.includes('linux') || name.includes('ubuntu') || name.includes('debian')) return 'linux';
        if (os.includes('freebsd') || os.includes('bsd')) return 'linux';
        return 'server';
    }

    /* ─── Colour palette ──────────────────────────────────────── */
    const PAL = {
        accent:    '#00D4FF',
        accent2:   '#7C3AED',
        healthy:   '#00CC6A',
        offline:   '#FF3250',
        degraded:  '#FFAA00',
        unknown:   '#6B7B9E',
        star:      'rgba(160,200,255,',
        ambient:   'rgba(0,212,255,',
    };

    function isNodeDead(rn) {
        if (!rn) return true;
        const s = (rn.status || '').toUpperCase();
        return s === 'DEAD' || s === 'SUSPECTED_DEAD' || s === 'OFFLINE' || s === 'FAILED' || s === 'UNREACHABLE' || s === 'DEREGISTERED';
    }

    function nodeColor(n) {
        if (isNodeDead(n)) return PAL.offline;
        const s = (n.status || '').toUpperCase();
        if (s === 'HEALTHY' || s === 'ONLINE' || s === 'ALIVE') return PAL.healthy;
        if (s === 'DEGRADED' || s === 'BUSY') return PAL.degraded;
        return PAL.accent;
    }

    /* ─── Scene init ──────────────────────────────────────────── */
    function buildStars() {
        stars = Array.from({ length: CFG.STAR_COUNT }, () => ({
            x: rand(0, 1), y: rand(0, 1),
            r: rand(0.4, 1.8),
            a: rand(0.15, 0.55),
            speed: rand(0.00003, 0.00009),
        }));
    }

    function buildAmbients() {
        ambients = Array.from({ length: CFG.AMBIENT_COUNT }, () => {
            const angle = rand(0, Math.PI * 2);
            const spd   = rand(0.15, CFG.AMBIENT_SPEED);
            return {
                x: rand(0, W), y: rand(0, H),
                vx: Math.cos(angle) * spd,
                vy: Math.sin(angle) * spd,
                r:  rand(1.5, 4),
                a:  rand(0.3, 0.7),
                ad: 0.007,
            };
        });
    }

    function placeNodes() {
        const cx = serverPos.x;
        const cy = serverPos.y;
        const rx = W * CFG.ORBIT_RX;
        const ry = H * CFG.ORBIT_RY;
        const aliveNodes = realNodes.filter(rn => !isNodeDead(rn));
        const deadNodes  = realNodes.filter(rn => isNodeDead(rn));

        const nAlive = Math.max(1, aliveNodes.length);

        aliveNodes.forEach((rn, i) => {
            const baseAngle = (i / nAlive) * Math.PI * 2 - Math.PI / 2;
            const curAngle  = baseAngle + spinAngle * 0.4;
            const depthFactor = 0.72 + 0.28 * Math.sin(curAngle + Math.PI / 2);

            rn.tx = cx + Math.cos(curAngle) * rx;
            rn.ty = cy + Math.sin(curAngle) * ry * 0.80;
            rn.depth = depthFactor;
            rn.scale = 0.75 + 0.25 * depthFactor;
            if (rn.x === undefined) { rn.x = rn.tx; rn.y = rn.ty; }
            rn.type = deviceType(rn);
        });

        deadNodes.forEach((rn, i) => {
            const deadAngle = Math.PI * 0.75 + (i * 0.4);
            const deadRx = rx * 1.35;
            const deadRy = ry * 1.25;
            rn.tx = cx + Math.cos(deadAngle) * deadRx;
            rn.ty = cy + Math.sin(deadAngle) * deadRy;
            rn.depth = 0.65;
            rn.scale = 0.72;
            if (rn.x === undefined) { rn.x = rn.tx; rn.y = rn.ty; }
            rn.type = deviceType(rn);
        });
    }

    /* ─── Device Pictograms ───────────────────────────────────── */

    function drawWindows(color, glow) {
        const mw = 40, mh = 26, br = 3;
        ctx.beginPath();
        ctx.roundRect(-mw/2, -mh/2 - 10, mw, mh, br);
        ctx.fillStyle   = '#111b2e';
        ctx.strokeStyle = color;
        ctx.lineWidth   = 1.5;
        ctx.fill(); ctx.stroke();

        ctx.save();
        ctx.clip();
        const sg = ctx.createLinearGradient(-mw/2, -mh/2 - 10, -mw/2, mh/2 - 10);
        sg.addColorStop(0, 'rgba(0,212,255,0.25)');
        sg.addColorStop(1, 'rgba(124,58,237,0.12)');
        ctx.fillStyle = sg;
        ctx.fillRect(-mw/2, -mh/2 - 10, mw, mh);
        ctx.restore();

        // Windows 4-square grid
        ctx.fillStyle = color;
        const wl = 5, wg = 1;
        ctx.fillRect(-wl - wg/2, -wl - wg/2 - 10 + wl, wl, wl);
        ctx.fillRect( wg/2,      -wl - wg/2 - 10 + wl, wl, wl);
        ctx.fillRect(-wl - wg/2,  wg/2       - 10 + wl, wl, wl);
        ctx.fillRect( wg/2,       wg/2       - 10 + wl, wl, wl);

        // Stand neck & base
        ctx.fillStyle = '#2A3550';
        ctx.beginPath();
        ctx.roundRect(-2, mh/2 - 10, 4, 8, 1);
        ctx.fill();
        ctx.beginPath();
        ctx.roundRect(-10, mh/2 - 10 + 7, 20, 4, 2);
        ctx.fill();
    }

    function drawMac(color, glow) {
        const lw = 42, lh = 28, br = 3;
        ctx.beginPath();
        ctx.roundRect(-lw/2, -lh/2 - 6, lw, lh, br);
        ctx.fillStyle   = '#161d2d';
        ctx.strokeStyle = color;
        ctx.lineWidth   = 1.5;
        ctx.fill(); ctx.stroke();

        ctx.save();
        const clip = new Path2D();
        clip.roundRect(-lw/2 + 3, -lh/2 - 6 + 3, lw - 6, lh - 6, 1);
        ctx.clip(clip);
        const sg = ctx.createLinearGradient(0, -lh/2 - 6, 0, lh/2 - 6);
        sg.addColorStop(0, 'rgba(0,212,255,0.22)');
        sg.addColorStop(1, 'rgba(124,58,237,0.10)');
        ctx.fillStyle = sg;
        ctx.fillRect(-lw/2, -lh/2 - 6, lw, lh);
        ctx.restore();

        // Apple logo
        ctx.beginPath();
        ctx.arc(0, -6, 4, 0, Math.PI * 2);
        ctx.fillStyle = color;
        ctx.fill();

        // Keyboard deck
        ctx.beginPath();
        ctx.roundRect(-lw/2, lh/2 - 6, lw, 8, [0, 0, 2, 2]);
        ctx.fillStyle = '#0f1422';
        ctx.strokeStyle = color;
        ctx.lineWidth = 1;
        ctx.fill(); ctx.stroke();
    }

    function drawLinux(color, glow) {
        const w = 30, h = 40;
        ctx.beginPath();
        ctx.roundRect(-w/2, -h/2, w, h, 4);
        ctx.fillStyle   = '#0a101d';
        ctx.strokeStyle = color;
        ctx.lineWidth   = 1.5;
        ctx.fill(); ctx.stroke();

        // 3D side face
        ctx.beginPath();
        ctx.moveTo(w/2, -h/2);
        ctx.lineTo(w/2 + 8, -h/2 - 6);
        ctx.lineTo(w/2 + 8, h/2 - 6);
        ctx.lineTo(w/2,     h/2);
        ctx.closePath();
        ctx.fillStyle   = 'rgba(0,212,255,0.08)';
        ctx.strokeStyle = 'rgba(0,212,255,0.20)';
        ctx.fill(); ctx.stroke();

        // Top face
        ctx.beginPath();
        ctx.moveTo(-w/2, -h/2);
        ctx.lineTo(-w/2 + 8, -h/2 - 6);
        ctx.lineTo( w/2 + 8, -h/2 - 6);
        ctx.lineTo( w/2,     -h/2);
        ctx.closePath();
        ctx.fillStyle = 'rgba(0,212,255,0.15)';
        ctx.fill(); ctx.stroke();

        // Terminal prompt
        ctx.fillStyle = '#00ff88';
        ctx.font      = 'bold 7px monospace';
        ctx.textAlign = 'left';
        ctx.fillText('$_', -w/2 + 6, h/2 - 8);
    }

    function drawRaspi(color, glow) {
        const bw = 36, bh = 28;
        ctx.beginPath();
        ctx.roundRect(-bw/2, -bh/2, bw, bh, 3);
        ctx.fillStyle   = '#180a2b';
        ctx.strokeStyle = '#8b5cf6';
        ctx.lineWidth   = 1.5;
        ctx.fill(); ctx.stroke();

        // CPU chip
        ctx.beginPath();
        ctx.roundRect(-7, -7, 14, 14, 2);
        ctx.fillStyle   = '#2d1558';
        ctx.strokeStyle = color;
        ctx.lineWidth   = 1;
        ctx.fill(); ctx.stroke();

        // Status LED
        ctx.beginPath();
        ctx.arc(bw/2 - 4, bh/2 - 4, 2.5, 0, Math.PI * 2);
        ctx.fillStyle = PAL.healthy;
        ctx.fill();
    }

    function drawGenericServer(color, glow) {
        const sw = 44, sh = 18;
        for (let u = 0; u < 2; u++) {
            const yOff = u * (sh + 4) - sh / 2 - 2;
            ctx.beginPath();
            ctx.roundRect(-sw/2, yOff, sw, sh, 2);
            ctx.fillStyle   = '#0a1424';
            ctx.strokeStyle = color;
            ctx.lineWidth   = 1.2;
            ctx.fill(); ctx.stroke();

            // LED indicators
            [PAL.healthy, PAL.accent, PAL.accent].forEach((lc, li) => {
                ctx.beginPath();
                ctx.arc(-sw/2 + 6 + li * 8, yOff + sh / 2, 2.5, 0, Math.PI * 2);
                ctx.fillStyle = lc;
                ctx.fill();
            });
        }
    }

    /** Real 3D Server Rack Cabinet (Not a circle!) */
    function drawServerRack(x, y, glowAlpha) {
        ctx.save();
        ctx.translate(x, y);

        const rw = CFG.SERVER_W;
        const rh = CFG.SERVER_H;

        // Ground shadow
        ctx.beginPath();
        ctx.ellipse(0, rh/2 + 6, rw/2 * 0.9, 8, 0, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(0,0,0,0.55)';
        ctx.fill();

        // Cabinet front face
        ctx.beginPath();
        ctx.roundRect(-rw/2, -rh/2, rw, rh, [4, 4, 2, 2]);
        ctx.fillStyle   = '#070d1a';
        ctx.strokeStyle = `rgba(0,212,255,${0.45 + glowAlpha * 0.35})`;
        ctx.lineWidth   = 2;
        ctx.fill(); ctx.stroke();

        // 3D side depth face
        ctx.beginPath();
        ctx.moveTo(rw/2,      -rh/2);
        ctx.lineTo(rw/2 + 16, -rh/2 - 12);
        ctx.lineTo(rw/2 + 16,  rh/2 - 12);
        ctx.lineTo(rw/2,       rh/2);
        ctx.closePath();
        ctx.fillStyle   = 'rgba(0,212,255,0.08)';
        ctx.strokeStyle = 'rgba(0,212,255,0.25)';
        ctx.lineWidth   = 1;
        ctx.fill(); ctx.stroke();

        // 3D top face
        ctx.beginPath();
        ctx.moveTo(-rw/2,      -rh/2);
        ctx.lineTo(-rw/2 + 16, -rh/2 - 12);
        ctx.lineTo( rw/2 + 16, -rh/2 - 12);
        ctx.lineTo( rw/2,      -rh/2);
        ctx.closePath();
        ctx.fillStyle   = 'rgba(0,212,255,0.16)';
        ctx.strokeStyle = 'rgba(0,212,255,0.35)';
        ctx.fill(); ctx.stroke();

        // 7 Rack Unit shelves with LEDs & Drive Bays
        for (let u = 0; u < 7; u++) {
            const uy = -rh/2 + 6 + u * 11.5;
            ctx.beginPath();
            ctx.roundRect(-rw/2 + 4, uy, rw - 8, 9.5, 1);
            ctx.fillStyle   = '#0c1626';
            ctx.strokeStyle = 'rgba(0,212,255,0.2)';
            ctx.lineWidth   = 0.6;
            ctx.fill(); ctx.stroke();

            // Activity LED
            const ledCol = u < 4 ? PAL.healthy : (u < 6 ? PAL.accent : PAL.degraded);
            ctx.beginPath();
            ctx.arc(-rw/2 + 9, uy + 4.8, 2.2, 0, Math.PI * 2);
            ctx.fillStyle = ledCol;
            ctx.shadowColor = ledCol;
            ctx.shadowBlur  = 6;
            ctx.fill();
            ctx.shadowBlur  = 0;

            // Reading drive bars
            const barW = (rw - 24) * (0.35 + 0.45 * Math.abs(Math.sin(spinAngle * 2.5 + u)));
            ctx.beginPath();
            ctx.roundRect(-rw/2 + 15, uy + 2.5, barW, 4, 1);
            ctx.fillStyle = 'rgba(0,212,255,0.22)';
            ctx.fill();
        }

        // Top cooling fan
        const fanR = 8;
        const fanX = rw/2 - 12;
        const fanY = -rh/2 + 12;
        ctx.save();
        ctx.translate(fanX, fanY);
        ctx.rotate(spinAngle * 4);
        for (let b = 0; b < 4; b++) {
            ctx.rotate(Math.PI / 2);
            ctx.beginPath();
            ctx.ellipse(0, -fanR/2, 2, fanR/2, 0, 0, Math.PI);
            ctx.fillStyle = 'rgba(0,212,255,0.45)';
            ctx.fill();
        }
        ctx.restore();

        // Controller Badge
        ctx.fillStyle = '#00d4ff';
        ctx.font      = 'bold 8px sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText('CONTROL PLANE', 0, rh/2 + 16);
        ctx.font      = '7px monospace';
        ctx.fillStyle = 'rgba(255,255,255,0.6)';
        ctx.fillText('Raft Leader :50051', 0, rh/2 + 26);

        // Ripples when absorbing results
        serverRipples.forEach(rip => {
            ctx.beginPath();
            ctx.arc(0, 0, rip.r, 0, Math.PI * 2);
            ctx.strokeStyle = `rgba(0,255,136,${rip.alpha})`;
            ctx.lineWidth   = 2;
            ctx.stroke();
        });

        ctx.restore();
    }

    function drawDevice(type, color, glow) {
        ctx.save();
        switch (type) {
            case 'windows': drawWindows(color, glow); break;
            case 'mac':     drawMac(color, glow);     break;
            case 'linux':   drawLinux(color, glow);   break;
            case 'raspi':   drawRaspi(color, glow);   break;
            default:        drawGenericServer(color, glow); break;
        }
        ctx.restore();
    }

    /* ─── Task Dispatcher API ─────────────────────────────────── */

    function dispatchTask(task) {
        const taskId = task.id || task.taskId || 'T-' + Date.now().toString(36);
        const alive = realNodes.filter(rn => !isNodeDead(rn));
        if (alive.length === 0) return;

        const targetNode = alive[Math.floor(Math.random() * alive.length)];
        activeTasks.push({
            taskId: taskId,
            nodeId: targetNode.id,
            node: targetNode,
            phase: 'DISPATCH', // DISPATCH -> PROCESSING -> RETURN
            t: 0,
            label: task.label || `Task #${taskId.slice(-4)}`,
            speed: 0.016
        });
    }

    /* ─── Main Render Loop ────────────────────────────────────── */

    function render(ts) {
        if (!running) return;
        const dt = ts - lastTs;
        if (dt < 1000 / CFG.FPS) { raf = requestAnimationFrame(render); return; }
        lastTs = ts;
        spinAngle += 0.005;

        const px = (mouse.x / W - 0.5) * CFG.PARALLAX * W;
        const py = (mouse.y / H - 0.5) * CFG.PARALLAX * H;

        ctx.clearRect(0, 0, W, H);

        /* ── 1. Starfield ── */
        stars.forEach(s => {
            s.x += s.speed;
            if (s.x > 1) s.x = 0;
            const sx = (s.x * W + px * 0.3) % W;
            const sy = (s.y * H + py * 0.3) % H;
            ctx.beginPath();
            ctx.arc(sx, sy, s.r, 0, Math.PI * 2);
            ctx.fillStyle = PAL.star + s.a + ')';
            ctx.fill();
        });

        /* ── 2. Update Node Positions ── */
        realNodes.forEach(rn => {
            rn.x = lerp(rn.x, rn.tx + px, 0.05);
            rn.y = lerp(rn.y, rn.ty + py, 0.05);
        });
        const sx = serverPos.x + px;
        const sy = serverPos.y + py;

        /* ── 3. Draw Connection Lines (Accurate Idle vs Dead) ── */
        realNodes.forEach(rn => {
            const dead = isNodeDead(rn);
            if (dead) {
                // SEVERED BROKEN RED CONNECTION
                ctx.save();
                ctx.setLineDash([6, 6]);
                ctx.beginPath();
                ctx.moveTo(sx, sy);
                const mx = (sx + rn.x) / 2;
                const my = (sy + rn.y) / 2;
                ctx.lineTo(mx, my);
                ctx.strokeStyle = 'rgba(255, 51, 80, 0.45)';
                ctx.lineWidth   = 1.5;
                ctx.stroke();

                // Severed mark
                ctx.setLineDash([]);
                ctx.fillStyle = '#ff3350';
                ctx.font      = 'bold 11px monospace';
                ctx.textAlign = 'center';
                ctx.fillText('✕ SEVERED', mx, my - 6);
                ctx.restore();
            } else {
                // CLEAN IDLE CONNECTION LINE (No constant pulse spam!)
                ctx.beginPath();
                ctx.moveTo(sx, sy);
                ctx.lineTo(rn.x, rn.y);
                ctx.strokeStyle = 'rgba(0, 212, 255, 0.22)';
                ctx.lineWidth   = 1.2 * rn.depth;
                ctx.stroke();
            }
        });

        /* ── 4. Task Lifecycle Execution & Packet Animations ── */
        activeTasks = activeTasks.filter(task => {
            let rn = realNodes.find(n => n.id === task.nodeId) || task.node;

            // Automatic Task Failover: if assigned node is dead, reroute to a healthy survivor!
            if (!rn || isNodeDead(rn)) {
                const survivors = realNodes.filter(n => !isNodeDead(n));
                if (survivors.length === 0) return false;
                rn = survivors[0];
                task.nodeId = rn.id;
                task.node = rn;
                task.phase = 'MIGRATING';
                task.t = 0;
            }

            if (task.phase === 'DISPATCH') {
                // SERVER -> NODE PACKET MOVEMENT
                task.t += task.speed;
                const px = lerp(sx, rn.x, task.t);
                const py = lerp(sy, rn.y, task.t);

                ctx.save();
                ctx.fillStyle   = '#00d4ff';
                ctx.shadowColor = '#00d4ff';
                ctx.shadowBlur  = 14;
                ctx.beginPath();
                ctx.arc(px, py, 5 * rn.depth, 0, Math.PI * 2);
                ctx.fill();

                // Packet floating tag
                ctx.shadowBlur = 0;
                ctx.fillStyle  = '#ffffff';
                ctx.font       = 'bold 8px monospace';
                ctx.textAlign  = 'center';
                ctx.fillText(task.label, px, py - 8);
                ctx.restore();

                if (task.t >= 1) {
                    task.phase = 'PROCESSING';
                    task.t = 0;
                    rn.isProcessing = true;
                }
                return true;

            } else if (task.phase === 'MIGRATING') {
                // FAILOVER REROUTING TO SURVIVOR
                task.t += task.speed * 1.5;
                const px = lerp(sx, rn.x, task.t);
                const py = lerp(sy, rn.y, task.t);

                ctx.save();
                ctx.fillStyle   = '#ffb700'; // Amber failover pulse
                ctx.shadowColor = '#ffb700';
                ctx.shadowBlur  = 16;
                ctx.beginPath();
                ctx.arc(px, py, 6.5 * rn.depth, 0, Math.PI * 2);
                ctx.fill();

                ctx.shadowBlur = 0;
                ctx.fillStyle  = '#ffb700';
                ctx.font       = 'bold 8.5px monospace';
                ctx.textAlign  = 'center';
                ctx.fillText(`⚡ FAILOVER ➔ ${rn.name || rn.id}`, px, py - 9);
                ctx.restore();

                if (task.t >= 1) {
                    task.phase = 'PROCESSING';
                    task.t = 0;
                    rn.isProcessing = true;
                }
                return true;

            } else if (task.phase === 'PROCESSING') {
                // JOB PROCESSING IN NODE
                task.t += 0.012; // ~2 seconds processing simulation
                const pct = Math.min(100, Math.round(task.t * 100));

                // Draw spinning computing ring around node
                ctx.save();
                ctx.translate(rn.x, rn.y);
                ctx.rotate(spinAngle * 6);
                ctx.beginPath();
                ctx.arc(0, 0, 36 * rn.scale, 0, Math.PI * 1.5);
                ctx.strokeStyle = '#ffb700';
                ctx.lineWidth   = 2.5;
                ctx.shadowColor = '#ffb700';
                ctx.shadowBlur  = 10;
                ctx.stroke();
                ctx.restore();

                // Compute Badge above node
                ctx.save();
                ctx.fillStyle = '#ffb700';
                ctx.font      = 'bold 9px monospace';
                ctx.textAlign = 'center';
                ctx.fillText(`⚡ COMPUTING ${pct}%`, rn.x, rn.y - 36 * rn.scale);
                ctx.restore();

                if (task.t >= 1) {
                    task.phase = 'RETURN';
                    task.t = 0;
                    rn.isProcessing = false;
                }
                return true;

            } else if (task.phase === 'RETURN') {
                // NODE -> SERVER RESULT PACKET
                task.t += task.speed * 1.2;
                const px = lerp(rn.x, sx, task.t);
                const py = lerp(rn.y, sy, task.t);

                ctx.save();
                ctx.fillStyle   = '#00ff88';
                ctx.shadowColor = '#00ff88';
                ctx.shadowBlur  = 16;
                ctx.beginPath();
                ctx.arc(px, py, 6 * rn.depth, 0, Math.PI * 2);
                ctx.fill();

                // Result label
                ctx.shadowBlur = 0;
                ctx.fillStyle  = '#00ff88';
                ctx.font       = 'bold 8px monospace';
                ctx.textAlign  = 'center';
                ctx.fillText('✔ RESULT DONE', px, py - 8);
                ctx.restore();

                if (task.t >= 1) {
                    // Server absorbs result with ripple!
                    serverRipples.push({ r: 10, alpha: 0.9 });
                    return false; // Task completed!
                }
                return true;
            }
            return false;
        });

        // Server absorption ripples
        serverRipples = serverRipples.filter(rip => {
            rip.r += 2.5;
            rip.alpha -= 0.03;
            return rip.alpha > 0;
        });

        /* ── 5. Central 3D Server Rack ── */
        drawServerRack(sx, sy, 0.7 + 0.3 * Math.sin(spinAngle * 2));

        /* ── 6. Nodes & Telemetry Details ── */
        const sorted = [...realNodes].sort((a, b) => a.depth - b.depth);
        sorted.forEach(rn => {
            const dead = isNodeDead(rn);
            const color = dead ? PAL.offline : (rn.isProcessing ? PAL.degraded : PAL.healthy);
            const sc = rn.scale;

            ctx.save();
            ctx.translate(rn.x, rn.y);
            ctx.scale(sc, sc);

            // Device shadow
            ctx.beginPath();
            ctx.ellipse(0, CFG.NODE_ICON_H / 2 + 4, CFG.NODE_ICON_W * 0.45, 5, 0, 0, Math.PI * 2);
            ctx.fillStyle = 'rgba(0,0,0,0.35)';
            ctx.fill();

            // Draw device
            drawDevice(rn.type, color, !dead);

            // Node Name & IP Labels
            ctx.restore();
            const labelY = rn.y + CFG.NODE_ICON_H * sc * 0.55 + 14;

            ctx.save();
            ctx.fillStyle = color;
            ctx.font      = `bold ${Math.round(11 * sc)}px sans-serif`;
            ctx.textAlign = 'center';
            ctx.fillText(rn.name || rn.hostname || 'Node', rn.x, labelY);

            // IP & Status
            ctx.fillStyle = dead ? '#ff3366' : 'rgba(255,255,255,0.7)';
            ctx.font      = `${Math.round(9 * sc)}px monospace`;
            ctx.fillText(dead ? '⚠ DEAD (UNREACHABLE)' : (rn.ip || '127.0.0.1'), rn.x, labelY + 12);

            // Mini CPU / RAM load bars if alive
            if (!dead) {
                const barW = 44 * sc;
                const cpuVal = rn.cpuUsage || (rn.isProcessing ? 78 : 12);
                ctx.fillStyle = 'rgba(255,255,255,0.15)';
                ctx.fillRect(rn.x - barW/2, labelY + 18, barW, 3.5);
                ctx.fillStyle = rn.isProcessing ? '#ffb700' : '#00d4ff';
                ctx.fillRect(rn.x - barW/2, labelY + 18, barW * (cpuVal / 100), 3.5);
            }
            ctx.restore();
        });

        raf = requestAnimationFrame(render);
    }

    /* ─── Resize & Mount ──────────────────────────────────────── */

    function resize() {
        dpr           = window.devicePixelRatio || 1;
        W             = window.innerWidth;
        H             = window.innerHeight;
        canvas.width  = W * dpr;
        canvas.height = H * dpr;
        canvas.style.width  = W + 'px';
        canvas.style.height = H + 'px';
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

        serverPos = { x: W / 2, y: H / 2 };
        buildStars();
        buildAmbients();
        placeNodes();
    }

    function updateNodes(nodes) {
        if (!canvas) return;
        const arr = (nodes || []).slice(0, 20);

        const next = arr.map((n, i) => {
            const existing = realNodes.find(r => r.id === n.id);
            return Object.assign(existing || {}, {
                id:       n.id || `node-${i}`,
                name:     n.name || n.hostname || n.id || `Node-${i}`,
                status:   n.status || 'ALIVE',
                ip:       n.ip || '127.0.0.1',
                cpuUsage: n.cpuUsage || (n.isProcessing ? 75 : 14),
                memoryUsage: n.memoryUsage || 38,
                os:       n.os || n.platform || n.operatingSystem || '',
                type:     deviceType(n),
                x:        existing ? existing.x : serverPos.x,
                y:        existing ? existing.y : serverPos.y,
                tx: 0, ty: 0, depth: 1, scale: 1,
            });
        });

        realNodes = next;
        placeNodes();
    }

    function mount() {
        if (canvas) return;

        canvas = document.createElement('canvas');
        canvas.id                  = 'ng-net-canvas';
        canvas.style.position      = 'fixed';
        canvas.style.inset         = '0';
        canvas.style.pointerEvents = 'none';
        canvas.style.zIndex        = '0';
        canvas.style.opacity       = '0.75';
        document.body.insertBefore(canvas, document.body.firstChild);

        ctx = canvas.getContext('2d');
        resize();

        window.addEventListener('resize', resize);
        window.addEventListener('mousemove', e => { mouse.x = e.clientX; mouse.y = e.clientY; });

        running = true;
        raf     = requestAnimationFrame(render);
    }

    function destroy() {
        running = false;
        cancelAnimationFrame(raf);
        window.removeEventListener('resize', resize);
        if (canvas) { canvas.remove(); canvas = null; }
    }

    NG.networkCanvas = { mount, destroy, updateNodes, dispatchTask };
})();
