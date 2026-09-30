package com.capyreader.app.ui.explica

import com.jocmp.capy.logging.CapyLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration

/** The folder under `filesDir` where the explanations are kept. */
const val SAVED_EXPLANATIONS_DIRECTORY = "explica"

/** The most explanations kept; the oldest go first. */
const val SAVED_EXPLANATIONS_MAX = 300

/** An explanation that was not saved again for this long is dropped. */
val SAVED_EXPLANATIONS_MAX_AGE: Duration = Duration.ofDays(90)

/** What `meta` of a saved explanation says, so that the reader knows it is not the server's answer of this moment. */
const val SAVED_COPY_MARK = "saved copy"

/** The failures that leave the server's answer unknown, the only ones for which a saved copy is shown. */
private val FAILURES_WITH_A_COPY = setOf(FailureKind.NETWORK, FailureKind.UNAVAILABLE, FailureKind.NOT_FOUND)

/**
 * The last finished explanation of each story, kept on the phone: one small JSON file per story
 * (`<entry id>.json` in [directory], the server's own [ExplainResponse]), so a story explained once can be read
 * again without a connection and after the server let go of it (it keeps the explanations of stories that are not
 * starred for 14 days, then answers 404).
 *
 * A file's age is the modification time, which [write] and [updateChat] set from [clock]: [prune] drops the files
 * older than [maxAge] and, beyond [maxFiles], the oldest. It only lists and stats files, so it is cheap.
 * Nothing here throws on a disk problem: a copy that can't be written or read is simply not there.
 */
class SavedExplanationStore(
    private val directory: File,
    private val clock: Clock = Clock.systemUTC(),
    private val maxFiles: Int = SAVED_EXPLANATIONS_MAX,
    private val maxAge: Duration = SAVED_EXPLANATIONS_MAX_AGE,
    private val json: Json = ExplicaJson,
) {
    private val lock = Any()

    /** The saved explanation of [entryId], or `null` when there is none (or the file is not one any more). */
    fun read(entryId: Long): ExplainResponse? = synchronized(lock) { load(entryId) }

    /**
     * Keeps [response] as the explanation of [entryId] when it is a finished one: `done`, with text, and not the
     * entry of the day (which has none). What only mattered while it was being written (the stage, a partial text,
     * the quota) is not kept, nor is an answer that is still being written in the chat.
     */
    fun write(entryId: Long, response: ExplainResponse) = synchronized(lock) {
        if (entryId <= 0 || response.status != STATUS_DONE || response.day || response.htmlApp.isBlank()) {
            return@synchronized
        }

        val isNew = !fileOf(entryId).exists()

        store(
            entryId,
            ExplainResponse(
                status = STATUS_DONE,
                title = response.title,
                link = response.link,
                htmlApp = response.htmlApp,
                meta = response.meta,
                suggest = response.suggest,
                chat = response.chat.withoutRunning(),
            ),
        )

        if (isNew) {
            prune()
        }
    }

    /**
     * Brings the chat of the saved explanation of [entryId] up to date with [chat], as the server last sent it.
     * Does nothing when there is no saved explanation (a chat alone is not kept) or the chat is the same, so the
     * polls of an answer that is being written don't touch the disk.
     */
    fun updateChat(entryId: Long, chat: List<ExplicaTurn>) = synchronized(lock) {
        val saved = load(entryId) ?: return@synchronized
        val settled = chat.withoutRunning()

        if (settled != saved.chat) {
            store(entryId, saved.copy(chat = settled))
        }
    }

    /** Drops what is too old, then the oldest beyond [maxFiles]; also the leftovers of a write that was cut short. */
    fun prune() = synchronized(lock) {
        val files = directory.listFiles().orEmpty()

        files.filter { it.name.endsWith(TEMP_SUFFIX) }.forEach { it.delete() }

        val cutoff = clock.millis() - maxAge.toMillis()
        val (old, recent) = files
            .filter { SAVED_NAME.matches(it.name) }
            .partition { it.lastModified() < cutoff }

        old.forEach { it.delete() }

        // Newest first; of two saved at the same moment the story with the higher id is the newer one.
        recent
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { idOf(it) })
            .drop(maxFiles)
            .forEach { it.delete() }
    }

    private fun fileOf(entryId: Long) = File(directory, "$entryId.json")

    private fun idOf(file: File) = file.name.removeSuffix(".json").toLongOrNull() ?: 0L

    private fun load(entryId: Long): ExplainResponse? {
        val file = fileOf(entryId)

        if (!file.isFile) {
            return null
        }

        return try {
            json.decodeFromString(ExplainResponse.serializer(), file.readText())
                .takeIf { it.status == STATUS_DONE && it.htmlApp.isNotBlank() }
                ?: discard(file)
        } catch (e: SerializationException) {
            discard(file)
        } catch (e: IllegalArgumentException) {
            discard(file)
        } catch (e: IOException) {
            CapyLog.error("explica_saved_read", e)
            null
        }
    }

    /** A file that is not a finished explanation is no use to anybody: it goes, and nothing is shown for it. */
    private fun discard(file: File): ExplainResponse? {
        file.delete()

        return null
    }

    /** Writes next to the file and moves it into place, so that a write cut short leaves the old copy, not half of one. */
    private fun store(entryId: Long, response: ExplainResponse) {
        val target = fileOf(entryId)
        val temp = File(directory, "$entryId.json$TEMP_SUFFIX")

        try {
            directory.mkdirs()
            temp.writeText(json.encodeToString(ExplainResponse.serializer(), response))
            temp.setLastModified(clock.millis())

            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            CapyLog.error("explica_saved_write", e)
            temp.delete()
        }
    }

    private companion object {
        val SAVED_NAME = Regex("""[0-9]+\.json""")
        const val TEMP_SUFFIX = ".tmp"
    }
}

