package com.spendwise.app.ui.components

import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Checkroom
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.LocalGroceryStore
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.Redeem
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spendwise.app.core.merchant.Merchants
import com.spendwise.app.core.money.Money
import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.db.dao.TxnRow
import com.spendwise.app.data.db.entity.PaymentMethod
import com.spendwise.app.data.db.entity.TxnKind
import com.spendwise.app.data.db.entity.TxnStatus
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Category icon keys (seeded in DatabaseSetup) → Material Symbols Rounded. */
object CategoryIcons {
    fun of(key: String?): ImageVector = when (key) {
        "restaurant" -> Icons.Rounded.Restaurant
        "local_grocery_store" -> Icons.Rounded.LocalGroceryStore
        "shopping_bag" -> Icons.Rounded.ShoppingBag
        "receipt_long" -> Icons.AutoMirrored.Rounded.ReceiptLong
        "directions_car" -> Icons.Rounded.DirectionsCar
        "local_gas_station" -> Icons.Rounded.LocalGasStation
        "movie" -> Icons.Rounded.Movie
        "autorenew" -> Icons.Rounded.Autorenew
        "school" -> Icons.Rounded.School
        "favorite" -> Icons.Rounded.Favorite
        "flight" -> Icons.Rounded.Flight
        "home" -> Icons.Rounded.Home
        "spa" -> Icons.Rounded.Spa
        "apartment" -> Icons.Rounded.Apartment
        "account_balance" -> Icons.Rounded.AccountBalance
        "shield" -> Icons.Rounded.Shield
        "redeem" -> Icons.Rounded.Redeem
        "more_horiz" -> Icons.Rounded.MoreHoriz
        "work" -> Icons.Rounded.Work
        "percent" -> Icons.Rounded.Percent
        "card_giftcard" -> Icons.Rounded.CardGiftcard
        "add_circle" -> Icons.Rounded.AddCircle
        "swap_horiz" -> Icons.Rounded.SwapHoriz
        "undo" -> Icons.AutoMirrored.Rounded.Undo
        "tune" -> Icons.Rounded.Tune
        "handshake" -> Icons.Rounded.Handshake
        "label" -> Icons.AutoMirrored.Rounded.Label
        "account_balance_wallet" -> Icons.Rounded.AccountBalanceWallet
        "checkroom" -> Icons.Rounded.Checkroom
        "directions_walk" -> Icons.AutoMirrored.Rounded.DirectionsWalk
        "devices" -> Icons.Rounded.Devices
        "fitness_center" -> Icons.Rounded.FitnessCenter
        "diamond" -> Icons.Rounded.Diamond
        "trending_up" -> Icons.AutoMirrored.Rounded.TrendingUp
        else -> Icons.Rounded.HelpOutline
    }
}

object TxnText {
    private val TIME = DateTimeFormatter.ofPattern("h:mm a")
    private val DAY = DateTimeFormatter.ofPattern("EEE, d MMM")

    fun title(row: TxnRow): String = when (row.kind) {
        TxnKind.TRANSFER_OUT -> "To ${row.counterpartyAccount ?: row.merchantRaw ?: "account"}"
        TxnKind.TRANSFER_IN -> "From ${row.counterpartyAccount ?: row.merchantRaw ?: "account"}"
        TxnKind.ATM_WITHDRAWAL -> "Cash withdrawal"
        TxnKind.CASH_DEPOSIT -> "Cash deposit"
        TxnKind.REFUND -> "Refund · ${row.merchantRaw?.let(Merchants::display) ?: row.categoryName ?: ""}".trimEnd(' ', '·')
        TxnKind.REPAYMENT -> "Paid back · ${row.merchantRaw?.let(Merchants::display) ?: "friend"}"
        else -> row.merchantRaw?.let(Merchants::display) ?: row.categoryName ?: kindLabel(row.kind)
    }

    fun kindLabel(kind: TxnKind): String = when (kind) {
        TxnKind.EXPENSE -> "Expense"
        TxnKind.INCOME -> "Income"
        TxnKind.TRANSFER_OUT, TxnKind.TRANSFER_IN -> "Transfer"
        TxnKind.ATM_WITHDRAWAL -> "Cash withdrawal"
        TxnKind.CASH_DEPOSIT -> "Cash deposit"
        TxnKind.REFUND -> "Refund"
        TxnKind.FEE -> "Fee"
        TxnKind.INTEREST -> "Interest"
        TxnKind.REVERSAL -> "Reversal"
        TxnKind.UNKNOWN -> "Unknown"
        TxnKind.REPAYMENT -> "Paid back"
    }

