package com.lasergrbl.glasskit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.glasskit.theme.LiquidTheme
import com.lasergrbl.glasskit.theme.LiquidType

/**
 * 玻璃输入框（对应 v2 的 `GlassInput`）。
 *
 * 完整保留 v2 的三条交互契约（这是数值输入不出错的关键）：
 *  1. **输入中不被外部值覆盖**：正在聚焦时，外部传入的 `value` 不会写回草稿；
 *  2. **失焦提交**：失焦时调用 [onCommit]，v2 的 `@blur="onNum(...)"` 就是这个时机；
 *  3. **非法值回弹**：若父层在 [onCommit] 中没有接受草稿（`value` 没变），失焦后草稿会回退到 `value`。
 *
 * [backdrop] 传 null 时退化为纯色底（用于对话框/弹层内部，那些地方没有折射源）。
 */
@Composable
fun LiquidTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    suffix: String? = null,
    onCommit: ((String) -> Unit)? = null
) {
    val colors = LiquidTheme.colors
    val dimens = LiquidTheme.dimens
    val focusManager = LocalFocusManager.current

    var draft by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(value, focused) {
        if (!focused && draft != value) draft = value
    }

    val field: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (draft.isEmpty() && placeholder != null) {
                    BasicText(
                        text = placeholder,
                        style = LiquidType.body.copy(color = colors.textFaint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        onValueChange(it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { state ->
                            val wasFocused = focused
                            focused = state.isFocused
                            if (wasFocused && !state.isFocused) onCommit?.invoke(draft)
                        },
                    enabled = enabled,
                    textStyle = LiquidType.body.copy(
                        color = if (enabled) colors.text else colors.textFaint
                    ),
                    singleLine = singleLine,
                    minLines = minLines,
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = imeAction
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                )
            }

            if (suffix != null) {
                BasicText(suffix, style = LiquidType.label.copy(color = colors.textDim))
            }
        }
    }

    if (backdrop != null) {
        LiquidPlainSurface(
            backdrop = backdrop,
            modifier = modifier,
            shape = RoundedCornerShape(dimens.fieldRadius),
            contentPadding = PaddingValues(0.dp)
        ) {
            field()
        }
    } else {
        Box(
            modifier
                .clip(RoundedCornerShape(dimens.fieldRadius))
                .background(colors.fill1)
        ) {
            field()
        }
    }
}
