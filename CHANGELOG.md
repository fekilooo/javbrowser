# Changelog

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
