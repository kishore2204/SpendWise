package com.spendwise.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.spendwise.app.ui.glass.LocalBackgroundBackdrop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.spendwise.app.core.money.Money
import com.spendwise.app.ui.glass.GlassKind
import com.spendwise.app.ui.glass.liquidGlass
import com.kyant.shapes.RoundedRectangle
import com.spendwise.app.ui.theme.Radius
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.financialFigures
import com.spendwise.app.ui.theme.Spacing

/** Grouped container: solid surface with a glass edge (ADR-009), continuous 20dp corners. Never glass-in-glass. */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen)
            .liquidGlass(RoundedCornerShape(Radius.group), GlassKind.Card)
            .clip(RoundedCornerShape(Radius.group)),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.let { if ('₹' in text) it.financialFigures() else it },
        color = SpendWise.colors.text2,
        modifier = modifier.padding(start = Spacing.screen + Spacing.xxs, end = Spacing.screen, top = Spacing.lg, bottom = Spacing.xs),
    )
}

@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    leadingTint: Color = SpendWise.colors.text2,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            IconBadge(leadingIcon, leadingTint)
            Spacer(Modifier.width(Spacing.sm))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = SpendWise.colors.text1)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SpendWise.colors.text2)
            }
        }
        trailing()
    }
}

/**
 * 40dp monochrome badge (ADR-009): neutral fill, glyph in [tint] (text2 by default). Colour is reserved for
 * meaning — the accent, income and expense — never decoration.
 */
@Composable
fun IconBadge(icon: ImageVector, tint: Color = SpendWise.colors.text2, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).clip(RoundedRectangle(Radius.sm)).background(SpendWise.colors.badge),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen)
            .liquidGlass(RoundedCornerShape(Radius.card), GlassKind.Card)
            .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon)
        Spacer(Modifier.height(Spacing.md))
        Text(title, style = MaterialTheme.typography.titleMedium, color = SpendWise.colors.text1, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.xs))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = SpendWise.colors.text2, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(Spacing.lg))
            action()
        }
    }
}

/** Segmented single choice (theme picker etc.). */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SpendWise.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(colors.surface3)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .then(
                        if (isSelected) {
                            Modifier.shadow(3.dp, CircleShape, ambientColor = colors.shadow, spotColor = colors.shadow)
                        } else {
                            Modifier
                        },
                    )
                    .clip(CircleShape)
                    .background(if (isSelected) colors.surface2 else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = if (isSelected) colors.text1 else colors.text2,
                )
            }
        }
    }
}

/**
 * Money with a slightly de-emphasised but readable ₹ and paise; leave a thin visual gap after the symbol.
 */
@Composable
fun MoneyText(
    paise: Long,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = SpendWise.colors.text1,
    sign: Money.SignStyle = Money.SignStyle.NegativeOnly,
) {
    val formatted = Money.format(paise, sign)
    val secondary = SpendWise.colors.text2
    val text = buildAnnotatedString {
        val rupeeIndex = formatted.indexOf('₹')
        val dotIndex = formatted.indexOf('.')
        append(formatted.substring(0, rupeeIndex))
        withStyle(SpanStyle(fontSize = 1.em)) { append('₹') }
        val wholeEnd = if (dotIndex >= 0) dotIndex else formatted.length
        val amountStart = if (formatted.getOrNull(rupeeIndex + 1) == '\u2009') rupeeIndex + 2 else rupeeIndex + 1
        if (amountStart == rupeeIndex + 1) append('\u2009')
        append(formatted.substring(amountStart, wholeEnd))
        if (dotIndex >= 0) {
            withStyle(SpanStyle(fontSize = 0.7.em, color = secondary)) { append(formatted.substring(dotIndex)) }
        }
    }
    Text(text, style = style, color = color, modifier = modifier, maxLines = 1)
}

/**
 * Primary action as light-lavender liquid glass — the same treatment as the floating "+" button.
 * Lives inside content, so it refracts the background pattern (never itself).
 */
@Composable
fun LiquidPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val colors = SpendWise.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 1.03f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "press")
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .liquidGlass(
                shape = CircleShape,
                kind = GlassKind.Control,
                tint = colors.accentGlass,
                backdropOverride = LocalBackgroundBackdrop.current,
                lensHeight = 12f,
                lensAmount = 18f,
                layerBlock = {
                    scaleX = scale
                    scaleY = scale
                },
            )
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.onAccentGlass, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = colors.onAccentGlass,
        )
    }
}
