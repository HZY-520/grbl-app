/**
 * 灰度 → 二值（抖动）算法集合
 * 约定：输入灰度 0 = 黑（需要雕刻），1 = 白（不雕刻）。
 * 输出 Uint8Array，1 表示开激光，0 表示关闭。
 */

export const DITHER_ALGOS = [
  ['floyd', 'Floyd–Steinberg'],
  ['atkinson', 'Atkinson'],
  ['jarvis', 'Jarvis–Judice–Ninke'],
  ['stucki', 'Stucki'],
  ['burkes', 'Burkes'],
  ['sierra', 'Sierra'],
  ['bayer4', 'Bayer 4×4'],
  ['bayer8', 'Bayer 8×8'],
  ['threshold', '固定阈值']
];

// 误差扩散核： dx, dy, weight（权重总和为 1）
const KERNELS = {
  floyd: [[1, 0, 7 / 16], [-1, 1, 3 / 16], [0, 1, 5 / 16], [1, 1, 1 / 16]],
  atkinson: [
    [1, 0, 1 / 8], [2, 0, 1 / 8],
    [-1, 1, 1 / 8], [0, 1, 1 / 8], [1, 1, 1 / 8],
    [0, 2, 1 / 8]
  ],
  jarvis: [
    [1, 0, 7 / 48], [2, 0, 5 / 48],
    [-2, 1, 3 / 48], [-1, 1, 5 / 48], [0, 1, 7 / 48], [1, 1, 5 / 48], [2, 1, 3 / 48],
    [-2, 2, 1 / 48], [-1, 2, 3 / 48], [0, 2, 5 / 48], [1, 2, 3 / 48], [2, 2, 1 / 48]
  ],
  stucki: [
    [1, 0, 8 / 42], [2, 0, 4 / 42],
    [-2, 1, 2 / 42], [-1, 1, 4 / 42], [0, 1, 8 / 42], [1, 1, 4 / 42], [2, 1, 2 / 42],
    [-2, 2, 1 / 42], [-1, 2, 2 / 42], [0, 2, 4 / 42], [1, 2, 2 / 42], [2, 2, 1 / 42]
  ],
  burkes: [
    [1, 0, 8 / 32], [2, 0, 4 / 32],
    [-2, 1, 2 / 32], [-1, 1, 4 / 32], [0, 1, 8 / 32], [1, 1, 4 / 32], [2, 1, 2 / 32]
  ],
  sierra: [
    [1, 0, 5 / 32], [2, 0, 3 / 32],
    [-2, 1, 2 / 32], [-1, 1, 4 / 32], [0, 1, 5 / 32], [1, 1, 4 / 32], [2, 1, 2 / 32],
    [-1, 2, 2 / 32], [0, 2, 3 / 32], [1, 2, 2 / 32]
  ]
};

function makeBayer(n) {
  // 递归构造 Bayer 矩阵，返回 0..1 的阈值矩阵
  let m = [[0]];
  let size = 1;
  while (size < n) {
    const next = [];
    for (let y = 0; y < size * 2; y++) next.push(new Array(size * 2).fill(0));
    for (let y = 0; y < size; y++) {
      for (let x = 0; x < size; x++) {
        const v = m[y][x] * 4;
        next[y][x] = v + 0;
        next[y][x + size] = v + 2;
        next[y + size][x] = v + 3;
        next[y + size][x + size] = v + 1;
      }
    }
    m = next;
    size *= 2;
  }
  const total = size * size;
  return { matrix: m, size, max: total };
}

const BAYER_CACHE = {};
function getBayer(n) {
  if (!BAYER_CACHE[n]) BAYER_CACHE[n] = makeBayer(n);
  return BAYER_CACHE[n];
}

function clamp01(v) {
  return v < 0 ? 0 : v > 1 ? 1 : v;
}

/**
 * 执行抖动
 * @param {Float32Array} gray 灰度数组（0=黑 … 1=白），长度为 w*h
 * @param {number} w
 * @param {number} h
 * @param {Object} opts { algo, strength:0..1, threshold:0..1 }
 * @returns {Uint8Array} 1 = 开激光
 */
export function ditherGray(gray, w, h, opts = {}) {
  const algo = opts.algo || 'floyd';
  const strength = opts.strength == null ? 1 : clamp01(opts.strength);
  const threshold = opts.threshold == null ? 0.5 : opts.threshold;
  const out = new Uint8Array(w * h);

  if (algo === 'bayer4' || algo === 'bayer8') {
    const n = algo === 'bayer4' ? 4 : 8;
    const { matrix, size } = getBayer(n);
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const t = (matrix[y % size][x % size] + 0.5) / (size * size);
        out[y * w + x] = gray[y * w + x] > t ? 1 : 0;
      }
    }
    return out;
  }

  if (algo === 'threshold') {
    for (let i = 0; i < gray.length; i++) out[i] = gray[i] > threshold ? 1 : 0;
    return out;
  }

  const kernel = KERNELS[algo] || KERNELS.floyd;
  const buf = Float32Array.from(gray);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const idx = y * w + x;
      const old = buf[idx];
      const nv = old > threshold ? 1 : 0;
      out[idx] = nv;
      let err = (old - nv) * strength;
      if (err === 0) continue;
      for (const [dx, dy, wgt] of kernel) {
        const nx = x + dx;
        const ny = y + dy;
        if (nx < 0 || nx >= w || ny >= h) continue;
        buf[ny * w + nx] += err * wgt;
      }
    }
  }
  return out;
}

/**
 * 灰度量化（多级功率）：把 0..1 灰度映射为 levels 级离散功率
 * @returns {Float32Array} 每像素功率 0..1（0 表示不雕刻）
 */
export function quantizeGray(gray, levels) {
  const n = Math.max(2, Math.round(levels));
  const out = new Float32Array(gray.length);
  for (let i = 0; i < gray.length; i++) {
    const v = clamp01(gray[i]);
    out[i] = Math.round((1 - v) * (n - 1)) / (n - 1); // 越黑功率越高
  }
  return out;
}