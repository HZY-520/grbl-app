package com.lasergrbl.core

/**
 * :core 模块占位。
 *
 * Phase 2 会把 v2 `src/core` 的 10,393 行 TypeScript 按模块移植到这里
 * （GRBL 协议与流式发送、光栅抖动、矢量 Potrace/中心线、Hershey 文字、G 代码分析、参数表）。
 * 移植期间以 v2 的 TS 实现为 oracle：tools/golden 生成黄金样本，本模块的 jvmTest 逐条比对。
 */
object CoreModule {

    const val VERSION = "3.0.0-phase0"

    /** 移植进度（Phase 2 完成后应为 25/25）。 */
    const val PORTED_MODULES = 0
    const val TOTAL_MODULES = 25
}
