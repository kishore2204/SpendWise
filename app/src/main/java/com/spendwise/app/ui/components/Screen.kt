package com.spendwise.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spendwise.app.ui.glass.GlassTopBarState
import com.spendwise.app.ui.glass.LocalGlassTopBar
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing

/** Height reserved at the bottom of every top-level screen for the floating liquid tab bar. */
val BottomBarReservedHeight: Dp = 64.dp + 12.dp + 24.dp

/**
 * One UI reachability + iOS-style title behaviour: content starts lower, the large title scrolls away, and the
 * scaffold's top bar (a soft scroll-edge fade with an inline title) appears once it is gone.
 */
@Composable
fun LargeTitleScreen(
    title: String,
    modifier: Modifier = Modifier,
    collapsedTitle: String = title,
    eyebrow: String? = null,
    reserveBottomBar: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val topBar = LocalGlassTopBar.current
    if (topBar != null) {
        val owner = remember { Any() }
        LaunchedEffect(topBar, collapsed, collapsedTitle) {
            topBar.owner = owner
            topBar.title = collapsedTitle
            topBar.visible = collapsed
        }
        DisposableEffect(topBar) {
            onDispose { if (topBar.owner === owner) topBar.visible = false }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = statusBar,
            bottom = navBar + if (reserveBottomBar) BottomBarReservedHeight else Spacing.lg,
        ),
    ) {
        item(key = "large-title", contentType = "header") {
            LargeHeader(title = title, eyebrow = eyebrow, topSpace = (screenHeight * 0.14f).coerceAtLeast(48.dp))
        }
        content()
    }
}

/** Scroll-edge effect: content dissolves under a soft fade instead of hitting a hard bar. */
@Composable
fun GlassTopBar(state: GlassTopBarState, modifier: Modifier = Modifier) {
    val colors = SpendWise.colors
    AnimatedVisibility(visible = state.visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to colors.background.copy(alpha = 0.96f),
                        0.6f to colors.background.copy(alpha = 0.75f),
                        1f to colors.background.copy(alpha = 0f),
                    ),
                )
                .statusBarsPadding()
                .height(64.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                state.title.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = colors.text1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp, start = Spacing.screen, end = Spacing.screen),
            )
        }
    }
}

@Composable
private fun LargeHeader(title: String, eyebrow: String?, topSpace: Dp) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen).padding(bottom = Spacing.lg),
    ) {
        Spacer(Modifier.height(topSpace))
        if (eyebrow != null) {
            Text(eyebrow, style = MaterialTheme.typography.titleSmall, color = SpendWise.colors.text2)
            Spacer(Modifier.height(Spacing.xxs))
        }
        Text(title, style = MaterialTheme.typography.displaySmall, color = SpendWise.colors.text1)
    }
}
