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

class ExplicaClientTest {
    private var lastRequest: Request? = null

    private fun client(token: String = "the-token", handler: (Request) -> Response): ExplicaClient {
        val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                lastRequest = chain.request()
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

    @Test
    fun explain_sendsTokenAndEntryId() = runTest {
        val api = client { it.reply(200, """{"status":"running","stage":"citesc articolul"}""") }

        val result = api.explain(entryId = 1234)

        val request = lastRequest!!
        assertEquals("https://stiri.example/explica/api/explain", request.url.toString())
        assertEquals("the-token", request.header("X-Auth-Token"))
        assertEquals("""{"entry_id":1234}""", bodyOf(request))
        assertTrue(result is ExplicaResult.Success)
        assertEquals("running", (result as ExplicaResult.Success).value.status)
        assertEquals("citesc articolul", result.value.stage)
    }

    @Test
    fun explain_retryIsSentOnlyWhenAsked() = runTest {
        val api = client { it.reply(200, """{"status":"running"}""") }

        api.explain(entryId = 7, retry = true)

        assertEquals("""{"entry_id":7,"retry":true}""", bodyOf(lastRequest))
    }

    @Test
    fun explain_startFalseIsSentOnlyWhenAsked() = runTest {
        val api = client { it.reply(200, """{"status":"idle"}""") }

        api.explain(entryId = 7, start = false)
        assertEquals("""{"entry_id":7,"start":false}""", bodyOf(lastRequest))

        api.explain(entryId = 7, retry = true, start = false)
        assertEquals("""{"entry_id":7,"retry":true,"start":false}""", bodyOf(lastRequest))

        // The server starts the explanation unless told otherwise, so the default and `start = true`
        // send no `start` at all.
        api.explain(entryId = 7)
        assertEquals("""{"entry_id":7}""", bodyOf(lastRequest))
        assertFalse(bodyOf(lastRequest).contains("start"))

        api.explain(entryId = 7, start = true)
        assertEquals("""{"entry_id":7}""", bodyOf(lastRequest))

        api.explain(entryId = 7, retry = true)
        assertEquals("""{"entry_id":7,"retry":true}""", bodyOf(lastRequest))
    }

    @Test
    fun explain_readsAnIdleAnswer() = runTest {
        val api = client {
            it.reply(
                200,
                """
                {"status":"idle","title":"Drone","link":"https://ipn.md/a","suggest":[],
                 "chat":[{"q":"What is a drone?","status":"done","html_app":"<p>An aircraft.</p>","meta":"m"}]}
                """.trimIndent()
            )
        }

        val value = (api.explain(entryId = 1, start = false) as ExplicaResult.Success).value

        assertEquals(STATUS_IDLE, value.status)
        assertEquals("Drone", value.title)
        assertEquals("https://ipn.md/a", value.link)
        assertTrue(value.suggest.isEmpty())
        assertEquals("", value.htmlApp)
        assertEquals("<p>An aircraft.</p>", value.chat.single().htmlApp)
    }

    @Test
    fun explain_readsTheAppHtmlAndIgnoresTheWebHtml() = runTest {
        val api = client {
            it.reply(
                200,
                """
                {"status":"done","title":"Drona","link":"https://ipn.md/a","meta":"MiniMax-M3 · 13 s",
                 "html":"<ol class=\"src\"><li value=\"2\">x</li></ol>",
                 "html_app":"<p>Text<sup><a href=\"https://ipn.md/a\">1</a></sup></p><p>[1] ipn.md</p>",
                 "suggest":["Ce urmează?","Cine e?"],
                 "chat":[{"q":"Ce e drona?","status":"done","html":"<p>w</p>","html_app":"<p>r</p>","meta":"m"}],
                 "necunoscut":true}
                """.trimIndent()
            )
        }

        val value = (api.explain(entryId = 1) as ExplicaResult.Success).value

        assertEquals("done", value.status)
        assertEquals("Drona", value.title)
        assertEquals("https://ipn.md/a", value.link)
        assertTrue(value.htmlApp.startsWith("<p>Text<sup>"))
        assertEquals(listOf("Ce urmează?", "Cine e?"), value.suggest)
        assertEquals("<p>r</p>", value.chat.single().htmlApp)
    }

    @Test
    fun ask_withAndWithoutQuestion() = runTest {
        val api = client { it.reply(200, """{"chat":[{"q":"Ce e drona?","status":"running","stage":"caut pe web","partial_app":"<p>p</p>"}]}""") }

        val asked = api.ask(entryId = 9, question = "Ce e drona?") as ExplicaResult.Success
        assertEquals("""{"entry_id":9,"question":"Ce e drona?"}""", bodyOf(lastRequest))
        assertEquals("https://stiri.example/explica/api/ask", lastRequest!!.url.toString())
        assertTrue(asked.value.chat.single().isRunning)
        assertEquals("<p>p</p>", asked.value.chat.single().partialApp)

        api.ask(entryId = 9)
        assertEquals("""{"entry_id":9}""", bodyOf(lastRequest))
    }

    @Test
    fun theTokenIsReadOnEveryCall() = runTest {
        var token = "first"
        val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                lastRequest = chain.request()
                chain.request().reply(200, "{}")
            })
            .build()
        val api = ExplicaClient(http, "https://stiri.example/explica/", token = { token })

