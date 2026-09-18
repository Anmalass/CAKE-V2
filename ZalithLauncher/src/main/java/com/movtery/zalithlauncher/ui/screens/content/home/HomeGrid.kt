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

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.movtery.zalithlauncher.R
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 网格线以卡片矩形为基准向外淡化的范围 */
private val GridFadeExtent = 72.dp

/** 网格线的最大不透明度（卡片矩形处） */
private const val GridLineMaxAlpha = 0.35f

/** 触发边缘自动滚动的带宽 */
private val AutoScrollEdge = 56.dp

/** 自动滚动的最大速度（dp/秒） */
private const val AutoScrollMaxSpeed = 900f

/**
 * 调整态选中节点，在包围盒四边中央绘制纯色手柄
 */
@SuppressLint("ModifierNodeInspectableProperties")
private data class SelectionHandlesElement(
    val color: Color,
    val handleLength: Dp,
    val handleThickness: Dp
) : ModifierNodeElement<SelectionHandlesNode>() {
    override fun create() = SelectionHandlesNode(color, handleLength, handleThickness)

    override fun update(node: SelectionHandlesNode) {
        node.color = color
        node.handleLength = handleLength
        node.handleThickness = handleThickness
    }
}

private class SelectionHandlesNode(
    var color: Color,
    var handleLength: Dp,
    var handleThickness: Dp
) : DrawModifierNode, Modifier.Node() {
    override fun ContentDrawScope.draw() {
        drawContent()

        fun drawPill(center: Offset, wide: Boolean) {
            val pillWidth = if (wide) handleLength.toPx() else handleThickness.toPx()
            val pillHeight = if (wide) handleThickness.toPx() else handleLength.toPx()
            drawRoundRect(
                color = color,
                topLeft = Offset(center.x - pillWidth / 2, center.y - pillHeight / 2),
                size = Size(pillWidth, pillHeight),
                cornerRadius = CornerRadius(handleThickness.toPx() / 2)
            )
        }

        drawPill(center = Offset(0f, size.height / 2), wide = false)
        drawPill(center = Offset(size.width, size.height / 2), wide = false)
        drawPill(center = Offset(size.width / 2, 0f), wide = true)
        drawPill(center = Offset(size.width / 2, size.height), wide = true)
    }
}

/**
 * 调整态选中手柄，绘制在节点包围盒四边中央
 */
fun Modifier.homeSelectionHandles(
    color: Color,
    handleLength: Dp = 16.dp,
    handleThickness: Dp = 5.dp,
): Modifier = then(SelectionHandlesElement(color, handleLength, handleThickness))

