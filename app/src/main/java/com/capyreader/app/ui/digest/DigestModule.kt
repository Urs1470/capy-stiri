package com.capyreader.app.ui.digest

import android.content.Context
import androidx.work.WorkManager
import com.capyreader.app.preferences.AndroidPreferenceStore
import com.capyreader.app.preferences.AppPreferences
import com.jocmp.capy.Account
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.worker
import org.koin.dsl.module

/** The file of preferences that only the fork's digest features use (the account's and the app's are upstream's). */
private const val DIGEST_PREFERENCES = "digest"

/** Loaded with the explainer's module (`includes` in `ExplicaModule`), so `KoinSetupModules` stays as upstream. */
internal val digestModule = module {
    single {
        SyncOnOpen(lastRefreshedAt = { get<Account>().preferences.lastRefreshedAt.get() })
    }

    single { MorningSyncScheduler(workManager = WorkManager.getInstance(androidContext())) }
    single {
        MorningSync(
            account = get(),
            refresher = get(),
            scheduler = get(),
            refreshInterval = { get<AppPreferences>().refreshInterval.get() },
            // Per account: a removed account that comes back is a new one.
            dayNotificationEnabled = AndroidPreferenceStore(
                androidContext().getSharedPreferences(DIGEST_PREFERENCES, Context.MODE_PRIVATE)
            ).getBoolean("day_notification_enabled_${get<Account>().id}", false),
        )
    }
    worker { MorningSyncWorker(get(), get(), get()) }
}