        api.explain(1)
        assertEquals("first", lastRequest!!.header("X-Auth-Token"))

        token = "second"
        api.explain(1)
        assertEquals("second", lastRequest!!.header("X-Auth-Token"))
    }

    @Test
    fun httpErrors_mapToKinds_withTheServersOwnMessage() = runTest {
        val cases = mapOf(
            401 to FailureKind.UNAUTHORIZED,
            403 to FailureKind.FORBIDDEN,
            404 to FailureKind.NOT_FOUND,
            409 to FailureKind.BUSY,
            429 to FailureKind.RATE_LIMITED,
            503 to FailureKind.UNAVAILABLE,
            500 to FailureKind.SERVER,
            418 to FailureKind.SERVER,
        )

        cases.forEach { (code, kind) ->
            val api = client { it.reply(code, """{"error":"mesaj $code"}""") }

            val failure = api.explain(1) as ExplicaResult.Failure

            assertEquals("HTTP $code", kind, failure.kind)
            assertEquals("mesaj $code", failure.message)
        }
    }

    @Test
    fun busy_comesWithTheChatSoTheScreenCanShowIt() = runTest {
        val api = client {
            it.reply(
                409,
                """{"error":"Aștept încă răspunsul","chat":[{"q":"Ce e drona?","status":"running"}]}"""
            )
        }

        val failure = api.ask(1, "Și dacă?") as ExplicaResult.Failure

        assertEquals(FailureKind.BUSY, failure.kind)
        assertEquals("Aștept încă răspunsul", failure.message)
        assertTrue(failure.chat.single().isRunning)
    }

    @Test
    fun aBodyThatIsNotJson_isAServerFailureNotACrash() = runTest {
        val html = client { it.reply(502, "<html>Bad gateway</html>") }.explain(1) as ExplicaResult.Failure
        assertEquals(FailureKind.SERVER, html.kind)
        assertEquals("", html.message)

        val garbage = client { it.reply(200, "not json") }.explain(1) as ExplicaResult.Failure
        assertEquals(FailureKind.SERVER, garbage.kind)
    }

    @Test
    fun noConnection_isANetworkFailure() = runTest {
        val api = client { throw IOException("Unable to resolve host") }

        val failure = api.explain(1) as ExplicaResult.Failure

        assertEquals(FailureKind.NETWORK, failure.kind)
        assertTrue(failure.message.contains("Unable to resolve host"))
    }

    @Test
    fun canExplain_everyArticleOfAMinifluxAccountWithAnApiToken_digestAndReading() {
        val token = com.jocmp.capy.accounts.Source.MINIFLUX_TOKEN

        assertTrue(canExplain(token, "https://news.iupif.org/sectiuni/republica-moldova.xml"))
        assertTrue(canExplain(token, "https://aeon.co/feed.rss"))
        assertEquals(false, canExplain(com.jocmp.capy.accounts.Source.MINIFLUX, "https://news.iupif.org/sectiuni/ai.xml"))
        assertEquals(false, canExplain(com.jocmp.capy.accounts.Source.LOCAL, "https://aeon.co/feed.rss"))
        assertEquals(false, canExplain(token, null))
        assertEquals(false, canExplain(token, ""))
    }
}
