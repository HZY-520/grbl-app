/**
 * 设备参数
 * 行程范围、原点位置、安全边距、机器最大进给。
 * 由这里统一推导「工作区」与「可用安全区」，
 * 供 G 代码生成（自适应缩放 / 越界判定 / 限速）与预览（绘制行程框）共用。
 */

/** 首次进入时的默认设备参数（常见 400×400 桌面激光机） */
export const DEVICE_DEFAULTS = {
  travelX: 400,
  travelY: 400,
  origin: 'bottom-left',
  margin: 5,
  maxFeed: 3000,
  autoFit: true
};

/** 正数取数：非数值或非正数时回退默认值 */
function positive(v, def) {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? n : def;
}

/** 归一化设备参数：补齐缺失项、裁剪合法区间 */
export function normalizeDevice(dev) {
  const d = { ...DEVICE_DEFAULTS, ...(dev || {}) };
  const margin = Number(d.margin);
  return {
    travelX: positive(d.travelX, DEVICE_DEFAULTS.travelX),
    travelY: positive(d.travelY, DEVICE_DEFAULTS.travelY),
    origin: ['bottom-left', 'top-left', 'center'].includes(d.origin) ? d.origin : DEVICE_DEFAULTS.origin,
    margin: Number.isFinite(margin) ? Math.max(0, margin) : DEVICE_DEFAULTS.margin,
    maxFeed: positive(d.maxFeed, DEVICE_DEFAULTS.maxFeed),
    autoFit: d.autoFit !== false
  };
}

/** 原点位置的中文描述 */
export function originLabel(origin) {
  if (origin === 'top-left') return '左上角';
  if (origin === 'center') return '机床中心';
  return '左下角';
}

/**
 * 整段行程（工作区）在机床坐标系中的矩形。
 * Y 轴向上，与 G 代码坐标系一致。
 */
export function workArea(dev) {
  const d = normalizeDevice(dev);
  switch (d.origin) {
    case 'top-left':
      // 原点在左上角：Y 轴向下为负
      return { minX: 0, minY: -d.travelY, maxX: d.travelX, maxY: 0 };
    case 'center':
      return { minX: -d.travelX / 2, minY: -d.travelY / 2, maxX: d.travelX / 2, maxY: d.travelY / 2 };
    default:
      return { minX: 0, minY: 0, maxX: d.travelX, maxY: d.travelY };
  }
}

/** 可用安全区 = 工作区四周向内收 margin */
export function usableArea(dev) {
  const d = normalizeDevice(dev);
  const a = workArea(d);
  const maxMargin = Math.max(0, Math.min((a.maxX - a.minX) / 2, (a.maxY - a.minY) / 2) - 0.5);
  const m = Math.min(d.margin, maxMargin);
  return {
    minX: a.minX + m,
    minY: a.minY + m,
    maxX: a.maxX - m,
    maxY: a.maxY - m,
    width: a.maxX - a.minX - 2 * m,
    height: a.maxY - a.minY - 2 * m,
    margin: m
  };
}

/** 包围盒超出区域的量（毫米），0 表示完全落在区域内 */
export function overflow(bounds, area) {
  if (!bounds) return 0;
  return Math.max(
    0,
    area.minX - bounds.minX,
    area.minY - bounds.minY,
    bounds.maxX - area.maxX,
    bounds.maxY - area.maxY
  );
}

/** 等比放入安全区所需的缩放系数（仅缩小，不放大），上限 1 */
export function fitScale(bounds, area) {
  if (!bounds) return 1;
  const bw = Math.max(1e-6, bounds.maxX - bounds.minX);
  const bh = Math.max(1e-6, bounds.maxY - bounds.minY);
  return Math.min(1, area.width / bw, area.height / bh);
}