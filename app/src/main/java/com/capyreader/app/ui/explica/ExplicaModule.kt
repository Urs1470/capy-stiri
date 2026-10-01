package com.capyreader.app.ui.explica

import com.capyreader.app.BuildConfig
import com.capyreader.app.ui.digest.digestModule
import com.jocmp.capy.Account
import com.jocmp.capy.accounts.Source
import com.jocmp.capy.accounts.baseHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.binds
import org.koin.dsl.module
import java.io.File

/**
 * The Explain button and the highlights are for every article of a Miniflux account signed in with an API token: the
 * stories of the digest and, since 2026-10-01, the reading feeds (Ion: highlights and Explain on the long reads too).
 * The server tells them apart: a digest story is explained as news, any other entry as a reading article.
 */
fun canExplain(
    source: Source,
    feedURL: String?,
): Boolean {
    return source == Source.MINIFLUX_TOKEN && !feedURL.isNullOrBlank()
}

internal val explicaModule = module {
    // The fork's other digest features (sync on open) load with this module.
    includes(digestModule)

    // The server knows the reader by the Miniflux API token, which the account keeps as its password.
    // One client serves the explainer and the highlights.
    single {
        ExplicaClient(
            http = baseHttpClient(),
            baseUrl = BuildConfig.EXPLICA_URL,
            token = { get<Account>().preferences.password.get() },
        )
    } binds arrayOf(HighlightsApi::class, AllHighlightsApi::class)
    // The explainer goes through the copies kept on the phone (see SavedExplanationsApi).
    single<ExplicaApi> {
        SavedExplanationsApi(
            delegate = get<ExplicaClient>(),
            store = SavedExplanationStore(File(androidContext().filesDir, SAVED_EXPLANATIONS_DIRECTORY)),
        )
    }
    viewModel { parameters ->
        ExplicaViewModel(
            api = get(),
            entryId = parameters.get(),
        )
    }
    viewModel { parameters ->
        HighlightsViewModel(
            api = get(),
            entryId = parameters.get(),
        )
    }
    viewModel {
        HighlightsPageViewModel(
            api = get(),
            hasArticle = { articleID -> get<Account>().findArticle(articleID) != null },
        )
    }
}
