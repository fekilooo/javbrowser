# Architecture

`nativeapp.ui` owns Compose/navigation/state and calls `JavRepository`, never providers. `nativeapp.data` aggregates, caches, migrates, and persists logical titles. `nativeapp.source` defines `JavSource` and adapters. `nativeapp.domain` contains provider-neutral models and identity/merge/ranking policies. `nativeapp.web` is limited to dynamic extraction and verification.

`JavTitle` is one logical title with multiple `SourceRef`s. Raw HTML stays inside adapters. Conservative exact normalized-code matches merge; title-only matches remain separate. Original provider identity is retained.

`JavRepository.search` starts enabled sources concurrently with per-source timeouts and emits after each completion. One failure cannot cancel siblings. Search has a ten-minute cache. Metadata merging keeps nonblank primary values and fills gaps; lists are unioned. Playback resolves separately and ranks preference, prior success, quality, then direct MP4 stability. Stream URLs are resolved on demand.

HTTP/OkHttp/Jsoup is preferred. `HeadlessWebEngine` owns a temporary cancellable WebView and destroys it after returning rendered HTML. `SourceVerificationActivity` is the controlled visible flow; it performs no CAPTCHA automation, persists ordinary cookies, then returns to native UI.

Favorites currently use private JSON preferences for reversible migration. `LegacyMigration` imports the old JSON once, retaining unsafe/unresolved records under a `legacy` ref. A future Room store can preserve repository/domain interfaces.

Main routes are Home, Discover, Library, Downloads. Search, Detail, Player, and Settings are secondary. Sources are never top-level destinations.
