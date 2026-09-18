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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 主页网格的状态持有者：
 * 统一持有系统卡片与用户卡片，所有指针坐标均为网格内容坐标系
 * （网格区域左上角为原点、像素单位），命中测试与会话结算均在此坐标系下进行，
 * 布局结算全部委托 [HomeGridEngine]，卡片渲染矩形通过逐卡 [Animatable] 以弹簧动画过渡。
 */
@Stable
class HomeGridState internal constructor(
    private val scope: CoroutineScope
) {
    /** 拖动/缩放会话 */
    private data class AdjustSession(
        val card: HomeCard.User,
        val originalLayout: CardLayout,
        val grabOffset: Offset,
        val mode: Mode
    ) {
        sealed interface Mode {
            data object Move : Mode
            data class Resize(val edge: HomeResizeEdge) : Mode
        }
    }

    /** 网格几何（列数恒为偶数，单元格为正方形） */
    var geometry by mutableStateOf(GridGeometry(MIN_GRID_COLUMNS, DEFAULT_TARGET_CELL_SIZE))
        private set

    /** 单元格边长（px） */
    var cellPx by mutableFloatStateOf(20f)
        private set

    /** 屏幕密度（dp → px 换算），随几何更新 */
    private var densityFactor by mutableFloatStateOf(1f)

    /** 卡片矩形在单元格内的收缩量（px） */
    var cardInsetPx by mutableIntStateOf(2)
        private set

    /** 统一持有的卡片列表：系统卡片在前、用户卡片在后，各自保持加入顺序 */
    var cards by mutableStateOf<List<HomeCard>>(emptyList())

    /** 处于调整态（长按选中）的卡片 id */
    var adjustingCardId by mutableStateOf<String?>(null)
        private set

    /** 是否处于调整态 */
    val isAdjusting: Boolean get() = adjustingCardId != null

    /** 吸附后的预览布局（虚影位置），仅会话期间非空 */
    var dragPreview by mutableStateOf<CardLayout?>(null)
        private set

    /** 跟随手指的原始矩形，仅会话期间非空 */
    var dragRawRect by mutableStateOf<Rect?>(null)
        private set

    /** 指针在网格内容坐标系中的位置，供网格光晕与自动滚动使用 */
    var pointerPosition by mutableStateOf<Offset?>(null)
        private set

    /** 网格区域在窗口坐标系中的偏移（随滚动变化） */
    private var areaOffsetInRoot by mutableStateOf(Offset.Zero)

    /**
     * 手指在窗口坐标系中的锚点：窗口坐标不受滚动影响，
     * 指针的网格坐标始终由锚点与网格区域当前偏移整体换算得出，
     * 避免滚动增量与事件坐标之间的反馈振荡。
     */
    private var pointerAnchorInRoot: Offset? = null

    /** 网格区域布局位置回调 */
    fun onAreaPositioned(offsetInRoot: Offset) {
        areaOffsetInRoot = offsetInRoot
    }

    /** 被挤压让位的卡片（id -> 让位布局），仅会话期间非空 */
    var displaced by mutableStateOf<Map<String, CardLayout>>(emptyMap())
        private set

    /** 布局发生结算后的回调（用于持久化） */
    var onLayoutCommitted: () -> Unit = {}

    /** 卡片被移除后的回调（用于同步外部与该卡片关联的数据） */
    var onCardRemoved: (cardId: String) -> Unit = {}

    /** 快速空间动画（拖动中的让位） */
    internal var fastSpec: AnimationSpec<Rect> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    /** 默认空间动画（结算、压实） */
    internal var defaultSpec: AnimationSpec<Rect> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    private var session by mutableStateOf<AdjustSession?>(null)

    /** 是否存在进行中的拖动/缩放会话 */
    val hasSession: Boolean get() = session != null

    /** 待播种的持久化卡片，待网格几何就绪后生效 */
    private var pendingCards: List<HomeCard.User>? = null
    private var pendingColumns: Int = 0

    /** 网格几何是否已经依据实际容器宽度完成过计算 */
    private var geometryReady = false

    private val animators = mutableMapOf<String, Animatable<Rect, AnimationVector4D>>()

    // ---------- 卡片列表 ----------

    /** 全部用户卡片 */
    fun userCards(): List<HomeCard.User> = cards.filterIsInstance<HomeCard.User>()

    private fun userById(id: String): HomeCard.User? =
        cards.firstOrNull { it is HomeCard.User && it.id == id } as? HomeCard.User

    private fun userLayouts(): List<CardLayout> = userCards().map { it.layout }

    private fun replaceCard(id: String, layout: CardLayout): List<HomeCard> = cards.map { card ->
        if (card is HomeCard.User && card.id == id) {
            card.copy(layout = layout)
        } else {
            card
        }
    }

    /**
     * 播种卡片：系统卡片立即生效，持久化的用户卡片
     * 待网格几何就绪后校验修复（列数一致）或按阅读顺序重排（列数不一致）。
     */
    fun seed(systemCards: List<HomeCard.System>, userCards: List<HomeCard.User>, storedColumns: Int) {
        cards = systemCards
        val distinct = userCards.distinctBy { it.id }
        if (distinct.isEmpty()) return

        pendingCards = distinct
        pendingColumns = storedColumns
        if (geometryReady) materializePending(newColumns = geometry.columns)
    }

    private fun materializePending(newColumns: Int) {
        val pending = pendingCards ?: return
        pendingCards = null
        val typeById = pending.associate { it.id to it.type }
        val limits: (CardLayout) -> CardLimits = { typeById[it.id]?.limits ?: CardLimits.DEFAULT }
        val layouts = if (pendingColumns == newColumns) {
            HomeGridEngine.validate(pending.map { it.layout }, newColumns, limits)
        } else {
            HomeGridEngine.reflow(
                pending.map { it.layout },
                oldColumns = pendingColumns,
                columns = newColumns,
                limits = limits
            )
        }.associateBy { it.id }

        val materialized = pending.map { card ->
            card.copy(layout = layouts.getValue(card.id))
        }
        cards = cards + materialized
        materialized.forEach { animateTo(it, effectiveLayout(it), defaultSpec) }
    }

    // ---------- 几何 ----------

    /** 依据容器宽度更新网格；列数变化时触发整体重排 */
    fun updateGeometry(widthDp: Float, density: Density) {
        val oldColumns = geometry.columns
        val newGeometry = computeGridGeometry(widthDp)
        cellPx = newGeometry.cellSize * density.density
        cardInsetPx = with(density) {
            HomeCardSpacing.roundToPx()
        }
        densityFactor = density.density
        geometry = newGeometry
        if (widthDp > 0f) {
            geometryReady = true
            materializePending(newColumns = newGeometry.columns)
        }
        if (newGeometry.columns != oldColumns && userCards().isNotEmpty()) {
            reflowTo(newColumns = newGeometry.columns, oldColumns = oldColumns)
        }
    }

    /** 卡片布局对应的渲染矩形（px），锚点为网格左上角 */
    fun rectFor(layout: CardLayout): Rect = Rect(
        left = layout.x * cellPx + cardInsetPx,
        top = layout.y * cellPx + cardInsetPx,
        right = layout.right * cellPx - cardInsetPx,
        bottom = layout.bottom * cellPx - cardInsetPx
    )

    /** 网格内容高度（px），含一行备用行供尾部拖放 */
    fun gridHeightPx(): Float {
        val rows = max(
            HomeGridEngine.totalRows(userLayouts()),
            dragPreview?.bottom ?: 0
        )
        return (rows + 1) * cellPx
    }

    // ---------- 卡片管理 ----------

    /** 追加一张卡片 */
    fun addCard(type: HomeCardType, id: String = UUID.randomUUID().toString()): HomeCard.User? {
        if (userCards().any { it.id == id }) return null
        val lim = type.limits.clampedFor(geometry.columns)
        val width = type.defaultSpan.x.coerceIn(lim.minWidth, lim.maxWidth)
        val height = type.defaultSpan.y.coerceIn(lim.minHeight, lim.maxHeight)
        val slot = HomeGridEngine.findTopLeftFreeSlot(
            width = width,
            height = height,
            columns = geometry.columns,
            obstacles = userLayouts()
        )
        val card = HomeCard.User(
            id = id,
            type = type,
            layout = CardLayout(id = id, x = slot.x, y = slot.y, width = width, height = height)
        )
        cards = cards + card
        onLayoutCommitted()
        return card
    }

    /** 移除一张用户卡片并压实剩余布局 */
    fun removeCard(id: String) {
        val removed = userById(id) ?: return
        val remaining = cards.filterNot { it.id == id }
        val compacted = HomeGridEngine.compact(
            remaining.filterIsInstance<HomeCard.User>().map { it.layout }
        ).associateBy { it.id }
        cards = remaining.map { card ->
            if (card is HomeCard.User) card.copy(layout = compacted.getValue(card.id)) else card
        }
        animators.remove(removed.id)
        if (adjustingCardId == id) adjustingCardId = null
        if (session?.card?.id == id) endSession()
        userCards().forEach { card ->
            animateTo(card, effectiveLayout(card), defaultSpec)
        }
        onLayoutCommitted()
        onCardRemoved(id)
    }

    // ---------- 命中测试（网格内容坐标系） ----------

    /** 命中测试用户卡片，z 序高的优先（调整态卡优先，其余列表靠后者在上，与绘制层叠顺序一致） */
    fun userCardAt(position: Offset): HomeCard.User? {
        val ordered = userCards()
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<HomeCard.User>> { (_, card) ->
                    if (card.id == adjustingCardId) 1 else 0
                }.thenByDescending { it.index }
            )
        return ordered.firstOrNull { (_, card) ->
            rectFor(effectiveLayout(card)).contains(position)
        }?.value
    }

    /**
     * 命中测试调整态卡片的缩放手柄热区，
     * @return 卡片与命中的边，未命中返回 null
     */
    fun resizeEdgeAt(position: Offset): Pair<HomeCard.User, HomeResizeEdge>? {
        val card = adjustingCardId?.let { userById(it) } ?: return null
        val rect = renderRectOf(card)
        val hitRadiusPx = EDGE_HIT_RADIUS_DP * densityFactor
        return HomeResizeEdge.entries
            .map { edge -> edge to edgeCenter(edge, rect) }
            .map { (edge, center) -> edge to (position - center).getDistance() }
            .filter { (_, distance) -> distance <= hitRadiusPx }
            .minByOrNull { (_, distance) -> distance }
            ?.let { (edge, _) -> card to edge }
    }

    private fun edgeCenter(edge: HomeResizeEdge, rect: Rect): Offset = when (edge) {
        HomeResizeEdge.Start -> Offset(rect.left, rect.center.y)
        HomeResizeEdge.Top -> Offset(rect.center.x, rect.top)
        HomeResizeEdge.End -> Offset(rect.right, rect.center.y)
        HomeResizeEdge.Bottom -> Offset(rect.center.x, rect.bottom)
    }

    // ---------- 调整会话：拖动 ----------

    /** 长按成功，卡片进入拖动（同时也进入了调整态） */
    fun onCardDragStart(card: HomeCard.User, pointer: Offset) {
        val layout = effectiveLayout(card)
        session = AdjustSession(
            card = card,
            originalLayout = card.layout,
            grabOffset = pointer - rectFor(layout).topLeft,
            mode = AdjustSession.Mode.Move
        )
        adjustingCardId = card.id
        pointerAnchorInRoot = areaOffsetInRoot + pointer
        dragRawRect = rectFor(layout)
        pointerPosition = pointer
        applyPreview(card.layout)
    }

    fun onCardDrag(pointer: Offset) {
        val current = session ?: return
        check(current.mode is AdjustSession.Mode.Move) { "Current session is not a drag session" }

        pointerAnchorInRoot = areaOffsetInRoot + pointer
        pointerPosition = pointer
        val size = rectFor(current.card.layout).size
        val topLeft = Offset(
            pointer.x - current.grabOffset.x,
            pointer.y - current.grabOffset.y
        )
        dragRawRect = Rect(offset = topLeft, size = size)
        applyPreview(snapMoveLayout(current.card, topLeft))
    }

    /** 松手：提交预览布局并压实 */
    fun onCardDragEnd() {
        val current = session ?: return
        commit(previewLayoutOf(current))
    }

    /** 拖动被取消：一切回到会话前的状态 */
    fun onCardDragCancel() {
        cancelSession()
    }

    // ---------- 调整会话：缩放 ----------

    /** 开始拖动某条边的手柄，单向调整跨度 */
    fun onResizeStart(card: HomeCard.User, edge: HomeResizeEdge, pointer: Offset) {
        session = AdjustSession(
            card = card,
            originalLayout = card.layout,
            grabOffset = pointer,
            mode = AdjustSession.Mode.Resize(edge)
        )
        adjustingCardId = card.id
        pointerAnchorInRoot = areaOffsetInRoot + pointer
        dragRawRect = rectFor(card.layout)
        pointerPosition = pointer
        applyPreview(card.layout)
    }

    fun onResize(pointer: Offset) {
        val current = session ?: return
        val edge = (current.mode as? AdjustSession.Mode.Resize)?.edge ?: return
        pointerAnchorInRoot = areaOffsetInRoot + pointer
        pointerPosition = pointer
        dragRawRect = rawRectForResize(current.card, edge, pointer)
        applyPreview(resizeLayout(current.card, current.card.layout, edge, pointer))
    }

    fun onResizeEnd() {
        val current = session ?: return
        commit(previewLayoutOf(current))
    }

    fun onResizeCancel() {
        cancelSession()
    }

    /**
     * 自动滚动后重算指针位置：
     * 手指的窗口锚点不变，滚动改变了网格区域的窗口偏移，
     * 指针的网格坐标由两者整体换算（全量覆盖，无增量累积）。
     */
    internal fun onAutoScroll() {
        val anchor = pointerAnchorInRoot ?: return
        val local = anchor - areaOffsetInRoot
        when (session?.mode) {
            is AdjustSession.Mode.Move -> onCardDrag(local)
            is AdjustSession.Mode.Resize -> onResize(local)
            null -> Unit
        }
    }

    // ---------- 调整态 ----------

    /** 指定卡片当前的交互状态 */
    fun interactionOf(cardId: String): HomeCardInteraction {
        val current = session
        return when {
            current?.card?.id == cardId && current.mode is AdjustSession.Mode.Resize -> HomeCardInteraction.Resizing
            current?.card?.id == cardId && current.mode is AdjustSession.Mode.Move -> HomeCardInteraction.Dragging
            adjustingCardId == cardId -> HomeCardInteraction.Adjusting
            else -> HomeCardInteraction.Idle
        }
    }

    /** 会话是否正作用于指定卡片（渲染时跟手矩形替换动画矩形） */
    fun isSessionCard(cardId: String): Boolean = session?.card?.id == cardId

    /** 退出调整态（点击空白或返回键） */
    fun exitAdjusting() {
        if (session != null) cancelSession()
        adjustingCardId = null
    }

    /** 提供给卡片内容的自身状态（缩放会话期间跟随吸附预览，实时感知尺寸变化） */
    fun cardStateOf(card: HomeCard.User): HomeCardState {
        val layout = if (isSessionCard(card.id)) dragPreview ?: card.layout else card.layout
        return HomeCardState(
            spanWidth = layout.width,
            spanHeight = layout.height,
            columns = geometry.columns,
            interaction = interactionOf(card.id)
        )
    }

    // ---------- 渲染 ----------

    /** 指定卡片的渲染矩形动画器 */
    internal fun animatorFor(card: HomeCard.User): Animatable<Rect, AnimationVector4D> =
        animators.getOrPut(card.id) {
            Animatable(rectFor(card.layout), Rect.VectorConverter)
        }

    /** 单张卡片的当前渲染矩形（会话卡跟手，其余取动画值） */
    internal fun renderRectOf(card: HomeCard.User): Rect {
        if (isSessionCard(card.id)) return dragRawRect ?: animatorFor(card).value
        return animatorFor(card).value
    }

    private fun animateTo(card: HomeCard.User, layout: CardLayout, spec: AnimationSpec<Rect>) {
        val animatable = animatorFor(card)
        val target = rectFor(layout)
        scope.launch { animatable.animateTo(target, spec) }
    }

    private fun animateAll() {
        userCards().forEach { animateTo(it, effectiveLayout(it), defaultSpec) }
    }

    /**
     * 会话卡结算：动画器先吸附到松手时的跟手矩形，再动画到最终布局，
     * 避免跟手渲染切换回动画渲染时发生瞬移。
     */
    private fun settleSessionCard(card: HomeCard.User, rawRect: Rect?, target: CardLayout) {
        val animatable = animatorFor(card)
        scope.launch {
            rawRect?.let { animatable.snapTo(it) }
            animatable.animateTo(rectFor(target), defaultSpec)
        }
    }

    // ---------- 内部：结算 ----------

    private fun effectiveLayout(card: HomeCard.User): CardLayout =
        displaced[card.id] ?: card.layout

    private fun previewLayoutOf(session: AdjustSession): CardLayout =
        dragPreview ?: session.card.layout

    /** 应用新的吸附预览：结算挤压让位并动画过渡受影响的卡片 */
    private fun applyPreview(preview: CardLayout) {
        if (dragPreview == preview) return
        dragPreview = preview
        val newDisplaced = HomeGridEngine.resolveDisplacements(
            moving = preview,
            columns = geometry.columns,
            cards = userLayouts()
        )
        val affected = displaced.keys + newDisplaced.keys
        displaced = newDisplaced
        affected.forEach { id ->
            userById(id)?.let { card ->
                animateTo(card, newDisplaced[id] ?: card.layout, fastSpec)
            }
        }
    }

    /** 提交会话结果：合并移动卡与让位卡，压实并持久化 */
    private fun commit(target: CardLayout) {
        val current = session ?: return
        // endSession 会清空跟手矩形，必须先捕获供动画器吸附
        val rawRect = dragRawRect
        cards = cards.map { card ->
            when {
                card is HomeCard.User && card.id == current.card.id -> card.copy(layout = target)
                card is HomeCard.User -> displaced[card.id]?.let { card.copy(layout = it) } ?: card
                else -> card
            }
        }
        cards = compactCards(cards)
        // 压实可能改变会话卡的最终落位，以列表中的最终布局为准
        val finalLayout = userById(current.card.id)?.layout ?: target
        endSession()
        settleSessionCard(current.card, rawRect, finalLayout)
        cards.filterIsInstance<HomeCard.User>()
            .filterNot { it.id == current.card.id }
            .forEach { animateTo(it, effectiveLayout(it), defaultSpec) }
        onLayoutCommitted()
    }

    /** 取消会话：让位卡与会话卡弹回原位，不产生任何结算 */
    private fun cancelSession() {
        val current = session ?: return
        val rawRect = dragRawRect
        val affected = displaced.keys
        endSession()
        affected.forEach { id ->
            userById(id)?.let { animateTo(it, it.layout, defaultSpec) }
        }
        settleSessionCard(current.card, rawRect, current.card.layout)
    }

    private fun endSession() {
        session = null
        dragPreview = null
        dragRawRect = null
        pointerPosition = null
        displaced = emptyMap()
    }

    /** 垂直压实全部用户卡片，保持实例映射 */
    private fun compactCards(list: List<HomeCard>): List<HomeCard> {
        val compacted = HomeGridEngine.compact(
            list.filterIsInstance<HomeCard.User>().map { it.layout }
        ).associateBy { it.id }
        return list.map { card ->
            if (card is HomeCard.User) card.copy(layout = compacted.getValue(card.id)) else card
        }
    }

    /** 列数变化：按阅读顺序重排并持久化 */
    private fun reflowTo(newColumns: Int, oldColumns: Int) {
        val typeById = userCards().associate { it.id to it.type }
        val layouts = HomeGridEngine.reflow(
            cards = userLayouts(),
            oldColumns = oldColumns,
            columns = newColumns,
            limits = { typeById[it.id]?.limits ?: CardLimits.DEFAULT }
        ).associateBy { it.id }
        cards = cards.map { card ->
            if (card is HomeCard.User) card.copy(layout = layouts.getValue(card.id)) else card
        }
        endSession()
        adjustingCardId = null
        animateAll()
        onLayoutCommitted()
    }

    // ---------- 内部：吸附计算 ----------

    private fun snapMoveLayout(card: HomeCard.User, rawTopLeft: Offset): CardLayout {
        val x = (rawTopLeft.x / cellPx).roundToInt().coerceIn(0, geometry.columns - card.layout.width)
        val y = (rawTopLeft.y / cellPx).roundToInt().coerceAtLeast(0)
        return card.layout.copy(x = x, y = y)
    }

    /** 依据指针位置计算碰壁式缩放的吸附布局，锚定对侧边缘 */
    private fun resizeLayout(
        card: HomeCard.User,
        layout: CardLayout,
        edge: HomeResizeEdge,
        pointer: Offset
    ): CardLayout {
        return HomeGridEngine.resizeWithWalls(
            current = layout,
            edge = edge,
            pointer = IntOffset(
                (pointer.x / cellPx).roundToInt(),
                (pointer.y / cellPx).roundToInt()
            ),
            columns = geometry.columns,
            limits = card.type.limits,
            obstacles = userLayouts().filterNot { it.id == card.id }
        )
    }

    /** 依据指针位置计算缩放时跟手的原始矩形，被拖动边钳制在碰壁界内 */
    private fun rawRectForResize(card: HomeCard.User, edge: HomeResizeEdge, pointer: Offset): Rect {
        val layout = card.layout
        val range = HomeGridEngine.resizeWall(
            current = layout,
            edge = edge,
            columns = geometry.columns,
            limits = card.type.limits,
            obstacles = userLayouts().filterNot { it.id == card.id }
        )
        val left = layout.x * cellPx + cardInsetPx
        val top = layout.y * cellPx + cardInsetPx
        val right = layout.right * cellPx - cardInsetPx
        val bottom = layout.bottom * cellPx - cardInsetPx
        // 各跨度对应的被拖动边像素位置，跟手矩形与吸附布局共用同一堵墙
        fun edgePxStart(span: Int) = (layout.right - span) * cellPx + cardInsetPx
        fun edgePxTop(span: Int) = (layout.bottom - span) * cellPx + cardInsetPx
        fun edgePxEnd(span: Int) = (layout.x + span) * cellPx - cardInsetPx
        fun edgePxBottom(span: Int) = (layout.y + span) * cellPx - cardInsetPx
        return when (edge) {
            HomeResizeEdge.End -> Rect(left, top, pointer.x.coerceIn(edgePxEnd(range.first), edgePxEnd(range.last)), bottom)
            HomeResizeEdge.Start -> Rect(pointer.x.coerceIn(edgePxStart(range.last), edgePxStart(range.first)), top, right, bottom)
            HomeResizeEdge.Bottom -> Rect(left, top, right, pointer.y.coerceIn(edgePxBottom(range.first), edgePxBottom(range.last)))
            HomeResizeEdge.Top -> Rect(left, pointer.y.coerceIn(edgePxTop(range.last), edgePxTop(range.first)), right, bottom)
        }
    }

    companion object {
        /** 缩放手柄热区半径（dp） */
        private const val EDGE_HIT_RADIUS_DP = 24f
    }
}

/** 创建与组合生命周期绑定的 [HomeGridState] */
@Composable
fun rememberHomeGridState(): HomeGridState {
    val scope = rememberCoroutineScope()
    return remember { HomeGridState(scope) }
}
