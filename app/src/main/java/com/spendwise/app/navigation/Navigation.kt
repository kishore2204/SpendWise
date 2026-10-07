package com.spendwise.app.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.spendwise.app.AppViewModel
import com.spendwise.app.feature.accounts.AccountDetailScreen
import com.spendwise.app.feature.accounts.AccountsScreen
import com.spendwise.app.feature.accounts.AddAccountScreen
import com.spendwise.app.feature.add.AddTransactionScreen
import com.spendwise.app.feature.transactions.TransactionDetailScreen
import com.spendwise.app.feature.transactions.TransactionsScreen
import com.spendwise.app.feature.home.HomeScreen
import com.spendwise.app.feature.insights.InsightsScreen
import com.spendwise.app.feature.settings.MoreScreen
import com.spendwise.app.ui.components.BottomBarItem
import com.spendwise.app.ui.components.GlassTopBar
import com.spendwise.app.ui.components.LiquidTabBar
import com.spendwise.app.ui.glass.LocalGlassTopBar
import com.spendwise.app.ui.glass.GlassScaffold
import com.spendwise.app.ui.theme.SpendWise
import kotlinx.serialization.Serializable

/* Routes. Top-level tabs reset the back stack; detail routes push onto it. */
@Serializable data object HomeRoute : NavKey
@Serializable data object AccountsRoute : NavKey
@Serializable data object InsightsRoute : NavKey
@Serializable data object MoreRoute : NavKey
@Serializable data class AddTransactionRoute(val editId: Long? = null) : NavKey
@Serializable data object TransactionsRoute : NavKey
@Serializable data class TransactionDetailRoute(val txnId: Long) : NavKey
@Serializable data object AddAccountRoute : NavKey
@Serializable data class AccountDetailRoute(val accountId: Long) : NavKey

private val TopLevelItems = listOf(
    BottomBarItem<NavKey>(HomeRoute, "Home", Icons.Outlined.Home, Icons.Rounded.Home),
    BottomBarItem<NavKey>(AccountsRoute, "Accounts", Icons.Outlined.AccountBalanceWallet, Icons.Rounded.AccountBalanceWallet),
    BottomBarItem<NavKey>(InsightsRoute, "Insights", Icons.Outlined.Insights, Icons.Rounded.Insights),
    BottomBarItem<NavKey>(MoreRoute, "More", Icons.Outlined.MoreHoriz, Icons.Rounded.MoreHoriz),
)

private fun NavBackStack<NavKey>.switchTab(tab: NavKey) {
    if (firstOrNull() == tab && size == 1) return
    clear()
    add(tab)
}

@Composable
fun SpendWiseNavHost(appViewModel: AppViewModel = hiltViewModel()) {
    val reduceTransparency by appViewModel.reduceTransparency.collectAsStateWithLifecycle()
    val backStack = rememberNavBackStack(HomeRoute)
    val top = backStack.lastOrNull()
    val currentTab = backStack.firstOrNull() ?: HomeRoute
    val showBar = top in TopLevelItems.map { it.key }
    val motion = SpendWise.motion

    GlassScaffold(
        reduceTransparency = reduceTransparency,
        overlay = {
            LocalGlassTopBar.current?.let { GlassTopBar(it, Modifier.align(Alignment.TopCenter)) }
            AnimatedVisibility(
                visible = showBar,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                LiquidTabBar(
                    items = TopLevelItems,
                    selected = currentTab,
                    onSelect = { backStack.switchTab(it) },
                    onPrimaryAction = { backStack.add(AddTransactionRoute()) },
                    primaryActionLabel = "Add expense",
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = backStack,
                onBack = {
                    when {
                        backStack.size > 1 -> backStack.removeLastOrNull()
                        // Back from a non-Home tab returns to Home (One UI convention) before leaving the app.
                        backStack.firstOrNull() != HomeRoute -> backStack.switchTab(HomeRoute)
                        else -> backStack.removeLastOrNull()
                    }
                },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                transitionSpec = {
                    fadeIn(tween(motion.duration(motion.medium), easing = motion.emphasizedDecelerate)) togetherWith
                        fadeOut(tween(motion.duration(motion.short)))
                },
                popTransitionSpec = {
                    fadeIn(tween(motion.duration(motion.medium), easing = motion.emphasizedDecelerate)) togetherWith
                        fadeOut(tween(motion.duration(motion.short)))
                },
                entryProvider = entryProvider {
                    entry<HomeRoute> {
                        HomeScreen(
                            onAddAccount = { backStack.add(AddAccountRoute) },
                            onManageAccounts = { backStack.switchTab(AccountsRoute) },
                            onSeeAll = { backStack.add(TransactionsRoute) },
                            onOpenTransaction = { backStack.add(TransactionDetailRoute(it)) },
                        )
                    }
                    entry<AccountsRoute> {
                        AccountsScreen(
                            onAddAccount = { backStack.add(AddAccountRoute) },
                            onOpenAccount = { backStack.add(AccountDetailRoute(it)) },
                        )
                    }
                    entry<AddAccountRoute> {
                        AddAccountScreen(
                            onClose = { backStack.removeLastOrNull() },
                            onCreated = { id ->
                                backStack.removeLastOrNull()
                                backStack.add(AccountDetailRoute(id))
                            },
                        )
                    }
                    entry<AccountDetailRoute> { key ->
                        AccountDetailScreen(accountId = key.accountId, onClose = { backStack.removeLastOrNull() })
                    }
                    entry<InsightsRoute> { InsightsScreen() }
                    entry<MoreRoute> { MoreScreen() }
                    entry<AddTransactionRoute> { key ->
                        AddTransactionScreen(onClose = { backStack.removeLastOrNull() }, editId = key.editId)
                    }
                    entry<TransactionsRoute> {
                        TransactionsScreen(
                            onClose = { backStack.removeLastOrNull() },
                            onOpen = { backStack.add(TransactionDetailRoute(it)) },
                        )
                    }
                    entry<TransactionDetailRoute> { key ->
                        TransactionDetailScreen(
                            txnId = key.txnId,
                            onClose = { backStack.removeLastOrNull() },
                            onEdit = { backStack.add(AddTransactionRoute(editId = it)) },
                        )
                    }
                },
            )
        }
    }
}
