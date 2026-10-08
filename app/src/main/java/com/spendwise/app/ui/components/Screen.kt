package com.spendwise.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spendwise.app.ui.glass.GlassTopBarState
import com.spendwise.app.ui.glass.LocalBlurAllowed
import com.spendwise.app.ui.glass.LocalContentBackdrop
import com.spendwise.app.ui.glass.LocalGlassTopBar
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import com.kyant.shapes.Capsule
import com.spendwise.app.ui.glass.GlassKind
import com.spendwise.app.ui.glass.liquidGlass
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
    titleBottomSpacing: Dp = Spacing.lg,
    glassTitle: Boolean = false,
    reserveBottomBar: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    val collapsed by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val scrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val topBar = LocalGlassTopBar.current
    if (topBar != null) {
        val owner = remember { Any() }
        SideEffect {
            topBar.owner = owner
            topBar.title = collapsedTitle
            topBar.scrolled = scrolled
            topBar.titleVisible = collapsed
        }
        DisposableEffect(topBar) {
            onDispose {
                if (topBar.owner === owner) {
                    topBar.scrolled = false
                    topBar.titleVisible = false
                }
            }
        }
    }

    LazyColumn(
        state = listState,
        // imePadding: the app is edge-to-edge, so the keyboard must shrink the list or it covers form fields.
        modifier = modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(
            top = statusBar,
            bottom = navBar + if (reserveBottomBar) BottomBarReservedHeight else Spacing.lg,
        ),
    ) {
        item(key = "large-title", contentType = "header") {
            LargeHeader(
                title = title,
                eyebrow = eyebrow,
                topSpace = (screenHeight * 0.14f).coerceAtLeast(48.dp),
                bottomSpacing = titleBottomSpacing,
                glassTitle = glassTitle,
            )
        }
        content()
    }
}

/**
 * Glass scroll edge (ADR-009): the content under the status bar is refracted through a light blur that fades
 * out downward, so text dissolves into the edge instead of hitting a hard bar. The inline title appears once
 * the large title has scrolled away.
 */
@Composable
fun GlassTopBar(state: GlassTopBarState, modifier: Modifier = Modifier) {
    val colors = SpendWise.colors
    val backdrop = LocalContentBackdrop.current
    val allowed = LocalBlurAllowed.current
    if (state.scrolled) {
        Box(
            Modifier
                .fillMaxWidth()
                // Offscreen layer + DstIn gradient = a mask that fades the whole glass edge out downward.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                }
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { RectangleShape },
                            effects = { if (allowed) blur(10.dp.toPx()) },
                            highlight = null,
                            shadow = null,
                            onDrawSurface = { drawRect(colors.background.copy(alpha = if (allowed) 0.55f else 0.92f)) },
                        )
                    } else {
                        Modifier.background(colors.background.copy(alpha = 0.92f))
                    },
                )
                .statusBarsPadding()
                .height(72.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (state.titleVisible) {
                Text(
                    state.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = colors.text1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp, start = Spacing.screen, end = Spacing.screen),
                )
            }
        }
    }
}

@Composable
private fun LargeHeader(title: String, eyebrow: String?, topSpace: Dp, bottomSpacing: Dp, glassTitle: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.screen).padding(bottom = bottomSpacing),
    ) {
        Spacer(Modifier.height(topSpace))
        if (eyebrow != null) {
            Text(eyebrow, style = MaterialTheme.typography.titleSmall, color = SpendWise.colors.text2)
            Spacer(Modifier.height(Spacing.xxs))
        }
        Text(
            title,
            style = MaterialTheme.typography.displaySmall,
            color = SpendWise.colors.text1,
            modifier = if (glassTitle) Modifier
                .liquidGlass(Capsule(), GlassKind.Card)
                .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            else Modifier,
        )
    }
}

/** Compact account context used below large titles on single-account reports and lists. */
@Composable
fun AccountContextPill(label: String) {
    Row(
        Modifier
            .padding(horizontal = Spacing.screen, vertical = Spacing.xxs)
            .widthIn(max = 320.dp)
            .liquidGlass(Capsule(), GlassKind.Card)
            .clip(CircleShape)
            .padding(horizontal = Spacing.md, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = SpendWise.colors.text1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
