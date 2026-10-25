package com.nutrix.app.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nutrix.app.R
import com.nutrix.app.core.ui.theme.NutrixTokens

/** App-wide snackbar host so screens can offer "Undo" after deletions. */
val LocalSnackbarHost = staticCompositionLocalOf { SnackbarHostState() }

/**
 * Shows an undo snackbar. [onUndo] runs when the user taps "Undo"; otherwise [onCommit] runs
 * once the snackbar disappears (used to remove photo files for good).
 */
suspend fun SnackbarHostState.showUndo(message: String, undoLabel: String, onUndo: suspend () -> Unit, onCommit: suspend () -> Unit = {}) {
    currentSnackbarData?.dismiss()
    when (showSnackbar(message, actionLabel = undoLabel, withDismissAction = true, duration = SnackbarDuration.Long)) {
        SnackbarResult.ActionPerformed -> onUndo()
        SnackbarResult.Dismissed -> onCommit()
    }
}

/** Standard inner screen: top bar with back arrow, content constrained for large screens. */
@Composable
fun NutrixScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                actions = actions,
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
        snackbarHost = { if (snackbarHost != null) SnackbarHost(snackbarHost) },
        content = content,
    )
}

/** Lazy column centered and width-limited on tablets/foldables, with standard paddings. */
@Composable
fun ScreenList(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    bottomExtra: androidx.compose.ui.unit.Dp = 96.dp,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = NutrixTokens.ScreenPadding,
                end = NutrixTokens.ScreenPadding,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + bottomExtra,
            ),
            verticalArrangement = Arrangement.spacedBy(NutrixTokens.CardSpacing),
            content = content,
        )
    }
}

fun Modifier.screenPadding(padding: PaddingValues): Modifier =
    this.padding(padding).padding(horizontal = NutrixTokens.ScreenPadding)
