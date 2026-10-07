package com.lasergrbl.glasskit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.glasskit.theme.LiquidTheme
import com.lasergrbl.glasskit.theme.LiquidType
import kotlinx.coroutines.delay

/** 一条轻提示。 */
data class LiquidToast(
    val id: Long,
    val message: String,
    val type: LiquidAlertType = LiquidAlertType.Info,
    val durationMillis: Long = 2200
)

/**
 * 轻提示控制器 —— 对应 v2 的命令式 `showToast(msg, type, duration)`。
 *
 * 用法：屏幕顶层 `val toast = rememberLiquidToastController()`，
 * 在 [LiquidScaffold] 的 `overlay` 槽里放 `LiquidToastHost(toast, backdrop)`，
 * 业务代码里 `toast.show("已保存")` 即可 —— 与 v2 `toast()` 的调用手感一致。
 */
class LiquidToastController {

    private val _toasts: SnapshotStateList<LiquidToast> = mutableStateListOf()
    val toasts: List<LiquidToast> get() = _toasts

    private var nextId = 1L

    fun show(
        message: String,
        type: LiquidAlertType = LiquidAlertType.Info,
        durationMillis: Long = 2200
    ): Long {
        val id = nextId++
        _toasts.add(LiquidToast(id, message, type, durationMillis))
        return id
    }

    fun dismiss(id: Long) {
        _toasts.removeAll { it.id == id }
    }

    fun dismissAll() {
        _toasts.clear()
    }
}

@Composable
fun rememberLiquidToastController(): LiquidToastController = remember { LiquidToastController() }

/**
 * 轻提示宿主。放在 `overlay` 槽里渲染；每条提示自己计时消失（默认 2.2 s，与 v2 一致）。
 */
@Composable
fun LiquidToastHost(
    controller: LiquidToastController,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopCenter
) {
    Box(modifier.fillMaxSize(), contentAlignment = alignment) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            controller.toasts.forEach { toast ->
                key(toast.id) {
                    LiquidToastItem(toast, backdrop) { controller.dismiss(toast.id) }
                }
            }
        }
    }
}

@Composable
private fun LiquidToastItem(
    toast: LiquidToast,
    backdrop: Backdrop,
    onFinished: () -> Unit
) {
    val colors = LiquidTheme.colors
    val accent = when (toast.type) {
        LiquidAlertType.Info -> colors.info
        LiquidAlertType.Success -> colors.success
        LiquidAlertType.Warning -> colors.warning
        LiquidAlertType.Error -> colors.danger
    }
    val iconName = when (toast.type) {
        LiquidAlertType.Info -> "info"
        LiquidAlertType.Success -> "check"
        LiquidAlertType.Warning -> "info"
        LiquidAlertType.Error -> "close"
    }

    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }

    LaunchedEffect(toast.id) {
        delay(toast.durationMillis)
        onFinished()
    }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut()
    ) {
        LiquidSurface(
            backdrop = backdrop,
            modifier = Modifier.widthIn(max = 420.dp),
            shape = RoundedCornerShape(22.dp),
            surfaceColor = colors.modalSurface,
            blurRadius = 4.dp,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Image(
                    imageVector = LiquidIcons.get(iconName),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    colorFilter = ColorFilter.tint(accent)
                )
                BasicText(toast.message, style = LiquidType.label.copy(color = colors.text))
            }
        }
    }
}
