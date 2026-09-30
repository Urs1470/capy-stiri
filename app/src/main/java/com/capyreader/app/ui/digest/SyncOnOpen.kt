package com.capyreader.app.ui.digest

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.koinInject
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Opening the app refreshes when the last refresh is older than this. The one place to change it. */
val SYNC_ON_OPEN_AFTER: Duration = 10.minutes

/** Whether the last refresh is old enough for opening the app to refresh again. */
class RefreshAge(
    private val clock: Clock = Clock.systemUTC(),
    private val staleAfter: Duration = SYNC_ON_OPEN_AFTER,
) {
    /**
     * [lastRefreshedAt] is what the account stores: epoch seconds, 0 before the first refresh. That first
     * refresh is the article list's own (`ArticleScreenViewModel` starts it by itself), so 0 is never stale
     * here, and a cold start on a new account isn't refreshed twice. A time in the future isn't stale either.
     */
    fun isStale(lastRefreshedAt: Long): Boolean {
        if (lastRefreshedAt <= 0) {
            return false
        }

        val age = clock.instant().epochSecond - lastRefreshedAt

        return age > staleAfter.inWholeSeconds
    }
}

/**
 * Sync on open (this fork). Upstream refreshes on the first run, on pull to refresh and, in the background, every
 * two hours: opening the app on an old list showed old stories. Now an open also refreshes when the last refresh
 * is older than [SYNC_ON_OPEN_AFTER].
 *
 * `MainActivity` reports each open ([onOpen]); the article list, which owns the refresh and its indicators,
 * asks whether one is due ([takeRefresh]) through [SyncOnOpenEffect]. An open that happens while the list is not on
 * screen (the reader is open) waits for the list instead of being lost.
 */
class SyncOnOpen(
    private val lastRefreshedAt: () -> Long,
    private val age: RefreshAge = RefreshAge(),
) {
    private val _pending = MutableStateFlow(false)

    /** True from an open until the article list has taken it with [takeRefresh]. */
    val pending: StateFlow<Boolean> = _pending

    /** The app came to the foreground. */
    fun onOpen() {
        _pending.value = true
    }

    /**
     * Takes the open that is waiting, if any, and says whether it is due a refresh: only when the last refresh is
     * stale. Each open is taken once, so a list that leaves the screen and comes back does not refresh again.
     */
    fun takeRefresh(): Boolean {
        if (!_pending.compareAndSet(expect = true, update = false)) {
            return false
        }

        return age.isStale(lastRefreshedAt())
    }
}

/** Refreshes through [onRefresh] when the app was opened with an old list; see [SyncOnOpen]. */
@Composable
fun SyncOnOpenEffect(
    onRefresh: () -> Unit,
    syncOnOpen: SyncOnOpen = koinInject(),
) {
    val pending by syncOnOpen.pending.collectAsState()
    val refresh by rememberUpdatedState(onRefresh)

    LaunchedEffect(pending) {
        if (pending && syncOnOpen.takeRefresh()) {
            refresh()
        }
    }
}
