package com.capyreader.app.ui.explica.highlights

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.capyreader.app.R
import com.capyreader.app.common.openLink
import com.capyreader.app.ui.articles.ListTitle
import com.capyreader.app.ui.articles.feeds.DrawerItem
import com.capyreader.app.ui.explica.FailureKind
import com.capyreader.app.ui.explica.HighlightsPageState
import com.capyreader.app.ui.explica.HighlightsPageViewModel
import com.capyreader.app.ui.explica.StoryHighlights
import com.jocmp.capy.accounts.Source
import org.koin.androidx.compose.koinViewModel

/**
 * The Highlights page (this fork; Ion, 2026-10-01): every highlight, one card per story, the story highlighted last
 * first. A tap opens the story in the reader, where the highlights are changed; the page only reads them.
 */
@Composable
fun HighlightsPageScreen(
    onNavigateBack: () -> Unit,
    onOpenArticle: (articleID: String) -> Unit,
    viewModel: HighlightsPageViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Highlights made or removed in a story since the page was read.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()

        onPauseOrDispose { }
    }

    HighlightsPage(
        state = state,
        onNavigateBack = onNavigateBack,
        onRefresh = viewModel::refresh,
        onOpen = { story -> viewModel.open(story, onOpenArticle) },
        onDismissMissing = viewModel::dismissMissing,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HighlightsPage(
    state: HighlightsPageState,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (StoryHighlights) -> Unit,
    onDismissMissing: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val goneText = stringResource(R.string.highlights_page_story_gone)
    val openSource = stringResource(R.string.highlights_page_open_source)
    val missing = state.missing

    LaunchedEffect(missing) {
        if (missing == null) {
            return@LaunchedEffect
        }

        val link = missing.link.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val result = snackbar.showSnackbar(message = goneText, actionLabel = link?.let { openSource })

        if (result == SnackbarResult.ActionPerformed && link != null) {
            openLink(context, link.toUri(), openExternalAdjacent = false, openInternally = true)
        }
        onDismissMissing()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.highlights_list_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.explica_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.loading) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.highlights_page_refresh),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbar) },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                state.stories.isNotEmpty() -> StoryList(stories = state.stories, onOpen = onOpen)
                state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.failure != null -> Failed(kind = state.failure, onRetry = onRefresh)
                else -> Message(stringResource(R.string.highlights_page_empty))
            }
        }
    }
}

@Composable
private fun StoryList(stories: List<StoryHighlights>, onOpen: (StoryHighlights) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.testTag("highlights_page_list"),
    ) {
        items(stories, key = { it.entryId }) { story ->
            StoryCard(story = story, onClick = { onOpen(story) })
        }
    }
}

@Composable
private fun StoryCard(story: StoryHighlights, onClick: () -> Unit) {
    val colors = rememberHighlightColors()
    val context = LocalContext.current
    val count = pluralStringResource(R.plurals.highlights_page_count, story.highlights.size, story.highlights.size)
    val day = remember(story.latest) {
        if (story.latest > 0) {
            DateUtils.formatDateTime(
                context,
                story.latest * 1_000,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
            )
        } else {
            ""
        }
    }

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = story.title.ifBlank { stringResource(R.string.highlights_page_untitled) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(story.section, day, count).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            story.highlights.forEach { highlight ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(vertical = 6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(6.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors(highlight.highlightColor)),
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            text = whereLabel(highlight.where),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = highlight.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Failed(kind: FailureKind, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        val cause = when (kind) {
            FailureKind.NETWORK -> R.string.highlights_cause_network
            FailureKind.UNAUTHORIZED -> R.string.highlights_cause_unauthorized
            FailureKind.UNAVAILABLE -> R.string.highlights_cause_unavailable
            FailureKind.RATE_LIMITED -> R.string.highlights_cause_rate_limited
            else -> null
        }
        Text(
            text = listOfNotNull(stringResource(R.string.highlights_load_failed), cause?.let { stringResource(it) })
                .joinToString(" "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.explica_try_again))
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}

/**
 * The drawer's way to the Highlights page, under Today. Only for a Miniflux account signed in with an API token: the
 * highlights are kept by the explainer server, which knows the reader by that token.
 */
@Composable
fun HighlightsDrawerItem(source: Source, onClick: () -> Unit) {
    if (source != Source.MINIFLUX_TOKEN) {
        return
    }

    DrawerItem(
        icon = { Icon(Icons.Outlined.Highlight, contentDescription = null) },
        label = { ListTitle(stringResource(R.string.highlights_list_title)) },
        selected = false,
        onClick = onClick,
    )
}
