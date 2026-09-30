package com.capyreader.app.ui.explica

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ExplicaPhase {
    /** The screen just opened and one read-only call is finding out where the story stands. Nothing is generated. */
    OPENING,

    /** No explanation exists and none is being written: questions can be asked, and Explain starts the explanation. */
    IDLE,

    /** The explanation is being written; [ExplicaState.explanationHtml] holds what exists so far. */
    LOADING,

    /** The explanation is done; questions can be asked. */
    READY,

    /**
     * The story can't be shown; [ExplicaState.failure] says why. Retry writes the explanation again if that
     * is what failed, and reads the state again if opening failed.
     */
    FAILED,
}

data class ExplicaState(
    val phase: ExplicaPhase = ExplicaPhase.OPENING,
    val title: String = "",
    val articleUrl: String = "",
    val stage: String = "",
    val explanationHtml: String = "",
    val meta: String = "",
    val suggestions: List<String> = emptyList(),
    val turns: List<ExplicaTurn> = emptyList(),
    val draft: String = "",
    val asking: Boolean = false,
    val failure: ExplicaResult.Failure? = null,
    val askFailure: ExplicaResult.Failure? = null,
) {
    /** Questions are taken before the explanation exists and after it, never while it is being written. */
    val canAsk: Boolean
        get() = phase == ExplicaPhase.IDLE || phase == ExplicaPhase.READY

    val canSend: Boolean
        get() = canAsk && !asking && draft.isNotBlank()

    /** The Explain shortcut: only while there is no explanation, and not while an answer is being written. */
    val canStartExplanation: Boolean
        get() = phase == ExplicaPhase.IDLE && !asking
}

/**
 * Backs the explainer screen of one story. Opening it only reads the state of the story (`explain`
 * with `start = false`), so nothing is generated until the reader asks: a question is answered from
 * the story alone, and [explain] starts the written explanation, which is polled until it is done.
 * The chat (`ask`) is polled while an answer is being written. [entryId] is the Miniflux entry id,
 * which is the article id of a Miniflux account.
 */