/**
 * 主页网格卡片容器
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeGrid(
    state: HomeGridState,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val motionScheme = MaterialTheme.motionScheme
    LaunchedEffect(motionScheme) {
        state.fastSpec = motionScheme.fastSpatialSpec()
        state.defaultSpec = motionScheme.defaultSpatialSpec()
    }

    // 播种系统卡片与持久化的用户卡片布局
    LaunchedEffect(Unit) {
        val snapshot = HomeGridStore.load()
        val typeById = HomeCards.userCardTypes.associateBy { it.typeId }
        val userCards = snapshot?.cards?.mapNotNull { entry ->
            typeById[entry.type]?.let { type ->
                HomeCard.User(
                    id = entry.id,
                    type = type,
                    layout = CardLayout(
                        id = entry.id,
                        x = entry.x,
                        y = entry.y,
                        width = entry.width,
                        height = entry.height
                    )
                )
            }
        } ?: emptyList()
        state.seed(
            systemCards = HomeCards.systemCards(),
            userCards = userCards,
            storedColumns = snapshot?.columns ?: 0
        )
    }

    // 布局结算后持久化
    LaunchedEffect(Unit) {
        state.onLayoutCommitted = {
            HomeGridStore.save(
                cards = state.cards,
                columns = state.geometry.columns
            )
        }
    }

    var viewportTop by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                viewportTop = coordinates.positionInRoot().y
                viewportHeight = coordinates.size.height.toFloat()
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                // 卡片自身再内缩 6dp，视觉边缘与容器保持 12dp
                .padding(6.dp)
        ) {
            state.cards.filterIsInstance<HomeCard.System>().forEachIndexed { index, systemCard ->
                key(systemCard.id) {
                    if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                    // 系统卡片补齐内缩量，视觉边缘同样与容器保持 12dp
                    Box(modifier = Modifier.padding(horizontal = 6.dp)) {
                        systemCard.content()
                    }
                }
            }
            // 与网格区首行卡片保持 12dp 视觉间距（6dp 间距 + 6dp 卡片内缩）
            Spacer(modifier = Modifier.height(6.dp))
            HomeGridArea(
                state = state,
                scrollState = scrollState,
                viewportTopProvider = { viewportTop },
                viewportHeightProvider = { viewportHeight },
                density = density
            )
        }
    }
}

@Composable
private fun HomeGridArea(
    state: HomeGridState,
    scrollState: ScrollState,
    viewportTopProvider: () -> Float,
    viewportHeightProvider: () -> Float,
    density: Density,
    modifier: Modifier = Modifier
) {
    val heightPx by remember { derivedStateOf { state.gridHeightPx() } }
    val targetHeight = with(density) { heightPx.toDp() }
    val animatedHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "homeGridHeight"
    )

    var areaTopInRoot by remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current

    BackHandler(enabled = state.isAdjusting) {
        state.exitAdjusting()
    }

    // 拖动/缩放期间的边缘自动滚动
    LaunchedEffect(state.hasSession) {
        if (!state.hasSession) return@LaunchedEffect
        var lastFrameNanos = 0L
        val edgePx = with(density) { AutoScrollEdge.toPx() }
        val maxSpeedPx = with(density) { AutoScrollMaxSpeed.dp.toPx() }
        while (true) {
            val frameNanos = withFrameNanos { it }
            if (lastFrameNanos != 0L) {
                val dt = (frameNanos - lastFrameNanos) / 1_000_000_000f
                val pointer = state.pointerPosition
                if (pointer != null) {
                    val viewportY = areaTopInRoot + pointer.y - viewportTopProvider()
                    val bottomDistance = viewportHeightProvider() - viewportY
                    val dyScroll = when {
                        viewportY in 0f..edgePx ->
                            -maxSpeedPx * (1f - viewportY / edgePx) * dt
                        bottomDistance in 0f..edgePx ->
                            maxSpeedPx * (1f - bottomDistance / edgePx) * dt
                        else -> 0f
                    }
                    if (dyScroll != 0f) {
                        scrollState.dispatchRawDelta(dyScroll)
                        state.onAutoScroll()
                    }
                }
            }
            lastFrameNanos = frameNanos
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                with(density) {
                    state.updateGeometry(coordinates.size.width.toDp().value, this)
                }
                state.onAreaPositioned(coordinates.positionInRoot())
                areaTopInRoot = coordinates.positionInRoot().y
            }
            .height(animatedHeight)
            .pointerInput(Unit) {
                // key 恒为 Unit：调整态通过 state.isAdjusting 在每次手势开始时动态读取，
                // 若以此为 key 会在长按进入调整态时重启并取消进行中的会话
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 已被子树交互组件（如调整态工具条）消费的手势不属于网格
                    if (down.isConsumed) return@awaitEachGesture
                    val start = down.position
                    val slopPx = viewConfiguration.touchSlop
                    val longPressMillis = viewConfiguration.longPressTimeoutMillis

                    if (!state.isAdjusting) {
                        // 非调整态：卡片上长按进入调整态并直接开始拖动
                        val hit = state.userCardAt(start) ?: return@awaitEachGesture
                        val longPressed = awaitLongPressOrAbort(down.id, start, slopPx, longPressMillis)
                        if (!longPressed) return@awaitEachGesture
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        state.onCardDragStart(hit, start)
                        runSession(
                            pointerId = down.id,
                            startPosition = start,
                            slopPx = slopPx,
                            state = state,
                            dispatch = { state.onCardDrag(it) },
                            onUp = { moved ->
                                if (moved) state.onCardDragEnd() else state.onCardDragCancel()
                            }
                        )
                    } else {
                        val edgeHit = state.resizeEdgeAt(start)
                        val card = state.userCardAt(start)
                        when {
                            // 调整态：按到手柄直接进入缩放
                            edgeHit != null -> {
                                val (hitCard, edge) = edgeHit
                                state.onResizeStart(hitCard, edge, start)
                                runSession(
                                    pointerId = down.id,
                                    startPosition = start,
                                    slopPx = slopPx,
                                    state = state,
                                    dispatch = { state.onResize(it) },
                                    onUp = { state.onResizeEnd() }
                                )
                            }
                            // 调整态：按到卡片直接拖动，无移动的松手视为点按
                            card != null -> {
                                state.onCardDragStart(card, start)
                                runSession(
                                    pointerId = down.id,
                                    startPosition = start,
                                    slopPx = slopPx,
                                    state = state,
                                    dispatch = { state.onCardDrag(it) },
                                    onUp = { moved ->
                                        when {
                                            moved -> state.onCardDragEnd()
                                            card.id == state.adjustingCardId -> state.onCardDragCancel()
                                            else -> {
                                                state.onCardDragCancel()
                                                state.exitAdjusting()
                                            }
                                        }
                                    }
                                )
                            }
                            // 调整态：空白处点按退出调整态
                            else -> {
                                val tapped = awaitUpWithoutSlop(down.id, start, slopPx)
                                if (tapped) state.exitAdjusting()
                            }
                        }
                    }
                }
            }
    ) {
        HomeGridGlowEffect(state = state, modifier = Modifier.matchParentSize())
        state.userCards().forEach { card ->
            key(card.id) {
                HomeCardSlot(state = state, card = card)
            }
        }
    }
}

/**
 * 等待长按成立：指针在超时前移动超过 [slopPx] 或抬起则中止（返回 false，
 * 事件不被消费，滚动照常进行），长按成立返回 true。
 */
