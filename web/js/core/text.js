/**
 * 文字 → 雕刻路径
 * 用 Canvas 把文字渲染成位图，再矢量化：
 *  - outline 轮廓描边：提取字形闭合轮廓（含孔洞），得到最干净的雕刻线
 *  - fill    实心填充：轮廓 + 扫描线填充（非零环绕数，孔洞自动掏空）
 *  - single  单线骨架：Zhang–Suen 细化后取中心线，适合细线雕刻 / 快速打样
 */

import { traceContours, thinBinary, traceSkeletonPaths } from './trace.js';
import { scanlineFill, crossHatchFill } from './fill.js';
import { chaikin, simplifyDP, clamp } from './geom.js';

const MAX_PIXELS = 4_000_000;

/** 灰度掩膜（alpha > 127 视为墨迹） */
function maskFromCanvas(ctx, w, h) {
  const data = ctx.getImageData(0, 0, w, h).data;
  const mask = new Uint8Array(w * h);
  for (let i = 0, p = 3; i < mask.length; i++, p += 4) mask[i] = data[p] > 127 ? 1 : 0;
  return mask;
}

/** 按字符宽度（含字距）计算文本宽度 */
function measure(ctx, text, spacingPx) {
  if (!text) return 0;
  let w = 0;
  for (const ch of text) w += ctx.measureText(ch).width;
  return w + spacingPx * Math.max(0, [...text].length - 1);
}

/** 逐字符绘制（实现字距控制） */
function drawSpaced(ctx, text, x, y, spacingPx) {
  let cursor = x;
  for (const ch of text) {
    ctx.fillText(ch, cursor, y);
    cursor += ctx.measureText(ch).width + spacingPx;
  }
}

/** 贪心换行：优先在空格断行，超长单词（如中文）按字符断行 */
function wrapLines(ctx, text, maxWidthPx, spacingPx) {
  const out = [];
  for (const raw of String(text).split('\n')) {
    if (!raw) {
      out.push('');
      continue;
    }
    if (!(maxWidthPx > 0)) {
      out.push(raw);
      continue;
    }
    let line = '';
    for (const word of raw.split(/(\s+)/)) {
      const candidate = line + word;
      if (measure(ctx, candidate, spacingPx) <= maxWidthPx || !line) {
        // 单词本身超宽 → 按字符硬断
        if (measure(ctx, candidate, spacingPx) > maxWidthPx && !line && word.length > 1) {
          let buf = '';
          for (const ch of word) {
            if (buf && measure(ctx, buf + ch, spacingPx) > maxWidthPx) {
              out.push(buf);
              buf = ch;
            } else {
              buf += ch;
            }
          }
          line = buf;
        } else {
          line = candidate;
        }
      } else {
        out.push(line.replace(/\s+$/, ''));
        line = word.replace(/^\s+/, '');
      }
    }
    out.push(line.replace(/\s+$/, ''));
  }
  return out;
}

/**
 * 文字转雕刻路径
 * @param {Object} opts
 * @param {string} opts.text
 * @param {string} [opts.fontFamily='sans-serif']
 * @param {number} [opts.fontSizeMm=20]
 * @param {number} [opts.weight=700]
 * @param {boolean} [opts.italic]
 * @param {'left'|'center'|'right'} [opts.align='center']
 * @param {number} [opts.maxWidthMm=120] 0 表示不换行
 * @param {number} [opts.spacingPct=0] 字距（相对字号百分比）
 * @param {number} [opts.lineHeightPct=110] 行距（相对字号百分比）
 * @param {'outline'|'fill'|'single'} [opts.mode='outline']
 * @param {number} [opts.fillSpacing=0.2] 填充行距（mm）
 * @param {number} [opts.fillAngle=0] 填充角度
 * @param {number} [opts.simplifyTolerance=0.05]
 * @returns {{strokes:Array, bounds:Object, info:Object}}
 */
