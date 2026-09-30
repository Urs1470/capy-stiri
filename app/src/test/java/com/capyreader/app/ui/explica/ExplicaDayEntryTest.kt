package com.capyreader.app.ui.explica

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The entry of the day ("00 Azi"): no explanation to start, other chips, questions answered from the day's stories. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExplicaDayEntryTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // the wire

    private fun client(body: String): ExplicaClient {
        val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("test")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        return ExplicaClient(http = http, baseUrl = "https://stiri.example/explica/", token = { "the-token" })
    }

    @Test
    fun explain_readsTheDayFlag() = runTest {
        val day = client("""{"status":"idle","title":"Rezumatul zilei","day":true,"suggest":[],"chat":[]}""")
            .explain(entryId = 1, start = false) as ExplicaResult.Success
        val story = client("""{"status":"idle","title":"Drona","suggest":[],"chat":[]}""")
            .explain(entryId = 2, start = false) as ExplicaResult.Success
        val explicitlyNot = client("""{"status":"idle","title":"Drona","day":false}""")
            .explain(entryId = 2, start = false) as ExplicaResult.Success
        val nullFlag = client("""{"status":"idle","title":"Drona","day":null}""")
            .explain(entryId = 2, start = false) as ExplicaResult.Success

        assertTrue(day.value.day)
        assertFalse(story.value.day)
        assertFalse(explicitlyNot.value.day)
        assertFalse(nullFlag.value.day)
    }

    // the chips

    @Test
    fun theDayChips_areTheFourFixedQuestions_inOrder_withinTheServersLimit() {
        assertEquals(
            listOf(
                "What are the most important stories today, in order?",
                "Which of today's stories matter most for Moldova, and why?",
                "What happened today in the economy and the markets, and what does it mean for savings?",
                "What should I follow in the coming days, based on today's stories?",
            ),
            DayQuestion.entries.map { it.question },
        )
        assertEquals(
            listOf(
                DayQuestion.TOP_STORIES,
                DayQuestion.FOR_MOLDOVA,
                DayQuestion.ECONOMY_AND_MARKETS,
                DayQuestion.WHAT_TO_WATCH,
            ),
            DayQuestion.entries,
        )
        DayQuestion.entries.forEach {
            assertTrue(it.name, it.question.length <= ExplicaViewModel.MAX_QUESTION_LENGTH)
            assertEquals(it.name, it.question.trim(), it.question)
        }
    }

    @Test
    fun theDayChips_carryNothingPersonal_becauseTheRepositoryIsPublic() {
        val personal = Regex("""@|https?:""", RegexOption.IGNORE_CASE)

        DayQuestion.entries.forEach { chip ->
            assertFalse(chip.name, personal.containsMatchIn(chip.question))
        }
    }

    @Test
    fun theDayChips_areDifferentFromTheStoryChips() {
        val story = QuickQuestion.entries.map { it.question }.toSet()

        assertTrue(DayQuestion.entries.none { it.question in story })
        assertEquals(
            DayQuestion.entries.size,
            DayQuestion.entries.map { it.label }.toSet().size,
        )
    }

    @Test
    fun aDayStory_showsTheDayChips_andAStoryShowsItsOwn() {
        val day = ExplicaState(phase = ExplicaPhase.IDLE, day = true)
        val story = ExplicaState(phase = ExplicaPhase.IDLE)

        assertEquals(DayQuestion.entries.toList(), day.quickQuestions)
        assertEquals(QuickQuestion.entries.toList(), story.quickQuestions)
    }

    @Test
    fun theDayChips_showWhereTheStoryChipsDo_andAreHiddenLikeThemOnceAsked() {
        val shown = setOf(ExplicaPhase.IDLE, ExplicaPhase.LOADING, ExplicaPhase.READY)

        ExplicaPhase.entries.forEach { phase ->
            assertEquals(phase.name, phase in shown, ExplicaState(phase = phase, day = true).quickQuestions.isNotEmpty())
        }

        val asked = listOf(ExplicaTurn(q = DayQuestion.FOR_MOLDOVA.question, status = STATUS_DONE, htmlApp = "<p>x</p>"))
        assertEquals(
            listOf(DayQuestion.TOP_STORIES, DayQuestion.ECONOMY_AND_MARKETS, DayQuestion.WHAT_TO_WATCH),
            ExplicaState(phase = ExplicaPhase.IDLE, day = true, turns = asked).quickQuestions,
        )
        assertTrue(
            ExplicaState(
                phase = ExplicaPhase.IDLE,
                day = true,
                turns = DayQuestion.entries.map { ExplicaTurn(q = it.question, status = STATUS_DONE) },
            ).quickQuestions.isEmpty()
        )
    }

    @Test
    fun aDayStoryAlreadyAskedAStoryChip_keepsAllItsDayChips() {
        val asked = listOf(ExplicaTurn(q = QuickQuestion.SUMMARY.question, status = STATUS_DONE))

        assertEquals(
            DayQuestion.entries.toList(),
            ExplicaState(phase = ExplicaPhase.IDLE, day = true, turns = asked).quickQuestions,
        )
    }

    // the Explain chip

    @Test
    fun theExplainChip_isOfferedInIdle_exceptForTheDay() {
        ExplicaPhase.entries.forEach { phase ->
            assertEquals(phase.name, phase == ExplicaPhase.IDLE, ExplicaState(phase = phase).offersExplain)
            assertFalse(phase.name, ExplicaState(phase = phase, day = true).offersExplain)
            assertFalse(phase.name, ExplicaState(phase = phase, day = true).canStartExplanation)
        }

        assertTrue(ExplicaState(phase = ExplicaPhase.IDLE).canStartExplanation)
        assertFalse(ExplicaState(phase = ExplicaPhase.IDLE, asking = true).canStartExplanation)
    }

    // the view model

    private class FakeApi : ExplicaApi {
        val explainStartFlags = mutableListOf<Boolean>()
        val questions = mutableListOf<String?>()
        val explainResults = ArrayDeque<ExplicaResult<ExplainResponse>>()
        val askResults = ArrayDeque<ExplicaResult<AskResponse>>()

        override suspend fun explain(entryId: Long, retry: Boolean, start: Boolean): ExplicaResult<ExplainResponse> {
            explainStartFlags += start
            check(explainResults.isNotEmpty()) { "explain() called more often than scripted" }

            return explainResults.removeFirst()
        }

        override suspend fun ask(entryId: Long, question: String?): ExplicaResult<AskResponse> {
            questions += question
            check(askResults.isNotEmpty()) { "ask() called more often than scripted" }

            return askResults.removeFirst()
        }
    }

    private fun dayIdle(chat: List<ExplicaTurn> = emptyList()) = ExplicaResult.Success(
        ExplainResponse(status = "idle", title = "Rezumatul zilei", day = true, chat = chat)
    )

    private fun viewModel(api: FakeApi) = ExplicaViewModel(api, entryId = 42, pollMillis = 1_000, retryMillis = 3_000)

    @Test
    fun openingTheDayEntry_marksTheState_hidesExplain_andShowsTheDayChips() = runTest(dispatcher) {
        val api = FakeApi().apply { explainResults += dayIdle() }

        val vm = viewModel(api)
        assertFalse(vm.state.value.day)
        assertTrue(vm.state.value.quickQuestions.isEmpty())
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(ExplicaPhase.IDLE, state.phase)
        assertTrue(state.day)
        assertFalse(state.offersExplain)
        assertFalse(state.canStartExplanation)
        assertEquals(DayQuestion.entries.toList(), state.quickQuestions)
        assertTrue(state.canAskQuick)
        assertEquals(listOf(false), api.explainStartFlags)
    }

    @Test
    fun explain_isIgnoredOnTheDayEntry_soTheServerIsNeverAskedToStartIt() = runTest(dispatcher) {
        val api = FakeApi().apply { explainResults += dayIdle() }

        val vm = viewModel(api)
        advanceUntilIdle()
        vm.explain()
        advanceUntilIdle()

        assertEquals(ExplicaPhase.IDLE, vm.state.value.phase)
        assertEquals(listOf(false), api.explainStartFlags)
    }

    @Test
    fun aDayChip_isSentAsAnOrdinaryQuestion_andItsChipGoesAway() = runTest(dispatcher) {
        val question = DayQuestion.TOP_STORIES.question
        val api = FakeApi().apply {
            explainResults += dayIdle()
            askResults += ExplicaResult.Success(AskResponse(chat = listOf(ExplicaTurn(question, STATUS_RUNNING))))
            askResults += ExplicaResult.Success(
                AskResponse(chat = listOf(ExplicaTurn(question, STATUS_DONE, htmlApp = "<p>1. Bugetul</p>")))
            )
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        vm.setDraft("ceva început")

        vm.ask(question)
        runCurrent()
        assertEquals(question, vm.state.value.turns.single().q)
        assertEquals(DayQuestion.entries - DayQuestion.TOP_STORIES, vm.state.value.quickQuestions)
        assertFalse(vm.state.value.canAskQuick)

        advanceUntilIdle()
        assertEquals(listOf<String?>(question, null), api.questions)
        assertEquals("ceva început", vm.state.value.draft)
        assertEquals("<p>1. Bugetul</p>", vm.state.value.turns.single().htmlApp)
        assertEquals(DayQuestion.entries - DayQuestion.TOP_STORIES, vm.state.value.quickQuestions)
        assertTrue(vm.state.value.day)
        assertEquals(listOf(false), api.explainStartFlags)
    }

    @Test
    fun aDayChipAskedInAnEarlierVisit_staysHiddenWhenTheScreenOpensAgain() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += dayIdle(
                chat = listOf(
                    ExplicaTurn(DayQuestion.FOR_MOLDOVA.question, STATUS_DONE, htmlApp = "<p>r</p>"),
                    ExplicaTurn(DayQuestion.WHAT_TO_WATCH.question, STATUS_DONE, htmlApp = "<p>r</p>"),
                )
            )
        }

        val vm = viewModel(api)
        advanceUntilIdle()

        assertEquals(
            listOf(DayQuestion.TOP_STORIES, DayQuestion.ECONOMY_AND_MARKETS),
            vm.state.value.quickQuestions,
        )
    }

    @Test
    fun aRejectedDayChip_bringsItsChipBack() = runTest(dispatcher) {
        val question = DayQuestion.ECONOMY_AND_MARKETS.question
        val api = FakeApi().apply {
            explainResults += dayIdle()
            askResults += ExplicaResult.Failure(FailureKind.RATE_LIMITED, "Ai atins limita de azi.")
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        vm.ask(question)
        advanceUntilIdle()

        assertTrue(vm.state.value.turns.isEmpty())
        assertEquals(DayQuestion.entries.toList(), vm.state.value.quickQuestions)
        assertEquals("Ai atins limita de azi.", vm.state.value.askFailure?.message)
    }

    @Test
    fun aStory_isNotTheDay_soNothingChanges() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += ExplicaResult.Success(ExplainResponse(status = "idle", title = "Drona"))
            explainResults += ExplicaResult.Success(ExplainResponse(status = "done", title = "Drona", htmlApp = "<p>x</p>"))
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        assertFalse(vm.state.value.day)
        assertTrue(vm.state.value.offersExplain)
        assertEquals(QuickQuestion.entries.toList(), vm.state.value.quickQuestions)

        vm.explain()
        advanceUntilIdle()
        assertEquals(ExplicaPhase.READY, vm.state.value.phase)
        assertEquals(listOf(false, true), api.explainStartFlags)
    }

    @Test
    fun onceTheDayIsKnown_aLaterAnswerWithoutTheFlagDoesNotUndoIt() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += ExplicaResult.Failure(FailureKind.SERVER, "")
            explainResults += dayIdle()
            explainResults += ExplicaResult.Success(ExplainResponse(status = "idle", title = "Rezumatul zilei"))
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        assertFalse(vm.state.value.day)

        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.state.value.day)

        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.state.value.day)
    }
}