private suspend fun AwaitPointerEventScope.awaitLongPressOrAbort(
    pointerId: PointerId,
    startPosition: Offset,
    slopPx: Float,
    timeoutMillis: Long
): Boolean {
    var longPressed = false
    try {
        withTimeout(timeoutMillis) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: continue
                if (!change.pressed) return@withTimeout
                if ((change.position - startPosition).getDistance() > slopPx) return@withTimeout
            }
        }
    } catch (_: PointerEventTimeoutCancellationException) {
        longPressed = true
    }
    return longPressed
}

/**
 * 等待指针在未超过 [slopPx] 的前提下抬起（点按成立返回 true）；
 * 移动超限则返回 false，事件保持不消费，滚动照常进行。
 */
private suspend fun AwaitPointerEventScope.awaitUpWithoutSlop(
    pointerId: PointerId,
    startPosition: Offset,
    slopPx: Float
): Boolean {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: continue
        if ((change.position - startPosition).getDistance() > slopPx) return false
        if (!change.pressed) return true
    }
}

/**
 * 会话拖动循环：消费全部指针事件并分发网格坐标，
 * 抬起时回调 [onUp]（是否发生了有效移动），会话被中断时取消结算。
 */
private suspend fun AwaitPointerEventScope.runSession(
    pointerId: PointerId,
    startPosition: Offset,
    slopPx: Float,
    state: HomeGridState,
    dispatch: (Offset) -> Unit,
    onUp: (moved: Boolean) -> Unit
) {
    try {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId } ?: continue
            if (!change.pressed) {
                event.changes.forEach { it.consume() }
                onUp((change.position - startPosition).getDistance() > slopPx)
                return
            }
            dispatch(change.position)
            event.changes.forEach { it.consume() }
        }
    } finally {
        if (state.hasSession) state.onCardDragCancel()
    }
}

@Composable
private fun HomeCardSlot(
    state: HomeGridState,
    card: HomeCard.User,
    modifier: Modifier = Modifier
) {
    val rectProvider: () -> Rect = { state.renderRectOf(card) }
    val interaction = state.interactionOf(card.id)
    val zIndex = when {
        state.isSessionCard(card.id) -> 3f
        interaction != HomeCardInteraction.Idle -> 2f
        else -> 1f
    }
    val adjusting = state.adjustingCardId == card.id

    Box(
        modifier = modifier
            .homeCardBounds(rectProvider)
            .zIndex(zIndex)
    ) {
        HomeCardSurface(
            interaction = interaction,
            modifier = Modifier.fillMaxSize(),
            shape = card.type.shape ?: MaterialTheme.shapes.extraLarge,
            selected = adjusting
        ) {
            card.type.content(state.cardStateOf(card))
        }

        if (adjusting) {
            HomeCardToolbar(state = state, card = card)
        }
    }
}

/**
 * 调整态悬浮工具条
 * @param height 工具条高度
 * @param gap 工具条与卡片之间的间隙
 */
