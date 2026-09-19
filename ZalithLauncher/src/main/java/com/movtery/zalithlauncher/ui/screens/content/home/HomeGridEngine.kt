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
import com.movtery.zalithlauncher.ui.screens.content.home.HomeGridEngine.resizeWall
import kotlin.math.roundToInt

object HomeGridEngine {
    /** 卡片是否完全位于网格边界内（纵向不设限） */
    fun isInGrid(layout: CardLayout, columns: Int): Boolean =
        layout.x >= 0 && layout.y >= 0 && layout.right <= columns

    /** 一组卡片之间是否存在重叠 */
    fun hasOverlap(cards: List<CardLayout>): Boolean {
        for (i in cards.indices) {
            for (j in i + 1 until cards.size) {
                if (cards[i].intersects(cards[j])) return true
            }
        }
        return false
    }

    /** 网格当前占用的总行数（无卡片时为 0） */
    fun totalRows(cards: List<CardLayout>): Int = cards.maxOfOrNull { it.bottom } ?: 0

    /**
     * 为尺寸 [width]×[height] 的卡片搜索距离 [origin] 最近的空闲位置。
     *
     * 以卡片中心间欧氏距离度量远近，距离相同者优先取更靠上、更靠左的位置；
     * [obstacles] 为需要避开的矩形集合，网格纵向无上限，
     * 搜索行数以障碍物最大底边为界（其下方整行必然空闲，解必定存在）。
     *
     * @return 最近的空闲位置，尺寸无法放入网格宽时返回 null
     */
    fun findNearestFreeSlot(
        width: Int,
        height: Int,
        origin: IntOffset,
        columns: Int,
        obstacles: List<CardLayout>
    ): IntOffset? {
        if (width <= 0 || height <= 0 || width > columns) return null
        val maxRow = obstacles.maxOfOrNull { it.bottom } ?: 0
        val originCenterX = origin.x + width / 2f
        val originCenterY = origin.y + height / 2f
        var best: IntOffset? = null
        var bestDistance = Float.MAX_VALUE
        for (cy in 0..maxRow) {
            for (cx in 0..columns - width) {
                val candidate = CardLayout("", cx, cy, width, height)
                if (obstacles.any { it.intersects(candidate) }) continue
                val dx = cx + width / 2f - originCenterX
                val dy = cy + height / 2f - originCenterY
                val distance = dx * dx + dy * dy
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = IntOffset(cx, cy)
                }
            }
        }
        return best
    }

    /**
     * 缩放时被拖动边可用的跨度区间：
     * 上界由尺寸边界、网格左、右、上边缘（下方不设限）与最近的阻挡卡片共同决定，
     * 下界为尺寸边界的最小跨度（收缩方向不受阻挡影响）。
     */
    fun resizeWall(
        current: CardLayout,
        edge: HomeResizeEdge,
        columns: Int,
        limits: CardLimits,
        obstacles: List<CardLayout>
    ): IntRange {
        val lim = limits.clampedFor(columns)
        val upper = when (edge) {
            HomeResizeEdge.End -> {
                val nearest = obstacles
                    .filter { it.x >= current.right && overlapsVertically(current, it) }
                    .minOfOrNull { it.x }
                minOf(lim.maxWidth, columns - current.x, (nearest ?: columns) - current.x)
            }
            HomeResizeEdge.Start -> {
                val nearest = obstacles
                    .filter { it.right <= current.x && overlapsVertically(current, it) }
                    .maxOfOrNull { it.right }
                minOf(lim.maxWidth, current.right, current.right - (nearest ?: 0))
            }
            HomeResizeEdge.Bottom -> {
                val nearest = obstacles
                    .filter { it.y >= current.bottom && overlapsHorizontally(current, it) }
                    .minOfOrNull { it.y }
                minOf(lim.maxHeight, (nearest ?: Int.MAX_VALUE) - current.y)
            }
            HomeResizeEdge.Top -> {
                val nearest = obstacles
                    .filter { it.bottom <= current.y && overlapsHorizontally(current, it) }
                    .maxOfOrNull { it.bottom }
                minOf(lim.maxHeight, current.bottom, current.bottom - (nearest ?: 0))
            }
        }.coerceAtLeast(1)
        val lower = (if (edge == HomeResizeEdge.Start || edge == HomeResizeEdge.End) {
            lim.minWidth
        } else {
            lim.minHeight
        }).coerceAtMost(upper)
        return lower..upper
    }

    /**
     * 碰壁式缩放：依据指针所在的单元格计算缩放布局，
     * 锚定被拖动边的对侧，跨度钳制在 [resizeWall] 的界内。
     */
    fun resizeWithWalls(
        current: CardLayout,
        edge: HomeResizeEdge,
        pointer: IntOffset,
        columns: Int,
        limits: CardLimits,
        obstacles: List<CardLayout>
    ): CardLayout {
        val range = resizeWall(current, edge, columns, limits, obstacles)
        return when (edge) {
            HomeResizeEdge.End -> current.copy(width = (pointer.x - current.x).coerceIn(range))
            HomeResizeEdge.Start -> {
                val width = (current.right - pointer.x).coerceIn(range)
                current.copy(x = current.right - width, width = width)
            }
            HomeResizeEdge.Bottom -> current.copy(height = (pointer.y - current.y).coerceIn(range))
            HomeResizeEdge.Top -> {
                val height = (current.bottom - pointer.y).coerceIn(range)
                current.copy(y = current.bottom - height, height = height)
            }
        }
    }

    /**
     * 依据指针所在的单元格计算缩放布局，锚定被拖动边的对侧，
     * 扩张方向上的网格边缘与 [limits] 为硬性界限。
     *
     * 被扩张重叠的卡片沿扩张方向被推至与前沿齐平，
     * 并联动推开自身推进方向上被压到的其他卡片；
     * 链条推不动时，跨度回退至可推动的最远位置。
     */
    fun resizeWithPush(
        current: CardLayout,
        edge: HomeResizeEdge,
        pointer: IntOffset,
        columns: Int,
        limits: CardLimits,
        obstacles: List<CardLayout>
    ): ResizePushResult {
        val desired = resizeWithWalls(current, edge, pointer, columns, limits, obstacles = emptyList())
        if (desired.spanOf(edge) <= current.spanOf(edge)) return ResizePushResult(desired, emptyMap())
        var candidate = desired
        while (true) {
            val pushed = pushForward(candidate, edge, columns, obstacles)
            if (pushed != null) return ResizePushResult(candidate, pushed)
            val span = candidate.spanOf(edge) - 1
            if (span < current.spanOf(edge)) return ResizePushResult(current, emptyMap())
            candidate = candidate.withSpan(edge, span)
        }
    }

    /** 推挤式缩放的结算结果：缩放布局与被推开的卡片（id -> 新布局） */
    data class ResizePushResult(
        val layout: CardLayout,
        val pushed: Map<String, CardLayout>
    )

    private fun CardLayout.spanOf(edge: HomeResizeEdge): Int = when (edge) {
        HomeResizeEdge.Start, HomeResizeEdge.End -> width
        HomeResizeEdge.Top, HomeResizeEdge.Bottom -> height
    }

    private fun CardLayout.withSpan(edge: HomeResizeEdge, span: Int): CardLayout = when (edge) {
        HomeResizeEdge.End -> copy(width = span)
        HomeResizeEdge.Start -> copy(x = right - span, width = span)
        HomeResizeEdge.Bottom -> copy(height = span)
        HomeResizeEdge.Top -> copy(y = bottom - span, height = span)
    }

    /**
     * 沿扩张方向推挤与 [candidate] 重叠的卡片：
     * 卡片依次被推至与前沿齐平，并联动推开自身推进方向上被压到的其他卡片；
     * 任何一张卡片触及网格边缘即整条链条推不动。
     *
     * @return 被推动的卡片（id -> 新布局），推不动时返回 null
     */
    private fun pushForward(
        candidate: CardLayout,
        edge: HomeResizeEdge,
        columns: Int,
        obstacles: List<CardLayout>
    ): Map<String, CardLayout>? {
        val horizontal = edge == HomeResizeEdge.Start || edge == HomeResizeEdge.End
        val forward = edge == HomeResizeEdge.End || edge == HomeResizeEdge.Bottom

        // 卡片朝向前沿的一侧
        fun leading(card: CardLayout): Int = when {
            horizontal && forward -> card.x
            horizontal -> card.right
            forward -> card.y
            else -> card.bottom
        }
        fun span(card: CardLayout): Int = if (horizontal) card.width else card.height
        fun inBand(card: CardLayout): Boolean =
            if (horizontal) overlapsVertically(candidate, card) else overlapsHorizontally(candidate, card)
        fun placedAt(card: CardLayout, front: Int): CardLayout = when {
            horizontal && forward -> card.copy(x = front)
            horizontal -> card.copy(x = front - card.width)
            forward -> card.copy(y = front)
            else -> card.copy(y = front - card.height)
        }

        // 前沿从候选布局的被拖动边出发，随推挤推进
        var front = when {
            horizontal && forward -> candidate.right
            horizontal -> candidate.x
            forward -> candidate.bottom
            else -> candidate.y
        }
        val pushed = mutableMapOf<String, CardLayout>()
        // 被推入的矩形可能压到扩张带之外的卡片，待推队列随之动态增长
        val queue = obstacles
            .filter { it.id != candidate.id && inBand(it) }
            .toMutableList()
        while (queue.isNotEmpty()) {
            queue.sortBy { if (forward) leading(it) else -leading(it) }
            val card = queue.removeAt(0)
            // 距前沿最近的卡片已被容纳到前沿之外，其后的卡片更远，推挤结束
            if (if (forward) leading(card) >= front else leading(card) <= front) break
            val placed = placedAt(card, front)
            front += if (forward) span(card) else -span(card)
            val blocked = when {
                horizontal && forward -> front > columns
                forward -> false
                else -> front < 0
            }
            if (blocked) return null
            pushed[card.id] = placed
            queue.addAll(
                obstacles.filter {
                    it.id != candidate.id && it.id !in pushed && it.intersects(placed) && it !in queue
                }
            )
        }
        return pushed
    }

    /**
     * 为尺寸 [width]×[height] 的卡片寻找最上、最左的空闲位置（贪心打包）。
     */
    fun findTopLeftFreeSlot(
        width: Int,
        height: Int,
        columns: Int,
        obstacles: List<CardLayout>
    ): IntOffset {
        val maxRow = obstacles.maxOfOrNull { it.bottom } ?: 0
        for (cy in 0..maxRow) {
            for (cx in 0..columns - width) {
                val candidate = CardLayout("", cx, cy, width, height)
                if (obstacles.none { it.intersects(candidate) }) {
                    return IntOffset(cx, cy)
                }
            }
        }
        return IntOffset(0, maxRow)
    }

    /**
     * 挤压结算：[moving] 为移动或缩放中的卡片预览（或落点），
     * 与其重叠的卡片按阅读顺序依次被重新安置到最近的空闲位置，
     * 尺寸保持不变，且不会级联推挤未被直接重叠的卡片。
     *
     * @return 被重新安置的卡片（id -> 新布局），不包含未受影响的卡片
     */
    fun resolveDisplacements(
        moving: CardLayout,
        columns: Int,
        cards: List<CardLayout>
    ): Map<String, CardLayout> {
        val displaced = cards
            .filter { it.id != moving.id && it.intersects(moving) }
            .sortedWith(readingOrder())
        val occupied = mutableListOf<CardLayout>()
        occupied.add(moving)
        occupied.addAll(cards.filter { it.id != moving.id && !it.intersects(moving) })
        val result = mutableMapOf<String, CardLayout>()
        for (card in displaced) {
            val slot = findNearestFreeSlot(
                width = card.width,
                height = card.height,
                origin = IntOffset(card.x, card.y),
                columns = columns,
                obstacles = occupied
            ) ?: continue
            val relocated = card.positionAt(slot)
            occupied.add(relocated)
            result[card.id] = relocated
        }
        return result
    }

    /**
     * 垂直压实：按阅读顺序处理，每张卡片在保持横向位置不变的前提下
     * 尽可能上浮，直到贴近网格顶部或压在已有卡片下方。
     * 压实后任何卡片都无法再向上移动；行内与行尾的横向空位保留。
     */
    fun compact(cards: List<CardLayout>): List<CardLayout> {
        val sorted = cards.sortedWith(readingOrder())
        val placed = mutableListOf<CardLayout>()
        for (card in sorted) {
            var y = 0
            while (true) {
                val blocking = placed.firstOrNull { it.intersects(card.positionAt(IntOffset(card.x, y))) }
                if (blocking == null) break
                y = blocking.bottom
            }
            placed.add(card.positionAt(IntOffset(card.x, y)))
        }
        return placed
    }

    /**
     * 按阅读顺序（先上后下、先左后右）贪心重排，
     * 用于网格宽度变化后的布局迁移：卡片宽高按新旧列数比例折算，
     * 以 [limits] 声明的边界钳制，再逐个放入最上最左的空位。
     */
    fun reflow(
        cards: List<CardLayout>,
        oldColumns: Int,
        columns: Int,
        limits: (CardLayout) -> CardLimits = { CardLimits.DEFAULT }
    ): List<CardLayout> {
        if (cards.isEmpty()) return cards
        val scale = columns.toFloat() / oldColumns.coerceAtLeast(1)
        val placed = mutableListOf<CardLayout>()
        for (card in cards.sortedWith(readingOrder())) {
            val lim = limits(card).clampedFor(columns)
            val width = (card.width * scale).roundToInt().let { lim.clampWidth(it) }
            val height = (card.height * scale).roundToInt().let { lim.clampHeight(it) }
            val slot = findTopLeftFreeSlot(width, height, columns, placed)
            placed.add(card.copy(x = slot.x, y = slot.y, width = width, height = height))
        }
        return placed
    }

    /**
     * 加载校验：钳制越界与非法的卡片、化解卡片间的重叠，
     * 最后执行一次垂直压实。重复 id 的卡片仅保留最先出现的一个。
     */
    fun validate(
        cards: List<CardLayout>,
        columns: Int,
        limits: (CardLayout) -> CardLimits = { CardLimits.DEFAULT }
    ): List<CardLayout> {
        val seen = mutableSetOf<String>()
        val clamped = mutableListOf<CardLayout>()
        for (card in cards) {
            if (!seen.add(card.id)) continue
            val lim = limits(card).clampedFor(columns)
            val width = lim.clampWidth(card.width.coerceAtLeast(1))
            val height = lim.clampHeight(card.height.coerceAtLeast(1))
            val x = card.x.coerceIn(0, columns - width)
            val y = card.y.coerceAtLeast(0)
            clamped.add(card.copy(x = x, y = y, width = width, height = height))
        }
        val settled = mutableListOf<CardLayout>()
        for (card in clamped.sortedWith(readingOrder())) {
            val position = if (settled.any { it.intersects(card) }) {
                findNearestFreeSlot(
                    width = card.width,
                    height = card.height,
                    origin = IntOffset(card.x, card.y),
                    columns = columns,
                    obstacles = settled
                ) ?: IntOffset(0, totalRows(settled))
            } else {
                IntOffset(card.x, card.y)
            }
            settled.add(card.positionAt(position))
        }
        return compact(settled)
    }

    private fun readingOrder() = compareBy<CardLayout>({ it.y }, { it.x })

    private fun overlapsVertically(a: CardLayout, b: CardLayout): Boolean =
        a.y < b.bottom && b.y < a.bottom

    private fun overlapsHorizontally(a: CardLayout, b: CardLayout): Boolean =
        a.x < b.right && b.x < a.right
}
