package com.spendwise.app.feature.friends

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.spendwise.app.core.merchant.Merchants
import com.spendwise.app.core.money.Money
import com.spendwise.app.core.time.Clock
import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.account.AccountRepository
import com.spendwise.app.data.db.dao.FriendDao
import com.spendwise.app.data.db.dao.OutstandingRow
import com.spendwise.app.data.db.dao.SettlementRow
import com.spendwise.app.data.db.dao.ShareRow
import com.spendwise.app.data.db.entity.AccountEntity
import com.spendwise.app.data.db.entity.AccountType
import com.spendwise.app.data.db.entity.FriendEntity
import com.spendwise.app.domain.LedgerResult
import com.spendwise.app.domain.RepaymentSuggestion
import com.spendwise.app.domain.SharedExpenseLedger
import com.spendwise.app.ui.components.ChipRow
import com.spendwise.app.ui.components.EmptyState
import com.spendwise.app.ui.components.GroupCard
import com.spendwise.app.ui.components.LargeTitleScreen
import com.spendwise.app.ui.components.LiquidPrimaryButton
import com.spendwise.app.ui.components.ListRow
import com.spendwise.app.ui.components.MoneyInput
import com.spendwise.app.ui.components.SectionLabel
import com.spendwise.app.ui.components.TextInput
import com.spendwise.app.ui.components.TxnText
import com.spendwise.app.ui.components.parseSignedRupees
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private fun day(at: Long) = TxnText.day(IndiaTime.localDate(at), IndiaTime.localDate(System.currentTimeMillis()))
private val divider = 68.dp

// ------------------------------------------------------------------------------------------------ friends list

/** One friend, with what they owe PER ACCOUNT that paid (rule #1: no total across accounts). */
data class FriendOwes(val friendId: Long, val name: String, val lines: List<OutstandingRow>)

@HiltViewModel
class FriendsViewModel @Inject constructor(friendDao: FriendDao) : ViewModel() {
    val owes: StateFlow<List<FriendOwes>> = friendDao.observeOutstanding()
        .map { rows -> rows.groupBy { it.friendId }.map { (id, lines) -> FriendOwes(id, lines.first().friendName, lines) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val friends: StateFlow<List<FriendEntity>> = friendDao.observeFriends()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun FriendsScreen(onClose: () -> Unit, onOpenFriend: (Long) -> Unit, viewModel: FriendsViewModel = hiltViewModel()) {
    val owes by viewModel.owes.collectAsStateWithLifecycle()
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val colors = SpendWise.colors
    BackHandler(onBack = onClose)
    val settled = friends.filter { f -> owes.none { it.friendId == f.id } }

    Box(Modifier.fillMaxSize()) {
        LargeTitleScreen(title = "Friends", eyebrow = "Shared bills", reserveBottomBar = false) {
            item(key = "intro") {
                Text(
                    "Paid a bill for someone? Open it and tap \"Split with a friend\". Only your part counts as your spending; " +
                        "when they pay you back it isn't counted as income.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text2,
                    modifier = Modifier.padding(horizontal = Spacing.screen + Spacing.xxs),
                )
            }
            if (owes.isEmpty() && friends.isEmpty()) {
                item(key = "empty") {
                    Spacer(Modifier.height(Spacing.md))
                    EmptyState(icon = Icons.Rounded.Group, title = "No shared bills yet", body = "Friends appear here once you split a bill with them.")
                }
            }
            if (owes.isNotEmpty()) {
                item(key = "owe-label") { SectionLabel("Owe you") }
                item(key = "owe") {
                    GroupCard {
                        owes.forEachIndexed { i, f ->
                            ListRow(
                                title = f.name,
                                // Per paying account, never one figure across accounts.
                                subtitle = f.lines.joinToString("\n") { "${Money.format(it.outstandingPaise)} from ${it.accountName} · ${it.bills} bill${if (it.bills == 1) "" else "s"} since ${day(it.oldestAt)}" },
                                leadingIcon = Icons.Rounded.Person,
                                onClick = { onOpenFriend(f.friendId) },
                            )
                            if (i < owes.lastIndex) HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = divider))
                        }
                    }
                }
            }
            if (settled.isNotEmpty()) {
                item(key = "settled-label") { SectionLabel("All settled") }
                item(key = "settled") {
                    GroupCard {
                        settled.forEachIndexed { i, f ->
                            ListRow(title = f.name, leadingIcon = Icons.Rounded.Person, onClick = { onOpenFriend(f.id) })
                            if (i < settled.lastIndex) HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = divider))
                        }
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(Spacing.xl)) }
        }
        BackButton(onClose)
    }
}

