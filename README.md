# Hematite for Android

An Android port of Hematite, the Markdown notes vault for Linux. Notes are
plain files in a vault folder, edited with formatting shown in place, and synced
with Hematite on your computer through a WebDAV server.

## Features

- **Notes and folders:** a tree of notes, newest first in each folder. Long-press
  a note or folder to rename it, move a note to another folder, or move it to
  Trash. Moving a note updates its relative image and file links.
- **Search:** find notes by title, path, or content, with matching text
  previews. All words must match; title matches rank first.
- **Markdown:** headings, bold, italics, strikethrough, bullets, tappable task
  checkboxes, links, tables, and images. Formatting markers appear on the line
  you are editing. Lists continue when you press Enter.
- **Editing toolbar:** undo and redo, bold, italic, strikethrough, checklists,
  bullets, list indentation, and inserting images from your photos. Images are
  saved in the vault, like pasted images on the desktop.
- **Autosave:** on by default; change it in Settings. With autosave off, a Save
  button appears and leaving a note asks whether to save it.
- **Trash:** notes and folders moved to Trash go to the vault's hidden `.trash`
  folder, and can be restored or deleted from Settings → Trash.
- **WebDAV sync:** the same protocol as the desktop app, so both can share one
  server folder.
- **Wide screens:** in landscape or on tablets, the notes list and the open note
  appear side by side.
- **Keyboard shortcuts** with a hardware keyboard: Ctrl+N, Ctrl+F, Ctrl+S,
  Ctrl+B, Ctrl+I, Ctrl+Z, Ctrl+Shift+Z, Ctrl+plus/minus/0 for text size,
  Ctrl+Enter to open a link while editing it, and Tab/Shift+Tab in lists.

## Vault folder

The vault starts in the app's own storage
(`Android/data/io.github.hematite/files/Vault`), which needs no permissions but
is removed if the app is uninstalled. In Settings you can choose a folder in
shared storage instead, such as `Documents/Vault`, so other apps can open the
notes too. That needs All files access, which Hematite asks for when you choose
the folder.

## Sync

Open Settings → Connect, and enter the server address, username, and password.
Use a full URL such as `https://example.org/vault/`, or a domain name; Hematite
tries the root and then `/vault/`.

- Saves go to disk first, so you can work offline. Pending changes sync when the
  connection returns.
- Hematite checks for changes when you return to it, every 30 seconds while it
  is open, and when the network comes back. Pull down on the notes list to sync
  now.
- The cloud icon shows progress, success, or a problem. Tap it for details or
  to sync now.
- Remote changes never replace unsaved edits. Conflicting edits are kept as
  separate `.conflict-remote-…` files, as on the desktop.
- Notes deleted on another device go to Hematite's Trash. Files moved to Trash
  in Hematite are deleted remotely; files removed by other apps are downloaded
  again unless **Sync files deleted outside Hematite** is on.
- The password is encrypted with a key held in the Android Keystore.

The server must provide strong ETags and support conditional writes or exclusive
WebDAV write locks, as for the desktop app. rclone's WebDAV server works through
the lock-based method.

## Build and install

Requires JDK 17 or later and the Android SDK (platform 37). Point
`local.properties` at the SDK, then:

```sh
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key, so it installs directly.

## Tests

```sh
./gradlew testDebugUnitTest            # ports of the desktop's unit tests
./gradlew testDebugUnitTest -Plive -PdavUrl=http://127.0.0.1:PORT/
```

The live test runs the desktop's WebDAV round trip against a scratch folder on
a server with user `test` and password `secret`, for example
`rclone serve webdav DIR --addr 127.0.0.1:PORT --user test --pass secret`.

## How it works

- `core/`: plain Kotlin, tested on the JVM, ported from the desktop's Rust
  modules with the same rules: `Dav.kt` (sync_dav.rs), `SyncEngine.kt`
  (sync_engine.rs), `Markdown.kt` (markdown_syntax.rs, using
  [commonmark-java](https://github.com/commonmark/commonmark-java) instead of
  pulldown-cmark), `Lists.kt`, `Moves.kt`, `Search.kt`, and `Vault.kt`.
- `Sync.kt`: the sync controller from sync.rs: sync history in the desktop's
  `state.json` format, deletion tracking, and applying downloads as they arrive.
- `ui/Editor.kt`: the live-preview editor. Like the desktop, it keeps the
  Markdown source in the text field and hides markers with tiny transparent
  text, so cursor positions always match the file. Tables and images are drawn
  over lines that are hidden and enlarged to make room for them.
