/**
 * ambient-3d-animals.js — High-Fidelity 3D Ambient Fauna Simulation
 *
 * Renders an ethereal, depth-projected 3D background of vivid, translucent polygonal creatures:
 *   1. Cyber Manta Ray (celestial cyan/azure translucent wings, bioluminescent spine, whip-tail)
 *   2. Origami Swift / Geometric Falcon (golden-amber & aurora-rose translucent wings, banking roll)
 *   3. Deep-Space Leviathan / Cosmic Whale (amethyst-lavender translucent hull, constellation spine)
 *   4. Prism Dragonfly / Neon Butterfly (dual translucent opal/mint fluttering wings, darting)
 *
 * True 3D projection, depth sorting, directional lighting, and mouse interactivity:
 *   • Mouse Move: Curious vortex flocking and aerodynamic banking deflection
 *   • Click / Tap: 3D glowing shockwave rings, stardust burst, and startled creature roll
 *   • Non-intrusive: Soft ethereal transparency (0.38-0.42 opacity) with high contrast against dark neumorphic UI
 */
(function (global) {
  'use strict';

  // ─── Math & Vector Helpers ───────────────────────────────────────────────────
  const sin = Math.sin, cos = Math.cos, sqrt = Math.sqrt, atan2 = Math.atan2, PI = Math.PI;
  const rand = (min, max) => min + Math.random() * (max - min);
  const clamp = (v, min, max) => Math.max(min, Math.min(max, v));

  // Light source vector: from top-left-front
  const LIGHT = { x: -0.4, y: -0.8, z: 0.45 };
  const lightLen = sqrt(LIGHT.x * LIGHT.x + LIGHT.y * LIGHT.y + LIGHT.z * LIGHT.z);
  LIGHT.x /= lightLen; LIGHT.y /= lightLen; LIGHT.z /= lightLen;

  // ─── Simulation Config ───────────────────────────────────────────────────────
  const CONFIG = {
    fov: 460,
    bounds: {
      minX: -950, maxX: 950,
      minY: -550, maxY: 550,
      minZ: -120, maxZ: 850
    },
    counts: {
      mantas: 4,
      birds: 6,
      whales: 2,
      butterflies: 5
    }
  };

  class FaunaEngine {
    constructor() {
      this.canvas = null;
      this.ctx = null;
      this.W = 0;
      this.H = 0;
      this.dpr = 1;
      this.entities = [];
      this.particles = [];
      this.shockwaves = [];
      this.mouse = { x: 0, y: 0, wx: 0, wy: 0, active: false };
      this.lastTime = 0;
      this.running = false;
      this.enabled = true;
      this.pillEl = null;

      this.boundResize = this.onResize.bind(this);
      this.boundMouseMove = this.onMouseMove.bind(this);
      this.boundClick = this.onClick.bind(this);
      this.boundLoop = this.loop.bind(this);
    }

    init(container) {
      if (this.canvas) return;

      this.canvas = document.createElement('canvas');
      this.canvas.id = 'ambientFaunaCanvas';
      this.canvas.style.position = 'fixed';
      this.canvas.style.inset = '0';
      this.canvas.style.width = '100vw';
      this.canvas.style.height = '100vh';
      this.canvas.style.pointerEvents = 'none';
      this.canvas.style.zIndex = '0';
      // Soft, delicate, ethereal transparency: non-distracting background presence
      this.canvas.style.opacity = '0.20';

      const parent = container || document.body;
      parent.insertBefore(this.canvas, parent.firstChild);

      this.ctx = this.canvas.getContext('2d', { alpha: true });
      this.onResize();

      window.addEventListener('resize', this.boundResize);
      window.addEventListener('pointermove', this.boundMouseMove);
      window.addEventListener('pointerdown', this.boundClick);

      this.spawnCreatures();
      this.createControlPill();

      this.running = true;
      this.lastTime = performance.now();
      requestAnimationFrame(this.boundLoop);
    }

    onResize() {
      this.dpr = Math.min(window.devicePixelRatio || 1, 2);
      this.W = window.innerWidth;
      this.H = window.innerHeight;
      this.canvas.width = this.W * this.dpr;
      this.canvas.height = this.H * this.dpr;
      this.ctx.scale(this.dpr, this.dpr);
    }

    onMouseMove(e) {
      this.mouse.x = e.clientX;
      this.mouse.y = e.clientY;
      this.mouse.active = true;

      // Project screen mouse to world coordinates at Z=0
      this.mouse.wx = (this.mouse.x - this.W / 2);
      this.mouse.wy = (this.mouse.y - this.H / 2);
    }

    onClick(e) {
      const clickX = e.clientX;
      const clickY = e.clientY;
      const wx = clickX - this.W / 2;
      const wy = clickY - this.H / 2;

      // Spawn expanding 3D shockwave ring
      this.shockwaves.push({
        x: wx, y: wy, z: rand(50, 200),
        r: 10, maxR: rand(240, 360),
        alpha: 0.6,
        color: Math.random() > 0.5 ? 'rgba(226, 232, 240, 0.6)' : 'rgba(251, 191, 36, 0.5)'
      });

      // Spawn glowing stardust burst particles
      for (let i = 0; i < 18; i++) {
        const angle = rand(0, PI * 2);
        const speed = rand(2.5, 8);
        this.particles.push({
          x: wx, y: wy, z: rand(50, 200),
          vx: cos(angle) * speed,
          vy: sin(angle) * speed,
          vz: rand(-3, 3),
          life: 1.0,
          decay: rand(0.015, 0.03),
          color: Math.random() > 0.5 ? 'rgba(241, 245, 249, 0.7)' : 'rgba(253, 230, 138, 0.6)',
          size: rand(2, 3.5)
        });
      }

      // Startle nearby creatures gently
      this.entities.forEach(ent => {
        const dx = ent.x - wx;
        const dy = ent.y - wy;
        const dist = sqrt(dx * dx + dy * dy);
        if (dist < 420) {
          ent.startle(dx, dy);
        }
      });
    }

    spawnCreatures() {
      this.entities = [];
      for (let i = 0; i < CONFIG.counts.mantas; i++) this.entities.push(new MantaRay());
      for (let i = 0; i < CONFIG.counts.birds; i++) this.entities.push(new SwiftBird());
      for (let i = 0; i < CONFIG.counts.whales; i++) this.entities.push(new SpaceWhale());
      for (let i = 0; i < CONFIG.counts.butterflies; i++) this.entities.push(new PrismButterfly());
    }

    createControlPill() {
      if (document.getElementById('faunaTogglePill')) return;
      const pill = document.createElement('button');
      pill.id = 'faunaTogglePill';
      pill.title = 'Click to toggle ambient 3D fauna';
      pill.style.position = 'fixed';
      pill.style.bottom = '18px';
      pill.style.left = '18px';
      pill.style.zIndex = '999';
      pill.style.background = 'linear-gradient(145deg, #151a24, #0f131c)';
      pill.style.boxShadow = '5px 5px 14px rgba(0,0,0,0.65), -3px -3px 10px rgba(255,255,255,0.035)';
      pill.style.border = '1px solid rgba(255, 255, 255, 0.08)';
      pill.style.borderRadius = '30px';
      pill.style.padding = '6px 14px';
      pill.style.color = '#cbd5e1';
      pill.style.fontFamily = "'SFMono-Regular', Consolas, Menlo, monospace";
      pill.style.fontSize = '11px';
      pill.style.fontWeight = '600';
      pill.style.cursor = 'pointer';
      pill.style.display = 'inline-flex';
      pill.style.alignItems = 'center';
      pill.style.gap = '8px';
      pill.style.transition = 'all 0.2s cubic-bezier(0.16, 1, 0.3, 1)';

      pill.innerHTML = `<span style="display:inline-block;width:7px;height:7px;border-radius:50%;background:#10b981;box-shadow:0 0 8px #10b981"></span><span>3D Fauna: ON</span>`;

      pill.addEventListener('mouseenter', () => {
        pill.style.transform = 'translateY(-1px)';
        pill.style.boxShadow = '6px 6px 16px rgba(0,0,0,0.7), -4px -4px 12px rgba(255,255,255,0.05)';
      });
      pill.addEventListener('mouseleave', () => {
        pill.style.transform = 'translateY(0)';
        pill.style.boxShadow = '5px 5px 14px rgba(0,0,0,0.65), -3px -3px 10px rgba(255,255,255,0.035)';
      });
      pill.addEventListener('mousedown', () => {
        pill.style.transform = 'scale(0.97) translateY(1px)';
        pill.style.boxShadow = 'inset 3px 3px 6px rgba(0,0,0,0.7), inset -2px -2px 4px rgba(255,255,255,0.035)';
      });
      pill.addEventListener('mouseup', () => {
        pill.style.transform = 'translateY(-1px)';
      });

      pill.addEventListener('click', (e) => {
        e.stopPropagation();
        this.enabled = !this.enabled;
        if (this.enabled) {
          this.canvas.style.display = 'block';
          pill.innerHTML = `<span style="display:inline-block;width:7px;height:7px;border-radius:50%;background:#10b981;box-shadow:0 0 8px #10b981"></span><span>3D Fauna: ON</span>`;
          pill.style.color = '#cbd5e1';
          pill.style.borderColor = 'rgba(255, 255, 255, 0.12)';
        } else {
          this.canvas.style.display = 'none';
          pill.innerHTML = `<span style="display:inline-block;width:7px;height:7px;border-radius:50%;background:#ef4444;"></span><span>3D Fauna: PAUSED</span>`;
          pill.style.color = '#64748b';
          pill.style.borderColor = 'rgba(255, 255, 255, 0.06)';
        }
      });

      document.body.appendChild(pill);
      this.pillEl = pill;
    }

    loop(now) {
      if (!this.running) return;
      const dt = Math.min((now - this.lastTime) / 1000, 0.1);
      this.lastTime = now;

      if (this.enabled) {
        this.ctx.clearRect(0, 0, this.W, this.H);

        // Update & Render Shockwaves
        this.renderShockwaves(dt);

        // Update Entities
        this.entities.forEach(ent => ent.update(dt, this.mouse, CONFIG.bounds));

        // Depth sort entities (far to near)
        this.entities.sort((a, b) => b.z - a.z);

        // Render Entities
        const cx = this.W / 2;
        const cy = this.H / 2;
        this.entities.forEach(ent => ent.render(this.ctx, cx, cy, CONFIG.fov));

        // Update & Render Particles
        this.renderParticles(dt, cx, cy, CONFIG.fov);
      }

      requestAnimationFrame(this.boundLoop);
    }

    renderShockwaves(dt) {
      const cx = this.W / 2;
      const cy = this.H / 2;
      for (let i = this.shockwaves.length - 1; i >= 0; i--) {
        const sw = this.shockwaves[i];
        sw.r += dt * 320;
        sw.alpha -= dt * 0.9;
        if (sw.alpha <= 0 || sw.r >= sw.maxR) {
          this.shockwaves.splice(i, 1);
          continue;
        }

        const scale = CONFIG.fov / (CONFIG.fov + sw.z);
        const px = cx + sw.x * scale;
        const py = cy + sw.y * scale;
        const pr = sw.r * scale;

        this.ctx.save();
        this.ctx.beginPath();
        this.ctx.arc(px, py, pr, 0, PI * 2);
        this.ctx.strokeStyle = sw.color;
        this.ctx.globalAlpha = sw.alpha * 0.5;
        this.ctx.lineWidth = 2.5 * scale;
        this.ctx.stroke();

        this.ctx.beginPath();
        this.ctx.arc(px, py, pr * 0.85, 0, PI * 2);
        this.ctx.strokeStyle = '#ffffff';
        this.ctx.globalAlpha = sw.alpha * 0.25;
        this.ctx.lineWidth = 1.2 * scale;
        this.ctx.stroke();
        this.ctx.restore();
      }
    }

    renderParticles(dt, cx, cy, fov) {
      for (let i = this.particles.length - 1; i >= 0; i--) {
        const p = this.particles[i];
        p.x += p.vx;
        p.y += p.vy;
        p.z += p.vz;
        p.life -= p.decay;

        if (p.life <= 0) {
          this.particles.splice(i, 1);
          continue;
        }

        const scale = fov / (fov + p.z);
        const px = cx + p.x * scale;
        const py = cy + p.y * scale;

        this.ctx.save();
        this.ctx.beginPath();
        this.ctx.arc(px, py, Math.max(1, p.size * scale), 0, PI * 2);
        this.ctx.fillStyle = p.color;
        this.ctx.globalAlpha = p.life * 0.65;
        this.ctx.shadowBlur = 6;
        this.ctx.shadowColor = p.color;
        this.ctx.fill();
        this.ctx.restore();
      }
    }

    destroy() {
      this.running = false;
      window.removeEventListener('resize', this.boundResize);
      window.removeEventListener('pointermove', this.boundMouseMove);
      window.removeEventListener('pointerdown', this.boundClick);
      if (this.canvas && this.canvas.parentNode) {
        this.canvas.parentNode.removeChild(this.canvas);
      }
      if (this.pillEl && this.pillEl.parentNode) {
        this.pillEl.parentNode.removeChild(this.pillEl);
      }
    }
  }

  // ─── Base Creature Class ──────────────────────────────────────────────────────
  class Creature {
    constructor() {
      this.x = rand(-600, 600);
      this.y = rand(-350, 350);
      this.z = rand(0, 700);
      this.vx = rand(-1, 1);
      this.vy = rand(-1, 1);
      this.vz = rand(-0.4, 0.4);
      this.speed = 1.0;
      this.baseSpeed = 1.0;
      this.yaw = rand(0, PI * 2);
      this.pitch = 0;
      this.roll = 0;
      this.targetYaw = this.yaw;
      this.time = rand(0, 100);
      this.startledTimer = 0;
    }

    startle(dx, dy) {
      this.startledTimer = 2.2;
      const angle = atan2(dy, dx);
      this.targetYaw = angle;
      this.speed = this.baseSpeed * 2.8;
      this.roll += PI * 2; // full evasive 3D roll
    }

    wrapBounds(b) {
      const margin = 80;
      if (this.x < b.minX - margin) this.x = b.maxX + margin;
      if (this.x > b.maxX + margin) this.x = b.minX - margin;
      if (this.y < b.minY - margin) this.y = b.maxY + margin;
      if (this.y > b.maxY + margin) this.y = b.minY - margin;
      if (this.z < b.minZ) this.z = b.maxZ;
      if (this.z > b.maxZ) this.z = b.minZ;
    }

    project(v, cx, cy, fov) {
      const scale = fov / (fov + v.z);
      return {
        x: cx + v.x * scale,
        y: cy + v.y * scale,
        scale
      };
    }
  }

  // ─── 1. Cyber Manta Ray (Celestial Cyan / Holographic Azure) ──────────────────
  class MantaRay extends Creature {
    constructor() {
      super();
      this.baseSpeed = rand(45, 75);
      this.speed = this.baseSpeed;
      this.span = rand(70, 95);
      this.tailNodes = [];
      for (let i = 0; i < 7; i++) {
        this.tailNodes.push({ x: this.x, y: this.y, z: this.z });
      }
    }

    update(dt, mouse, bounds) {
      this.time += dt * 2.4 * (this.speed / this.baseSpeed);

      if (this.startledTimer > 0) {
        this.startledTimer -= dt;
        if (this.startledTimer <= 0) this.speed = this.baseSpeed;
      }

      // Gentle vortex attraction toward mouse
      if (mouse.active) {
        const dx = mouse.wx - this.x;
        const dy = mouse.wy - this.y;
        const dist = sqrt(dx * dx + dy * dy);
        if (dist > 80 && dist < 650) {
          const desired = atan2(dy, dx);
          let diff = desired - this.yaw;
          while (diff < -PI) diff += PI * 2;
          while (diff > PI) diff -= PI * 2;
          this.yaw += diff * dt * 0.85;
        }
      } else {
        this.yaw += sin(this.time * 0.4) * dt * 0.3;
      }

      // Smooth pitch and roll
      this.pitch = sin(this.time * 0.6) * 0.12;
      this.roll = sin(this.time * 0.8) * 0.18;

      const fwdX = cos(this.yaw);
      const fwdY = sin(this.yaw);

      this.x += fwdX * this.speed * dt;
      this.y += fwdY * this.speed * dt;
      this.z += sin(this.time * 0.5) * 12 * dt;

      this.wrapBounds(bounds);

      // Trailing whip-tail physics
      let leader = { x: this.x, y: this.y, z: this.z };
      for (let i = 0; i < this.tailNodes.length; i++) {
        const node = this.tailNodes[i];
        const segLen = 14;
        const dx = node.x - leader.x;
        const dy = node.y - leader.y;
        const dz = node.z - leader.z;
        const dist = sqrt(dx * dx + dy * dy + dz * dz) || 1;
        node.x = leader.x + (dx / dist) * segLen;
        node.y = leader.y + (dy / dist) * segLen;
        node.z = leader.z + (dz / dist) * segLen;
        leader = node;
      }
    }

    render(ctx, cx, cy, fov) {
      const scale = fov / (fov + this.z);
      if (scale <= 0) return;

      const px = cx + this.x * scale;
      const py = cy + this.y * scale;

      const cyaw = cos(this.yaw), syaw = sin(this.yaw);
      const flap = sin(this.time) * 22;

      ctx.save();
      ctx.translate(px, py);
      ctx.rotate(this.yaw);

      // Dorsal Body dimensions
      const headL = 28 * scale;
      const tailL = -32 * scale;
      const wingR = this.span * scale;

      // Draw Left Wing — Translucent Pearlescent Moonlight Silver
      ctx.beginPath();
      ctx.moveTo(headL, 0);
      ctx.lineTo(4 * scale, -wingR);
      ctx.lineTo(tailL, 0);
      ctx.closePath();
      const gradL = ctx.createLinearGradient(0, -wingR, 0, 0);
      gradL.addColorStop(0, 'rgba(241, 245, 249, 0.16)');
      gradL.addColorStop(0.6, 'rgba(203, 213, 225, 0.08)');
      gradL.addColorStop(1, 'rgba(148, 163, 184, 0.02)');
      ctx.fillStyle = gradL;
      ctx.fill();
      ctx.strokeStyle = 'rgba(226, 232, 240, 0.55)';
      ctx.lineWidth = 1.0;
      ctx.stroke();

      // Draw Right Wing — Translucent Platinum
      ctx.beginPath();
      ctx.moveTo(headL, 0);
      ctx.lineTo(4 * scale, wingR);
      ctx.lineTo(tailL, 0);
      ctx.closePath();
      const gradR = ctx.createLinearGradient(0, wingR, 0, 0);
      gradR.addColorStop(0, 'rgba(226, 232, 240, 0.16)');
      gradR.addColorStop(0.6, 'rgba(203, 213, 225, 0.08)');
      gradR.addColorStop(1, 'rgba(148, 163, 184, 0.02)');
      ctx.fillStyle = gradR;
      ctx.fill();
      ctx.strokeStyle = 'rgba(203, 213, 225, 0.55)';
      ctx.lineWidth = 1.0;
      ctx.stroke();

      // Translucent Center Hull Diamond
      ctx.beginPath();
      ctx.moveTo(headL * 0.8, 0);
      ctx.lineTo(0, -8 * scale);
      ctx.lineTo(tailL * 0.7, 0);
      ctx.lineTo(0, 8 * scale);
      ctx.closePath();
      ctx.fillStyle = 'rgba(255, 255, 255, 0.08)';
      ctx.fill();

      // Moonlight central spine
      ctx.beginPath();
      ctx.moveTo(headL, 0);
      ctx.lineTo(tailL, 0);
      ctx.strokeStyle = 'rgba(248, 250, 252, 0.75)';
      ctx.lineWidth = 1.5 * scale;
      ctx.shadowBlur = 6;
      ctx.shadowColor = 'rgba(248, 250, 252, 0.4)';
      ctx.stroke();

      // Cephalic horns (mouth fin blades)
      ctx.beginPath();
      ctx.moveTo(headL - 2, -6 * scale);
      ctx.lineTo(headL + 12 * scale, -10 * scale);
      ctx.moveTo(headL - 2, 6 * scale);
      ctx.lineTo(headL + 12 * scale, 10 * scale);
      ctx.strokeStyle = 'rgba(226, 232, 240, 0.6)';
      ctx.lineWidth = 1.2 * scale;
      ctx.stroke();

      ctx.restore();

      // Render Trailing Tail
      ctx.save();
      ctx.beginPath();
      const pTail = this.project(this.tailNodes[0], cx, cy, fov);
      ctx.moveTo(pTail.x, pTail.y);
      for (let i = 1; i < this.tailNodes.length; i++) {
        const pt = this.project(this.tailNodes[i], cx, cy, fov);
        ctx.lineTo(pt.x, pt.y);
      }
      ctx.strokeStyle = 'rgba(203, 213, 225, 0.35)';
      ctx.lineWidth = 1.0 * scale;
      ctx.stroke();
      ctx.restore();
    }
  }

  // ─── 2. Origami Swift / Geometric Falcon (Golden Amber & Rose Quartz) ─────────
  class SwiftBird extends Creature {
    constructor() {
      super();
      this.baseSpeed = rand(90, 140);
      this.speed = this.baseSpeed;
      this.wingSpan = rand(40, 60);
      this.flapRate = rand(7.5, 9.5);
    }

    update(dt, mouse, bounds) {
      this.time += dt * this.flapRate * (this.speed / this.baseSpeed);

      if (this.startledTimer > 0) {
        this.startledTimer -= dt;
        if (this.startledTimer <= 0) this.speed = this.baseSpeed;
      }

      // Aerodynamic deflection away from mouse if close
      if (mouse.active) {
        const dx = this.x - mouse.wx;
        const dy = this.y - mouse.wy;
        const dist = sqrt(dx * dx + dy * dy);
        if (dist < 220) {
          const fleeAngle = atan2(dy, dx);
          let diff = fleeAngle - this.yaw;
          while (diff < -PI) diff += PI * 2;
          while (diff > PI) diff -= PI * 2;
          this.yaw += diff * dt * 3.5;
        }
      }

      this.yaw += sin(this.time * 0.3) * dt * 0.45;

      const fwdX = cos(this.yaw);
      const fwdY = sin(this.yaw);

      this.x += fwdX * this.speed * dt;
      this.y += fwdY * this.speed * dt;
      this.z += sin(this.time * 0.4) * 16 * dt;

      this.wrapBounds(bounds);
    }

    render(ctx, cx, cy, fov) {
      const scale = fov / (fov + this.z);
      if (scale <= 0) return;

      const px = cx + this.x * scale;
      const py = cy + this.y * scale;

      const flap = sin(this.time) * 0.75;
      const span = this.wingSpan * scale;

      ctx.save();
      ctx.translate(px, py);
      ctx.rotate(this.yaw);

      // 3D Polygonal Fuselage
      const beak = 18 * scale;
      const tail = -16 * scale;
      const bodyW = 5 * scale;

      // Body triangle — Translucent Champagne/Amber
      ctx.beginPath();
      ctx.moveTo(beak, 0);
      ctx.lineTo(-6 * scale, -bodyW);
      ctx.lineTo(tail, 0);
      ctx.lineTo(-6 * scale, bodyW);
      ctx.closePath();
      ctx.fillStyle = 'rgba(251, 191, 36, 0.18)';
      ctx.fill();
      ctx.strokeStyle = 'rgba(251, 191, 36, 0.75)';
      ctx.lineWidth = 1.1;
      ctx.stroke();

      // Left Articulated Wing — Translucent Warm Champagne Gold
      const leftTipY = -span * (0.8 + flap * 0.3);
      ctx.beginPath();
      ctx.moveTo(2 * scale, -bodyW);
      ctx.lineTo(-8 * scale, leftTipY);
      ctx.lineTo(-14 * scale, -bodyW * 0.6);
      ctx.closePath();
      const gradWingL = ctx.createLinearGradient(0, -bodyW, 0, leftTipY);
      gradWingL.addColorStop(0, 'rgba(251, 191, 36, 0.03)');
      gradWingL.addColorStop(1, 'rgba(245, 158, 11, 0.18)');
      ctx.fillStyle = gradWingL;
      ctx.fill();
      ctx.strokeStyle = 'rgba(252, 211, 77, 0.55)';
      ctx.lineWidth = 1;
      ctx.stroke();

      // Right Articulated Wing — Translucent Soft Amber
      const rightTipY = span * (0.8 + flap * 0.3);
      ctx.beginPath();
      ctx.moveTo(2 * scale, bodyW);
      ctx.lineTo(-8 * scale, rightTipY);
      ctx.lineTo(-14 * scale, bodyW * 0.6);
      ctx.closePath();
      const gradWingR = ctx.createLinearGradient(0, bodyW, 0, rightTipY);
      gradWingR.addColorStop(0, 'rgba(253, 230, 138, 0.03)');
      gradWingR.addColorStop(1, 'rgba(217, 119, 6, 0.18)');
      ctx.fillStyle = gradWingR;
      ctx.fill();
      ctx.strokeStyle = 'rgba(251, 191, 36, 0.55)';
      ctx.lineWidth = 1;
      ctx.stroke();

      // Glowing Beak spark
      ctx.beginPath();
      ctx.arc(beak, 0, 1.8 * scale, 0, PI * 2);
      ctx.fillStyle = '#fffbeb';
      ctx.shadowBlur = 6;
      ctx.shadowColor = 'rgba(251, 191, 36, 0.5)';
      ctx.fill();

      ctx.restore();
    }
  }

  // ─── 3. Deep-Space Leviathan / Cosmic Whale (Amethyst & Astral Violet) ─────────
  class SpaceWhale extends Creature {
    constructor() {
      super();
      this.baseSpeed = rand(30, 48);
      this.speed = this.baseSpeed;
      this.length = rand(120, 160);
      this.width = rand(26, 36);
    }

    update(dt, mouse, bounds) {
      this.time += dt * 1.5;

      if (this.startledTimer > 0) {
        this.startledTimer -= dt;
        if (this.startledTimer <= 0) this.speed = this.baseSpeed;
      }

      this.yaw += sin(this.time * 0.2) * dt * 0.15;

      const fwdX = cos(this.yaw);
      const fwdY = sin(this.yaw);

      this.x += fwdX * this.speed * dt;
      this.y += fwdY * this.speed * dt;
      this.z += cos(this.time * 0.3) * 8 * dt;

      this.wrapBounds(bounds);
    }

    render(ctx, cx, cy, fov) {
      const scale = fov / (fov + this.z);
      if (scale <= 0) return;

      const px = cx + this.x * scale;
      const py = cy + this.y * scale;

      const len = this.length * scale;
      const wid = this.width * scale;
      const flukeWave = sin(this.time * 2.2) * 14 * scale;

      ctx.save();
      ctx.translate(px, py);
      ctx.rotate(this.yaw);

      // Translucent Contoured Hull — Platinum Slate / Moonlight
      ctx.beginPath();
      ctx.ellipse(0, 0, len * 0.5, wid * 0.5, 0, 0, PI * 2);
      const grad = ctx.createLinearGradient(-len * 0.5, 0, len * 0.5, 0);
      grad.addColorStop(0, 'rgba(203, 213, 225, 0.05)');
      grad.addColorStop(0.5, 'rgba(148, 163, 184, 0.16)');
      grad.addColorStop(1, 'rgba(203, 213, 225, 0.05)');
      ctx.fillStyle = grad;
      ctx.fill();
      ctx.strokeStyle = 'rgba(226, 232, 240, 0.5)';
      ctx.lineWidth = 1.1;
      ctx.stroke();

      // Ventral ribs — Delicate Platinum Soundwaves
      for (let i = -3; i <= 3; i++) {
        const rx = i * (len * 0.1);
        ctx.beginPath();
        ctx.moveTo(rx, -wid * 0.4);
        ctx.lineTo(rx, wid * 0.4);
        ctx.strokeStyle = 'rgba(203, 213, 225, 0.20)';
        ctx.lineWidth = 0.8;
        ctx.stroke();
      }

      // Lateral Pectoral Flippers — Translucent Frosted Silver
      ctx.beginPath();
      ctx.moveTo(len * 0.1, -wid * 0.45);
      ctx.lineTo(len * 0.2, -wid * 1.1);
      ctx.lineTo(0, -wid * 0.5);
      ctx.fillStyle = 'rgba(226, 232, 240, 0.12)';
      ctx.fill();
      ctx.strokeStyle = 'rgba(203, 213, 225, 0.5)';
      ctx.stroke();

      ctx.beginPath();
      ctx.moveTo(len * 0.1, wid * 0.45);
      ctx.lineTo(len * 0.2, wid * 1.1);
      ctx.lineTo(0, wid * 0.5);
      ctx.fillStyle = 'rgba(56, 189, 248, 0.22)';
      ctx.fill();
      ctx.strokeStyle = 'rgba(56, 189, 248, 0.6)';
      ctx.stroke();

      // Massive Fluke Tail — Royal Shimmering Violet
      const tailX = -len * 0.5;
      ctx.beginPath();
      ctx.moveTo(tailX, 0);
      ctx.lineTo(tailX - 22 * scale, -wid * 0.8 + flukeWave);
      ctx.lineTo(tailX - 16 * scale, flukeWave * 0.5);
      ctx.lineTo(tailX - 22 * scale, wid * 0.8 + flukeWave);
      ctx.closePath();
      ctx.fillStyle = 'rgba(139, 92, 246, 0.32)';
      ctx.fill();
      ctx.strokeStyle = 'rgba(196, 181, 253, 0.8)';
      ctx.lineWidth = 1.1;
      ctx.stroke();

      // Constellation spine LEDs
      for (let i = -4; i <= 3; i++) {
        const sx = i * (len * 0.09);
        ctx.beginPath();
        ctx.arc(sx, 0, 1.8 * scale, 0, PI * 2);
        ctx.fillStyle = i % 2 === 0 ? '#38bdf8' : '#e879f9';
        ctx.shadowBlur = 6;
        ctx.shadowColor = ctx.fillStyle;
        ctx.fill();
      }

      ctx.restore();
    }
  }

  // ─── 4. Prism Butterfly / Neon Dragonfly (Opal Pink & Mint Iridescence) ───────
  class PrismButterfly extends Creature {
    constructor() {
      super();
      this.baseSpeed = rand(60, 95);
      this.speed = this.baseSpeed;
      this.size = rand(18, 28);
    }

    update(dt, mouse, bounds) {
      this.time += dt * 18;

      if (this.startledTimer > 0) {
        this.startledTimer -= dt;
        if (this.startledTimer <= 0) this.speed = this.baseSpeed;
      }

      this.yaw += (Math.random() - 0.5) * dt * 3.0;

      const fwdX = cos(this.yaw);
      const fwdY = sin(this.yaw);

      this.x += fwdX * this.speed * dt;
      this.y += fwdY * this.speed * dt;
      this.z += (Math.random() - 0.5) * 20 * dt;

      this.wrapBounds(bounds);
    }

    render(ctx, cx, cy, fov) {
      const scale = fov / (fov + this.z);
      if (scale <= 0) return;

      const px = cx + this.x * scale;
      const py = cy + this.y * scale;

      const flap = sin(this.time);
      const sz = this.size * scale;

      ctx.save();
      ctx.translate(px, py);
      ctx.rotate(this.yaw);

      // Dual translucent fluttering wings
      const wingW = sz * Math.abs(flap);

      // Top wing: Translucent Frosted Crystal
      ctx.beginPath();
      ctx.ellipse(-sz * 0.2, -wingW * 0.7, sz * 0.7, wingW, -0.4, 0, PI * 2);
      ctx.fillStyle = 'rgba(241, 245, 249, 0.16)';
      ctx.fill();
      ctx.strokeStyle = 'rgba(226, 232, 240, 0.55)';
      ctx.lineWidth = 1;
      ctx.stroke();

      // Bottom wing: Translucent Opal Ivory
      ctx.beginPath();
      ctx.ellipse(-sz * 0.2, wingW * 0.7, sz * 0.7, wingW, 0.4, 0, PI * 2);
      ctx.fillStyle = 'rgba(253, 230, 138, 0.12)';
      ctx.fill();
      ctx.strokeStyle = 'rgba(252, 211, 77, 0.50)';
      ctx.lineWidth = 1;
      ctx.stroke();

      // Slender ivory core
      ctx.beginPath();
      ctx.ellipse(0, 0, sz * 0.4, sz * 0.1, 0, 0, PI * 2);
      ctx.fillStyle = '#ffffff';
      ctx.fill();

      ctx.restore();
    }
  }

  // Export Singleton
  const engine = new FaunaEngine();
  global.Ambient3DAnimals = engine;

  // Auto-boot when DOM is loaded
  if (typeof document !== 'undefined') {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', () => engine.init());
    } else {
      engine.init();
    }
  }
})(typeof window !== 'undefined' ? window : this);
