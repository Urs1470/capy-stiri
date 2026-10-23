package com.capyreader.app.ui.explica

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class HighlightAction {
    ADD,
    REMOVE,
    RECOLOR,
    NOTE,

    /** Not a call: the selection is longer than the server takes, so nothing was sent. */
    SELECTION_TOO_LONG,
}

/**
 * A change that the server refused or never got, and that was undone on screen. [kind] is why, and `null` when
 * no call was made; [serial] tells two equal failures apart.
 */
data class HighlightFailure(
    val action: HighlightAction,
    val kind: FailureKind?,
    val serial: Int,
)

/**
 * The highlights of one story. [items] are oldest first and include the ones the reader has just made and the
 * server hasn't confirmed yet ([pendingIds]); those can't be changed again until the call is over.
 */
data class HighlightsState(
    val items: List<Highlight> = emptyList(),
    val pendingIds: Set<String> = emptySet(),
    val loaded: Boolean = false,
    val loadFailed: Boolean = false,
    val failure: HighlightFailure? = null,
) {
    fun forWhere(where: String): List<Highlight> = items.filter { it.where == where }

    fun isPending(id: String): Boolean = id in pendingIds
}

/**
 * Backs the highlights of one story ([entryId] is the Miniflux entry id). The screen is optimistic: a highlight
 * appears, goes, changes color or gets its note at once, and is put back with a [HighlightFailure] when the server
 * doesn't take the change. Nothing is kept for later: a change made without a connection is lost, and the next [refresh]
 * shows what the server has. Calls reach the server one at a time, in the order they were made.
 */