export function textToStrokes(opts) {
  const text = opts.text == null ? '' : String(opts.text);
  if (!text.trim()) throw new Error('请输入要雕刻的文字内容');

  const fontSizeMm = Math.max(0.5, opts.fontSizeMm || 20);
  const weight = opts.weight || 700;
  const family = opts.fontFamily || 'sans-serif';
  const align = opts.align || 'center';
  const maxWidthMm = opts.maxWidthMm || 0;
  const spacingPct = opts.spacingPct || 0;
  const lineHeightPct = opts.lineHeightPct || 110;
  const mode = opts.mode || 'outline';

  // 每毫米对应的渲染像素：小字号自动提高分辨率，避免轮廓失真
  const pxPerMm = clamp(8 + 240 / fontSizeMm, 4, 32);
  let fontSizePx = Math.max(12, Math.round(fontSizeMm * pxPerMm));

  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d', { willReadFrequently: true });

  const render = () => {
    ctx.font = `${opts.italic ? 'italic ' : ''}${weight} ${fontSizePx}px ${family}`;
    ctx.textBaseline = 'alphabetic';
    const spacingPx = (spacingPct / 100) * fontSizePx;
    const lineHeightPx = (lineHeightPct / 100) * fontSizePx;
    const wrapWidthPx = maxWidthMm > 0 ? maxWidthMm * pxPerMm : 0;
    const lines = wrapLines(ctx, text, wrapWidthPx, spacingPx);
    const widths = lines.map((l) => measure(ctx, l, spacingPx));
    const blockW = Math.max(fontSizePx * 0.5, ...widths);
    const pad = Math.ceil(fontSizePx * 0.15) + 4;
    const w = Math.ceil(blockW) + pad * 2;
    const h = Math.ceil((lines.length - 1) * lineHeightPx + fontSizePx * 1.25) + pad * 2;
    canvas.width = w;
    canvas.height = h;
    ctx.clearRect(0, 0, w, h);
    ctx.font = `${opts.italic ? 'italic ' : ''}${weight} ${fontSizePx}px ${family}`;
    ctx.fillStyle = '#000';
    ctx.textBaseline = 'alphabetic';
    const metrics = ctx.measureText('H');
    const ascent = metrics.actualBoundingBoxAscent || fontSizePx * 0.8;
    lines.forEach((line, i) => {
      const lw = widths[i];
      let x = pad;
      if (align === 'center') x = pad + (blockW - lw) / 2;
      else if (align === 'right') x = pad + (blockW - lw);
      const y = pad + ascent + i * lineHeightPx;
      drawSpaced(ctx, line, x, y, spacingPx);
    });
    return { w, h, spacingPx, lineHeightPx };
  };

  let layout = render();
  // 超大文本降分辨率，保证总像素可控
  let guard = 0;
  while (layout.w * layout.h > MAX_PIXELS && fontSizePx > 16 && guard++ < 12) {
    fontSizePx = Math.round(fontSizePx * 0.75);
    layout = render();
  }

  const { w, h } = layout;
  const scale = fontSizeMm / fontSizePx; // 像素 → 毫米

  const mask = maskFromCanvas(ctx, w, h);
  const toMm = (p) => ({ x: p.x * scale, y: (h - p.y) * scale });
  const tol = opts.simplifyTolerance == null ? 0.05 : opts.simplifyTolerance;
  const power = opts.power == null ? 1 : opts.power;

  const strokes = [];
  let info = { glyphs: 0, mode };

  if (mode === 'single') {
    const skel = thinBinary(mask, w, h);
    const paths = traceSkeletonPaths(skel, w, h, { minPoints: Math.max(3, Math.round(fontSizePx * 0.06)) });
    for (const pl of paths) {
      let pts = pl.map(toMm);
      if (tol > 0) pts = simplifyDP(pts, tol);
      if (pts.length < 2) continue;
      strokes.push({ pts, power, group: 'text' });
    }
    info.glyphs = paths.length;
  } else {
    const loops = traceContours(mask, w, h, { minArea: Math.max(2, fontSizePx * fontSizePx * 0.002) });
    info.glyphs = loops.length;
    const polygons = [];
    for (const loop of loops) {
      let pts = loop.pts.map(toMm);
      if (opts.smooth !== false && pts.length > 6) pts = chaikin(pts, 1, true);
      if (tol > 0) {
        pts = simplifyDP(pts.concat([pts[0]]), tol);
        if (pts.length > 1 && pts[0].x === pts[pts.length - 1].x && pts[0].y === pts[pts.length - 1].y) pts.pop();
      }
      if (pts.length < 3) continue;
      polygons.push(pts);
      if (mode === 'outline') strokes.push({ pts, power, closed: true, group: 'text' });
    }
    if (mode === 'fill') {
      const spacing = opts.fillSpacing || 0.2;
      const fillOpts = {
        spacing,
        angleDeg: opts.fillAngle || 0,
        power,
        serpentine: true,
        group: 'text'
      };
      strokes.push(...(opts.fillCross ? crossHatchFill(polygons, fillOpts) : scanlineFill(polygons, fillOpts)));
      info.polygons = polygons.length;
    }
  }

  if (!strokes.length) throw new Error('未能从文字中提取到路径，请尝试更换字体或增大字号');

  return { strokes, bounds: null, info };
}