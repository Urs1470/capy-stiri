package com.capyreader.app.ui.digest

import com.capyreader.app.ui.explica.explicaModule
import com.jocmp.capy.Account
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.time.Instant

// Account modules are loaded together (KoinSetupModules lists explicaModule, which includes digestModule).
class DigestModuleTest {
    private fun accountWith(lastRefreshedAt: Long): Account {
        return mockk {
            every { preferences.lastRefreshedAt.get() } returns lastRefreshedAt
        }
    }

    @Test
    fun syncOnOpen_comesWithTheExplainerModule_andIsOneInstance() {
        val koin = koinApplication {
            modules(explicaModule, module { single { accountWith(lastRefreshedAt = 0L) } })
        }.koin

        try {
            assertSame(koin.get<SyncOnOpen>(), koin.get<SyncOnOpen>())
        } finally {
            koin.close()
        }
    }

    @Test
    fun unloadingTheAccountModules_takesSyncOnOpenAwayToo() {
        val koin = koinApplication {
            modules(explicaModule, module { single { accountWith(lastRefreshedAt = 0L) } })
        }.koin

        try {
            assertNotNull(koin.getOrNull<SyncOnOpen>())

            koin.unloadModules(listOf(explicaModule))

            assertNull(koin.getOrNull<SyncOnOpen>())
        } finally {
            koin.close()
        }
    }

    @Test
    fun syncOnOpen_asksTheAccountForItsLastRefresh_onlyWhenTheListTakesTheOpen() {
        val longAgo = Instant.now().epochSecond - 3 * 60 * 60
        val account = accountWith(lastRefreshedAt = longAgo)
        val koin = koinApplication {
            modules(explicaModule, module { single { account } })
        }.koin

        try {
            val sync = koin.get<SyncOnOpen>()

            sync.onOpen()
            verify(exactly = 0) { account.preferences }

            assertTrue(sync.takeRefresh())
            verify(exactly = 1) { account.preferences }
        } finally {
            koin.close()
        }
    }

    @Test
    fun syncOnOpen_doesNotRefreshAFreshAccountOrANewOne() {
        val justNow = Instant.now().epochSecond
        val fresh = koinApplication {
            modules(explicaModule, module { single { accountWith(lastRefreshedAt = justNow) } })
        }.koin
        val new = koinApplication {
            modules(explicaModule, module { single { accountWith(lastRefreshedAt = 0L) } })
        }.koin

        try {
            fresh.get<SyncOnOpen>().run {
                onOpen()
                assertFalse(takeRefresh())
            }
            new.get<SyncOnOpen>().run {
                onOpen()
                assertFalse(takeRefresh())
            }
        } finally {
            fresh.close()
            new.close()
        }
    }
}
