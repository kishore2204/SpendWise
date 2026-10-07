package com.spendwise.app.feature.accounts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.ui.graphics.vector.ImageVector
import com.spendwise.app.data.db.entity.AccountEntity
import com.spendwise.app.data.db.entity.AccountType

/** Display helpers shared by Home, Accounts and the switcher. */
object AccountVisuals {
    fun icon(type: AccountType): ImageVector = when (type) {
        AccountType.SAVINGS -> Icons.Rounded.Savings
        AccountType.CURRENT -> Icons.Rounded.AccountBalance
        AccountType.CREDIT_CARD -> Icons.Rounded.CreditCard
        AccountType.CASH -> Icons.Rounded.Payments
        AccountType.OTHER -> Icons.Rounded.Wallet
    }

    fun typeLabel(type: AccountType): String = when (type) {
        AccountType.SAVINGS -> "Savings"
        AccountType.CURRENT -> "Current"
        AccountType.CREDIT_CARD -> "Credit card"
        AccountType.CASH -> "Cash"
        AccountType.OTHER -> "Other"
    }

    /** "HDFC ••••4521" — the switcher label. Cash has no digits. */
    fun short(account: AccountEntity): String =
        if (account.last4.isEmpty()) account.nickname else "${account.nickname} ••••${account.last4}"

    /** "Indian Bank ••••8625 · Savings" — secondary line. */
    fun detail(account: AccountEntity): String = buildString {
        append(account.bankName)
        if (account.last4.isNotEmpty()) append(" ••••").append(account.last4)
        append(" · ").append(typeLabel(account.type))
    }
}

/** Banks offered when adding an account. Codes are the DLT sender tokens used by SMS detection (ADR-005). */
enum class BankPreset(val code: String, val displayName: String) {
    IndianBank("INDBNK", "Indian Bank"),
    Hdfc("HDFCBK", "HDFC Bank"),
    Cash("CASH", "Cash"),
    Other("", "Other"),
}
