package com.capyreader.app.ui.explica.highlights

import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.capyreader.app.R
import com.capyreader.app.ui.components.LocalSnackbarHost
import com.capyreader.app.ui.explica.FailureKind
import com.capyreader.app.ui.explica.Highlight
import com.capyreader.app.ui.explica.HighlightAction
import com.capyreader.app.ui.explica.HighlightColor
import com.capyreader.app.ui.explica.HighlightFailure
import com.capyreader.app.ui.explica.HighlightWhere
import com.capyreader.app.ui.explica.HighlightsState
import com.capyreader.app.ui.explica.HighlightsViewModel
import com.capyreader.app.ui.explica.canExplain
import com.capyreader.app.ui.theme.LocalAppTheme
import com.jocmp.capy.Account
import com.jocmp.capy.Article
import com.jocmp.mallet.LinearArticle
import com.jocmp.mallet.LinearText
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/**
 * The highlights of one story as a screen sees them: what the [HighlightsViewModel] holds, and the changes
 * it takes. A screen that shows highlights provides one with [LocalHighlights].
 */
@Stable
class HighlightsHost internal constructor(
    private val viewModel: HighlightsViewModel,
    private val current: State<HighlightsState>,
) {
    val state: HighlightsState
        get() = current.value

    fun add(text: String, color: HighlightColor, where: String, prefix: String, suffix: String) {
        viewModel.add(text = text, color = color, where = where, prefix = prefix, suffix = suffix)
    }

    fun remove(id: String) = viewModel.remove(id)

    fun recolor(id: String, color: HighlightColor) = viewModel.recolor(id, color)

    fun selectionTooLong() = viewModel.selectionTooLong()

    fun refresh() = viewModel.refresh()

    fun dismissFailure(failure: HighlightFailure) = viewModel.dismissFailure(failure)
}

/** `null` where highlights are off: other feeds and other account types, and any screen that doesn't provide them. */
val LocalHighlights = compositionLocalOf<HighlightsHost?> { null }

@Composable
fun rememberHighlightsHost(viewModel: HighlightsViewModel): HighlightsHost {
    val state = viewModel.state.collectAsStateWithLifecycle()

    return remember(viewModel) { HighlightsHost(viewModel, state) }
}

/** One [HighlightsViewModel] per story, kept by the screen that shows the reader, so the top bar and the text share it. */
@Composable
fun storyHighlightsViewModel(articleId: String): HighlightsViewModel {
    return koinViewModel(key = "highlights-$articleId") { parametersOf(articleId.toLongOrNull() ?: 0L) }
}

/** A selection waiting for a color: [clearSelection] takes the selection off the text once the highlight is made. */
internal class PendingHighlight(
    val text: String,
    val prefix: String,
    val suffix: String,
    val clearSelection: () -> Unit,
)

/** The highlight state of one block of text (the story, the explanation or one answer), in [where]. */
@Stable
internal class HighlightScopeState(
    val where: String,
    val paragraphs: List<LinearText>,
    private val host: HighlightsHost,
) {
    internal var pending by mutableStateOf<PendingHighlight?>(null)
    var tappedId by mutableStateOf<String?>(null)

    /** The reader chose Highlight in the selection toolbar; [pieces] are the selected parts of the paragraphs. */
    fun requestHighlight(pieces: List<String>, clearSelection: () -> Unit) {
        when (val capture = HighlightAnchoring.capture(paragraphs.map { it.text }, pieces)) {
            is Capture.Passage -> {
                val captured = capture.captured

                pending = PendingHighlight(captured.text, captured.prefix, captured.suffix, clearSelection)
            }

            Capture.Empty -> Unit
            Capture.TooLong -> host.selectionTooLong()
        }
    }

    /** The reader chose [color] for the selection that waits: it becomes a highlight and the selection goes away. */
    fun pick(color: HighlightColor) {
        val waiting = pending ?: return

        host.add(waiting.text, color, where, waiting.prefix, waiting.suffix)
        waiting.clearSelection()
        pending = null
    }

    fun tap(highlightId: String) {
        tappedId = highlightId
    }
}

internal val LocalHighlightScope = compositionLocalOf<HighlightScopeState?> { null }

/**
 * Makes the [article] a block of highlights in [where]: its paragraphs draw the highlights of that place and a tap
 * on one opens its actions, and [HighlightSelection] inside can make new ones. Does nothing (the [content] is drawn
 * as it is) when there is no [LocalHighlights] or no [where].
 */
@Composable
fun HighlightScope(
    where: String?,
    article: LinearArticle?,
    content: @Composable () -> Unit,
) {
    val host = LocalHighlights.current

    if (host == null || where == null) {
        content()
        return
    }

    val paragraphs = remember(article) { article?.paragraphs().orEmpty() }
    val scope = remember(where, paragraphs, host) { HighlightScopeState(where, paragraphs, host) }
    val items = host.state.forWhere(where)
    val dark = LocalAppTheme.current.isDark
    val spans = remember(scope, items, dark) {
        HighlightSpans.resolve(
            paragraphs = scope.paragraphs,
            highlights = items,
            dark = dark,
            onTap = scope::tap,
        )
    }

    CompositionLocalProvider(
        LocalHighlightScope provides scope,
        LocalHighlightSpans provides spans,
    ) {
        content()
    }

    val pending = scope.pending

    if (pending != null) {
        HighlightColorSheet(
            passage = pending.text,
            onPick = scope::pick,
            onDismiss = { scope.pending = null },
        )
    }

    val tapped = host.state.items.firstOrNull { it.id == scope.tappedId }

    if (scope.tappedId != null && tapped == null) {
        // The highlight was removed (here or from the list) while its actions were open.
        LaunchedEffect(scope.tappedId) { scope.tappedId = null }
    }

    if (tapped != null) {
        TappedHighlightActions(host = host, highlight = tapped, onDismiss = { scope.tappedId = null })
    }
}

