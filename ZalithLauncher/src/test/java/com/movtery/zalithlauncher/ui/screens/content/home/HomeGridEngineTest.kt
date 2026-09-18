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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HomeGridEngineTest {

    private fun card(
        id: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ) = CardLayout(id = id, x = x, y = y, width = width, height = height)

    // ---------- 网格几何 ----------

    @Test
    fun testGridColumnsAlwaysEven() {
        // 500dp / 20dp = 25 → 取最近的偶数 26
        assertEquals(26, computeGridGeometry(500f).columns)
        // 300dp / 20dp = 15 → 取最近的偶数 16
        assertEquals(16, computeGridGeometry(300f).columns)
        // 199dp / 20dp ≈ 10 → 保持偶数
        assertEquals(10, computeGridGeometry(199f).columns)
    }

    @Test
    fun testGridEdgesAlignToContainer() {
        val geometry = computeGridGeometry(500f)
        assertEquals(500f, geometry.columns * geometry.cellSize, 0.01f)
        // 单元格边长保持在 20dp 附近
        assertTrue(abs(geometry.cellSize - 20f) < 2f)
    }

    @Test
    fun testGridMinimumColumns() {
        assertEquals(MIN_GRID_COLUMNS, computeGridGeometry(30f).columns)
        assertEquals(MIN_GRID_COLUMNS, computeGridGeometry(0f).columns)
        assertEquals(MIN_GRID_COLUMNS, computeGridGeometry(-10f).columns)
    }

    // ---------- 形态分类 ----------

    @Test
    fun testDeriveSizeClass() {
        val columns = 16
        assertEquals(HomeCardSizeClass.FULL_WIDTH, deriveSizeClass(16, 4, columns))
        assertEquals(HomeCardSizeClass.LARGE, deriveSizeClass(12, 12, columns))
        assertEquals(HomeCardSizeClass.WIDE, deriveSizeClass(8, 4, columns))
        assertEquals(HomeCardSizeClass.TALL, deriveSizeClass(4, 8, columns))
        assertEquals(HomeCardSizeClass.COMPACT, deriveSizeClass(4, 4, columns))
        assertEquals(HomeCardSizeClass.SQUARE, deriveSizeClass(10, 10, columns))
    }

    // ---------- 最近空位搜索 ----------

    @Test
    fun testFindNearestSlotPrefersSidewaysWhenBlockedBelow() {
        // 原位与正下方均被占用，左侧 (0,0) 成为最近空位
        val obstacles = listOf(
            card("block1", 8, 0, 8, 4),
            card("block2", 8, 4, 8, 4)
        )
        val slot = HomeGridEngine.findNearestFreeSlot(8, 4, IntOffset(8, 0), 16, obstacles)
        assertEquals(IntOffset(0, 0), slot)
    }

    @Test
    fun testFindNearestSlotFallsBelowFullRow() {
        // 整行被占用，只能落到下一行
        val obstacles = listOf(card("block", 0, 0, 16, 4))
        val slot = HomeGridEngine.findNearestFreeSlot(16, 4, IntOffset(0, 0), 16, obstacles)
        assertEquals(IntOffset(0, 4), slot)
    }

    @Test
    fun testFindNearestSlotGuaranteedBelowAllObstacles() {
        // 纵向堆满的障碍，解落在所有障碍物下方
        val obstacles = listOf(
            card("a", 0, 0, 4, 4),
            card("b", 0, 4, 4, 4)
        )
        val slot = HomeGridEngine.findNearestFreeSlot(4, 4, IntOffset(0, 4), 4, obstacles)
        assertEquals(IntOffset(0, 8), slot)
    }

    @Test
    fun testFindNearestSlotImpossibleWidth() {
        assertNull(
            HomeGridEngine.findNearestFreeSlot(20, 4, IntOffset(0, 0), 16, emptyList())
        )
    }

    // ---------- 挤压结算 ----------

    @Test
    fun testDisplacedCardSlidesSidewaysWithoutCascade() {
        val columns = 16
        val moving = card("A", 0, 0, 8, 4)
        val others = listOf(
            card("B", 4, 0, 8, 4),   // 与 A 重叠，将被挤压
            card("C", 0, 4, 8, 4)    // 未被重叠，不允许被级联影响
        )
        val result = HomeGridEngine.resolveDisplacements(moving, columns, others)
        // B 滑到 A 右侧，尺寸不变
        assertEquals(card("B", 8, 0, 8, 4), result["B"])
        // C 完全不受影响
        assertFalse(result.containsKey("C"))
    }

    @Test
    fun testDisplacedCardFallsToNextRowWhenRowFull() {
        val columns = 16
        val moving = card("A", 0, 0, 16, 4)
        val others = listOf(card("B", 0, 0, 8, 4))
        val result = HomeGridEngine.resolveDisplacements(moving, columns, others)
        // 行内无处可去，B 落到下一行
        assertEquals(card("B", 0, 4, 8, 4), result["B"])
    }

    @Test
    fun testResizeOverlapsTwoCardsDisplacesBoth() {
        val columns = 16
        // B 横向扩容，同时压到左右两张卡
        val moving = card("B", 4, 0, 8, 4)
        val others = listOf(
            card("L", 0, 0, 6, 4),
            card("R", 10, 0, 6, 4)
        )
        val result = HomeGridEngine.resolveDisplacements(moving, columns, others)
        assertEquals(2, result.size)
        val settled = others.map { result[it.id] ?: it } + moving
        assertFalse(HomeGridEngine.hasOverlap(settled))
    }

    // ---------- 垂直压实 ----------

    @Test
    fun testCompactFillsVerticalGap() {
        val cards = listOf(
            card("A", 0, 0, 8, 4),
            card("B", 0, 8, 8, 4)
        )
        val compacted = HomeGridEngine.compact(cards)
        // B 上浮填补 A 与 B 之间的空隙
        assertEquals(4, compacted.first { it.id == "B" }.y)
    }

    @Test
    fun testCompactBlockedByOtherCard() {
        val cards = listOf(
            card("A", 0, 0, 8, 4),
            card("B", 0, 8, 8, 4),
            card("C", 0, 4, 8, 4)
        )
        val compacted = HomeGridEngine.compact(cards)
        val a = compacted.first { it.id == "A" }
        val b = compacted.first { it.id == "B" }
        val c = compacted.first { it.id == "C" }
        assertEquals(0, a.y)
        assertEquals(4, c.y)
        // C 挡在中间，B 只能压在 C 下方
        assertEquals(8, b.y)
    }

    @Test
    fun testCompactPreservesHorizontalPosition() {
        val cards = listOf(card("D", 8, 8, 8, 4))
        val compacted = HomeGridEngine.compact(cards)
        assertEquals(card("D", 8, 0, 8, 4), compacted.first())
    }

    @Test
    fun testCompactAlreadyCompactedIsStable() {
        val cards = listOf(
            card("A", 0, 0, 8, 4),
            card("B", 8, 0, 8, 4),
            card("C", 0, 4, 8, 4)
        )
        assertEquals(cards.sortedWith(compareBy({ it.y }, { it.x })), HomeGridEngine.compact(cards))
    }

    // ---------- 碰壁缩放 ----------

    @Test
    fun testResizeEndBlockedByAdjacentCard() {
        // B 紧贴 A 右侧，A 的右缘完全无法扩张
        val a = card("A", 0, 0, 4, 4)
        val obstacles = listOf(card("B", 4, 0, 4, 4))
        val result = HomeGridEngine.resizeWithWalls(a, HomeResizeEdge.End, IntOffset(16, 0), 16, CardLimits.DEFAULT, obstacles)
        assertEquals(card("A", 0, 0, 4, 4), result)
        assertEquals(4..4, HomeGridEngine.resizeWall(a, HomeResizeEdge.End, 16, CardLimits.DEFAULT, obstacles))
    }

    @Test
    fun testResizeEndGrowsUpToObstacle() {
        // 距离最近的右侧卡片留出 8 格空隙，扩张止步于 B 左缘
        val a = card("A", 0, 0, 4, 4)
        val obstacles = listOf(card("B", 8, 0, 4, 4))
        val result = HomeGridEngine.resizeWithWalls(a, HomeResizeEdge.End, IntOffset(16, 0), 16, CardLimits.DEFAULT, obstacles)
        assertEquals(card("A", 0, 0, 8, 4), result)
    }

    @Test
    fun testResizeEndBlockedByGridEdge() {
        // 无阻挡卡片时，扩张止步于网格右缘
        val a = card("A", 0, 0, 4, 4)
        val result = HomeGridEngine.resizeWithWalls(a, HomeResizeEdge.End, IntOffset(16, 0), 6, CardLimits.DEFAULT, emptyList())
        assertEquals(card("A", 0, 0, 6, 4), result)
    }

    @Test
    fun testResizeStartBlockedByCardAndGridEdge() {
        // 左侧被 B 挡住：左缘无法越过 B 的右缘
        val a = card("A", 4, 0, 4, 4)
        val obstacles = listOf(card("B", 0, 0, 4, 4))
        val blocked = HomeGridEngine.resizeWithWalls(a, HomeResizeEdge.Start, IntOffset(0, 0), 16, CardLimits.DEFAULT, obstacles)
        assertEquals(card("A", 4, 0, 4, 4), blocked)
        // 无阻挡时止步于网格左缘
        val free = HomeGridEngine.resizeWithWalls(a, HomeResizeEdge.Start, IntOffset(0, 0), 16, CardLimits.DEFAULT, emptyList())
        assertEquals(card("A", 0, 0, 8, 4), free)
    }

    @Test
    fun testResizeBottomUnboundedBelow() {
        // 下方无阻挡卡片，网格纵向不设限，仅受尺寸边界约束
        val a = card("A", 0, 0, 4, 4)
        val result = HomeGridEngine.resizeWithWalls(a, HomeResizeEdge.Bottom, IntOffset(0, 100), 16, CardLimits.DEFAULT, emptyList())
        assertEquals(card("A", 0, 0, 4, 12), result)
    }

    @Test
    fun testResizeBottomBlockedByCardBelow() {
        val a = card("A", 0, 0, 4, 4)
        val obstacles = listOf(card("B", 0, 8, 4, 4))
        val result = HomeGridEngine.resizeWithWalls(a, HomeResizeEdge.Bottom, IntOffset(0, 100), 16, CardLimits.DEFAULT, obstacles)
        assertEquals(card("A", 0, 0, 4, 8), result)
    }

    @Test
    fun testResizeTopBlockedByGridEdgeAndCard() {
        val a = card("A", 0, 4, 4, 4)
        // 上缘止步于网格顶部
        val toGrid = HomeGridEngine.resizeWithWalls(a, HomeResizeEdge.Top, IntOffset(0, 0), 16, CardLimits.DEFAULT, emptyList())
        assertEquals(card("A", 0, 0, 4, 8), toGrid)
        // 上缘止步于上方卡片 B 的下缘
        val obstacles = listOf(card("B", 0, 0, 4, 4))
        val toCard = HomeGridEngine.resizeWithWalls(a, HomeResizeEdge.Top, IntOffset(0, 0), 16, CardLimits.DEFAULT, obstacles)
        assertEquals(card("A", 0, 4, 4, 4), toCard)
    }

    @Test
    fun testResizeShrinkNeverBlocked() {
        // 收缩方向不受阻挡影响，仅受最小跨度约束
        val a = card("A", 0, 0, 8, 4)
        val obstacles = listOf(card("B", 8, 0, 4, 4))
        val result = HomeGridEngine.resizeWithWalls(a, HomeResizeEdge.End, IntOffset(0, 0), 16, CardLimits.DEFAULT, obstacles)
        assertEquals(card("A", 0, 0, 4, 4), result)
    }

    @Test
    fun testResizeIgnoresCardsOutsideSpan() {
        // 行范围不重叠的卡片不构成阻挡
        val a = card("A", 0, 0, 4, 4)
        val obstacles = listOf(card("B", 6, 4, 4, 4))   // B 位于 A 下方
        val result = HomeGridEngine.resizeWithWalls(a, HomeResizeEdge.End, IntOffset(16, 0), 16, CardLimits.DEFAULT, obstacles)
        assertEquals(card("A", 0, 0, 16, 4), result)
        // 部分行重叠的卡片构成阻挡
        val tall = card("A", 0, 0, 4, 8)
        val partial = listOf(card("C", 6, 2, 4, 2))     // 与 A 的行范围 2..4 重叠
        val partialResult = HomeGridEngine.resizeWithWalls(tall, HomeResizeEdge.End, IntOffset(16, 0), 16, CardLimits.DEFAULT, partial)
        assertEquals(card("A", 0, 0, 6, 8), partialResult)
    }

    // ---------- 重排 ----------

    @Test
    fun testReflowScalesSpansProportionally() {
        val cards = listOf(card("A", 0, 0, 4, 4))
        val result = HomeGridEngine.reflow(cards, oldColumns = 8, columns = 16)
        assertEquals(card("A", 0, 0, 8, 8), result.first())
    }

    @Test
    fun testReflowKeepsReadingOrder() {
        val cards = listOf(
            card("A", 0, 0, 8, 4),
            card("B", 0, 4, 8, 4)
        )
        val result = HomeGridEngine.reflow(cards, oldColumns = 8, columns = 16)
        val a = result.first { it.id == "A" }
        val b = result.first { it.id == "B" }
        assertTrue(a.y <= b.y)
        assertFalse(HomeGridEngine.hasOverlap(result))
    }

    @Test
    fun testReflowClampsToLimits() {
        val cards = listOf(card("A", 0, 0, 16, 16))
        val limits: (CardLayout) -> CardLimits = { CardLimits(maxWidth = 8, maxHeight = 8) }
        val result = HomeGridEngine.reflow(cards, oldColumns = 16, columns = 16, limits = limits)
        assertEquals(card("A", 0, 0, 8, 8), result.first())
    }

    @Test
    fun testReflowShrinkingColumnsNeverOverflows() {
        val cards = listOf(
            card("A", 0, 0, 16, 4),
            card("B", 0, 4, 16, 4)
        )
        val result = HomeGridEngine.reflow(cards, oldColumns = 16, columns = 8)
        assertTrue(result.all { HomeGridEngine.isInGrid(it, 8) })
        assertFalse(HomeGridEngine.hasOverlap(result))
    }

    // ---------- 加载校验 ----------

    @Test
    fun testValidateClampsOutOfBounds() {
        val result = HomeGridEngine.validate(
            listOf(card("A", 14, -2, 8, 4)),
            columns = 16
        )
        assertEquals(card("A", 8, 0, 8, 4), result.first())
    }

    @Test
    fun testValidateResolvesOverlap() {
        val result = HomeGridEngine.validate(
            listOf(
                card("A", 0, 0, 8, 4),
                card("B", 0, 0, 8, 4)
            ),
            columns = 16
        )
        assertFalse(HomeGridEngine.hasOverlap(result))
        assertEquals(2, result.size)
    }

    @Test
    fun testValidateDeduplicatesIds() {
        val result = HomeGridEngine.validate(
            listOf(
                card("A", 0, 0, 8, 4),
                card("A", 0, 0, 8, 4)
            ),
            columns = 16
        )
        assertEquals(1, result.size)
    }

    @Test
    fun testValidateClampsSizeToLimits() {
        val limits: (CardLayout) -> CardLimits = { CardLimits(minWidth = 4, minHeight = 4, maxWidth = 8, maxHeight = 8) }
        val result = HomeGridEngine.validate(
            listOf(card("A", 0, 0, 16, 1)),
            columns = 16,
            limits = limits
        )
        assertEquals(card("A", 0, 0, 8, 4), result.first())
    }

    @Test
    fun testValidateEndsCompacted() {
        val result = HomeGridEngine.validate(
            listOf(card("A", 4, 8, 8, 4)),
            columns = 16
        )
        assertEquals(0, result.first().y)
    }

    // ---------- 汇总 ----------

    @Test
    fun testTotalRowsAndOverlap() {
        val cards = listOf(
            card("A", 0, 0, 8, 4),
            card("B", 0, 4, 8, 4)
        )
        assertEquals(8, HomeGridEngine.totalRows(cards))
        assertFalse(HomeGridEngine.hasOverlap(cards))
        assertTrue(HomeGridEngine.hasOverlap(cards + card("C", 4, 2, 8, 4)))
    }
}
