package com.capyreader.app.ui.explica

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The explanations kept on the phone: a fake API in place of the server, a temporary folder in place of `filesDir`
 * and a clock that only moves when the test says so.
 */
class SavedExplanationsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A clock the test moves by hand. */
    private class MovableClock(private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now

        fun advance(by: Duration) {
            now = now.plus(by)
        }
    }

    private class FakeApi : ExplicaApi {
        data class ExplainCall(val entryId: Long, val retry: Boolean, val start: Boolean)

        val explainCalls = mutableListOf<ExplainCall>()
        val askCalls = mutableListOf<Pair<Long, String?>>()
        val explainResults = ArrayDeque<ExplicaResult<ExplainResponse>>()
        val askResults = ArrayDeque<ExplicaResult<AskResponse>>()

        override suspend fun explain(entryId: Long, retry: Boolean, start: Boolean): ExplicaResult<ExplainResponse> {
            explainCalls += ExplainCall(entryId, retry, start)
            check(explainResults.isNotEmpty()) { "explain() called more often than scripted" }

            return explainResults.removeFirst()
        }

        override suspend fun ask(entryId: Long, question: String?): ExplicaResult<AskResponse> {
            askCalls += entryId to question
            check(askResults.isNotEmpty()) { "ask() called more often than scripted" }

            return askResults.removeFirst()
        }
    }

    private val begin = Instant.parse("2026-09-30T06:00:00Z")
    private val clock = MovableClock(begin)
    private val fake = FakeApi()
    private val directory get() = File(tmp.root, SAVED_EXPLANATIONS_DIRECTORY)

    private fun store(maxFiles: Int = SAVED_EXPLANATIONS_MAX) = SavedExplanationStore(directory, clock, maxFiles = maxFiles)

    private fun savedApi(store: SavedExplanationStore = store()) = SavedExplanationsApi(fake, store)

    private fun done(
        chat: List<ExplicaTurn> = emptyList(),
        meta: String = "MiniMax-M3 · 13 s",
        html: String = "<p>Explicația</p>",
        quota: Quota? = null,
        day: Boolean = false,
    ) = ExplainResponse(
        status = STATUS_DONE,
        title = "Drona",
        link = "https://ipn.md/a",
        htmlApp = html,
        meta = meta,
        suggest = listOf("Ce urmează?", "Cine e?"),
        chat = chat,
        quota = quota,
        day = day,
    )

    private fun turn(q: String, status: String = STATUS_DONE, html: String = "<p>r</p>") =
        ExplicaTurn(q = q, status = status, htmlApp = if (status == STATUS_DONE) html else "", meta = "m")

    private fun ok(response: ExplainResponse) = ExplicaResult.Success(response)

    private fun success(result: ExplicaResult<ExplainResponse>) = (result as ExplicaResult.Success).value

    private fun failure(kind: FailureKind, message: String = "") = ExplicaResult.Failure(kind, message)

    private fun raw(name: String, at: Instant, text: String = """{"status":"done","html_app":"<p>x</p>"}""") {
        directory.mkdirs()
        File(directory, name).apply {
            writeText(text)
            setLastModified(at.toEpochMilli())
        }
    }

    private fun names() = directory.listFiles().orEmpty().map { it.name }.sorted()

    // what is saved

    @Test
    fun aFinishedExplanation_isSavedUnderTheEntryId_asOneSmallJsonFile() = runTest {
        fake.explainResults += ok(done(chat = listOf(turn("Ce e drona?"))))
        val api = savedApi()

        api.explain(42)

        assertEquals(listOf("42.json"), names())
        val text = File(directory, "42.json").readText()
        assertTrue(text, text.contains(""""html_app":"<p>Explicația</p>""""))
        assertTrue(text, text.contains(""""q":"Ce e drona?""""))
        assertTrue(text.length < 2_000)
    }

    @Test
    fun whatMatteredOnlyWhileWriting_isNotKept() = runTest {
        fake.explainResults += ok(
            done(quota = Quota(used = 18, cap = 60)).copy(stage = "scriu", partialApp = "<p>par</p>", error = "x")
        )

        savedApi().explain(42)

        val text = File(directory, "42.json").readText()
        listOf("quota", "stage", "partial_app", "error", "day").forEach { key ->
            assertFalse("$key in $text", text.contains("\"$key\""))
        }
    }

    @Test
    fun whatIsNotAFinishedExplanation_isNotSaved() = runTest {
        fake.explainResults += ok(ExplainResponse(status = "idle", title = "Drona"))
        fake.explainResults += ok(ExplainResponse(status = "running", title = "Drona", partialApp = "<p>p</p>"))
        fake.explainResults += ok(ExplainResponse(status = "error", title = "Drona", error = "It failed."))
        fake.explainResults += ok(done(html = "  "))
        fake.explainResults += ok(done(day = true))
        val api = savedApi()

        repeat(5) { api.explain(42) }

        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun aRunningAnswerInTheChat_isNotKeptWithTheExplanation() = runTest {
        fake.explainResults += ok(
            done(chat = listOf(turn("Prima"), turn("A doua", status = STATUS_RUNNING)))
        )
        fake.explainResults += failure(FailureKind.NETWORK)
        val api = savedApi()

        api.explain(42)
        val copy = success(api.explain(42))

        assertEquals(listOf("Prima"), copy.chat.map { it.q })
    }

    @Test
    fun aStoryExplainedAgain_keepsOneFile_andTheNewerAnswer() = runTest {
        fake.explainResults += ok(done(html = "<p>Întâi</p>"))
        fake.explainResults += ok(done(html = "<p>Apoi</p>"))
        fake.explainResults += failure(FailureKind.NETWORK)
        val api = savedApi()

        api.explain(42)
        api.explain(42)

        assertEquals(listOf("42.json"), names())
        assertEquals("<p>Apoi</p>", success(api.explain(42)).htmlApp)
    }

    @Test
    fun twoStories_areTwoFiles() = runTest {
        fake.explainResults += ok(done())
        fake.explainResults += ok(done())
        val api = savedApi()

        api.explain(42)
        api.explain(43)

        assertEquals(listOf("42.json", "43.json"), names())
    }

    // when it is shown

    @Test
    fun whenTheNetworkFails_theSavedCopyIsTheAnswer_markedAsACopy() = runTest {
        val chat = listOf(turn("Ce e drona?"))
        fake.explainResults += ok(done(chat = chat))
        fake.explainResults += failure(FailureKind.NETWORK, "Unable to resolve host")
        val api = savedApi()

        val first = success(api.explain(42))
        val copy = success(api.explain(42))

        assertEquals(STATUS_DONE, copy.status)
        assertEquals("Drona", copy.title)
        assertEquals("https://ipn.md/a", copy.link)
        assertEquals("<p>Explicația</p>", copy.htmlApp)
        assertEquals(listOf("Ce urmează?", "Cine e?"), copy.suggest)
        assertEquals(chat, copy.chat)
        assertEquals("MiniMax-M3 · 13 s · saved copy", copy.meta)
        // The server's own answer is left as it came.
        assertEquals("MiniMax-M3 · 13 s", first.meta)
        assertNull(copy.quota)
        assertFalse(copy.day)
    }

    @Test
    fun whenTheServerCantCheckTheToken_orTheStoryIsGone_theSavedCopyIsTheAnswer() = runTest {
        listOf(FailureKind.UNAVAILABLE, FailureKind.NOT_FOUND).forEach { kind ->
            val api = FakeApi().apply {
                explainResults += ok(done())
                explainResults += failure(kind, "The server's words")
            }.let { SavedExplanationsApi(it, store()) }

            // A fresh API over the same folder: the copy is on disk, not in memory.
            val first = api.explain(42)
            val copy = api.explain(42)

            assertTrue(kind.name, first is ExplicaResult.Success)
            assertEquals(kind.name, "<p>Explicația</p>", success(copy).htmlApp)
            assertTrue(kind.name, success(copy).meta.endsWith(" · saved copy"))
        }
    }

    @Test
    fun theCopyIsReadFromDisk_soItSurvivesTheApp() = runTest {
        fake.explainResults += ok(done())
        savedApi().explain(42)

        val afterRestart = FakeApi().apply { explainResults += failure(FailureKind.NETWORK) }
        val copy = success(SavedExplanationsApi(afterRestart, store()).explain(42))

        assertEquals("<p>Explicația</p>", copy.htmlApp)
    }

    @Test
    fun aFailureThatSaysSomethingAboutTheReader_isShownAsItIs_evenWithACopy() = runTest {
        listOf(
            FailureKind.UNAUTHORIZED,
            FailureKind.FORBIDDEN,
            FailureKind.BUSY,
            FailureKind.RATE_LIMITED,
            FailureKind.SERVER,
        ).forEach { kind ->
            val delegate = FakeApi().apply {
                explainResults += ok(done())
                explainResults += failure(kind, "Text $kind")
            }
            val api = SavedExplanationsApi(delegate, store())

            api.explain(42)
            val result = api.explain(42)

            assertEquals(kind.name, ExplicaResult.Failure(kind, "Text $kind"), result)
        }
    }

    @Test
    fun withoutACopy_aFailureIsShownAsItIs() = runTest {
        FailureKind.entries.forEach { kind ->
            val delegate = FakeApi().apply { explainResults += failure(kind, "Text $kind") }
            val api = SavedExplanationsApi(delegate, store())

            assertEquals(kind.name, ExplicaResult.Failure(kind, "Text $kind"), api.explain(99))
        }
    }

    @Test
    fun aCopyOfAnotherStory_isNotUsed() = runTest {
        fake.explainResults += ok(done())
        fake.explainResults += failure(FailureKind.NETWORK)
        val api = savedApi()

        api.explain(42)

        assertTrue(api.explain(43) is ExplicaResult.Failure)
    }

    // the server's answer wins

    @Test
    fun whenTheServerAnswers_itsAnswerIsNeverReplacedByTheCopy() = runTest {
        val idle = ExplainResponse(status = STATUS_IDLE, title = "Drona")
        val running = ExplainResponse(status = STATUS_RUNNING, title = "Drona", stage = "scriu")
        val error = ExplainResponse(status = STATUS_ERROR, title = "Drona", error = "It failed.")
        fake.explainResults += ok(done())
        fake.explainResults += ok(idle)
        fake.explainResults += ok(running)
        fake.explainResults += ok(error)
        val api = savedApi()

        api.explain(42)

        assertEquals(idle, success(api.explain(42, start = false)))
        assertEquals(running, success(api.explain(42)))
        assertEquals(error, success(api.explain(42)))
    }

    @Test
    fun aDoneAnswer_replacesTheCopy() = runTest {
        fake.explainResults += ok(done(html = "<p>Veche</p>"))
        fake.explainResults += ok(done(html = "<p>Nouă</p>", meta = "alt model · 9 s"))
        fake.explainResults += failure(FailureKind.NETWORK)
        val api = savedApi()

        api.explain(42)
        api.explain(42)
        val copy = success(api.explain(42))

        assertEquals("<p>Nouă</p>", copy.htmlApp)
        assertEquals("alt model · 9 s · saved copy", copy.meta)
    }

    @Test
    fun aServerThatAnswersIdle_doesNotDeleteTheCopy_butDoesNotLendItEither() = runTest {
        fake.explainResults += ok(done())
        fake.explainResults += ok(ExplainResponse(status = STATUS_IDLE, title = "Drona"))
        fake.explainResults += failure(FailureKind.NETWORK)
        val api = savedApi()

        api.explain(42)
        assertEquals(STATUS_IDLE, success(api.explain(42)).status)

        // Offline later, the copy is all there is.
        assertEquals(STATUS_DONE, success(api.explain(42)).status)
    }

    // what is sent

    @Test
    fun theOpenCall_worksLikeTheOthers_andWhatIsSentIsNotChanged() = runTest {
        fake.explainResults += ok(done())
        fake.explainResults += failure(FailureKind.NETWORK)
        fake.explainResults += failure(FailureKind.NOT_FOUND)
        fake.explainResults += ok(done())
        val api = savedApi()

        api.explain(7, retry = false, start = false)
        val offline = api.explain(7, retry = false, start = false)
        val gone = api.explain(7, retry = true, start = true)
        api.explain(7)

        assertEquals("<p>Explicația</p>", success(offline).htmlApp)
        assertEquals("<p>Explicația</p>", success(gone).htmlApp)
        assertEquals(
            listOf(
                FakeApi.ExplainCall(7, retry = false, start = false),
                FakeApi.ExplainCall(7, retry = false, start = false),
                FakeApi.ExplainCall(7, retry = true, start = true),
                FakeApi.ExplainCall(7, retry = false, start = true),
            ),
            fake.explainCalls,
        )
    }

    @Test
    fun ask_passesTheQuestionOn_andItsAnswerBack() = runTest {
        val answer = AskResponse(chat = listOf(turn("Ce e drona?")), quota = Quota(used = 3, cap = 60))
        fake.askResults += ExplicaResult.Success(answer)
        fake.askResults += ExplicaResult.Success(answer)
        fake.askResults += failure(FailureKind.NETWORK, "timeout")
        val api = savedApi()

        assertEquals(ExplicaResult.Success(answer), api.ask(42, "Ce e drona?"))
        assertEquals(ExplicaResult.Success(answer), api.ask(42))
        assertEquals(failure(FailureKind.NETWORK, "timeout"), api.ask(42, "Și dacă?"))
        assertEquals(listOf(42L to "Ce e drona?", 42L to null, 42L to "Și dacă?"), fake.askCalls)
    }

    @Test
    fun aFailureKeepsItsQuotaAndChat_whenPassedOn() = runTest {
        val busy = ExplicaResult.Failure(
            kind = FailureKind.BUSY,
            message = "Aștept",
            chat = listOf(turn("Prima", status = STATUS_RUNNING)),
            quota = Quota(used = 5, cap = 60),
        )
        fake.askResults += busy

        assertEquals(busy, savedApi().ask(42, "A doua"))
    }

    // the chat

    @Test
    fun aSuccessfulAsk_bringsTheChatOfTheCopyUpToDate() = runTest {
        fake.explainResults += ok(done(chat = listOf(turn("Prima"))))
        fake.askResults += ExplicaResult.Success(AskResponse(chat = listOf(turn("Prima"), turn("A doua", STATUS_RUNNING))))
        fake.askResults += ExplicaResult.Success(AskResponse(chat = listOf(turn("Prima"), turn("A doua"))))
        fake.explainResults += failure(FailureKind.NETWORK)
        val api = savedApi()

        api.explain(42)
        api.ask(42, "A doua")
        // While the answer is written only what is final is on disk.
        assertEquals(listOf("Prima"), store().read(42)!!.chat.map { it.q })

        api.ask(42)
        val copy = success(api.explain(42))

        assertEquals(listOf("Prima", "A doua"), copy.chat.map { it.q })
        assertEquals(listOf(STATUS_DONE, STATUS_DONE), copy.chat.map { it.status })
        assertEquals("<p>Explicația</p>", copy.htmlApp)
    }

    @Test
    fun thePollsOfARunningAnswer_doNotTouchTheFile() = runTest {
        fake.explainResults += ok(done(chat = listOf(turn("Prima"))))
        val running = AskResponse(chat = listOf(turn("Prima"), turn("A doua", STATUS_RUNNING)))
        fake.askResults += ExplicaResult.Success(running)
        fake.askResults += ExplicaResult.Success(running)
        val api = savedApi()
        api.explain(42)
        val file = File(directory, "42.json")
        file.setLastModified(begin.toEpochMilli())
        clock.advance(Duration.ofHours(3))

        api.ask(42, "A doua")
        api.ask(42)

        assertEquals(begin.toEpochMilli(), file.lastModified())
    }

    @Test
    fun anAskWithoutACopy_savesNothing() = runTest {
        fake.askResults += ExplicaResult.Success(AskResponse(chat = listOf(turn("Prima"))))

        savedApi().ask(42, "Prima")

        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun aFailedAsk_leavesTheCopyAlone() = runTest {
        fake.explainResults += ok(done(chat = listOf(turn("Prima"))))
        fake.askResults += failure(FailureKind.NETWORK)
        fake.askResults += ExplicaResult.Failure(
            kind = FailureKind.BUSY,
            message = "Aștept",
            chat = listOf(turn("Alta")),
        )
        fake.explainResults += failure(FailureKind.NETWORK)
        val api = savedApi()

        api.explain(42)
        api.ask(42, "A doua")
        api.ask(42, "A treia")

        assertEquals(listOf("Prima"), success(api.explain(42)).chat.map { it.q })
    }

    // the mark

    @Test
    fun theMark_followsTheMetaWithADot_orStandsAloneWithoutOne() {
        assertEquals("MiniMax-M3 · 13 s · saved copy", "MiniMax-M3 · 13 s".markedAsSavedCopy())
        assertEquals("Saved copy", "".markedAsSavedCopy())
        assertEquals("Saved copy", "  ".markedAsSavedCopy())
        assertEquals("saved copy", SAVED_COPY_MARK)
    }

    @Test
    fun aCopyShownTwice_isMarkedOnce_andTheFileIsLeftAsItWas() = runTest {
        fake.explainResults += ok(done())
        fake.explainResults += failure(FailureKind.NETWORK)
        fake.explainResults += failure(FailureKind.NETWORK)
        val api = savedApi()
        api.explain(42)
        val before = File(directory, "42.json").readText()

        val once = success(api.explain(42))
        val twice = success(api.explain(42))

        assertEquals("MiniMax-M3 · 13 s · saved copy", once.meta)
        assertEquals(once, twice)
        assertEquals(before, File(directory, "42.json").readText())
    }

    @Test
    fun aCopyWithoutMeta_isMarkedAlone() = runTest {
        fake.explainResults += ok(done(meta = ""))
        fake.explainResults += failure(FailureKind.NETWORK)
        val api = savedApi()

        api.explain(42)

        assertEquals("Saved copy", success(api.explain(42)).meta)
    }

    // tidying

    @Test
    fun creatingTheDecorator_dropsWhatIsOlderThanNinetyDays() {
        raw("1.json", begin)
        raw("2.json", begin.plus(Duration.ofDays(10)))
        clock.advance(Duration.ofDays(90))

        savedApi()
        // Exactly ninety days old is still kept.
        assertEquals(listOf("1.json", "2.json"), names())

        clock.advance(Duration.ofSeconds(1))
        savedApi()
        assertEquals(listOf("2.json"), names())

        clock.advance(Duration.ofDays(10))
        savedApi()
        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun creatingTheDecorator_keepsTheNewestThreeHundred() {
        (1..305).forEach { raw("$it.json", begin.plusSeconds(it.toLong())) }
        clock.advance(Duration.ofHours(1))

        savedApi()

        val kept = names()
        assertEquals(300, kept.size)
        assertEquals((6..305).map { "$it.json" }.sorted(), kept)
    }

    @Test
    fun ofTwoSavedAtTheSameMoment_theLowerStoryIdGoesFirst() {
        (1..5).forEach { raw("$it.json", begin) }

        SavedExplanationStore(directory, clock, maxFiles = 3).prune()

        assertEquals(listOf("3.json", "4.json", "5.json"), names())
    }

    @Test
    fun savingMoreThanTheLimit_dropsTheOldest() = runTest {
        repeat(5) { fake.explainResults += ok(done()) }
        val api = savedApi(store(maxFiles = 3))

        (1L..5L).forEach { id ->
            api.explain(id)
            clock.advance(Duration.ofMinutes(5))
        }

        assertEquals(listOf("3.json", "4.json", "5.json"), names())
    }

    @Test
    fun aCopyThatIsSavedAgain_startsItsNinetyDaysOver() = runTest {
        fake.explainResults += ok(done())
        fake.explainResults += ok(done(html = "<p>Nouă</p>"))
        val api = savedApi()
        api.explain(42)
        clock.advance(Duration.ofDays(80))
        api.explain(42)
        clock.advance(Duration.ofDays(80))

        savedApi()

        assertEquals(listOf("42.json"), names())
    }

    @Test
    fun aCopyWhoseChatGrew_startsItsNinetyDaysOver() = runTest {
        fake.explainResults += ok(done(chat = listOf(turn("Prima"))))
        fake.askResults += ExplicaResult.Success(AskResponse(chat = listOf(turn("Prima"), turn("A doua"))))
        val api = savedApi()
        api.explain(42)
        clock.advance(Duration.ofDays(80))

        api.ask(42, "A doua")
        clock.advance(Duration.ofDays(80))

        savedApi()
        assertEquals(listOf("42.json"), names())
    }

    @Test
    fun aChatThatDidNotChange_doesNotRenewTheCopy() = runTest {
        val chat = listOf(turn("Prima"))
        fake.explainResults += ok(done(chat = chat))
        fake.askResults += ExplicaResult.Success(AskResponse(chat = chat))
        val api = savedApi()
        api.explain(42)
        clock.advance(Duration.ofDays(80))

        api.ask(42)
        clock.advance(Duration.ofDays(11))

        savedApi()
        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun leftoversOfAWriteCutShort_go_otherFilesStay() {
        raw("42.json.tmp", begin, text = "{")
        raw("notes.txt", begin, text = "mine")

        savedApi()

        assertEquals(listOf("notes.txt"), names())
    }

    // trouble

    @Test
    fun aFileThatIsNotAnExplanation_isNotACopy_andIsRemoved() = runTest {
        raw("42.json", begin, text = "not json")
        raw("43.json", begin, text = """{"status":"idle","title":"Drona"}""")
        raw("44.json", begin, text = """{"status":"done","html_app":""}""")
        val delegate = FakeApi().apply { repeat(3) { explainResults += failure(FailureKind.NETWORK, "timeout") } }
        val api = SavedExplanationsApi(delegate, store())

        listOf(42L, 43L, 44L).forEach { id ->
            assertEquals(id.toString(), failure(FailureKind.NETWORK, "timeout"), api.explain(id))
        }

        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun aFolderThatCantBeUsed_doesNotBreakTheCalls() = runTest {
        tmp.root.mkdirs()
        File(directory.path).writeText("a file where the folder should be")
        fake.explainResults += ok(done())
        fake.explainResults += failure(FailureKind.NETWORK, "timeout")
        fake.askResults += ExplicaResult.Success(AskResponse(chat = listOf(turn("Prima"))))

        val api = savedApi()
        val explained = api.explain(42)
        val failed = api.explain(42)
        val asked = api.ask(42, "Prima")

        assertTrue(explained is ExplicaResult.Success)
        assertEquals(failure(FailureKind.NETWORK, "timeout"), failed)
        assertTrue(asked is ExplicaResult.Success)
    }

    @Test
    fun aStoryWithoutAnEntryId_isNeverSaved() = runTest {
        fake.explainResults += ok(done())
        fake.explainResults += ok(done())

        savedApi().explain(0)
        savedApi().explain(-5)

        assertEquals(emptyList<String>(), names())
    }

    @Test
    fun theStoreAlone_writesReadsAndForgets() {
        val store = store()

        assertNull(store.read(1))
        store.write(1, done())
        assertEquals("<p>Explicația</p>", store.read(1)!!.htmlApp)
        assertNotEquals("", store.read(1)!!.title)

        store.updateChat(1, listOf(turn("Prima")))
        assertEquals(listOf("Prima"), store.read(1)!!.chat.map { it.q })

        store.updateChat(2, listOf(turn("Prima")))
        assertNull(store.read(2))
    }
}
