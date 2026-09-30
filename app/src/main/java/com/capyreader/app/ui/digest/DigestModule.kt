package com.capyreader.app.ui.digest

import com.jocmp.capy.Account
import org.koin.dsl.module

/** Loaded with the explainer's module (`includes` in `ExplicaModule`), so `KoinSetupModules` stays as upstream. */
internal val digestModule = module {
    single {
        SyncOnOpen(lastRefreshedAt = { get<Account>().preferences.lastRefreshedAt.get() })
    }
}