private fun List<ExplicaTurn>.withoutRunning() = filterNot { it.isRunning }

/**
 * `meta` of a saved explanation, with [SAVED_COPY_MARK] after it ("MiniMax-M3 · 13 s · saved copy"); the mark alone
 * when the explanation came without one.
 */
internal fun String.markedAsSavedCopy(): String {
    return if (isBlank()) SAVED_COPY_MARK.replaceFirstChar { it.uppercase() } else "$this · $SAVED_COPY_MARK"
}

/**
 * Keeps the explanations on the phone (see [SavedExplanationStore]) in front of the server's [ExplicaApi], and never
 * changes what is sent to it.
 *
 * The network goes first, for every call, the read-only open (`start = false`) too. A `done` answer of `explain`
 * replaces the saved explanation of the story, and every successful `ask` brings its chat up to date. Only when the
 * call fails in a way that leaves the server's answer unknown (no connection, the server can't check the token, or
 * the story is gone from the server: [FAILURES_WITH_A_COPY]) and a copy exists, the copy is the answer, with
 * [SAVED_COPY_MARK] at the end of its `meta`. An answer from the server is never replaced by the copy, whatever it
 * says, and a failure that says something about the reader (the token, the cap, a busy chat) is shown as it is.
 *
 * The folder is tidied when this is created. Files are read and written off the main thread ([io]).
 */
class SavedExplanationsApi(
    private val delegate: ExplicaApi,
    private val store: SavedExplanationStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ExplicaApi {
    init {
        store.prune()
    }

    override suspend fun explain(entryId: Long, retry: Boolean, start: Boolean): ExplicaResult<ExplainResponse> {
        return when (val result = delegate.explain(entryId, retry, start)) {
            is ExplicaResult.Success -> {
                if (result.value.status == STATUS_DONE) {
                    // A cancelled screen doesn't lose an answer that has arrived.
                    withContext(io + NonCancellable) { store.write(entryId, result.value) }
                }

                result
            }

            is ExplicaResult.Failure -> savedCopyAfter(entryId, result) ?: result
        }
    }

    override suspend fun ask(entryId: Long, question: String?): ExplicaResult<AskResponse> {
        val result = delegate.ask(entryId, question)

        if (result is ExplicaResult.Success) {
            withContext(io + NonCancellable) { store.updateChat(entryId, result.value.chat) }
        }

        return result
    }

    private suspend fun savedCopyAfter(
        entryId: Long,
        failure: ExplicaResult.Failure,
    ): ExplicaResult<ExplainResponse>? {
        if (failure.kind !in FAILURES_WITH_A_COPY) {
            return null
        }

        val copy = withContext(io) { store.read(entryId) } ?: return null

        CapyLog.info("explica_saved_copy", mapOf("entry_id" to entryId, "reason" to failure.kind.name))

        return ExplicaResult.Success(copy.copy(meta = copy.meta.markedAsSavedCopy()))
    }
}
