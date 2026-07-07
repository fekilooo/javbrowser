# JAV Browser

> A private Android browser, internal player, download manager, and smart library for MISSAV, JABLE.TV, ROU.VIDEO, AVJOY, JavHDPorn, JavDB, and JavTrailers.

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://www.android.com/)
[![Min SDK](https://img.shields.io/badge/min%20sdk-API%2024%20(Android%207.0)-orange.svg)]()

---

## Overview

**JAV Browser** is a privacy-focused Android app built for browsing, playing, bookmarking, and managing videos from supported JAV sites. It combines an ad-reduced WebView browser, an internal fullscreen player, external player handoff, smart bookmarks, cross-site lookup, and a built-in video downloader.

The app is designed for local-first use: bookmarks, download records, settings, and imported local video references are stored on the device.

---

## Highlights

- **Ad-reduced browsing** for supported sites with network blocking, DOM cleanup, popup handling, and cloud-updatable rules.
- **Internal fullscreen player** with HLS playback, orientation lock, resolution selection on supported MISSAV streams, and press-and-hold speed boost.
- **External player support** for apps such as MX Player, VLC, and other Android video players.
- **Built-in video downloads** for direct MP4 and HLS streams, including progress notifications, cancel/retry, resume-friendly HLS segment cache, and MP4 remux when possible.
- **Download management page** with file size, app video storage usage, phone free space, folder selection, import, open, delete, retry, and record cleanup.
- **Smart bookmark library** with thumbnails, titles, metadata, cast/tag filtering, batch delete, backup/import/export, and lazy JavDB enrichment.
- **Batch bookmark import** from pasted/shared video codes, including FC2 handling through MISSAV when JavTrailers is unavailable.
- **Cross-site related links** for Jable, MISSAV variants, AvJoy, and JavHDPorn when matching pages are found.
- **Local video import** through Android's file picker, useful for private/isolated storage spaces where direct folder scanning is restricted.
- **Privacy tools** including biometric lock, optional screenshot blocking, recent-apps protection, and launcher icon disguise.
- **Bilingual UI** that follows the device language for Traditional Chinese and English.

---

## Screenshots

| Home | Player | Bookmarks | Settings |
| :--: | :--: | :--: | :--: |
| <img src="https://i.imgur.com/M4mLWUz.png" width="200" alt="Home" /> | <img src="https://i.imgur.com/FWctSCb.png" width="200" alt="Player" /> | <img src="https://i.imgur.com/hIQzMod.png" width="200" alt="Bookmarks" /> | <img src="https://i.imgur.com/ueXo6Mh.png" width="200" alt="Settings" /> |

<img src="https://i.imgur.com/DodPkNT.png" width="200" alt="Settings2" />
<img src="https://i.imgur.com/MhFE59E.png" width="200" alt="Settings3" />
<img src="https://i.imgur.com/iyJwlsd.png" width="200" alt="DL" />
---

## Browser And Playback

JAV Browser detects supported video pages and shows playback controls when a playable stream is found.

- Internal player for HLS and direct video URLs.
- One-tap external player handoff.
- MISSAV HLS quality selector when the master playlist exposes variants such as Auto, 1080p, 720p, and 360p.
- Current-orientation screen lock in the internal player, including portrait, landscape, and reverse landscape states.
- Press-and-hold video surface speed boost, configurable in Settings. The default is 3x.
- Referer, Cookie, and User-Agent handling for protected media URLs.

---

## Downloads

The built-in downloader is intended for videos that can be legally accessed and downloaded by the user.

- Direct MP4 downloads.
- HLS playlist parsing and highest-quality variant selection.
- MISSAV HLS parallel segment downloads with sequential fallback.
- AES-128 HLS segment handling when keys are available.
- HLS segment merge and TS-to-MP4 remux when Android's media stack can remux the file.
- Foreground notifications with progress, speed, completion, failure, and cancel action.
- Resume-friendly temporary segment cache for interrupted HLS downloads.
- Custom download folder support through Android's folder picker.
- Download manager showing each file size plus total app video usage and remaining phone storage.
- Local video import for files that Android exposes through the system picker.

Current limitations:

- DRM and live streams are not supported.
- Byte-Range HLS is not supported.
- Some protected/private storage areas can only be accessed through Android's file picker or share/open-with flow.

---

## Bookmarks And Library

The bookmark system is built as a video library rather than a simple URL list.

- One-tap bookmark from supported video/detail pages.
- Visual cards with title, cover, code, tags, actors, maker, rating, release date, and related links when available.
- Search, filters, batch selection, batch delete, and date grouping.
- JavDB enrichment through the configured method and token.
- JavTrailers lookup for title, cover, gallery images, and trailer previews.
- Batch add from pasted/shared JAV codes.
- FC2 batch bookmarks use MISSAV as the primary source, because FC2 entries are not available on JavTrailers.
- Bookmark backup, import, and export from Settings.

---

## Supported Sites

Primary browsing, playback, and download targets:

- [MISSAV](https://missav.ws)
- [JABLE.TV](https://jable.tv)
- [ROU.VIDEO](https://rou.video)
- [AVJOY](https://avjoy.me)
- [JavHDPorn](https://www.javhdporn.net)

Metadata, library, and related-link helpers:

- [JavDB](https://javdb.com)
- [JavTrailers](https://javtrailers.com)

Domains can be updated through app rules when supported sites change hostnames.

---

## Privacy Features

- Biometric app lock with fingerprint/face unlock when supported by the device.
- Optional screenshot and screen recording blocking.
- Recent-apps privacy behavior.
- Launcher icon disguise:
  - JAV Browser
  - Calculator
  - Notes
  - File Manager
- Local-first storage for bookmarks, settings, download records, and local video references.

No analytics or tracking SDK is required for the app's core features.

---

## Installation

1. Download the latest APK from the [Releases page](https://github.com/fekilooo/javbrowser/releases/).
2. Enable `Install Unknown Apps` for the app or browser used to install the APK.
3. Install and launch JAV Browser.
4. Open `Settings` and update the rule set for the latest ad-blocking and domain rules.
5. Optional: enable app lock, icon disguise, screenshot protection, download folder, and JavDB enrichment.

---

## Requirements

- Android 7.0+ (API 24)
- Android 13+ notification permission is recommended for download progress notifications.
- Biometric hardware is optional and only required for biometric lock.
- Storage and video permissions may be requested depending on Android version and local video features.

---

## Typical Workflows

| Workflow | What You Do |
| --- | --- |
| Clean browsing | Open a supported site, browse normally, and let rule-based cleanup reduce disruptive ads. |
| Internal playback | Open a video page, tap Play, use fullscreen controls, quality selector, orientation lock, and speed boost. |
| External playback | Tap Play and choose an external player when preferred. |
| Download | Open a playable video, tap download, monitor progress in notifications or the Downloads page. |
| Build a library | Bookmark pages, enrich metadata, filter by tags/actors, and follow related site links. |
| Batch bookmarks | Paste or share multiple video codes from the home page and add them in one pass. |
| Local videos | Import videos through the Android picker and play them from the Downloads page. |
| Privacy mode | Enable app lock, disguise the launcher icon, and hide sensitive previews. |

---

## Disclaimer

- This app is intended for adults only.
- Use it responsibly and follow the laws and terms that apply in your region.
- Only download or store media that you are allowed to access.
- Supported websites may change their structure, domains, or protection methods at any time, which can affect playback, downloads, metadata lookup, and ad reduction.

---

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

---

<p align="center">
  <strong>JAV Browser</strong><br>
  Private browsing, internal playback, smart bookmarks, and local video management for Android.
</p>
