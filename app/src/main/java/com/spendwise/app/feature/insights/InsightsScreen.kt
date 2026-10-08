package com.spendwise.app.feature.insights

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.ColumnCartesianLayerModel
import com.spendwise.app.domain.analytics.ReportDateRange
import com.spendwise.app.domain.analytics.ReportPeriod
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.spendwise.app.core.merchant.Merchants
import com.spendwise.app.core.money.Money
import com.spendwise.app.core.time.Clock
import com.spendwise.app.core.time.IndiaTime
import com.spendwise.app.data.account.AccountRepository
import com.spendwise.app.data.account.SelectedAccountStore
import com.spendwise.app.data.db.DatabaseSetup
import com.spendwise.app.data.db.entity.AccountEntity
import com.spendwise.app.data.db.entity.Cadence
import com.spendwise.app.data.db.entity.CategoryKind
import com.spendwise.app.domain.LedgerResult
import com.spendwise.app.feature.accounts.AccountVisuals
import com.spendwise.app.domain.analytics.AnalyticsRepository
import com.spendwise.app.domain.analytics.BudgetProgress
import com.spendwise.app.domain.analytics.BudgetState
import com.spendwise.app.domain.analytics.MonthAnalytics
import com.spendwise.app.domain.analytics.RecurringSuggestion
import com.spendwise.app.ui.components.CategoryIcons
import com.spendwise.app.ui.components.Chip
import com.spendwise.app.ui.components.ChipRow
import com.spendwise.app.ui.components.EmptyState
import com.spendwise.app.ui.components.AccountContextPill
import com.spendwise.app.ui.components.GroupCard
import com.spendwise.app.ui.components.LargeTitleScreen
import com.spendwise.app.ui.components.ListRow
import com.spendwise.app.ui.components.MoneyInput
import com.spendwise.app.ui.components.MoneyText
import com.spendwise.app.ui.components.SectionLabel
import com.spendwise.app.ui.components.LiquidPeriodPickerOverlay
import com.spendwise.app.ui.components.LiquidPeriodPickerTrigger
import com.spendwise.app.ui.components.parseSignedRupees
import com.spendwise.app.ui.glass.GlassKind
import com.spendwise.app.ui.glass.liquidGlass
import com.spendwise.app.ui.theme.Radius
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class InsightsUiState(
    val initialized: Boolean = false,
    val accounts: List<AccountEntity> = emptyList(),
    val selectedAccountId: Long? = null,
    val period: ReportPeriod = ReportPeriod.MONTH,
    val offset: Int = 0,
    val range: ReportDateRange,
    val report: MonthAnalytics? = null,
    val loading: Boolean = true,
)

