# XChina and Theporny integration

## Entry points and navigation

Both sources appear directly in HOME alongside the existing websites. There is no source-management screen or enable preference. Each opens a native three-column poster grid with rounded 2:3 covers, two-line titles and source labels. Card covers have no menu overlay. Unified search keeps its existing result cards. All 21 XChina and 14 Theporny category IDs from the handoff are retained in source order and shown as horizontally scrollable labels inside the source page. Labels have readable Chinese and English display names. Selecting a category clears the current keyword and starts at page one.

Opening a video displays a 16:9 poster/play preview at the top, followed by the title, metadata and description. There is no detail More options or bookmark button; the shared navigation heart handles bookmarks. Catalog search/category controls are hidden on this detail screen. The preview does not start media; pressing Play opens the existing fullscreen internal player and resolves the stream afresh. Returning to the grid reuses its RecyclerView to retain scroll position.

The main browser and native sources inflate the same navigation layout. HOME opens the browser landing page, the heart toggles the current catalog/video bookmark, and bookmarks/downloads/settings use the existing screens. The play action appears when video metadata is ready. There is no duplicate bookmark button in detail content. Catalog bookmarks reopen the native source catalog; detail bookmarks retain source/video identity.

Both source search fields inflate the same layout as unified search: white input text, #999999 hint text, #BB86FC input tint and button background, and the original 48dp control height and spacing.

## Chinese search variants

Source-local and unified search share the same client path. Traditional Chinese keywords are searched in their original form and a Simplified Chinese form, then merged by source/video identity with original-query ordering first. Each variant requests the same page number, and hasMore is the union of both responses. Identical variants, English and video codes generate one request. If a variant fails, the page fails and can be retried without advancing; the successfully fetched variant can be reused from the short-lived cache. Both variants share the existing overall request deadline and cancellation.

Offline conversion uses unmodified OpenCC TSCharacters/TSPhrases dictionaries, pinned to fae2a0a911c30764e669a7d8b0591cd8e852934a, with an application longest-match reader. Source, license and attribution are bundled under resources/opencc. Display titles and other search providers are unchanged. Fixture tests cover phrase exceptions, mixed English/codes/emoji, duplicate-query prevention, merging, page numbers and partial failure recovery.

Validation: all 13 targeted JVM tests passed (3 conversion, 6 client, 4 paging). The APK was replacement-installed on the development phone. An Android test loaded the packaged dictionaries, checked conversion/phrase/code behavior and queried both live sources with 台灣; each returned 48 unique merged first-page items in that run. This is a point-in-time result, not a fixed result-count guarantee. The test runner and its report were removed after verification.

Catalogs support keyword search, bounded pagination, details, cover images, bookmark creation, and the existing fullscreen internal player. Both sources also participate in unified search. List and detail tasks cancel when leaving the screen; category, query, and selected video survive Activity recreation. Theporny offers automatic selection or hd1, hd2, hd3 and default1.

Catalog pagination is automatic within two rows of the bottom. Only one page request can run at a time. Appending preserves the grid and scroll position; failures pause automatic attempts and show a retry action. Empty or fully duplicate pages end pagination even if the source's approximate hasMore flag remains true. Four JVM pagination tests cover prefetch/in-flight guards, retrying the same failed page, repeated/empty page termination, category reset and cancellation.

An app-specific `javbrowser_video` query on each source's website origin stores stable source/video identity for bookmarks and playback history. It is an internal navigation marker, not a documented public website route. BrowserNavigator, browser incoming intents, and main-frame navigation route these markers back into the native detail page. A fresh media URL is resolved when Play is pressed. Media URLs and signed query tokens are not saved in bookmarks or history.

## Protocol and access

The Kotlin implementation was written from the handoff's protocol description and local reference behavior. It does not copy the Go provider implementation or package its dependencies. The reference project has no root license file available in the supplied checkout; no upstream source files or embedded account credentials are redistributed.

Requests use POST /js with source-specific app/origin/referer fields. The response decoder supports plain JSON and OpenSSL Salted__ AES-256-CBC envelopes, deriving key/IV through EVP_BytesToKey-compatible MD5 with the documented protocol password. Invalid lengths, padding, JSON, blocked responses and malformed lists report errors. Network work runs off the UI thread, with cancellable calls, per-call timeouts, an overall deadline and finite host failover. Catalog pages have a one-minute bounded cache. Playback addresses are resolved afresh.

This integration uses anonymous requests and no third-party login, stored password, token or shared cookie. The supplied upstream hardcoded account was not used. Account-restricted content may fail to return playable media. Theporny fingerprint restrictions may vary; PC and Android clients must be tested separately.

Playback uses the existing loopback proxy and player. The proxy binds to 127.0.0.1, rewrites HLS requests and supplies the source's Referer/User-Agent. Existing cookie-domain scoping is retained. New-source playback pauses when its Activity loses foreground; pending resolution cancels on stop, and the proxy and WebView release on close. Other player lifecycle behavior is preserved.

## Validation

- Nine JVM tests passed: independent fixed encryption vector with Chinese text, damaged cipher/blocked/invalid responses, original category IDs/order, cross-source identity, row/image mapping, relative and unsafe media URLs, request fields, HTTP host failover, active cancellation, selected server and fresh media resolution.
- PC anonymous API probes returned valid encrypted catalogs with 24 entries for each source and details containing a media candidate. The response content, titles, credentials and signed media URLs were not printed.
- The initial Android playback check passed for both sources: readyState 4, advancing video time, pause on leaving foreground and proxy release on close. That initial run also contained a failed source-switch test; source switches were subsequently removed at the user's request. This result does not establish the final category-label UI until its replacement device test passes.
- Final category labels, recreation behavior and playback are covered by SevenSourcePlaybackTest. Device results are recorded separately from JVM fixtures.
- The grid/detail revision compiled and was installed on the development phone. A detached Android layout test passed using only synthetic metadata: three columns, equal first-row alignment, 2:3 covers, two-line titles, a 16:9 detail preview, and description/bookmark placement below it. This test neither opens a screen nor plays media. The earlier ActivityScenario-based UI run did not complete reliably and is not counted as a passed flow test.

No completed download, VIP/account-restricted access, long-duration playback, every category/page, or all Theporny servers is claimed by these checks. Existing download controls remain available through the common player, but these two sources have not completed an offline-download acceptance test.

Reference: local `XChina_Theporny_接入交接.md` and upstream protocol snapshot c187feae6c5aa86bb899a3a00edcdb5d0d05004d. Website endpoints and permissions can change.
