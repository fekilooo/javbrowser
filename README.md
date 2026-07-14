# JAV Browser

> A privacy-focused Android browser, internal video player, download manager, and personal video library for multiple supported websites.

[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://www.android.com/)
[![Android](https://img.shields.io/badge/Android-7.0%2B-orange.svg)](https://developer.android.com/)
[![Rules](https://img.shields.io/badge/ad--filter%20rules-v3.0.1-purple.svg)](ad-filter-rules.json)

JAV Browser combines a site-aware WebView browser with stream detection, an internal fullscreen player, external-player handoff, direct MP4/HLS downloads, a searchable bookmark library, local-video management, and cloud-updatable ad-filter rules.

The current release is substantially different from the early browser-only version. It is now a multi-site media tool with dedicated handling for the websites listed below. Bookmarks, settings, download records, custom tags, and notes are stored locally on the device.

> [!IMPORTANT]
> Website layouts, domains, tokens, and stream protection can change without notice. A site listed as supported may temporarily lose playback, download, cover extraction, or ad filtering until its adapter is updated.

## Screenshots

| Home | Player | Bookmarks | Settings |
| :--: | :--: | :--: | :--: |
| <img src="https://i.imgur.com/M4mLWUz.png" width="200" alt="Home" /> | <img src="https://i.imgur.com/FWctSCb.png" width="200" alt="Player" /> | <img src="https://i.imgur.com/hIQzMod.png" width="200" alt="Bookmarks" /> | <img src="https://i.imgur.com/ueXo6Mh.png" width="200" alt="Settings" /> |

<img src="https://i.imgur.com/DodPkNT.png" width="200" alt="Settings2" />
<img src="https://i.imgur.com/MhFE59E.png" width="200" alt="Settings3" />
<img src="https://i.imgur.com/iyJwlsd.png" width="200" alt="DL" />


## What Changed From the Earlier Version?

This branch extends the original browser-centered design in the following areas:

| Earlier Design | Current Version |
| --- | --- |
| A small set of website shortcuts | A multi-site home page with dedicated search URLs and site-aware page detection |
| Basic stream detection and external-player handoff | A fullscreen internal HLS/MP4 player, external-player support, quality selection, orientation lock, and hold-to-speed-up |
| Simple URL-style favorites | A visual 16:9 library with metadata, source labels, custom tags, personal notes, filters, and backup/import/export |
| No complete built-in download workflow | Direct MP4 and HLS downloading, progress notifications, retries, cancellation, HLS merging, MP4 remux, and a download manager |
| Fixed/general ad blocking | Cloud-updatable global rules plus per-site request, navigation, allow-list, and DOM-removal rules |
| Normal public-folder access | Android Storage Access Framework support, a selectable download folder, local import, and improved VIVO Atomic Privacy compatibility |
| Mostly Traditional Chinese interface | Traditional Chinese and English UI selected from the device language |

## Main Features

### Multi-Site Browser and Search

- Home-page shortcuts for the primary video, metadata, and general video websites supported by this version.
- Site-specific search URL generation, including PigAV and AVToday search pages.
- Page recognition that distinguishes video/detail pages from listing, search, and home pages.
- Scroll-position restoration when returning to a previously visited page.
- Site-aware extraction of titles, video codes, covers, tags, and playable sources where supported.

### Internal and External Playback

- Fullscreen internal playback for detected HLS (`.m3u8`) and direct video URLs.
- Referer, Cookie, Origin, and User-Agent forwarding for sites that reject unqualified media requests.
- MISSAV quality selection when the master playlist exposes variants such as Auto, 1080p, 720p, or 360p.
- Screen lock based on the device's actual orientation at the time of locking, including portrait, reverse portrait, landscape, and reverse landscape.
- Press and hold the video surface to temporarily accelerate playback. UI controls are excluded from the gesture area.
- Configurable hold speed; the default is **3x**.
- External-player handoff to Android players such as MX Player or VLC.
- Local proxy support for selected CDN-protected streams.

### Built-In Downloader

- Direct MP4 downloads.
- HLS master-playlist parsing and highest-quality variant selection.
- Parallel MISSAV segment downloading with a sequential fallback if the server rejects concurrent requests.
- AES-128 HLS decryption when the playlist exposes a usable key.
- Referer, Cookie, Origin, User-Agent, retry, and timeout handling.
- Foreground progress notifications with speed, progress, completion, failure, and cancellation states.
- Resume-friendly temporary HLS segment storage.
- HLS segment merging followed by TS-to-MP4 remux when Android's media stack can process the stream.
- Safe fallback to the merged TS file when MP4 remux is not possible.
- Default output under **Downloads/JAV Browser**, with an optional folder selected through Android's system folder picker.

The download manager shows:

- The size and status of every app-tracked video.
- Total storage used by the app's local videos.
- Total phone storage and currently available space.
- The amount of space that can be recovered by deleting the app's local videos.
- A compact storage-usage bar that scrolls with the page rather than occupying a fixed header.
- Open, retry, cancel, delete, record cleanup, folder selection, and local import actions.

Only videos downloaded or explicitly imported through JAV Browser are added to its managed library. The app does not intentionally index every video on the phone.

### Smart Bookmark Library

- 16:9 bookmark covers with the title above the information area and the source shown at the bottom of the card.
- Source-aware bookmarks for both code-based JAV pages and non-code-based platforms.
- Metadata fields such as code, title, actors, maker, release date, rating, tags, and gallery images when available.
- Search across titles, codes, metadata, user tags, and personal notes.
- Category, source, actor, and tag filtering.
- Custom tags and personal notes edited from the TAG page's edit mode.
- Custom tags that match an existing system genre are merged into that genre instead of creating a duplicate custom-tag entry.
- Batch selection and deletion.
- Bookmark backup, import, and export.
- JavDB enrichment using the configured lookup method and token.
- JavTrailers lookup for covers, titles, gallery images, and trailer sources.
- FC2 batch-import routing through MISSAV because JavTrailers generally cannot provide FC2 metadata.
- Related-site links for matching pages when the cross-site checker finds them.

### Local Video and Private-Space Access

Android normally prevents an app from freely scanning files owned by another profile or protected space. JAV Browser therefore uses the system file/folder picker and persistent content URIs for imported local videos.

This is especially relevant to **VIVO Atomic Privacy**:

1. Put JAV Browser inside Atomic Privacy if that is where it will be used.
2. Keep the video in Atomic Privacy's Album or File Manager, not in the more restricted Super Vault.
3. In JAV Browser, open Downloads and use the local import/folder picker.
4. Select the video with VIVO's system picker, or use Share/Open with from Atomic Privacy's File Manager.

The app records only the selected video references. Background execution and long downloads can still be limited by VIVO battery management, so allow background activity and disable aggressive battery optimization for JAV Browser when necessary.

### Privacy and Language

- Biometric app lock using supported fingerprint or face authentication.
- Optional screenshot and screen-recording protection.
- Recent-apps preview protection.
- Launcher icon choices: JAV Browser, Calculator, Notes, and File Manager.
- Local storage for bookmarks, download records, settings, tags, and notes.
- Traditional Chinese and English interface text based on the device language.

The project does not require an analytics SDK for its core functions.

## Website Support

Support levels are deliberately separated. A browser shortcut or bookmark adapter does not automatically mean that protected playback and downloads are available.

| Website | Browse / Search | Smart Bookmark | Internal Playback | Built-In Download | Notes |
| --- | :---: | :---: | :---: | :---: | --- |
| [MISSAV](https://missav.ai/) | Yes | Yes | Yes | Yes | HLS quality detection and parallel segment download are supported when variants are exposed. |
| [JABLE.TV](https://jable.tv/) | Yes | Yes | Yes | Yes | HLS extraction and authenticated request headers are supported. |
| ROU.VIDEO / configured ROU mirror | Yes | Yes | When detected | When detected | Availability depends on the active mirror and player structure. |
| [AVJOY](https://avjoy.me/) | Yes | Yes | When detected | When detected | Includes protected-stream proxy handling and related-page lookup. |
| [PigAV](https://pigav.ws/) | Yes | Yes | Yes | Yes, when a media file is exposed | Uses PeerTube page/API information for title, code, cover, stream, and downloadable MP4 variants. |
| [AVToday](https://avtoday.io/cht/index.html) | Yes | Yes | Yes | When detected | Search, cover extraction, preview images, HLS playback, and dedicated ad rules are supported. |
| [JavHDPorn](https://www.javhdporn.net/) | Yes | Yes | When detected | Site-dependent | Includes special embedded-player and Streamtape-related detection. |
| [JavDB](https://javdb.com/) | Yes | Metadata source | Trailer/detail support | No | Used primarily for bookmark enrichment and metadata lookup. |
| [JavTrailers](https://javtrailers.com/) | Yes | Metadata source | Trailer support | No | Used for covers, titles, galleries, and trailers; FC2 is routed elsewhere. |
| [Pornhub](https://cn.pornhub.com/) | Yes | Yes | Web playback | Not guaranteed | Includes dedicated poster extraction and a source category for non-code bookmarks. |
| [XVideos](https://www.xvideos.com/) | Yes | Yes | Web playback | Not guaranteed | Treated as a source-based bookmark platform rather than a traditional JAV-code source. |
| [Stripchat](https://zt.stripchat.com/) | Yes | Model pages | Experimental | No | Model-page URLs are bookmarkable. Protected live-stream extraction is not guaranteed. |

## Ad-Filter Rule System

The app uses a local built-in fallback and a remotely updateable JSON rule file:

```text
https://raw.githubusercontent.com/fekilooo/javbrowser/refs/heads/main/ad-filter-rules.json
```

The repository copy is [ad-filter-rules.json](ad-filter-rules.json). On first use the app has built-in fallback rules. When the user selects **Settings → Update Rules**, the app downloads the JSON file, validates it, and stores it locally.

### Do Rule Changes Require a New APK?

| Change | Recompile APK? | User Action |
| --- | :---: | --- |
| Add/remove strings in `commonBlock` | No | Push the JSON to GitHub, then select Update Rules in the app. |
| Add/edit `siteRules` JSON | No | Push the JSON to GitHub, then select Update Rules in the app. |
| Change the cloud-rule URL | Yes | Rebuild and reinstall the app. |
| Add a new matcher type or filtering behavior in Kotlin | Yes | Rebuild and reinstall the app. |
| Change built-in fallback rules in `AdFilterRules.kt` | Yes | Rebuild and reinstall the app. |
| Change browsing, playback, download, or bookmark code | Yes | Rebuild and reinstall the app. |

In other words, normal filter maintenance is **data-only** and does not require compiling the Android project.

### Rule File Structure

```json
{
  "version": "3.0.1",
  "lastUpdate": "2026-07-14T16:33:00Z",
  "domains": {
    "missav": "missav.ai",
    "jable": "jable.tv"
  },
  "rules": {
    "commonBlock": [
      "example-ad-network.com"
    ],
    "networkBlock": [],
    "linkBlock": [],
    "iframeBlock": [],
    "redirectBlock": [],
    "siteRules": {
      "example.com": {
        "allowRequests": [],
        "requestBlock": [],
        "navigationBlock": [],
        "domRemove": []
      }
    }
  }
}
```

The parser retains `networkBlock`, `linkBlock`, `iframeBlock`, and `redirectBlock` for backward compatibility. The current main WebView interception path actively uses `commonBlock` and `siteRules`, so new rules should not rely on the legacy arrays alone. `siteRules` should be preferred for a website that serves both legitimate images/video and advertisements from its own domain.

### Per-Site URL Matchers

Each item in `allowRequests`, `requestBlock`, or `navigationBlock` may use one or more of these fields:

| Field | Meaning |
| --- | --- |
| `hostEquals` | Match one exact hostname only. |
| `hostSuffix` | Match the hostname and its subdomains. `example.com` also matches `cdn.example.com`. |
| `pathEquals` | Match one exact URL path. |
| `pathPrefix` | Match any URL path beginning with the supplied value. |
| `urlContains` | Case-insensitive substring match against the complete URL. |
| `mainFrame` | `true` for top-level page navigation, `false` for subresources/iframes, or omitted for either. |

All supplied fields in one object must match. Use multiple objects for OR behavior.

Rule priority for a page is:

1. `allowRequests` protects legitimate thumbnails, previews, players, or streams.
2. `requestBlock` blocks matching network requests.
3. `commonBlock` applies to remaining requests.
4. `navigationBlock` prevents matching links or redirects from becoming top-level navigation.
5. `domRemove` removes matching page elements and observes later DOM changes.

### DOM Removal Rules

A DOM rule contains a CSS selector and an optional ancestor selector:

```json
{
  "selector": "iframe[src*='/redirect-stripchat']",
  "closest": ".thumbnail.col"
}
```

- `selector` identifies the ad element.
- `closest` removes the nearest matching container, which prevents an empty ad card from remaining in the layout.
- If `closest` is omitted, only the selected element is removed.

Keep selectors narrow. A broad selector such as `iframe`, `.thumbnail`, or `a[target='_blank']` can remove legitimate players, preview cards, or navigation.

### AVToday Example

AVToday serves legitimate covers and previews from its own domain, while some advertisements also use same-domain paths. Blocking the entire `avtoday.io` domain would break the site. The current rules therefore allow known media paths and block only known ad paths or containers:

```json
"siteRules": {
  "avtoday.io": {
    "allowRequests": [
      { "hostSuffix": "avtoday.io", "pathPrefix": "/pic/" },
      { "hostSuffix": "avtoday.io", "pathPrefix": "/preview/" },
      { "hostSuffix": "avtoday.io", "pathPrefix": "/player" },
      { "hostSuffix": "avtoday.io", "pathPrefix": "/streaming/" }
    ],
    "requestBlock": [
      {
        "hostSuffix": "avtoday.io",
        "pathEquals": "/redirect-stripchat",
        "mainFrame": false
      },
      {
        "hostSuffix": "avtoday.io",
        "pathEquals": "/img/500x143-9u.gif",
        "mainFrame": false
      }
    ],
    "navigationBlock": [
      { "hostSuffix": "aaa9u.com" },
      { "hostSuffix": "telegram.me" }
    ],
    "domRemove": [
      { "selector": "a[href*='aaa9u.com']", "closest": "a" },
      {
        "selector": "img[src*='/img/500x143-9u.gif']",
        "closest": "a"
      },
      {
        "selector": "iframe[src*='/redirect-stripchat']",
        "closest": ".thumbnail.col"
      },
      {
        "selector": ".video-title a[href='/live']",
        "closest": ".thumbnail.col"
      },
      {
        "selector": "a.btn-categories[href*='telegram.me']",
        "closest": ".swiper-slide"
      }
    ]
  }
}
```

This removes the same-domain banner GIF, the embedded Stripchat advertisement card, the external `aaa9u.com` navigation, and the Telegram category slide without hiding AVToday's normal thumbnails.

### Editing and Publishing Rules

1. Edit `ad-filter-rules.json` as UTF-8.
2. Increase `version` and update `lastUpdate`.
3. Validate the JSON locally.
4. Test that normal thumbnails, search results, video pages, and playback still work.
5. Commit and push the file to the GitHub branch used by `DEFAULT_CLOUD_URL`.
6. Open JAV Browser and select **Settings → Update Rules**.
7. Reload the affected page and confirm the ad is removed without breaking legitimate content.

PowerShell validation:

```powershell
Get-Content -Raw -Encoding UTF8 .\ad-filter-rules.json |
    ConvertFrom-Json |
    Out-Null
```

Python validation:

```bash
python -m json.tool ad-filter-rules.json > /dev/null
```

Typical Git workflow:

```bash
git add ad-filter-rules.json
git commit -m "Update ad-filter rules"
git push origin main
```

After pushing, open the raw URL in a browser and confirm that it returns the new JSON rather than an HTML page. GitHub's raw CDN can briefly cache old content, so wait a moment and retry if the version shown in the app has not changed.

### Rule Safety Guidelines

- Prefer exact hosts and paths over vague substrings.
- Add legitimate same-domain media paths to `allowRequests` before introducing a broader block.
- Use `mainFrame: false` for ad resources or iframes that should not affect normal page navigation.
- Use `navigationBlock` for external click-through or redirect destinations.
- Use `closest` to remove the full visual ad card instead of leaving whitespace.
- Test home, search, video detail, preview, playback, bookmark cover extraction, and downloads after every site-specific change.
- Keep the cloud format declarative. The app does not need to download arbitrary JavaScript as an ad rule.

## Building the Android App

### Requirements

- Windows, macOS, or Linux.
- Android Studio with Android SDK 34 installed.
- JDK 17 for Android Gradle Plugin 8.2.0.
- Android 7.0 or newer for the target device.
- ADB if installing from the command line.

The app is configured with:

- `compileSdk = 34`
- `targetSdk = 34`
- `minSdk = 24`
- Gradle 8.2
- Android Gradle Plugin 8.2.0
- Kotlin 1.9.20

### Clone and Build

```bash
git clone https://github.com/fekilooo/javbrowser.git
cd javbrowser
```

Windows debug build:

```powershell
.\gradlew.bat assembleDebug
```

macOS/Linux debug build:

```bash
./gradlew assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Install on a Connected Device

Build and install in one command on Windows:

```powershell
.\gradlew.bat installDebug
```

Or install an already-built APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Wireless ADB

On Android 11 or newer, enable **Developer options → Wireless debugging**. Android shows separate pairing and connection ports.

```bash
adb pair PHONE_IP:PAIRING_PORT
```

Enter the six-digit pairing code shown by the phone, then connect using the wireless-debugging address:

```bash
adb connect PHONE_IP:ADB_PORT
adb devices
```

The pairing port and ADB connection port are usually different. After `adb devices` shows the phone as `device`, run:

```powershell
.\gradlew.bat installDebug
```

### Archive a Timestamped APK

This project's development workflow archives successful debug builds with a timestamp:

```powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
Copy-Item `
    .\app\build\outputs\apk\debug\app-debug.apk `
    ".\JAV-debug-$stamp.apk"
```

Example:

```text
.\JAV-debug-20260629-024819.apk
```

### When to Rebuild After an Ad-Rule Change

- If only `ad-filter-rules.json` changed and it is hosted at the configured raw URL, do **not** rebuild. Publish the JSON and update rules in the app.
- If `AdFilterRules.kt`, `MainActivity.kt`, the default cloud URL, or the rule parser changed, run `assembleDebug` or `installDebug` and distribute the new APK.

## Important Project Files

| File | Purpose |
| --- | --- |
| `ad-filter-rules.json` | Cloud-published global and per-site filter rules. |
| `app/src/main/java/com/example/javbrowser/AdFilterRules.kt` | Rule parsing, validation, cloud update, matcher definitions, and built-in fallback rules. |
| `app/src/main/java/com/example/javbrowser/MainActivity.kt` | Main WebView, navigation, page detection, extraction, filtering, and site-specific integration. |
| `app/src/main/java/com/example/javbrowser/FullscreenInternalPlayerActivity.kt` | Internal fullscreen player, quality selection, orientation lock, and press-hold speed control. |
| `app/src/main/java/com/example/javbrowser/VideoDownloadService.kt` | MP4/HLS downloading, concurrency, decryption, merging, and MP4 remux. |
| `app/src/main/java/com/example/javbrowser/DownloadsActivity.kt` | Download list, file actions, local import, and storage summary. |
| `app/src/main/java/com/example/javbrowser/FavoritesActivity.kt` | Bookmark library, search, filters, editing, tags, notes, and source-aware presentation. |
| `app/src/main/java/com/example/javbrowser/LanguageManager.kt` | Traditional Chinese and English runtime translations. |

## Troubleshooting

### A Video Page Shows Only “Auto” Quality

The player can list resolutions only when it receives a master HLS playlist containing variant streams. If a website exposes only one media playlist, only Auto is available even if the encoded video itself is high resolution.

### Playback or Download Returns HTTP 403

- Reload the website and start playback again so the app can refresh cookies and the media URL.
- Confirm the media request uses the correct Referer, Origin, Cookie, and User-Agent.
- Check whether the signed media URL has expired.
- Avoid copying an old `.m3u8` or MP4 URL from logs and reusing it later.
- Compare the embedded webpage request and internal-player request with ADB logs.

Useful log command:

```bash
adb logcat | grep -i "VIDEO\|MISSAV\|JABLE\|PIGAV\|AVTODAY\|HTTP 403"
```

On Windows PowerShell:

```powershell
adb logcat | Select-String -Pattern 'VIDEO|MISSAV|JABLE|PIGAV|AVTODAY|HTTP 403'
```

### AVToday Covers or Previews Disappear

Check that `/pic/` and `/preview/` remain in the site's `allowRequests`. Do not add `avtoday.io` itself to `commonBlock`.

### Cloud Rules Do Not Update

- Open the raw GitHub URL and check that it returns valid JSON.
- Confirm that `version` changed.
- Validate the file with `ConvertFrom-Json` or `python -m json.tool`.
- Make sure the JSON contains a `rules` object and at least one supported rule section.
- Retry after GitHub's raw CDN cache updates.

### VIVO Stops a Background Download

Allow background activity, unrestricted battery use, notifications, and network access for JAV Browser. Atomic Privacy can impose additional background restrictions even when the same app works normally in the main system.

## Current Limitations

- DRM-protected video is not supported.
- Live-stream downloading is not supported.
- Byte-Range HLS is not supported by the downloader.
- Stripchat internal live playback remains experimental because the website uses protected, short-lived stream data.
- Some sites expose only one HLS media playlist, so the player cannot offer manual resolution choices.
- TS-to-MP4 remux depends on the codecs and container structure supported by Android's media stack; the TS file is retained if remux fails.
- Private folders that the Android system picker does not expose cannot be scanned directly by the app.
- General video sites may support browsing and bookmarking without guaranteed internal playback or download extraction.

## Privacy, Legal, and Responsible Use

- This project is intended for adults only.
- Follow the laws and website terms that apply in your region.
- Download or store only media that you are authorized to access.
- Do not use the app to bypass DRM, access controls, subscriptions, or legal restrictions.
- The project is not affiliated with the websites listed above.
- Third-party websites receive normal browser/network requests when you visit them and remain governed by their own privacy policies.

## License

No license file is currently included in this repository. Add an explicit license before redistributing or reusing the source outside the terms granted by the repository owner.

---

<p align="center">
  <strong>JAV Browser</strong><br>
  Private browsing, internal playback, smart bookmarks, and local video management for Android.
</p>