@Composable
private fun TappedHighlightActions(
    host: HighlightsHost,
    highlight: Highlight,
    onDismiss: () -> Unit,
) {
    val copy = rememberCopyText()

    HighlightActionsSheet(
        highlight = highlight,
        pending = host.state.isPending(highlight.id),
        onRecolor = { color ->
            host.recolor(highlight.id, color)
            onDismiss()
        },
        onCopy = {
            copy(highlight.text)
            onDismiss()
        },
        onRemove = {
            host.remove(highlight.id)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

/** The key of the Highlight item in the selection toolbar. */
private const val HIGHLIGHT_MENU_KEY = "capy-news-highlight"

/**
 * The selection container of a block of text. Inside a [HighlightScope] the selection toolbar gets a Highlight item
 * next to Copy; anywhere else this is the plain [SelectionContainer] it replaces. A test can hand in the
 * [state] to select text without a finger.
 */
@Composable
fun HighlightSelection(
    state: SelectionState? = null,
    content: @Composable () -> Unit,
) {
    val scope = LocalHighlightScope.current

    if (scope == null) {
        SelectionContainer(content = content)
        return
    }

    val selection = state ?: rememberSelectionState()
    val label = stringResource(R.string.highlights_menu_item)
    val toolbarItem = remember(scope, selection, label) {
        Modifier.appendTextContextMenuComponents {
            item(key = HIGHLIGHT_MENU_KEY, label = label) {
                // The selected parts of the paragraphs, one piece for each paragraph the selection touches.
                scope.requestHighlight(
                    pieces = selection.selectedTexts.map { it.text },
                    clearSelection = selection::clear,
                )
                close()
            }
        }
    }

    SelectionContainer(state = selection, modifier = toolbarItem, content = content)
}

/** Tells the reader when a change was refused and undone, once for each failure. */
@Composable
fun HighlightMessages(host: HighlightsHost, snackbar: SnackbarHostState) {
    val failure = host.state.failure
    val message = failure?.let { highlightFailureText(it) }

    LaunchedEffect(failure) {
        if (failure != null && message != null) {
            try {
                snackbar.showSnackbar(message)
            } finally {
                host.dismissFailure(failure)
            }
        }
    }
}

@Composable
private fun highlightFailureText(failure: HighlightFailure): String {
    val action = when (failure.action) {
        HighlightAction.ADD -> R.string.highlights_error_add
        HighlightAction.REMOVE -> R.string.highlights_error_remove
        HighlightAction.RECOLOR -> R.string.highlights_error_recolor
        HighlightAction.SELECTION_TOO_LONG -> return stringResource(R.string.highlights_error_too_long)
    }
    val cause = when (failure.kind) {
        FailureKind.NETWORK -> R.string.highlights_cause_network
        FailureKind.UNAUTHORIZED -> R.string.highlights_cause_unauthorized
        FailureKind.UNAVAILABLE -> R.string.highlights_cause_unavailable
        FailureKind.RATE_LIMITED -> R.string.highlights_cause_rate_limited
        else -> null
    }

    return if (cause == null) {
        stringResource(action)
    } else {
        "${stringResource(action)} ${stringResource(cause)}"
    }
}

/** Reads the highlights again whenever the screen comes back: another screen or device may have changed them. */
@Composable
fun RefreshHighlightsOnResume(host: HighlightsHost) {
    LifecycleResumeEffect(host) {
        host.refresh()

        onPauseOrDispose { }
    }
}

/**
 * The selectable content of the reader. For a story of the daily digest (the ones with the Explain button) the text
 * of the [flattened] story can be highlighted, and the highlights are read and saved on the server. For any other
 * story this is the plain [SelectionContainer] the reader always had.
 */
@Composable
fun StoryHighlightableContent(
    article: Article,
    flattened: LinearArticle?,
    content: @Composable () -> Unit,
) {
    val account: Account = koinInject()

    if (!canExplain(account.source, article.feedURL)) {
        SelectionContainer(content = content)
        return
    }

    val host = rememberHighlightsHost(storyHighlightsViewModel(article.id))

    HighlightMessages(host = host, snackbar = LocalSnackbarHost.current)
    RefreshHighlightsOnResume(host)

    CompositionLocalProvider(LocalHighlights provides host) {
        HighlightScope(where = HighlightWhere.STORY, article = flattened) {
            HighlightSelection(content = content)
        }
    }
}

/** The list of the highlights of a story, from the reader's top bar. */
@Composable
fun StoryHighlightsSheet(articleId: String, onDismiss: () -> Unit) {
    HighlightsListSheet(
        host = rememberHighlightsHost(storyHighlightsViewModel(articleId)),
        onDismiss = onDismiss,
    )
}
