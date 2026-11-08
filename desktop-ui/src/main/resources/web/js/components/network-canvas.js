/**
 * network-canvas.js — 3D Distributed Cluster Visualiser
 *
 * Renders a full-screen, depth-perspective scene behind the entire app:
 *   • A central "server rack" model in the middle
 *   • Compute nodes arranged in an ellipse, each drawn as its device type
 *     (Windows PC, MacBook, Linux terminal, Raspberry Pi, generic server)
 *   • Animated data-packet pulses traveling along edges
 *   • Live status colours (healthy=cyan-green, offline=red, degraded=amber)
 *   • Ambient star-field + floating particle mesh for depth
 *   • Subtle mouse-parallax on the whole scene
 *
 * Public API:
 *   NG.networkCanvas.mount()              — create canvas & start loop
 *   NG.networkCanvas.destroy()            — stop loop & remove canvas
 *   NG.networkCanvas.updateNodes(nodes)   — push live NodeDto array from SSE
 */
(function () {
    'use strict';

    window.NG = window.NG || {};

    /* ─── Config ─────────────────────────────────────────────── */
    const CFG = {
        FPS:            60,
        ORBIT_RX:       0.30,   // fraction of W  for node ellipse semi-axis X
        ORBIT_RY:       0.26,   // fraction of H  for node ellipse semi-axis Y
        NODE_ICON_W:    52,
        NODE_ICON_H:    44,
        SERVER_W:       68,
        SERVER_H:       90,
        PULSE_SPEED:    2.2,
        PULSE_RADIUS:   4.5,
        PULSE_INTERVAL: 1800,   // ms between new pulse spawns
        STAR_COUNT:     90,
        PARALLAX:       0.008,
        AMBIENT_COUNT:  18,
        AMBIENT_SPEED:  0.28,
        GLOW_LAYERS:    3,
    };

    /* ─── State ───────────────────────────────────────────────── */
    let canvas, ctx, W, H, dpr;
    let stars         = [];
    let ambients      = [];
    let realNodes     = [];     // processed from SSE NodeDto
    let pulses        = [];     // { from, to, t, color, w }
    let mouse         = { x: 0, y: 0 };
    let raf           = 0;
    let lastTs        = 0;
    let nextPulse     = 0;
    let serverPos     = { x: 0, y: 0 };
    let running       = false;
    let spinAngle     = 0;      // slow server rotation indicator

    /* ─── Helpers ─────────────────────────────────────────────── */
    const rand  = (a, b) => a + Math.random() * (b - a);
    const lerp  = (a, b, t) => a + (b - a) * t;
    const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

    function deviceType(node) {
        const os = (node.os || node.platform || node.operatingSystem || '').toLowerCase();
        const name = (node.name || '').toLowerCase();
        if (os.includes('win') || name.includes('win'))   return 'windows';
        if (os.includes('mac') || name.includes('mac'))   return 'mac';
        if (os.includes('raspb') || name.includes('pi'))  return 'raspi';
        if (os.includes('linux') || name.includes('linux') || name.includes('ubuntu') || name.includes('debian')) return 'linux';
        if (os.includes('freebsd') || os.includes('bsd')) return 'linux';
        return 'server';   // generic / unknown
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
        edge:      'rgba(0,212,255,',
        edge2:     'rgba(124,58,237,',
        ambient:   'rgba(0,212,255,',
    };

    function nodeColor(n) {
        const s = (n.status || '').toUpperCase();
        if (s === 'HEALTHY' || s === 'ONLINE')  return PAL.healthy;
        if (s === 'OFFLINE')                     return PAL.offline;
        if (s === 'DEGRADED')                    return PAL.degraded;
        return PAL.unknown;
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
        const n  = Math.max(1, realNodes.length);

        realNodes.forEach((rn, i) => {
            const angle = (i / n) * Math.PI * 2 - Math.PI / 2;
            // Depth factor: nodes at top/bottom get a slight z compression
            const depthFactor = 0.72 + 0.28 * Math.sin(angle + Math.PI / 2);

            rn.tx = cx + Math.cos(angle) * rx;
            rn.ty = cy + Math.sin(angle) * ry * 0.80;   // flatten Y a bit for 3D feel
            rn.depth  = depthFactor;   // 0.7 (far) … 1.0 (close)
            rn.scale  = 0.72 + 0.28 * depthFactor;

            // Animate toward target
            if (rn.x === undefined) { rn.x = rn.tx; rn.y = rn.ty; }
            rn.type = deviceType(rn);
        });
    }

    /* ─── Drawing primitives ──────────────────────────────────── */

    /** Draw a glowing circle */
    function drawGlow(x, y, r, color, alpha) {
        const g = ctx.createRadialGradient(x, y, 0, x, y, r * 2.5);
        g.addColorStop(0,   color.replace(')', `,${alpha})`).replace('rgb', 'rgba'));
        g.addColorStop(0.5, color.replace(')', `,${alpha * 0.4})`).replace('rgb', 'rgba'));
        g.addColorStop(1,   color.replace(')', ',0)').replace('rgb', 'rgba'));
        ctx.beginPath();
        ctx.arc(x, y, r * 2.5, 0, Math.PI * 2);
        ctx.fillStyle = g;
        ctx.fill();
    }

    /** glowing connection edge with gradient */
    function drawEdge(x1, y1, x2, y2, color, alpha, width) {
        const g = ctx.createLinearGradient(x1, y1, x2, y2);
        g.addColorStop(0,   `${color.slice(0, -1)},${alpha})`);
        g.addColorStop(0.5, `${color.slice(0, -1)},${alpha * 0.6})`);
        g.addColorStop(1,   `${color.slice(0, -1)},${alpha})`);
        ctx.beginPath();
        ctx.moveTo(x1, y1);
        ctx.lineTo(x2, y2);
        ctx.strokeStyle = g;
        ctx.lineWidth   = width;
        ctx.stroke();
    }

    /** Draw data-packet pulse dot */
    function drawPulse(p) {
        const fx = lerp(p.from.x, p.to.x, p.t);
        const fy = lerp(p.from.y, p.to.y, p.t);
        const alpha = 1 - Math.pow(Math.abs(p.t - 0.5) * 2, 2);

        ctx.save();
        ctx.shadowColor = p.color;
        ctx.shadowBlur  = 12;
        ctx.beginPath();
        ctx.arc(fx, fy, p.w * alpha, 0, Math.PI * 2);
        ctx.fillStyle = p.color;
        ctx.globalAlpha = alpha;
        ctx.fill();
        ctx.restore();
    }

    /* ─── Device pictograms ───────────────────────────────────── */

    /**
     * Draw a pictorial 3D-ish device model.
     * Each device is drawn in a local coordinate system centred at (0,0),
     * then the caller applies ctx.translate + ctx.scale.
     */

    function drawWindows(color, glow) {
        // Monitor + stand
        const mw = 38, mh = 26, br = 3;
        // Screen bezel
        ctx.beginPath();
        ctx.roundRect(-mw/2, -mh/2 - 10, mw, mh, br);
        ctx.fillStyle   = '#1A2540';
        ctx.strokeStyle = color;
        ctx.lineWidth   = 1.5;
        ctx.fill();
        ctx.stroke();
        // Screen glow
        ctx.save();
        ctx.clip();
        const sg = ctx.createLinearGradient(-mw/2, -mh/2 - 10, -mw/2, mh/2 - 10);
        sg.addColorStop(0,   'rgba(0,212,255,0.22)');
        sg.addColorStop(1,   'rgba(124,58,237,0.10)');
        ctx.fillStyle = sg;
        ctx.fillRect(-mw/2, -mh/2 - 10, mw, mh);
        ctx.restore();
        // Windows logo
        ctx.fillStyle = color;
        const wl = 5, wg = 1;
        ctx.fillRect(-wl - wg/2, -wl - wg/2 - 10 + wl, wl, wl);
        ctx.fillRect( wg/2,      -wl - wg/2 - 10 + wl, wl, wl);
        ctx.fillRect(-wl - wg/2,  wg/2       - 10 + wl, wl, wl);
        ctx.fillRect( wg/2,       wg/2       - 10 + wl, wl, wl);
        // Stand neck
        ctx.fillStyle   = '#2A3550';
        ctx.strokeStyle = 'rgba(0,212,255,0.2)';
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.roundRect(-2, mh/2 - 10, 4, 8, 1);
        ctx.fill(); ctx.stroke();
        // Base
        ctx.beginPath();
        ctx.roundRect(-10, mh/2 - 10 + 7, 20, 4, 2);
        ctx.fill(); ctx.stroke();
    }

    function drawMac(color, glow) {
        // MacBook lid
        const lw = 40, lh = 26, br = 3;
        // Lid frame
        ctx.beginPath();
        ctx.roundRect(-lw/2, -lh/2 - 6, lw, lh, br);
        ctx.fillStyle   = '#1C2030';
        ctx.strokeStyle = color;
        ctx.lineWidth   = 1.5;
        ctx.fill(); ctx.stroke();
        // Screen
        ctx.save();
        const clip = new Path2D();
        clip.roundRect(-lw/2 + 3, -lh/2 - 6 + 3, lw - 6, lh - 6, 1);
        ctx.clip(clip);
        const sg = ctx.createLinearGradient(0, -lh/2 - 6, 0, lh/2 - 6);
        sg.addColorStop(0, 'rgba(0,212,255,0.18)');
        sg.addColorStop(1, 'rgba(124,58,237,0.08)');
        ctx.fillStyle = sg;
        ctx.fillRect(-lw/2, -lh/2 - 6, lw, lh);
        ctx.restore();
        // Apple logo (circle + leaf)
        ctx.save();
        ctx.strokeStyle = color;
        ctx.lineWidth   = 1;
        ctx.beginPath();
        ctx.arc(0, -6, 4, 0, Math.PI * 2);
        ctx.stroke();
        ctx.restore();
        // Keyboard base
        ctx.beginPath();
        ctx.roundRect(-lw/2, lh/2 - 6, lw, 8, [0, 0, 2, 2]);
        ctx.fillStyle   = '#141828';
        ctx.strokeStyle = color;
        ctx.lineWidth   = 1;
        ctx.fill(); ctx.stroke();
        // Trackpad hint
        ctx.beginPath();
        ctx.roundRect(-7, lh/2 - 6 + 2, 14, 4, 1);
        ctx.fillStyle   = 'rgba(0,212,255,0.15)';
        ctx.strokeStyle = 'rgba(0,212,255,0.25)';
        ctx.lineWidth   = 0.5;
        ctx.fill(); ctx.stroke();
    }

    function drawLinux(color, glow) {
        // Terminal / tower PC
        const w = 28, h = 38;
        // Tower body
        ctx.beginPath();
        ctx.roundRect(-w/2, -h/2, w, h, 4);
        ctx.fillStyle   = '#0D1520';
        ctx.strokeStyle = color;
        ctx.lineWidth   = 1.5;
        ctx.fill(); ctx.stroke();
        // 3D side face (depth effect)
        ctx.beginPath();
        ctx.moveTo(w/2, -h/2);
        ctx.lineTo(w/2 + 8, -h/2 - 6);
        ctx.lineTo(w/2 + 8, h/2 - 6);
        ctx.lineTo(w/2,     h/2);
        ctx.closePath();
        ctx.fillStyle   = 'rgba(0,212,255,0.07)';
        ctx.strokeStyle = 'rgba(0,212,255,0.15)';
        ctx.lineWidth   = 0.8;
        ctx.fill(); ctx.stroke();
        // Top face
        ctx.beginPath();
        ctx.moveTo(-w/2, -h/2);
        ctx.lineTo(-w/2 + 8, -h/2 - 6);
        ctx.lineTo( w/2 + 8, -h/2 - 6);
        ctx.lineTo( w/2,     -h/2);
        ctx.closePath();
        ctx.fillStyle   = 'rgba(0,212,255,0.12)';
        ctx.stroke(); ctx.fill();
        // LED strip
        ctx.beginPath();
        ctx.roundRect(-w/2 + 4, -h/2 + 6, w - 8, 4, 1);
        ctx.fillStyle = glow ? color : 'rgba(0,212,255,0.3)';
        ctx.fill();
        // Terminal prompt
        ctx.fillStyle = 'rgba(0,255,136,0.7)';
        ctx.font      = 'bold 7px monospace';
        ctx.textAlign = 'left';
        ctx.fillText('$_', -w/2 + 5, h/2 - 6);
    }

    function drawRaspi(color, glow) {
        // Raspberry Pi board (top-down view, tilted)
        const bw = 34, bh = 28;
        // Board
        ctx.beginPath();
        ctx.roundRect(-bw/2, -bh/2, bw, bh, 3);
        ctx.fillStyle   = '#1A0A2E';   // dark purple board
        ctx.strokeStyle = '#7C3AED';
        ctx.lineWidth   = 1.5;
        ctx.fill(); ctx.stroke();
        // GPIO pins (top)
        for (let i = 0; i < 8; i++) {
            ctx.beginPath();
            ctx.rect(-bw/2 + 3 + i * 4, -bh/2 - 3, 2, 3);
            ctx.fillStyle = '#9CA3AF';
            ctx.fill();
        }
        // CPU chip
        ctx.beginPath();
        ctx.roundRect(-7, -7, 14, 14, 2);
        ctx.fillStyle   = '#2D1B69';
        ctx.strokeStyle = color;
        ctx.lineWidth   = 1;
        ctx.fill(); ctx.stroke();
        // Raspberry logo dots
        ctx.fillStyle = color;
        [[0, -2], [-3, 2], [3, 2]].forEach(([dx, dy]) => {
            ctx.beginPath();
            ctx.arc(dx, dy, 1.5, 0, Math.PI * 2);
            ctx.fill();
        });
        // USB ports (right side)
        for (let i = 0; i < 2; i++) {
            ctx.beginPath();
            ctx.rect(bw/2, -6 + i * 8, 4, 5);
            ctx.fillStyle   = '#374151';
            ctx.strokeStyle = 'rgba(156,163,175,0.4)';
            ctx.lineWidth   = 0.5;
            ctx.fill(); ctx.stroke();
        }
        // Status LED
        ctx.beginPath();
        ctx.arc(bw/2 - 4, bh/2 - 4, 2, 0, Math.PI * 2);
        ctx.fillStyle = glow ? PAL.healthy : 'rgba(0,204,106,0.3)';
        if (glow) {
            ctx.shadowColor = PAL.healthy;
            ctx.shadowBlur  = 6;
        }
        ctx.fill();
        ctx.shadowBlur = 0;
    }

    function drawGenericServer(color, glow) {
        // 1U rack server (front view)
        const sw = 44, sh = 18, br = 2;
        for (let u = 0; u < 2; u++) {
            const yOff = u * (sh + 4) - sh / 2 - 2;
            ctx.beginPath();
            ctx.roundRect(-sw/2, yOff, sw, sh, br);
            ctx.fillStyle   = '#0D1828';
            ctx.strokeStyle = color;
            ctx.lineWidth   = 1;
            ctx.fill(); ctx.stroke();
            // 3D top edge
            ctx.beginPath();
            ctx.moveTo(-sw/2, yOff);
            ctx.lineTo(-sw/2 + 6, yOff - 4);
            ctx.lineTo( sw/2 + 6, yOff - 4);
            ctx.lineTo( sw/2,     yOff);
            ctx.fillStyle = 'rgba(0,212,255,0.08)';
            ctx.fill();
            // LED indicators
            const ledColors = [PAL.healthy, PAL.accent, PAL.accent];
            ledColors.forEach((lc, li) => {
                ctx.beginPath();
                ctx.arc(-sw/2 + 6 + li * 8, yOff + sh / 2, 2.5, 0, Math.PI * 2);
                ctx.fillStyle = lc;
                if (glow) { ctx.shadowColor = lc; ctx.shadowBlur = 5; }
                ctx.fill();
                ctx.shadowBlur = 0;
            });
            // Drive bays
            for (let d = 0; d < 3; d++) {
                ctx.beginPath();
                ctx.roundRect(sw/2 - 16 + d * 5.5, yOff + 3, 4, sh - 6, 1);
                ctx.fillStyle   = '#1E2D44';
                ctx.strokeStyle = 'rgba(0,212,255,0.2)';
                ctx.lineWidth   = 0.5;
                ctx.fill(); ctx.stroke();
            }
        }
    }

    /** Central server rack */
    function drawServerRack(x, y, glowAlpha) {
        ctx.save();
        ctx.translate(x, y);

        const rw = CFG.SERVER_W;
        const rh = CFG.SERVER_H;

        // Cabinet shadow
        ctx.beginPath();
        ctx.ellipse(0, rh/2 + 4, rw/2 * 0.8, 6, 0, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(0,0,0,0.45)';
        ctx.fill();

        // Cabinet body front face
        ctx.beginPath();
        ctx.roundRect(-rw/2, -rh/2, rw, rh, [4, 4, 2, 2]);
        ctx.fillStyle   = '#080E1C';
        ctx.strokeStyle = `rgba(0,212,255,${0.4 + glowAlpha * 0.3})`;
        ctx.lineWidth   = 1.5;
        ctx.fill(); ctx.stroke();

        // Cabinet right side (3D depth)
        ctx.beginPath();
        ctx.moveTo(rw/2,      -rh/2);
        ctx.lineTo(rw/2 + 14, -rh/2 - 10);
        ctx.lineTo(rw/2 + 14,  rh/2 - 10);
        ctx.lineTo(rw/2,       rh/2);
        ctx.closePath();
        ctx.fillStyle   = 'rgba(0,212,255,0.06)';
        ctx.strokeStyle = 'rgba(0,212,255,0.20)';
        ctx.lineWidth   = 0.8;
        ctx.fill(); ctx.stroke();

        // Cabinet top face
        ctx.beginPath();
        ctx.moveTo(-rw/2,      -rh/2);
        ctx.lineTo(-rw/2 + 14, -rh/2 - 10);
        ctx.lineTo( rw/2 + 14, -rh/2 - 10);
        ctx.lineTo( rw/2,      -rh/2);
        ctx.closePath();
        ctx.fillStyle   = 'rgba(0,212,255,0.12)';
        ctx.strokeStyle = 'rgba(0,212,255,0.30)';
        ctx.fill(); ctx.stroke();

        // Rack unit slots (1U strips)
        for (let u = 0; u < 7; u++) {
            const uy = -rh/2 + 6 + u * 11;
            ctx.beginPath();
            ctx.roundRect(-rw/2 + 4, uy, rw - 8, 9, 1);
            ctx.fillStyle   = '#0D1828';
            ctx.strokeStyle = 'rgba(0,212,255,0.15)';
            ctx.lineWidth   = 0.5;
            ctx.fill(); ctx.stroke();

            // LED row per unit
            const ledOn = Math.random() > 0.3;
            const ledCol = u < 4 ? PAL.healthy : (u < 6 ? PAL.accent : PAL.degraded);
            ctx.beginPath();
            ctx.arc(-rw/2 + 9, uy + 4.5, 2, 0, Math.PI * 2);
            ctx.fillStyle = ledOn ? ledCol : 'rgba(100,120,150,0.3)';
            if (ledOn && glowAlpha > 0.5) { ctx.shadowColor = ledCol; ctx.shadowBlur = 6; }
            ctx.fill();
            ctx.shadowBlur = 0;

            // Activity bar
            const barW = (rw - 24) * (0.3 + 0.5 * Math.abs(Math.sin(spinAngle * 2 + u)));
            ctx.beginPath();
            ctx.roundRect(-rw/2 + 14, uy + 2.5, barW, 4, 1);
            ctx.fillStyle = `rgba(0,212,255,${0.10 + glowAlpha * 0.10})`;
            ctx.fill();
        }

        // Spinning fan indicator at top
        const fanR = 8;
        const fanX = rw/2 - 12;
        const fanY = -rh/2 + 12;
        ctx.save();
        ctx.translate(fanX, fanY);
        ctx.rotate(spinAngle * 3);
        for (let b = 0; b < 4; b++) {
            ctx.rotate(Math.PI / 2);
            ctx.beginPath();
            ctx.ellipse(0, -fanR/2, 2, fanR/2, 0, 0, Math.PI);
            ctx.fillStyle = `rgba(0,212,255,${0.25 + glowAlpha * 0.15})`;
            ctx.fill();
        }
        ctx.restore();

        // "CONTROL PLANE" label
        ctx.fillStyle = `rgba(0,212,255,${0.5 + glowAlpha * 0.3})`;
        ctx.font      = 'bold 5px Inter, sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText('CONTROL PLANE', 0, rh/2 + 14);

        // Outer glow
        for (let g = CFG.GLOW_LAYERS; g > 0; g--) {
            const gr = ctx.createRadialGradient(0, 0, 0, 0, 0, rw * (0.8 + g * 0.4));
            gr.addColorStop(0,   `rgba(0,212,255,0)`);
            gr.addColorStop(0.5, `rgba(0,212,255,${glowAlpha * 0.05 / g})`);
            gr.addColorStop(1,   `rgba(0,212,255,0)`);
            ctx.beginPath();
            ctx.arc(0, 0, rw * (0.8 + g * 0.4), 0, Math.PI * 2);
            ctx.fillStyle = gr;
            ctx.fill();
        }

        ctx.restore();
    }

    /** Draw a node device model centred at (0,0) with ctx already transformed */
    function drawDevice(type, color, glow) {
        ctx.save();
        ctx.shadowColor = color;
        ctx.shadowBlur  = glow ? 14 : 4;

        switch (type) {
            case 'windows': drawWindows(color, glow); break;
            case 'mac':     drawMac(color, glow);     break;
            case 'linux':   drawLinux(color, glow);   break;
            case 'raspi':   drawRaspi(color, glow);   break;
            default:        drawGenericServer(color, glow); break;
        }

        ctx.restore();
    }

    /** OS label below device */
    function osLabel(type) {
        const labels = { windows: 'Windows', mac: 'macOS', linux: 'Linux', raspi: 'Raspberry Pi', server: 'Server' };
        return labels[type] || 'Unknown';
    }

    /* ─── Pulse helpers ───────────────────────────────────────── */

    function spawnPulse(from, to, color, w) {
        pulses.push({ from: { x: from.x, y: from.y }, to: { x: to.x, y: to.y }, t: 0, color, w: w || CFG.PULSE_RADIUS });
    }

    /* ─── Main render loop ────────────────────────────────────── */

    function render(ts) {
        if (!running) return;
        const dt = ts - lastTs;
        if (dt < 1000 / CFG.FPS) { raf = requestAnimationFrame(render); return; }
        lastTs  = ts;
        spinAngle += 0.006;

        // Parallax offset
        const px = (mouse.x / W - 0.5) * CFG.PARALLAX * W;
        const py = (mouse.y / H - 0.5) * CFG.PARALLAX * H;

        ctx.clearRect(0, 0, W, H);

        /* ── 1. Stars ── */
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

        /* ── 2. Ambient particle mesh ── */
        ambients.forEach(a => {
            a.x += a.vx + px * 0.05;
            a.y += a.vy + py * 0.05;
            if (a.x < -20)  a.x = W + 10;
            if (a.x > W+20) a.x = -10;
            if (a.y < -20)  a.y = H + 10;
            if (a.y > H+20) a.y = -10;
            a.a += a.ad;
            if (a.a > 0.65 || a.a < 0.15) a.ad = -a.ad;
        });
        for (let i = 0; i < ambients.length; i++) {
            for (let j = i + 1; j < ambients.length; j++) {
                const dx = ambients[i].x - ambients[j].x;
                const dy = ambients[i].y - ambients[j].y;
                const d  = Math.sqrt(dx * dx + dy * dy);
                if (d < 160) {
                    const alpha = (1 - d / 160) * 0.12;
                    ctx.beginPath();
                    ctx.moveTo(ambients[i].x, ambients[i].y);
                    ctx.lineTo(ambients[j].x, ambients[j].y);
                    ctx.strokeStyle = PAL.ambient + alpha + ')';
                    ctx.lineWidth   = 0.5;
                    ctx.stroke();
                }
            }
        }
        ambients.forEach(a => {
            ctx.beginPath();
            ctx.arc(a.x, a.y, a.r, 0, Math.PI * 2);
            ctx.fillStyle   = PAL.ambient + a.a + ')';
            ctx.shadowColor = PAL.accent;
            ctx.shadowBlur  = 6;
            ctx.fill();
            ctx.shadowBlur = 0;
        });

        /* ── 3. Animate real node positions ── */
        realNodes.forEach(rn => {
            rn.x = lerp(rn.x, rn.tx + px, 0.06);
            rn.y = lerp(rn.y, rn.ty + py, 0.06);
        });
        const sx = serverPos.x + px;
        const sy = serverPos.y + py;

        /* ── 4. Sort nodes by depth (far → near) for correct overlap ── */
        const sorted = [...realNodes].sort((a, b) => a.depth - b.depth);

        /* ── 5. Draw edges (server ↔ node) ── */
        sorted.forEach(rn => {
            const color = nodeColor(rn);
            const isHealthy = color === PAL.healthy;
            const edgeAlpha = isHealthy ? 0.22 : 0.10;
            drawEdge(sx, sy, rn.x, rn.y, `rgba(${isHealthy ? '0,212,255' : '255,50,80'},`, edgeAlpha, 0.8 + rn.depth * 0.5);
        });

        /* ── 6. Spawn new pulses ── */
        if (ts > nextPulse && realNodes.length > 0) {
            nextPulse = ts + CFG.PULSE_INTERVAL + rand(-400, 400);
            const rn    = realNodes[Math.floor(Math.random() * realNodes.length)];
            const toSrv = Math.random() > 0.5;
            const col   = nodeColor(rn);
            spawnPulse(
                toSrv ? rn         : { x: sx, y: sy },
                toSrv ? { x: sx, y: sy } : rn,
                col, CFG.PULSE_RADIUS * rn.depth
            );
        } else if (ts > nextPulse && ambients.length > 1) {
            nextPulse = ts + CFG.PULSE_INTERVAL;
            const i   = Math.floor(Math.random() * ambients.length);
            let j = i;
            while (j === i) j = Math.floor(Math.random() * ambients.length);
            spawnPulse(ambients[i], ambients[j], PAL.accent, 3);
        }

        /* ── 7. Tick & draw pulses ── */
        pulses = pulses.filter(p => {
            const dx = p.to.x - p.from.x;
            const dy = p.to.y - p.from.y;
            const d  = Math.sqrt(dx * dx + dy * dy) || 1;
            p.t += (CFG.PULSE_SPEED / d);
            if (p.t >= 1) return false;
            drawPulse(p);
            return true;
        });

        /* ── 8. Central server rack ── */
        const glowPulse = 0.65 + 0.35 * Math.abs(Math.sin(spinAngle));
        drawServerRack(sx, sy, glowPulse);

        /* ── 9. Node devices (sorted far→near so near ones render on top) ── */
        sorted.forEach((rn, idx) => {
            const color = nodeColor(rn);
            const isHealthy = color === PAL.healthy;
            const sc    = rn.scale;
            const glow  = isHealthy && Math.abs(Math.sin(spinAngle + idx)) > 0.6;

            ctx.save();
            ctx.translate(rn.x, rn.y);
            ctx.scale(sc, sc);

            // Shadow ellipse beneath device
            ctx.beginPath();
            ctx.ellipse(0, CFG.NODE_ICON_H / 2 + 4, CFG.NODE_ICON_W * 0.45, 5, 0, 0, Math.PI * 2);
            ctx.fillStyle = 'rgba(0,0,0,0.35)';
            ctx.fill();

            // Outer glow halo
            const hg = ctx.createRadialGradient(0, 0, 0, 0, 0, CFG.NODE_ICON_W * 0.9);
            hg.addColorStop(0,   color.replace(')', ',0.20)').replace('rgb', 'rgba').replace('#', 'rgba(').replace(')', ',0.20)'));
            hg.addColorStop(1,   'transparent');
            ctx.beginPath();
            ctx.arc(0, 0, CFG.NODE_ICON_W * 0.9, 0, Math.PI * 2);
            ctx.fillStyle = hg;
            ctx.fill();

            // Draw the device
            drawDevice(rn.type, color, glow);

            ctx.restore();

            // Labels (not scaled with device, fixed size)
            const labelY = rn.y + CFG.NODE_ICON_H * sc * 0.55 + 14;

            // Node name
            ctx.save();
            ctx.fillStyle = color;
            ctx.shadowColor = color;
            ctx.shadowBlur = glow ? 8 : 3;
            ctx.font      = `bold ${Math.round(11 * sc)}px Inter, sans-serif`;
            ctx.textAlign = 'center';
            ctx.fillText(rn.name.length > 14 ? rn.name.slice(0, 12) + '…' : rn.name, rn.x, labelY);
            ctx.restore();

            // OS type + status
            const subY = labelY + Math.round(13 * sc);
            ctx.fillStyle = `rgba(140,180,220,${0.5 * sc})`;
            ctx.font      = `${Math.round(9 * sc)}px Inter, sans-serif`;
            ctx.textAlign = 'center';
            ctx.fillText(`${osLabel(rn.type)} · ${rn.status || 'UNKNOWN'}`, rn.x, subY);

            // Status indicator ring around device
            ctx.beginPath();
            ctx.arc(rn.x, rn.y - 4, (CFG.NODE_ICON_W / 2 + 4) * sc, 0, Math.PI * 2);
            ctx.strokeStyle = color;
            ctx.globalAlpha = 0.18 + (isHealthy ? 0.12 * Math.abs(Math.sin(spinAngle + idx * 1.3)) : 0);
            ctx.lineWidth   = 1;
            ctx.stroke();
            ctx.globalAlpha = 1;
        });
    }

    /* ─── Resize ─────────────────────────────────────────────── */

    function resize() {
        dpr         = window.devicePixelRatio || 1;
        W           = window.innerWidth;
        H           = window.innerHeight;
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

    /* ─── Public API ─────────────────────────────────────────── */

    /**
     * Called by shell.js / dashboard.js with live NodeDto[] from SSE.
     * Reads node.id, node.name, node.status, node.os / node.platform.
     */
    function updateNodes(nodes) {
        if (!canvas) return;
        const arr = (nodes || []).slice(0, 20);

        // Merge: keep existing x/y for smooth animation
        const next = arr.map((n, i) => {
            const existing = realNodes.find(r => r.id === n.id);
            return Object.assign(existing || {}, {
                id:     n.id,
                name:   n.name || n.id || `Node-${i}`,
                status: n.status || 'UNKNOWN',
                os:     n.os || n.platform || n.operatingSystem || '',
                type:   deviceType(n),
                x:      existing ? existing.x : serverPos.x,
                y:      existing ? existing.y : serverPos.y,
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
        canvas.style.opacity       = '0.72';
        document.body.insertBefore(canvas, document.body.firstChild);

        ctx = canvas.getContext('2d');
        resize();

        window.addEventListener('resize',    resize);
        window.addEventListener('mousemove', e => { mouse.x = e.clientX; mouse.y = e.clientY; });

        running = true;
        raf     = requestAnimationFrame(render);
    }

    function destroy() {
        running = false;
        cancelAnimationFrame(raf);
        window.removeEventListener('resize',    resize);
        if (canvas) { canvas.remove(); canvas = null; }
    }

    NG.networkCanvas = { mount, destroy, updateNodes };
})();
