package com.jocmp.capy.persistence

import com.jocmp.capy.ArticleStatus
import com.jocmp.capy.InMemoryDatabaseProvider
import com.jocmp.capy.MarkRead
import com.jocmp.capy.articles.SortOrder
import com.jocmp.capy.common.newsDayStart
import com.jocmp.capy.db.Database
import com.jocmp.capy.fixtures.ArticleFixture
import com.jocmp.capy.fixtures.FeedFixture
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * This fork: Today shows only the news digest (feeds under https://news.iupif.org/sectiuni/) once the account has
 * any, so the reading feeds imported from Inoreader stay in their folders. Every other list is the upstream one.
 */
class DigestTodayTest {
    private lateinit var database: Database
    private lateinit var records: ArticleRecords
    private lateinit var articles: ArticleFixture
    private lateinit var feeds: FeedFixture

    @Before
    fun setup() {
        database = InMemoryDatabaseProvider.build("777")
        records = ArticleRecords(database)
        articles = ArticleFixture(database)
        feeds = FeedFixture(database)
    }

    // Today counts from the real clock (OffsetDateTime.now); the fixture's default time comes from TimeHelpers, whose
    // clock other tests of the suite move.
    private val now = java.time.OffsetDateTime.now().toEpochSecond()

    private fun today() = records.byToday
        .all(ArticleStatus.ALL, limit = 50, offset = 0, sortOrder = SortOrder.NEWEST_FIRST, since = null)
        .executeAsList()
        .map { it.id }
        .toSet()

    private fun all() = records.byStatus
        .all(ArticleStatus.ALL, limit = 50, offset = 0, sortOrder = SortOrder.NEWEST_FIRST)
        .executeAsList()
        .map { it.id }
        .toSet()

    @Test
    fun withoutDigestFeeds_todayIsUnchanged() {
        val reading = feeds.create(feedURL = "https://aeon.co/feed.rss")
        val a = articles.create(feed = reading, read = false, publishedAt = now)
        val b = articles.create(read = false, publishedAt = now)

        assertEquals(setOf(a.id, b.id), today())
        assertEquals(2, records.byToday.count(ArticleStatus.ALL).executeAsOne())
    }

    @Test
    fun withDigestFeeds_todayHasOnlyTheDigest_andEveryOtherListHasEverything() {
        val section = feeds.create(feedURL = "https://news.iupif.org/sectiuni/republica-moldova.xml", title = "Republica Moldova")
        val day = feeds.create(feedURL = "https://news.iupif.org/sectiuni/conceptul-zilei.xml", title = "Conceptul zilei")
        val reading = feeds.create(feedURL = "https://warontherocks.com/feed/", title = "War on the Rocks")
        val story = articles.create(feed = section, read = false, publishedAt = now)
        val concept = articles.create(feed = day, read = false, publishedAt = now)
        val essay = articles.create(feed = reading, read = false, publishedAt = now)

        assertEquals(setOf(story.id, concept.id), today())
        assertEquals(2, records.byToday.count(ArticleStatus.ALL).executeAsOne())
        assertEquals(2, records.byToday.count(ArticleStatus.UNREAD).executeAsOne())
        assertEquals(
            setOf(story.id, concept.id),
            records.byToday.unreadArticleIDs(ArticleStatus.ALL, MarkRead.All, SortOrder.NEWEST_FIRST, query = null)
                .executeAsList()
                .toSet(),
        )

        assertEquals(setOf(story.id, concept.id, essay.id), all())
        assertEquals(3, records.byStatus.count(ArticleStatus.ALL).executeAsOne())
    }

    @Test
    fun theNewsDayStartsAt4_andBefore4ItIsStillTheDayBefore() {
        val zone = java.time.ZoneId.of("Europe/Chisinau")
        fun at(month: Int, day: Int, hour: Int, minute: Int = 0) =
            java.time.ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone).toOffsetDateTime()
        fun start(month: Int, day: Int) = at(month, day, 4).toEpochSecond()

        assertEquals(start(10, 1), newsDayStart(at(10, 1, 9, 25), zone))
        assertEquals(start(10, 1), newsDayStart(at(10, 1, 4), zone))
        assertEquals(start(10, 1), newsDayStart(at(10, 2, 3, 59), zone))
        assertEquals(start(9, 30), newsDayStart(at(9, 30, 23), zone))
        // the change to winter time (25 October): the day still starts at 04:00 on the clock
        assertEquals(start(10, 25), newsDayStart(at(10, 25, 12), zone))
    }

    @Test
    fun withDigestFeeds_todayIsTheNewsDay_soYesterdaysDigestAndTheNightBeforeFourStayOut() {
        val zone = java.time.ZoneId.systemDefault()
        val section = feeds.create(feedURL = "https://news.iupif.org/sectiuni/republica-moldova.xml", title = "Republica Moldova")
        fun at(day: Int, hour: Int, minute: Int) =
            java.time.ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toEpochSecond()

        val yesterday = articles.create(feed = section, read = false,
            publishedAt = java.time.ZonedDateTime.of(2026, 9, 30, 16, 22, 0, 0, zone).toEpochSecond())
        val night = articles.create(feed = section, read = false, publishedAt = at(1, 0, 5))
        val digest = articles.create(feed = section, read = false, publishedAt = at(1, 6, 10))
        val opened = java.time.ZonedDateTime.of(2026, 10, 1, 9, 25, 0, 0, zone).toOffsetDateTime()

        val today = records.byToday
            .all(ArticleStatus.ALL, limit = 50, offset = 0, sortOrder = SortOrder.NEWEST_FIRST, since = opened)
            .executeAsList()
            .map { it.id }

        assertEquals(listOf(digest.id), today)
        assertEquals(1, records.byToday.count(ArticleStatus.ALL, since = opened).executeAsOne())
        assertEquals(setOf(yesterday.id, night.id, digest.id), all())
    }

    @Test
    fun withDigestFeeds_theReaderSkipsTheReadingFeedsWhenWalkingToday() {
        val section = feeds.create(feedURL = "https://news.iupif.org/sectiuni/ai.xml", title = "AI")
        val reading = feeds.create(feedURL = "https://aeon.co/feed.rss", title = "Aeon")
        val first = articles.create(feed = section, publishedAt = now - 30)
        articles.create(feed = reading, publishedAt = now - 20)
        val last = articles.create(feed = section, publishedAt = now - 10)

        val (previous, next) = records.byToday.neighbors(ArticleStatus.ALL, SortOrder.NEWEST_FIRST, since = null, articleID = last.id)

        assertEquals(null, previous)
        assertEquals(first.id, next)
    }
}
