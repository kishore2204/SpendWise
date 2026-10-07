package com.spendwise.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spendwise.app.ui.glass.liveGlass
import com.spendwise.app.ui.theme.SpendWise
import com.spendwise.app.ui.theme.Spacing
import androidx.compose.material3.MaterialTheme
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** Height reserved at the bottom of every top-level screen for the floating glass nav pill. */
val BottomBarReservedHeight: Dp = 64.dp + 12.dp + 24.dp

/**
 * One UI-style top-level screen: content starts lower (reachability), the large title scrolls away,
 * and a compact glass top bar fades in once the title is gone. The top bar blurs this screen's own list.
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
    val hazeState = rememberHazeState()
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().hazeSource(hazeState),
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

        AnimatedVisibility(
            visible = collapsed,
            enter = fadeIn() + slideInVertically { -it / 3 },
            exit = fadeOut() + slideOutVertically { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .liveGlass(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp), hazeState)
                    .statusBarsPadding()
                    .height(52.dp)
                    .padding(horizontal = Spacing.screen),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    collapsedTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = SpendWise.colors.text1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LargeHeader(title: String, eyebrow: String?, topSpace: Dp) {
    androidx.compose.foundation.layout.Column(
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
