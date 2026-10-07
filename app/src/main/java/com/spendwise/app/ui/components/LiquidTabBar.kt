package com.spendwise.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.spendwise.app.ui.glass.LocalBlurAllowed
import com.spendwise.app.ui.glass.LocalContentBackdrop
import com.spendwise.app.ui.glass.liquidGlass
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

data class BottomBarItem<K>(
    val key: K,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

/**
 * Floating liquid-glass navigation (ADR-008): a clear glass capsule of tabs with a liquid selection droplet,
 * plus a separate floating glass "+" button — bottom-weighted for one-handed reach.
 */
@Composable
fun <K> LiquidTabBar(
    items: List<BottomBarItem<K>>,
    selected: K,
    onSelect: (K) -> Unit,
    onPrimaryAction: () -> Unit,
    primaryActionLabel: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiquidTabs(items, selected, onSelect, Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        LiquidPrimaryButton(primaryActionLabel, onPrimaryAction)
    }
}

@Composable
private fun <K> LiquidTabs(
    items: List<BottomBarItem<K>>,
    selected: K,
    onSelect: (K) -> Unit,
    modifier: Modifier,
) {
    val colors = SpendWise.colors
    val haptics = LocalHapticFeedback.current
    val contentBackdrop = LocalContentBackdrop.current
    val refraction = LocalBlurAllowed.current
    val accentLayer = rememberLayerBackdrop()
    val dropletBackdrop = if (contentBackdrop != null) rememberCombinedBackdrop(contentBackdrop, accentLayer) else accentLayer
    val scope = rememberCoroutineScope()
    val count = items.size
    val selectedIndex = items.indexOfFirst { it.key == selected }.coerceAtLeast(0)
    val currentOnSelect by rememberUpdatedState(onSelect)

    /** Droplet position in tab units, and how "pressed/liquid" it is (0 = resting, 1 = swollen lens). */
    val position = remember { Animatable(selectedIndex.toFloat()) }
    val press = remember { Animatable(0f) }

    LaunchedEffect(selectedIndex) {
        position.animateTo(selectedIndex.toFloat(), spring(dampingRatio = 0.72f, stiffness = 380f))
    }

    BoxWithConstraints(modifier.height(64.dp)) {
        val density = LocalDensity.current
        val padPx = with(density) { 4.dp.toPx() }
        val tabWidthPx = (constraints.maxWidth - 2 * padPx) / count
        val tabWidth = with(density) { tabWidthPx.toDp() }

        // 1 — the visible glass capsule with all tabs in their resting colour.
        Row(
            Modifier
                .fillMaxSize()
                .liquidGlass(Capsule())
                .pointerInput(count, tabWidthPx) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        scope.launch { press.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 900f)) }
                        var dragging = false
                        var travelled = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            val dx = change.positionChange().x
                            travelled += dx
                            if (!dragging && abs(travelled) > viewConfiguration.touchSlop) dragging = true
                            if (dragging) {
                                change.consume()
                                scope.launch {
                                    position.snapTo((position.value + dx / tabWidthPx).coerceIn(0f, (count - 1).toFloat()))
                                }
                            }
                        }
                        if (dragging) {
                            val target = position.value.roundToInt().coerceIn(0, count - 1)
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            currentOnSelect(items[target].key)
                            scope.launch { position.animateTo(target.toFloat(), spring(dampingRatio = 0.72f, stiffness = 380f)) }
                        }
                        scope.launch { press.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 300f)) }
                    }
                }
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                TabContent(
                    item = item,
                    selected = index == selectedIndex,
                    tint = colors.text2,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = index == selectedIndex,
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onSelect(item.key)
                            },
                        ),
                )
            }
        }

        // 2 — an invisible accent-tinted copy of the tabs, recorded so the droplet can show it through its lens.
        Row(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(accentLayer)
                // Flat copy of the bar's fill: the droplet already refracts the real backdrop, so refracting
                // here too would bend the bar twice (and cost a full glass pass every frame).
                .background(colors.glassTint, Capsule())
                // The droplet's resting fill lives UNDER the accent icons, so the selected tab stays vivid.
                .background(colors.droplet, Capsule())
                .padding(4.dp)
                .graphicsLayer(colorFilter = ColorFilter.tint(colors.primary)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                TabContent(item, selected = true, tint = Color.White, modifier = Modifier.weight(1f).fillMaxHeight(), emphasized = true)
            }
        }

        // 3 — the liquid droplet. Resting: a soft lit capsule over the selected tab, revealing its accent icon.
        // Touched or dragged: it swells, refracts with chromatic aberration and stretches with velocity.
        Box(
            Modifier
                .padding(4.dp)
                .graphicsLayer { translationX = position.value * tabWidthPx }
                .width(tabWidth)
                .fillMaxHeight()
                .clearAndSetSemantics {}
                .drawBackdrop(
                    backdrop = dropletBackdrop,
                    shape = { Capsule() },
                    effects = {
                        val p = press.value
                        // Resting: a gentle lens so the droplet reads as glass. Pressed: deeper, with colour fringing.
                        if (refraction) lens((8f + 6f * p).dp.toPx(), (12f + 8f * p).dp.toPx(), chromaticAberration = p > 0.05f)
                    },
                    highlight = { Highlight(width = 1.dp, alpha = 0.5f + 0.5f * press.value) },
                    shadow = { Shadow(radius = 10.dp, alpha = if (colors.isDark) press.value else 0.5f + 0.5f * press.value) },
                    innerShadow = { InnerShadow(radius = 8.dp * press.value, alpha = press.value) },
                    layerBlock = {
                        val p = press.value
                        val swell = 1f + 0.16f * p
                        val v = (position.velocity / 10f).coerceIn(-1f, 1f)
                        scaleX = swell / (1f - (abs(v) * 0.25f).coerceAtMost(0.2f))
                        scaleY = swell * (1f - (abs(v) * 0.12f).coerceAtMost(0.12f))
                    },
                    onDrawSurface = {
                        // Pressed: a faint shade so the swollen lens reads as thicker glass.
                        drawRect(Color.Black.copy(alpha = 0.04f * press.value))
                    },
                ),
        )
    }
}

@Composable
private fun <K> TabContent(item: BottomBarItem<K>, selected: Boolean, tint: Color, modifier: Modifier, emphasized: Boolean = false) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Text(
            item.label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 12.sp,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = tint,
            maxLines = 1,
        )
    }
}

@Composable
private fun LiquidPrimaryButton(label: String, onClick: () -> Unit) {
    val colors = SpendWise.colors
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(pressed) {
        scale.animateTo(if (pressed) 1.08f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f))
    }
    Box(
        Modifier
            .size(64.dp)
            // Solid accent with glass edges (rim, lens at the border, shadow). Scale lives in the glass layer,
            // so pressing grows the glass instead of distorting what it samples.
            .liquidGlass(
                Capsule(),
                tint = colors.primary.copy(alpha = 0.92f),
                lensHeight = 14f,
                lensAmount = 24f,
                blurDp = 2f,
                layerBlock = {
                    scaleX = scale.value
                    scaleY = scale.value
                },
            )
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onClick()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(28.dp))
    }
}
