package com.capyreader.app.ui.explica

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val STATUS_RUNNING = "running"
const val STATUS_DONE = "done"
const val STATUS_ERROR = "error"

/** Only answers a read-only `explain` (`start = false`): no explanation exists and none is being written. */
const val STATUS_IDLE = "idle"

/**
 * One question and its answer, as the news explainer server sends it
 * (`POST /explica/api/ask`, field `chat`). [htmlApp] and [partialApp] are the answer in the
 * simplified HTML the reader can flatten (no `class`/`value`/`data-*`, sources numbered in the text).
 */
@Serializable
data class ExplicaTurn(
    val q: String,
    val status: String,
    @SerialName("html_app") val htmlApp: String = "",
    val meta: String = "",
    val stage: String = "",
    @SerialName("partial_app") val partialApp: String = "",
    val error: String = "",
) {
    val isRunning: Boolean
        get() = status == STATUS_RUNNING
}

/**
 * The daily cap of the explainer server, as it reports it with every answer of `explain` and `ask` (the 429
 * that says the cap is reached too): [used] of [cap] requests today. An answer of an older server has none.
 */
@Serializable
data class Quota(
    val used: Int = 0,
    val cap: Int = 0,
) {
    /** What is left today; never below zero, also when the server counts past its cap. */
    val left: Int
        get() = (cap - used).coerceAtLeast(0)
}

/** The AI screen tells the reader how many requests are left once this many or fewer are. */
const val QUOTA_CAPTION_AT_OR_BELOW = 60

/**
 * Reads the `quota` of an answer without letting it spoil the answer: it is only a caption, so a value that is not
 * what the server promised (a number as `18.0` or `"18"` is read as 18; anything that is not a number, or not an
 * object, is no quota at all) must not turn an explanation into a failure.
 */
internal object LenientQuotaSerializer : JsonTransformingSerializer<Quota?>(Quota.serializer().nullable) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val quota = element as? JsonObject ?: return JsonNull

        return buildJsonObject {
            listOf("used", "cap").forEach { key ->
                val value = quota[key]

                if (value == null || value is JsonNull) {
                    return@forEach
                }

                val number = (value as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: return JsonNull

                put(key, number.toInt())
            }
        }
    }
}

/**
 * `POST /explica/api/explain`: the explanation of one story, or its progress. [status] is `done`,
 * `running`, `error` or, after a read-only call, `idle` (then only [title], [link] and [chat] matter).
 * [day] is true for the entry of the day (the story of the feed "Conceptul zilei"): it has no explanation, and the
 * server answers its questions from all the other stories of the day.
 */
@Serializable
data class ExplainResponse(
    val status: String = "",
    val title: String = "",
    val link: String = "",
    val stage: String = "",
    @SerialName("partial_app") val partialApp: String = "",
    @SerialName("html_app") val htmlApp: String = "",
    val meta: String = "",
    val suggest: List<String> = emptyList(),
    val chat: List<ExplicaTurn> = emptyList(),
    val error: String = "",
    @Serializable(with = LenientQuotaSerializer::class) val quota: Quota? = null,
    val day: Boolean = false,
)

/** `POST /explica/api/ask`: the chat of one story. */
@Serializable
data class AskResponse(
    val chat: List<ExplicaTurn> = emptyList(),
    val error: String = "",
    @Serializable(with = LenientQuotaSerializer::class) val quota: Quota? = null,
)

enum class FailureKind {
    /** 401: the API token isn't known to the server. */
    UNAUTHORIZED,

    /** 403: the story belongs to another user of the digest. */
    FORBIDDEN,

    /** 404: not a story from the digest (or gone from the server, which keeps them 14 days). */
    NOT_FOUND,

    /** 409: a question is still being answered. */
    BUSY,

    /** 429: daily cap or too many questions on one story. */
    RATE_LIMITED,

    /** 503: the server can't reach Miniflux to check the token. */
    UNAVAILABLE,

    /** Any other HTTP error, or a body that isn't what the server sends. */
    SERVER,

    /** No connection, timeout. */
    NETWORK,
}

sealed interface ExplicaResult<out T> {
    data class Success<T>(val value: T) : ExplicaResult<T>

    /**
     * [message] is the server's own text (Romanian), when it sent one; [chat] comes with 409 and [quota] with
     * the 429 of the daily cap.
     */
    data class Failure(
        val kind: FailureKind,
        val message: String = "",
        val chat: List<ExplicaTurn> = emptyList(),
        val quota: Quota? = null,
    ) : ExplicaResult<Nothing>
}
