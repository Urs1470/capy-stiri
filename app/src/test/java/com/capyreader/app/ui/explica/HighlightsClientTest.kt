package com.capyreader.app.ui.explica

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HighlightsClientTest {
    private var lastRequest: Request? = null
    private var requestCount = 0

    private fun client(token: String = "the-token", handler: (Request) -> Response): ExplicaClient {
        val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                lastRequest = chain.request()
                requestCount++
                handler(chain.request())
            })
            .build()

        return ExplicaClient(http = http, baseUrl = "https://stiri.example/explica/", token = { token })
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

    private fun bodyOf(request: Request?): String {
        val buffer = Buffer()
        request!!.body!!.writeTo(buffer)

        return buffer.readUtf8()
    }

    private val stored = """
        {"id":"h1","text":"a passage","color":"green","where":"answer:2","prefix":"before ","suffix":" after","created":1790000000}
    """.trimIndent()

    @Test
    fun list_postsTheEntryIdAndTheTokenToTheHighlightsEndpoint() = runTest {
        val api = client { it.reply(200, """{"highlights":[$stored]}""") }

        val result = api.listHighlights(entryId = 1234)

        val request = lastRequest!!
        assertEquals("POST", request.method)
        assertEquals("https://stiri.example/explica/api/highlights", request.url.toString())
        assertEquals("the-token", request.header("X-Auth-Token"))
        assertEquals("""{"entry_id":1234,"op":"list"}""", bodyOf(request))
        assertTrue(result is ExplicaResult.Success)
    }

    @Test
    fun list_readsEveryFieldOfAHighlight_andKeepsTheServersOrder() = runTest {
        val api = client {
            it.reply(
                200,
                """
                {"highlights":[
                  $stored,
                  {"id":"h2","text":"second","color":"pink","where":"story","prefix":"","suffix":"","created":1790000100,"extra":true}
                ]}
                """.trimIndent()
            )
        }

        val highlights = (api.listHighlights(1) as ExplicaResult.Success).value

        assertEquals(listOf("h1", "h2"), highlights.map { it.id })
        assertEquals(
            Highlight(
                id = "h1",
                text = "a passage",
                color = "green",
                where = "answer:2",
                prefix = "before ",
                suffix = " after",
                created = 1790000000,
            ),
            highlights[0],
        )
        assertEquals(HighlightColor.GREEN, highlights[0].highlightColor)
        assertEquals(2, HighlightWhere.answerIndex(highlights[0].where))
        assertEquals(HighlightColor.PINK, highlights[1].highlightColor)
    }

    @Test
    fun list_aStoryWithoutHighlights_isAnEmptyList() = runTest {
        val api = client { it.reply(200, """{"highlights":[]}""") }

        assertEquals(emptyList<Highlight>(), (api.listHighlights(1) as ExplicaResult.Success).value)
    }

    @Test
    fun add_sendsThePassageItsColorItsPlaceAndItsContext() = runTest {
        val api = client { it.reply(200, """{"highlight":$stored}""") }

        val result = api.addHighlight(
            entryId = 7,
            text = "a passage",
            color = HighlightColor.GREEN,
            where = HighlightWhere.answer(2),
            prefix = "before ",
            suffix = " after",
        )

        assertEquals(
            """{"entry_id":7,"op":"add","text":"a passage","color":"green","where":"answer:2","prefix":"before ","suffix":" after"}""",
            bodyOf(lastRequest),
        )
        assertEquals("the-token", lastRequest!!.header("X-Auth-Token"))
        assertEquals("h1", (result as ExplicaResult.Success).value.id)
    }

    @Test
    fun add_leavesOutTheContextWhenThereIsNone() = runTest {
        val api = client { it.reply(200, """{"highlight":$stored}""") }

        api.addHighlight(entryId = 7, text = "a passage", color = HighlightColor.YELLOW, where = HighlightWhere.STORY)

        val body = bodyOf(lastRequest)
        assertEquals(
            """{"entry_id":7,"op":"add","text":"a passage","color":"yellow","where":"story"}""",
            body,
        )
        assertFalse(body.contains("prefix"))
        assertFalse(body.contains("suffix"))
    }

    @Test
    fun add_escapesTheTextLikeJson() = runTest {
        val api = client { it.reply(200, """{"highlight":$stored}""") }

        api.addHighlight(7, "He said \"hi\"\tță", HighlightColor.BLUE, HighlightWhere.EXPLANATION)

        assertTrue(bodyOf(lastRequest).contains(""""text":"He said \"hi\"\tță""""))
    }

    @Test
    fun add_anAnswerWithoutAHighlight_isAServerFailure() = runTest {
        val api = client { it.reply(200, "{}") }

        val failure = api.addHighlight(7, "x", HighlightColor.PINK, HighlightWhere.STORY) as ExplicaResult.Failure

        assertEquals(FailureKind.SERVER, failure.kind)
    }

    @Test
    fun remove_sendsTheIdAndExpectsOk() = runTest {
        val api = client { it.reply(200, """{"ok":true}""") }

        val result = api.removeHighlight(entryId = 7, id = "h1")

        assertEquals("""{"entry_id":7,"op":"remove","id":"h1"}""", bodyOf(lastRequest))
        assertEquals(ExplicaResult.Success(Unit), result)
    }

    @Test
    fun remove_anAnswerThatIsNotOk_isAServerFailure() = runTest {
        val api = client { it.reply(200, """{"ok":false}""") }

        val failure = api.removeHighlight(7, "h1") as ExplicaResult.Failure

        assertEquals(FailureKind.SERVER, failure.kind)
    }

    @Test
    fun httpErrors_mapToTheSameKindsAsTheOtherEndpoints() = runTest {
        val cases = mapOf(
            401 to FailureKind.UNAUTHORIZED,
            403 to FailureKind.FORBIDDEN,
            404 to FailureKind.NOT_FOUND,
            429 to FailureKind.RATE_LIMITED,
            503 to FailureKind.UNAVAILABLE,
            500 to FailureKind.SERVER,
        )

        cases.forEach { (code, kind) ->
            val api = client { it.reply(code, """{"error":"message $code"}""") }

            val listed = api.listHighlights(1) as ExplicaResult.Failure
            val added = api.addHighlight(1, "x", HighlightColor.YELLOW, HighlightWhere.STORY) as ExplicaResult.Failure
            val removed = api.removeHighlight(1, "h1") as ExplicaResult.Failure

            assertEquals("list, HTTP $code", kind, listed.kind)
            assertEquals("add, HTTP $code", kind, added.kind)
            assertEquals("remove, HTTP $code", kind, removed.kind)
            assertEquals("message $code", removed.message)
        }
    }

    @Test
    fun aBodyThatIsNotJson_isAServerFailureNotACrash() = runTest {
        val html = client { it.reply(502, "<html>Bad gateway</html>") }.listHighlights(1) as ExplicaResult.Failure
        assertEquals(FailureKind.SERVER, html.kind)

        val garbage = client { it.reply(200, "not json") }.listHighlights(1) as ExplicaResult.Failure
        assertEquals(FailureKind.SERVER, garbage.kind)
    }

    @Test
    fun noConnection_isANetworkFailure() = runTest {
        val api = client { throw IOException("Unable to resolve host") }

        val failure = api.removeHighlight(1, "h1") as ExplicaResult.Failure

        assertEquals(FailureKind.NETWORK, failure.kind)
        assertEquals(1, requestCount)
    }

    @Test
    fun theTokenIsReadOnEveryCall() = runTest {
        var token = "first"
        val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                lastRequest = chain.request()
                chain.request().reply(200, """{"highlights":[]}""")
            })
            .build()
        val api = ExplicaClient(http, "https://stiri.example/explica/", token = { token })

        api.listHighlights(1)
        assertEquals("first", lastRequest!!.header("X-Auth-Token"))

        token = "second"
        api.listHighlights(1)
        assertEquals("second", lastRequest!!.header("X-Auth-Token"))
    }

    @Test
    fun anUnknownColor_isDrawnYellow_andTheAnswerStillDecodes() = runTest {
        val api = client {
            it.reply(200, """{"highlights":[{"id":"h9","text":"x","color":"purple","where":"story"}]}""")
        }

        val highlight = (api.listHighlights(1) as ExplicaResult.Success).value.single()

        assertEquals("purple", highlight.color)
        assertEquals(HighlightColor.YELLOW, highlight.highlightColor)
        assertEquals(0L, highlight.created)
        assertEquals("", highlight.prefix)
    }
}
