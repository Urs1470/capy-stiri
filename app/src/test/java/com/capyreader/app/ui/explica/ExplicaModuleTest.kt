package com.capyreader.app.ui.explica

import com.jocmp.capy.Account
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * How the explainer's module hands out its pieces: the screens get the server's client through the copies kept on the
 * phone, the highlights get the client itself. A plain Application, and nothing here touches the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ExplicaModuleTest {
    private fun koin() = koinApplication {
        androidContext(RuntimeEnvironment.getApplication())
        modules(explicaModule, module { single { mockk<Account>(relaxed = true) } })
    }.koin

    @Test
    fun theExplainer_goesThroughTheSavedCopies_andTheHighlightsUseTheClientItself() {
        val koin = koin()

        try {
            val explainer = koin.get<ExplicaApi>()
            val highlights = koin.get<HighlightsApi>()

            assertTrue(explainer is SavedExplanationsApi)
            assertTrue(highlights is ExplicaClient)
            assertSame(highlights, koin.get<ExplicaClient>())
            assertSame(explainer, koin.get<ExplicaApi>())
        } finally {
            koin.close()
        }
    }

    @Test
    fun theCopiesLiveInAFolderOfTheAppsOwnFiles() {
        assertEquals("explica", SAVED_EXPLANATIONS_DIRECTORY)
        assertEquals(300, SAVED_EXPLANATIONS_MAX)
        assertEquals(90L, SAVED_EXPLANATIONS_MAX_AGE.toDays())
    }
}
