package com.capyreader.app.ui.explica

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Highlights page: the `op: all` call, and what the page shows and opens. */
@OptIn(ExperimentalCoroutinesApi::class)
class HighlightsPageTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // the client

    private var lastRequest: Request? = null

    private fun client(code: Int, body: String): ExplicaClient {
        val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                lastRequest = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("test")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        return ExplicaClient(http = http, baseUrl = "https://stiri.example/explica/", token = { "the-token" })
    }

    private val serverAnswer = """
        {"stories":[
          {"entry_id":5012,"title":"Gemini 4 Argon","link":"https://deepmind.google/a","section":"AI","latest":1790850000,
           "highlights":[
             {"id":"h1","text":"1M output tokens","color":"green","where":"story","prefix":"","suffix":"","created":1790840000},
             {"id":"h2","text":"phased release","color":"blue","where":"answer:0","prefix":"","suffix":"","created":1790850000}
           ]},
          {"entry_id":4999,"title":"","link":"","section":"","latest":1790700000,
           "highlights":[{"id":"h3","text":"old one","color":"yellow","where":"story","created":1790700000}],"extra":1}
        ]}
    """.trimIndent()

    @Test
    fun all_postsOpAllWithTheToken_andReadsTheStoriesInTheServersOrder() = runTest {
        val result = client(200, serverAnswer).allHighlights()

        val request = lastRequest!!
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertEquals("https://stiri.example/explica/api/highlights", request.url.toString())
        assertEquals("the-token", request.header("X-Auth-Token"))
        assertEquals("""{"op":"all"}""", body)

        val stories = (result as ExplicaResult.Success).value
        assertEquals(listOf(5012L, 4999L), stories.map { it.entryId })
        assertEquals("Gemini 4 Argon", stories[0].title)
        assertEquals(listOf("1M output tokens", "phased release"), stories[0].highlights.map { it.text })
        assertEquals(HighlightColor.BLUE, stories[0].highlights[1].highlightColor)
        assertEquals("", stories[1].title)
    }

    @Test
    fun all_failuresComeBackAsTheirKind() = runTest {
        assertEquals(FailureKind.UNAUTHORIZED, (client(401, """{"error":"x"}""").allHighlights() as ExplicaResult.Failure).kind)
        assertEquals(FailureKind.SERVER, (client(200, "not json").allHighlights() as ExplicaResult.Failure).kind)
    }

    // the view model

    private class FakeAll(var answer: ExplicaResult<List<StoryHighlights>>) : AllHighlightsApi {
        var calls = 0

        override suspend fun allHighlights(): ExplicaResult<List<StoryHighlights>> {
            calls++
            return answer
        }
    }

    private val story = StoryHighlights(entryId = 5012, title = "Gemini", link = "https://deepmind.google/a",
        highlights = listOf(Highlight(id = "h1", text = "x")))
    private val gone = StoryHighlights(entryId = 4999, title = "Gone", link = "https://example.com/gone",
        highlights = listOf(Highlight(id = "h3", text = "y")))

    private fun TestScope.viewModel(api: AllHighlightsApi, onPhone: Set<String> = setOf("5012")): HighlightsPageViewModel {
        val vm = HighlightsPageViewModel(api = api, hasArticle = { it in onPhone })
        advanceUntilIdle()
        return vm
    }

    @Test
    fun theStoriesAreReadWhenThePageOpens() = runTest(dispatcher) {
        val api = FakeAll(ExplicaResult.Success(listOf(story, gone)))
        val vm = viewModel(api)

        assertEquals(1, api.calls)
        assertEquals(listOf(story, gone), vm.state.value.stories)
        assertEquals(false, vm.state.value.loading)
        assertNull(vm.state.value.failure)
    }

    @Test
    fun aFailedRead_keepsWhatWasShown_andSaysWhy() = runTest(dispatcher) {
        val api = FakeAll(ExplicaResult.Success(listOf(story)))
        val vm = viewModel(api)

        api.answer = ExplicaResult.Failure(FailureKind.NETWORK)
        vm.refresh()
        advanceUntilIdle()

        assertEquals(listOf(story), vm.state.value.stories)
        assertEquals(FailureKind.NETWORK, vm.state.value.failure)

        api.answer = ExplicaResult.Success(emptyList())
        vm.refresh()
        advanceUntilIdle()

        assertEquals(emptyList<StoryHighlights>(), vm.state.value.stories)
        assertNull(vm.state.value.failure)
        assertTrue(vm.state.value.loaded)
    }

    @Test
    fun aStoryOnThePhoneOpensInTheReader_byItsArticleId() = runTest(dispatcher) {
        val vm = viewModel(FakeAll(ExplicaResult.Success(listOf(story, gone))))
        val opened = mutableListOf<String>()

        vm.open(story) { opened += it }
        advanceUntilIdle()

        assertEquals(listOf("5012"), opened)
        assertNull(vm.state.value.missing)
    }

    @Test
    fun aStoryNoLongerOnThePhone_isReportedWithItsLink_andNothingOpens() = runTest(dispatcher) {
        val vm = viewModel(FakeAll(ExplicaResult.Success(listOf(story, gone))))
        val opened = mutableListOf<String>()

        vm.open(gone) { opened += it }
        advanceUntilIdle()

        assertEquals(emptyList<String>(), opened)
        assertEquals(gone, vm.state.value.missing)

        vm.dismissMissing()
        assertNull(vm.state.value.missing)
    }
}