private data class InsightsRequest(
    val accounts: List<AccountEntity>,
    val selectedAccountId: Long?,
    val today: LocalDate,
    val period: ReportPeriod,
    val offset: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val selected: SelectedAccountStore,
    accounts: AccountRepository,
    private val analytics: AnalyticsRepository,
    private val clock: Clock,
) : ViewModel() {
    private val _today = MutableStateFlow(ReportPeriod.today(clock.now()))
    private val _period = MutableStateFlow(ReportPeriod.MONTH)
    private val _offset = MutableStateFlow(0)
    val period: StateFlow<ReportPeriod> = _period
    val offset: StateFlow<Int> = _offset
    val selectedId: StateFlow<Long?> = selected.selectedAccountId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The controls and report are published as one keyed state. The first emission for a new key is Loading,
     * so an initial empty-account state or a previous report can never flash before the current Room result.
     */
    val uiState: StateFlow<InsightsUiState> = combine(
        accounts.observeAccountsWithBalances().map { rows -> rows.map { it.account } },
        selected.selectedAccountId,
        _today,
        _period,
        _offset,
    ) { accountRows, selectedAccountId, today, selectedPeriod, selectedOffset ->
        InsightsRequest(accountRows, selectedAccountId, today, selectedPeriod, selectedOffset)
    }.flatMapLatest { request ->
        val range = request.period.range(request.today, request.offset)
        val base = InsightsUiState(
            initialized = true,
            accounts = request.accounts,
            selectedAccountId = request.selectedAccountId,
            period = request.period,
            offset = request.offset,
            range = range,
            loading = true,
        )
        when {
            request.accounts.isEmpty() -> flowOf(base.copy(loading = false))
            request.selectedAccountId == null || request.accounts.none { it.id == request.selectedAccountId } -> flowOf(base)
            else -> kotlinx.coroutines.flow.flow {
                emit(base)
                analytics.observeRange(request.selectedAccountId, range, request.period).collect { report ->
                    val matches = report.accountId == request.selectedAccountId &&
                        report.rangeStart == range.start && report.rangeEnd == range.endInclusive &&
                        report.period == request.period
                    emit(if (matches) base.copy(report = report, loading = false) else base)
                }
            }
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        InsightsUiState(range = ReportPeriod.MONTH.range(_today.value)),
    )

    /** (accountId, text): shown only while that account is on screen. */
    val message = MutableStateFlow<Pair<Long, String>?>(null)

    fun select(accountId: Long) = viewModelScope.launch { message.value = null; selected.select(accountId) }
    fun selectPeriod(value: ReportPeriod) { message.value = null; _period.value = value; _offset.value = 0 }
    fun previousPeriod() { message.value = null; _offset.value -= 1 }
    fun nextPeriod() { if (_offset.value < 0) { message.value = null; _offset.value += 1 } }
    fun refreshToday() { _today.value = ReportPeriod.today(clock.now()) }

    /**
     * Writes use the account and month the user was LOOKING at (passed from the drawn data), and are refused if the
     * selection changed in between — never "whatever is loaded at tap time" (rule #1).
     */
    private fun stillShowing(accountId: Long): Boolean = uiState.value.let { state ->
        !state.loading && state.selectedAccountId == accountId && state.report?.accountId == accountId
    }

    fun setBudget(accountId: Long, month: YearMonth, categoryId: Long, paise: Long) = viewModelScope.launch {
        if (!stillShowing(accountId)) return@launch
        (analytics.setBudget(accountId, categoryId, paise, month) as? LedgerResult.Rejected)?.let { message.value = accountId to it.reason }
    }

    fun track(accountId: Long, s: RecurringSuggestion) = viewModelScope.launch {
        if (!stillShowing(accountId)) return@launch
        (analytics.track(accountId, s) as? LedgerResult.Rejected)?.let { message.value = accountId to it.reason }
    }

    fun stopTracking(accountId: Long, ruleId: Long) = viewModelScope.launch {
        if (!stillShowing(accountId)) return@launch
        analytics.stopTracking(accountId, ruleId)
    }

    fun today(): LocalDate = IndiaTime.localDate(clock.now())
}

private val SHORT_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM")
private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

@Composable
fun InsightsScreen(viewModel: InsightsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val accounts = state.accounts
    val selectedId = state.selectedAccountId
    val period = state.period
    val offset = state.offset
    val range = state.range
    val data = state.report
    val message by viewModel.message.collectAsStateWithLifecycle()
    val colors = SpendWise.colors
    val account = accounts.firstOrNull { it.id == selectedId }
    var periodPickerOpen by rememberSaveable { mutableStateOf(false) }
    var periodPickerAnchor by remember { mutableStateOf<Rect?>(null) }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshToday()
        onPauseOrDispose { }
    }

    Box(Modifier.fillMaxSize()) {
    LargeTitleScreen(title = "Insights", titleBottomSpacing = Spacing.xs) {
        if (!state.initialized) {
            item(key = "loading-account-context") { InsightsContextPlaceholder() }
        } else if (accounts.isEmpty()) {
            item(key = "empty") {
                EmptyState(icon = Icons.Rounded.Insights, title = "Nothing to analyse yet", body = "Insights appear once an account has transactions — always for one account at a time.")
            }
            return@LargeTitleScreen
        } else if (accounts.size > 1) {
            item(key = "accounts") {
                val dupes = accounts.groupBy { it.nickname }.filter { it.value.size > 1 }.keys
                ChipRow(
                    options = accounts,
                    selected = account,
                    label = { if (it.nickname in dupes && it.last4.isNotEmpty()) "${it.nickname} ••${it.last4}" else it.nickname },
                    onSelect = { viewModel.select(it.id) },
                )
                Spacer(Modifier.height(Spacing.sm))
            }
        } else {
            account?.let { current ->
                item(key = "account-context") {
                    AccountContextPill(AccountVisuals.short(current))
                }
            }
        }
        item(key = "period-picker") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.screen),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Period", style = MaterialTheme.typography.labelMedium, color = colors.text2, modifier = Modifier.weight(1f))
                PeriodPicker(
                    selected = period,
                    expanded = periodPickerOpen,
                    onToggle = { periodPickerOpen = !periodPickerOpen },
                    onAnchorChanged = { periodPickerAnchor = it },
                )
            }
        }
        item(key = "period-range") {
            MonthBar(period.label(range), canGoNext = offset < 0, onPrev = viewModel::previousPeriod, onNext = viewModel::nextPeriod)
        }
        // Never draw one account's numbers under another account's name (ADR-003 stamp).
        val a = data
        if (!state.initialized || state.loading || a == null || a.accountId != selectedId || a.rangeStart != range.start || a.rangeEnd != range.endInclusive || a.period != period) {
            item(key = "report-pending") { InsightsReportPlaceholder() }
            return@LargeTitleScreen
        }

        item(key = "summary") { SummaryCard(a) }

        if (a.insights.isNotEmpty()) {
            item(key = "insights-label") { SectionLabel("What stands out") }
            item(key = "insights") {
                GroupCard {
                    a.insights.forEachIndexed { i, ins ->
                        ListRow(title = ins.title, subtitle = ins.body, leadingIcon = Icons.Rounded.Lightbulb)
                        if (i < a.insights.lastIndex) HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                    }
                }
            }
        }

        if (a.spendByCategory.isNotEmpty()) {
            item(key = "cats-label") { SectionLabel("Where it went") }
            item(key = "cats") { CategoryCard(a) }
        }

        if (a.spentPaise > 0) {
            item(key = "daily-label") { SectionLabel(if (period == ReportPeriod.YEAR) "Month by month" else "Day by day") }
            item(key = "daily") {
                ChartCard {
                    ColumnChart(
                        series = listOf(if (period == ReportPeriod.YEAR) a.trend.map { it.second.coerceAtLeast(0) / 100 } else a.daily.map { it.coerceAtLeast(0) / 100 }),
                        colors = listOf(colors.expense),
                        xLabel = { x ->
                            if (period == ReportPeriod.YEAR) a.trend.getOrNull(x)?.first?.format(SHORT_MONTH).orEmpty()
                            else if (period == ReportPeriod.DAY) range.start.format(DateTimeFormatter.ofPattern("d MMM"))
                            else range.start.plusDays(x.toLong()).let { if (period == ReportPeriod.WEEK) it.dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::uppercase) else it.dayOfMonth.toString() }
                        },
                        labelEvery = when (period) { ReportPeriod.DAY, ReportPeriod.WEEK -> 1; ReportPeriod.MONTH -> 5; ReportPeriod.YEAR -> 1 },
                    )
                    Text(if (period == ReportPeriod.YEAR) "Spending per month in ₹" else "Spending per day, in ₹", style = MaterialTheme.typography.labelSmall, color = colors.text2)
                }
            }
        }

        if (period == ReportPeriod.MONTH && a.trend.any { it.second != 0L || it.third != 0L }) {
            item(key = "trend-label") { SectionLabel("Last 6 months") }
            item(key = "trend") {
                ChartCard {
                    ColumnChart(
                        series = listOf(a.trend.map { it.third.coerceAtLeast(0) / 100 }, a.trend.map { it.second.coerceAtLeast(0) / 100 }),
                        colors = listOf(colors.income, colors.expense),
                        xLabel = { x -> a.trend.getOrNull(x)?.first?.format(SHORT_MONTH).orEmpty() },
                        labelEvery = 1,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Legend(colors.income, "Income")
                        Legend(colors.expense, "Spent")
                    }
                }
            }
        }

        if (a.topMerchants.isNotEmpty()) {
            item(key = "merchants-label") { SectionLabel("Top places") }
            item(key = "merchants") {
                GroupCard {
                    a.topMerchants.forEachIndexed { i, m ->
                        ListRow(
                            title = Merchants.display(m.name),
                            subtitle = if (m.count == 1) "Once" else "${m.count} times",
                            leadingIcon = Icons.Rounded.Storefront,
                        ) { MoneyText(m.paise, style = SpendWise.money.row, color = colors.expense) }
                        if (i < a.topMerchants.lastIndex) HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
                    }
                }
            }
        }

        if (period == ReportPeriod.MONTH) {
            item(key = "budgets-label") { SectionLabel("Budgets · ${account?.nickname ?: ""}") }
            item(key = "budgets") {
                // Form state belongs to this account and month — a switch starts fresh (rule #1).
                key(a.accountId, a.month) { BudgetsCard(a, onSet = { cat, paise -> viewModel.setBudget(a.accountId, a.month, cat, paise) }) }
            }
        }

        if (a.tracked.isNotEmpty() || a.recurring.isNotEmpty()) {
            item(key = "bills-label") { SectionLabel("Regular payments") }
            item(key = "bills") {
                key(a.accountId) {
                    BillsCard(a, viewModel.today(), onTrack = { viewModel.track(a.accountId, it) }, onStop = { viewModel.stopTracking(a.accountId, it) })
                }
            }
        }
        message?.takeIf { it.first == a.accountId }?.let { (_, m) -> item(key = "msg") { Text(m, color = colors.danger, modifier = Modifier.padding(Spacing.screen)) } }
        item(key = "end") { Spacer(Modifier.height(Spacing.xl)) }
    }
        LiquidPeriodPickerOverlay(
            expanded = periodPickerOpen,
            selected = period,
            anchorInWindow = periodPickerAnchor,
            onDismiss = { periodPickerOpen = false },
            onSelect = viewModel::selectPeriod,
            shortLabels = true,
        )
    }
}

