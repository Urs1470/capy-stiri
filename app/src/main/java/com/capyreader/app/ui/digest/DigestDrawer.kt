package com.capyreader.app.ui.digest

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Euro
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import com.capyreader.app.BuildConfig
import com.capyreader.app.ui.articles.CountBadge
import com.capyreader.app.ui.articles.ListTitle
import com.capyreader.app.ui.articles.feeds.DrawerItem
import com.capyreader.app.ui.articles.feeds.FolderRow
import com.capyreader.app.ui.articles.list.MarkAllReadMenu
import com.jocmp.capy.ArticleFilter
import com.jocmp.capy.Feed
import com.jocmp.capy.Folder
import com.jocmp.capy.accounts.Source
import com.jocmp.capy.common.DigestFolderOrder

/**
 * The drawer's folders in two groups (this fork; Ion, 2026-10-01: "there are quite a lot of them now"): the sections of
 * the daily digest, and the rest, which are the reading folders. A folder belongs to the digest when every feed in it
 * is one of the digest's (its URL starts with [feedPrefix]). The order within each group is the drawer's.
 */
data class DrawerFolders(val digest: List<Folder>, val reading: List<Folder>)

fun splitDigestFolders(folders: List<Folder>, feedPrefix: String = BuildConfig.EXPLICA_FEED_PREFIX): DrawerFolders {
    val (digest, reading) = folders.partition { folder ->
        folder.feeds.isNotEmpty() && folder.feeds.all { it.feedURL.startsWith(feedPrefix) }
    }

    return DrawerFolders(digest = digest, reading = reading)
}

private val ORDER_PREFIX = Regex("""^\s*\d+\s*[-–—.)]\s*""")

/**
 * The name a reading folder is shown with: a leading number ("3 - Educație…") only orders the folders, as it did in
 * Inoreader, so it isn't shown. The folder keeps its full name everywhere else (filters, Miniflux).
 */
fun readingFolderLabel(title: String): String {
    return title.replace(ORDER_PREFIX, "").ifBlank { title }
}

/** The icon of a section of the digest, by its place in [com.jocmp.capy.common.DIGEST_SECTION_ORDER]. */
internal fun sectionIcon(title: String): ImageVector {
    return when (DigestFolderOrder.rank(title)) {
        0 -> Icons.Outlined.Lightbulb
        1 -> Icons.Outlined.Home
        2 -> Icons.Outlined.Flag
        3 -> Icons.Outlined.Euro
        4 -> Icons.AutoMirrored.Outlined.ShowChart
        5 -> Icons.Outlined.Public
        6 -> Icons.Outlined.Memory
        7 -> Icons.Outlined.Bolt
        else -> Icons.Outlined.Newspaper
    }
}

/**
 * A section of the digest in the drawer. A section is one folder with one feed of the same name, so the row is that
 * folder, with its icon and without the arrow that only opened a copy of it. A section that somehow has several feeds
 * keeps the usual folder row.
 */
@Composable
fun DigestSectionRow(
    folder: Folder,
    filter: ArticleFilter,
    source: Source,
    onSelectFolder: (Folder) -> Unit,
    onSelectFeed: (feed: Feed, folderTitle: String) -> Unit,
    onMarkAllRead: (ArticleFilter) -> Unit,
) {
    val only = folder.feeds.singleOrNull()

    if (only == null) {
        FolderRow(
            folder = folder,
            onFolderSelect = onSelectFolder,
            onFeedSelect = { feed -> onSelectFeed(feed, folder.title) },
            onMarkAllRead = onMarkAllRead,
            filter = filter,
            source = source,
        )
        return
    }

    val (showMenu, setShowMenu) = remember { mutableStateOf(false) }

    Box {
        DrawerItem(
            icon = { Icon(sectionIcon(folder.title), contentDescription = null) },
            label = { ListTitle(folder.title) },
            badge = { CountBadge(count = folder.count) },
            selected = filter.isFolderSelected(folder) || filter.isFeedSelected(only),
            onClick = { onSelectFolder(folder) },
            onLongClick = { setShowMenu(true) },
        )

        MarkAllReadMenu(
            expanded = showMenu,
            onDismiss = { setShowMenu(false) },
            onMarkAllRead = {
                onMarkAllRead(ArticleFilter.Folders(folderTitle = folder.title, folderStatus = filter.status))
            },
        )
    }
}
