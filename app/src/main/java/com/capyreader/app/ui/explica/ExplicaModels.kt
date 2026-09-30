package com.capyreader.app.ui.explica

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val STATUS_RUNNING = "running"
const val STATUS_DONE = "done"
const val STATUS_ERROR = "error"

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

/** `POST /explica/api/explain`: the explanation of one story, or its progress. */
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
)

/** `POST /explica/api/ask`: the chat of one story. */
@Serializable
data class AskResponse(
    val chat: List<ExplicaTurn> = emptyList(),
    val error: String = "",
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

    /** [message] is the server's own text (Romanian), when it sent one; [chat] comes with 409. */
    data class Failure(
        val kind: FailureKind,
        val message: String = "",
        val chat: List<ExplicaTurn> = emptyList(),
    ) : ExplicaResult<Nothing>
}
