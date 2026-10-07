package com.lasergrbl.android.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.glasskit.theme.LiquidTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志列表 —— v2 `src/ui/components/LogList.vue`（86 行）的 Compose 版。
 *
 * 首页与串口终端共用。
 *
 * ### 两处与 v2 的差异（都是刻意的）
 * 1. **倒序渲染**：v2 是正序渲染 + `el.scrollTop = el.scrollHeight` 自动滚到底。
 *    倒序 `LazyColumn` + 滚到 index 0 的视觉效果相同，但**新增日志时不会重建整个列表**
 *    （正序列表每次都在底部追加，`LazyColumn` 也要重算）；日志上限 600 条时差异明显。
 * 2. **不做 `nextTick` 等待**：Compose 的 `LaunchedEffect(log.size)` 已经在组合提交后执行，
 *    等价于 v2 的 `await nextTick()`。
 *
 * @param maxLines 可见高度上限（v2 的 `.lg-log` 是固定 260px）
 */
@Composable
fun GlassLogList(
    log: List<LogItem>,
    modifier: Modifier = Modifier,
    maxLines: Int = 9,
    showTime: Boolean = false,
    backdrop: Backdrop? = null
) {
    val colors = LiquidTheme.colors
    val state = rememberLazyListState()
    val ordered = remember(log) { log.asReversed() }

    // 自动滚到最新一条（倒序后就是 index 0）
    LaunchedEffect(log.size) {
        if (ordered.isNotEmpty()) state.animateScrollToItem(0)
    }

    val content: @Composable () -> Unit = {
        if (ordered.isEmpty()) {
            GlassEmpty("暂无日志")
        } else {
            LazyColumn(
                state = state,
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(max = (maxLines * LINE_HEIGHT_DP).dp)
                    .clip(RoundedCornerShape(LiquidTheme.dimens.fieldRadius))
                    .background(if (colors.isLight) Color(0x0A000000) else Color(0x14000000))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(ordered, key = { it.id }) { item ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (showTime) {
                            GlassText(
                                text = TIME_FORMAT.format(Date(item.time)),
                                color = colors.textFaint,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        GlassText(
                            text = item.text,
                            color = logLineColor(item.kind, colors),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    if (backdrop != null) {
        GlassCard(backdrop, padding = PaddingValues(10.dp)) { content() }
    } else {
        Box { content() }
    }
}

/** 每行高度估算（用于 `heightIn` 上限）。 */
private const val LINE_HEIGHT_DP = 22

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
