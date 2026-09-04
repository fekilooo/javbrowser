# Migration

`NativeMainActivity` is the launcher. Legacy Activities/services remain compiled so working behavior is not deleted before replacement.

Migrated now: favorite code/title/thumbnail/source URL, launcher aliases, secure-screen behavior, share/view intents, and referer-aware extraction concepts behind `JavSource`. Import reads `favorites_prefs/favorites_list` once. Unsafe records remain `legacy`; no old store is cleared.

Not migrated yet: download queue, progress/history, PC recordings, local MediaStore matching, PIN/biometric credential state, favorite notes/tags, and Stripchat recording.
