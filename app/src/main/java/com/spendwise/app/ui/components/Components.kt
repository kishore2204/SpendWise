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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.spendwise.app.core.money.Money
import com.spendwise.app.ui.glass.liquidGlass
import com.kyant.shapes.RoundedRectangle
import com.spendwise.app.ui.theme.Radius
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing

/** Grouped container: card-kind liquid glass (more tint, so dense text stays crisp), continuous 20dp corners. */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen)
            .liquidGlass(RoundedRectangle(Radius.group))
            .clip(RoundedRectangle(Radius.group)),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
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
    leadingTint: Color = SpendWise.colors.primary,
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

/** 40dp rounded container: hue at 16% with the glyph at full strength (ADR-007 iconography). */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).clip(RoundedCornerShape(Radius.sm)).background(tint.copy(alpha = 0.16f)),
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
            .liquidGlass()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon, SpendWise.colors.primary)
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
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(SpendWise.colors.surface2)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (isSelected) SpendWise.colors.primaryContainer else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) SpendWise.colors.onPrimaryContainer else SpendWise.colors.text2,
                )
            }
        }
    }
}

/**
 * Money with ₹ and paise de-emphasised (ADR-007): "₹28,450.75" renders ₹ at 60% and ".75" at 55% in text 2.
 * Always uses tabular figures through the money styles.
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
        withStyle(SpanStyle(fontSize = 0.6.em, color = secondary)) { append('₹') }
        val wholeEnd = if (dotIndex >= 0) dotIndex else formatted.length
        append(formatted.substring(rupeeIndex + 1, wholeEnd))
        if (dotIndex >= 0) {
            withStyle(SpanStyle(fontSize = 0.55.em, color = secondary)) { append(formatted.substring(dotIndex)) }
        }
    }
    Text(text, style = style, color = color, modifier = modifier, maxLines = 1)
}