/** Keeps the report header in place while Room supplies the account context. */
@Composable
private fun InsightsContextPlaceholder() {
    val colors = SpendWise.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen, vertical = Spacing.xxs)) {
        Box(Modifier.width(132.dp).height(34.dp).clip(CircleShape).background(colors.surface2))
    }
}

@Composable
private fun InsightsReportPlaceholder() {
    val colors = SpendWise.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen).height(56.dp)
            .liquidGlass(RoundedCornerShape(Radius.card), GlassKind.Card)
            .padding(horizontal = Spacing.md)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(Icons.Rounded.Insights, contentDescription = null, tint = colors.text2, modifier = Modifier.size(20.dp))
        Text("Preparing this account’s insights", style = MaterialTheme.typography.bodySmall, color = colors.text2)
    }
}

@Composable
private fun MonthBar(label: String, canGoNext: Boolean, onPrev: () -> Unit, onNext: () -> Unit) {
    val colors = SpendWise.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous month", tint = colors.text1) }
        Text(label, style = MaterialTheme.typography.titleMedium, color = colors.text1, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = onNext, enabled = canGoNext) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next month", tint = if (canGoNext) colors.text1 else colors.text3)
        }
    }
}

@Composable
private fun PeriodPicker(
    selected: ReportPeriod,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAnchorChanged: (Rect) -> Unit,
) {
    LiquidPeriodPickerTrigger(
        selected = selected,
        expanded = expanded,
        onToggle = onToggle,
        onAnchorChanged = onAnchorChanged,
        shortLabel = true,
        modifier = Modifier.width(112.dp * LocalDensity.current.fontScale.coerceAtMost(1.9f)),
    )
}

