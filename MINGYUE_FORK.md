# Mingyue Live fork of Metrolist

This branch (`mingyue`) adds an explicit, non-UI control surface on top of
Metrolist's `MediaLibraryService`. It exists so Mingyue Live can favourite and
download an arbitrary song without switching the current player item, changing
the queue, or relying on `TOGGLE_LIKE` / muted playback workarounds.

`main` stays an upstream mirror. All fork work lands on `mingyue` only.

- fork remote: `origin` = `https://github.com/flufy3d/Metrolist.git`
- upstream: `upstream` = `https://github.com/MetrolistGroup/Metrolist.git`
- minimum compatible Mingyue Live release: the one that detects
  `SET_LIKED` + `GET_DOWNLOAD_STATE` in `onConnect`'s available session commands

Upstream does not accept feature PRs while the feature freeze is active. This
fork is maintained locally and is not proposed upstream.

## Protocol overview

All commands are Media3 `SessionCommand` custom actions. Arguments and results
are `android.os.Bundle` values; no JSON strings are embedded. Controllers must
send commands only after seeing them in the `onConnect` result's
`availableSessionCommands`. Every command below is registered there.

Song ids use the same safe-id rule as Media3 download request ids:
`^[A-Za-z0-9_-]{1,128}$`. Path separators, whitespace, empty values, and
over-long ids are rejected with `RESULT_ERROR_BAD_VALUE`.

### `DOWNLOAD` (existing since `6a815923d`)

Queue a download for any song id. Does not change the current item or queue.

| argument | type | required | notes |
| --- | --- | --- | --- |
| `songId` | String | yes | safe media id |
| `title` | String | yes | used when the song is not in the database |
| `artist` | String | no | defaults to `Unknown` |
| `durationMs` | long | no | converted to seconds |
| `thumbnailUrl` | String | no | |
| `albumId` | String | no | |
| `albumTitle` | String | no | |
| `explicit` | boolean | no | default `false` |

Result: `RESULT_SUCCESS` when the request was accepted and queued, otherwise
`RESULT_ERROR_BAD_VALUE`. Download progress is observed with
`GET_DOWNLOAD_STATE`; `DOWNLOAD` itself does not return a job id.

### `SET_LIKED`

Explicitly set Metrolist's liked flag for a song. It does **not** require the
song to be the current player item, does not switch songs, and does not touch
the queue. Implemented directly, never by invoking `TOGGLE_LIKE`.

| argument | type | required | notes |
| --- | --- | --- | --- |
| `songId` | String | yes | safe media id |
| `liked` | boolean | yes | must be present; `false` is a value, not "missing" |
| `title` | String | for new songs | required only when the song is not already in the database |
| `artist` | String | no | used when creating a row |
| `durationMs` | long | no | used when creating a row |
| `thumbnailUrl` | String | no | used when creating a row |
| `albumId` | String | no | used when creating a row |
| `albumTitle` | String | no | used when creating a row |
| `explicit` | boolean | no | default `false` |

Semantics:

- Existing song: updates `liked` and `likedDate`; liking also ensures library
  membership, mirroring Metrolist's stock heart button. Unliking leaves library
  membership alone.
- Missing song: creates a song row from the supplied metadata, then applies the
  liked state. A blank/missing title returns `RESULT_ERROR_BAD_VALUE`.
- Repeated `SET_LIKED(true)` is idempotent and keeps the original liked date.
- If the row carries `libraryAddToken` or `libraryRemoveToken`, a best-effort
  `SyncUtils.likeSong` is queued. If neither token is present, the change is
  local to Metrolist's database and the result marks it accordingly.
  `SyncUtils` itself skips the remote write when the user is not logged in.
- If the changed song is currently playing, the media notification and widget
  are refreshed. Playback, position, and queue are untouched.

Result extras:

| key | type | meaning |
| --- | --- | --- |
| `songId` | String | echoed id |
| `liked` | boolean | stored value |
| `localOnly` | boolean | `true` when no YouTube sync was attempted |

Return codes: `RESULT_SUCCESS` once the local database write succeeds,
`RESULT_ERROR_BAD_VALUE` for invalid args or a new song without a title,
`RESULT_ERROR_UNKNOWN` when the database write fails.

### `GET_LIKED_STATE`

