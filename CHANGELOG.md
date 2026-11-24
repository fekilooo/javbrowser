# Changelog

## 1.2.2 - 2026-11-24

- Fix subtitle muxing for MP4 files whose media chunk offsets exceed the unsigned 32-bit limit: promote chunk-offset tables to co64 and compensate all tracks for the enlarged movie header. Convert remaining mux assertion failures into recoverable packaging errors so the existing download path retains the source video.
- Guard JavDB manual-completion and playback-diagnostic dialogs against inactive, finishing, destroyed or detached Activity windows. Handle token invalidation at show time, discard late playback UI actions and centralize JavDB WebView/timer cleanup on dismiss/destroy.
- Bind the media proxy to loopback.
- Move download playback preferences to a single player-settings button at the top right of Downloads. Apply Ask every time, Internal player, or Other apps to all video rows, retain existing preferences, and remove the repeated player-choice button beside each Play action.

## 1.2.1 - 2026-10-30

### Faster MP4 Packaging

- Remux downloaded TS parts directly through a buffered, seekable source, removing the intermediate TS merge on the normal path.
- Cache extractor packet metadata, stage MP4 writes in app-private storage when space permits, and copy to the destination in large blocks. Keep direct output for low-space devices and the merged-file fallback for unsupported sources.
- Buffer subtitle output, preserve encoded samples and their timing, and propagate packaging cancellation without starting a fallback.
- On the development phone, post-download processing for a 96 MB synthetic HLS fixture fell from 10.485 to 3.088 seconds, approximately 71%. The result's packet hashes and timestamps matched the baseline, and full video/audio decoding passed. See `docs/mp4-packaging-performance.md` for scope and reproduction.
- A 30-minute synthetic fixture reduced processing from 66.669 to 24.273 seconds, approximately 64%, with matching encoded packets and timestamps and successful full decoding. Nine JVM tests, six Android checks, and both JavRate session tests passed.

### JavRate Download Authorization

- Forward the player's `apiSecretIssuedAt` value in token-refresh requests, matching the current website request format. Missing this field caused HTTP 400 responses when the initial download authorization needed renewal.
- Extend the session regression test with a server fixture that rejects missing or altered secret issue times, while retaining concurrent-refresh and transient-failure coverage.
- On the development phone, otherwise identical signed refresh requests returned HTTP 400 without this field and HTTP 200 with a valid replacement token when it was included. Debug compilation and replacement installation succeeded.
- The user's full 1,688-segment download completed at 100 percent, including MP4 packaging and embedded subtitles. Verified the final MP4 header and actual file size of 2,058,099,147 bytes against the completed download record; no subtitle error was recorded. Multiple ordinary refresh cycles returned HTTP 200 while downloading after the player was closed.

## 1.2.0 Update - 2026-10-27

### Stripchat Watch Status

- Separate unavailable status checks from confirmed offline/public-broadcast waiting states in both watch controls. Show recording startup and retry states explicitly.
- When the status API is unavailable, confirm an advancing, infinite-duration video in the current model's main player using two samples. Reject paused/stalled players, finite previews, other model pages, and stale model overlays.
- Reuse an already playing model video when starting recording instead of reloading the page. Retain explicit offline/private API results as higher-priority restrictions.
- Retry unknown status checks after 15 seconds, expire stuck recording-start attempts, and reset offline confirmations after unknown results.
- Mount the floating watch controls when the page becomes visible and during SPA model navigation. Restore controls removed by a site rerender, including localized Stripchat domains and delayed body creation.
- Remove the raw internal-player debug toast that displayed successful `overlay-ready`/`overlay-injected` responses as a confusing popup. Report actual capture, encoder, and file-write failures with explicit diagnostic stages instead.
- Close native recording storage when encoder startup fails after creating the file, and stop recording after chunk-write failure instead of retaining a misleading active state.
- Eight JavaScript playback/control/recorder-failure tests and two Kotlin watch-state tests passed. The debug APK compiled and was replacement-installed on the development phone.
- Direct device inspection confirmed advancing playback and a growing recording file. A 149.12-second MP4 (74,342,660 bytes) contained H.264 video at 1280 x 720 and AAC audio, decoded successfully, and had strictly increasing packet decode timestamps in both tracks. The latest installation logged storage creation and the first written chunk, and its active recording file continued to grow. Automatic offline/reconnect transitions remain unverified.

## 1.2.0 Update — 2026-10-23

### Usage Analytics and Crash Reporting

- Integrate Firebase Analytics and Crashlytics, enabled automatically on startup without an in-app opt-out.
- Count selected feature openings, internal-player attempts and actual playback starts, media errors, and download starts, completions, cancellations, and failures.
- Restrict custom event parameters to fixed feature/outcome categories, online/offline mode, and bounded numeric media error codes. Exclude browsing content, titles, queries, cookies, and tokens.
- Remove exception messages throughout cause and suppressed-error chains before passing Java/Kotlin fatal errors and download failures to Crashlytics, retaining exception type names and code locations.
- Disable advertising consent, advertising ID collection, and automatic screen reporting, and disclose collection in Settings.
- Keep the project-specific Firebase configuration local and document setup and measurement limits in `docs/telemetry.md`.