@Composable
private fun SummaryCard(a: MonthAnalytics) {
    val colors = SpendWise.colors
    val net = a.incomePaise - a.spentPaise
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screen)
            .liquidGlass(RoundedCornerShape(Radius.card), GlassKind.Card)
            .padding(Spacing.lg),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            Column(Modifier.weight(1f)) {
                Text("Spent", style = MaterialTheme.typography.labelMedium, color = colors.text2)
                MoneyText(a.spentPaise, style = SpendWise.money.large, color = colors.expense)
                if (a.lentPaise > 0) Text("+ ${Money.format(a.lentPaise)} paid for friends", style = MaterialTheme.typography.bodySmall, color = colors.text2)
            }
            Column(Modifier.weight(1f)) {
                Text("Income", style = MaterialTheme.typography.labelMedium, color = colors.text2)
                MoneyText(a.incomePaise, style = SpendWise.money.large, color = colors.income)
                if (a.repaidPaise > 0) Text("+ ${Money.format(a.repaidPaise)} paid back", style = MaterialTheme.typography.bodySmall, color = colors.text2)
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Text(
            buildString {
                append(if (net >= 0) "Saved ${Money.format(net)}" else "Spent ${Money.format(-net)} more than came in")
                if (a.previousSpentPaise > 0) {
                    val diff = a.spentPaise - a.previousSpentPaise
                    append(" · ")
                    append(if (diff >= 0) "${Money.format(diff)} more than ${a.previousLabel}" else "${Money.format(-diff)} less than ${a.previousLabel}")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.text2,
        )
    }
}

/** Monochrome accent tints (ADR-009: one accent, no colour pools); the rest is grouped as "Other". */
private const val MAX_SLICES = 5

@Composable
private fun CategoryCard(a: MonthAnalytics) {
    val colors = SpendWise.colors
    val positives = a.spendByCategory.filter { it.second > 0 }
    val total = positives.sumOf { it.second }.coerceAtLeast(1)
    val top = positives.take(MAX_SLICES)
    val rest = positives.drop(MAX_SLICES).sumOf { it.second }
    val slices = top.map { it.second } + listOfNotNull(rest.takeIf { it > 0 })
    val alphas = listOf(1f, 0.72f, 0.52f, 0.38f, 0.27f, 0.18f)
    GroupCard {
        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Donut(slices.map { it.toFloat() / total }, slices.indices.map { colors.primary.copy(alpha = alphas[it.coerceAtMost(alphas.lastIndex)]) })
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text("Spent", style = MaterialTheme.typography.labelMedium, color = colors.text2)
                MoneyText(a.spentPaise, style = SpendWise.money.large, color = colors.expense)
                Text("${positives.size} categor${if (positives.size == 1) "y" else "ies"}", style = MaterialTheme.typography.bodySmall, color = colors.text2)
            }
        }
        HorizontalDivider(color = colors.divider)
        a.spendByCategory.forEachIndexed { i, (cat, paise) ->
            val c = cat?.let { a.categories[it] }
            val pct = if (paise > 0) (paise * 100 / total).toInt() else 0
            ListRow(
                title = c?.name ?: "Uncategorised",
                subtitle = if (paise > 0) "$pct%" else "Refunds and reversals",
                leadingIcon = CategoryIcons.of(c?.iconKey),
                leadingTint = if (i < MAX_SLICES && paise > 0) colors.primary.copy(alpha = alphas[i].coerceAtLeast(0.5f)) else colors.text2,
            ) { MoneyText(paise, style = SpendWise.money.row, color = if (paise > 0) colors.expense else colors.income) }
        }
    }
}