Single-song lookup, so controllers do not have to browse the whole liked
playlist.

| argument | type | required |
| --- | --- | --- |
| `songId` | String | yes |

Result extras: `songId` (String), `exists` (boolean), `liked` (boolean).
`RESULT_ERROR_BAD_VALUE` for an invalid id, `RESULT_ERROR_UNKNOWN` on a read
failure.

### `GET_DOWNLOAD_STATE`

Single-song download lookup. Reads one `DownloadIndex` row plus one song row;
it never scans the full download list.

| argument | type | required |
| --- | --- | --- |
| `songId` | String | yes |

Result extras:

| key | type | meaning |
| --- | --- | --- |
| `state` | String | `not_found`, `queued`, `downloading`, `completed`, `failed`, `removed` |
| `progress` | int | 0..100, `-1` when unknown |
| `cached` | boolean | completed download, or Metrolist's `isDownloaded` flag |
| `error` | String | optional; currently `failure_reason_<code>` for failed downloads |

State mapping:

- no download row and `isDownloaded = false` -> `not_found`
- no download row and `isDownloaded = true` -> `completed` (migration/cache case)
- `STATE_QUEUED` -> `queued`, progress `0`
- `STATE_DOWNLOADING` -> `downloading`, progress from Media3 `percentDownloaded`
- `STATE_COMPLETED` -> `completed`, progress `100`, `cached = true`
- `STATE_FAILED` -> `failed`, progress `-1`, optional `error`
- `STATE_STOPPED` / `STATE_REMOVING` -> `removed`

`RESULT_ERROR_BAD_VALUE` for an invalid id, `RESULT_ERROR_UNKNOWN` on a read
failure.

### `CANCEL_DOWNLOAD`

| argument | type | required |
| --- | --- | --- |
| `songId` | String | yes |

Calls `DownloadManager.removeDownload(songId)` when the download index has an
entry. Result extras: `songId` (String), `removed` (boolean; `false` means no
download existed). `RESULT_ERROR_BAD_VALUE` for an invalid id,
`RESULT_ERROR_UNKNOWN` on a failure.

### `SET_AUTO_DOWNLOAD`

Writes Metrolist's `AutoDownloadOnLikeKey`, so Mingyue Live's auto-download
switch can be reflected in the fork.

| argument | type | required |
| --- | --- | --- |
| `enabled` | boolean | yes (`containsKey` is checked) |

Result extras: `enabled` (boolean). Missing key -> `RESULT_ERROR_BAD_VALUE`,
DataStore failure -> `RESULT_ERROR_UNKNOWN`.

### Legacy `TOGGLE_LIKE`

Kept unchanged for stock clients and old Mingyue Live builds. New code must use
`SET_LIKED`; `TOGGLE_LIKE` acts on the current player item only and has no
direction guarantee under concurrency.

## Implementation map

- `app/src/main/kotlin/com/metrolist/music/constants/MediaSessionConstants.kt`
  - action names, arg/extra keys, `SessionCommand` instances
- `app/src/main/kotlin/com/metrolist/music/playback/MediaSessionCommands.kt`
  - safe-id validation, `SET_LIKED` parsing, title requirement,
    download-state mapping (pure functions)
- `app/src/main/kotlin/com/metrolist/music/playback/MediaLibrarySessionCallback.kt`
  - command registration in `onConnect`, handlers, result bundles
- `app/src/main/kotlin/com/metrolist/music/playback/DownloadCommand.kt`
  - shared safe-id rule for `DOWNLOAD`
- `app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt`
  - `refreshLikedStateForCurrentSong` (notification/widget refresh only)
- `app/src/test/kotlin/com/metrolist/music/playback/MediaLibrarySessionCallbackTest.kt`
  - validation and state-mapping tests

No database schema change was made. No UI feature was added.

## Build

Environment:

- JDK 21: `E:\Projects\tools\jdk-21.0.12.1+1`
- Android SDK: `C:\Users\JamesChen\AppData\Local\Android\Sdk`
- `local.properties`: `sdk.dir=C:/Users/JamesChen/AppData/Local/Android/Sdk`

Default debug build (package `com.metrolist.music.debug`):

