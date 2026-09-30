package com.capyreader.app.ui.explica

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HighlightsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A server that keeps the highlights in a list, and answers what the test scripts instead when it is told to. */
    private class FakeApi : HighlightsApi {
        val server = mutableListOf<Highlight>()
        val log = mutableListOf<String>()
        val listResults = ArrayDeque<ExplicaResult<List<Highlight>>>()
        val addResults = ArrayDeque<ExplicaResult<Highlight>>()
        val removeResults = ArrayDeque<ExplicaResult<Unit>>()

        /** Each call takes the first gate there is and waits for it to open, so a test can hold a call in flight. */
        val gates = ArrayDeque<CompletableDeferred<Unit>>()

        private var created = 0

        override suspend fun listHighlights(entryId: Long): ExplicaResult<List<Highlight>> {
            log += "list"
            gates.removeFirstOrNull()?.await()

            return listResults.removeFirstOrNull() ?: ExplicaResult.Success(server.toList())
        }

        override suspend fun addHighlight(
            entryId: Long,
            text: String,
            color: HighlightColor,
            where: String,
            prefix: String,
            suffix: String,
        ): ExplicaResult<Highlight> {
            log += "add:$text:${color.wire}"
            gates.removeFirstOrNull()?.await()
            addResults.removeFirstOrNull()?.let { return it }

            val existing = server.firstOrNull { it.where == where && it.text == text }

            if (existing != null) {
                return ExplicaResult.Success(existing)
            }

            val highlight = Highlight(
                id = "n${++created}",
                text = text,
                color = color.wire,
                where = where,
                prefix = prefix,
                suffix = suffix,
                created = 1_000L + created,
            )

            server += highlight

            return ExplicaResult.Success(highlight)
        }

        override suspend fun removeHighlight(entryId: Long, id: String): ExplicaResult<Unit> {
            log += "remove:$id"
            gates.removeFirstOrNull()?.await()
            removeResults.removeFirstOrNull()?.let { return it }

            return if (server.removeAll { it.id == id }) {
                ExplicaResult.Success(Unit)
            } else {
                ExplicaResult.Failure(FailureKind.NOT_FOUND)
            }
        }
    }

    private fun highlight(
        id: String,
        text: String,
        color: String = "yellow",
        where: String = "explanation",
    ) = Highlight(id = id, text = text, color = color, where = where, created = 10)

    private fun viewModel(api: FakeApi, entryId: Long = 42) =
        HighlightsViewModel(api = api, entryId = entryId, clock = { 1_700_000_000L })

    private fun FakeApi.withServer(vararg highlights: Highlight) = apply { server += highlights }

    @Test
    fun opening_readsTheHighlights_oldestFirst() = runTest(dispatcher) {
        val api = FakeApi().withServer(
            highlight("s1", "first"),
            highlight("s2", "second", where = "story"),
            highlight("s3", "third", where = "answer:0"),
        )

        val vm = viewModel(api)
        assertFalse(vm.state.value.loaded)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.loaded)
        assertFalse(state.loadFailed)
        assertEquals(listOf("s1", "s2", "s3"), state.items.map { it.id })
        assertEquals(listOf("first"), state.forWhere("explanation").map { it.text })
        assertEquals(listOf("third"), state.forWhere("answer:0").map { it.text })
        assertEquals(listOf("list"), api.log)
    }

    @Test
    fun add_showsTheHighlightAtOnce_andTakesTheServersVersionWhenItAnswers() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = viewModel(api)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        api.gates += gate

        vm.add("a passage", HighlightColor.GREEN, "explanation", prefix = "before ", suffix = " after")

        val shown = vm.state.value.items.single()
        assertEquals("a passage", shown.text)
        assertEquals("green", shown.color)
        assertEquals("explanation", shown.where)
        assertEquals("before ", shown.prefix)
        assertEquals(1_700_000_000L, shown.created)
        assertTrue(vm.state.value.isPending(shown.id))

        runCurrent()
        assertEquals(listOf("list", "add:a passage:green"), api.log)
        assertTrue(vm.state.value.isPending(shown.id))

        gate.complete(Unit)
        advanceUntilIdle()

        val confirmed = vm.state.value.items.single()
        assertEquals("n1", confirmed.id)
        assertEquals(1_001L, confirmed.created)
        assertEquals(emptySet<String>(), vm.state.value.pendingIds)
        assertNull(vm.state.value.failure)
    }

    @Test
    fun add_aPassageTheServerAlreadyHas_doesNotMakeTwo() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = viewModel(api)
        advanceUntilIdle()
        // Highlighted on another device since this screen read the list.
        api.server += highlight("s1", "known passage")

        vm.add("known passage", HighlightColor.YELLOW, "explanation")
        advanceUntilIdle()

        assertEquals(listOf("s1"), vm.state.value.items.map { it.id })
        assertEquals(emptySet<String>(), vm.state.value.pendingIds)
    }

    @Test
    fun add_whenTheServerRefuses_takesTheHighlightBackAndSaysWhy() = runTest(dispatcher) {
        val api = FakeApi().apply { addResults += ExplicaResult.Failure(FailureKind.NETWORK, "offline") }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.add("a passage", HighlightColor.PINK, "story")

        assertEquals(listOf("a passage"), vm.state.value.items.map { it.text })

        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.items.isEmpty())
        assertTrue(state.pendingIds.isEmpty())
        assertEquals(HighlightAction.ADD, state.failure?.action)
        assertEquals(FailureKind.NETWORK, state.failure?.kind)
    }

    @Test
    fun add_aPassageThatIsHighlighted_isNotSentAgain_andAnotherColorChangesTheColor() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.add("a passage", HighlightColor.YELLOW, "story")
        advanceUntilIdle()
        vm.add("a passage", HighlightColor.YELLOW, "story")
        advanceUntilIdle()

        assertEquals(listOf("list", "add:a passage:yellow"), api.log)

        vm.add("a passage", HighlightColor.PINK, "story")
        advanceUntilIdle()

        assertEquals(listOf("list", "add:a passage:yellow", "remove:n1", "add:a passage:pink"), api.log)
        assertEquals(listOf("pink"), vm.state.value.items.map { it.color })

        // The same words in another place are another highlight.
        vm.add("a passage", HighlightColor.YELLOW, "explanation")
        advanceUntilIdle()

        assertEquals(listOf("story", "explanation"), vm.state.value.items.map { it.where })
    }

    @Test
    fun add_blankText_orWithoutAnEntry_isIgnored() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = viewModel(api)
        val none = viewModel(api, entryId = 0)
        advanceUntilIdle()

        vm.add("   ", HighlightColor.YELLOW, "story")
        none.add("a passage", HighlightColor.YELLOW, "story")
        none.refresh()
        advanceUntilIdle()

        assertTrue(vm.state.value.items.isEmpty())
        assertTrue(none.state.value.items.isEmpty())
        assertEquals(listOf("list"), api.log)
    }

    @Test
    fun remove_takesTheHighlightAtOnce_andKeepsItGoneWhenTheServerConfirms() = runTest(dispatcher) {
        val api = FakeApi().withServer(highlight("s1", "one"), highlight("s2", "two"))
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.remove("s1")

        assertEquals(listOf("s2"), vm.state.value.items.map { it.id })

        advanceUntilIdle()

        assertEquals(listOf("s2"), vm.state.value.items.map { it.id })
        assertEquals(listOf("s2"), api.server.map { it.id })
        assertNull(vm.state.value.failure)
    }

    @Test
    fun remove_whenTheServerRefuses_putsTheHighlightBackInItsPlace() = runTest(dispatcher) {
        val api = FakeApi()
            .withServer(highlight("s1", "one"), highlight("s2", "two"), highlight("s3", "three"))
            .apply { removeResults += ExplicaResult.Failure(FailureKind.SERVER) }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.remove("s2")
        assertEquals(listOf("s1", "s3"), vm.state.value.items.map { it.id })

        advanceUntilIdle()

        assertEquals(listOf("s1", "s2", "s3"), vm.state.value.items.map { it.id })
        assertEquals(HighlightAction.REMOVE, vm.state.value.failure?.action)
    }

    @Test
    fun remove_aHighlightTheServerDoesNotKnow_staysRemovedWithoutAnError() = runTest(dispatcher) {
        val api = FakeApi().withServer(highlight("s1", "one"), highlight("s2", "two"))
        val vm = viewModel(api)
        advanceUntilIdle()
        // Removed on another device in the meantime.
        api.server.removeAll { it.id == "s2" }

        vm.remove("s2")
        advanceUntilIdle()

        assertEquals(listOf("s1"), vm.state.value.items.map { it.id })
        assertNull(vm.state.value.failure)
    }

    @Test
    fun remove_aHighlightThatIsNotThere_orNotSavedYet_isIgnored() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = viewModel(api)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        api.gates += gate

        vm.add("a passage", HighlightColor.BLUE, "story")
        val localId = vm.state.value.items.single().id
        runCurrent()

        vm.remove(localId)
        vm.remove("nobody")
        vm.recolor(localId, HighlightColor.PINK)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("list", "add:a passage:blue"), api.log)
        assertEquals(listOf("blue"), vm.state.value.items.map { it.color })
        assertNull(vm.state.value.failure)
    }

    @Test
    fun recolor_changesTheColorAtOnce_thenRemovesAndAddsTheHighlightOnTheServer() = runTest(dispatcher) {
        val api = FakeApi().withServer(highlight("s1", "one", color = "yellow"))
        val vm = viewModel(api)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        api.gates += gate

        vm.recolor("s1", HighlightColor.BLUE)

        assertEquals(listOf("blue"), vm.state.value.items.map { it.color })
        assertTrue(vm.state.value.isPending("s1"))

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("list", "remove:s1", "add:one:blue"), api.log)
        val item = vm.state.value.items.single()
        assertEquals("n1", item.id)
        assertEquals("blue", item.color)
        assertTrue(vm.state.value.pendingIds.isEmpty())
        assertNull(vm.state.value.failure)
    }

    @Test
    fun recolor_toTheColorItHas_doesNothing() = runTest(dispatcher) {
        val api = FakeApi().withServer(highlight("s1", "one", color = "green"))
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.recolor("s1", HighlightColor.GREEN)
        advanceUntilIdle()

        assertEquals(listOf("list"), api.log)
    }

    @Test
    fun recolor_whenTheRemovalFails_putsTheOldColorBack() = runTest(dispatcher) {
        val api = FakeApi()
            .withServer(highlight("s1", "one", color = "yellow"))
            .apply { removeResults += ExplicaResult.Failure(FailureKind.NETWORK) }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.recolor("s1", HighlightColor.PINK)
        advanceUntilIdle()

        assertEquals(listOf("list", "remove:s1"), api.log)
        val item = vm.state.value.items.single()
        assertEquals("s1", item.id)
        assertEquals("yellow", item.color)
        assertTrue(vm.state.value.pendingIds.isEmpty())
        assertEquals(HighlightAction.RECOLOR, vm.state.value.failure?.action)
        assertEquals(FailureKind.NETWORK, vm.state.value.failure?.kind)
    }

    @Test
    fun recolor_whenTheNewHighlightIsRefused_addsTheOldColorBack() = runTest(dispatcher) {
        val api = FakeApi()
            .withServer(highlight("s1", "one", color = "yellow"))
            .apply { addResults += ExplicaResult.Failure(FailureKind.RATE_LIMITED) }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.recolor("s1", HighlightColor.BLUE)
        advanceUntilIdle()

        assertEquals(listOf("list", "remove:s1", "add:one:blue", "add:one:yellow"), api.log)
        val item = vm.state.value.items.single()
        assertEquals("yellow", item.color)
        assertNotEquals("s1", item.id)
        assertEquals(listOf(item.id), api.server.map { it.id })
        assertEquals(FailureKind.RATE_LIMITED, vm.state.value.failure?.kind)
    }

    @Test
    fun recolor_whenNothingCanBeAddedAnyMore_dropsTheHighlightLikeTheServerDid() = runTest(dispatcher) {
        val api = FakeApi()
            .withServer(highlight("s1", "one", color = "yellow"))
            .apply {
                addResults += ExplicaResult.Failure(FailureKind.NETWORK)
                addResults += ExplicaResult.Failure(FailureKind.NETWORK)
            }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.recolor("s1", HighlightColor.GREEN)
        advanceUntilIdle()

        assertTrue(vm.state.value.items.isEmpty())
        assertTrue(api.server.isEmpty())
        assertEquals(HighlightAction.RECOLOR, vm.state.value.failure?.action)
    }

    @Test
    fun refresh_keepsWhatIsNotSavedYet_andDoesNotBringBackWhatWasJustRemoved() = runTest(dispatcher) {
        val api = FakeApi().withServer(highlight("s1", "one"), highlight("s2", "two"))
        val vm = viewModel(api)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        api.gates += gate
        // The list that is being read was made before the changes below.
        api.listResults += ExplicaResult.Success(listOf(highlight("s1", "one"), highlight("s2", "two")))

        vm.refresh()
        runCurrent()
        vm.add("new one", HighlightColor.PINK, "story")
        vm.remove("s2")
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("s1", vm.state.value.items.last().id), vm.state.value.items.map { it.id })
        assertEquals("new one", vm.state.value.items.last().text)

        advanceUntilIdle()

        assertEquals(listOf("list", "list", "add:new one:pink", "remove:s2"), api.log)
        assertEquals(listOf("s1", "n1"), vm.state.value.items.map { it.id })
        assertTrue(vm.state.value.pendingIds.isEmpty())
    }

    @Test
    fun refresh_whenItFails_keepsTheListAndSaysSo_andRecoversNextTime() = runTest(dispatcher) {
        val api = FakeApi().withServer(highlight("s1", "one"))
        val vm = viewModel(api)
        advanceUntilIdle()
        api.listResults += ExplicaResult.Failure(FailureKind.NETWORK)

        vm.refresh()
        advanceUntilIdle()

        assertEquals(listOf("s1"), vm.state.value.items.map { it.id })
        assertTrue(vm.state.value.loadFailed)

        api.server += highlight("s2", "two")
        vm.refresh()
        advanceUntilIdle()

        assertEquals(listOf("s1", "s2"), vm.state.value.items.map { it.id })
        assertFalse(vm.state.value.loadFailed)
    }

    @Test
    fun refresh_whileOneIsRunning_isNotRepeated() = runTest(dispatcher) {
        val api = FakeApi()
        val gate = CompletableDeferred<Unit>()
        api.gates += gate
        val vm = viewModel(api)
        runCurrent()

        vm.refresh()
        vm.refresh()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("list"), api.log)

        vm.refresh()
        advanceUntilIdle()

        assertEquals(listOf("list", "list"), api.log)
    }

    @Test
    fun calls_reachTheServerOneAtATime_inTheOrderTheyWereMade() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = viewModel(api)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        api.gates += gate

        vm.add("first", HighlightColor.YELLOW, "story")
        vm.add("second", HighlightColor.GREEN, "story")
        runCurrent()

        // Both are on screen, but only the first call has left.
        assertEquals(listOf("first", "second"), vm.state.value.items.map { it.text })
        assertEquals(listOf("list", "add:first:yellow"), api.log)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("list", "add:first:yellow", "add:second:green"), api.log)
        assertEquals(listOf("n1", "n2"), vm.state.value.items.map { it.id })
    }

    @Test
    fun dismissFailure_onlyClearsTheFailureItWasGiven() = runTest(dispatcher) {
        val api = FakeApi().apply {
            addResults += ExplicaResult.Failure(FailureKind.NETWORK)
            addResults += ExplicaResult.Failure(FailureKind.NETWORK)
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.add("one", HighlightColor.YELLOW, "story")
        advanceUntilIdle()
        val first = vm.state.value.failure!!
        vm.add("two", HighlightColor.YELLOW, "story")
        advanceUntilIdle()
        val second = vm.state.value.failure!!

        // Two failures of the same kind are still two events.
        assertNotEquals(first, second)

        vm.dismissFailure(first)
        assertEquals(second, vm.state.value.failure)

        vm.dismissFailure(second)
        assertNull(vm.state.value.failure)
    }

    @Test
    fun aSelectionThatIsTooLong_isAMessageAndNoCall() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.selectionTooLong()

        val failure = vm.state.value.failure!!
        assertEquals(HighlightAction.SELECTION_TOO_LONG, failure.action)
        assertNull(failure.kind)
        assertEquals(listOf("list"), api.log)
        assertTrue(vm.state.value.items.isEmpty())

        vm.dismissFailure(failure)
        assertNull(vm.state.value.failure)
    }

    @Test
    fun aStoryWithoutAnEntryId_callsNothing() = runTest(dispatcher) {
        val api = FakeApi().withServer(highlight("s1", "one"))
        val vm = viewModel(api, entryId = 0)
        advanceUntilIdle()

        vm.add("a passage", HighlightColor.YELLOW, "story")
        vm.remove("s1")
        vm.recolor("s1", HighlightColor.PINK)
        advanceUntilIdle()

        assertTrue(api.log.isEmpty())
        assertTrue(vm.state.value.items.isEmpty())
    }
}
