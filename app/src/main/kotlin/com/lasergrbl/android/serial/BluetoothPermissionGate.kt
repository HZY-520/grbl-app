package com.lasergrbl.android.serial

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** `BLUETOOTH_CONNECT` 成为运行时权限的起始 API（Android 12 / S）。 */
internal const val BLUETOOTH_CONNECT_MIN_SDK: Int = 31

/** 未拿到蓝牙权限时抛出的文案 —— 与 v2 被拒绝时的 reject 文案一致（`BluetoothSerialPlugin.java:225`）。 */
internal const val MESSAGE_BLUETOOTH_PERMISSION_DENIED = "未获得蓝牙权限"

/**
 * 版本门禁的判定结果（纯数据，便于 JVM 单测逐条断言）。
 */
internal enum class BluetoothConnectPermission {
    /** API < 31：`BLUETOOTH_CONNECT` 还不是运行时权限，直接放行。 */
    NotRequired,

    /** 已经持有权限。 */
    Granted,

    /** API >= 31 且未授权：需要弹系统权限框。 */
    MustRequest
}

/**
 * 版本门禁的**纯函数** —— 逐字对应 v2 `withPermission` 的第一个判断
 * （`BluetoothSerialPlugin.java:202-204`：`SDK_INT < S || state == GRANTED` 就直接放行）。
 *
 * 之所以抽成纯函数（而不是直接读 `Build.VERSION.SDK_INT`）：这条规则是本次交付里**可离线验证**的
 * 部分之一，单测可以把 API 24/30/31/34 都跑一遍，不需要真机。
 *
 * @param sdkInt  `Build.VERSION.SDK_INT`
 * @param granted `ContextCompat.checkSelfPermission(BLUETOOTH_CONNECT) == PERMISSION_GRANTED`
 */
internal fun decideBluetoothConnectPermission(sdkInt: Int, granted: Boolean): BluetoothConnectPermission = when {
    sdkInt < BLUETOOTH_CONNECT_MIN_SDK -> BluetoothConnectPermission.NotRequired
    granted -> BluetoothConnectPermission.Granted
    else -> BluetoothConnectPermission.MustRequest
}

/**
 * `BLUETOOTH_CONNECT` 运行时权限门禁（Android 12 / API 31 起）：检查 → 必要时弹系统权限框 → 复检。
 *
 * 对应 v2 `BluetoothSerialPlugin` 的 `withPermission` / `permissionCallback`
 * （`BluetoothSerialPlugin.java:195-227`）：那里借 Capacitor 的 `requestPermissionForAlias` 弹框，
 * 这里用 `ActivityResultContracts.RequestPermission` + `ActivityResultRegistry` 的等价物。
 *
 * ### 取舍：Activity 从哪里来（**任务书要求写进 KDoc**）
 * v2 的插件天然活在 Activity 上（Capacitor 的 `getActivity()`），弹框不需要额外找 Activity。
 * 3.0 的传输层是长生命周期对象，构造时只有 `Context`，所以按下面的优先级拿 Activity：
 *
 *  1. **自动跟踪（默认路径）**：构造时若 context 的 `applicationContext` 是 [Application]，
 *     注册 `ActivityLifecycleCallbacks`，在 `onActivityResumed` 记下弱引用、`onActivityPaused` /
 *     `onActivityDestroyed` 清除。**这样 `MainActivity` / `IgRblApplication` 不需要任何改动。**
 *  2. **构造时传入的 context 本身是 Activity**（或其 `ContextWrapper` 包装）：
 *     直接解包登记，覆盖「页面创建 transport 时顺手把 Activity 传进来」的用法。
 *  3. **显式 [attach]**：Phase 4 的 Compose 侧可以在 `DisposableEffect` 里登记当前 Activity。
 *  4. **都拿不到 → 降级**：只检查已有授权；未授权时**抛** [SecurityException]（文案
 *     [MESSAGE_BLUETOOTH_PERMISSION_DENIED]），而不是静默失败 —— 与 v2 权限被拒时的
 *     reject 文案一致。降级路径下不会弹框（没有 Activity 无法承载 `startActivityForResult`）。
 *
 * ⚠️ 已知时序限制：若本对象在 Activity **已经 resume 之后**才第一次构造，且构造时传入的不是
 * Activity 上下文，则要等下一次 `onActivityResumed` 才认得当前 Activity。因此 Phase 4 的连接页
 * 建议在页面创建时构造 transport（或用 [attach] 显式登记）。
 *
 * ### 线程
 * [ensureGranted] 是 `suspend`，可在任意线程调用：内部用主线程 [Handler] 落到主线程再
 * `register` + `launch`（`ActivityResultRegistry.register(lifecycleOwner, ...)` 会调用
 * `LifecycleRegistry.addObserver`，**必须在主线程**）。这里刻意不用 `Dispatchers.Main`，
 * 以免依赖 `kotlinx-coroutines-android` 是否在运行期类路径上。
 *
 * 本类不持有 Activity 强引用（只用 [WeakReference]），也不持有长生命周期的注册回调。
 */
class BluetoothPermissionGate(context: Context) {

    /** 全部系统调用都用 application context：门禁对象可能比 Activity 活得久。 */
    private val appContext: Context = context.applicationContext

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 每次请求用不同的 registry key，避免重复注册同一个 key（注册过的 key 直到 Activity 销毁才释放）。 */
    private val requestKeys = AtomicInteger(0)

    @Volatile
    private var currentActivity: WeakReference<Activity>? = null

    /** 构造时传入的 context 恰好是 Activity（或包装了 Activity）时的兜底登记。 */
    private val initialActivity: Activity? = unwrapActivity(context)

