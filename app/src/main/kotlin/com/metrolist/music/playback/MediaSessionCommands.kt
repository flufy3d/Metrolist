/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.exoplayer.offline.Download
import kotlin.math.roundToInt

internal val MEDIA_SONG_ID = Regex("^[A-Za-z0-9_-]{1,128}$")

internal fun isValidMediaSongId(songId: String?): Boolean =
    MEDIA_SONG_ID.matches(songId?.trim().orEmpty())

/**
 * A parsed SET_LIKED request. [title] is optional because an existing database
 * row already has one; [requiresTitleForLikedWrite] decides whether it is
 * actually needed.
 */
internal data class SetLikedRequest(
    val songId: String,
    val liked: Boolean,
    val title: String?,
    val artist: String?,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val albumId: String?,
    val albumTitle: String?,
    val explicit: Boolean,
)

internal fun parseSetLikedRequest(
    songId: String?,
    likedProvided: Boolean,
    liked: Boolean,
    title: String?,
    artist: String?,
    durationMs: Long,
    thumbnailUrl: String?,
    albumId: String?,
    albumTitle: String?,
    explicit: Boolean,
): SetLikedRequest? {
    val id = songId?.trim().orEmpty()
    if (!likedProvided || !isValidMediaSongId(id)) return null

    return SetLikedRequest(
        songId = id,
        liked = liked,
        title = title?.trim()?.takeIf { it.isNotEmpty() },
        artist = artist?.trim()?.takeIf { it.isNotEmpty() },
        durationMs = durationMs.coerceAtLeast(0L),
        thumbnailUrl = thumbnailUrl?.trim()?.takeIf { it.isNotEmpty() },
        albumId = albumId?.trim()?.takeIf { it.isNotEmpty() },
        albumTitle = albumTitle?.trim()?.takeIf { it.isNotEmpty() },
        explicit = explicit,
    )
}

/**
 * A song that is not in the database yet has to carry a title so Metrolist can
 * create a usable row. Existing rows only need the id and the liked flag.
 */
internal fun requiresTitleForLikedWrite(
    songExists: Boolean,
    title: String?,
): Boolean = !songExists && title.isNullOrBlank()

internal const val DOWNLOAD_STATE_NOT_FOUND = "not_found"
internal const val DOWNLOAD_STATE_QUEUED = "queued"
internal const val DOWNLOAD_STATE_DOWNLOADING = "downloading"
internal const val DOWNLOAD_STATE_COMPLETED = "completed"
internal const val DOWNLOAD_STATE_FAILED = "failed"
internal const val DOWNLOAD_STATE_REMOVED = "removed"

internal data class DownloadStateSnapshot(
    val state: String,
    val progress: Int,
    val cached: Boolean,
    val error: String? = null,
)

/**
 * Maps a Media3 [Download] state to the stable strings used by external
 * controllers. [downloadState] is null when the download index has no entry for
 * the song. The function is pure so every branch can be unit tested without a
 * DownloadManager.
 */
internal fun downloadStateSnapshot(
    dbDownloaded: Boolean,
    downloadState: Int?,
    percentDownloaded: Float,
    failureReason: Int? = null,
): DownloadStateSnapshot {
    fun completed() = DownloadStateSnapshot(
        state = DOWNLOAD_STATE_COMPLETED,
        progress = 100,
        cached = true,
    )

    return when (downloadState) {
        null -> if (dbDownloaded) {
            completed()
        } else {
            DownloadStateSnapshot(DOWNLOAD_STATE_NOT_FOUND, progress = -1, cached = false)
        }

        Download.STATE_COMPLETED -> completed()
        Download.STATE_DOWNLOADING -> DownloadStateSnapshot(
            state = DOWNLOAD_STATE_DOWNLOADING,
            progress = downloadProgress(percentDownloaded),
            cached = dbDownloaded,
        )

        Download.STATE_QUEUED -> DownloadStateSnapshot(
            state = DOWNLOAD_STATE_QUEUED,
            progress = 0,
            cached = false,
        )

        Download.STATE_FAILED -> DownloadStateSnapshot(
            state = DOWNLOAD_STATE_FAILED,
            progress = -1,
            cached = dbDownloaded,
            error = failureReason?.takeIf { it != 0 }?.let { "failure_reason_$it" },
        )

        Download.STATE_STOPPED,
        Download.STATE_REMOVING,
        -> DownloadStateSnapshot(
            state = DOWNLOAD_STATE_REMOVED,
            progress = -1,
            cached = false,
        )

        else -> DownloadStateSnapshot(DOWNLOAD_STATE_NOT_FOUND, progress = -1, cached = false)
    }
}

private fun downloadProgress(percentDownloaded: Float): Int =
    if (percentDownloaded.isNaN() || percentDownloaded < 0f) {
        -1
    } else {
        percentDownloaded.roundToInt().coerceIn(0, 100)
    }
