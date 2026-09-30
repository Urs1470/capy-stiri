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
 * appears, goes or changes color at once, and is put back with a [HighlightFailure] when the server doesn't take
 * the change. Nothing is kept for later: a change made without a connection is lost, and the next [refresh]
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
     * Gives the highlight [id] another [color]. The server never changes the color of a passage it has, so this
     * is a removal followed by a new highlight of the same passage (with a new id and time). If the new one is
     * refused the old color is added back; if that fails too, the highlight is gone, as it is on the server.
     */
    fun recolor(id: String, color: HighlightColor) {
        val current = _state.value
        val index = current.items.indexOfFirst { it.id == id }

        if (entryId <= 0 || index < 0 || current.isPending(id)) {
            return
        }

        val original = current.items[index]

        if (original.color == color.wire) {
            return
        }

        _state.update {
            it.copy(
                items = it.items.map { item -> if (item.id == id) item.withColor(color) else item },
                pendingIds = it.pendingIds + id,
            )
        }

        viewModelScope.launch {
            val outcome = calls.withLock { changeColor(original, color) }

            _state.update { applyRecolor(it, original, outcome) }
        }
    }

    /** The reader selected more than the server takes (`HighlightAnchoring.MAX_PASSAGE`): nothing is sent. */
    fun selectionTooLong() {
        _state.update { it.copy(failure = failed(HighlightAction.SELECTION_TOO_LONG, kind = null)) }
    }

    fun dismissFailure(failure: HighlightFailure) {
        _state.update { if (it.failure == failure) it.copy(failure = null) else it }
    }

    private sealed interface Recolored {
        data class Done(val highlight: Highlight) : Recolored

        data class Unchanged(val kind: FailureKind) : Recolored

        data class Refused(val kind: FailureKind, val restored: Highlight?) : Recolored
    }

    private suspend fun changeColor(original: Highlight, color: HighlightColor): Recolored {
        val removal = api.removeHighlight(entryId, original.id)

        if (removal is ExplicaResult.Failure && removal.kind != FailureKind.NOT_FOUND) {
            return Recolored.Unchanged(removal.kind)
        }

        return when (val added = addAgain(original, color)) {
            is ExplicaResult.Success -> Recolored.Done(added.value)
            is ExplicaResult.Failure -> {
                val restored = addAgain(original, original.highlightColor)

                Recolored.Refused(added.kind, (restored as? ExplicaResult.Success)?.value)
            }
        }
    }

    private suspend fun addAgain(of: Highlight, color: HighlightColor): ExplicaResult<Highlight> {
        return api.addHighlight(entryId, of.text, color, of.where, of.prefix, of.suffix)
    }

    private fun applyRecolor(state: HighlightsState, original: Highlight, outcome: Recolored): HighlightsState {
        val settled = state.copy(pendingIds = state.pendingIds - original.id)

        return when (outcome) {
            is Recolored.Done -> settled.copy(items = replace(settled.items, original.id, outcome.highlight))

            is Recolored.Unchanged -> settled.copy(
                items = replace(settled.items, original.id, original),
                failure = failed(HighlightAction.RECOLOR, outcome.kind),
            )

            is Recolored.Refused -> settled.copy(
                items = replace(settled.items, original.id, outcome.restored),
                failure = failed(HighlightAction.RECOLOR, outcome.kind),
            )
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