## 1.2.0 Update — 2026-10-15

### JavRate Playback

- Recognize date-prefixed CDN asset directories when the authorization token scope omits that date prefix. Propagate and refresh credentials for the matching variant playlists and segments without sharing them with other asset directories or dates.
- Support normal-storage player configurations that provide a direct media URL without Bunny-style token fields.
- Refresh nearly expired initial credentials immediately and retain HTTPS and trusted-host restrictions.
- Reproduced repeated variant-playlist HTTP 403 responses on the development phone before the directory fix. After replacement installation, internal playback advanced from approximately 7 to 36 seconds at 1920 × 1080 with zero dropped decoded frames; the observed subsequent media requests and token refreshes returned HTTP 200. This does not establish playback for every title or completed downloads.
- The targeted Kotlin tests and JavaScript session tests passed, and the debug APK compiled successfully. The app version remains 1.2.0.

## 1.2.0 Update — 2026-10-14

The app version remains 1.2.0. These updates extend the existing 1.2.0 codebase.

### Playback History

- Record the original webpage when opening the internal player, including subsequent episode selections.
- Keep the latest 200 unique pages and move replayed pages to the top. Trim older histories to the same limit.
- Display video covers, website icons, page titles, actress names when supplied by the page, and playback dates.
- Recover missing artwork and actress metadata for existing entries where the website permits access.
- Search history by title, actress, or URL; swipe left to delete individual records, or confirm before clearing the list.
- Reopen the original webpage from a history entry and return to the history list when closing the browser.
- Place a full-width dark yellow history button below all HOME website shortcuts, with another entry in Settings.

### Internal Player and Episodes

- Add ROU and JAVDAY episode selection inside the internal player and automatically advance to the next available episode when playback ends.
- Preserve episode labels and update the selected page and download source when switching episodes.
- Resolve ROU's primary stream before the webpage player has been started and prevent thumbnail playlists from replacing the video stream.
- Recognize changing ROU domains and extensionless HLS API endpoints.
- Add a clean-picture button beside Close to hide player controls and overlays; tap the picture to restore them.

### HOME and Search

- Show an optional HOME prompt for copied links belonging to supported website shortcuts while the app is in the foreground.
- Correct HOME page recognition so clipboard prompts can appear after returning to the app.
- Add separate ROU video and series search sources, preserve the selected tab during pagination, and add JAVDAY search shortcuts.
- Extend site navigation, search adapters, source filters, and cover handling for JAVDAY, SUPJAV, and JavRate.

### Site Playback, Downloads, and Subtitles

- Follow the selected SUPJAV Server and its player frames, invalidate stale selections, retain the player Referer, and recognize EVS text-based HLS manifests and Streamtape media endpoints.
- Use direct WebView transport for supported SUPJAV playback and report unavailable or verification-gated sources without retaining a previous stream.
- Detect AVToday signed MP4 and legacy HLS from the selected player iframe while excluding page previews.
- Maintain JavRate authorization sessions for playback and downloads, and isolate advertising popups from the main page.
- Import JavRate WebVTT subtitles into the internal player and embed available tracks into downloaded MP4 files without re-encoding video.
- Read embedded subtitles during offline playback and preserve subtitle packaging status and failure information in download records.
- Add subtitle and packaging repair actions for existing downloads, preserve edit-list timing, and normalize ADTS AAC samples during MP4 packaging.

### Build and Validation Notes

- Debug APK compilation and replacement installation succeeded on the development Android device.
- Website metadata remains dependent on the page layout and access restrictions; unavailable actress names use a placeholder.
- Earlier site-specific device observations are documented in `docs/avtoday-player-analysis.md` and `docs/supjav-server-analysis.md`.
- A complete regression test run was not performed for this publication.

## 1.2.0 — 2026-10-02

### Offline Downloads and Playback

- Consolidate duplicate library records that point to the same physical video file while retaining download metadata.
- Preserve source presentation timestamps during MP4 remuxing to avoid corrupting B-frame timing and causing uneven offline playback.
- Improve cleanup of the original transport-stream file after MP4 conversion.

### Video Deletion

- Delete videos through the appropriate local-file, document-provider, or MediaStore operation.
- Request system consent when Android requires permission to delete a media file.
- Retry deletion after Android 10 grants permission.
- Keep library records when file deletion fails or permission is denied, with a visible explanation.

### Browser Navigation

- Follow WebView page history before returning from a search-result or sales-ranking browser page.
- Return to the originating list when no previous page remains.
- Provide a close action that immediately returns to the originating list.
- Apply the same Back and Close behavior to original search-source pages.
- Reset earlier browser history when opening a sales-ranking item.
- Refresh the close action when an existing browser activity receives a new navigation request.

### Validation

- Debug APK compilation and replacement installation were verified on a physical Android device during development.
- Website-specific navigation and Android 10 deletion consent require additional device coverage.
