package com.capyreader.app.ui.explica

import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.capyreader.app.BuildConfig
import com.capyreader.app.R
import com.capyreader.app.ui.articles.reader.ArticleBody
import com.capyreader.app.ui.articles.reader.LocalReaderStyle
import com.capyreader.app.ui.articles.reader.ReaderActions
import com.capyreader.app.ui.articles.reader.ReaderStyle
import com.capyreader.app.ui.articles.reader.rememberReaderStyle
import com.capyreader.app.ui.explica.highlights.HighlightMessages
import com.capyreader.app.ui.explica.highlights.HighlightScope
import com.capyreader.app.ui.explica.highlights.HighlightSelection
import com.capyreader.app.ui.explica.highlights.HighlightsListSheet
import com.capyreader.app.ui.explica.highlights.HighlightsToolbarButton
import com.capyreader.app.ui.explica.highlights.LocalHighlights
import com.capyreader.app.ui.explica.highlights.RefreshHighlightsOnResume
import com.capyreader.app.ui.explica.highlights.rememberHighlightsHost
import com.capyreader.app.ui.provideLinkOpener
import com.jocmp.mallet.Mallet
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private val MAX_CONTENT_WIDTH = 640.dp

/**
 * The explainer of one story of the daily digest: a chat about the story and, when the reader asks for
 * it, the AI explanation with suggested questions, from the news explainer server. Reached from the
 * article's top bar ([canExplain]).
 */
