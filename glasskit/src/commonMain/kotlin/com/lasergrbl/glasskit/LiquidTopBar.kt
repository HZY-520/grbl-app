package com.lasergrbl.glasskit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.lasergrbl.glasskit.theme.LiquidTheme
import com.lasergrbl.glasskit.theme.LiquidType

/**
 * 玻璃顶栏（对应 v2 的 `GlassNavBar`）。
 *
 * 参数取自 v2：圆角 22、`shift` 式的弱折射、较大的模糊（顶栏背后常有滚动内容，需要柔化）。
 * 库里的玻璃配方仍是他 `LiquidButton` 那一套，只是把 `lens` 调弱、`blur` 调强。
 */
@Composable
fun LiquidTopBar(
    title: String,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigation: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val colors = LiquidTheme.colors

    LiquidSurface(
        backdrop = backdrop,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        refractionHeight = 8.dp,
        refractionAmount = 16.dp,
        blurRadius = 8.dp,
        highlight = null,
        shadow = null,
        contentPadding = PaddingValues(0.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = LiquidTheme.dimens.topBarHeight)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            navigation?.invoke()

            Column(Modifier.weight(1f)) {
                BasicText(
                    text = title,
                    style = LiquidType.title.copy(color = colors.text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    BasicText(
                        text = subtitle,
                        style = LiquidType.caption.copy(color = colors.textDim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            actions()
        }
    }
}