```powershell
$env:JAVA_HOME = 'E:\Projects\tools\jdk-21.0.12.1+1'
cd E:\Projects\Metrolist
.\gradlew.bat :app:assembleFossDebug
```

Unit tests:

```powershell
.\gradlew.bat :app:testFossDebugUnitTest --tests 'com.metrolist.music.playback.MediaLibrarySessionCallbackTest'
```

APK: `app\build\outputs\apk\foss\debug\app-foss-debug.apk`

## Replacing the stock installation on the device

Stock Metrolist 13.7.0 is release-signed and uses `com.metrolist.music`, so a
normal debug build cannot update it. Two supported options:

1. Keep the stock package and install the fork side-by-side under
   `com.metrolist.music.debug` (default debug build). Mingyue Live can only
   control one package at a time; this is useful for testing but not for the
   final replacement.
2. Replace the stock app: build a debug APK with the stock application id and
   a persistent debug keystore, then uninstall/reinstall.

Persistent debug keystore (ignored by git, path `app/persistent-debug.keystore`):

```powershell
keytool -genkeypair -v `
  -keystore app\persistent-debug.keystore `
  -storepass android -keypass android `
  -alias androiddebugkey `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -dname "CN=Android Debug,O=Android,C=US"
```

Same-package build:

```powershell
$env:JAVA_HOME = 'E:\Projects\tools\jdk-21.0.12.1+1'
$env:METROLIST_APPLICATION_ID = 'com.metrolist.music'
$env:METROLIST_APP_NAME = 'Metrolist'
cd E:\Projects\Metrolist
.\gradlew.bat :app:assembleFossDebug
adb -s 100.83.39.115:5555 install -r app\build\outputs\apk\foss\debug\app-foss-debug.apk
```

`METROLIST_APPLICATION_ID` removes the `.debug` suffix;
`METROLIST_APP_NAME` removes the "Metrolist Debug" label. When
`app/persistent-debug.keystore` exists it is used automatically. Optional
overrides: `METROLIST_DEBUG_KEYSTORE_PASSWORD`, `METROLIST_DEBUG_KEY_ALIAS`,
`METROLIST_DEBUG_KEY_PASSWORD`.

> Uninstalling `com.metrolist.music` deletes its library and downloads. Confirm
> with the user first and back up `/data/data/com.metrolist.music` when root is
> available. Mingyue Live's own `music.db.favorites` is a separate database and
> is not affected.

Release signing is intentionally not configured in this fork. If a release
build is needed, set up a private keystore outside the repository; never commit
it.

## Keeping up with upstream

```powershell
git fetch upstream main
git checkout mingyue
git merge upstream/main
# resolve conflicts, then:
.\gradlew.bat :app:assembleFossDebug
.\gradlew.bat :app:testFossDebugUnitTest --tests 'com.metrolist.music.playback.MediaLibrarySessionCallbackTest'
# install and verify on the device
```

Rules:

- `main` is an upstream mirror; never commit fork changes there.
- `mingyue` is the working branch; do not force-push, rewrite history, or
  delete branches.
- Do not commit `local.properties`, keystores, `app/build`, databases, logs, or
  images.
- Commit messages: `feat(playback): ...` / `fix(...)`.
- Every new API needs documentation, unit tests, and device verification.

## Known limitations

- `SET_LIKED` is always authoritative locally. YouTube sync is best-effort and
  is only queued when the song row carries a library feedback token. Newly
  created rows from external metadata have no token, so they are reported as
  `localOnly = true`; a later full sync may reconcile them if the account can
  see the song, but this is not guaranteed. Check the result's `localOnly` flag
  instead of assuming a remote like.
- `SyncUtils` skips remote writes when the user is not logged in; the local
  liked state still changes.
- `GET_DOWNLOAD_STATE.progress` comes from Media3's `percentDownloaded`. It can
  be `-1` (unknown) early in a download, and jumps are normal because the
  content length is resolved lazily.
- `CANCEL_DOWNLOAD` removes an entry from the Media3 download index. It does not
  undo a like and does not delete the song row.
- `SET_LIKED` does not enqueue a download; Mingyue Live sends `DOWNLOAD`
  separately so it can choose its own policy.
- The fork still exposes `TOGGLE_LIKE` for compatibility. It operates on the
  current item and should not be used by new controllers.
