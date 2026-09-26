/**
 * SVG → 雕刻路径
 * 用浏览器原生 DOMParser 解析，遍历图形元素并累积 transform，
 * 把 path 指令（含贝塞尔 / 椭圆弧）在毫米坐标系内自适应离散为折线，
 * 再按元素的 fill / stroke 归类为「填充区域」与「描边轮廓」。
 *
 * 输出为毫米坐标（Y 向上），与其它输入方式保持一致。
 */

import { simplifyDP, dedupe, dist } from './geom.js';
import { scanlineFill, crossHatchFill } from './fill.js';

const SKIP_TAGS = new Set([
  'defs', 'clippath', 'mask', 'pattern', 'symbol', 'marker',
  'lineargradient', 'radialgradient', 'filter', 'style', 'title', 'desc', 'metadata'
]);
const SHAPE_TAGS = new Set(['path', 'rect', 'circle', 'ellipse', 'line', 'polyline', 'polygon']);
const MAX_SUBDIV = 20;

function parseNumbers(str) {
  if (!str) return [];
  const re = /[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?/g;
  const out = [];
  let m;
  while ((m = re.exec(str)) !== null) out.push(parseFloat(m[0]));
  return out;
}

function parseLength(str) {
  if (!str) return 0;
  const m = /^\s*([-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?)\s*([a-zA-Z%]*)\s*$/.exec(str);
  if (!m) return 0;
  const v = parseFloat(m[1]);
  switch (m[2].toLowerCase()) {
    case '': case 'px': return v;
    case 'mm': return (v * 96) / 25.4;
    case 'cm': return (v * 96) / 2.54;
    case 'in': return v * 96;
    case 'pt': return (v * 96) / 72;
    case 'pc': return (v * 12 * 96) / 72;
    case 'em': return v * 16;
    default: return v;
  }
}

function parseViewBox(str) {
  const n = parseNumbers(str);
  if (n.length < 4 || !(n[2] > 0) || !(n[3] > 0)) return null;
  return [n[0], n[1], n[2], n[3]];
}

const IDENTITY = { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 };

function mul(m1, m2) {
  return {
    a: m1.a * m2.a + m1.c * m2.b,
    b: m1.b * m2.a + m1.d * m2.b,
    c: m1.a * m2.c + m1.c * m2.d,
    d: m1.b * m2.c + m1.d * m2.d,
    e: m1.a * m2.e + m1.c * m2.f + m1.e,
    f: m1.b * m2.e + m1.d * m2.f + m1.f
  };
}

function applyMat(m, x, y) {
  return { x: m.a * x + m.c * y + m.e, y: m.b * x + m.d * y + m.f };
}

function transformFromArgs(name, a) {
  switch (name) {
    case 'translate':
      return { a: 1, b: 0, c: 0, d: 1, e: a[0] || 0, f: a[1] || 0 };
    case 'scale': {
      const sx = a.length ? a[0] : 1;
      const sy = a.length > 1 ? a[1] : sx;
      return { a: sx, b: 0, c: 0, d: sy, e: 0, f: 0 };
    }
    case 'rotate': {
      const ang = ((a[0] || 0) * Math.PI) / 180;
      const cos = Math.cos(ang);
      const sin = Math.sin(ang);
      const rot = { a: cos, b: sin, c: -sin, d: cos, e: 0, f: 0 };
      if (a.length >= 3) {
        return mul(
          mul({ a: 1, b: 0, c: 0, d: 1, e: a[1], f: a[2] }, rot),
          { a: 1, b: 0, c: 0, d: 1, e: -a[1], f: -a[2] }
        );
      }
      return rot;
    }
    case 'matrix':
      return { a: a[0], b: a[1], c: a[2], d: a[3], e: a[4], f: a[5] };
    case 'skewX':
      return { a: 1, b: 0, c: Math.tan((((a[0] || 0) * Math.PI) / 180)), d: 1, e: 0, f: 0 };
    case 'skewY':
      return { a: 1, b: Math.tan((((a[0] || 0) * Math.PI) / 180)), c: 0, d: 1, e: 0, f: 0 };
    default:
      return IDENTITY;
  }
}

function parseTransform(str) {
  if (!str) return IDENTITY;
  const re = /([a-zA-Z]+)\s*\(([^)]*)\)/g;
  let result = IDENTITY;
  let m;
  while ((m = re.exec(str)) !== null) {
    result = mul(result, transformFromArgs(m[1], parseNumbers(m[2])));
  }
  return result;
}

function triArea(a, b, c) {
  return Math.abs(a.x * b.y + b.x * c.y + c.x * a.y - a.y * b.x - b.y * c.x - c.y * a.x) / 2;
}

function isFlat(p0, p1, p2, p3, tol) {
  return Math.sqrt(triArea(p0, p1, p2)) < tol && Math.sqrt(triArea(p1, p2, p3)) < tol;
}

function flattenCubicRec(p0, p1, p2, p3, tol, depth, out) {
  if (depth >= MAX_SUBDIV || isFlat(p0, p1, p2, p3, tol)) {
    out.push(p3);
    return;
  }
  const p01 = { x: (p0.x + p1.x) / 2, y: (p0.y + p1.y) / 2 };
  const p12 = { x: (p1.x + p2.x) / 2, y: (p1.y + p2.y) / 2 };
  const p23 = { x: (p2.x + p3.x) / 2, y: (p2.y + p3.y) / 2 };
  const p012 = { x: (p01.x + p12.x) / 2, y: (p01.y + p12.y) / 2 };
  const p123 = { x: (p12.x + p23.x) / 2, y: (p12.y + p23.y) / 2 };
  const mid = { x: (p012.x + p123.x) / 2, y: (p012.y + p123.y) / 2 };
  flattenCubicRec(p0, p01, p012, mid, tol, depth + 1, out);
  flattenCubicRec(mid, p123, p23, p3, tol, depth + 1, out);
}

function vectorAngle(ux, uy, vx, vy) {
  const ta = Math.atan2(uy, ux);
  const tb = Math.atan2(vy, vx);
  if (tb >= ta) return tb - ta;
  return Math.PI * 2 - (ta - tb);
}

/** 椭圆弧 → 若干 ≤90° 的三次贝塞尔 */
function arcToCubics(sx, sy, rx0, ry0, angleDeg, large, sweep, ex, ey, emit) {
  const phi = (angleDeg * Math.PI) / 180;
  const sinPhi = Math.sin(phi);
  const cosPhi = Math.cos(phi);
  const x1dash = (cosPhi * (sx - ex)) / 2 + (sinPhi * (sy - ey)) / 2;
  const y1dash = (-sinPhi * (sx - ex)) / 2 + (cosPhi * (sy - ey)) / 2;
  const numerator = rx0 * rx0 * ry0 * ry0 - rx0 * rx0 * y1dash * y1dash - ry0 * ry0 * x1dash * x1dash;

  let rx = rx0;
  let ry = ry0;
  let root;
  if (numerator < 0) {
    const s = Math.sqrt(1 - numerator / (rx0 * rx0 * ry0 * ry0));
    rx *= s;
    ry *= s;
    root = 0;
  } else {
    const sign = (large === 1 && sweep === 1) || (large === 0 && sweep === 0) ? -1 : 1;
    root = sign * Math.sqrt(numerator / (rx0 * rx0 * y1dash * y1dash + ry0 * ry0 * x1dash * x1dash));
  }

  const cxdash = (root * rx * y1dash) / ry;
  const cydash = (-root * ry * x1dash) / rx;
  const cx = cosPhi * cxdash - sinPhi * cydash + (sx + ex) / 2;
  const cy = sinPhi * cxdash + cosPhi * cydash + (sy + ey) / 2;

  let theta1 = vectorAngle(1, 0, (x1dash - cxdash) / rx, (y1dash - cydash) / ry);
  let dtheta = vectorAngle(
    (x1dash - cxdash) / rx, (y1dash - cydash) / ry,
    (-x1dash - cxdash) / rx, (-y1dash - cydash) / ry
  );
  if (sweep === 0 && dtheta > 0) dtheta -= 2 * Math.PI;
  else if (sweep === 1 && dtheta < 0) dtheta += 2 * Math.PI;

  const segments = Math.max(1, Math.ceil(Math.abs(dtheta / (Math.PI / 2))));
  const delta = dtheta / segments;
  const t = ((8 / 3) * Math.sin(delta / 4) * Math.sin(delta / 4)) / Math.sin(delta / 2);

  let startX = sx;
  let startY = sy;
  for (let i = 0; i < segments; i++) {
    const cosT1 = Math.cos(theta1);
    const sinT1 = Math.sin(theta1);
    const theta2 = theta1 + delta;
    const cosT2 = Math.cos(theta2);
    const sinT2 = Math.sin(theta2);
    const endpointX = cosPhi * rx * cosT2 - sinPhi * ry * sinT2 + cx;
    const endpointY = sinPhi * rx * cosT2 + cosPhi * ry * sinT2 + cy;
    const dx1 = t * (-cosPhi * rx * sinT1 - sinPhi * ry * cosT1);
    const dy1 = t * (-sinPhi * rx * sinT1 + cosPhi * ry * cosT1);
    const dxe = t * (cosPhi * rx * sinT2 + sinPhi * ry * cosT2);
    const dye = t * (sinPhi * rx * sinT2 - cosPhi * ry * cosT2);
    emit(
      { x: startX, y: startY },
      { x: startX + dx1, y: startY + dy1 },
      { x: endpointX + dxe, y: endpointY + dye },
      { x: endpointX, y: endpointY }
    );
    theta1 = theta2;
    startX = endpointX;
    startY = endpointY;
  }
}

function tokenizePath(d) {
  const re = /([MmLlHhVvCcSsQqTtAaZz])|([-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?)/g;
  const groups = [];
  let cur = null;
  let m;
  while ((m = re.exec(d)) !== null) {
    if (m[1]) {
      cur = { cmd: m[1], nums: [] };
      groups.push(cur);
    } else if (m[2] !== undefined && cur) {
      cur.nums.push(parseFloat(m[2]));
    }
  }
  return groups;
}

/** path 的 d → 毫米坐标系下的折线子路径 */
function processPath(d, m, tol, out) {
  const groups = tokenizePath(d);
  if (!groups.length) return;

  let cur = { x: 0, y: 0 };
  let subStart = { x: 0, y: 0 };
  let currentPts = [];
  let prevCubicCtrl = null;
  let prevQuadCtrl = null;
  let lastCmd = '';
  let firstCmd = true;
  let closedFlag = false;

  const finish = () => {
    if (currentPts.length >= 2) out.push({ pts: currentPts, closed: closedFlag });
    currentPts = [];
    closedFlag = false;
  };
  const ensureSub = () => {
    if (!currentPts.length) {
      currentPts.push(applyMat(m, cur.x, cur.y));
      subStart = cur;
    }
  };
  const emitCubic = (p0, p1, p2, p3) => {
    flattenCubicRec(
      applyMat(m, p0.x, p0.y), applyMat(m, p1.x, p1.y),
      applyMat(m, p2.x, p2.y), applyMat(m, p3.x, p3.y), tol, 0, currentPts
    );
  };
  const emitQuad = (p0, c, p1) => {
    const c1 = { x: p0.x + (2 / 3) * (c.x - p0.x), y: p0.y + (2 / 3) * (c.y - p0.y) };
    const c2 = { x: p1.x + (2 / 3) * (c.x - p1.x), y: p1.y + (2 / 3) * (c.y - p1.y) };
    emitCubic(p0, c1, c2, p1);
  };

  for (const g of groups) {
    const upper = g.cmd.toUpperCase();
    const rel = g.cmd !== upper;
    const nums = g.nums;
    lastCmd = upper;

    switch (upper) {
      case 'M':
        for (let i = 0; i + 1 < nums.length; i += 2) {
          let px = nums[i];
          let py = nums[i + 1];
          if (rel && !(firstCmd && i === 0)) {
            px += cur.x;
            py += cur.y;
          }
          if (i === 0) {
            finish();
            cur = { x: px, y: py };
            subStart = cur;
            currentPts = [applyMat(m, px, py)];
          } else {
            cur = { x: px, y: py };
            currentPts.push(applyMat(m, px, py));
          }
        }
        prevCubicCtrl = null;
        prevQuadCtrl = null;
        break;
      case 'L':
        for (let i = 0; i + 1 < nums.length; i += 2) {
          const px = rel ? cur.x + nums[i] : nums[i];
          const py = rel ? cur.y + nums[i + 1] : nums[i + 1];
          ensureSub();
          cur = { x: px, y: py };
          currentPts.push(applyMat(m, px, py));
        }
        prevCubicCtrl = null;
        prevQuadCtrl = null;
        break;
      case 'H':
        for (let i = 0; i < nums.length; i++) {
          const px = rel ? cur.x + nums[i] : nums[i];
          ensureSub();
          cur = { x: px, y: cur.y };
          currentPts.push(applyMat(m, cur.x, cur.y));
        }
        prevCubicCtrl = null;
        prevQuadCtrl = null;
        break;
      case 'V':
        for (let i = 0; i < nums.length; i++) {
          const py = rel ? cur.y + nums[i] : nums[i];
          ensureSub();
          cur = { x: cur.x, y: py };
          currentPts.push(applyMat(m, cur.x, cur.y));
        }
        prevCubicCtrl = null;
        prevQuadCtrl = null;
        break;
      case 'C':
        for (let i = 0; i + 5 < nums.length; i += 6) {
          const c1 = { x: rel ? cur.x + nums[i] : nums[i], y: rel ? cur.y + nums[i + 1] : nums[i + 1] };
          const c2 = { x: rel ? cur.x + nums[i + 2] : nums[i + 2], y: rel ? cur.y + nums[i + 3] : nums[i + 3] };
          const p = { x: rel ? cur.x + nums[i + 4] : nums[i + 4], y: rel ? cur.y + nums[i + 5] : nums[i + 5] };
          ensureSub();
          emitCubic(cur, c1, c2, p);
          prevCubicCtrl = c2;
          prevQuadCtrl = null;
          cur = p;
        }
        break;
      case 'S':
        for (let i = 0; i + 3 < nums.length; i += 4) {
          const c2 = { x: rel ? cur.x + nums[i] : nums[i], y: rel ? cur.y + nums[i + 1] : nums[i + 1] };
          const p = { x: rel ? cur.x + nums[i + 2] : nums[i + 2], y: rel ? cur.y + nums[i + 3] : nums[i + 3] };
          const useMirror = (lastCmd === 'C' || lastCmd === 'S') && prevCubicCtrl;
          const c1 = useMirror
            ? { x: 2 * cur.x - prevCubicCtrl.x, y: 2 * cur.y - prevCubicCtrl.y }
            : { x: cur.x, y: cur.y };
          ensureSub();
          emitCubic(cur, c1, c2, p);
          prevCubicCtrl = c2;
          prevQuadCtrl = null;
          cur = p;
        }
        break;
      case 'Q':
        for (let i = 0; i + 3 < nums.length; i += 4) {
          const c = { x: rel ? cur.x + nums[i] : nums[i], y: rel ? cur.y + nums[i + 1] : nums[i + 1] };
          const p = { x: rel ? cur.x + nums[i + 2] : nums[i + 2], y: rel ? cur.y + nums[i + 3] : nums[i + 3] };
          ensureSub();
          emitQuad(cur, c, p);
          prevQuadCtrl = c;
          prevCubicCtrl = null;
          cur = p;
        }
        break;
      case 'T':
        for (let i = 0; i + 1 < nums.length; i += 2) {
          const p = { x: rel ? cur.x + nums[i] : nums[i], y: rel ? cur.y + nums[i + 1] : nums[i + 1] };
          const useMirror = (lastCmd === 'Q' || lastCmd === 'T') && prevQuadCtrl;
          const c = useMirror
            ? { x: 2 * cur.x - prevQuadCtrl.x, y: 2 * cur.y - prevQuadCtrl.y }
            : { x: cur.x, y: cur.y };
          ensureSub();
          emitQuad(cur, c, p);
          prevQuadCtrl = c;
          prevCubicCtrl = null;
          cur = p;
        }
        break;
      case 'A':
        for (let i = 0; i + 6 < nums.length; i += 7) {
          const rx = nums[i];
          const ry = nums[i + 1];
          const rot = nums[i + 2];
          const large = nums[i + 3];
          const sweep = nums[i + 4];
          const p = { x: rel ? cur.x + nums[i + 5] : nums[i + 5], y: rel ? cur.y + nums[i + 6] : nums[i + 6] };
          ensureSub();
          if (rx > 0 && ry > 0) arcToCubics(cur.x, cur.y, rx, ry, rot, large, sweep, p.x, p.y, emitCubic);
          else currentPts.push(applyMat(m, p.x, p.y));
          prevCubicCtrl = null;
          prevQuadCtrl = null;
          cur = p;
        }
        break;
      case 'Z':
        if (currentPts.length) {
          const startMm = applyMat(m, subStart.x, subStart.y);
          const last = currentPts[currentPts.length - 1];
          if (dist(startMm, last) > 1e-6) currentPts.push(startMm);
          closedFlag = true;
        }
        finish();
        cur = { ...subStart };
        prevCubicCtrl = null;
        prevQuadCtrl = null;
        break;
      default:
        break;
    }
    firstCmd = false;
  }
  finish();
}

function numAttr(el, name) {
  return parseLength(el.getAttribute(name));
}

function parsePointsAttr(str) {
  const n = parseNumbers(str);
  const pts = [];
  for (let i = 0; i + 1 < n.length; i += 2) pts.push({ x: n[i], y: n[i + 1] });
  return pts;
}

function shapeToPathData(el, tag) {
  switch (tag) {
    case 'path':
      return el.getAttribute('d');
    case 'rect': {
      const x = numAttr(el, 'x');
      const y = numAttr(el, 'y');
      const w = numAttr(el, 'width');
      const h = numAttr(el, 'height');
      if (!(w > 0) || !(h > 0)) return null;
      let rx = numAttr(el, 'rx');
      let ry = numAttr(el, 'ry');
      if (rx <= 0 && ry > 0) rx = ry;
      if (ry <= 0 && rx > 0) ry = rx;
      rx = Math.min(rx, w / 2);
      ry = Math.min(ry, h / 2);
      if (rx > 0 && ry > 0) {
        return `M ${x + rx} ${y} H ${x + w - rx} A ${rx} ${ry} 0 0 1 ${x + w} ${y + ry} `
          + `V ${y + h - ry} A ${rx} ${ry} 0 0 1 ${x + w - rx} ${y + h} `
          + `H ${x + rx} A ${rx} ${ry} 0 0 1 ${x} ${y + h - ry} V ${y + ry} A ${rx} ${ry} 0 0 1 ${x + rx} ${y} Z`;
      }
      return `M ${x} ${y} H ${x + w} V ${y + h} H ${x} Z`;
    }
    case 'circle': {
      const cx = numAttr(el, 'cx');
      const cy = numAttr(el, 'cy');
      const r = numAttr(el, 'r');
      if (!(r > 0)) return null;
      return `M ${cx - r} ${cy} A ${r} ${r} 0 1 1 ${cx + r} ${cy} A ${r} ${r} 0 1 1 ${cx - r} ${cy} Z`;
    }
    case 'ellipse': {
      const cx = numAttr(el, 'cx');
      const cy = numAttr(el, 'cy');
      const rx = numAttr(el, 'rx');
      const ry = numAttr(el, 'ry');
      if (!(rx > 0) || !(ry > 0)) return null;
      return `M ${cx - rx} ${cy} A ${rx} ${ry} 0 1 1 ${cx + rx} ${cy} A ${rx} ${ry} 0 1 1 ${cx - rx} ${cy} Z`;
    }
    case 'line':
      return `M ${numAttr(el, 'x1')} ${numAttr(el, 'y1')} L ${numAttr(el, 'x2')} ${numAttr(el, 'y2')}`;
    case 'polyline':
    case 'polygon': {
      const pts = parsePointsAttr(el.getAttribute('points'));
      if (pts.length < 2) return null;
      let d = `M ${pts[0].x} ${pts[0].y}`;
      for (let i = 1; i < pts.length; i++) d += ` L ${pts[i].x} ${pts[i].y}`;
      if (tag === 'polygon') d += ' Z';
      return d;
    }
    default:
      return null;
  }
}

/** 解析行内 style */
function parseStyleAttr(str) {
  const out = {};
  if (!str) return out;
  for (const part of str.split(';')) {
    const i = part.indexOf(':');
    if (i < 0) continue;
    out[part.slice(0, i).trim().toLowerCase()] = part.slice(i + 1).trim();
  }
  return out;
}

/** 读取元素自身的绘制样式（不继承） */
function ownPaint(el) {
  const style = parseStyleAttr(el.getAttribute('style'));
  const pick = (name) => (style[name] != null ? style[name] : el.getAttribute(name));
  return {
    fill: pick('fill'),
    stroke: pick('stroke'),
    fillOpacity: pick('fill-opacity'),
    strokeOpacity: pick('stroke-opacity'),
    opacity: pick('opacity'),
    display: pick('display'),
    visibility: pick('visibility'),
    strokeWidth: pick('stroke-width')
  };
}

function isPainted(value, opacity) {
  if (value == null) return null;
  const v = String(value).trim().toLowerCase();
  if (!v || v === 'none' || v === 'transparent') return false;
  if (opacity != null && parseFloat(opacity) === 0) return false;
  if (v.startsWith('url(')) return true; // 渐变 / 图案按实心处理
  return true;
}

/** 递归遍历，收集「填充区域」与「描边轮廓」 */
function walk(el, parentMatrix, inherited, ctx) {
  const children = el.children;
  for (let i = 0; i < children.length; i++) {
    const child = children[i];
    const tag = child.tagName.toLowerCase();
    if (SKIP_TAGS.has(tag)) continue;

    const own = ownPaint(child);
    const paint = {
      fill: own.fill != null ? own.fill : inherited.fill,
      stroke: own.stroke != null ? own.stroke : inherited.stroke,
      fillOpacity: own.fillOpacity != null ? own.fillOpacity : inherited.fillOpacity,
      strokeOpacity: own.strokeOpacity != null ? own.strokeOpacity : inherited.strokeOpacity,
      display: own.display != null ? own.display : inherited.display,
      visibility: own.visibility != null ? own.visibility : inherited.visibility
    };
    if (own.opacity === '0') continue;
    if (paint.display === 'none' || paint.visibility === 'hidden') continue;

    const m = mul(parentMatrix, parseTransform(child.getAttribute('transform')));
    const subpaths = [];
    if (SHAPE_TAGS.has(tag)) {
      const d = shapeToPathData(child, tag);
      if (d) processPath(d, m, ctx.tolerance, subpaths);
      const filled = isPainted(paint.fill, paint.fillOpacity);
      const stroked = isPainted(paint.stroke, paint.strokeOpacity);
      for (const sp of subpaths) ctx.collect(sp, filled !== false, stroked === true);
    } else {
      walk(child, m, paint, ctx);
    }
  }
}

/**
 * SVG 转雕刻路径
 * @param {string} svgText
 * @param {Object} opts
 * @param {number} opts.targetWidthMm
 * @param {number} [opts.targetHeightMm]
 * @param {number} [opts.tolerance=0.1] 曲线离散精度（mm）
 * @param {'auto'|'fill'|'stroke'|'all'} [opts.extract='auto']
 * @param {number} [opts.fillSpacing=0.2]
 * @param {number} [opts.fillAngle=0]
 * @param {boolean} [opts.crossHatch]
 * @param {number} [opts.simplifyTolerance=0.05]
 * @returns {{strokes:Array, bounds:Object, info:Object}}
 */
export function svgToStrokes(svgText, opts) {
  if (!svgText || !String(svgText).trim()) throw new Error('请上传或粘贴 SVG 内容');
  const doc = new DOMParser().parseFromString(String(svgText), 'image/svg+xml');
  if (doc.querySelector('parsererror')) throw new Error('SVG 解析失败：文件格式无效');
  const root = doc.documentElement;
  if (!root || root.tagName.toLowerCase() !== 'svg') throw new Error('SVG 解析失败：缺少 <svg> 根元素');

  const tolerance = opts.tolerance == null ? 0.1 : opts.tolerance;
  const targetW = Math.max(1, opts.targetWidthMm || 100);

  const vb = parseViewBox(root.getAttribute('viewBox'));
  let minX = 0;
  let minY = 0;
  let contentW;
  let contentH;
  if (vb) {
    [minX, minY, contentW, contentH] = vb;
  } else {
    contentW = parseLength(root.getAttribute('width'));
    contentH = parseLength(root.getAttribute('height'));
    if (contentW <= 0 && contentH > 0) contentW = contentH;
    if (contentH <= 0 && contentW > 0) contentH = contentW;
  }
  if (!(contentW > 0)) contentW = 100;
  if (!(contentH > 0)) contentH = 100;

  const scaleX = targetW / contentW;
  const heightMm = opts.targetHeightMm ? opts.targetHeightMm : (targetW * contentH) / contentW;
  const scaleY = heightMm / contentH;

  // 用户坐标 → 毫米（含 Y 轴翻转）
  const globalMatrix = {
    a: scaleX, b: 0, c: 0, d: -scaleY,
    e: -minX * scaleX,
    f: (contentH + minY) * scaleY
  };

  const extract = opts.extract || 'auto';
  const outlineRaw = [];
  const fillRaw = [];

  const ctx = {
    tolerance,
    collect(sp, filled, stroked) {
      if (extract === 'all') {
        outlineRaw.push(sp);
        return;
      }
      if (extract === 'fill' && filled) fillRaw.push(sp);
      else if (extract === 'stroke' && stroked) outlineRaw.push(sp);
      else if (extract === 'auto') {
        if (filled) fillRaw.push(sp);
        if (stroked) outlineRaw.push(sp);
      }
    }
  };

  walk(root, globalMatrix, { fill: 'black', stroke: 'none' }, ctx);

  const tol = opts.simplifyTolerance == null ? 0.05 : opts.simplifyTolerance;
  const power = opts.power == null ? 1 : opts.power;
  const strokes = [];

  const prep = (pts) => {
    let p = dedupe(pts, 0.0005);
    if (tol > 0) p = simplifyDP(p, tol);
    return p;
  };

  // 描边轮廓
  for (const sp of outlineRaw) {
    const pts = prep(sp.pts);
    if (pts.length < 2) continue;
    strokes.push({ pts, power, closed: sp.closed, group: 'svg-stroke' });
  }

  // 填充区域（非零环绕数，孔洞自动掏空）
  const polygons = [];
  for (const sp of fillRaw) {
    const pts = prep(sp.pts);
    if (pts.length < 3) continue;
    polygons.push(pts);
  }
  if (polygons.length) {
    const fillOpts = {
      spacing: opts.fillSpacing || 0.2,
      angleDeg: opts.fillAngle || 0,
      power,
      serpentine: true,
      group: 'svg-fill'
    };
    strokes.push(...(opts.crossHatch ? crossHatchFill(polygons, fillOpts) : scanlineFill(polygons, fillOpts)));
  }

  if (!strokes.length) throw new Error('SVG 中没有可雕刻内容（可能全部为隐藏或无色元素）');

  return {
    strokes,
    bounds: null,
    info: { outlines: outlineRaw.length, polygons: polygons.length, widthMm: targetW, heightMm }
  };
}