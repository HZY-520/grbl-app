/**
 * 图像变换 —— 移植自 LaserGRBL RasterConverter/ImageTransform.cs
 * 使用浏览器 Canvas / ImageData 实现（替代 System.Drawing）。
 */
import { ditherImage, type DitheringMode } from './dithering'

export enum Formula {
  SimpleAverage = 0,
  WeightAverage = 1,
  OpticalCorrect = 2,
  Custom = 3
}

export const FORMULA_LABELS: Record<number, string> = {
  0: '简单平均',
  1: '加权平均',
  2: '光学校正 (BT.601)',
  3: '自定义 R/G/B'
}

/** 将图片绘制到指定尺寸的 ImageData（双三次插值由 canvas 提供） */
export function resizeImage(
  source: CanvasImageSource,
  srcW: number,
  srcH: number,
  sizeW: number,
  sizeH: number,
  killAlpha = false,
  interpolation: 'high' | 'low' = 'high'
): ImageData {
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, sizeW)
  canvas.height = Math.max(1, sizeH)
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!
  if (killAlpha) {
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
  }
  ctx.imageSmoothingEnabled = interpolation === 'high'
  ctx.imageSmoothingQuality = interpolation === 'high' ? 'high' : 'low'
  ctx.drawImage(source, 0, 0, srcW, srcH, 0, 0, canvas.width, canvas.height)
  return ctx.getImageData(0, 0, canvas.width, canvas.height)
}

/** 读取图片原始像素 */
export function readImageData(source: CanvasImageSource, w: number, h: number): ImageData {
  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!
  ctx.drawImage(source, 0, 0)
  return ctx.getImageData(0, 0, w, h)
}

/** 灰度化（对应 ImageTransform.GrayScale 的 ColorMatrix 效果） */
export function grayScale(
  data: ImageData,
  R: number,
  G: number,
  B: number,
  brightness: number,
  contrast: number,
  formula: Formula
): void {
  let rf = 0
  let gf = 0
  let bf = 0
  if (formula === Formula.SimpleAverage) {
    rf = 0.333
    gf = 0.333
    bf = 0.333
  } else if (formula === Formula.WeightAverage) {
    rf = 0.333
    gf = 0.444
    bf = 0.222
  } else if (formula === Formula.OpticalCorrect) {
    rf = 0.299
    gf = 0.587
    bf = 0.114
  } else {
    rf = 0.333 * (R / 100)
    gf = 0.333 * (G / 100)
    bf = 0.333 * (B / 100)
  }
  rf *= contrast
  gf *= contrast
  bf *= contrast
  const brightOffset = brightness * 255

  const px = data.data
  for (let i = 0; i < px.length; i += 4) {
    const v = rf * px[i] + gf * px[i + 1] + bf * px[i + 2] + brightOffset
    const c = v < 0 ? 0 : v > 255 ? 255 : v
    px[i] = c
    px[i + 1] = c
    px[i + 2] = c
    // alpha 保持不变
  }
}

/**
 * 白色裁剪（对应 ImageTransform.Whitenize / ColorSubstitution）
 * 将接近白色的像素变为透明（从雕刻路径中剔除）。
 */
export function whitenize(data: ImageData, threshold: number): void {
  const px = data.data
  const min = 255 - threshold
  const max = 255 + threshold
  for (let i = 0; i < px.length; i += 4) {
    const a = px[i + 3]
    if (a === 0) continue
    const r = px[i]
    const g = px[i + 1]
    const b = px[i + 2]
    if (r < max && r > min && g < max && g > min && b < max && b > min) {
      px[i] = 255 - r
      px[i + 1] = 255 - g
      px[i + 2] = 255 - b
      px[i + 3] = 0
    }
  }
}

/**
 * 阈值化（对应 ImageTransform.Threshold）
 * 先合成到白色背景，再按阈值二值化。
 */
export function threshold(data: ImageData, threshold01: number, apply: boolean): void {
  const px = data.data
  const t = threshold01 * 255
  for (let i = 0; i < px.length; i += 4) {
    const a = px[i + 3] / 255
    // 合成到白色背景
    const r = px[i] * a + 255 * (1 - a)
    const g = px[i + 1] * a + 255 * (1 - a)
    const b = px[i + 2] * a + 255 * (1 - a)
    if (apply) {
      px[i] = r < t ? 0 : 255
      px[i + 1] = g < t ? 0 : 255
      px[i + 2] = b < t ? 0 : 255
    } else {
      px[i] = r
      px[i + 1] = g
      px[i + 2] = b
    }
    px[i + 3] = 255
  }
}

/** 抖动（保留 alpha，与 LaserGRBL 一致） */
export function dither(data: ImageData, mode: DitheringMode): void {
  ditherImage(data.data, data.width, data.height, mode)
}

/** 垂直翻转（对应 Bitmap.RotateFlip(RotateNoneFlipY)） */
export function flipVertical(data: ImageData): ImageData {
  const { width, height, data: px } = data
  const out = new Uint8ClampedArray(px.length)
  const rowBytes = width * 4
  for (let y = 0; y < height; y++) {
    const srcStart = y * rowBytes
    const dstStart = (height - 1 - y) * rowBytes
    out.set(px.subarray(srcStart, srcStart + rowBytes), dstStart)
  }
  return new ImageData(out, width, height)
}

/** 检测图片是否为灰度图 */
export function testGrayScale(data: ImageData): boolean {
  const px = data.data
  for (let i = 0; i < px.length; i += 4) {
    if (px[i] !== px[i + 1] || px[i + 1] !== px[i + 2]) return false
  }
  return true
}

/** 把 ImageData 转成 dataURL（预览用） */
export function toDataURL(data: ImageData): string {
  const canvas = document.createElement('canvas')
  canvas.width = data.width
  canvas.height = data.height
  canvas.getContext('2d')!.putImageData(data, 0, 0)
  return canvas.toDataURL('image/png')
}