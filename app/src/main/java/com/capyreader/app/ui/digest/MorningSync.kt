package com.capyreader.app.ui.digest

import androidx.activity.ComponentActivity
import androidx.work.WorkManager
import com.capyreader.app.BuildConfig
import com.capyreader.app.refresher.FeedRefresher
import com.capyreader.app.refresher.RefreshInterval
import com.jocmp.capy.Account
import com.jocmp.capy.Feed
import com.jocmp.capy.accounts.Source
import com.jocmp.capy.logging.CapyLog
import com.jocmp.capy.preferences.Preference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.getKoin

/** The file name, under the digest's feed prefix, of the feed of the entry of the day ("Conceptul zilei"). */
const val DAY_FEED_FILE = "conceptul-zilei.xml"

/** Its name until 2026-10-01 ("00 Azi"): the server renames the feed in place, so a phone not synced since still has it. */
const val OLD_DAY_FEED_FILE = "00-azi.xml"

/** A Miniflux account that has at least one of the digest's feeds, whose URLs start with [feedPrefix]. */
fun hasDigest(source: Source, feeds: List<Feed>, feedPrefix: String): Boolean {
    val miniflux = source == Source.MINIFLUX || source == Source.MINIFLUX_TOKEN

    return miniflux && feeds.any { it.feedURL.startsWith(feedPrefix) }
}

/** The feed of the entry of the day, if the account has it. */
fun dayFeedOf(feeds: List<Feed>, feedPrefix: String): Feed? {
    return feeds.firstOrNull { it.feedURL == feedPrefix + DAY_FEED_FILE }
        ?: feeds.firstOrNull { it.feedURL == feedPrefix + OLD_DAY_FEED_FILE }
}

/**
 * The morning sync of the digest (this fork): around [MORNING_SYNC_TIME] the app syncs by itself, so that the digest
 * that is published about 06:10 is already there, and the entry of the day ("Conceptul zilei") raises one notification.
 *
 * It is a chain of one-day pieces of work ([MorningSyncScheduler]); every run ([run], started by
 * [MorningSyncWorker]) queues the next day's first and then does what the periodic refresh does: the same
 * `FeedRefresher.refresh` (the same refresh, the same Wi-Fi-only rule, the same notifications for the feeds that have
 * them on). Nothing new notifies: the notification is upstream's, per feed, and the one feed this turns it on for is
 * the day entry's, once ([enableDayNotificationOnce]), so the other stories notify only when the reader turned them on.
 *
 * It exists only for a Miniflux account that has the digest's feeds and while the refresh setting is not "Manually
 * only", as upstream's notifications do ([isDue]). Upstream queues its own background work only when an account is
 * created or the interval is changed, so [keepScheduled] is called at every open of the app and queues the chain or
 * cancels it as [isDue] says.
 */
class MorningSync(
    private val account: Account,
    private val refresher: FeedRefresher,
    private val scheduler: MorningSyncScheduler,
    private val refreshInterval: () -> RefreshInterval,
    /** Whether the notification of the day feed has been turned on, once, for this account: the reader's later choice stays. */
    private val dayNotificationEnabled: Preference<Boolean>,
    private val feedPrefix: String = BuildConfig.EXPLICA_FEED_PREFIX,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    enum class Outcome {
        /** The account was refreshed. */
        REFRESHED,

        /** The refresh did not go through (no connection, the server, Wi-Fi only): worth another try soon. */
        NOT_REFRESHED,

        /** There is no digest account (any more), so nothing was done and nothing is queued. */
        SKIPPED,
    }

    /** Called at every open of the app: queues the chain when the account is due it, cancels it when not. */
    fun keepScheduled() {
        scope.launch {
            try {
                scheduleOrCancel()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CapyLog.error("morning_sync_schedule", e)
            }
        }
    }

    suspend fun scheduleOrCancel() {
        if (isDue(account.allFeeds.first())) {
            scheduler.scheduleNext()
        } else {
            scheduler.cancelAll()
        }
    }

    /**
     * One morning sync. The next day's is queued before anything else, so nothing below can break the chain; then the
     * notification of the day feed is turned on if that has not been done, the account is refreshed (with the
     * notifications for the new stories, inside `FeedRefresher.refresh`), and the notification is tried once more for
     * a day feed that only this refresh brought in.
     *
     * That last one is turned on after the notifications were made, deliberately: a feed first seen now comes with
     * whatever the server holds of it, and announcing all of it at once would be a burst and not one per day. From the
     * next refresh on it notifies as any feed does, for what is new.
     */
    suspend fun run(): Outcome {
        val feeds = account.allFeeds.first()

        if (!isDue(feeds)) {
            return Outcome.SKIPPED
        }

        scheduler.scheduleNext()
        enableDayNotificationOnce(feeds)

        val before = account.preferences.lastRefreshedAt.get()
        refresher.refresh()
        val refreshed = account.preferences.lastRefreshedAt.get() > before

        enableDayNotificationOnce(account.allFeeds.first())

        return if (refreshed) Outcome.REFRESHED else Outcome.NOT_REFRESHED
    }

    private fun isDue(feeds: List<Feed>): Boolean {
        return refreshInterval().isPeriodic && hasDigest(account.source, feeds, feedPrefix)
    }

    /**
     * Turns the notification of the day feed on the first time the account has that feed, and never again: a reader who
     * turns it off in Settings, Notifications, keeps it off. Nothing else is touched.
     */
    private suspend fun enableDayNotificationOnce(feeds: List<Feed>) {
        if (dayNotificationEnabled.get()) {
            return
        }

        val day = dayFeedOf(feeds, feedPrefix) ?: return

        if (!day.enableNotifications) {
            account.toggleNotifications(feedID = day.id, enabled = true)
        }

        dayNotificationEnabled.set(true)
        CapyLog.info("morning_sync_day_notification", mapOf("feed" to day.id))
    }
}

/**
 * Called from `MainActivity.onStart`, at every open of the app: keeps the morning sync queued while there is an account
 * that is due it, and cancels it when there is no account at all (a removed account leaves its work behind).
 */
fun ComponentActivity.keepMorningSync(hasAccount: Boolean) {
    if (hasAccount) {
        getKoin().getOrNull<MorningSync>()?.keepScheduled()
    } else {
        MorningSyncScheduler(WorkManager.getInstance(applicationContext)).cancelAll()
    }
}
