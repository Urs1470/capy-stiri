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
        val questions = mutableListOf<String?>()
        val explainResults = ArrayDeque<ExplicaResult<ExplainResponse>>()
        val askResults = ArrayDeque<ExplicaResult<AskResponse>>()

        override suspend fun explain(entryId: Long, retry: Boolean): ExplicaResult<ExplainResponse> {
            explainRetryFlags += retry
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

    private fun turn(q: String, status: String, html: String = "", partial: String = "", stage: String = "") =
        ExplicaTurn(q = q, status = status, htmlApp = html, partialApp = partial, stage = stage)

    private fun TestScope.viewModel(api: FakeApi, entryId: Long = 42) =
        ExplicaViewModel(api, entryId, pollMillis = 1_000, retryMillis = 3_000)

    @Test
    fun explanation_showsProgress_thenTheResult() = runTest(dispatcher) {
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
    fun unauthorized_failsAtOnce_andRetryStartsAgain() = runTest(dispatcher) {
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
        advanceUntilIdle()
        assertEquals(ExplicaPhase.READY, vm.state.value.phase)
        assertNull(vm.state.value.failure)
        assertEquals(listOf(false, true), api.explainRetryFlags)
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
}