@Composable
private fun Donut(fractions: List<Float>, sliceColors: List<Color>) {
    val track = SpendWise.colors.surface3
    Canvas(Modifier.size(96.dp)) {
        val stroke = 16.dp.toPx()
        val inset = stroke / 2
        val box = Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
        var start = -90f
        fractions.forEachIndexed { i, f ->
            val sweep = 360f * f
            if (sweep > 0.5f) drawArc(sliceColors[i], start, (sweep - 1.5f).coerceAtLeast(0.5f), false, Offset(inset, inset), box, style = Stroke(stroke))
            start += sweep
        }
    }
}

@Composable
private fun ChartCard(content: @Composable () -> Unit) {
    GroupCard { Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) { content() } }
}

/**
 * Vico column chart; one series per colour, the whole range visible (no scrolling). Values are whole rupees for
 * display only — money math stays in paise. [xLabel] names column x (0-based).
 */
@Composable
private fun ColumnChart(series: List<List<Long>>, colors: List<Color>, xLabel: (Int) -> String, labelEvery: Int) {
    if (series.all { s -> s.all { it == 0L } }) return
    ProvideVicoTheme(rememberM3VicoTheme()) {
        val columns = colors.map { LineComponent(fill = Fill(SolidColor(it)), thickness = 6.dp, shape = RoundedCornerShape(3.dp)) }
        val chart = rememberCartesianChart(
            rememberColumnCartesianLayer(ColumnCartesianLayer.ColumnProvider.series(columns)),
            startAxis = VerticalAxis.rememberStart(guideline = null),
            bottomAxis = HorizontalAxis.rememberBottom(
                guideline = null,
                valueFormatter = CartesianValueFormatter { _, x, _ -> xLabel(x.toInt()) },
                itemPlacer = HorizontalAxis.ItemPlacer.aligned(spacing = { labelEvery }),
            ),
        )
        val model = remember(series) { CartesianChartModel(ColumnCartesianLayerModel.build { series.forEach { s -> series(s) } }) }
        CartesianChartHost(
            chart,
            model,
            Modifier.fillMaxWidth().height(180.dp),
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
        )
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = SpendWise.colors.text2)
    }
}