class ExplicaViewModel(
    private val api: ExplicaApi,
    private val entryId: Long,
    private val pollMillis: Long = POLL_MILLIS,
    private val retryMillis: Long = RETRY_MILLIS,
) : ViewModel() {
    private val _state = MutableStateFlow(ExplicaState())
    val state: StateFlow<ExplicaState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var chatJob: Job? = null

    /**
     * Once the reader has asked for the explanation (or the server says it failed), Retry writes it again.
     * Before that, a failed open has produced nothing to write: Retry only reads the state again.
     */
    private var explanationAsked = false

    init {
        if (entryId <= 0) {
            _state.value = ExplicaState(
                phase = ExplicaPhase.FAILED,
                failure = ExplicaResult.Failure(FailureKind.NOT_FOUND),
            )
        } else {
            load(start = false, retry = false)
        }
    }

    /** The Explain shortcut: asks the server to write the explanation of the story (~30 s, from a shared quota). */
    fun explain() {
        if (entryId > 0 && _state.value.canStartExplanation) {
            explanationAsked = true
            load(start = true, retry = false)
        }
    }

    fun retry() {
        if (entryId > 0) {
            load(start = explanationAsked, retry = explanationAsked)
        }
    }

    fun setDraft(text: String) {
        _state.update { it.copy(draft = text.take(MAX_QUESTION_LENGTH)) }
    }

    /** Sends what is typed. The input clears at once and comes back if the server doesn't take the question. */
    fun send() {
        submit(_state.value.draft, fromDraft = true)
    }

    fun ask(question: String) {
        submit(question, fromDraft = false)
    }

    private fun submit(question: String, fromDraft: Boolean) {
        val text = question.trim()
        val current = _state.value

        if (text.isEmpty() || !current.canAsk || current.asking) {
            return
        }

        chatJob?.cancel()
        chatJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    asking = true,
                    askFailure = null,
                    draft = if (fromDraft) "" else it.draft,
                    turns = it.turns + ExplicaTurn(q = text, status = STATUS_RUNNING),
                    suggestions = it.suggestions - text,
                )
            }

            when (val result = api.ask(entryId, text)) {
                is ExplicaResult.Success -> showChat(result.value.chat)
                is ExplicaResult.Failure -> {
                    if (result.chat.isNotEmpty()) {
                        showChat(result.chat)
                    } else {
                        _state.update { it.copy(turns = it.turns.dropLast(1)) }
                    }

                    _state.update {
                        it.copy(
                            askFailure = result,
                            draft = if (fromDraft && it.draft.isEmpty()) text else it.draft,
                        )
                    }

                    if (_state.value.turns.lastOrNull()?.isRunning != true) {
                        _state.update { it.copy(asking = false) }
                        return@launch
                    }
                }
            }

            pollChat()
        }
    }

    /**
     * With [start] `false` (opening the screen) every call only reads the state and never starts the
     * explanation, so an `idle` answer ends the loop and a `running` one is followed until it is done.
     * With [start] `true` (the Explain shortcut, or Retry after the explanation failed) the server starts
     * the explanation on the first call and the loop follows its progress.
     */
    private fun load(start: Boolean, retry: Boolean) {
        loadJob?.cancel()
        chatJob?.cancel()

        _state.update {
            it.copy(
                phase = if (start) ExplicaPhase.LOADING else ExplicaPhase.OPENING,
                failure = null,
                askFailure = null,
                asking = false,
            )
        }

        loadJob = viewModelScope.launch {
            var firstCall = retry
            var networkFailures = 0

            while (true) {
                when (val result = api.explain(entryId, retry = firstCall, start = start)) {
                    is ExplicaResult.Success -> {
                        networkFailures = 0
                        firstCall = false
                        val response = result.value

                        when (response.status) {
                            STATUS_DONE -> {
                                showExplanation(response)
                                pollChat()
                                return@launch
                            }

                            STATUS_IDLE -> {
                                showIdle(response)
                                pollChat()
                                return@launch
                            }

                            STATUS_ERROR -> {
                                explanationAsked = true
                                fail(
                                    ExplicaResult.Failure(FailureKind.SERVER, response.error),
                                    title = response.title,
                                )
                                return@launch
                            }

                            else -> {
                                showProgress(response)
                                delay(pollMillis)
                            }
                        }
                    }

                    is ExplicaResult.Failure -> {
                        if (result.kind == FailureKind.NETWORK && ++networkFailures < MAX_NETWORK_FAILURES) {
                            delay(retryMillis)
                        } else {
                            fail(result)
                            return@launch
                        }
                    }
                }
            }
        }
    }

    private suspend fun pollChat() {
        var networkFailures = 0

        while (_state.value.turns.lastOrNull()?.isRunning == true) {
            delay(pollMillis)

            when (val result = api.ask(entryId)) {
                is ExplicaResult.Success -> {
                    networkFailures = 0
                    showChat(result.value.chat)
                }

                is ExplicaResult.Failure -> {
                    if (result.kind == FailureKind.NETWORK && ++networkFailures < MAX_NETWORK_FAILURES) {
                        delay(retryMillis)
                    } else {
                        stopAsking(result)
                        return
                    }
                }
            }
        }

        _state.update { it.copy(asking = false) }
    }

    private fun showProgress(response: ExplainResponse) {
        _state.update {
            it.copy(
                phase = ExplicaPhase.LOADING,
                title = response.title.ifBlank { it.title },
                stage = response.stage,
                explanationHtml = response.partialApp.ifBlank { it.explanationHtml },
            )
        }
    }

    private fun showExplanation(response: ExplainResponse) {
        _state.update {
            it.copy(
                phase = ExplicaPhase.READY,
                title = response.title.ifBlank { it.title },
                articleUrl = response.link.ifBlank { it.articleUrl },
                stage = "",
                explanationHtml = response.htmlApp,
                meta = response.meta,
                suggestions = response.suggest,
                turns = response.chat,
                asking = response.chat.lastOrNull()?.isRunning == true,
            )
        }
    }

    /** No explanation yet: the title, the link and the chat the story already has, and no explanation section. */
    private fun showIdle(response: ExplainResponse) {
        _state.update {
            it.copy(
                phase = ExplicaPhase.IDLE,
                title = response.title.ifBlank { it.title },
                articleUrl = response.link.ifBlank { it.articleUrl },
                stage = "",
                explanationHtml = "",
                meta = "",
                suggestions = response.suggest,
                turns = response.chat,
                asking = response.chat.lastOrNull()?.isRunning == true,
            )
        }
    }

    private fun showChat(turns: List<ExplicaTurn>) {
        _state.update { it.copy(turns = turns, asking = turns.lastOrNull()?.isRunning == true) }
    }

    /** A poll gave up: the running turn shows the error instead of waiting forever. */
    private fun stopAsking(failure: ExplicaResult.Failure) {
        _state.update {
            val last = it.turns.lastOrNull()
            val turns = if (last != null && last.isRunning) {
                it.turns.dropLast(1) + last.copy(status = STATUS_ERROR, error = failure.message)
            } else {
                it.turns
            }

            it.copy(turns = turns, asking = false, askFailure = failure)
        }
    }

    private fun fail(failure: ExplicaResult.Failure, title: String = "") {
        _state.update {
            it.copy(
                phase = ExplicaPhase.FAILED,
                title = title.ifBlank { it.title },
                stage = "",
                failure = failure,
            )
        }
    }

    companion object {
        const val POLL_MILLIS = 1_000L
        const val RETRY_MILLIS = 3_000L
        const val MAX_NETWORK_FAILURES = 5
        const val MAX_QUESTION_LENGTH = 500
    }
}
