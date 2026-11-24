# Dialog window crash fixes

The supplied Crashlytics issue summaries identify two 1.2.1 entry points with WindowManager.BadTokenException: FavoritesActivity.showJavDbManualWebViewDialog and MainActivity.showPlaybackDebugDialog. Full remote stacks were not fetched. Code review found both can be reached by asynchronous callbacks and previously called show without validating the Activity/window.

## Changes

- Normalize these dialog entry points to the main thread. Require a resumed, non-finishing, non-destroyed Activity with an attached decor view and window token before allocating/showing the window. Catch BadTokenException and InvalidDisplayException at the actual show boundary to handle system-side invalidation after the check.
- Skip late playback diagnostic/external-player actions when the owner is inactive. Restore the play button for an existing stopped Activity so it is usable on return. Discard results completely after finishing/destroying the owner.
- Track and dismiss the playback diagnostic dialog on owner destruction.
- Keep only one JavDB manual dialog per Activity. Centralize cleanup behind an idempotent guard: remove delayed callbacks, stop loading, detach WebView clients and parent, and destroy the WebView once. Run cleanup on dismiss, failed show and owner destruction. Reject delayed JavaScript results after closure and route the success timer through normal dismissal.

## Validation and limits

Debug app/test compilation passed and the replacement APK was installed on the development phone. Both Android regression tests passed. They exercise the same show boundary without opening a screen: a queued callback rechecks whether its owner is active, inactive callbacks never execute show, synthetic token/display failures are contained, and a not-yet-created owner/off-main invocation is rejected. These checks do not reproduce the original production events or prove a zero-crash rate in Crashlytics.

An initial ActivityScenario-based foreground/background/recreation run did not complete reliably on the connected device and was stopped; it is not counted as passing. The replacement checks avoid launching Activities. No production issue is marked resolved, and post-release Crashlytics observation remains necessary.
