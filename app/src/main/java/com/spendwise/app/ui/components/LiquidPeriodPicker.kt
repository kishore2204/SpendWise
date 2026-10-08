package com.spendwise.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.shapes.Capsule
import com.spendwise.app.domain.analytics.ReportPeriod
import com.spendwise.app.ui.glass.GlassKind
import com.spendwise.app.ui.glass.liquidGlass
import com.spendwise.app.ui.theme.Radius
import com.spendwise.app.ui.theme.Spacing
import com.spendwise.app.ui.theme.SpendWise
import kotlin.math.roundToInt

/** Compact liquid-glass trigger. Its menu is rendered separately in a screen-level overlay host. */
@Composable
fun LiquidPeriodPickerTrigger(
    selected: ReportPeriod,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAnchorChanged: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    shortLabel: Boolean = false,
) {
    val colors = SpendWise.colors
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        if (expanded) 180f else 0f,
        tween(220),
        label = "period-chevron",
    )
    Row(
        modifier
            .liquidGlass(Capsule(), GlassKind.Card)
            .clip(CircleShape)
            .clickable(onClick = onToggle)
            .onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInWindow()
                val next = Rect(origin, origin + Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                onAnchorChanged(next)
            }
            .semantics {
                contentDescription = if (shortLabel) "Insights period, ${selected.granularityLabel}" else "Summary period, ${selected.label}"
                paneTitle = if (expanded) "Period menu expanded" else "Period menu"
            }
            .padding(horizontal = Spacing.sm, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (shortLabel) selected.granularityLabel else selected.label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = colors.text1,
            maxLines = 1,
        )
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = colors.text2,
            modifier = Modifier.rotate(rotation),
        )
    }
}

/** Same-window overlay keeps Kyant glass sampling and never participates in LazyColumn measurement. */
@Composable
fun LiquidPeriodPickerOverlay(
    expanded: Boolean,
    selected: ReportPeriod,
    anchorInWindow: Rect?,
    onDismiss: () -> Unit,
    onSelect: (ReportPeriod) -> Unit,
    shortLabels: Boolean = false,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = expanded, onBack = onDismiss)
    val density = LocalDensity.current
    var rootInWindow by remember { mutableStateOf(Offset.Zero) }
    var panelHeightPx by remember { mutableIntStateOf(0) }
    val colors = SpendWise.colors

    BoxWithConstraints(
        modifier.fillMaxSize().zIndex(20f).onGloballyPositioned { coordinates ->
            val next = coordinates.positionInWindow()
            if (next != rootInWindow) rootInWindow = next
        },
    ) {
        val rootWidthPx = with(density) { maxWidth.roundToPx() }
        val rootHeightPx = with(density) { maxHeight.roundToPx() }
        val menuWidth = 184.dp
        val menuWidthPx = with(density) { menuWidth.roundToPx() }
        val marginPx = with(density) { Spacing.screen.roundToPx() }
        val gapPx = with(density) { Spacing.xs.roundToPx() }
        val estimatedHeightPx = with(density) { 220.dp.roundToPx() }
        val menuHeightPx = panelHeightPx.takeIf { it > 0 } ?: estimatedHeightPx
        val statusTopPx = androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(density)
        val navigationBottomPx = androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(density)
        val bottomLimit = (rootHeightPx - navigationBottomPx - marginPx).coerceAtLeast(statusTopPx + marginPx)

        AnimatedVisibility(
            visible = expanded && anchorInWindow != null,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.97f, animationSpec = tween(160)),
            exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.98f, animationSpec = tween(120)),
        ) {
            val anchor = anchorInWindow!!.translate(-rootInWindow.x, -rootInWindow.y)
            val minX = marginPx
            val maxX = (rootWidthPx - menuWidthPx - marginPx).coerceAtLeast(minX)
            val x = (anchor.right.roundToInt() - menuWidthPx).coerceIn(minX, maxX)
            val below = anchor.bottom.roundToInt() + gapPx
            val y = if (below + menuHeightPx <= bottomLimit) {
                below
            } else {
                (anchor.top.roundToInt() - menuHeightPx - gapPx).coerceAtLeast(statusTopPx + marginPx)
            }

            Box(Modifier.fillMaxSize()) {
                // Transparent dismissal target; the page remains visible through the glass.
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(onClick = onDismiss)
                        .semantics { contentDescription = "Dismiss period menu" },
                )
                Column(
                    Modifier
                        .offset { IntOffset(x, y) }
                        .width(menuWidth)
                        .onSizeChanged { panelHeightPx = it.height }
                        .liquidGlass(RoundedCornerShape(Radius.card), GlassKind.Card)
                        .clip(RoundedCornerShape(Radius.card))
                        .selectableGroup()
                        .semantics { paneTitle = "Choose period" },
                ) {
                    Spacer(Modifier.height(Spacing.xs))
                    ReportPeriod.entries.forEachIndexed { index, period ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .selectable(
                                    selected = period == selected,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(period); onDismiss() },
                                )
                                .padding(horizontal = Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (shortLabels) period.granularityLabel else period.label,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.text1,
                            )
                            if (period == selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = colors.primary)
                        }
                        if (index < ReportPeriod.entries.lastIndex) {
                            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = Spacing.md))
                        }
                    }
                    Spacer(Modifier.height(Spacing.xs))
                }
            }
        }
    }
}