@Composable
fun ExplicaScreen(
    articleID: String,
    onNavigateBack: () -> Unit,
    viewModel: ExplicaViewModel = koinViewModel { parametersOf(articleID.toLongOrNull() ?: 0L) },
    highlightsViewModel: HighlightsViewModel = koinViewModel { parametersOf(articleID.toLongOrNull() ?: 0L) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val linkOpener = provideLinkOpener(LocalContext.current)
    val readerStyle = rememberReaderStyle(showImages = false)
    val highlights = rememberHighlightsHost(highlightsViewModel)

    RefreshHighlightsOnResume(highlights)

    CompositionLocalProvider(LocalHighlights provides highlights) {
        ExplicaView(
            state = state,
            readerStyle = readerStyle,
            onNavigateBack = onNavigateBack,
            onDraftChange = viewModel::setDraft,
            onSend = viewModel::send,
            onAsk = viewModel::ask,
            onExplain = viewModel::explain,
            onRetry = viewModel::retry,
            onOpenLink = { url -> linkOpener.open(Uri.parse(url)) },
        )
    }
}

private sealed interface Entry {
    val key: String
}

private data object OpeningEntry : Entry {
    override val key = "opening"
}

private data object HintEntry : Entry {
    override val key = "hint"
}

private data object ExplanationEntry : Entry {
    override val key = "explanation"
}

private data object SuggestionsEntry : Entry {
    override val key = "suggestions"
}

private data object AskErrorEntry : Entry {
    override val key = "ask-error"
}

private data class TurnEntry(val index: Int, val turn: ExplicaTurn) : Entry {
    override val key = "turn-$index"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplicaView(
    state: ExplicaState,
    readerStyle: ReaderStyle,
    onNavigateBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAsk: (String) -> Unit,
    onExplain: () -> Unit,
    onRetry: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    var highlightsOpen by rememberSaveable { mutableStateOf(false) }
    // Null when the screen was given no highlights (a preview, a test): then nothing about them shows.
    val highlights = LocalHighlights.current

    if (highlights != null) {
        HighlightMessages(host = highlights, snackbar = snackbar)
    }

    val entries = remember(state.phase, state.suggestions, state.turns, state.asking, state.askFailure) {
        buildList<Entry> {
            // Before the explanation is asked for there is no explanation section, only the chat (or a hint).
            when (state.phase) {
                ExplicaPhase.OPENING -> add(OpeningEntry)
                ExplicaPhase.IDLE -> if (state.turns.isEmpty()) add(HintEntry)
                ExplicaPhase.LOADING, ExplicaPhase.READY, ExplicaPhase.FAILED -> add(ExplanationEntry)
            }

            state.turns.forEachIndexed { index, turn -> add(TurnEntry(index, turn)) }

            if (state.phase == ExplicaPhase.READY && !state.asking && state.suggestions.isNotEmpty()) {
                add(SuggestionsEntry)
            }

            if (state.askFailure != null) {
                add(AskErrorEntry)
            }
        }
    }

    // A new question brings the chat into view; while an answer is written the reader keeps their place.
    LaunchedEffect(state.turns.size) {
        if (state.turns.isNotEmpty()) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }

    // The explanation section sits above the chat: started after some questions, it would open out of sight.
    LaunchedEffect(state.phase) {
        if (state.phase == ExplicaPhase.LOADING) {
            listState.animateScrollToItem(0)
        }
    }

    ResizeForKeyboard()

    CompositionLocalProvider(LocalReaderStyle provides readerStyle) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = state.title.ifBlank { stringResource(R.string.explica_title) },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.explica_back),
                            )
                        }
                    },
                    actions = {
                        if (highlights != null) {
                            HighlightsToolbarButton(onClick = { highlightsOpen = true })
                        }
                        if (state.articleUrl.isNotBlank()) {
                            IconButton(onClick = { onOpenLink(state.articleUrl) }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = stringResource(R.string.explica_open_article),
                                )
                            }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbar) },
            bottomBar = {
                // Only a failed explanation takes the bar away. While the story is opening or the explanation is
                // being written the bar stays, disabled, so the keyboard and the layout don't jump.
                if (state.phase != ExplicaPhase.FAILED) {
                    MessageBar(
                        draft = state.draft,
                        canSend = state.canSend,
                        enabled = state.canAsk && !state.asking,
                        showExplain = state.phase == ExplicaPhase.IDLE,
                        canExplain = state.canStartExplanation,
                        quickQuestions = state.quickQuestions,
                        canAskQuick = state.canAskQuick,
                        onDraftChange = onDraftChange,
                        onSend = onSend,
                        onExplain = onExplain,
                        onAsk = onAsk,
                    )
                }
            },
        ) { padding ->
            Box(
                contentAlignment = Alignment.TopCenter,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier
                        .widthIn(max = MAX_CONTENT_WIDTH)
                        .fillMaxSize(),
                ) {
                    itemsIndexed(entries, key = { _, entry -> entry.key }) { _, entry ->
                        when (entry) {
                            is OpeningEntry -> ProgressRow(stringResource(R.string.explica_loading))
                            is HintEntry -> Text(
                                text = stringResource(R.string.explica_idle_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            is ExplanationEntry -> ExplanationSection(state, onRetry, onOpenLink)
                            is TurnEntry -> TurnItem(entry.index, entry.turn, onOpenLink)
                            is SuggestionsEntry -> Suggestions(state.suggestions, onAsk)
                            is AskErrorEntry -> Text(
                                text = failureText(state.askFailure),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        if (highlightsOpen && highlights != null) {
            HighlightsListSheet(host = highlights, onDismiss = { highlightsOpen = false })
        }
    }
}

@Composable
private fun ExplanationSection(
    state: ExplicaState,
    onRetry: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.explica_heading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (state.explanationHtml.isNotBlank()) {
            // What is still being written isn't final, so it can't be highlighted yet.
            HtmlBlock(
                html = state.explanationHtml,
                onOpenLink = onOpenLink,
                where = HighlightWhere.EXPLANATION.takeIf { state.phase == ExplicaPhase.READY },
            )
        }

        when (state.phase) {
            ExplicaPhase.LOADING -> ProgressRow(state.stage.ifBlank { stringResource(R.string.explica_working) })
            ExplicaPhase.FAILED -> FailureCard(failureText(state.failure), onRetry)
            ExplicaPhase.READY -> if (state.meta.isNotBlank()) {
                Text(
                    text = state.meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The section is drawn only once the explanation has been asked for.
            ExplicaPhase.OPENING, ExplicaPhase.IDLE -> Unit
        }
    }
}

@Composable
private fun TurnItem(index: Int, turn: ExplicaTurn, onOpenLink: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(
                    text = turn.q,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        when (turn.status) {
            STATUS_DONE -> {
                HtmlBlock(html = turn.htmlApp, onOpenLink = onOpenLink, where = HighlightWhere.answer(index))

                if (turn.meta.isNotBlank()) {
                    Text(
                        text = turn.meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            STATUS_ERROR -> Text(
                text = turn.error.ifBlank { stringResource(R.string.explica_error_generic) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            else -> {
                ProgressRow(turn.stage.ifBlank { stringResource(R.string.explica_working) })

                if (turn.partialApp.isNotBlank()) {
                    HtmlBlock(turn.partialApp, onOpenLink)
                }
            }
        }
    }
}

@Composable
private fun Suggestions(questions: List<String>, onAsk: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        questions.forEach { question ->
            Surface(
                onClick = { onAsk(question) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = question,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/**
 * Without an explicit mode the system pans the whole window up when the keyboard opens over the message
 * bar, and the bar is lifted a second time by [imePadding]: the bar ends up mid-screen, the top bar
 * disappears and black shows behind the keyboard's rounded corners. With `adjustResize` the system
 * leaves the window alone (the app draws edge to edge) and the keyboard is handled only through insets.
 * The previous mode comes back when the screen closes, so the rest of the app is unaffected.
 */
@Composable
private fun ResizeForKeyboard() {
    val window = LocalActivity.current?.window ?: return

    DisposableEffect(window) {
        val previous = window.attributes.softInputMode
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        onDispose { window.setSoftInputMode(previous) }
    }
}

/**
 * The bar's surface reaches the bottom edge of the window and takes the keyboard (or the navigation bar)
 * as its own padding, so its color also fills the space behind the keyboard. The shortcut chips, when
 * shown, sit above the input inside the same surface. The side padding is on the rows, not on the column,
 * so the chips scroll right up to the edge of the screen.
 */
@Composable
private fun MessageBar(
    draft: String,
    canSend: Boolean,
    enabled: Boolean,
    showExplain: Boolean,
    canExplain: Boolean,
    quickQuestions: List<QuickQuestion>,
    canAskQuick: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onExplain: () -> Unit,
    onAsk: (String) -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(vertical = 8.dp),
        ) {
            if (showExplain || quickQuestions.isNotEmpty()) {
                ShortcutRow(
                    showExplain = showExplain,
                    canExplain = canExplain,
                    quickQuestions = quickQuestions,
                    canAskQuick = canAskQuick,
                    onExplain = onExplain,
                    onAsk = onAsk,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = enabled,
                    placeholder = { Text(stringResource(R.string.explica_input_hint)) },
                    shape = RoundedCornerShape(28.dp),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(
                    onClick = onSend,
                    enabled = canSend,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = stringResource(R.string.explica_send),
                    )
                }
            }
        }
    }
}

/**
 * One-tap shortcuts above the input. The explanation is one of them because it costs a generation
 * from a shared quota, so it never starts by itself. After it come the fixed questions ([QuickQuestion]),
 * each sent as a question of its own. The row scrolls sideways when the chips don't fit the screen.
 */
@Composable
private fun ShortcutRow(
    showExplain: Boolean,
    canExplain: Boolean,
    quickQuestions: List<QuickQuestion>,
    canAskQuick: Boolean,
    onExplain: () -> Unit,
    onAsk: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        if (showExplain) {
            AssistChip(
                onClick = onExplain,
                enabled = canExplain,
                label = { Text(stringResource(R.string.explica_explain)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
            )
        }

        quickQuestions.forEach { quick ->
            SuggestionChip(
                onClick = { onAsk(quick.question) },
                enabled = canAskQuick,
                label = { Text(stringResource(quick.label)) },
            )
        }
    }
}

@Composable
private fun ProgressRow(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FailureCard(message: String, onRetry: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            FilledTonalButton(onClick = onRetry) {
                Text(stringResource(R.string.explica_try_again))
            }
        }
    }
}

/**
 * The server's HTML (`html_app`), flattened and drawn by the article reader, so it follows the reader's font settings.
 * With a [where] its text can be highlighted (see [HighlightScope]).
 */
@Composable
private fun HtmlBlock(html: String, onOpenLink: (String) -> Unit, where: String? = null) {
    val article = remember(html) { Mallet.flatten(html, BuildConfig.EXPLICA_URL).getOrNull() } ?: return
    val actions = remember(onOpenLink) {
        ReaderActions(
            onLinkClick = { url, _ -> onOpenLink(url) },
            onLinkLongPress = {},
            onImageClick = {},
            onImageLongPress = {},
            onAudioClick = {},
        )
    }

    HighlightScope(where = where, article = article) {
        HighlightSelection {
            ProvideTextStyle(
                LocalReaderStyle.current.bodyTextStyle.copy(color = MaterialTheme.colorScheme.onSurface)
            ) {
                ArticleBody(article = article, actions = actions)
            }
        }
    }
}

/** Local text for the failures the reader can act on; the server's own words for the rest. */
@Composable
private fun failureText(failure: ExplicaResult.Failure?): String {
    return when (failure?.kind) {
        FailureKind.UNAUTHORIZED -> stringResource(R.string.explica_error_unauthorized)
        FailureKind.NETWORK -> stringResource(R.string.explica_error_network)
        FailureKind.UNAVAILABLE -> stringResource(R.string.explica_error_unavailable)
        FailureKind.NOT_FOUND,
        FailureKind.FORBIDDEN,
        FailureKind.BUSY,
        FailureKind.RATE_LIMITED,
        FailureKind.SERVER,
        null -> failure?.message?.ifBlank { null } ?: stringResource(R.string.explica_error_generic)
    }
}
