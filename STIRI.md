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

Other feeds, other account types and the reader behave as upstream, with three changes: the app syncs when it is
opened (see below), folders named like the digest's sections come first in the folder list, and Today shows only
the digest's feeds when the account has any (the reading feeds stay in their folders; `articlesByStatus.sq`, where
`publishedSince` is set only by Today; an account without digest feeds keeps the upstream Today). The build installs next to the Play Store app (`com.capyreader.app.nightly`, named
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
  `explica_requests_left`). At 0 nothing else changes: the server's own 429 text still shows as the error. The key is
  read leniently (`LenientQuotaSerializer`): `18.0` or `"18"` is 18, and a value that is not a quota is none, because a
  caption must never turn an answer into a failure.
- The day entry: for the story of the feed "Conceptul zilei" (until 2026-10-01 "00 Azi") (the day's title, the "N articles from M sources" line and the concept
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
  - Morning sync (`MorningSync*.kt`, see "Morning sync and notification" below).
  - `DigestModule.kt`: the Koin definitions, included by `explicaModule`.
- Folder order: `DigestFolderOrder` and `DIGEST_SECTION_ORDER` (`capy/.../common/DigestFolderOrder.kt`) put the
  sections in the digest's order ("Conceptul zilei", "Republica Moldova", "România", "Economie", "Bursă", "Geopolitică și
  știri globale", "AI", "Tehnologie — domeniul meu") ahead of every other folder, which stay alphabetical. A title
  matches a section by whole words, ignoring case and diacritics, so "Geopolitică" is the long name and "Aisle" is not
  "AI". `Account.folders` already sorts with `sortedByTitle()`, so the drawer, the swipe up to the next section and
  "open next feed" after mark all read follow it, as does the feed edit dialog.
- Tests: `app/src/test/.../ui/explica/` (client with a fake OkHttp interceptor, ViewModel with a fake API, the
  chips, the daily cap, the day entry, the saved explanations with a temporary folder and a clock moved by hand, the bar
  of the screen on Robolectric) and `app/src/test/.../ui/digest/` (folder order, staleness with an injectable clock, HTML
  to text, share text, the morning sync: next run across time zones and summer time, the queue and the work on
  WorkManager's test helpers; `ShareArticleTest` and the work tests need Robolectric).
- Contact points in upstream files (small, additive): `build.gradle.kts` (`EXPLICA_URL`,
  `EXPLICA_FEED_PREFIX`), `Route.kt` (`Route.Explica`), `App.kt` (the entry), `ArticleDetailScreen.kt`,
  `ArticleView.kt`, `ArticleTopBar.kt` (the button, only when `canExplain`), `KoinSetupModules.kt`,
  `app/src/nightly/res/values/strings.xml` (app name); for the later additions: `MainActivity.kt` (`onStart`
  reports the open and keeps the morning sync queued), `ArticleScreen.kt` (one `SyncOnOpenEffect` call), `ContextShareArticleExt.kt` (what
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
- Highlight is the first item of the selection toolbar. Compose collects the items up the tree, nearest first, so
  an item added around a `SelectionContainer` comes after Copy, Select all and the apps that process text (seventh
  on Ion's phone). `HighlightFirstToolbar` is the toolbar provider of the container: it hands the platform's own
  provider the same items with Highlight moved to the front. The platform provider is read by a zero-size
  `SelectionContainer` inside (it sets up the default one only where none is provided). Tested on the framework's
  real floating `ActionMode` (`HighlightSelectionTest`).

The **Highlights** page (drawer, under Today; Miniflux with an API token only) lists every highlight, one card per
story, the story highlighted last first: `{"op": "all"}` gives `{"stories": [{entry_id, title, link, section,
latest, highlights: [H, ...]}]}`. A tap opens the story in the reader; a story no longer on the phone says so and
offers its source. Code: `HighlightsPageViewModel`, `highlights/HighlightsPage.kt`; contact points: `Route.kt`
(`Highlights`), `App.kt` (its entry), `ArticleScreen.kt` and `FeedList.kt` (an `extraItems` slot in the drawer).

The tests in `ui/explica/` run the real screens on Robolectric (Android 15): the selection, the toolbar item, the
painting, taps, the sheets and the reader's content and top bar. What they can't show is how it looks, a long press
(none started in any attempt) and where in a line a finger lands: Robolectric's text has no real font metrics.

## Morning sync and notification

Around 06:20 on the phone's clock (`MORNING_SYNC_TIME`; the digest is published about 06:10) the app syncs by itself and
the entry of the day, "Conceptul zilei", raises one notification. Code: `ui/digest/MorningSync*.kt`.

How upstream does background work, which this follows: `RefreshScheduler` queues one periodic job (`RefreshFeedsWorker`,
every two hours by default, `refresher/RefreshInterval.kt`) when an account is created (`LoginViewModel`,
`AddAccountViewModel`) or the interval is changed in Settings, and WorkManager keeps it across restarts. The job is
`FeedRefresher.refresh()`: refresh the account, then `NotificationHelper.notify`, which raises a notification for each new
unread article of a feed whose notification flag is on (Settings > Notifications, per feed, off for every feed at first).
Periodic work can't be set to a time of day, and a foreground refresh (opening the app, pull to refresh) never notifies.

- A chain of one-time jobs. `MorningSyncScheduler` queues one `MorningSyncWorker` that waits until the next 06:20
  (`nextMorningSync`: java.time on the calendar of the phone's time zone, so summer time changes the distance between
  two syncs and not the time of day; an injectable clock and zone) and needs a network, like the periodic job. Every day
  has its own unique name, `digest_morning_sync_<moment>` ("2026-10-02T06:20+03:00"), queued with `KEEP`: queueing is
  harmless to repeat, and a run can queue the next day without cancelling itself, which replacing its own name would do.
- Every run (`MorningSync.run`) queues the next day first, so nothing after it can break the chain, then does
  `FeedRefresher.refresh()`: the same refresh, the same Wi-Fi-only rule, the same notifications as the periodic job. When
  the refresh did not go through, WorkManager tries again (three attempts, its own back-off).
- Where it is queued: upstream queues its work only at login and in Settings, so nothing would queue it for an account that
  exists already. `MainActivity.onStart`, where the sync on open is, calls `keepMorningSync(hasAccount)` at every open:
  with an account the next day is queued (nothing happens when it is) or the chain is cancelled when the account is not due
  it; without an account whatever a removed account left is cancelled. A new login is queued at the next open.
- Who it is for: a Miniflux account (password or API token) with a feed of the digest (a URL under `EXPLICA_FEED_PREFIX`),
  and only while the refresh interval is not "Manually only", as upstream's notifications are. For any other account the
  chain is cancelled, and a run that finds it is not due any more ends the chain.
- The notification is upstream's, per feed; no new code raises one. `MorningSync` turns the flag of the day feed (the URL
  `.../sectiuni/conceptul-zilei.xml`, or the former `00-azi.xml`) on, once per account (the mark is `day_notification_enabled_<account id>` in the `digest`
  preferences file): before the refresh when the account has that feed already, so that the morning's entry is announced,
  and after it when only that refresh brought the feed in, then for the next days (announcing all it holds at once would
  be a burst, not one a day). After that the choice is the reader's: turn it off in Settings > Notifications > "Conceptul zilei" and
  it stays off; turn it on there by hand if it was never turned on. The other stories notify only when the reader turned
  them on, as upstream.
- Limits: the time is not exact, because WorkManager runs the job when the system lets it: in Doze, or when the system puts
  the app in a low standby bucket (`technotes/Refresh.md`), often later, even hours later. The notification then comes with
  the job, or with the next background refresh if that is earlier (the periodic one, every two hours by default); opening
  the app refreshes the list but never notifies. So a story that a refresh of the open app brought in before 06:20 is not
  announced. Android 13 and later need the notification permission, which the app asks for when Settings > Notifications is
  opened, once. A phone that was off for days announces each day entry it missed, one notification each.

## Building

Locally (JDK 21, Android SDK with platform 37): `./gradlew :app:testFreeDebugUnitTest --tests
'com.capyreader.app.ui.explica.*' --tests 'com.capyreader.app.ui.digest.*'`. On Windows, keep `TEMP` short and
without `~` (JDK 21 fails at `Selector.open()` otherwise and Gradle can't start its daemon).

Release: run the workflow. Every build is a newer version: `-PnewsBuild=<run number>` gives versionCode
`upstream × 1000 + N` and versionName `<upstream>.N`, which is also the release's tag, so Obtainium sees each release
as an update (a local build without the property keeps the upstream version and can't install over a release).
It needs these repository secrets: `ENCODED_RELEASE_KEYSTORE` (base64 of the
PKCS12 keystore), `PROP_STORE_PASSWORD`, `PROP_KEY_ALIAS`, `PROP_KEY_PASSWORD`.

## Following upstream

Branch `main` mirrors upstream; the work is on `stiri-next` (`stiri` is the same work with the old author address).

    git fetch upstream
    git checkout main && git merge --ff-only upstream/main && git push origin main
    git checkout stiri-next && git rebase main

Conflicts are unlikely: the contact points are a few lines each.
