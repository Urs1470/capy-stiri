package com.capyreader.app.ui.digest

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.capyreader.app.R
import com.capyreader.app.ui.articles.CountBadge
import com.capyreader.app.ui.articles.ListTitle
import com.capyreader.app.ui.articles.feeds.DrawerItem

/**
 * Starred in the drawer, under Today (this fork; Ion, 2026-10-01): the starred articles of every feed, without going
 * through the status bar. [count] is shown while it is [selected] (the drawer's count is the current list's).
 */
@Composable
fun StarredDrawerItem(selected: Boolean, count: Long, onClick: () -> Unit) {
    DrawerItem(
        icon = { Icon(Icons.Rounded.Star, contentDescription = null) },
        label = { ListTitle(stringResource(R.string.filter_starred)) },
        badge = { if (selected) CountBadge(count = count) },
        selected = selected,
        onClick = onClick,
    )
}
