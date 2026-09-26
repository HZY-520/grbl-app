/**
 * 雕刻路径 Canvas 预览
 * - 路径按功率分桶后烘焙为 Path2D（毫米坐标），缩放 / 平移只需改变换矩阵
 * - 支持滚轮缩放、拖拽平移、双指捏合、双击适应窗口
 * - 支持进度动画：按累计线段长度逐段点亮，模拟雕刻过程
 */

import { workArea, usableArea, normalizeDevice, originLabel } from '../core/device.js';

const BUCKETS = 24;
const GRID_STEPS = [0.1, 0.25, 0.5, 1, 2, 5, 10, 20, 50, 100, 200, 500, 1000];

/** 功率 → 颜色（低功率偏蓝、高功率偏橙红） */
function powerColor(p) {
  const t = Math.max(0, Math.min(1, p));
  const hue = 205 - 185 * t;
  const sat = 72 + 18 * t;
  const light = 52 + 16 * t;
  return `hsl(${hue}, ${sat}%, ${light}%)`;
}

export class Preview {
  constructor(canvas, wrap, onChange) {
    this.canvas = canvas;
    this.wrap = wrap;
    this.ctx = canvas.getContext('2d');
    this.onChange = onChange || (() => {});
    this.view = { scale: 6, ox: 40, oy: 200 };
    this.bounds = null;
    this.device = null;
    this.plan = null;
    this.totalLength = 0;
    this.progress = 1;
    this.flags = { showTravel: true, showGrid: true, colorByPower: true };
    this.paths = null;
    this.travelPath = null;
    this.heavy = false;
    this._raf = null;
    this._pointers = new Map();
    this._pinchDist = 0;
    this._panning = false;

    this._resize();
    this._bind();
    this._ro = new ResizeObserver(() => this._resize());
    this._ro.observe(wrap);
  }

  destroy() {
    this._ro.disconnect();
    if (this._raf) cancelAnimationFrame(this._raf);
  }

  /** 画布尺寸与 DPR 处理 */
  _resize() {
    const rect = this.wrap.getBoundingClientRect();
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const w = Math.max(1, Math.floor(rect.width));
    const h = Math.max(1, Math.floor(rect.height));
    this.canvas.width = Math.floor(w * dpr);
    this.canvas.height = Math.floor(h * dpr);
    this.canvas.style.width = `${w}px`;
    this.canvas.style.height = `${h}px`;
    this.dpr = dpr;
    this.w = w;
    this.h = h;
    this.render();
  }

  setFlags(flags) {
    const needsBake = flags.showTravel !== undefined || flags.colorByPower !== undefined;
    this.flags = { ...this.flags, ...flags };
    if (needsBake && this.plan) this._bake();
    this.render();
  }

  setProgress(p) {
    this.progress = Math.max(0, Math.min(1, p));
    this.render();
  }

  /** 更新设备参数：始终绘制行程范围；无任务时按行程范围取景 */
  setDevice(dev) {
    this.device = normalizeDevice(dev);
    if (!this.plan) this.fit();
    this.render();
  }

  /** 载入任务并自动适应窗口 */
  setJob(job) {
    this.plan = job ? job.plan : null;
    this.bounds = job ? job.bounds : null;
    if (job && job.device) this.device = normalizeDevice(job.device);
    this.totalLength = 0;
    this.paths = null;
    this.travelPath = null;

    if (!this.plan) {
      this.fit();
      return;
    }
    let points = 0;
    for (const it of this.plan.items) {
      this.totalLength += it.length;
      points += it.pts.length;
    }
    this.heavy = points > 260000 || this.plan.items.length > 9000;
    this._bake();
    this.fit();
  }

  /** 把路径烘焙成按功率分桶的 Path2D（毫米坐标） */
  _bake() {
    const buckets = [];
    for (let i = 0; i < BUCKETS; i++) buckets.push(new Path2D());
    const travel = new Path2D();
    let hasTravel = false;
    let cursor = { x: 0, y: 0 };

    for (const it of this.plan.items) {
      const b = this.flags.colorByPower
        ? Math.min(BUCKETS - 1, Math.max(0, Math.round(it.power * (BUCKETS - 1))))
        : 0;
      const path = buckets[b];
      const pts = it.pts;
      if (it.chained) {
        // 连续衔接：把上一段终点到本段起点的连线也算作雕刻段
        path.moveTo(cursor.x, cursor.y);
      } else {
        path.moveTo(pts[0].x, pts[0].y);
      }
      for (let i = 1; i < pts.length; i++) path.lineTo(pts[i].x, pts[i].y);
      if (it.closed && pts.length > 2) path.lineTo(pts[0].x, pts[0].y);
      cursor = pts[pts.length - 1];
    }

    if (this.flags.showTravel && !this.heavy) {
      let c = { x: 0, y: 0 };
      for (const it of this.plan.items) {
        if (!it.chained) {
          travel.moveTo(c.x, c.y);
          travel.lineTo(it.pts[0].x, it.pts[0].y);
          hasTravel = true;
        }
        c = it.pts[it.pts.length - 1];
      }
    }
    this.paths = { buckets, travel, hasTravel };
  }