// ------------------------------------------------------------------------------------------------ one friend

@HiltViewModel(assistedFactory = FriendDetailViewModel.Factory::class)
class FriendDetailViewModel @AssistedInject constructor(
    @Assisted private val friendId: Long,
    friendDao: FriendDao,
    repository: AccountRepository,
    private val shared: SharedExpenseLedger,
    private val clock: Clock,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(friendId: Long): FriendDetailViewModel
    }

    val friend = friendDao.observeFriend(friendId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val shares = friendDao.observeSharesOfFriend(friendId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val accounts: StateFlow<List<AccountEntity>> = repository.observeAccountsWithBalances().map { l -> l.map { it.account } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val message = MutableStateFlow<String?>(null)

    fun recordRepayment(accountId: Long, paise: Long, onDone: () -> Unit) = viewModelScope.launch {
        when (val r = shared.recordRepayment(accountId, friendId, paise, clock.now())) {
            is LedgerResult.Ok -> { message.value = null; onDone() }
            is LedgerResult.Rejected -> message.value = r.reason
        }
    }

    fun forgive(shareId: Long) = viewModelScope.launch {
        (shared.forgive(shareId) as? LedgerResult.Rejected)?.let { message.value = it.reason }
    }
}

@Composable
fun FriendDetailScreen(
    friendId: Long,
    onClose: () -> Unit,
    onOpenBill: (Long) -> Unit,
    viewModel: FriendDetailViewModel = hiltViewModel<FriendDetailViewModel, FriendDetailViewModel.Factory>(key = "friend-$friendId") { it.create(friendId) },
) {
    val friend by viewModel.friend.collectAsStateWithLifecycle()
    val shares by viewModel.shares.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val colors = SpendWise.colors
    var recording by remember { mutableStateOf(false) }
    BackHandler(onBack = { if (recording) recording = false else onClose() })
    val open = shares.filter { it.openPaise > 0 }
    // Per paying account — never one figure across accounts.
    val perAccount = open.groupBy { it.accountId }.map { (_, l) -> l.first().accountName to l.sumOf { it.openPaise } }

    Box(Modifier.fillMaxSize()) {
        LargeTitleScreen(title = friend?.name.orEmpty(), eyebrow = "Friend", reserveBottomBar = false) {
            item(key = "owes") {
                GroupCard {
                    Column(Modifier.padding(Spacing.md)) {
                        if (perAccount.isEmpty()) {
                            Text("All settled", style = MaterialTheme.typography.titleMedium, color = colors.text1)
                        } else {
                            Text("Owes you", style = MaterialTheme.typography.labelMedium, color = colors.text2)
                            perAccount.forEach { (account, paise) ->
                                Text("${Money.format(paise)} · from $account", style = MaterialTheme.typography.titleMedium, color = colors.text1)
                            }
                        }
                    }
                }
            }
            if (open.isNotEmpty()) {
                item(key = "record") {
                    Spacer(Modifier.height(Spacing.md))
                    Column(Modifier.padding(horizontal = Spacing.screen)) {
                        LiquidPrimaryButton(text = "Paid back in cash", icon = Icons.Rounded.Handshake, onClick = { recording = !recording })
                        Text(
                            "Paid by UPI? It arrives as a bank SMS — open it and tap \"Paid back by ${friend?.name ?: "a friend"}\".",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.text2,
                            modifier = Modifier.padding(top = Spacing.xs, start = Spacing.xxs),
                        )
                    }
                }
                item(key = "record-panel") {
                    AnimatedVisibility(recording) {
                        RecordRepaymentPanel(
                            owedLines = perAccount.joinToString(" · ") { (account, paise) -> "${Money.format(paise)} from $account" },
                            accounts = accounts,
                            onCancel = { recording = false },
                            onSave = { accountId, paise -> viewModel.recordRepayment(accountId, paise) { recording = false } },
                        )
                    }
                }
            }
            message?.let { m ->
                item(key = "msg") { Text(m, color = colors.danger, modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.sm)) }
            }
            if (shares.isNotEmpty()) {
                item(key = "bills-label") { SectionLabel("Bills") }
                item(key = "bills") {
                    GroupCard {
                        shares.forEachIndexed { i, s ->
                            ShareLine(s, onOpen = { onOpenBill(s.txnId) }, onForgive = { viewModel.forgive(s.shareId) })
                            if (i < shares.lastIndex) HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = divider))
                        }
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(Spacing.xl)) }
        }
        BackButton(onClose)
    }
}

