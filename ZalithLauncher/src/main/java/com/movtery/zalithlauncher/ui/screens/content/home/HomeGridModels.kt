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

import androidx.compose.ui.unit.IntOffset
import com.movtery.zalithlauncher.ui.screens.content.home.CardLimits.Companion.DEFAULT
import kotlin.math.roundToInt

/** 缩放手柄所在的边 */
enum class HomeResizeEdge { Start, Top, End, Bottom }

/** 网格的最小列数 */
const val MIN_GRID_COLUMNS = 4

/** 单元格边长的设计目标值（dp） */
const val DEFAULT_TARGET_CELL_SIZE = 20f

/**
 * 主页网格卡片的布局矩形。
 * 坐标与尺寸均以网格单元格为单位，锚点为卡片左上角，
 * y 轴向下为正，纵向（行数）不设上限。
 */
data class CardLayout(
    val id: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    fun positionAt(position: IntOffset): CardLayout = copy(x = position.x, y = position.y)

    fun intersects(other: CardLayout): Boolean =
        x < other.right && other.x < right && y < other.bottom && other.y < bottom
}

/**
 * 卡片的尺寸边界（单元格跨度），由卡片类型自行声明，
 * 未声明的卡片使用 [DEFAULT] 默认值。
 */
data class CardLimits(
    val minWidth: Int = DEFAULT_MIN_SPAN,
    val minHeight: Int = DEFAULT_MIN_SPAN,
    val maxWidth: Int = Int.MAX_VALUE,
    val maxHeight: Int = Int.MAX_VALUE
) {
    /**
     * 依据实际网格宽度归一化边界：
     * 最大跨度不超过网格，最小跨度不超过最大跨度。
     */
    fun clampedFor(columns: Int): CardLimits {
        val maxW = maxWidth.coerceAtMost(columns)
        val minW = minWidth.coerceAtMost(maxW)
        val maxH = maxHeight
        val minH = minHeight.coerceAtMost(maxH)
        return CardLimits(minWidth = minW, minHeight = minH, maxWidth = maxW, maxHeight = maxH)
    }

    fun clampWidth(width: Int): Int = width.coerceIn(minWidth, maxWidth)

    fun clampHeight(height: Int): Int = height.coerceIn(minHeight, maxHeight)

    companion object {
        /** 默认最小跨度：4×4（约 80dp） */
        const val DEFAULT_MIN_SPAN = 4

        /** 默认最大高度跨度：12 行（约 240dp） */
        const val DEFAULT_MAX_HEIGHT = 12

        val DEFAULT = CardLimits(maxHeight = DEFAULT_MAX_HEIGHT)
    }
}

/**
 * 网格几何信息。
 * [columns] 列数恒为偶数，保证卡片可严格对齐半宽等对称分割；
 * [cellSize] 为单个正方形单元格的边长（dp），
 * 网格首尾两端与容器边缘严格对齐。
 */
data class GridGeometry(
    val columns: Int,
    val cellSize: Float
) {
    /** 宽度方向是否与存储布局的列数一致，不一致时需要触发重排 */
    fun matchesStoredColumns(storedColumns: Int): Boolean = columns == storedColumns
}

/**
 * 依据容器宽度计算网格：
 * 以 [targetCellSize] 为目标细分出偶数列，宽度余数平摊进每个单元格，
 * 使单元格边长保持在目标值附近、网格边缘与容器边缘对齐。
 */
fun computeGridGeometry(
    widthDp: Float,
    targetCellSize: Float = DEFAULT_TARGET_CELL_SIZE
): GridGeometry {
    if (widthDp <= 0f) return GridGeometry(MIN_GRID_COLUMNS, targetCellSize)
    val evenColumns = (widthDp / targetCellSize / 2f)
        .roundToInt()
        .coerceAtLeast(MIN_GRID_COLUMNS / 2) * 2
    return GridGeometry(columns = evenColumns, cellSize = widthDp / evenColumns)
}

/**
 * 卡片依据自身跨度推导出的形态分类，供卡片内容按形态切换显示
 * 高度档位按跨度（格）的绝对数值划分
 */
enum class HomeCardSizeClass(val maxSpan: Int) {
    COMPACT(4),
    SMALL(5),
    MEDIUM(7),
    LARGE(9),
    EXTRA_LARGE(Int.MAX_VALUE);

    companion object {
        /**
         * 依据高度跨度（格）推导所处档位
         */
        fun fromSpan(span: Int): HomeCardSizeClass =
            entries.first { span <= it.maxSpan }

        /**
         * 依据宽度占网格宽度的比例推导所处档位
         */
        fun fromFraction(width: Int, columns: Int): HomeCardSizeClass {
            val classes = entries
            if (columns <= 0 || width <= 0) return COMPACT
            val fraction = width / columns.toFloat()
            val index = (fraction * classes.size).toInt().coerceIn(0, classes.lastIndex)
            return classes[index]
        }
    }
}

/** 卡片形态记录 */
data class HomeCardSize(
    val width: HomeCardSizeClass,
    val height: HomeCardSizeClass
)

/**
 * 由卡片跨度推导宽、高各自所处的形态档位
 */
fun deriveSizeClass(
    width: Int,
    height: Int,
    columns: Int
): HomeCardSize = HomeCardSize(
    width = HomeCardSizeClass.fromFraction(width, columns),
    height = HomeCardSizeClass.fromSpan(height)
)
