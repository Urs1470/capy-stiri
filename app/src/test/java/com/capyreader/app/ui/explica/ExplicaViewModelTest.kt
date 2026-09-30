package com.capyreader.app.ui.explica

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExplicaViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeApi : ExplicaApi {
        val explainRetryFlags = mutableListOf<Boolean>()
        val explainStartFlags = mutableListOf<Boolean>()
        val questions = mutableListOf<String?>()
        val explainResults = ArrayDeque<ExplicaResult<ExplainResponse>>()
        val askResults = ArrayDeque<ExplicaResult<AskResponse>>()

        override suspend fun explain(entryId: Long, retry: Boolean, start: Boolean): ExplicaResult<ExplainResponse> {
            explainRetryFlags += retry
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

    private fun running(stage: String = "", partial: String = "") =
        ExplicaResult.Success(ExplainResponse(status = "running", title = "Drona", stage = stage, partialApp = partial))

    private fun done(
        suggest: List<String> = emptyList(),
        chat: List<ExplicaTurn> = emptyList(),
    ) = ExplicaResult.Success(
        ExplainResponse(
            status = "done",
            title = "Drona",
            link = "https://ipn.md/a",
            htmlApp = "<p>Explicația</p>",
            meta = "MiniMax-M3 · 13 s",
            suggest = suggest,
            chat = chat,
        )
    )

    /** What a read-only `explain` (`start = false`) answers when nothing was asked for yet. */
    private fun idle(chat: List<ExplicaTurn> = emptyList()) = ExplicaResult.Success(
        ExplainResponse(status = "idle", title = "Drona", link = "https://ipn.md/a", chat = chat)
    )

    private fun turn(q: String, status: String, html: String = "", partial: String = "", stage: String = "") =
        ExplicaTurn(q = q, status = status, htmlApp = html, partialApp = partial, stage = stage)

    private fun TestScope.viewModel(api: FakeApi, entryId: Long = 42) =
        ExplicaViewModel(api, entryId, pollMillis = 1_000, retryMillis = 3_000)

    @Test
    fun opening_anExplanationAlreadyBeingWritten_isFollowedUntilItIsDone() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += running(stage = "citesc articolul")
            explainResults += running(stage = "scriu explicația", partial = "<p>par</p>")
            explainResults += done(suggest = listOf("Ce urmează?", "Cine e?"))
        }

        val vm = viewModel(api)
        runCurrent()

        assertEquals(ExplicaPhase.LOADING, vm.state.value.phase)
        assertEquals("citesc articolul", vm.state.value.stage)
        assertEquals("Drona", vm.state.value.title)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("<p>par</p>", vm.state.value.explanationHtml)
        assertEquals("scriu explicația", vm.state.value.stage)

        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(ExplicaPhase.READY, state.phase)
        assertEquals("<p>Explicația</p>", state.explanationHtml)
        assertEquals("https://ipn.md/a", state.articleUrl)
        assertEquals("MiniMax-M3 · 13 s", state.meta)
        assertEquals(listOf("Ce urmează?", "Cine e?"), state.suggestions)
        assertEquals("", state.stage)
        assertEquals(listOf(false, false, false), api.explainRetryFlags)
        // Opening never starts an explanation, not even while it follows one somebody else started.
        assertEquals(listOf(false, false, false), api.explainStartFlags)
    }

    @Test
    fun opening_aStoryWithoutAnExplanation_generatesNothing_andIsIdle() = runTest(dispatcher) {
        val api = FakeApi().apply { explainResults += idle() }

        val vm = viewModel(api)
        assertEquals(ExplicaPhase.OPENING, vm.state.value.phase)
        assertFalse(vm.state.value.canSend)

        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(ExplicaPhase.IDLE, state.phase)
        assertEquals("Drona", state.title)
        assertEquals("https://ipn.md/a", state.articleUrl)
        assertEquals("", state.explanationHtml)
        assertEquals("", state.stage)
        assertTrue(state.turns.isEmpty())
        assertFalse(state.asking)
        assertNull(state.failure)
        assertEquals(listOf(false), api.explainStartFlags)
        assertEquals(listOf(false), api.explainRetryFlags)

        // Idle is final: no polling and no second call, however long the screen stays open.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, api.explainStartFlags.size)
        assertTrue(api.questions.isEmpty())
    }

    @Test
    fun opening_aFinishedExplanation_isShownWithItsSuggestionsAndChat() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += done(
                suggest = listOf("What happens next?"),
                chat = listOf(turn("What is a drone?", "done", html = "<p>An aircraft.</p>")),
            )
        }

        val vm = viewModel(api)
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(ExplicaPhase.READY, state.phase)
        assertTrue(state.explanationHtml.isNotBlank())
        assertEquals(listOf("What happens next?"), state.suggestions)
        assertEquals("What is a drone?", state.turns.single().q)
        assertEquals("<p>An aircraft.</p>", state.turns.single().htmlApp)
        assertFalse(state.asking)
        assertEquals(listOf(false), api.explainStartFlags)
    }

    @Test
    fun theExplainShortcut_startsTheExplanation_showsProgress_thenTheResult() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += idle()
            explainResults += running(stage = "reading the article")
            explainResults += running(stage = "writing", partial = "<p>part</p>")
            explainResults += done(suggest = listOf("What happens next?", "Who is involved?"))
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        assertEquals(ExplicaPhase.IDLE, vm.state.value.phase)
        assertTrue(vm.state.value.canStartExplanation)

        vm.explain()
        assertEquals(ExplicaPhase.LOADING, vm.state.value.phase)
        assertFalse(vm.state.value.canStartExplanation)
        runCurrent()
        assertEquals("reading the article", vm.state.value.stage)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("<p>part</p>", vm.state.value.explanationHtml)
        assertEquals("writing", vm.state.value.stage)

        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(ExplicaPhase.READY, state.phase)
        assertEquals(listOf("What happens next?", "Who is involved?"), state.suggestions)
        assertEquals("", state.stage)
        // One read-only call on open, then every call of the explanation asks the server to start it.
        assertEquals(listOf(false, true, true, true), api.explainStartFlags)
        assertEquals(listOf(false, false, false, false), api.explainRetryFlags)
    }

    @Test
    fun aStoryWithoutAnEntryId_failsWithoutCallingTheServer() = runTest(dispatcher) {
        val api = FakeApi()

        val vm = viewModel(api, entryId = 0)
        advanceUntilIdle()

        assertEquals(ExplicaPhase.FAILED, vm.state.value.phase)
        assertEquals(FailureKind.NOT_FOUND, vm.state.value.failure?.kind)
        assertTrue(api.explainRetryFlags.isEmpty())
    }

    @Test
    fun aFailedOpen_failsAtOnce_andTryAgainReadsTheStateAgainWithoutStartingAnything() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += ExplicaResult.Failure(FailureKind.UNAUTHORIZED, "Cheia Miniflux nu e recunoscută.")
            explainResults += done()
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        assertEquals(ExplicaPhase.FAILED, vm.state.value.phase)
        assertEquals(FailureKind.UNAUTHORIZED, vm.state.value.failure?.kind)
        assertEquals(1, api.explainRetryFlags.size)

        vm.retry()
        assertEquals(ExplicaPhase.OPENING, vm.state.value.phase)
        advanceUntilIdle()
        assertEquals(ExplicaPhase.READY, vm.state.value.phase)
        assertNull(vm.state.value.failure)
        // Nothing was asked for yet, so there is nothing to write again: Try again is another read-only call.
        assertEquals(listOf(false, false), api.explainRetryFlags)
        assertEquals(listOf(false, false), api.explainStartFlags)
    }

    @Test
    fun aFailedOpen_thenTryAgain_canFindAnIdleStory() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += ExplicaResult.Failure(FailureKind.SERVER, "")
            explainResults += idle()
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        vm.setDraft("What is a drone?")
        assertEquals(ExplicaPhase.FAILED, vm.state.value.phase)
        assertFalse(vm.state.value.canSend)

        vm.retry()
        advanceUntilIdle()

        assertEquals(ExplicaPhase.IDLE, vm.state.value.phase)
        assertNull(vm.state.value.failure)
        assertTrue(vm.state.value.canSend)
        assertEquals(listOf(false, false), api.explainStartFlags)
    }

    @Test
    fun anErrorFromTheServer_isShownWithItsMessage() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += ExplicaResult.Success(
                ExplainResponse(status = "error", title = "Drona", error = "Explicația n-a ieșit: HTTP 529")
            )
        }

        val vm = viewModel(api)
        advanceUntilIdle()

        assertEquals(ExplicaPhase.FAILED, vm.state.value.phase)
        assertEquals("Explicația n-a ieșit: HTTP 529", vm.state.value.failure?.message)
        assertEquals("Drona", vm.state.value.title)
    }

    @Test
    fun anErrorFoundOnOpen_isRetriedByWritingTheExplanationAgain() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += ExplicaResult.Success(ExplainResponse(status = "error", error = "It failed."))
            explainResults += done()
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        assertEquals(ExplicaPhase.FAILED, vm.state.value.phase)

        vm.retry()
        assertEquals(ExplicaPhase.LOADING, vm.state.value.phase)
        advanceUntilIdle()

        assertEquals(ExplicaPhase.READY, vm.state.value.phase)
        assertEquals(listOf(false, true), api.explainStartFlags)
        assertEquals(listOf(false, true), api.explainRetryFlags)
    }

    @Test
    fun aFailedExplanation_isRetriedByWritingItAgain() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += idle()
            explainResults += ExplicaResult.Success(
                ExplainResponse(status = "error", title = "Drona", error = "It failed.")
            )
            explainResults += done()
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        vm.explain()
        advanceUntilIdle()
        assertEquals(ExplicaPhase.FAILED, vm.state.value.phase)
        assertEquals("It failed.", vm.state.value.failure?.message)

        vm.retry()
        assertEquals(ExplicaPhase.LOADING, vm.state.value.phase)
        advanceUntilIdle()

        assertEquals(ExplicaPhase.READY, vm.state.value.phase)
        assertNull(vm.state.value.failure)
        assertEquals(listOf(false, true, true), api.explainStartFlags)
        assertEquals(listOf(false, false, true), api.explainRetryFlags)
    }

    @Test
    fun aDroppedConnectionWhileTheExplanationIsWritten_isRetriedByWritingItAgain() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += idle()
            repeat(ExplicaViewModel.MAX_NETWORK_FAILURES) {
                explainResults += ExplicaResult.Failure(FailureKind.NETWORK, "timeout")
            }
            explainResults += done()
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        vm.explain()
        advanceUntilIdle()
        assertEquals(ExplicaPhase.FAILED, vm.state.value.phase)
        assertEquals(FailureKind.NETWORK, vm.state.value.failure?.kind)

        vm.retry()
        advanceUntilIdle()

        assertEquals(ExplicaPhase.READY, vm.state.value.phase)
        assertTrue(api.explainStartFlags.last())
        assertTrue(api.explainRetryFlags.last())
    }

    @Test
    fun networkFailures_areRetriedThenGiveUp() = runTest(dispatcher) {
        val api = FakeApi().apply {
            repeat(ExplicaViewModel.MAX_NETWORK_FAILURES) {
                explainResults += ExplicaResult.Failure(FailureKind.NETWORK, "timeout")
            }
        }

        val vm = viewModel(api)
        advanceUntilIdle()

        assertEquals(ExplicaPhase.FAILED, vm.state.value.phase)
        assertEquals(FailureKind.NETWORK, vm.state.value.failure?.kind)
        assertEquals(ExplicaViewModel.MAX_NETWORK_FAILURES, api.explainRetryFlags.size)
        // The open call keeps the retry loop, and every attempt stays read-only.
        assertTrue(api.explainStartFlags.none { it })
    }

    @Test
    fun aNetworkBlip_isSurvived() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += ExplicaResult.Failure(FailureKind.NETWORK, "timeout")
            explainResults += running(stage = "caut context pe web")
            explainResults += done()
        }

        val vm = viewModel(api)
        advanceUntilIdle()

        assertEquals(ExplicaPhase.READY, vm.state.value.phase)
    }

    @Test
    fun aRunningAnswerFoundOnOpen_isPolledUntilDone() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += done(chat = listOf(turn("Ce e drona?", "running", partial = "<p>p</p>")))
            askResults += ExplicaResult.Success(AskResponse(chat = listOf(turn("Ce e drona?", "running", partial = "<p>pa</p>"))))
            askResults += ExplicaResult.Success(AskResponse(chat = listOf(turn("Ce e drona?", "done", html = "<p>gata</p>"))))
        }

        val vm = viewModel(api)
        runCurrent()
        assertTrue(vm.state.value.asking)

        advanceUntilIdle()
        assertFalse(vm.state.value.asking)
        assertEquals("<p>gata</p>", vm.state.value.turns.single().htmlApp)
        assertEquals(listOf<String?>(null, null), api.questions)
    }

    @Test
    fun asking_showsTheQuestionAtOnce_clearsTheInput_andPollsTheAnswer() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += done(suggest = listOf("Ce urmează?", "Cine e?"))
            askResults += ExplicaResult.Success(AskResponse(chat = listOf(turn("Ce urmează?", "running"))))
            askResults += ExplicaResult.Success(AskResponse(chat = listOf(turn("Ce urmează?", "running", partial = "<p>p</p>"))))
            askResults += ExplicaResult.Success(AskResponse(chat = listOf(turn("Ce urmează?", "done", html = "<p>r</p>"))))
        }

        val vm = viewModel(api)
        advanceUntilIdle()

        vm.setDraft("Ce urmează?")
        assertTrue(vm.state.value.canSend)
        vm.send()
        runCurrent()

        assertEquals("", vm.state.value.draft)
        assertTrue(vm.state.value.asking)
        assertEquals("Ce urmează?", vm.state.value.turns.single().q)
        assertEquals(listOf("Cine e?"), vm.state.value.suggestions)

        advanceUntilIdle()
        assertFalse(vm.state.value.asking)
        assertEquals("<p>r</p>", vm.state.value.turns.single().htmlApp)
        assertEquals(listOf<String?>("Ce urmează?", null, null), api.questions)
    }

    @Test
    fun aSuggestionIsAskedWithoutTouchingTheDraft() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += done(suggest = listOf("Ce urmează?"))
            askResults += ExplicaResult.Success(AskResponse(chat = listOf(turn("Ce urmează?", "done", html = "<p>r</p>"))))
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        vm.setDraft("ceva început")

        vm.ask("Ce urmează?")
        advanceUntilIdle()

        assertEquals("ceva început", vm.state.value.draft)
        assertTrue(vm.state.value.suggestions.isEmpty())
        assertEquals("<p>r</p>", vm.state.value.turns.single().htmlApp)
    }

    @Test
    fun aRejectedQuestion_isDropped_andTheDraftComesBack() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += done()
            askResults += ExplicaResult.Failure(FailureKind.UNAUTHORIZED, "Cheia Miniflux nu e recunoscută.")
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        vm.setDraft("Ce e drona?")

        vm.send()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.turns.isEmpty())
        assertEquals("Ce e drona?", state.draft)
        assertEquals(FailureKind.UNAUTHORIZED, state.askFailure?.kind)
        assertFalse(state.asking)
    }

    @Test
    fun busyServer_showsTheChatItAlreadyHas_andWaitsForIt() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += done()
            askResults += ExplicaResult.Failure(
                FailureKind.BUSY,
                "Aștept încă răspunsul la întrebarea de dinainte.",
                chat = listOf(turn("Prima", "running")),
            )
            askResults += ExplicaResult.Success(AskResponse(chat = listOf(turn("Prima", "done", html = "<p>1</p>"))))
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        vm.setDraft("A doua")

        vm.send()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("Prima", state.turns.single().q)
        assertEquals("<p>1</p>", state.turns.single().htmlApp)
        assertEquals("A doua", state.draft)
        assertEquals(FailureKind.BUSY, state.askFailure?.kind)
        assertFalse(state.asking)
    }

    @Test
    fun aPollThatGivesUp_turnsTheRunningAnswerIntoAnError() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += done()
            askResults += ExplicaResult.Success(AskResponse(chat = listOf(turn("Ce e drona?", "running"))))
            repeat(ExplicaViewModel.MAX_NETWORK_FAILURES) {
                askResults += ExplicaResult.Failure(FailureKind.NETWORK, "timeout")
            }
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        vm.setDraft("Ce e drona?")

        vm.send()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(STATUS_ERROR, state.turns.single().status)
        assertFalse(state.asking)
        assertEquals(FailureKind.NETWORK, state.askFailure?.kind)
    }

    @Test
    fun sending_isIgnoredWhenNotReady_blank_orWhileAnAnswerIsWritten() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += running()
            explainResults += done()
            askResults += ExplicaResult.Success(AskResponse(chat = listOf(turn("Întrebare", "running"))))
            askResults += ExplicaResult.Success(AskResponse(chat = listOf(turn("Întrebare", "done", html = "<p>r</p>"))))
        }

        val vm = viewModel(api)
        runCurrent()
        vm.setDraft("Prea devreme")
        vm.send()
        runCurrent()
        assertTrue(vm.state.value.turns.isEmpty())
        assertTrue(api.questions.isEmpty())

        advanceUntilIdle()
        vm.setDraft("   ")
        assertFalse(vm.state.value.canSend)
        vm.send()
        runCurrent()
        assertTrue(api.questions.isEmpty())

        vm.setDraft("Întrebare")
        vm.send()
        runCurrent()
        vm.setDraft("A doua, cât se scrie prima")
        vm.send()
        runCurrent()
        assertEquals(1, vm.state.value.turns.size)

        advanceUntilIdle()
        assertEquals(listOf<String?>("Întrebare", null), api.questions)
    }

    @Test
    fun theDraftIsCappedAtTheServersLimit() = runTest(dispatcher) {
        val api = FakeApi().apply { explainResults += done() }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.setDraft("a".repeat(ExplicaViewModel.MAX_QUESTION_LENGTH + 40))

        assertEquals(ExplicaViewModel.MAX_QUESTION_LENGTH, vm.state.value.draft.length)
    }

    @Test
    fun canSend_isAllowedInIdleAndReady_onlyWithATextAndNoAnswerBeingWritten() {
        val allowed = setOf(ExplicaPhase.IDLE, ExplicaPhase.READY)

        ExplicaPhase.entries.forEach { phase ->
            assertEquals(phase.name, phase in allowed, ExplicaState(phase = phase, draft = "a question").canSend)
            assertFalse(phase.name, ExplicaState(phase = phase, draft = "   ").canSend)
            assertFalse(phase.name, ExplicaState(phase = phase, draft = "a question", asking = true).canSend)
        }
    }

    @Test
    fun theExplainShortcut_isOnlyOfferedInIdle_andNotWhileAnAnswerIsWritten() {
        ExplicaPhase.entries.forEach { phase ->
            assertEquals(phase.name, phase == ExplicaPhase.IDLE, ExplicaState(phase = phase).canStartExplanation)
            assertFalse(phase.name, ExplicaState(phase = phase, asking = true).canStartExplanation)
        }
    }

    @Test
    fun aQuestionInIdle_isSentAndAnswered_withoutAnExplanation() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += idle()
            askResults += ExplicaResult.Success(AskResponse(chat = listOf(turn("What is a drone?", "running"))))
            askResults += ExplicaResult.Success(
                AskResponse(chat = listOf(turn("What is a drone?", "done", html = "<p>An aircraft.</p>")))
            )
        }

        val vm = viewModel(api)
        advanceUntilIdle()

        vm.setDraft("What is a drone?")
        assertTrue(vm.state.value.canSend)
        vm.send()
        runCurrent()

        assertEquals(ExplicaPhase.IDLE, vm.state.value.phase)
        assertEquals("", vm.state.value.draft)
        assertTrue(vm.state.value.asking)
        assertEquals("What is a drone?", vm.state.value.turns.single().q)

        advanceUntilIdle()
        assertEquals(ExplicaPhase.IDLE, vm.state.value.phase)
        assertFalse(vm.state.value.asking)
        assertEquals("<p>An aircraft.</p>", vm.state.value.turns.single().htmlApp)
        assertEquals("", vm.state.value.explanationHtml)
        assertEquals(listOf<String?>("What is a drone?", null), api.questions)
        // Asking does not start the explanation.
        assertEquals(listOf(false), api.explainStartFlags)
    }

    @Test
    fun aQuestionTypedWhileTheStoryIsOpening_isIgnored() = runTest(dispatcher) {
        val api = FakeApi().apply { explainResults += idle() }

        val vm = viewModel(api)
        vm.setDraft("Too early")
        assertFalse(vm.state.value.canSend)
        vm.send()
        advanceUntilIdle()

        assertEquals(ExplicaPhase.IDLE, vm.state.value.phase)
        assertTrue(api.questions.isEmpty())
        assertTrue(vm.state.value.turns.isEmpty())
        assertEquals("Too early", vm.state.value.draft)
    }

    @Test
    fun aRunningAnswerFoundOnOpenInIdle_isPolledUntilDone() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += idle(chat = listOf(turn("What is a drone?", "running", partial = "<p>An</p>")))
            askResults += ExplicaResult.Success(
                AskResponse(chat = listOf(turn("What is a drone?", "running", partial = "<p>An air</p>")))
            )
            askResults += ExplicaResult.Success(
                AskResponse(chat = listOf(turn("What is a drone?", "done", html = "<p>An aircraft.</p>")))
            )
        }

        val vm = viewModel(api)
        runCurrent()

        assertEquals(ExplicaPhase.IDLE, vm.state.value.phase)
        assertTrue(vm.state.value.asking)
        assertEquals("<p>An</p>", vm.state.value.turns.single().partialApp)

        advanceUntilIdle()
        assertEquals(ExplicaPhase.IDLE, vm.state.value.phase)
        assertFalse(vm.state.value.asking)
        assertEquals("<p>An aircraft.</p>", vm.state.value.turns.single().htmlApp)
        assertEquals(listOf<String?>(null, null), api.questions)
        assertEquals(listOf(false), api.explainStartFlags)
    }

    @Test
    fun questionsAskedBeforeTheExplanation_stayWhenItStarts_andWhenItIsDone() = runTest(dispatcher) {
        val asked = turn("What is a drone?", "done", html = "<p>An aircraft.</p>")
        val api = FakeApi().apply {
            explainResults += idle(chat = listOf(asked))
            explainResults += running(stage = "writing")
            explainResults += done(chat = listOf(asked))
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        assertEquals(ExplicaPhase.IDLE, vm.state.value.phase)
        assertEquals(listOf(asked), vm.state.value.turns)

        vm.explain()
        runCurrent()
        assertEquals(ExplicaPhase.LOADING, vm.state.value.phase)
        assertEquals(listOf(asked), vm.state.value.turns)

        advanceUntilIdle()
        assertEquals(ExplicaPhase.READY, vm.state.value.phase)
        assertEquals(listOf(asked), vm.state.value.turns)
        assertEquals(listOf(false, true, true), api.explainStartFlags)
    }

    @Test
    fun theDraftTypedInIdle_survivesTheExplanation() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += idle()
            explainResults += done()
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        vm.setDraft("What is a drone?")

        vm.explain()
        assertFalse(vm.state.value.canSend)
        advanceUntilIdle()

        assertEquals(ExplicaPhase.READY, vm.state.value.phase)
        assertEquals("What is a drone?", vm.state.value.draft)
        assertTrue(vm.state.value.canSend)
    }

    @Test
    fun explain_isIgnoredWhileOpening_andOnceAnExplanationExists() = runTest(dispatcher) {
        val opening = FakeApi().apply { explainResults += idle() }
        val whileOpening = viewModel(opening)
        whileOpening.explain()
        advanceUntilIdle()
        assertEquals(ExplicaPhase.IDLE, whileOpening.state.value.phase)
        assertEquals(listOf(false), opening.explainStartFlags)

        val finished = FakeApi().apply { explainResults += done() }
        val afterwards = viewModel(finished)
        advanceUntilIdle()
        afterwards.explain()
        advanceUntilIdle()
        assertEquals(ExplicaPhase.READY, afterwards.state.value.phase)
        assertEquals(listOf(false), finished.explainStartFlags)
    }

    @Test
    fun explain_isIgnoredWhileItIsBeingWritten_soATapCantStartItTwice() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += idle()
            explainResults += running(stage = "writing")
            explainResults += done()
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        vm.explain()
        vm.explain()
        runCurrent()
        vm.explain()
        advanceUntilIdle()

        assertEquals(ExplicaPhase.READY, vm.state.value.phase)
        assertEquals(listOf(false, true, true), api.explainStartFlags)
    }

    @Test
    fun explain_isIgnoredWhileAnAnswerIsWritten() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += idle(chat = listOf(turn("What is a drone?", "running")))
            askResults += ExplicaResult.Success(
                AskResponse(chat = listOf(turn("What is a drone?", "done", html = "<p>An aircraft.</p>")))
            )
        }

        val vm = viewModel(api)
        runCurrent()
        assertTrue(vm.state.value.asking)
        assertFalse(vm.state.value.canStartExplanation)

        vm.explain()
        runCurrent()
        assertEquals(ExplicaPhase.IDLE, vm.state.value.phase)
        assertEquals(listOf(false), api.explainStartFlags)

        advanceUntilIdle()
        assertFalse(vm.state.value.asking)
        assertTrue(vm.state.value.canStartExplanation)
    }
}