@Composable
private fun ShareLine(s: ShareRow, onOpen: () -> Unit, onForgive: () -> Unit) {
    val colors = SpendWise.colors
    val status = when {
        s.forgivenAt != null -> "Forgiven" + if (s.settledPaise > 0) " after ${Money.format(s.settledPaise)}" else ""
        s.openPaise == 0L -> "Paid back"
        s.settledPaise > 0 -> "${Money.format(s.settledPaise)} paid back · ${Money.format(s.openPaise)} to go"
        else -> "Not paid back yet"
    }
    ListRow(
        title = "${Money.format(s.amountPaise)} of ${s.billMerchant?.let(Merchants::display) ?: "a bill"}",
        subtitle = "${day(s.billAt)} · ${s.accountName} · $status",
        leadingIcon = Icons.Rounded.Handshake,
        leadingTint = if (s.openPaise > 0) colors.pending else colors.text2,
        onClick = onOpen,
    ) {
        if (s.openPaise > 0) TextButton(onClick = onForgive) { Text("Forgive", color = colors.text2) }
    }
}

@Composable
private fun RecordRepaymentPanel(owedLines: String, accounts: List<AccountEntity>, onCancel: () -> Unit, onSave: (Long, Long) -> Unit) {
    val colors = SpendWise.colors
    var amount by remember { mutableStateOf("") }
    var account by remember { mutableStateOf(accounts.firstOrNull { it.type == AccountType.CASH } ?: accounts.firstOrNull()) }
    var error by remember { mutableStateOf<String?>(null) }
    Spacer(Modifier.height(Spacing.md))
    GroupCard {
        Column(Modifier.padding(vertical = Spacing.md)) {
            Text("Where did the money go?", style = MaterialTheme.typography.titleSmall, color = colors.text1, modifier = Modifier.padding(horizontal = Spacing.md))
            Spacer(Modifier.height(Spacing.xs))
            ChipRow(options = accounts, selected = account, label = { it.nickname }, onSelect = { account = it })
            Spacer(Modifier.height(Spacing.sm))
            MoneyInput(amount, { amount = it; error = null }, "Amount", error = error, supporting = "Owes $owedLines", modifier = Modifier.padding(horizontal = Spacing.md))
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel", color = colors.text2) }
                TextButton(onClick = {
                    val p = parseSignedRupees(amount)?.takeIf { it > 0 }
                    val a = account
                    when {
                        p == null -> error = "Enter the amount they paid"
                        a == null -> error = "Choose an account"
                        else -> onSave(a.id, p)
                    }
                }) { Text("Save", color = colors.primary) }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ on a transaction

/**
 * On a confirmed bill: who owes part of it, and "Split with a friend". Shown on the transaction detail screen.
 * Only the user's own part counts as their spending (ADR-011).
 */
@Composable
fun BillSharesSection(
    billPaise: Long,
    shares: List<ShareRow>,
    friends: List<FriendEntity>,
    roomPaise: Long,
    onShare: (friendName: String, paise: Long) -> Unit,
    onRemove: (Long) -> Unit,
    onForgive: (Long) -> Unit,
    onOpenFriend: (Long) -> Unit,
) {
    val colors = SpendWise.colors
    var adding by remember { mutableStateOf(false) }
    val sharedTotal = shares.sumOf { it.amountPaise }
    SectionLabel(if (shares.isEmpty()) "Paid for friends too?" else "Shared with friends · your part ${Money.format(-billPaise - sharedTotal)}")
    GroupCard {
        shares.forEach { s ->
            val status = when {
                s.forgivenAt != null -> "Forgiven"
                s.openPaise == 0L -> "Paid back"
                s.settledPaise > 0 -> "${Money.format(s.openPaise)} still to come"
                else -> "Not paid back yet"
            }
            ListRow(
                title = "${s.friendName} · ${Money.format(s.amountPaise)}",
                subtitle = status,
                leadingIcon = Icons.Rounded.Person,
                leadingTint = if (s.openPaise > 0) colors.pending else colors.text2,
                onClick = { onOpenFriend(s.friendId) },
            ) {
                when {
                    s.settledPaise == 0L && s.forgivenAt == null -> TextButton(onClick = { onRemove(s.shareId) }) { Text("Remove", color = colors.text2) }
                    s.openPaise > 0 -> TextButton(onClick = { onForgive(s.shareId) }) { Text("Forgive", color = colors.text2) }
                }
            }
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = divider))
        }
        if (roomPaise > 0) {
            ListRow(
                title = "Split with a friend",
                subtitle = "Only your part counts as your spending",
                leadingIcon = Icons.Rounded.PersonAdd,
                onClick = { adding = !adding },
            )
        }
    }
    AnimatedVisibility(adding) {
        SharePanel(
            defaultPaise = if (shares.isEmpty()) (-billPaise) / 2 else roomPaise,
            maxPaise = roomPaise,
            friends = friends,
            onCancel = { adding = false },
            onSave = { name, paise -> onShare(name, paise); adding = false },
        )
    }
}

@Composable
private fun SharePanel(defaultPaise: Long, maxPaise: Long, friends: List<FriendEntity>, onCancel: () -> Unit, onSave: (String, Long) -> Unit) {
    val colors = SpendWise.colors
    var name by remember { mutableStateOf(friends.firstOrNull()?.name.orEmpty()) }
    var amount by remember { mutableStateOf(Money.format(defaultPaise.coerceAtMost(maxPaise), Money.SignStyle.None).removePrefix("₹")) }
    var error by remember { mutableStateOf<String?>(null) }
    Spacer(Modifier.height(Spacing.md))
    GroupCard {
        Column(Modifier.padding(vertical = Spacing.md)) {
            Text("Their part of the bill", style = MaterialTheme.typography.titleSmall, color = colors.text1, modifier = Modifier.padding(horizontal = Spacing.md))
            if (friends.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xs))
                ChipRow(options = friends.take(8), selected = friends.firstOrNull { it.name.equals(name, ignoreCase = true) }, label = { it.name }, onSelect = { name = it.name })
            }
            Spacer(Modifier.height(Spacing.xs))
            Column(Modifier.padding(horizontal = Spacing.md)) {
                TextInput(name, { name = it; error = null }, "Friend's name")
                Spacer(Modifier.height(Spacing.xs))
                MoneyInput(amount, { amount = it; error = null }, "Their part", error = error, supporting = "Up to ${Money.format(maxPaise)}")
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel", color = colors.text2) }
                TextButton(onClick = {
                    val p = parseSignedRupees(amount)?.takeIf { it in 1..maxPaise }
                    when {
                        name.isBlank() -> error = "Enter your friend's name"
                        p == null -> error = "Enter up to ${Money.format(maxPaise)}"
                        else -> onSave(name, p)
                    }
                }) { Text("Save", color = colors.primary) }
            }
        }
    }
}

