package com.spendwise.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.spendwise.app.ui.glass.liveGlass
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing

data class BottomBarItem<K>(
    val key: K,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

/**
 * Floating glass navigation pill: the one live-blurred surface on every top-level screen (ADR-007).
 * Layout: two tabs · primary "+ Expense" · two tabs — bottom-weighted for one-handed reach.
 */
@Composable
fun <K> GlassBottomBar(
    items: List<BottomBarItem<K>>,
    selected: K,
    onSelect: (K) -> Unit,
    onPrimaryAction: () -> Unit,
    primaryActionLabel: String,
    modifier: Modifier = Modifier,
) {
    require(items.size == 4) { "Bottom bar is designed for exactly four destinations" }
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.sm)
            .height(64.dp)
            .liveGlass(CircleShape)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        items.take(2).forEach { item ->
            NavItem(item, item.key == selected, Modifier.weight(1f)) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onSelect(item.key)
            }
        }
        PrimaryActionButton(primaryActionLabel) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onPrimaryAction()
        }
        items.drop(2).forEach { item ->
            NavItem(item, item.key == selected, Modifier.weight(1f)) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onSelect(item.key)
            }
        }
    }
}

@Composable
private fun <K> NavItem(item: BottomBarItem<K>, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = SpendWise.colors
    val motion = SpendWise.motion
    val tint by animateColorAsState(
        if (selected) colors.primary else colors.text2,
        tween(motion.duration(motion.short)),
        label = "navTint",
    )
    Column(
        modifier
            .fillMaxHeight()
            .clip(CircleShape)
            .selectable(
                selected = selected,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                onClick = onClick,
            )
            // The single glow in the app (ADR-007): a soft light behind the selected destination.
            .drawBehind {
                if (selected) {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(colors.primary.copy(alpha = if (colors.isDark) 0.22f else 0.14f), colors.primary.copy(alpha = 0f)),
                            center = Offset(size.width / 2, size.height * 0.38f),
                            radius = size.minDimension * 0.75f,
                        ),
                        radius = size.minDimension * 0.75f,
                        center = Offset(size.width / 2, size.height * 0.38f),
                    )
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Text(item.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

@Composable
private fun PrimaryActionButton(label: String, onClick: () -> Unit) {
    val colors = SpendWise.colors
    Box(
        Modifier
            .padding(horizontal = 6.dp)
            .size(48.dp)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(colors.primary, colors.primary.copy(alpha = 0.85f))))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(26.dp))
    }
}
