# Luma — native multi-source JAV client

Luma is an in-progress native Android media client. Websites are content providers: normal navigation, search, details, favorites, source selection, and playback stay inside the app.

## Current features

- Material 3 / Compose UI: Home, Search, Discover, Detail, Library, Downloads, Settings, Media3 player.
- Concurrent progressive search, isolated source errors, and JAV-code deduplication.
- Code normalization including separator, URL-encoding, and FC2-PPV variants.
- Deterministic metadata merging and playback ranking.
- JavDB metadata search, MISSAV resolution, and JABLE playback fallback.
- Source health summaries and a controlled browser-verification session.
- Legacy favorite migration; unresolved records are retained.
- ACTION_VIEW/ACTION_SEND, EN/zh-CN/zh-TW resources, HLS/MP4 playback, screenshot protection.

| Source | Search | Metadata | Playback | Status |
|---|---:|---:|---:|---|
| JavDB | Yes | Yes | No | HTTP/Jsoup |
| MISSAV | Yes | Basic | Yes | HLS/MP4; verification may be required |
| JABLE | Yes | Basic | Yes | HLS/MP4 fallback |

PigAV, AVToday, JavTrailers, and JavHDPorn are not yet migrated into the new API.

## Build

Requires JDK 17, Android SDK 34, and access to Google Maven/Maven Central.

The `Android CI` workflow runs unit tests, lint, and a debug build on every branch update, then publishes the APK as a workflow artifact.

```bash
./gradlew assembleDebug
./gradlew test
```

The old XML/WebView code remains as migration/fallback code, but `.nativeapp.NativeMainActivity` is the launcher. Read [ARCHITECTURE.md](ARCHITECTURE.md), [SOURCE_DEVELOPMENT.md](SOURCE_DEVELOPMENT.md), [MIGRATION.md](MIGRATION.md), and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Legal

This repository had no license when the rewrite began. No license grant should be inferred. New native code was independently written. Users are responsible for applicable law and provider terms.
