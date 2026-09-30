package com.capyreader.app.ui.explica

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.capyreader.app.ui.provideLinkOpener
import com.jocmp.mallet.Mallet
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private val MAX_CONTENT_WIDTH = 640.dp

/**
 * The explainer of one story of the daily digest: the AI explanation, suggested questions and a chat,
 * from the news explainer server. Reached from the article's top bar ([canExplain]).
 */
@Composable
fun ExplicaScreen(
    articleID: String,
    onNavigateBack: () -> Unit,
    viewModel: ExplicaViewModel = koinViewModel { parametersOf(articleID.toLongOrNull() ?: 0L) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val linkOpener = provideLinkOpener(LocalContext.current)
    val readerStyle = rememberReaderStyle(showImages = false)

    ExplicaView(
        state = state,
        readerStyle = readerStyle,
        onNavigateBack = onNavigateBack,
        onDraftChange = viewModel::setDraft,
        onSend = viewModel::send,
        onAsk = viewModel::ask,
        onRetry = viewModel::retry,
        onOpenLink = { url -> linkOpener.open(Uri.parse(url)) },
    )
}

private sealed interface Entry {
    val key: String
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
    onRetry: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val listState = rememberLazyListState()

    val entries = remember(state.phase, state.suggestions, state.turns, state.asking, state.askFailure) {
        buildList<Entry> {
            add(ExplanationEntry)
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

    CompositionLocalProvider(LocalReaderStyle provides readerStyle) {
        Scaffold(
            modifier = Modifier.imePadding(),
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
            bottomBar = {
                if (state.phase == ExplicaPhase.READY) {
                    MessageBar(
                        draft = state.draft,
                        canSend = state.canSend,
                        asking = state.asking,
                        onDraftChange = onDraftChange,
                        onSend = onSend,
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
                            is ExplanationEntry -> ExplanationSection(state, onRetry, onOpenLink)
                            is TurnEntry -> TurnItem(entry.turn, onOpenLink)
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
            HtmlBlock(state.explanationHtml, onOpenLink)
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
        }
    }
}

@Composable
private fun TurnItem(turn: ExplicaTurn, onOpenLink: (String) -> Unit) {
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
                HtmlBlock(turn.htmlApp, onOpenLink)

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

@Composable
private fun MessageBar(
    draft: String,
    canSend: Boolean,
    asking: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                enabled = !asking,
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

/** The server's HTML (`html_app`), flattened and drawn by the article reader, so it follows the reader's font settings. */
@Composable
private fun HtmlBlock(html: String, onOpenLink: (String) -> Unit) {
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

    SelectionContainer {
        ProvideTextStyle(
            LocalReaderStyle.current.bodyTextStyle.copy(color = MaterialTheme.colorScheme.onSurface)
        ) {
            ArticleBody(article = article, actions = actions)
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