@Composable
private fun BudgetsCard(a: MonthAnalytics, onSet: (Long, Long) -> Unit) {
    val colors = SpendWise.colors
    var editing by remember { mutableStateOf(false) }
    GroupCard {
        if (a.budgets.isEmpty()) {
            ListRow(title = "No budget yet", subtitle = "Set a monthly limit for this account — overall or per category", leadingIcon = Icons.Rounded.Savings)
        }
        a.budgets.forEach { b -> BudgetRow(b, a) }
        ListRow(title = if (editing) "Close" else "Set a budget", leadingIcon = Icons.Rounded.Savings, onClick = { editing = !editing })
    }
    AnimatedVisibility(editing) {
        BudgetEditor(a, onSave = { cat, paise -> onSet(cat, paise); editing = false })
    }
}

@Composable
private fun BudgetRow(b: BudgetProgress, a: MonthAnalytics) {
    val colors = SpendWise.colors
    val name = if (b.categoryId == DatabaseSetup.CATEGORY_ALL_SPENDING) "All spending" else a.categories[b.categoryId]?.name ?: "Category"
    val tint = when (b.state) {
        BudgetState.OVER -> colors.expense
        BudgetState.NEAR -> colors.pending
        BudgetState.OK -> colors.income
    }
    Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
        Row(Modifier.fillMaxWidth()) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = colors.text1, modifier = Modifier.weight(1f))
            Text("${Money.format(b.spentPaise.coerceAtLeast(0))} of ${Money.format(b.limitPaise)}", style = MaterialTheme.typography.bodyMedium, color = colors.text2)
        }
        Spacer(Modifier.height(Spacing.xxs))
        LinearProgressIndicator(
            progress = { (b.percent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = tint,
            trackColor = colors.surface3,
            drawStopIndicator = {},
        )
        Text(
            when (b.state) {
                BudgetState.OVER -> "${Money.format(-b.leftPaise)} over"
                BudgetState.NEAR -> "${b.percent}% used · ${Money.format(b.leftPaise)} left"
                BudgetState.OK -> "${Money.format(b.leftPaise)} left"
            },
            style = MaterialTheme.typography.bodySmall,
            color = tint,
        )
    }
}

