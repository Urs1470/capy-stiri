package com.capyreader.app.ui.explica

import com.capyreader.app.BuildConfig
import com.jocmp.capy.Account
import com.jocmp.capy.accounts.Source
import com.jocmp.capy.accounts.baseHttpClient
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** The explain button is for stories of the digest feeds, in a Miniflux account signed in with an API token. */
fun canExplain(
    source: Source,
    feedURL: String?,
    feedPrefix: String = BuildConfig.EXPLICA_FEED_PREFIX,
): Boolean {
    return source == Source.MINIFLUX_TOKEN && feedURL?.startsWith(feedPrefix) == true
}

internal val explicaModule = module {
    // The server knows the reader by the Miniflux API token, which the account keeps as its password.
    single<ExplicaApi> {
        ExplicaClient(
            http = baseHttpClient(),
            baseUrl = BuildConfig.EXPLICA_URL,
            token = { get<Account>().preferences.password.get() },
        )
    }
    viewModel { parameters ->
        ExplicaViewModel(
            api = get(),
            entryId = parameters.get(),
        )
    }
}
