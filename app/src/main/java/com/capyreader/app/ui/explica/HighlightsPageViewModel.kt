package com.capyreader.app.ui.explica

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Highlights page: every highlight of the reader, one card per story, the story highlighted last first.
 * [failure] is why the last read didn't work; the stories read before stay on screen. [missing] is a story the reader
 * tapped that is no longer on the phone (the app keeps articles for a while only), with the link to its source.
 */
data class HighlightsPageState(
    val stories: List<StoryHighlights> = emptyList(),
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val failure: FailureKind? = null,
    val missing: StoryHighlights? = null,
)

/**
 * Backs the Highlights page (this fork). [hasArticle] tells whether the story with that article id is on the phone,
 * where the reader can open it; the page itself only reads, the highlights are changed from the story.
 */
class HighlightsPageViewModel(
    private val api: AllHighlightsApi,
    private val hasArticle: suspend (articleID: String) -> Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(HighlightsPageState())
    val state: StateFlow<HighlightsPageState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    init {
        refresh()
    }

    /** Reads all the highlights again; one read at a time, the newest request wins. */
    fun refresh() {
        refreshJob?.cancel()
        _state.update { it.copy(loading = true) }

        refreshJob = viewModelScope.launch {
            when (val result = api.allHighlights()) {
                is ExplicaResult.Success -> _state.update {
                    it.copy(stories = result.value, loading = false, loaded = true, failure = null)
                }

                is ExplicaResult.Failure -> _state.update {
                    it.copy(loading = false, failure = result.kind)
                }
            }
        }
    }

    /** Opens the [story] in the reader through [onOpen], or, when it isn't on the phone any more, says so ([missing]). */
    fun open(story: StoryHighlights, onOpen: (articleID: String) -> Unit) {
        viewModelScope.launch {
            val articleID = story.entryId.toString()

            if (hasArticle(articleID)) {
                onOpen(articleID)
            } else {
                _state.update { it.copy(missing = story) }
            }
        }
    }

    fun dismissMissing() {
        _state.update { it.copy(missing = null) }
    }
}