@Composable
private fun BudgetEditor(a: MonthAnalytics, onSave: (Long, Long) -> Unit) {
    val colors = SpendWise.colors
    val options = listOfNotNull(a.categories[DatabaseSetup.CATEGORY_ALL_SPENDING]) +
        a.categories.values.filter { it.kind == CategoryKind.EXPENSE && it.archivedAt == null }.sortedBy { it.sortOrder }
    var category by remember { mutableStateOf(options.firstOrNull()) }
    var amount by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Spacer(Modifier.height(Spacing.md))
    GroupCard {
        Column(Modifier.padding(vertical = Spacing.md)) {
            Text("Monthly limit from ${a.month.format(MONTH)}", style = MaterialTheme.typography.titleSmall, color = colors.text1, modifier = Modifier.padding(horizontal = Spacing.md))
            Spacer(Modifier.height(Spacing.xs))
            ChipRow(options = options, selected = category, label = { it.name }, icon = { CategoryIcons.of(it.iconKey) }, onSelect = { category = it })
            Spacer(Modifier.height(Spacing.xs))
            MoneyInput(amount, { amount = it; error = null }, "Limit per month", error = error, supporting = "0 removes it. Repeats every month until you change it.", modifier = Modifier.padding(horizontal = Spacing.md))
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    val p = parseSignedRupees(amount, emptyIsZero = false)?.takeIf { it >= 0 }
                    val c = category
                    when {
                        c == null -> error = "Pick a category"
                        p == null -> error = "Enter an amount"
                        else -> onSave(c.id, p)
                    }
                }) { Text("Save", color = colors.primary) }
            }
        }
    }
}

@Composable
private fun BillsCard(a: MonthAnalytics, today: LocalDate, onTrack: (RecurringSuggestion) -> Unit, onStop: (Long) -> Unit) {
    val colors = SpendWise.colors
    GroupCard {
        a.tracked.forEach { b ->
            val days = b.nextDueDay - today.toEpochDay()
            ListRow(
                title = Merchants.display(b.rule.displayName),
                subtitle = "${Money.format(-b.rule.amountPaise)} ${cadence(b.rule.cadence)} · " + when {
                    days < 0 -> "was due ${-days} day${if (days == -1L) "" else "s"} ago"
                    days == 0L -> "due today"
                    days == 1L -> "due tomorrow"
                    else -> "due in $days days"
                },
                leadingIcon = Icons.Rounded.Autorenew,
                leadingTint = if (days < 0) colors.pending else colors.text2,
            ) { TextButton(onClick = { onStop(b.rule.id) }) { Text("Stop", color = colors.text2) } }
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
        }
        a.recurring.forEach { s ->
            ListRow(
                title = Merchants.display(s.name),
                subtitle = "Looks ${cadence(s.cadence)} · ${Money.format(s.amountPaise)} · seen ${s.occurrences} times",
                leadingIcon = Icons.Rounded.Autorenew,
            ) { TextButton(onClick = { onTrack(s) }) { Text("Track", color = colors.primary) } }
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
        }
    }
}

private fun cadence(c: Cadence) = when (c) {
    Cadence.WEEKLY -> "weekly"
    Cadence.MONTHLY -> "monthly"
    Cadence.QUARTERLY -> "every 3 months"
    Cadence.YEARLY -> "yearly"
}
