/**
 * GRBL 兼容 G 代码生成
 * 输出 G0 空移 / G1 雕刻 + M3|M4 开激光 + S 功率 + M5 关激光。
 * 「精简模态输出」会自动省略与上一行相同的轴坐标与 F 值，可显著减小文件体积。
 */

import { fmt, formatDuration, formatBytes, clamp } from './geom.js';

/**
 * @param {Object} plan optimizeStrokes 的结果
 * @param {Object} opts
 * @param {number} opts.bounds 包围盒 {minX,minY,maxX,maxY}
 * @param {'mm'|'in'} [opts.unit='mm']
 * @param {'M3'|'M4'} [opts.laserMode='M3']
 * @param {number} [opts.feed=1000] 雕刻速度 mm/min
 * @param {number} [opts.travelFeed=3000] 空移速度 mm/min
 * @param {number} [opts.powerMinS=0] 最低功率 S 值
 * @param {number} [opts.powerMaxS=800] 最高功率 S 值
 * @param {boolean} [opts.compact=true] 精简模态坐标
 * @param {boolean} [opts.useS0=true] 用 S0 代替 M5 关激光
 * @param {boolean} [opts.frame=false] 追加边框
 * @param {boolean} [opts.home=true] 结束时返回原点
 * @param {string} [opts.header] 自定义文件头
 * @param {string} [opts.footer] 自定义文件尾
 * @param {string[]} [opts.comments] 额外的注释行
 * @returns {{lines:string[], text:string, stats:Object}}
 */
export function buildGcode(plan, opts) {
  const unit = opts.unit === 'in' ? 'in' : 'mm';
  const k = unit === 'in' ? 1 / 25.4 : 1;
  const digits = unit === 'in' ? 4 : 3;
  const laserMode = opts.laserMode === 'M4' ? 'M4' : 'M3';
  const feed = Math.max(1, opts.feed || 1000);
  const travelFeed = Math.max(1, opts.travelFeed || 3000);
  const powerMinS = Math.max(0, Math.round(opts.powerMinS || 0));
  const powerMaxS = Math.max(1, Math.round(opts.powerMaxS || 800));
  const compact = opts.compact !== false;
  const useS0 = opts.useS0 !== false;
  const home = opts.home !== false;
  const bounds = opts.bounds;

  const lines = [];
  const conv = (v) => fmt(v * k, digits);
  const sFor = (power) => Math.round(powerMinS + (powerMaxS - powerMinS) * clamp(power, 0, 1));

  const state = { x: null, y: null, f: null, s: null, on: false };
  const laserOff = () => (useS0 ? `${laserMode} S0` : 'M5');

  const moveLine = (code, p, feedValue) => {
    const x = conv(p.x);
    const y = conv(p.y);
    const parts = [code];
    if (!compact || state.x === null || x !== state.x) parts.push(`X${x}`);
    if (!compact || state.y === null || y !== state.y) parts.push(`Y${y}`);
    if (compact && parts.length === 1) return false; // 位置未变化，无需输出
    if (feedValue != null) {
      const f = conv(feedValue);
      if (!compact || state.f !== f) parts.push(`F${f}`);
      state.f = f;
    }
    state.x = x;
    state.y = y;
    lines.push(parts.join(' '));
    return true;
  };

  // ---- 文件头 ----
  lines.push('; Laser GCode Studio — 激光雕刻代码');
  for (const c of opts.comments || []) lines.push(`; ${c}`);
  lines.push(`; 单位 ${unit === 'in' ? '英寸 (G20)' : '毫米 (G21)'} / ${laserMode} 功率模式 / 雕刻 F${fmt(feed)} 空移 F${fmt(travelFeed)}`);
  lines.push(unit === 'in' ? 'G20' : 'G21');
  lines.push('G90');
  lines.push('G17');
  lines.push(laserOff());
  if (opts.header && opts.header.trim()) {
    for (const l of opts.header.split('\n')) if (l.trim()) lines.push(l.trim());
  }
  // 空移速度先设定一次
  lines.push(`G0 F${conv(travelFeed)}`);
  state.f = conv(travelFeed);

  let markLength = 0;

  // ---- 边框 ----
  if (opts.frame && bounds) {
    const corners = [
      { x: bounds.minX, y: bounds.minY },
      { x: bounds.maxX, y: bounds.minY },
      { x: bounds.maxX, y: bounds.maxY },
      { x: bounds.minX, y: bounds.maxY },
      { x: bounds.minX, y: bounds.minY }
    ];
    lines.push('; --- 边框 ---');
    moveLine('G0', corners[0], null);
    lines.push(`${laserMode} S${powerMaxS}`);
    state.on = true;
    state.s = String(powerMaxS);
    for (let i = 1; i < corners.length; i++) moveLine('G1', corners[i], feed);
    lines.push(laserOff());
    state.on = false;
    markLength += 2 * ((bounds.maxX - bounds.minX) + (bounds.maxY - bounds.minY));
  }

  // ---- 主体 ----
  const sequences = plan.sequences && plan.sequences.length ? plan.sequences : [plan.items];
  sequences.forEach((items, pi) => {
    if (sequences.length > 1) lines.push(`; --- 第 ${pi + 1} / ${sequences.length} 遍 ---`);
    items.forEach((item, idx) => {
      const pts = item.pts;
      if (!item.chained) {
        if (state.on) {
          lines.push(laserOff());
          state.on = false;
          state.s = null;
        }
        const moved = moveLine('G0', pts[0], null);
        if (!moved && compact) {
          // 与当前位置重合：仍需设定速度但不输出移动
          state.x = conv(pts[0].x);
          state.y = conv(pts[0].y);
        }
      }
      const s = String(sFor(item.power));
      if (!state.on || state.s !== s) {
        lines.push(`${laserMode} S${s}`);
        state.on = true;
        state.s = s;
      }
      for (let i = 1; i < pts.length; i++) moveLine('G1', pts[i], feed);
      if (item.closed && pts.length > 2) moveLine('G1', pts[0], feed);
      markLength += item.length;
      // 未衔接的路径之间需要关闭激光
      const next = items[idx + 1];
      if (!next || !next.chained) {
        lines.push(laserOff());
        state.on = false;
        state.s = null;
      }
    });
  });

  // ---- 文件尾 ----
  lines.push(laserOff());
  if (home) {
    lines.push(`G0 X0 Y0`);
    state.x = '0';
    state.y = '0';
  }
  if (opts.footer && opts.footer.trim()) {
    for (const l of opts.footer.split('\n')) if (l.trim()) lines.push(l.trim());
  }
  lines.push('; 结束');

  const text = lines.join('\n') + '\n';
  const markMin = markLength / feed;
  const travelMin = plan.travelLength / travelFeed;
  const estSeconds = (markMin + travelMin) * 60 + plan.count * 0.12;

  return {
    lines,
    text,
    stats: {
      lines: lines.length,
      bytes: new Blob([text]).size,
      markLength,
      travelLength: plan.travelLength,
      estSeconds,
      estText: formatDuration(estSeconds),
      sizeText: formatBytes(new Blob([text]).size)
    }
  };
}