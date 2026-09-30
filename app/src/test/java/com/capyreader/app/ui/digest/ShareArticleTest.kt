package com.capyreader.app.ui.digest

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.capyreader.app.common.shareArticle
import com.jocmp.capy.Article
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// shareArticle is what the share buttons call: a digest story goes out as text, everything else as before.
// A plain Application: the app's own one starts Koin and this test never stops it, which broke the tests after it.
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ShareArticleTest {
    /** Records what would be started, so the test doesn't depend on how the framework starts activities. */
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            started += intent
        }
    }

    /** The text of the ACTION_SEND the chooser wraps, or null when nothing was started. */
    private fun sharedText(article: Article): String? {
        val context = RecordingContext(RuntimeEnvironment.getApplication())

        context.shareArticle(article)

        val chooser = context.started.singleOrNull() ?: return null
        val sent = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull(sent)
        assertEquals(Intent.ACTION_SEND, sent!!.action)
        assertEquals("text/plain", sent.type)
        assertEquals(article.title, sent.getStringExtra(Intent.EXTRA_TITLE))

        return sent.getStringExtra(Intent.EXTRA_TEXT)
    }

    @Test
    fun aDigestArticle_isSharedAsItsStory() {
        val text = sharedText(testArticle())!!

        assertTrue(text.startsWith("Bugetul pe 2027\n\nGuvernul a aprobat azi bugetul pe 2027."))
        assertTrue(text.endsWith("Sursă: HotNews\n\n$TEST_SOURCE_URL"))
    }

    @Test
    fun anyOtherArticle_isSharedAsItsUrlOnly_asUpstreamDoes() {
        assertEquals(
            "https://hotnews.ro/alta-stire",
            sharedText(testArticle(feedURL = "https://hotnews.ro/rss", url = "https://hotnews.ro/alta-stire")),
        )
    }

    @Test
    fun anOtherArticleWithoutAUrl_isNotShared() {
        assertNull(sharedText(testArticle(feedURL = "https://hotnews.ro/rss", url = null)))
    }
}
