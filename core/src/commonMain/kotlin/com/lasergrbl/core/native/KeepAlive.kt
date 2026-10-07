package com.lasergrbl.core.native

/**
 * 后台保活的**策略部分** —— 移植自 v2 `src/core/native/KeepAlive.ts`。
 *
 * 与原生交互的那一半（Android 前台服务 + 常驻通知）在 `:app` 里用 Kotlin 重写，
 * 但"什么时候该发原生调用"（节流）以及通知文案必须与 v2 逐字一致，否则通知行为会变：
 *  v2 只在 **百分比真的变了**（`PROGRESS_STEP = 1`）时才通知原生层，避免刷爆通知；
 *  并且 `start` / `stop` 都会重置节流状态。
 *
 * 这里把策略与原生调用通过 [NativeKeepAlive] 分开，于是节流行为可以在 JVM 单测里
 * 用一个假实现逐条断言（不必起模拟器）：这正是 §6.1 第 2 条的思路。
 */

/** 原生层返回的通知可见性状态（对应 v2 的 `KeepAliveNotificationState`）。 */
enum class KeepAliveNotification {
    /** 通知已显示。 */
    Shown,

    /** 缺少运行时权限（`POST_NOTIFICATIONS`），前台服务仍在保活。 */
    Denied,

    /** 系统不支持（无通知管理器）。 */
    Unsupported;

    companion object {
        /** 字符串名与 v2 一致（`shown` / `denied` / `unsupported`）。 */
        fun fromName(name: String): KeepAliveNotification? =
            entries.firstOrNull { it.name.lowercase() == name.lowercase() }
    }
}

/** 原生调用结果（对应 v2 的 `KeepAliveResult`）。 */
data class KeepAliveResult(
    /** 前台服务是否已启动。 */
    val running: Boolean,
    /** 通知是否可见。 */
    val notification: KeepAliveNotification,
    /** 降级原因（仅在通知不可见时返回）。 */
    val reason: String? = null
)

/**
 * 原生保活接缝：Android 侧由 `:app` 用前台服务实现；测试用假实现；
 * 非 Android 平台可以是纯 no-op。
 */
interface NativeKeepAlive {

    /** 启动/刷新前台服务与通知。 */
    suspend fun start(title: String, text: String, progress: Int): KeepAliveResult

    /** 更新通知正文与进度。 */
    suspend fun update(text: String, progress: Int): KeepAliveResult

    /** 停止前台服务并移除通知（**幂等**）。 */
    suspend fun stop(): KeepAliveResult
}

/** 节流策略 + 原生调用编排（v2 `KeepAlive.ts` 的 `startKeepAlive` / `updateKeepAliveProgress` / `stopKeepAlive`）。 */
class KeepAliveController(
    /** 原生实现；为 null 时所有方法退化为 no-op（对应 v2 在 Web 环境的 `NOOP_RESULT`）。 */
    private val native: NativeKeepAlive? = null
) {

    /** 是否处于保活中（v2 的 `active`）。 */
    var isActive: Boolean = false
        private set

    /** 当前任务名（用于通知正文）。 */
    var jobName: String = ""
        private set

    /** 上一次真正发出去的百分比（v2 的 `lastPercent`，-1 表示尚未发送）。 */
    var lastPercent: Int = NOT_SENT
        private set

    /** 最近一次原生结果（便于 UI 判断通知是否可见）。 */
    var lastResult: KeepAliveResult? = null
        private set

    /**
     * 开始保活：重置节流状态并启动前台服务。
     *
     * 与 v2 的差异：v2 在 Web 环境直接返回 `{running:false, notification:'unsupported'}`，
     * 这里 [native] 为 null 时返回同一语义的结果（**不**把 active 置真）。
     */
    suspend fun start(name: String, percent: Double = 0.0): KeepAliveResult {
        resetThrottle(name)
        isActive = false

        val impl = native ?: return NOOP_RESULT
        val safePercent = clampPercent(percent)
        return try {
            val result = impl.start(NOTIFICATION_TITLE, formatText(jobName, safePercent.toDouble()), safePercent)
            isActive = true
            lastPercent = safePercent
            lastResult = result
            result
        } catch (t: Throwable) {
            // v2：原生调用失败（极端定制系统禁用前台服务）不影响雕刻流程，但调用方仍能看到异常
            isActive = false
            lastPercent = NOT_SENT
            throw t
        }
    }

    /**
     * 更新进度：百分比未变化时**直接跳过**（不给原生发调用）。
     *
     * 注意 v2 的两个细节：`active` 为假时直接返回；阈值判断是
     * `percent === lastPercent` 与 `abs(percent - lastPercent) < PROGRESS_STEP` 两条。
     * 因为百分比已经被 [percentOf] 规整成 0~100 整数，第二条实际上只在 `PROGRESS_STEP > 1`
     * 时才生效 —— 这里照抄，不做"优化"。
     */
    suspend fun updateProgress(executed: Double, total: Double) {
        val impl = native ?: return
        if (!isActive) return
        val percent = percentOf(executed, total)
        if (percent == lastPercent) return
        if (kotlin.math.abs(percent - lastPercent) < PROGRESS_STEP) return
        lastPercent = percent
        try {
            lastResult = impl.update(formatText(jobName, percent.toDouble()), percent)
        } catch (_: Throwable) {
            // v2：通知刷新失败可忽略（保活本身不依赖通知）
        }
    }

    /**
     * 结束保活：停止前台服务并移除通知（**幂等**）。
     *
     * 无论是否处于激活状态都会调用一次原生 `stop()` —— 这样能清理上一次页面刷新 / 崩溃
     * 遗留的僵尸通知（v2 的原话如此）。
     */
    suspend fun stop() {
        val impl = native
        isActive = false
        resetThrottle("")
        if (impl == null) return
        try {
            lastResult = impl.stop()
        } catch (_: Throwable) {
            // 服务可能已被系统回收，忽略
        }
    }

    private fun resetThrottle(name: String) {
        jobName = name
        lastPercent = NOT_SENT
    }

    companion object {
        /** 尚未发送过任何百分比（v2 `lastPercent = -1`）。 */
        const val NOT_SENT = -1

        /** Web / 非原生环境的兜底结果（v2 `NOOP_RESULT`）。 */
        val NOOP_RESULT = KeepAliveResult(running = false, notification = KeepAliveNotification.Unsupported)
    }
}
