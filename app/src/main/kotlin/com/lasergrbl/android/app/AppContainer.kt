package com.lasergrbl.android.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lasergrbl.android.IgRblApplication
import com.lasergrbl.android.platform.ImageResampler
import com.lasergrbl.android.platform.TriangleResampler
import com.lasergrbl.core.grbl.GrblCore
import com.lasergrbl.core.raster.Interpolation
import com.lasergrbl.core.raster.ResizeSampler
import com.lasergrbl.core.serial.TransportKind
import com.lasergrbl.core.vector.PotraceImage
import com.lasergrbl.glasskit.LiquidToastController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 应用容器：把状态层、导航器、Toast、平台接缝装配到一起。
 *
 * 生命周期挂在 [IgRblApplication] 上（进程级单例），界面只通过
 * [LocalApp] 拿它 —— 这样 `GrblCore` 与两个串口传输层在整个进程里**只有一个实例**，
 * 与 Phase 3 审计的约束一致（多个传输层实例会各自注册 USB 拔出广播、争抢同一个句柄）。
 */
class AppContainer(
    val context: Context,
    val grblCore: GrblCore,
    val keepAlive: com.lasergrbl.core.native.KeepAliveController
) {

    /** 界面协程作用域：主线程，进程级（不用 `rememberCoroutineScope`，否则切屏会取消在途任务）。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 导航器。 */
    val navigator = AppNavigator()

    /** Toast 宿主（唯一渲染点是 `AppShell` 的 overlay 槽）。 */
    val toasts = LiquidToastController()

    /**
     * 当前待确认的对话框（走 overlay 槽）。
     *
     * 用容器级状态而不是每屏一个 `Dialog`：Phase 1 的架构结论要求浮层同窗口，
     * 而 [AppShell] 的 `overlay` 槽是唯一渲染点。
     */
    var confirm: GlassConfirmState? by mutableStateOf(null)

    /**
     * **屏幕级浮层注册口**。
     *
     * 屏幕拿不到 `LiquidScaffold` 的 `overlay` 槽（它是 `AppShell` 的参数），而 Phase 1 的结论
     * 禁止用 `Popup`/`Dialog`。所以需要底部弹层（下拉选择、机型列表）的屏幕把内容**注册**到这里，
     * 由 `AppShell` 在 overlay 槽里统一渲染 —— 这样仍然是同窗口 + 共用折射源。
     *
     * 用法（屏幕内）：
     * ```kotlin
     * container.screenOverlay = { backdrop -> LiquidBottomSheet(backdrop, onDismiss = { container.screenOverlay = null }, title = "选择") { ... } }
     * // 关闭：
     * container.screenOverlay = null
     * ```
     * 屏幕离开组合时会自动清空（`AppShell` 在 `when(screen)` 切换时重置），避免浮层跨屏残留。
     */
    var screenOverlay: (@Composable (com.kyant.backdrop.Backdrop) -> Unit)? by mutableStateOf(null)

    /** 状态层（订阅 `GrblCore` 事件）。 */
    val grbl = GrblController(grblCore, keepAlive, scope)

    /**
     * 重采样接缝适配器：把 `:core` 的 [Interpolation] 映射到 `:app` 的
     * [ImageResampler.Interpolation]。
     *
     * ⚠️ 两个枚举是**各自独立声明的**（`:core` 不能依赖 `:app`，见
     * `docs/PHASE3-PLATFORM-MODULES.md` §5.3）。这个适配器就是那条约束的落点：
     * 全项目**只有这里**做映射，其余地方一律用 `:core` 的类型。
     */
    val resampler: ResizeSampler = ResizeSampler { source: PotraceImage, sizeW, sizeH, interpolation, fillWhite ->
        TriangleResampler.resample(
            source = source,
            sizeW = sizeW,
            sizeH = sizeH,
            interpolation = when (interpolation) {
                Interpolation.High -> ImageResampler.Interpolation.High
                Interpolation.Low -> ImageResampler.Interpolation.Low
            },
            killAlpha = fillWhite
        )
    }

    init {
        grbl.bind()
    }
}

/**
 * 进程级容器持有者。
 *
 * 在 [IgRblApplication.onCreate] 里创建（那里已经注入了设置持久化），
 * Activity 通过 [of] 取用。
 */
object AppContainerHolder {

    @Volatile
    private var container: AppContainer? = null

    /** 创建（幂等）。 */
    fun create(app: IgRblApplication): AppContainer {
        container?.let { return it }
        synchronized(this) {
            container?.let { return it }
            // 复用 Application 上那个唯一的 GrblCore（它已经绑定了应用级传输层）。
            // 这里**不要**再 new 一个：多个 GrblCore 会争抢同一个串口句柄与事件回调。
            val created = AppContainer(app, app.grblCore, app.keepAlive)
            container = created
            return created
        }
    }

    /** 取用；未创建时抛异常（说明 `Application.onCreate` 没跑或顺序错了）。 */
    fun of(): AppContainer = container ?: error("AppContainer 尚未创建：应在 IgRblApplication.onCreate 里调用 create()")

    /** 当前是否已创建（测试/防御用）。 */
    val exists: Boolean get() = container != null
}
