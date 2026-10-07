package com.lasergrbl.core.internal

/**
 * 当前时间（毫秒）—— 对应 v2 里直接用 `Date.now()` 的地方
 * （目前只有 Random 抖动拿它当随机种子）。放在 expect/actual 里，commonMain 才能保持纯净。
 */
internal expect fun nowMillis(): Long
