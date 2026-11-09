package com.capyreader.app.ui.articles.audio

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.capyreader.app.ui.explica.highlights.ComposeScreen
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The progress bar of the full player on a stand-in player, on Robolectric (see [ComposeScreen]): the clock beside
 * the bar follows a finger while it drags, and the player is asked to seek once, when the finger lifts.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AudioSeekBarTest {
    private lateinit var screen: ComposeScreen
    private val player = SeekRecordingPlayer(durationMs = 40 * 60_000L)

    @After
    fun tearDown() {
        if (::screen.isInitialized) screen.destroy()
    }

    private fun show() {
        screen = ComposeScreen.show {
            MaterialTheme {
                AudioSeekBar(player = player)
            }
        }
    }

    private fun texts() = screen.drawn().map { it.text }

    private fun slider(): SemanticsNode {
        return screen.nodes().first { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null }
    }

    @Test
    fun theClocks_startAtZeroAndShowTheLength() {
        show()

        assertTrue(texts().toString(), "0:00" in texts())
        assertTrue(texts().toString(), "40:00" in texts())
    }

    @Test
    fun draggingTheBar_showsWhereItWouldLand_andSeeksOnlyOnRelease() {
        show()

        val bounds = slider().boundsInRoot
        val y = bounds.center.y
        val from = Offset(bounds.left + bounds.width * 0.1f, y)
        val to = Offset(bounds.left + bounds.width * 0.75f, y)

        val downTime = screen.newGestureTime()
        screen.touch(MotionEvent.ACTION_DOWN, from, downTime, downTime)
        screen.touch(MotionEvent.ACTION_MOVE, Offset((from.x + to.x) / 2, y), downTime, downTime + 20)
        screen.touch(MotionEvent.ACTION_MOVE, to, downTime, downTime + 40)
        screen.stepUntil { SystemClock.uptimeMillis(); texts().any { it != "0:00" && it != "40:00" } }
        val whileDragging = texts()
        val seeksBeforeRelease = player.seeks.toList()

        screen.touch(MotionEvent.ACTION_UP, to, downTime, downTime + 60)
        screen.stepUntil { player.seeks.isNotEmpty() }

        // 75 % of 40 minutes is 30:00; the bar has one step per pixel, so allow a few seconds
        val shown = whileDragging.first { it != "0:00" && it != "40:00" }
        val (minutes, seconds) = shown.split(":").map { it.toInt() }
        assertTrue("clock while dragging: $whileDragging", minutes * 60 + seconds in (28 * 60)..(32 * 60))
        assertEquals("no seek before the finger lifts", emptyList<Long>(), seeksBeforeRelease)
        assertEquals("one seek on release: ${player.seeks}", 1, player.seeks.size)
        assertTrue("seek to ${player.seeks}", player.seeks.single() in (28 * 60_000L)..(32 * 60_000L))
    }
}

/** A player with one 40-minute item that records the positions it is asked to seek to. */
@UnstableApi
private class SeekRecordingPlayer(private val durationMs: Long) : SimpleBasePlayer(Looper.getMainLooper()) {
    val seeks = mutableListOf<Long>()
    private var position = 0L

    override fun getState(): State {
        val item = MediaItemData.Builder("episode")
            .setMediaItem(MediaItem.fromUri("http://example.invalid/episode.mp3"))
            .setDurationUs(durationMs * 1000)
            .build()

        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_TIMELINE,
                        Player.COMMAND_GET_METADATA,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_BACK,
                        Player.COMMAND_SEEK_FORWARD,
                        Player.COMMAND_RELEASE,
                    )
                    .build()
            )
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(position)
            .setPlaybackState(STATE_READY)
            .setPlayWhenReady(false, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (positionMs != C.TIME_UNSET) {
            seeks += positionMs
            position = positionMs
        }

        return Futures.immediateVoidFuture()
    }
}
