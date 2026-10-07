package com.lasergrbl.core

/**
 * 简单类型安全事件发射器 —— 逐语义移植自 v2 `src/core/Emitter.ts`。
 *
 * 保持一致的三条语义：
 *  1. 监听器按注册顺序回调；
 *  2. `emit` 期间增删监听器**不影响本次派发**（v2 是 `for (const cb of [...arr])`，先拷贝）；
 *  3. 单个监听器抛异常不影响其它监听器（v2 里 `console.error('[emitter]', ...)`）。
 *
 * v2 用「事件名字符串 → 载荷类型」的映射表；Kotlin 里改成**每种载荷一个 Emitter 实例**，
 * 换来同样的类型安全且不需要字符串键。
 */
class Emitter<T> {

    private val listeners = mutableListOf<(T) -> Unit>()

    /** 异常上报口，默认打印堆栈；App 层可替换成自己的日志。 */
    var errorHandler: (Throwable) -> Unit = { it.printStackTrace() }

    /** 注册监听器，返回取消订阅的函数（与 v2 的返回闭包一致）。 */
    fun on(listener: (T) -> Unit): () -> Unit {
        listeners.add(listener)
        return { off(listener) }
    }

    fun off(listener: (T) -> Unit) {
        listeners.remove(listener)
    }

    fun emit(payload: T) {
        listeners.toList().forEach { listener ->
            try {
                listener(payload)
            } catch (e: Throwable) {
                errorHandler(e)
            }
        }
    }

    /** 当前监听器数量（测试与调试用）。 */
    val listenerCount: Int get() = listeners.size

    fun clear() {
        listeners.clear()
    }
}
