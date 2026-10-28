/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

/**
 * A download command needs a media id and a title. The id is also used as the
 * Media3 download request id, so path separators and whitespace are rejected.
 */
internal fun isValidDownloadRequest(songId: String?, title: String?): Boolean =
    isValidMediaSongId(songId) && !title.isNullOrBlank()