class HighlightsViewModel(
    private val api: HighlightsApi,
    private val entryId: Long,
    private val clock: () -> Long = { System.currentTimeMillis() / 1_000 },
) : ViewModel() {
    private val _state = MutableStateFlow(HighlightsState())
    val state: StateFlow<HighlightsState> = _state.asStateFlow()

    private val calls = Mutex()
    private var refreshJob: Job? = null

    /** Removed on screen, the call still to come: a refresh that started earlier must not bring them back. */
    private val removing = mutableSetOf<String>()
    private var localCount = 0
    private var failureCount = 0

    init {
        refresh()
    }

    /** Reads the highlights from the server. Keeps what the reader has just changed and isn't confirmed yet. */
    fun refresh() {
        if (entryId <= 0 || refreshJob?.isActive == true) {
            return
        }

        refreshJob = viewModelScope.launch {
            when (val result = calls.withLock { api.listHighlights(entryId) }) {
                is ExplicaResult.Success -> _state.update { merge(it, result.value) }
                is ExplicaResult.Failure -> _state.update { it.copy(loadFailed = true) }
            }
        }
    }

    /**
     * Highlights [text] in [where]. A passage that is already highlighted there only changes color (when the
     * color differs), because the server keeps one highlight per passage.
     */
    fun add(text: String, color: HighlightColor, where: String, prefix: String = "", suffix: String = "") {
        if (entryId <= 0 || text.isBlank()) {
            return
        }

        val existing = _state.value.items.firstOrNull { it.where == where && it.text == text }

        if (existing != null) {
            recolor(existing.id, color)
            return
        }

        val local = Highlight(
            id = "local-${++localCount}",
            text = text,
            color = color.wire,
            where = where,
            prefix = prefix,
            suffix = suffix,
            created = clock(),
        )

        _state.update { it.copy(items = it.items + local, pendingIds = it.pendingIds + local.id) }

        viewModelScope.launch {
            val result = calls.withLock { api.addHighlight(entryId, text, color, where, prefix, suffix) }

            _state.update {
                when (result) {
                    is ExplicaResult.Success -> confirm(it, local.id, result.value)
                    is ExplicaResult.Failure -> it.copy(
                        items = it.items.filterNot { item -> item.id == local.id },
                        pendingIds = it.pendingIds - local.id,
                        failure = failed(HighlightAction.ADD, result.kind),
                    )
                }
            }
        }
    }

    fun remove(id: String) {
        val current = _state.value
        val index = current.items.indexOfFirst { it.id == id }

        if (entryId <= 0 || index < 0 || current.isPending(id)) {
            return
        }

        val removed = current.items[index]

        removing += id
        _state.update { it.copy(items = it.items.filterNot { item -> item.id == id }) }

        viewModelScope.launch {
            val result = calls.withLock { api.removeHighlight(entryId, id) }

            removing -= id

            // A highlight the server doesn't know is already gone, which is what was asked for.
            if (result is ExplicaResult.Failure && result.kind != FailureKind.NOT_FOUND) {
                _state.update { restore(it, removed, index).copy(failure = failed(HighlightAction.REMOVE, result.kind)) }
            }
        }
    }

    /**
     * Gives the highlight [id] another [color], in place on the server too (`op: update`: same id, time and note).
     * Until 2026-10-23 the server couldn't change a color, so this was a removal and a new highlight, which would now
     * lose the note. The old color comes back when the server refuses; a highlight it doesn't know any more is gone.
     */
    fun recolor(id: String, color: HighlightColor) {
        val original = changeable(id) ?: return

        if (original.color == color.wire) {
            return
        }

        change(original, original.withColor(color), HighlightAction.RECOLOR) {
            api.updateHighlight(entryId, id, color = color)
        }
    }

    /**
     * Writes the reader's [note] on the highlight [id] (trimmed; an empty one removes the note), at once on screen. The
     * note that was there comes back when the server refuses; a highlight it doesn't know any more is gone.
     */
    fun setNote(id: String, note: String) {
        val original = changeable(id) ?: return
        val clean = note.trim().take(Highlight.MAX_NOTE)

        if (clean == original.note) {
            return
        }

        change(original, original.copy(note = clean), HighlightAction.NOTE) {
            api.updateHighlight(entryId, id, note = clean)
        }
    }

    /** The reader selected more than the server takes (`HighlightAnchoring.MAX_PASSAGE`): nothing is sent. */
    fun selectionTooLong() {
        _state.update { it.copy(failure = failed(HighlightAction.SELECTION_TOO_LONG, kind = null)) }
    }

    fun dismissFailure(failure: HighlightFailure) {
        _state.update { if (it.failure == failure) it.copy(failure = null) else it }
    }

    /** The highlight [id] when it can be changed now: it exists and no call about it is under way. */
    private fun changeable(id: String): Highlight? {
        val current = _state.value

        if (entryId <= 0 || current.isPending(id)) {
            return null
        }

        return current.items.firstOrNull { it.id == id }
    }

    /**
     * Shows [changed] in the place of [original] at once, then sends the [call]: the server's answer replaces it, a
     * refusal puts [original] back with a [HighlightFailure] of [action], and a highlight the server doesn't know any
     * more ([FailureKind.NOT_FOUND]) leaves the screen, as it left the server.
     */
    private fun change(
        original: Highlight,
        changed: Highlight,
        action: HighlightAction,
        call: suspend () -> ExplicaResult<Highlight>,
    ) {
        _state.update {
            it.copy(
                items = it.items.map { item -> if (item.id == original.id) changed else item },
                pendingIds = it.pendingIds + original.id,
            )
        }

        viewModelScope.launch {
            val result = calls.withLock { call() }

            _state.update {
                val settled = it.copy(pendingIds = it.pendingIds - original.id)

                when (result) {
                    is ExplicaResult.Success -> settled.copy(items = replace(settled.items, original.id, result.value))

                    is ExplicaResult.Failure -> settled.copy(
                        items = replace(
                            settled.items,
                            original.id,
                            original.takeIf { result.kind != FailureKind.NOT_FOUND },
                        ),
                        failure = failed(action, result.kind),
                    )
                }
            }
        }
    }

    /** [replacement] in the place of the highlight [id], or no highlight when [replacement] is `null`. */
    private fun replace(items: List<Highlight>, id: String, replacement: Highlight?): List<Highlight> {
        val without = items.filterNot { it.id == id }

        if (replacement == null || without.any { it.id == replacement.id }) {
            return without
        }

        return items.map { if (it.id == id) replacement else it }
    }

    private fun confirm(state: HighlightsState, localId: String, confirmed: Highlight): HighlightsState {
        return state.copy(
            items = replace(state.items, localId, confirmed),
            pendingIds = state.pendingIds - localId,
        )
    }

    private fun restore(state: HighlightsState, highlight: Highlight, index: Int): HighlightsState {
        if (state.items.any { it.id == highlight.id }) {
            return state
        }

        val items = state.items.toMutableList()

        items.add(index.coerceAtMost(items.size), highlight)

        return state.copy(items = items)
    }

    private fun merge(state: HighlightsState, fromServer: List<Highlight>): HighlightsState {
        val serverIds = fromServer.map { it.id }.toSet()
        val local = state.items.associateBy { it.id }
        val confirmed = fromServer
            .filterNot { it.id in removing }
            .map { server -> if (server.id in state.pendingIds) local[server.id] ?: server else server }
        val notSavedYet = state.items.filter { it.id in state.pendingIds && it.id !in serverIds }

        return state.copy(items = confirmed + notSavedYet, loaded = true, loadFailed = false)
    }

    private fun failed(action: HighlightAction, kind: FailureKind?): HighlightFailure {
        return HighlightFailure(action = action, kind = kind, serial = ++failureCount)
    }
}