    /**
     * 自动跟踪当前 Activity。**7 个方法必须全部实现**：API 29 之前
     * `ActivityLifecycleCallbacks` 没有默认实现，少实现一个会在老设备上
     * 抛 `AbstractMethodError`（本工程 minSdk 24）。
     */
    private val activityTracker = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) {
            currentActivity = WeakReference(activity)
        }

        override fun onActivityPaused(activity: Activity) = clearIfCurrent(activity)

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = clearIfCurrent(activity)
    }

    init {
        (appContext as? Application)?.registerActivityLifecycleCallbacks(activityTracker)
        initialActivity?.let { currentActivity = WeakReference(it) }
    }

    /** 当前可用于弹权限框的 Activity（可能为 null；已 finishing / destroyed 的不返回）。 */
    fun currentActivity(): Activity? {
        val activity = currentActivity?.get() ?: initialActivity
        if (activity == null) return null
        if (activity.isFinishing) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed) return null
        return activity
    }

    /** 显式登记当前 Activity（可选接线；与 [detach] 配对使用）。 */
    fun attach(activity: Activity) {
        currentActivity = WeakReference(activity)
    }

    /** 取消登记；只有当前登记的正是 [activity] 时才清除，避免误清更晚登记的那个。 */
    fun detach(activity: Activity) = clearIfCurrent(activity)

    /** 是否**已经**持有 `BLUETOOTH_CONNECT`（与 API 版本无关；API < 31 上该权限不存在，恒为 false）。 */
    fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 版本门禁判定（`Build.VERSION.SDK_INT` + 已有授权）。
     *
     * `internal`：判定结果是需要申请/已授权/不需要三类，UI 只需要 [canUseBluetooth] 的布尔值，
     * 拿不到布尔时也应当走 [ensureGranted]（它会把该弹的框弹出来）。
     */
    internal fun decision(): BluetoothConnectPermission =
        decideBluetoothConnectPermission(Build.VERSION.SDK_INT, isGranted())

    /** 是否可以直接使用蓝牙 API（无需再弹框）。 */
    fun canUseBluetooth(): Boolean = decision() != BluetoothConnectPermission.MustRequest

    /**
     * 确保蓝牙可用：API < 31 或已授权时立即返回；否则弹系统权限框并等待结果。
     *
     * @throws SecurityException 拿不到 Activity（无法弹框）或用户拒绝时，文案为
     *   [MESSAGE_BLUETOOTH_PERMISSION_DENIED]（与 v2 一致）。
     */
    suspend fun ensureGranted() {
        when (decision()) {
            BluetoothConnectPermission.NotRequired, BluetoothConnectPermission.Granted -> return
            BluetoothConnectPermission.MustRequest -> Unit
        }

        val activity = currentActivity()
        if (activity == null) {
            // 降级路径：没有 Activity 就没法弹框，只能检查已有授权（见类 KDoc 第 4 条）
            if (!isGranted()) throw SecurityException(MESSAGE_BLUETOOTH_PERMISSION_DENIED)
            return
        }
        if (requestViaActivity(activity)) return
        throw SecurityException(MESSAGE_BLUETOOTH_PERMISSION_DENIED)
    }

    /** 释放跟踪回调（进程退出/测试用；幂等）。 */
    fun release() {
        (appContext as? Application)?.unregisterActivityLifecycleCallbacks(activityTracker)
        currentActivity = null
    }

    // ================= 内部 =================

    /**
     * 用 `ActivityResultContracts.RequestPermission` 弹框并挂起等待结果。
     *
     * 注册走 `activityResultRegistry.register(key, lifecycleOwner, contract, callback)`：这个重载
     * **允许在 Activity 已 STARTED 之后调用**（`registerForActivityResult` 则不行，它必须在
     * `onCreate` 之前完成）。注册与 launch 都在主线程。
     */
    private suspend fun requestViaActivity(activity: Activity): Boolean {
        val component = activity as? ComponentActivity ?: return isGranted()
        if (isGranted()) return true

        return suspendCancellableCoroutine { continuation ->
            mainHandler.post {
                if (!continuation.isActive) return@post
                var launcher: ActivityResultLauncher<String>? = null
                try {
                    val key = "$ACTIVITY_RESULT_KEY_PREFIX${requestKeys.incrementAndGet()}"
                    launcher = component.activityResultRegistry.register(
                        key,
                        component,
                        ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        // 结果回调一定在主线程
                        runCatching { launcher?.unregister() }
                        if (continuation.isActive) continuation.resume(granted || isGranted())
                    }
                    launcher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } catch (t: Throwable) {
                    // 注册被拒（例如 lifecycle 已 DESTROYED）或 launch 失败：退回"检查已有授权"
                    runCatching { launcher?.unregister() }
                    if (continuation.isActive) continuation.resume(isGranted())
                }
            }
        }
    }

    private fun clearIfCurrent(activity: Activity) {
        if (currentActivity?.get() === activity) currentActivity = null
    }

    private companion object {
        /** `ActivityResultRegistry` 的 key 前缀（每次请求追加自增序号，保证不重复）。 */
        const val ACTIVITY_RESULT_KEY_PREFIX = "igrbl.bluetooth.connect."

        /** 顺着 `ContextWrapper.baseContext` 找 Activity（找不到返回 null）。 */
        fun unwrapActivity(context: Context?): Activity? {
            var current = context
            while (current is ContextWrapper) {
                if (current is Activity) return current
                current = current.baseContext
            }
            return null
        }
    }
}
