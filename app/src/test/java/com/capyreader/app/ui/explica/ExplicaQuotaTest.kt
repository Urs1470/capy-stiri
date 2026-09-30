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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The daily cap the server reports with every answer: parsing it, keeping the last one, and when the screen says it. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExplicaQuotaTest {
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

    private fun client(code: Int = 200, body: String): ExplicaClient {
        val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> chain.request().reply(code, body) })
            .build()

        return ExplicaClient(http = http, baseUrl = "https://stiri.example/explica/", token = { "the-token" })
    }

    private fun Request.reply(code: Int, body: String): Response {
        return Response.Builder()
            .request(this)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    @Test
    fun explain_readsTheQuotaOfAnAnswer() = runTest {
        val api = client(body = """{"status":"idle","title":"Drone","quota":{"used":18,"cap":60}}""")

        val value = (api.explain(entryId = 1, start = false) as ExplicaResult.Success).value

        assertEquals(Quota(used = 18, cap = 60), value.quota)
        assertEquals(42, value.quota!!.left)
    }

    @Test
    fun ask_readsTheQuotaOfAnAnswer() = runTest {
        val api = client(body = """{"chat":[{"q":"Ce e drona?","status":"running"}],"quota":{"used":59,"cap":60}}""")

        val value = (api.ask(entryId = 1, question = "Ce e drona?") as ExplicaResult.Success).value

        assertEquals(Quota(used = 59, cap = 60), value.quota)
        assertEquals(1, value.quota!!.left)
        assertTrue(value.chat.single().isRunning)
    }

    @Test
    fun anAnswerOfAnOlderServer_hasNoQuota() = runTest {
        val explained = client(body = """{"status":"done","title":"Drona","html_app":"<p>x</p>"}""")
            .explain(entryId = 1) as ExplicaResult.Success
        val asked = client(body = """{"chat":[]}""").ask(entryId = 1) as ExplicaResult.Success

        assertNull(explained.value.quota)
        assertNull(asked.value.quota)
    }

    @Test
    fun theQuotaOfTheDailyCap429_comesWithTheServersOwnText() = runTest {
        val body = """{"error":"Ai atins limita de azi.","quota":{"used":60,"cap":60}}"""

        val explain = client(code = 429, body = body).explain(entryId = 1) as ExplicaResult.Failure
        val ask = client(code = 429, body = body).ask(entryId = 1, question = "Ce e drona?") as ExplicaResult.Failure

        listOf(explain, ask).forEach { failure ->
            assertEquals(FailureKind.RATE_LIMITED, failure.kind)
            assertEquals("Ai atins limita de azi.", failure.message)
            assertEquals(Quota(used = 60, cap = 60), failure.quota)
            assertEquals(0, failure.quota!!.left)
        }
    }

    @Test
    fun a429WithoutAQuota_hasNone_andOtherFailuresKeepTheirs() = runTest {
        val plain = client(code = 429, body = """{"error":"Prea multe întrebări la știrea asta."}""")
            .ask(entryId = 1, question = "Ce e drona?") as ExplicaResult.Failure
        val notJson = client(code = 502, body = "<html>Bad gateway</html>").explain(1) as ExplicaResult.Failure
        val unavailable = client(code = 503, body = """{"error":"n-am putut verifica cheia","quota":{"used":3,"cap":60}}""")
            .explain(1) as ExplicaResult.Failure

        assertNull(plain.quota)
        assertNull(notJson.quota)
        assertEquals(Quota(used = 3, cap = 60), unavailable.quota)
    }

    @Test
    fun aQuotaWithMissingOrNullParts_isStillRead() = runTest {
        val partial = client(body = """{"status":"idle","quota":{"used":5}}""").explain(1) as ExplicaResult.Success
        val nulls = client(body = """{"status":"idle","quota":{"used":null,"cap":null}}""").explain(1) as ExplicaResult.Success
        val empty = client(body = """{"status":"idle","quota":null}""").explain(1) as ExplicaResult.Success

        assertEquals(Quota(used = 5, cap = 0), partial.value.quota)
        assertEquals(Quota(used = 0, cap = 0), nulls.value.quota)
        assertNull(empty.value.quota)
    }

    @Test
    fun whatIsLeft_neverGoesBelowZero() {
        assertEquals(0, Quota(used = 61, cap = 60).left)
        assertEquals(0, Quota(used = 60, cap = 60).left)
        assertEquals(60, Quota(used = 0, cap = 60).left)
    }

    // the state

    @Test
    fun theCaption_showsOnlyWhenSixtyOrFewerAreLeft() {
        fun left(used: Int, cap: Int) = ExplicaState(phase = ExplicaPhase.IDLE, quota = Quota(used, cap)).requestsLeft

        assertEquals(60, QUOTA_CAPTION_AT_OR_BELOW)
        assertNull(left(used = 0, cap = 200))
        assertNull(left(used = 139, cap = 200))
        assertEquals(60, left(used = 140, cap = 200))
        assertEquals(42, left(used = 158, cap = 200))
        assertEquals(1, left(used = 199, cap = 200))
        assertEquals(0, left(used = 200, cap = 200))
        assertEquals(0, left(used = 230, cap = 200))
    }

    @Test
    fun theCaption_isAbsentWithoutAReportOrWithoutACap() {
        assertNull(ExplicaState(phase = ExplicaPhase.IDLE).requestsLeft)
        assertNull(ExplicaState(phase = ExplicaPhase.IDLE, quota = Quota(used = 5, cap = 0)).requestsLeft)
        assertNull(ExplicaState(phase = ExplicaPhase.IDLE, quota = Quota()).requestsLeft)
    }

    // the view model

    private class FakeApi : ExplicaApi {
        val explainResults = ArrayDeque<ExplicaResult<ExplainResponse>>()
        val askResults = ArrayDeque<ExplicaResult<AskResponse>>()

        override suspend fun explain(entryId: Long, retry: Boolean, start: Boolean): ExplicaResult<ExplainResponse> {
            check(explainResults.isNotEmpty()) { "explain() called more often than scripted" }

            return explainResults.removeFirst()
        }

        override suspend fun ask(entryId: Long, question: String?): ExplicaResult<AskResponse> {
            check(askResults.isNotEmpty()) { "ask() called more often than scripted" }

            return askResults.removeFirst()
        }
    }

    private fun idle(quota: Quota? = null) =
        ExplicaResult.Success(ExplainResponse(status = "idle", title = "Drona", quota = quota))

    private fun done(quota: Quota? = null) = ExplicaResult.Success(
        ExplainResponse(status = "done", title = "Drona", htmlApp = "<p>Explicația</p>", quota = quota)
    )

    private fun answer(status: String, quota: Quota? = null) = ExplicaResult.Success(
        AskResponse(chat = listOf(ExplicaTurn(q = "Ce e drona?", status = status, htmlApp = "<p>r</p>")), quota = quota)
    )

    private fun viewModel(api: FakeApi) = ExplicaViewModel(api, entryId = 42, pollMillis = 1_000, retryMillis = 3_000)

    @Test
    fun theQuotaOfTheOpenCall_isKept() = runTest(dispatcher) {
        val api = FakeApi().apply { explainResults += idle(quota = Quota(used = 18, cap = 60)) }

        val vm = viewModel(api)
        assertNull(vm.state.value.quota)
        advanceUntilIdle()

        assertEquals(Quota(used = 18, cap = 60), vm.state.value.quota)
        assertEquals(42, vm.state.value.requestsLeft)
    }

    @Test
    fun theQuotaOfAFinishedExplanation_isKept_andSoIsOneOfAnErrorAnswer() = runTest(dispatcher) {
        val finished = FakeApi().apply { explainResults += done(quota = Quota(used = 2, cap = 60)) }
        val vm = viewModel(finished)
        advanceUntilIdle()
        assertEquals(Quota(used = 2, cap = 60), vm.state.value.quota)

        val failed = FakeApi().apply {
            explainResults += ExplicaResult.Success(
                ExplainResponse(status = "error", error = "It failed.", quota = Quota(used = 7, cap = 60))
            )
        }
        val vmFailed = viewModel(failed)
        advanceUntilIdle()
        assertEquals(ExplicaPhase.FAILED, vmFailed.state.value.phase)
        assertEquals(Quota(used = 7, cap = 60), vmFailed.state.value.quota)
    }

    @Test
    fun eachAnswerToAQuestion_replacesTheQuota_andAnAnswerWithoutOne_keepsTheLast() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += idle(quota = Quota(used = 18, cap = 60))
            askResults += answer("done", quota = Quota(used = 19, cap = 60))
            askResults += answer("done")
        }

        val vm = viewModel(api)
        advanceUntilIdle()

        vm.ask("Ce e drona?")
        advanceUntilIdle()
        assertEquals(Quota(used = 19, cap = 60), vm.state.value.quota)

        // An older server (or a proxy) answers without the key: what was known stays.
        vm.ask("Și dacă?")
        advanceUntilIdle()
        assertEquals(Quota(used = 19, cap = 60), vm.state.value.quota)
    }

    @Test
    fun thePolls_carryTheQuotaToo() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += idle(quota = Quota(used = 10, cap = 60))
            askResults += answer("running", quota = Quota(used = 11, cap = 60))
            askResults += answer("running", quota = Quota(used = 11, cap = 60))
            askResults += answer("done", quota = Quota(used = 12, cap = 60))
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        vm.ask("Ce e drona?")
        runCurrent()
        assertEquals(Quota(used = 11, cap = 60), vm.state.value.quota)

        advanceUntilIdle()
        assertEquals(Quota(used = 12, cap = 60), vm.state.value.quota)
        assertEquals(48, vm.state.value.requestsLeft)
    }

    @Test
    fun theDailyCap429_keepsItsQuota_andTheServersTextStaysTheError() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += done(quota = Quota(used = 59, cap = 60))
            askResults += ExplicaResult.Failure(
                kind = FailureKind.RATE_LIMITED,
                message = "Ai atins limita de azi.",
                quota = Quota(used = 60, cap = 60),
            )
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.requestsLeft)

        vm.setDraft("Ce e drona?")
        vm.send()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(0, state.requestsLeft)
        // The path that was already there: the question is dropped, the draft comes back, the server's words are shown.
        assertEquals(FailureKind.RATE_LIMITED, state.askFailure?.kind)
        assertEquals("Ai atins limita de azi.", state.askFailure?.message)
        assertEquals("Ce e drona?", state.draft)
        assertTrue(state.turns.isEmpty())
    }

    @Test
    fun theDailyCap429OnOpen_keepsItsQuota_andTheFailureCardKeepsTheServersText() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += ExplicaResult.Failure(
                kind = FailureKind.RATE_LIMITED,
                message = "Ai atins limita de azi.",
                quota = Quota(used = 60, cap = 60),
            )
        }

        val vm = viewModel(api)
        advanceUntilIdle()

        assertEquals(ExplicaPhase.FAILED, vm.state.value.phase)
        assertEquals("Ai atins limita de azi.", vm.state.value.failure?.message)
        assertEquals(Quota(used = 60, cap = 60), vm.state.value.quota)
        assertEquals(0, vm.state.value.requestsLeft)
    }

    @Test
    fun aFailureWithoutAQuota_leavesTheLastOneInPlace() = runTest(dispatcher) {
        val api = FakeApi().apply {
            explainResults += done(quota = Quota(used = 30, cap = 60))
            askResults += ExplicaResult.Failure(FailureKind.NETWORK, "timeout")
        }

        val vm = viewModel(api)
        advanceUntilIdle()
        vm.ask("Ce e drona?")
        advanceUntilIdle()

        assertEquals(Quota(used = 30, cap = 60), vm.state.value.quota)
    }
}
