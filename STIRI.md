# Capy News — personal fork of Capy Reader

A private build of [jocmp/capyreader](https://github.com/jocmp/capyreader) (GPL-3.0) with one addition:
for the stories of a daily news digest kept in Miniflux, an **Explain** button in the article's top bar
opens a native screen (Material You) with an AI explanation of the story, suggested questions and a
chat. The words come from a small server (`tools/stiri-explica/` in Ion's Knowledge vault); this app only
draws them.

Nothing else changes: other feeds, other account types and the reader behave as upstream. The build
installs next to the Play Store app (`com.capyreader.app.nightly`, named "Capy News").

## What was added

- New package `app/src/main/java/com/capyreader/app/ui/explica/`: `ExplicaClient` (OkHttp; the Miniflux
  API token as `X-Auth-Token`, the Miniflux entry id = the article id), `ExplicaViewModel` (polls
  `explain`, then the chat), `ExplicaScreen` (Compose; the server's HTML goes through `Mallet.flatten`
  and `ArticleBody`, so it follows the reader's font settings), `ExplicaModule` (Koin, and `canExplain`).
- Strings: `res/values/explica_strings.xml`. The app stays in English everywhere: no translations of the fork's
  own strings, and `androidResources.localeFilters` in `app/build.gradle.kts` packages only the English resources
  (`locales_config.xml` offers only `en`), whatever the language of the phone.
- AI gestures: `ArticleVerticalSwipe.EXPLAIN_WITH_AI` ("Explain with AI") is one more choice for the reader's
  swipe down and swipe up rows in Settings > Gestures. It opens the same screen as the Explain button, only on
  stories that have the button, and is never the default (`ArticleView.kt`, `ArticleVerticalSwipe.kt`).
- Tests: `app/src/test/.../ui/explica/` (client with a fake OkHttp interceptor, ViewModel with a fake API).
- Contact points in upstream files (small, additive): `build.gradle.kts` (`EXPLICA_URL`,
  `EXPLICA_FEED_PREFIX`), `Route.kt` (`Route.Explica`), `App.kt` (the entry), `ArticleDetailScreen.kt`,
  `ArticleView.kt`, `ArticleTopBar.kt` (the button, only when `canExplain`), `KoinSetupModules.kt`,
  `app/src/nightly/res/values/strings.xml` (app name).
- `.github/workflows/build-stiri.yml`: tests, signed APK, release of this repo. Upstream's own workflows
  are disabled in this repository.

The button shows only for a Miniflux account signed in with an **API token**, on stories whose feed URL
starts with `https://news.iupif.org/sectiuni/` (the digest's feeds).

## Building

Locally (JDK 21, Android SDK with platform 37): `./gradlew :app:testFreeDebugUnitTest --tests
'com.capyreader.app.ui.explica.*'`. On Windows, keep `TEMP` short and without `~` (JDK 21 fails at
`Selector.open()` otherwise and Gradle can't start its daemon).

Release: run the workflow. It needs these repository secrets: `ENCODED_RELEASE_KEYSTORE` (base64 of the
PKCS12 keystore), `PROP_STORE_PASSWORD`, `PROP_KEY_ALIAS`, `PROP_KEY_PASSWORD`.

## Following upstream

Branch `main` mirrors upstream; the work is on `stiri`.

    git fetch upstream
    git checkout main && git merge --ff-only upstream/main && git push origin main
    git checkout stiri && git rebase main

Conflicts are unlikely: the contact points are a few lines each.
