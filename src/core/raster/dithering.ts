/**
 * 图像抖动算法 —— 移植自 LaserGRBL RasterConverter/Dithering/*
 * 基于 Cyotek.Drawing.Imaging.ColorReduction 的误差扩散实现。
 */

export type DitheringMode =
  | 'Atkinson'
  | 'FloydSteinberg'
  | 'Burks'
  | 'Jarvis'
  | 'Random'
  | 'Sierra2'
  | 'Sierra3'
  | 'SierraLight'
  | 'Stucki'

export const DITHERING_MODES: DitheringMode[] = [
  'Atkinson',
  'FloydSteinberg',
  'Burks',
  'Jarvis',
  'Random',
  'Sierra2',
  'Sierra3',
  'SierraLight',
  'Stucki'
]

/** 抖动算法中文名称 */
export const DITHERING_LABELS: Record<DitheringMode, string> = {
  Atkinson: 'Atkinson（阿特金森）',
  FloydSteinberg: 'Floyd-Steinberg（弗洛伊德）',
  Burks: 'Burks（伯克斯）',
  Jarvis: 'Jarvis-Judice-Ninke（贾维斯）',
  Random: 'Random（随机）',
  Sierra2: 'Sierra2（塞拉 2 行）',
  Sierra3: 'Sierra3（塞拉 3 行）',
  SierraLight: 'Sierra Light（塞拉轻量）',
  Stucki: 'Stucki（斯图基）'
}

interface DiffMatrix {
  matrix: number[][]
  divisor: number
  /** true 表示 divisor 为移位位数（2^divisor） */
  useShifting: boolean
}

const MATRICES: Record<string, DiffMatrix> = {
  FloydSteinberg: {
    matrix: [
      [0, 0, 7],
      [3, 5, 1]
    ],
    divisor: 4,
    useShifting: true
  },
  Atkinson: {
    matrix: [
      [0, 0, 1, 1],
      [1, 1, 1, 0],
      [0, 1, 0, 0]
    ],
    divisor: 3,
    useShifting: true
  },
  Burks: {
    matrix: [
      [0, 0, 0, 8, 4],
      [2, 4, 8, 4, 2]
    ],
    divisor: 5,
    useShifting: true
  },
  Jarvis: {
    matrix: [
      [0, 0, 0, 7, 5],
      [3, 5, 7, 5, 3],
      [1, 3, 5, 3, 1]
    ],
    divisor: 48,
    useShifting: false
  },
  Sierra2: {
    matrix: [
      [0, 0, 0, 4, 3],
      [1, 2, 3, 2, 1]
    ],
    divisor: 4,
    useShifting: true
  },
  Sierra3: {
    matrix: [
      [0, 0, 0, 5, 3],
      [2, 4, 5, 4, 2],
      [0, 2, 3, 2, 0]
    ],
    divisor: 5,
    useShifting: true
  },
  SierraLight: {
    matrix: [
      [0, 0, 2],
      [1, 1, 0]
    ],
    divisor: 2,
    useShifting: true
  },
  Stucki: {
    matrix: [
      [0, 0, 0, 8, 4],
      [2, 4, 8, 4, 2],
      [1, 2, 4, 2, 1]
    ],
    divisor: 42,
    useShifting: false
  }
}

function toByte(v: number): number {
  if (v < 0) return 0
  if (v > 255) return 255
  return v
}

/** 抖动前把像素转为纯黑白 */
function transformPixel(r: number, g: number, b: number): { r: number; g: number; b: number } {
  const gray = 0.299 * r + 0.587 * g + 0.114 * b
  return gray < 128 ? { r: 0, g: 0, b: 0 } : { r: 255, g: 255, b: 255 }
}

/**
 * 对 RGBA 像素数组执行抖动。
 * data 为 Uint8ClampedArray，长度 = width*height*4（RGBA 顺序）。
 */
export function ditherImage(data: Uint8ClampedArray, width: number, height: number, mode: DitheringMode): void {
  if (mode === 'Random') {
    randomDither(data, width, height)
    return
  }
  const dm = MATRICES[mode] ?? MATRICES.FloydSteinberg
  const { matrix, divisor, useShifting } = dm
  const matrixHeight = matrix.length
  const matrixWidth = matrix[0].length

  // 计算起始偏移
  let startingOffset = 0
  for (let i = 0; i < matrixWidth; i++) {
    if (matrix[0][i] !== 0) {
      startingOffset = i - 1
      break
    }
  }

  for (let row = 0; row < height; row++) {
    for (let col = 0; col < width; col++) {
      const index = (row * width + col) * 4
      const origR = data[index]
      const origG = data[index + 1]
      const origB = data[index + 2]
      const a = data[index + 3]

      const t = transformPixel(origR, origG, origB)
      data[index] = t.r
      data[index + 1] = t.g
      data[index + 2] = t.b

      // 误差（与 LaserGRBL 一致：redError 用 R、green 用 G、blue 用 B）
      const redError = origR - t.r
      const greenError = origG - t.g
      const blueError = origB - t.b

      for (let mrow = 0; mrow < matrixHeight; mrow++) {
        const offsetY = row + mrow
        for (let mcol = 0; mcol < matrixWidth; mcol++) {
          const coefficient = matrix[mrow][mcol]
          const offsetX = col + (mcol - startingOffset)
          if (coefficient !== 0 && offsetX > 0 && offsetX < width && offsetY > 0 && offsetY < height) {
            const offsetIndex = (offsetY * width + offsetX) * 4
            let newR: number
            let newG: number
            let newB: number
            if (useShifting) {
              newR = (redError * coefficient) >> divisor
              newG = (greenError * coefficient) >> divisor
              newB = (blueError * coefficient) >> divisor
            } else {
              newR = Math.trunc((redError * coefficient) / divisor)
              newG = Math.trunc((greenError * coefficient) / divisor)
              newB = Math.trunc((blueError * coefficient) / divisor)
            }
            data[offsetIndex] = toByte(data[offsetIndex] + newR)
            data[offsetIndex + 1] = toByte(data[offsetIndex + 1] + newG)
            data[offsetIndex + 2] = toByte(data[offsetIndex + 2] + newB)
          }
        }
      }
      void a
    }
  }
}

function randomDither(data: Uint8ClampedArray, width: number, height: number): void {
  let seed = Date.now() & 0x7fffffff
  const next = () => {
    // 线性同余，模拟 System.Random 序列（范围 0..254）
    seed = (seed * 1103515245 + 12345) & 0x7fffffff
    return seed % 255
  }
  for (let i = 0; i < width * height; i++) {
    const idx = i * 4
    const gray = 0.299 * data[idx] + 0.587 * data[idx + 1] + 0.114 * data[idx + 2]
    if (gray > next()) {
      data[idx] = 255
      data[idx + 1] = 255
      data[idx + 2] = 255
    } else {
      data[idx] = 0
      data[idx + 1] = 0
      data[idx + 2] = 0
    }
  }
}