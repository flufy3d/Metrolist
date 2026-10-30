/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

internal const val PLAY_QUEUE_MAX_ITEMS = 200

/** One song of an external PLAY_QUEUE request. */
internal data class PlayQueueEntry(
    val songId: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val thumbnailUrl: String?,
)

/** A parsed PLAY_QUEUE request: an explicit ordered queue and a start index. */
internal data class PlayQueueRequest(
    val entries: List<PlayQueueEntry>,
    val startIndex: Int,
    val title: String?,
)

/**
 * Parses the parallel lists sent with PLAY_QUEUE. Invalid ids are dropped,
 * duplicates keep their first position, and at most [PLAY_QUEUE_MAX_ITEMS]
 * entries are kept. The start index follows the requested song when entries
 * before it were dropped. Returns null when nothing playable is left.
 */
internal fun parsePlayQueueRequest(
    songIds: List<String?>?,
    titles: List<String?>?,
    artists: List<String?>?,
    thumbnailUrls: List<String?>?,
    durationsMs: LongArray?,
    startIndex: Int,
    title: String?,
): PlayQueueRequest? {
    if (songIds.isNullOrEmpty()) return null
    val requestedStart = songIds.getOrNull(startIndex)?.trim()
    val seen = HashSet<String>()
    val entries = ArrayList<PlayQueueEntry>()
    for (index in songIds.indices) {
        if (entries.size >= PLAY_QUEUE_MAX_ITEMS) break
        val id = songIds[index]?.trim().orEmpty()
        if (!isValidMediaSongId(id) || !seen.add(id)) continue
        entries.add(
            PlayQueueEntry(
                songId = id,
                title = titles?.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() } ?: id,
                artist = artists?.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() },
                durationMs = (durationsMs?.getOrNull(index) ?: 0L).coerceAtLeast(0L),
                thumbnailUrl = thumbnailUrls?.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
    }
    if (entries.isEmpty()) return null
    val start = entries.indexOfFirst { it.songId == requestedStart }.takeIf { it >= 0 } ?: 0
    return PlayQueueRequest(
        entries = entries,
        startIndex = start,
        title = title?.trim()?.takeIf { it.isNotEmpty() },
    )
}
