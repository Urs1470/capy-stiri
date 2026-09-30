package com.capyreader.app.ui.explica

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The screen's view model over the saved explanations, the way the app builds them: a story that was explained once
 * opens from its copy when the server can't be reached, and from the server's answer whenever it answers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedExplanationsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeServer : ExplicaApi {
        val explainStartFlags = mutableListOf<Boolean>()
        val explainResults = ArrayDeque<ExplicaResult<ExplainResponse>>()
        val askResults = ArrayDeque<ExplicaResult<AskResponse>>()

        override suspend fun explain(entryId: Long, retry: Boolean, start: Boolean): ExplicaResult<ExplainResponse> {
            explainStartFlags += start
            check(explainResults.isNotEmpty()) { "explain() called more often than scripted" }

            return explainResults.removeFirst()
        }

        override suspend fun ask(entryId: Long, question: String?): ExplicaResult<AskResponse> {
            check(askResults.isNotEmpty()) { "ask() called more often than scripted" }

            return askResults.removeFirst()
        }
    }

    private val server = FakeServer()

    /** The decorator on the test dispatcher, so that its file work is part of the test's own time. */
    private fun savedApi() = SavedExplanationsApi(
        delegate = server,
        store = SavedExplanationStore(File(tmp.root, SAVED_EXPLANATIONS_DIRECTORY)),
        io = dispatcher,
    )

    private fun viewModel(api: ExplicaApi) = ExplicaViewModel(api, entryId = 42, pollMillis = 1_000, retryMillis = 3_000)

    private fun done(chat: List<ExplicaTurn> = emptyList()) = ExplicaResult.Success(
        ExplainResponse(
            status = STATUS_DONE,
            title = "Drona",
            link = "https://ipn.md/a",
            htmlApp = "<p>Explicația</p>",
            meta = "MiniMax-M3 · 13 s",
            suggest = listOf("Ce urmează?"),
            chat = chat,
        )
    )

    private fun answered(q: String) = ExplicaTurn(q = q, status = STATUS_DONE, htmlApp = "<p>r</p>", meta = "m")

    @Test
    fun aStoryExplainedOnce_opensFromItsCopy_whenThereIsNoConnection() = runTest(dispatcher) {
        server.explainResults += done(chat = listOf(answered("Ce e drona?")))
        server.explainResults += ExplicaResult.Failure(FailureKind.NETWORK, "Unable to resolve host")
        val api = savedApi()

        val online = viewModel(api)
        advanceUntilIdle()
        assertEquals(ExplicaPhase.READY, online.state.value.phase)
        assertEquals("MiniMax-M3 · 13 s", online.state.value.meta)

        val offline = viewModel(api)
        advanceUntilIdle()

        val state = offline.state.value
        assertEquals(ExplicaPhase.READY, state.phase)
        assertNull(state.failure)
        assertEquals("<p>Explicația</p>", state.explanationHtml)
        assertEquals("MiniMax-M3 · 13 s · saved copy", state.meta)
        assertEquals("Drona", state.title)
        assertEquals("https://ipn.md/a", state.articleUrl)
        assertEquals(listOf("Ce urmează?"), state.suggestions)
        assertEquals(listOf("Ce e drona?"), state.turns.map { it.q })
        assertFalse(state.asking)
        // One read-only call on each open; the copy answered the second at once, without the five tries.
        assertEquals(listOf(false, false), server.explainStartFlags)
    }

    @Test
    fun aStoryTheServerForgot_opensFromItsCopy_onA404() = runTest(dispatcher) {
        server.explainResults += done()
        server.explainResults += ExplicaResult.Failure(FailureKind.NOT_FOUND, "Nu e o știre din rezumat.")
        val api = savedApi()

        viewModel(api)
        advanceUntilIdle()
        val later = viewModel(api)
        advanceUntilIdle()

        assertEquals(ExplicaPhase.READY, later.state.value.phase)
        assertTrue(later.state.value.meta.endsWith("saved copy"))
    }

    @Test
    fun aStoryWithoutACopy_failsAsBefore_afterTheRetriesOfANetworkFailure() = runTest(dispatcher) {
        repeat(ExplicaViewModel.MAX_NETWORK_FAILURES) {
            server.explainResults += ExplicaResult.Failure(FailureKind.NETWORK, "timeout")
        }

        val vm = viewModel(savedApi())
        advanceUntilIdle()

        assertEquals(ExplicaPhase.FAILED, vm.state.value.phase)
        assertEquals(FailureKind.NETWORK, vm.state.value.failure?.kind)
        assertEquals(ExplicaViewModel.MAX_NETWORK_FAILURES, server.explainStartFlags.size)
    }

    @Test
    fun whenTheServerAnswers_thatIsWhatTheScreenShows_evenWithACopy() = runTest(dispatcher) {
        server.explainResults += done()
        server.explainResults += ExplicaResult.Success(ExplainResponse(status = STATUS_IDLE, title = "Drona"))
        val api = savedApi()

        viewModel(api)
        advanceUntilIdle()
        val later = viewModel(api)
        advanceUntilIdle()

        assertEquals(ExplicaPhase.IDLE, later.state.value.phase)
        assertEquals("", later.state.value.explanationHtml)
    }

    @Test
    fun aQuestionAsked_isInTheCopyTheNextTimeTheStoryIsOpenedOffline() = runTest(dispatcher) {
        server.explainResults += done()
        server.askResults += ExplicaResult.Success(AskResponse(chat = listOf(answered("Ce e drona?"))))
        server.explainResults += ExplicaResult.Failure(FailureKind.NETWORK, "Unable to resolve host")
        val api = savedApi()

        val online = viewModel(api)
        advanceUntilIdle()
        online.ask("Ce e drona?")
        advanceUntilIdle()
        assertEquals(1, online.state.value.turns.size)

        val offline = viewModel(api)
        advanceUntilIdle()

        assertEquals(ExplicaPhase.READY, offline.state.value.phase)
        assertEquals(listOf("Ce e drona?"), offline.state.value.turns.map { it.q })
        assertEquals("<p>r</p>", offline.state.value.turns.single().htmlApp)
    }
}
