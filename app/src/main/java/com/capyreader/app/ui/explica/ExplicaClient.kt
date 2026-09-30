package com.capyreader.app.ui.explica

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the explainer screen needs from the news explainer server (`tools/stiri-explica/server.py`). */
interface ExplicaApi {
    /**
     * Returns the progress or result of the explanation of a story, and starts it when there is none.
     * With [start] `false` the call only reads the state and never starts anything: it answers `idle`
     * when no explanation was asked for. Poll until `status != running`.
     */
    suspend fun explain(entryId: Long, retry: Boolean = false, start: Boolean = true): ExplicaResult<ExplainResponse>

    /** Asks [question] about the story, or, with `null`, returns the chat so far (poll while a turn is running). */
    suspend fun ask(entryId: Long, question: String? = null): ExplicaResult<AskResponse>
}

/**
 * The highlights of a story, kept by the same server (`POST api/highlights`; the `op` is `list`, `add` or `remove`).
 * Every call is one request: nothing is queued, so a call that fails is for the caller to undo.
 */
interface HighlightsApi {
    /** All the highlights of the story for this reader, oldest first. */
    suspend fun listHighlights(entryId: Long): ExplicaResult<List<Highlight>>

    /**
     * Saves [text] (1..1000 characters) as a highlight of [color] in [where] (see [HighlightWhere]). [prefix] and
     * [suffix] are the text around it (up to 60 characters each) that tells the passage apart when the same words
     * occur several times. Adding the same [where] and [text] again returns the highlight that exists.
     */
    suspend fun addHighlight(
        entryId: Long,
        text: String,
        color: HighlightColor,
        where: String,
        prefix: String = "",
        suffix: String = "",
    ): ExplicaResult<Highlight>

    /** Removes a highlight; an [id] the server doesn't know is a [FailureKind.NOT_FOUND]. */
    suspend fun removeHighlight(entryId: Long, id: String): ExplicaResult<Unit>
}

internal val ExplicaJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * The server identifies the reader by the Miniflux API token (`X-Auth-Token`) and the story by the
 * Miniflux entry id, which is also the article id of a Miniflux account in this app.
 */
class ExplicaClient(
    private val http: OkHttpClient,
    baseUrl: String,
    private val token: () -> String,
    private val json: Json = ExplicaJson,
) : ExplicaApi, HighlightsApi {
    private val base = baseUrl.toHttpUrl()

    override suspend fun explain(entryId: Long, retry: Boolean, start: Boolean): ExplicaResult<ExplainResponse> =
        post("api/explain", ExplainResponse.serializer()) {
            put("entry_id", entryId)
            if (retry) put("retry", true)
            // Sent only when false: without it the server behaves as before and starts the explanation.
            if (!start) put("start", false)
        }

    override suspend fun ask(entryId: Long, question: String?): ExplicaResult<AskResponse> =
        post("api/ask", AskResponse.serializer()) {
            put("entry_id", entryId)
            if (question != null) put("question", question)
        }

    override suspend fun listHighlights(entryId: Long): ExplicaResult<List<Highlight>> {
        val result = post("api/highlights", HighlightsResponse.serializer()) {
            put("entry_id", entryId)
            put("op", "list")
        }

        return when (result) {
            is ExplicaResult.Success -> ExplicaResult.Success(result.value.highlights)
            is ExplicaResult.Failure -> result
        }
    }

    override suspend fun addHighlight(
        entryId: Long,
        text: String,
        color: HighlightColor,
        where: String,
        prefix: String,
        suffix: String,
    ): ExplicaResult<Highlight> {
        val result = post("api/highlights", HighlightResponse.serializer()) {
            put("entry_id", entryId)
            put("op", "add")
            put("text", text)
            put("color", color.wire)
            put("where", where)
            // The context is optional: without it the server stores none.
            if (prefix.isNotEmpty()) put("prefix", prefix)
            if (suffix.isNotEmpty()) put("suffix", suffix)
        }

        return when (result) {
            is ExplicaResult.Success -> {
                val highlight = result.value.highlight

                if (highlight == null) {
                    ExplicaResult.Failure(FailureKind.SERVER, "no highlight in the answer")
                } else {
                    ExplicaResult.Success(highlight)
                }
            }

            is ExplicaResult.Failure -> result
        }
    }

    override suspend fun removeHighlight(entryId: Long, id: String): ExplicaResult<Unit> {
        val result = post("api/highlights", OkResponse.serializer()) {
            put("entry_id", entryId)
            put("op", "remove")
            put("id", id)
        }

        return when (result) {
            is ExplicaResult.Success -> {
                if (result.value.ok) {
                    ExplicaResult.Success(Unit)
                } else {
                    ExplicaResult.Failure(FailureKind.SERVER, "the server did not confirm the removal")
                }
            }

            is ExplicaResult.Failure -> result
        }
    }

    private suspend fun <T> post(
        path: String,
        serializer: KSerializer<T>,
        body: JsonObjectBuilder.() -> Unit,
    ): ExplicaResult<T> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject(body).toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(base.resolve(path) ?: return@withContext ExplicaResult.Failure(FailureKind.SERVER, "bad URL"))
            .header("X-Auth-Token", token())
            .post(payload)
            .build()

        try {
            http.newCall(request).await().use { response ->
                val text = response.body.string()

                if (response.isSuccessful) {
                    ExplicaResult.Success(json.decodeFromString(serializer, text))
                } else {
                    failure(response.code, text)
                }
            }
        } catch (e: IOException) {
            ExplicaResult.Failure(FailureKind.NETWORK, e.message.orEmpty())
        } catch (e: SerializationException) {
            ExplicaResult.Failure(FailureKind.SERVER, e.message.orEmpty())
        } catch (e: IllegalArgumentException) {
            ExplicaResult.Failure(FailureKind.SERVER, e.message.orEmpty())
        }
    }

    private fun failure(code: Int, body: String): ExplicaResult.Failure {
        // Errors come as {"error": "...", "chat": [...]}; a Cloudflare page or an empty body is fine too.
        val parsed = try {
            json.decodeFromString(AskResponse.serializer(), body)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        return ExplicaResult.Failure(
            kind = kindOf(code),
            message = parsed?.error.orEmpty(),
            chat = parsed?.chat.orEmpty(),
        )
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal fun kindOf(code: Int): FailureKind = when (code) {
            401 -> FailureKind.UNAUTHORIZED
            403 -> FailureKind.FORBIDDEN
            404 -> FailureKind.NOT_FOUND
            409 -> FailureKind.BUSY
            429 -> FailureKind.RATE_LIMITED
            503 -> FailureKind.UNAVAILABLE
            else -> FailureKind.SERVER
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }

    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }
        }
    )
}
