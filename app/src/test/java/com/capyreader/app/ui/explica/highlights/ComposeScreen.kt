package com.capyreader.app.ui.explica.highlights

import android.content.ClipboardManager
import android.os.Looper
import android.os.SystemClock
import android.view.ActionMode
import android.view.Choreographer
import android.view.InputDevice
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.core.view.children
import com.capyreader.app.ui.explica.HighlightColor
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import kotlin.coroutines.ContinuationInterceptor

/**
 * A Compose screen on Robolectric without the Compose test rule, which this project doesn't have: the screen is
 * composed in an activity, the looper is stepped by hand, and what is on the screen is read from its semantics.
 *
 * Two things to know. While text is selected, the selection handles' popup asks for a frame over and over, and a
 * looper that idles (`idle`, `idleFor`) then never comes back, so [stepUntil] runs one task at a time and stops when
 * its condition holds. And Compose keeps global state, so a test has to [destroy] its screen or it holds up the next.
 *
 * Robolectric's text has no real font metrics (every tap in a line lands on the gap before its first character or
 * the one after its last), and a long press did not start a selection in any attempt made here.
 */
@OptIn(InternalComposeUiApi::class)
internal class ComposeScreen private constructor(
    private val controller: ActivityController<ComponentActivity>,
) {
    val activity: ComponentActivity
        get() = controller.get()

    /**
     * Runs the main looper one task at a time until [done] is true; the number of steps, or -1 if it never was. When
     * nothing is due the clock moves by hand, a millisecond at a time, so that the next frame can come.
     */
    fun stepUntil(limit: Int = 5_000, done: () -> Boolean): Int {
        val looper = shadowOf(Looper.getMainLooper())

        repeat(limit) { step ->
            if (done()) return step

            if (looper.isIdle) looper.idleFor(Duration.ofMillis(1)) else ShadowLooper.runMainLooperOneTask()
        }

        return -1
    }

    fun assertSoon(message: String, done: () -> Boolean) {
        assertTrue(message, stepUntil(done = done) >= 0)
    }

    /**
     * Takes the screen down and lets the looper finish what the screen had asked for. A frame that Compose or the
     * window asked for and the looper has not run is lost when Robolectric resets the looper between tests, and the
     * Choreographer then believes it is still coming and never schedules another, so no later test gets a frame.
     * That is why time moves on until nothing has come due for a hundred milliseconds.
     */
    fun destroy() {
        controller.pause().stop().destroy()

        val looper = shadowOf(Looper.getMainLooper())
        var quiet = 0

        repeat(200) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(20))

            if (looper.isIdle) {
                quiet++

                if (quiet >= 5) return
            } else {
                quiet = 0

                repeat(500) { if (!looper.isIdle) ShadowLooper.runMainLooperOneTask() }
            }
        }
    }

    // the selection toolbar

    /** The floating toolbar over the selected text (the framework's `ActionMode`), once it is shown. */
    fun toolbar(): ActionMode? {
        return ReflectionHelpers.getField<ActionMode?>(activity.window.decorView, "mFloatingActionMode")
    }

    fun toolbarTitles(mode: ActionMode): List<String> {
        return (0 until mode.menu.size()).map { mode.menu.getItem(it).title.toString() }
    }

    /** What tapping [title] in the toolbar does: the framework runs the click listener the item was given. */
    fun tap(mode: ActionMode, title: String) {
        val item: MenuItem = (0 until mode.menu.size())
            .map { mode.menu.getItem(it) }
            .first { it.title?.toString() == title }

        item.javaClass.getMethod("invoke").invoke(item)
    }

    // what is on the screen

    private fun rootOf(window: View): ViewRootForTest {
        fun find(view: View): View? {
            if (view is ViewRootForTest) return view

            return (view as? ViewGroup)?.children?.firstNotNullOfOrNull { find(it) }
        }

        return find(window) as ViewRootForTest
    }

    /** The semantics of the screen, or of the window that was opened last (a bottom sheet is a window of its own). */
    fun nodes(merged: Boolean = false, inDialog: Boolean = false): List<SemanticsNode> {
        val window = if (inDialog) ShadowDialog.getLatestDialog().window!!.decorView else activity.window.decorView
        val owner = rootOf(window).semanticsOwner
        val all = mutableListOf<SemanticsNode>()

        fun walk(node: SemanticsNode) {
            all += node
            node.children.forEach { walk(it) }
        }

        walk(if (merged) owner.rootSemanticsNode else owner.unmergedRootSemanticsNode)

        return all
    }

    /** What the text elements were given to draw. */
    fun drawn(): List<AnnotatedString> {
        return nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull() }
    }

    /** The backgrounds painted behind the text [text], as start, end and color. */
    fun backgrounds(text: String): List<Triple<Int, Int, Color>> {
        val painted = drawn().single { it.text == text }

        return painted.spanStyles
            .filter { it.item.background != Color.Unspecified }
            .map { Triple(it.start, it.end, it.item.background) }
    }

    fun hasText(node: SemanticsNode, text: String): Boolean {
        return node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }

    fun hasDescription(node: SemanticsNode, description: String): Boolean {
        return node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
    }

    // fingers

    private fun touch(action: Int, at: Offset, downTime: Long, eventTime: Long) {
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coordinates = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = at.x
                y = at.y
                pressure = 1f
                size = 1f
            }
        )
        val event = MotionEvent.obtain(
            downTime, eventTime, action, 1, properties, coordinates, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )

        (rootOf(activity.window.decorView) as View).dispatchTouchEvent(event)
        event.recycle()
    }

    /**
     * A second press soon after a first, at the same place, is a double tap to the selection container. A finger
     * that comes later is not: the clock is moved on by hand before every gesture.
     */
    private fun newGestureTime(): Long {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))

        return SystemClock.uptimeMillis()
    }

    /** A finger that goes down at [at] and comes up [millis] later. */
    fun press(at: Offset, millis: Long = 40) {
        val downTime = newGestureTime()

        touch(MotionEvent.ACTION_DOWN, at, downTime, downTime)
        touch(MotionEvent.ACTION_UP, at, downTime, downTime + millis)
    }

    /** Two quick presses at the same place: to the selection container, a double tap, which selects a word. */
    fun doubleTap(at: Offset) {
        val first = newGestureTime()

        touch(MotionEvent.ACTION_DOWN, at, first, first)
        touch(MotionEvent.ACTION_UP, at, first, first + 40)

        val second = first + 100

        touch(MotionEvent.ACTION_DOWN, at, second, second)
        touch(MotionEvent.ACTION_UP, at, second, second + 40)
    }

    /** A finger that goes down at [from], moves to [to] and comes up there. */
    fun drag(from: Offset, to: Offset) {
        val downTime = newGestureTime()

        touch(MotionEvent.ACTION_DOWN, from, downTime, downTime)
        touch(MotionEvent.ACTION_MOVE, to, downTime, downTime + 20)
        touch(MotionEvent.ACTION_UP, to, downTime, downTime + 40)
    }

    // the bottom sheets and the dialogs

    fun hasSheet(): Boolean = runCatching { ShadowDialog.getLatestDialog() }.getOrNull() != null

    /** The texts the last sheet shows, merged the way a screen reader hears them. */
    fun sheetTexts(): List<String> {
        return nodes(merged = true, inDialog = true)
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
    }

    fun sheetClick(where: (SemanticsNode) -> Boolean) {
        val node = nodes(merged = true, inDialog = true)
            .first { it.config.getOrNull(SemanticsActions.OnClick) != null && where(it) }

        node.config[SemanticsActions.OnClick].action!!.invoke()
    }

    /** Clicks the node of the screen itself (not a sheet) that [where] picks. */
    fun click(where: (SemanticsNode) -> Boolean) {
        val node = nodes(merged = true).first { it.config.getOrNull(SemanticsActions.OnClick) != null && where(it) }

        node.config[SemanticsActions.OnClick].action!!.invoke()
    }

    fun clipboardText(): String? {
        val clipboard = activity.getSystemService(ClipboardManager::class.java)

        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    }

    companion object {
        /**
         * Composes [content] in a new activity and lets it settle: 40 frames, each with at most 200 tasks. An endless
         * animation (a progress indicator) keeps the looper busy, so this never waits for it to be idle.
         */
        fun show(content: @Composable () -> Unit): ComposeScreen {
            unwedgeComposeDispatcher()

            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()

            controller.get().setContent(content = content)

            val looper = shadowOf(Looper.getMainLooper())

            repeat(40) {
                repeat(200) { if (!looper.isIdle) ShadowLooper.runMainLooperOneTask() }
                ShadowSystemClock.advanceBy(Duration.ofMillis(16))
            }

            return ComposeScreen(controller)
        }

        fun color(color: HighlightColor) = Color(HighlightPalette.argb(color, dark = false))

        /**
         * Compose's UI dispatcher (`AndroidUiDispatcher.Main`, one for the whole test JVM) remembers that a message
         * is on its way to the looper. When Robolectric resets the looper between two tests while one is, the message
         * is lost and the dispatcher waits for it for ever: nothing is recomposed in any later test. Doing what the
         * message and the frame callback would have done puts it right, and changes nothing when all is well.
         */
        private fun unwedgeComposeDispatcher() {
            val dispatcher = AndroidUiDispatcher.Main[ContinuationInterceptor] ?: return
            val callback = try {
                ReflectionHelpers.getField<Any>(dispatcher, "dispatchCallback")
            } catch (e: RuntimeException) {
                throw AssertionError(
                    "AndroidUiDispatcher has no dispatchCallback field any more; update unwedgeComposeDispatcher", e
                )
            }

            (callback as Runnable).run()
            (callback as Choreographer.FrameCallback).doFrame(System.nanoTime())
        }
    }
}
