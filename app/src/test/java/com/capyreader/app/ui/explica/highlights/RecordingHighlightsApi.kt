package com.capyreader.app.ui.explica.highlights

import com.capyreader.app.ui.explica.ExplicaResult
import com.capyreader.app.ui.explica.FailureKind
import com.capyreader.app.ui.explica.Highlight
import com.capyreader.app.ui.explica.HighlightColor
import com.capyreader.app.ui.explica.HighlightsApi

/** The server as the screen tests see it: it keeps what it is given and remembers every call. */
internal class RecordingHighlightsApi(saved: List<Highlight> = emptyList()) : HighlightsApi {
    val server = saved.toMutableList()
    val calls = mutableListOf<String>()

    /** When set, the server refuses every new highlight for this reason. */
    var refuseAdds: FailureKind? = null

    override suspend fun listHighlights(entryId: Long): ExplicaResult<List<Highlight>> {
        calls += "list:$entryId"

        return ExplicaResult.Success(server.toList())
    }

    override suspend fun addHighlight(
        entryId: Long,
        text: String,
        color: HighlightColor,
        where: String,
        prefix: String,
        suffix: String,
    ): ExplicaResult<Highlight> {
        calls += "add:$entryId:$text:${color.wire}:$where:$prefix|$suffix"

        refuseAdds?.let { return ExplicaResult.Failure(it) }

        val highlight = Highlight(
            id = "s${server.size + 1}",
            text = text,
            color = color.wire,
            where = where,
            prefix = prefix,
            suffix = suffix,
            created = 1_000,
        )

        server += highlight

        return ExplicaResult.Success(highlight)
    }

    override suspend fun removeHighlight(entryId: Long, id: String): ExplicaResult<Unit> {
        calls += "remove:$id"
        server.removeAll { it.id == id }

        return ExplicaResult.Success(Unit)
    }
}