/** On money coming in: "Paid back by Ravi?" — suggestions plus any friend who owes something. Never automatic. */
@Composable
fun RepaymentPrompt(
    suggestions: List<RepaymentSuggestion>,
    owingFriends: List<Pair<Long, String>>,
    onMark: (Long) -> Unit,
) {
    val colors = SpendWise.colors
    val others = owingFriends.filter { (id, _) -> suggestions.none { it.friendId == id } }
    if (suggestions.isEmpty() && others.isEmpty()) return
    SectionLabel("Is this a friend paying you back?")
    GroupCard {
        suggestions.forEach { s ->
            ListRow(title = "Paid back by ${s.friendName}", subtitle = s.reason, leadingIcon = Icons.Rounded.Handshake, onClick = { onMark(s.friendId) }) {
                Text("Yes", color = colors.primary, style = MaterialTheme.typography.labelLarge)
            }
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = divider))
        }
        if (others.isNotEmpty()) {
            Column(Modifier.padding(vertical = Spacing.sm)) {
                Text(
                    if (suggestions.isEmpty()) "Pick who paid — then it won't count as income" else "Someone else?",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.text2,
                    modifier = Modifier.padding(horizontal = Spacing.md),
                )
                Spacer(Modifier.height(Spacing.xs))
                ChipRow(options = others, selected = null, label = { it.second }, onSelect = { onMark(it.first) })
            }
        }
    }
}

/** On a repayment: which bills it paid, and "Not a repayment" to undo. */
@Composable
fun RepaymentDetails(settlements: List<SettlementRow>, fromSms: Boolean, onOpenBill: (Long) -> Unit, onUndo: () -> Unit) {
    val colors = SpendWise.colors
    SectionLabel("Paid back for")
    GroupCard {
        settlements.forEach { x ->
            ListRow(
                title = "${Money.format(x.amountPaise)} · ${x.billMerchant?.let(Merchants::display) ?: "a bill"}",
                subtitle = "${x.friendName} · ${day(x.billAt)} · ${x.billAccount}",
                leadingIcon = Icons.Rounded.Handshake,
                onClick = { onOpenBill(x.billTxnId) },
            )
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = divider))
        }
        if (fromSms) {
            ListRow(title = "Not a repayment", subtitle = "Send it back to review", leadingIcon = Icons.Rounded.Handshake, onClick = onUndo)
        }
    }
}

@Composable
private fun BackButton(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = Spacing.xs)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = SpendWise.colors.text1)
        }
    }
}