@Composable
private fun BoxScope.HomeCardToolbar(
    state: HomeGridState,
    card: HomeCard.User,
    height: Dp = 40.dp,
    gap: Dp = 8.dp
) {
    Surface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .offset(y = -(gap + height))
            .zIndex(1f)
            .gestureGuard(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .height(height)
                .padding(all = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                modifier = Modifier.size(34.dp),
                onClick = { state.removeCard(card.id) }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete_outlined),
                    contentDescription = stringResource(R.string.generic_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
            IconButton(
                modifier = Modifier.size(34.dp),
                onClick = { state.exitAdjusting() }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = stringResource(R.string.generic_done),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 消费落在自身范围内的全部指针事件：
 * 子节点先行处理，未被消费的事件在此标记为已消费，
 * 使手势在向上冒泡途中止于自身，不再触达祖先节点。
 */
private fun Modifier.gestureGuard(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) return@awaitEachGesture
        }
    }
}

/**
 * 拖动/缩放期间显示网格线：以吸附预览矩形为中心，
 * 网格线随所在位置到卡片的距离增加而逐格淡化消失，如光晕般收敛于卡片周围。
 */
@Composable
private fun HomeGridGlowEffect(state: HomeGridState, modifier: Modifier = Modifier) {
    val preview = state.dragPreview ?: return
    val previewRect = state.rectFor(preview)
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val cell = state.cellPx
    val columns = state.geometry.columns
    Canvas(modifier = modifier.zIndex(0.1f)) {
        // 吸附落点高亮
        drawRoundRect(
            color = primary.copy(alpha = 0.08f),
            topLeft = previewRect.topLeft,
            size = previewRect.size,
            cornerRadius = CornerRadius(16.dp.toPx())
        )

        val fadePx = GridFadeExtent.toPx()
        val startCol = floor((previewRect.left - fadePx) / cell).toInt().coerceAtLeast(0)
        val endCol = ceil((previewRect.right + fadePx) / cell).toInt().coerceAtMost(columns)
        val startRow = floor((previewRect.top - fadePx) / cell).toInt().coerceAtLeast(0)
        val endRow = ceil((previewRect.bottom + fadePx) / cell).toInt()

        // 线段到预览矩形的距离越远，透明度越低（smoothstep 衰减）
        fun segmentAlpha(seg: Rect): Float {
            val dx = maxOf(0f, previewRect.left - seg.right, seg.left - previewRect.right)
            val dy = maxOf(0f, previewRect.top - seg.bottom, seg.top - previewRect.bottom)
            val distance = sqrt(dx * dx + dy * dy)
            if (distance >= fadePx) return 0f
            val t = 1f - distance / fadePx
            return GridLineMaxAlpha * t * t * (3f - 2f * t)
        }

        val strokeWidth = 1.dp.toPx()
        for (row in startRow..endRow) {
            val y = row * cell
            for (col in startCol..endCol) {
                val alpha = segmentAlpha(Rect(col * cell, y, (col + 1) * cell, y))
                if (alpha > 0f) {
                    drawLine(
                        color = onSurface.copy(alpha = alpha),
                        start = Offset(col * cell, y),
                        end = Offset((col + 1) * cell, y),
                        strokeWidth = strokeWidth
                    )
                }
            }
        }
        for (col in startCol..endCol) {
            val x = col * cell
            for (row in startRow..endRow) {
                val alpha = segmentAlpha(Rect(x, row * cell, x, (row + 1) * cell))
                if (alpha > 0f) {
                    drawLine(
                        color = onSurface.copy(alpha = alpha),
                        start = Offset(x, row * cell),
                        end = Offset(x, (row + 1) * cell),
                        strokeWidth = strokeWidth
                    )
                }
            }
        }
    }
}

/** 以网格坐标矩形定位并定尺寸的修饰符，矩形变化时触发重新测量 */
private fun Modifier.homeCardBounds(rectProvider: () -> Rect): Modifier = this
    .offset {
        val rect = rectProvider()
        IntOffset(rect.left.roundToInt(), rect.top.roundToInt())
    }
    .layout { measurable, constraints ->
        val rect = rectProvider()
        val width = rect.width.roundToInt().coerceAtLeast(1)
        val height = rect.height.roundToInt().coerceAtLeast(1)
        val placeable = measurable.measure(
            constraints.copy(
                minWidth = width,
                maxWidth = width,
                minHeight = height,
                maxHeight = height
            )
        )
        layout(width, height) { placeable.place(0, 0) }
    }