  /** 生成「已完成雕刻」前缀路径 */
  _prefixPath(fraction) {
    const target = this.totalLength * fraction;
    const path = new Path2D();
    let acc = 0;
    for (const it of this.plan.items) {
      const pts = it.pts;
      if (acc + it.length <= target) {
        path.moveTo(pts[0].x, pts[0].y);
        for (let i = 1; i < pts.length; i++) path.lineTo(pts[i].x, pts[i].y);
        if (it.closed && pts.length > 2) path.lineTo(pts[0].x, pts[0].y);
        acc += it.length;
        continue;
      }
      // 部分绘制当前段
      let remaining = target - acc;
      path.moveTo(pts[0].x, pts[0].y);
      for (let i = 1; i < pts.length; i++) {
        const segLen = Math.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y);
        if (segLen <= remaining) {
          path.lineTo(pts[i].x, pts[i].y);
          remaining -= segLen;
        } else {
          const t = segLen > 0 ? remaining / segLen : 0;
          path.lineTo(
            pts[i - 1].x + (pts[i].x - pts[i - 1].x) * t,
            pts[i - 1].y + (pts[i].y - pts[i - 1].y) * t
          );
          break;
        }
      }
      break;
    }
    return path;
  }

  fit() {
    // 有任务时按雕刻内容取景；否则按设备行程范围取景
    const b = this.bounds || (this.device ? workArea(this.device) : null);
    if (!b) return;
    const pad = 26;
    const bw = Math.max(1e-3, b.maxX - b.minX);
    const bh = Math.max(1e-3, b.maxY - b.minY);
    const scale = Math.min((this.w - pad * 2) / bw, (this.h - pad * 2) / bh);
    this.view.scale = Math.max(0.05, Math.min(400, scale));
    this.fitScale = this.view.scale;
    this._centerContent(b);
    this.render();
    this.onChange();
  }

  _centerContent(b = this.bounds) {
    if (!b) return;
    const bw = (b.maxX - b.minX) * this.view.scale;
    const bh = (b.maxY - b.minY) * this.view.scale;
    const padX = Math.max(10, (this.w - bw) / 2);
    const padY = Math.max(10, (this.h - bh) / 2);
    this.view.ox = padX - b.minX * this.view.scale;
    this.view.oy = this.h - padY + b.minY * this.view.scale;
  }

  zoomBy(factor, center) {
    const c = center || { x: this.w / 2, y: this.h / 2 };
    const world = this.toWorld(c.x, c.y);
    const scale = Math.max(0.05, Math.min(400, this.view.scale * factor));
    this.view.scale = scale;
    this.view.ox = c.x - world.x * scale;
    this.view.oy = c.y + world.y * scale;
    this.render();
    this.onChange();
  }

  toWorld(sx, sy) {
    return { x: (sx - this.view.ox) / this.view.scale, y: (this.view.oy - sy) / this.view.scale };
  }

  _bind() {
    const canvas = this.canvas;

    canvas.addEventListener('wheel', (e) => {
      e.preventDefault();
      const rect = canvas.getBoundingClientRect();
      const factor = Math.pow(1.0015, -e.deltaY);
      this.zoomBy(factor, { x: e.clientX - rect.left, y: e.clientY - rect.top });
    }, { passive: false });

    canvas.addEventListener('pointerdown', (e) => {
      canvas.setPointerCapture(e.pointerId);
      this._pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
      if (this._pointers.size === 1) {
        this._panning = true;
        canvas.classList.add('is-panning');
      } else if (this._pointers.size === 2) {
        const [a, b] = [...this._pointers.values()];
        this._pinchDist = Math.hypot(a.x - b.x, a.y - b.y);
      }
    });

    canvas.addEventListener('pointermove', (e) => {
      const prev = this._pointers.get(e.pointerId);
      if (!prev) return;
      const cur = { x: e.clientX, y: e.clientY };
      this._pointers.set(e.pointerId, cur);

      if (this._pointers.size === 2) {
        const [a, b] = [...this._pointers.values()];
        const d = Math.hypot(a.x - b.x, a.y - b.y);
        if (this._pinchDist > 0) {
          const rect = canvas.getBoundingClientRect();
          const mid = { x: (a.x + b.x) / 2 - rect.left, y: (a.y + b.y) / 2 - rect.top };
          this.zoomBy(d / this._pinchDist, mid);
        }
        this._pinchDist = d;
        return;
      }
      if (this._panning) {
        this.view.ox += cur.x - prev.x;
        this.view.oy += cur.y - prev.y;
        this.render();
        this.onChange();
      }
    });

    const end = (e) => {
      this._pointers.delete(e.pointerId);
      if (this._pointers.size < 2) this._pinchDist = 0;
      if (this._pointers.size === 0) {
        this._panning = false;
        canvas.classList.remove('is-panning');
      }
    };
    canvas.addEventListener('pointerup', end);
    canvas.addEventListener('pointercancel', end);

    canvas.addEventListener('dblclick', () => this.fit());
  }

  /** 网格步长：保证屏幕间距 ≥ 44px */
  _gridStep() {
    for (const s of GRID_STEPS) {
      if (s * this.view.scale >= 44) return s;
    }
    return GRID_STEPS[GRID_STEPS.length - 1];
  }

  render() {
    if (this._raf) return;
    this._raf = requestAnimationFrame(() => {
      this._raf = null;
      this._draw();
    });
  }

  _draw() {
    const ctx = this.ctx;
    const { w, h, dpr } = this;
    if (!w || !h) return;

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    // 背景
    const dark = !document.body.classList.contains('theme-light');
    ctx.fillStyle = dark ? '#0f1116' : '#f7f8fc';
    ctx.fillRect(0, 0, w, h);

    const { scale, ox, oy } = this.view;

    if (this.flags.showGrid) this._drawGrid(ctx, scale, ox, oy, dark);

    // 设备行程范围与安全区（画在路径下层）
    this._drawWorkArea(ctx, dark);

    // 世界坐标 → 屏幕（Y 轴翻转）
    ctx.save();
    ctx.transform(scale, 0, 0, -scale, ox, oy);

    if (this.plan && this.paths) {
      // 走线
      if (this.flags.showTravel && this.paths.hasTravel) {
        ctx.save();
        ctx.setLineDash([3 / scale, 3 / scale]);
        ctx.strokeStyle = dark ? 'rgba(74,144,217,0.45)' : 'rgba(60,120,190,0.4)';
        ctx.lineWidth = Math.max(0.08, 0.7 / scale);
        ctx.stroke(this.paths.travel);
        ctx.restore();
      }

      const single = !this.flags.colorByPower;
      // 暗底：整体路径
      if (!this.heavy && this.progress < 1) {
        ctx.lineWidth = Math.max(0.06, 1 / scale);
        ctx.strokeStyle = dark ? 'rgba(150,165,190,0.35)' : 'rgba(120,132,155,0.35)';
        for (const p of this.paths.buckets) ctx.stroke(p);
      }

      ctx.lineWidth = Math.max(0.08, (this.heavy ? 0.6 : 1.1) / scale);
      ctx.lineJoin = 'round';
      ctx.lineCap = 'round';

      if (this.progress >= 1) {
        if (single) {
          ctx.strokeStyle = '#ff8a3d';
          ctx.stroke(this.paths.buckets[0]);
          for (let i = 1; i < BUCKETS; i++) ctx.stroke(this.paths.buckets[i]);
        } else {
          for (let i = 0; i < BUCKETS; i++) {
            ctx.strokeStyle = powerColor(i / (BUCKETS - 1));
            ctx.stroke(this.paths.buckets[i]);
          }
        }
      } else {
        const prefix = this._prefixPath(this.progress);
        ctx.strokeStyle = '#ff8a3d';
        ctx.stroke(prefix);
      }
    }

    ctx.restore();

    // 原点标记与坐标轴
    this._drawOrigin(ctx, scale, ox, oy, dark);
    // 尺寸标注
    if (this.plan) this._drawBounds(ctx, dark);
  }

  _drawGrid(ctx, scale, ox, oy, dark) {
    const step = this._gridStep();
    const tl = this.toWorld(0, 0);
    const br = this.toWorld(this.w, this.h);
    ctx.save();
    ctx.lineWidth = 1;
    ctx.strokeStyle = dark ? 'rgba(255,255,255,0.055)' : 'rgba(30,40,60,0.08)';
    ctx.beginPath();
    const x0 = Math.floor(tl.x / step) * step;
    const x1 = Math.ceil(br.x / step) * step;
    for (let x = x0; x <= x1; x += step) {
      const sx = Math.round(ox + x * scale) + 0.5;
      ctx.moveTo(sx, 0);
      ctx.lineTo(sx, this.h);
    }
    const y0 = Math.floor(br.y / step) * step;
    const y1 = Math.ceil(tl.y / step) * step;
    for (let y = y0; y <= y1; y += step) {
      const sy = Math.round(oy - y * scale) + 0.5;
      ctx.moveTo(0, sy);
      ctx.lineTo(this.w, sy);
    }
    ctx.stroke();

    // 网格尺寸标签
    ctx.fillStyle = dark ? 'rgba(200,210,230,0.4)' : 'rgba(60,72,95,0.55)';
    ctx.font = '10px ui-monospace, monospace';
    ctx.fillText(`${step} mm`, 8, this.h - 8);
    ctx.restore();
  }

  /** 绘制设备行程范围（实线）与安全区（虚线） */
  _drawWorkArea(ctx, dark) {
    const dev = this.device;
    if (!dev) return;
    const a = workArea(dev);
    const u = usableArea(dev);
    const s = this.view.scale;
    const sx = (x) => this.view.ox + x * s;
    const sy = (y) => this.view.oy - y * s;
    const rw = (a.maxX - a.minX) * s;
    const rh = (a.maxY - a.minY) * s;
    if (sx(a.maxX) < 0 || sx(a.minX) > this.w || sy(a.maxY) > this.h || sy(a.minY) < 0) return;

    ctx.save();
    // 行程范围
    ctx.fillStyle = dark ? 'rgba(90,140,220,0.05)' : 'rgba(70,120,200,0.05)';
    ctx.fillRect(sx(a.minX), sy(a.maxY), rw, rh);
    ctx.strokeStyle = dark ? 'rgba(120,170,255,0.5)' : 'rgba(40,90,180,0.45)';
    ctx.lineWidth = 1.5;
    ctx.strokeRect(sx(a.minX) + 0.5, sy(a.maxY) + 0.5, rw, rh);
    // 安全区
    ctx.setLineDash([5, 4]);
    ctx.strokeStyle = dark ? 'rgba(255,122,47,0.5)' : 'rgba(220,90,20,0.5)';
    ctx.lineWidth = 1;
    ctx.strokeRect(sx(u.minX) + 0.5, sy(u.maxY) + 0.5, u.width * s, u.height * s);
    ctx.setLineDash([]);
    // 标注
    ctx.font = '11px ui-monospace, monospace';
    ctx.fillStyle = dark ? 'rgba(160,200,255,0.9)' : 'rgba(30,70,150,0.9)';
    ctx.fillText(
      `行程 ${dev.travelX} × ${dev.travelY} mm · 原点${originLabel(dev.origin)}`,
      sx(a.minX) + 6,
      sy(a.maxY) - 6
    );
    ctx.restore();
  }

  _drawOrigin(ctx, scale, ox, oy, dark) {
    const sx = ox;
    const sy = oy;
    if (sx < -40 || sy < -40 || sx > this.w + 40 || sy > this.h + 40) return;
    ctx.save();
    ctx.strokeStyle = dark ? 'rgba(255,255,255,0.6)' : 'rgba(30,40,60,0.65)';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(sx - 9, sy);
    ctx.lineTo(sx + 9, sy);
    ctx.moveTo(sx, sy - 9);
    ctx.lineTo(sx, sy + 9);
    ctx.stroke();
    // 坐标轴指示
    ctx.strokeStyle = 'rgba(255,90,90,0.85)';
    ctx.beginPath();
    ctx.moveTo(sx, sy);
    ctx.lineTo(sx + 26, sy);
    ctx.stroke();
    ctx.strokeStyle = 'rgba(61,220,151,0.85)';
    ctx.beginPath();
    ctx.moveTo(sx, sy);
    ctx.lineTo(sx, sy - 26);
    ctx.stroke();
    ctx.font = '10px ui-monospace, monospace';
    ctx.fillStyle = 'rgba(255,90,90,0.9)';
    ctx.fillText('X', sx + 30, sy + 3);
    ctx.fillStyle = 'rgba(61,220,151,0.9)';
    ctx.fillText('Y', sx - 12, sy - 30);
    ctx.restore();
  }

  _drawBounds(ctx, dark) {
    const b = this.bounds;
    const x0 = this.view.ox + b.minX * this.view.scale;
    const x1 = this.view.ox + b.maxX * this.view.scale;
    const y0 = this.view.oy - b.maxY * this.view.scale;
    const y1 = this.view.oy - b.minY * this.view.scale;
    ctx.save();
    ctx.setLineDash([6, 4]);
    ctx.strokeStyle = dark ? 'rgba(255,255,255,0.18)' : 'rgba(40,50,70,0.25)';
    ctx.lineWidth = 1;
    ctx.strokeRect(x0 + 0.5, y0 + 0.5, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
    ctx.setLineDash([]);
    // 尺寸标注（在包围盒下方）
    if (!this.heavy) {
      ctx.fillStyle = dark ? 'rgba(190,200,220,0.7)' : 'rgba(60,72,95,0.8)';
      ctx.font = '11px ui-monospace, monospace';
      const label = `${(b.maxX - b.minX).toFixed(1)} × ${(b.maxY - b.minY).toFixed(1)} mm`;
      ctx.fillText(label, Math.min(x0 + 2, this.w - 130), Math.max(12, y0 - 6));
    }
    ctx.restore();
  }
}