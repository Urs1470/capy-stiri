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
- Daily cap: the server reports `quota: {"used", "cap"}` with every answer of `explain` and `ask`, the 429 of the daily
  cap included (`Quota` in `ExplicaModels.kt`; `ExplicaResult.Failure.quota` for the 429). The view model keeps the last
  one (an answer without the key, from an older server, leaves it in place) and, when `QUOTA_CAPTION_AT_OR_BELOW` (60) or
  fewer requests are left, the screen draws a small caption above the chips, "42 requests left today" (the plural
  `explica_requests_left`). At 0 nothing else changes: the server's own 429 text still shows as the error.
- The day entry: for the story of the feed "00 Azi" (the day's title, the "N articles from M sources" line and the concept
  of the day) `explain` answers `"day": true`, never writes an explanation and answers questions from all the other
  stories of the day. For it the screen has no Explain chip (`ExplicaState.offersExplain`; `explain()` ignores the call
  too), the four story chips give way to four `DayQuestion` chips ("Top stories", "For Moldova", "Economy & markets", "What
  to watch"; fixed English questions, nothing personal), and the empty-chat hint and the field's placeholder speak of
  today's stories. The questions go through the ordinary `ask`.
- Saved explanations (`SavedExplanations.kt`): `SavedExplanationsApi` sits in front of the client (one place,
  `ExplicaModule.kt`) and keeps the last `done` answer of `explain` of each story, with its chat (brought up to date after
  every successful `ask`, without the answer still being written), as one small JSON file,
  `filesDir/explica/<entry id>.json` (`SavedExplanationStore`). At most 300 files, and none that was last saved more than 90
  days ago; it is tidied when the decorator is created (the first time the AI screen is opened), and a new file also
  holds the limit. The network goes first, for every call, the read-only open (`start:false`) too, and what is sent to the
  server is not changed. The copy answers only when the call fails with no connection, `503` (the server can't check the
  token) or `404` (the server keeps the explanations of stories that are not starred for 14 days, then forgets them), and
  its `meta` then ends with " · saved copy". An answer from the server is never replaced by the copy, and a `done` one
  replaces it. Other failures (token, cap, busy chat, a 5xx from a proxy) show as before. The entry of the day has no
  explanation, so no copy, and a chat without an explanation isn't saved either.
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

## Highlights

On the same stories, in the reader and in the AI screen (the explanation and each answer), the reader selects text,
chooses **Highlight** in the selection toolbar and then a color: yellow is an idea, green a fact or definition,
blue a question, pink a quote (the colors of the vault's reading system, which exports them into notes and Anki
cards). The passage gets an opaque background per theme mode (at least 7:1 against the text of the app's built-in
themes, `HighlightPaletteTest`; the wallpaper colors of Material You are not checked). A tap on it opens a sheet to change the color, copy it or remove it. An icon in the top
bar of both screens lists the story's highlights with Copy and Remove. Other feeds and account types are untouched.

It works on the selection itself, not on paragraphs: Compose foundation 1.13.0-alpha01 (pulled in by material3
1.5.0-alpha29) has `SelectionContainer(state)` with `SelectionState.selectedTexts` and
`Modifier.appendTextContextMenuComponents` for the toolbar item. The selection gives the words but not where they are,
so a highlight is its `text` plus up to 60 characters before and after it (`prefix`, `suffix`), and
`HighlightAnchoring` finds it again in a block, preferring the occurrence whose context matches. Code:
`ui/explica/highlights/` and `HighlightsViewModel`. Upstream files touched: `ArticleReaderContent.kt` (the
`SelectionContainer` line), `ArticleElements.kt` (`TextElement` draws its paragraph's highlights and reports a tap),
`ArticleTopBar.kt` (the list icon).

Server contract, `POST {EXPLICA_URL}api/highlights` with the Miniflux token as `X-Auth-Token`, JSON in and out, the
failures 401, 403, 404, 429 and 503 as for `explain` and `ask`; `H` is `{id, text, color, where, prefix, suffix,
created}` (`created` in unix seconds):

- `{"entry_id": n, "op": "list"}` gives `{"highlights": [H, ...]}`, oldest first.
- `{"entry_id": n, "op": "add", "text": 1..1000 characters, "color": "yellow"|"green"|"blue"|"pink", "where":
  "story"|"explanation"|"answer:<n>", "prefix"?, "suffix"?}` gives `{"highlight": H}`; the same `where` and `text`
  again gives the one that exists. `<n>` is the position of the turn in the story's `chat`, from zero.
- `{"entry_id": n, "op": "remove", "id": "..."}` gives `{"ok": true}`; an unknown id is 404 (taken as already gone).

Limits of this first version:

- No offline queue. A highlight appears, goes or changes color at once and is put back with a short message when the
  call fails; a change made without a connection is lost. The list is read again when a screen opens or comes back.
- A passage that occurs several times in a block is stored without context (nothing says which one was selected) and
  drawn at every occurrence, except that whole words win over the same letters inside a longer word.
- A passage that crosses paragraphs is stored on one line. A selection that reaches outside the story text (its
  title, say) is saved but not drawn. The explanation and an answer can't be highlighted while they are being written.
- The server keeps the first color of a passage, so changing a color is a removal followed by a new highlight (a new
  id and time). If the new one is refused the old color is added back; if that fails too the highlight is gone.
- The Highlight item is added at the end of the selection toolbar; on a narrow toolbar it can sit under the overflow.

The tests in `ui/explica/` run the real screens on Robolectric (Android 15): the selection, the toolbar item, the
painting, taps, the sheets and the reader's content and top bar. What they can't show is how it looks, a long press
(none started in any attempt) and where in a line a finger lands: Robolectric's text has no real font metrics.

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
