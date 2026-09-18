/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.ui.screens.content.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.ui.screens.content.elements.backgroundGlass
import com.movtery.zalithlauncher.ui.theme.cardColor
import com.movtery.zalithlauncher.ui.theme.onCardColor

/** 卡片与用户的交互状态 */
enum class HomeCardInteraction { Idle, Adjusting, Dragging, Resizing }

/**
 * 主页卡片的统一模型
 */
sealed interface HomeCard {
    val id: String
    /**
     * 用户卡片
     */
    data class User(
        override val id: String,
        val type: HomeCardType,
        val layout: CardLayout
    ) : HomeCard
    /**
     * 系统卡片 由启动器自行提供
     */
    class System(
        override val id: String,
        val content: @Composable () -> Unit
    ) : HomeCard
}

/**
 * 提供给卡片内容的自身状态，
 * 卡片依据尺寸形态与交互状态切换不同的显示形态。
 */
class HomeCardState(
    val spanWidth: Int,
    val spanHeight: Int,
    val columns: Int,
    val interaction: HomeCardInteraction
) {
    /** 依据跨度推导的形态分类 */
    val sizeClass: HomeCardSizeClass = deriveSizeClass(spanWidth, spanHeight, columns)
    /** 卡片是否占据整行宽度 */
    val isFullWidth: Boolean = spanWidth >= columns
}

typealias HomeCardContent = @Composable HomeCardState.() -> Unit

/**
 * 用户卡片的类型声明，未声明时使用主题默认形状。
 * @param defaultSpan 默认跨度
 * @param limits 尺寸边界限制
 * @param shape 形状
 * @param content 该卡片的 UI 内容
 */
class HomeCardType(
    val typeId: String,
    val defaultSpan: IntOffset,
    val limits: CardLimits = CardLimits.DEFAULT,
    val shape: Shape? = null,
    val content: HomeCardContent
)

/**
 * 卡片矩形在单元格内向四边的内缩量
 */
val HomeCardSpacing = 6.dp

/**
 * 主页网格卡片的表面容器
 */
@Composable
fun HomeCardSurface(
    interaction: HomeCardInteraction,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    containerColor: Color = cardColor(),
    contentColor: Color = onCardColor(),
    handlesColor: Color = MaterialTheme.colorScheme.primary,
    elevation: Dp = 0.dp,
    activatedElevation: Dp = 6.dp,
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val activated = interaction != HomeCardInteraction.Idle
    val internalElevation by animateDpAsState(
        targetValue = if (activated) activatedElevation else elevation,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "homeCardElevation"
    )
    val handles = if (selected) {
        Modifier.homeSelectionHandles(color = handlesColor)
    } else {
        Modifier
    }

    Card(
        modifier = modifier.then(handles),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = internalElevation)
    ) {
        Column(
            modifier = Modifier.backgroundGlass(
                blur = AllSettings.backgroundBlur.state,
                color = containerColor,
                enabled = true
            ),
            content = content
        )
    }
}