    fun method(m: PaymentMethod): String? = when (m) {
        PaymentMethod.UPI -> "UPI"
        PaymentMethod.CARD -> "Card"
        PaymentMethod.NETBANKING -> "Net banking"
        PaymentMethod.NEFT -> "NEFT"
        PaymentMethod.IMPS -> "IMPS"
        PaymentMethod.RTGS -> "RTGS"
        PaymentMethod.ATM -> "ATM"
        PaymentMethod.CASH -> "Cash"
        PaymentMethod.CHEQUE -> "Cheque"
        PaymentMethod.AUTO_DEBIT -> "Auto-debit"
        PaymentMethod.OTHER, PaymentMethod.UNKNOWN -> null
    }

    fun time(millis: Long): String = TIME.format(java.time.Instant.ofEpochMilli(millis).atZone(IndiaTime.ZONE))

    fun day(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DAY.format(date)
    }
}

/**
 * One transaction row. Rows are never glass (ADR-011 audit): they sit on the solid row surface supplied by the
 * caller. Money: income/refund tinted with "+", spending in text1 with "−", transfers neutral.
 */
@Composable
fun TransactionRowView(row: TxnRow, onClick: () -> Unit, modifier: Modifier = Modifier, showDate: Boolean = false) {
    val colors = SpendWise.colors
    val isTransfer = row.kind.isTransferFamily
    val icon = if (isTransfer) Icons.Rounded.SwapHoriz else CategoryIcons.of(row.categoryIcon)
    // Green = money in, red = money out (user choice): the colour says which way the money moved; the subtitle
    // says what it counts as (Transfer, Paid back…).
    val amountColor = if (row.amountPaise > 0) colors.income else colors.expense
    val subtitle = buildList {
        when {
            row.hasSplits -> add("Split")
            isTransfer -> add("Transfer")
            else -> row.categoryName?.let(::add) ?: add("Uncategorised")
        }
        add(if (showDate) TxnText.day(IndiaTime.localDate(row.occurredAt), IndiaTime.localDate(System.currentTimeMillis())) else TxnText.time(row.occurredAt))
        TxnText.method(row.paymentMethod)?.let(::add)
    }.joinToString(" · ")

    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon)
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(TxnText.title(row), style = MaterialTheme.typography.bodyLarge, color = colors.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.status == TxnStatus.PENDING) {
                    Text(
                        "Review",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.pending,
                        modifier = Modifier.border(1.dp, colors.pending, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(Spacing.xs))
        Text(
            Money.format(row.amountPaise, Money.SignStyle.Always),
            style = SpendWise.money.row,
            color = amountColor,
        )
    }
}

/** Single-choice pill chips. Selection = raised surface + outline + SemiBold (≥ 3:1 non-colour cue). */
@Composable
fun <T> ChipRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector?)? = null,
    scrollable: Boolean = true,
) {
    val content: @Composable () -> Unit = {
        options.forEach { option -> Chip(label(option), icon?.invoke(option), option == selected) { onSelect(option) } }
    }
    if (scrollable) {
        Row(
            modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) { content() }
    } else {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier.fillMaxWidth().padding(horizontal = Spacing.screen),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) { content() }
    }
}

@Composable
fun Chip(text: String, icon: ImageVector?, selected: Boolean, onClick: () -> Unit) {
    val colors = SpendWise.colors
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (selected) colors.primaryContainer else colors.surface3.copy(alpha = 0.7f))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) colors.primary else colors.glassHairline, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (selected) colors.primary else colors.text2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (selected) colors.text1 else colors.text2,
            maxLines = 1,
        )
    }
}

/** Solid row surface for list segments (never glass): rounded only at the ends of a group. */
fun rowSegmentShape(first: Boolean, last: Boolean) = androidx.compose.foundation.shape.RoundedCornerShape(
    topStart = if (first) 20.dp else 0.dp, topEnd = if (first) 20.dp else 0.dp,
    bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp,
)

