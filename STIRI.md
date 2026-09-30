# Capy News — personal fork of Capy Reader

A personal build of [jocmp/capyreader](https://github.com/jocmp/capyreader) (GPL-3.0) with one addition:
for the stories of a daily news digest kept in Miniflux, an **Explain** button in the article's top bar
opens a native screen (Material You) with a chat about the story and, on request, an AI explanation with
suggested questions. The words come from a small server (`tools/stiri-explica/` in Ion's Knowledge
vault); this app only draws them.

Opening the screen never starts a generation. It makes one read-only call (`explain` with `start:false`)
and shows what exists: the explanation if it is done, its progress if it is being written, or, when
there is none, the story's title and any earlier chat, with a message field and an **Explain** chip above
it. A quick question can be asked at once (the server answers from the story alone); the explanation
is written only when the chip is tapped.

Other feeds, other account types and the reader behave as upstream, with two changes that apply to every
account: the app syncs when it is opened (see below), and folders named like the digest's sections come first
in the folder list. The build installs next to the Play Store app (`com.capyreader.app.nightly`, named
"Capy News").

## What was added

- New package `app/src/main/java/com/capyreader/app/ui/explica/`: `ExplicaClient` (OkHttp; the Miniflux
  API token as `X-Auth-Token`, the Miniflux entry id = the article id; `start:false` is sent only for the
  read-only call), `ExplicaViewModel` (phases `OPENING`, `IDLE`, `LOADING`, `READY`, `FAILED`: reads the
  state on open, starts the explanation only on `explain()`, polls it, then the chat), `ExplicaScreen`
  (Compose; the server's HTML goes through `Mallet.flatten` and `ArticleBody`, so it follows the reader's
  font settings), `ExplicaModule` (Koin, and `canExplain`).
- Strings: `res/values/explica_strings.xml`. The app stays in English everywhere: no translations of the fork's
  own strings, and `androidResources.localeFilters` in `app/build.gradle.kts` packages only the English resources
  (`locales_config.xml` offers only `en`), whatever the language of the phone.
- AI gestures: `ArticleVerticalSwipe.EXPLAIN_WITH_AI` ("Explain with AI") is one more choice for the reader's
  swipe down and swipe up rows in Settings > Gestures. It opens the same screen as the Explain button, only on
  stories that have the button, and is never the default (`ArticleView.kt`, `ArticleVerticalSwipe.kt`).
- Quick questions: four chips after Explain in the bar above the message field ("Summary", "Terms", "Why it
  matters", "Background"; `QuickQuestion` in `ExplicaQuickQuestions.kt`). Each sends one fixed English question
  through the ordinary `ask` (the server answers in the language of the digest, and no prompt has anything
  personal in it: the repository is public). The row shows while the story is open (idle, while the explanation
  is written, and after it), scrolls sideways when it doesn't fit, is disabled while an answer or the
  explanation is being written, and a chip whose question is already in the chat is hidden.
- New package `app/src/main/java/com/capyreader/app/ui/digest/` (the other additions for the digest's stories):
  - Sync on open (`SyncOnOpen.kt`): upstream refreshes on the first run, on pull to refresh and every two hours
    in the background, so opening the app showed the list as it was. Now an open (`MainActivity.onStart`) also
    refreshes when the last refresh is older than `SYNC_ON_OPEN_AFTER` (10 minutes, one constant): the article
    list takes the open through `SyncOnOpenEffect` and calls its own `refreshAll()`, so the refresh looks and
    behaves like a manual one. A new account (`lastRefreshedAt` 0) is left to the list's first-run refresh, and an
    open that happens while the reader is on screen waits for the list.
  - Share as text (`DigestShare.kt`, `HtmlToPlainText.kt`): sharing a story of the digest sends its title, its text
    (paragraphs, the "Ce înseamnă" quote and the source line, no HTML; converted with `Mallet`) and the link of the
    first source unless the text has it; any other article is shared as a link, as before.
  - `DigestModule.kt`: the Koin definition, included by `explicaModule`.
- Folder order: `DigestFolderOrder` and `DIGEST_SECTION_ORDER` (`capy/.../common/DigestFolderOrder.kt`) put the
  sections in the digest's order ("00 Azi", "Republica Moldova", "România", "Economie", "Bursă", "Geopolitică și
  știri globale", "AI", "Tehnologie — domeniul meu") ahead of every other folder, which stay alphabetical. A title
  matches a section by whole words, ignoring case and diacritics, so "Geopolitică" is the long name and "Aisle" is not
  "AI". `Account.folders` already sorts with `sortedByTitle()`, so the drawer, the swipe up to the next section and
  "open next feed" after mark all read follow it, as does the feed edit dialog.
- Tests: `app/src/test/.../ui/explica/` (client with a fake OkHttp interceptor, ViewModel with a fake API, the
  chips) and `app/src/test/.../ui/digest/` (folder order, staleness with an injectable clock, HTML to text, share
  text; `ShareArticleTest` needs Robolectric).
- Contact points in upstream files (small, additive): `build.gradle.kts` (`EXPLICA_URL`,
  `EXPLICA_FEED_PREFIX`), `Route.kt` (`Route.Explica`), `App.kt` (the entry), `ArticleDetailScreen.kt`,
  `ArticleView.kt`, `ArticleTopBar.kt` (the button, only when `canExplain`), `KoinSetupModules.kt`,
  `app/src/nightly/res/values/strings.xml` (app name); for the later additions: `MainActivity.kt` (`onStart`
  reports the open), `ArticleScreen.kt` (one `SyncOnOpenEffect` call), `ContextShareArticleExt.kt` (what
  `shareArticle` sends) and `capy/.../common/FolderListExt.kt` (`sortedByTitle` of folders uses the digest order).
- `.github/workflows/build-stiri.yml`: tests, signed APK, release of this repo. Upstream's own workflows
  are disabled in this repository.

The button shows only for a Miniflux account signed in with an **API token**, on stories whose feed URL
starts with `https://news.iupif.org/sectiuni/` (the digest's feeds).

## Building

Locally (JDK 21, Android SDK with platform 37): `./gradlew :app:testFreeDebugUnitTest --tests
'com.capyreader.app.ui.explica.*' --tests 'com.capyreader.app.ui.digest.*'`. On Windows, keep `TEMP` short and
without `~` (JDK 21 fails at `Selector.open()` otherwise and Gradle can't start its daemon).

Release: run the workflow. It needs these repository secrets: `ENCODED_RELEASE_KEYSTORE` (base64 of the
PKCS12 keystore), `PROP_STORE_PASSWORD`, `PROP_KEY_ALIAS`, `PROP_KEY_PASSWORD`.

## Following upstream

Branch `main` mirrors upstream; the work is on `stiri`.

    git fetch upstream
    git checkout main && git merge --ff-only upstream/main && git push origin main
    git checkout stiri && git rebase main

Conflicts are unlikely: the contact points are a few lines each.
