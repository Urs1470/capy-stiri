package com.capyreader.app.ui.explica.highlights

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.capyreader.app.R
import com.capyreader.app.ui.explica.Highlight
import com.capyreader.app.ui.explica.HighlightColor
import com.capyreader.app.ui.explica.HighlightWhere
import com.capyreader.app.ui.theme.LocalAppTheme
import kotlinx.coroutines.launch

/** The background a [HighlightColor] has in the theme that is showing: the same the highlighted text is drawn on. */
@Composable
internal fun rememberHighlightColors(): (HighlightColor) -> Color {
    val dark = LocalAppTheme.current.isDark

    return { color -> Color(HighlightPalette.argb(color, dark)) }
}

@Composable
private fun HighlightColor.label(): String {
    return stringResource(
        when (this) {
            HighlightColor.YELLOW -> R.string.highlights_color_yellow
            HighlightColor.GREEN -> R.string.highlights_color_green
            HighlightColor.BLUE -> R.string.highlights_color_blue
            HighlightColor.PINK -> R.string.highlights_color_pink
        }
    )
}

@Composable
private fun whereLabel(where: String): String {
    val answer = HighlightWhere.answerIndex(where)

    return when {
        where == HighlightWhere.STORY -> stringResource(R.string.highlights_where_story)
        where == HighlightWhere.EXPLANATION -> stringResource(R.string.highlights_where_explanation)
        // The chat is counted from zero on the server; the reader counts from one.
        answer != null -> stringResource(R.string.highlights_where_answer, answer + 1)
        else -> where
    }
}

/** Puts a text on the clipboard, for a highlight copied from its sheet or from the list. */
@Composable
internal fun rememberCopyText(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    return { text ->
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", text)))
        }
    }
}

@Composable
private fun ColorDot(
    color: Color,
    size: Dp,
    selected: Boolean = false,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = CircleShape),
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(size * 0.6f),
            )
        }
    }
}

/** Asks which color a selected passage gets; [passage] is what will be highlighted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HighlightColorSheet(
    passage: String,
    onPick: (HighlightColor) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = rememberHighlightColors()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Text(
                text = stringResource(R.string.highlights_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = passage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
            )

            HighlightColor.entries.forEach { color ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(color) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    ColorDot(color = colors(color), size = 28.dp)
                    Text(
                        text = color.label(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

/**
 * What can be done to a highlight that was tapped: change its color, copy it, remove it. While the server has
 * not confirmed it ([pending]) it can only be copied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HighlightActionsSheet(
    highlight: Highlight,
    pending: Boolean,
    onRecolor: (HighlightColor) -> Unit,
    onCopy: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = rememberHighlightColors()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = colors(highlight.highlightColor),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = highlight.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(12.dp),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.highlights_color),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HighlightColor.entries.forEach { color ->
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(enabled = !pending, onClickLabel = color.label()) { onRecolor(color) }
                            .padding(4.dp),
                    ) {
                        ColorDot(
                            color = colors(color),
                            size = 32.dp,
                            selected = color == highlight.highlightColor,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.highlights_copy))
                }
                TextButton(onClick = onRemove, enabled = !pending) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.highlights_remove))
                }
            }
        }
    }
}

/** Every highlight of the story, with its color, where it is, and Copy and Remove. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightsListSheet(
    host: HighlightsHost,
    onDismiss: () -> Unit,
) {
    val colors = rememberHighlightColors()
    val copy = rememberCopyText()
    val state = host.state

    // What another screen or device highlighted since this one read the list.
    LaunchedEffect(host) { host.refresh() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.highlights_list_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )

        if (state.items.isEmpty()) {
            Text(
                text = stringResource(
                    if (state.loadFailed && !state.loaded) R.string.highlights_load_failed else R.string.highlights_empty
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 40.dp),
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(state.items, key = { it.id }) { highlight ->
                    val pending = state.isPending(highlight.id)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .alpha(if (pending) 0.6f else 1f)
                            .padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(6.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(colors(highlight.highlightColor)),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                        ) {
                            Text(
                                text = whereLabel(highlight.where),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = highlight.text,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { copy(highlight.text) }) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.highlights_copy),
                            )
                        }
                        IconButton(
                            onClick = { host.remove(highlight.id) },
                            enabled = !pending,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.highlights_remove),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The top bar icon that opens the list of the highlights of a story. */
@Composable
fun HighlightsToolbarButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Outlined.Highlight,
            contentDescription = stringResource(R.string.highlights_action),
            modifier = Modifier.size(24.dp),
        )
    }
}
