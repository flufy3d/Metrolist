/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibrarySessionCallbackTest {
    @Test
    fun `browse tree parents accept subscriptions`() {
        assertTrue(isBrowsableMediaId(MusicService.ROOT))
        assertTrue(isBrowsableMediaId(MusicService.ARTIST))
        assertTrue(isBrowsableMediaId("${MusicService.ARTIST}/artist-id"))
        assertTrue(isBrowsableMediaId("${MusicService.PLAYLIST}/playlist-id"))
        assertFalse(isBrowsableMediaId("unknown"))
        assertFalse(isBrowsableMediaId("${MusicService.SONG}/song-id"))
    }

    @Test
    fun `children are limited to the requested page`() {
        val children = listOf("one", "two", "three", "four", "five")

        assertEquals(listOf("one", "two"), children.paginate(page = 0, pageSize = 2))
        assertEquals(listOf("three", "four"), children.paginate(page = 1, pageSize = 2))
        assertEquals(listOf("five"), children.paginate(page = 2, pageSize = 2))
        assertEquals(emptyList<String>(), children.paginate(page = 3, pageSize = 2))
    }

    @Test
    fun `pagination never returns more than one page`() {
        val results = List(82) { "song-$it" }

        assertEquals(30, results.paginate(page = 0, pageSize = 30).size)
        assertEquals("song-30", results.paginate(page = 1, pageSize = 30).first())
        assertEquals(22, results.paginate(page = 2, pageSize = 30).size)
        assertEquals(30, results.paginate(page = -1, pageSize = 30).size)
    }

    @Test
    fun `database pages are capped before loading relations`() {
        assertEquals(
            AndroidAutoPageRequest(offset = 0, limit = MAX_ANDROID_AUTO_PAGE_SIZE),
            androidAutoPageRequest(page = 0, pageSize = Int.MAX_VALUE),
        )
        assertEquals(
            AndroidAutoPageRequest(offset = MAX_ANDROID_AUTO_PAGE_SIZE, limit = MAX_ANDROID_AUTO_PAGE_SIZE),
            androidAutoPageRequest(page = 1, pageSize = 1_000),
        )
        assertEquals(
            AndroidAutoPageRequest(offset = 1_000, limit = MAX_ANDROID_AUTO_PAGE_SIZE),
            androidAutoPageRequest(page = 2, pageSize = 500),
        )
    }

    @Test
    fun `playlist pages account for the leading shuffle action`() {
        assertEquals(
            AndroidAutoPageRequest(offset = 0, limit = 99),
            androidAutoPageRequest(page = 0, pageSize = 100).afterLeadingItems(1),
        )
        assertEquals(
            AndroidAutoPageRequest(offset = 99, limit = 100),
            androidAutoPageRequest(page = 1, pageSize = 100).afterLeadingItems(1),
        )
        assertEquals(
            AndroidAutoPageRequest(offset = 499, limit = MAX_ANDROID_AUTO_PAGE_SIZE),
            androidAutoPageRequest(page = 1, pageSize = 1_000).afterLeadingItems(1),
        )
    }

    @Test
    fun `playlist containers account for both built in playlists`() {
        assertEquals(
            AndroidAutoPageRequest(offset = 0, limit = 0),
            androidAutoPageRequest(page = 0, pageSize = 1).afterLeadingItems(2),
        )
        assertEquals(
            AndroidAutoPageRequest(offset = 0, limit = 0),
            androidAutoPageRequest(page = 1, pageSize = 1).afterLeadingItems(2),
        )
        assertEquals(
            AndroidAutoPageRequest(offset = 0, limit = 1),
            androidAutoPageRequest(page = 2, pageSize = 1).afterLeadingItems(2),
        )
    }

    @Test
    fun `download command requires a safe media id and a title`() {
        assertTrue(isValidDownloadRequest("dQw4w9WgXcQ", "Never Gonna Give You Up"))
        assertTrue(isValidDownloadRequest("  dQw4w9WgXcQ  ", "  Title  "))
        assertFalse(isValidDownloadRequest(null, "Title"))
        assertFalse(isValidDownloadRequest("", "Title"))
        assertFalse(isValidDownloadRequest("a/b", "Title"))
        assertFalse(isValidDownloadRequest("a b", "Title"))
        assertFalse(isValidDownloadRequest("dQw4w9WgXcQ", null))
        assertFalse(isValidDownloadRequest("dQw4w9WgXcQ", "   "))
    }

    @Test
    fun `set liked requires a safe id and an explicit liked flag`() {
        val parsed = parseSetLikedRequest(
            songId = "  dQw4w9WgXcQ  ",
            likedProvided = true,
            liked = true,
            title = "  Never Gonna Give You Up  ",
            artist = "  Rick Astley  ",
            durationMs = 213_000L,
            thumbnailUrl = "  https://example.com/a.jpg  ",
            albumId = "  album-id  ",
            albumTitle = "  Whenever You Need Somebody  ",
            explicit = false,
        )
        assertNotNull(parsed)
        assertEquals("dQw4w9WgXcQ", parsed?.songId)
        assertEquals("Never Gonna Give You Up", parsed?.title)
        assertEquals("Rick Astley", parsed?.artist)
        assertEquals(213_000L, parsed?.durationMs)
        assertEquals("https://example.com/a.jpg", parsed?.thumbnailUrl)
        assertEquals("album-id", parsed?.albumId)
        assertEquals("Whenever You Need Somebody", parsed?.albumTitle)

        assertNull(
            parseSetLikedRequest(
                songId = "dQw4w9WgXcQ",
                likedProvided = false,
                liked = false,
                title = "Title",
                artist = null,
                durationMs = 0L,
                thumbnailUrl = null,
                albumId = null,
                albumTitle = null,
                explicit = false,
            ),
        )
        assertNull(
            parseSetLikedRequest(
                songId = "a/b",
                likedProvided = true,
                liked = true,
                title = "Title",
                artist = null,
                durationMs = 0L,
                thumbnailUrl = null,
                albumId = null,
                albumTitle = null,
                explicit = false,
            ),
        )
        assertNull(
            parseSetLikedRequest(
                songId = "",
                likedProvided = true,
                liked = true,
                title = "Title",
                artist = null,
                durationMs = 0L,
                thumbnailUrl = null,
                albumId = null,
                albumTitle = null,
                explicit = false,
            ),
        )
    }

    @Test
    fun `new liked songs need a title but existing songs do not`() {
        assertTrue(requiresTitleForLikedWrite(songExists = false, title = null))
        assertTrue(requiresTitleForLikedWrite(songExists = false, title = "   "))
        assertFalse(requiresTitleForLikedWrite(songExists = false, title = "Title"))
        assertFalse(requiresTitleForLikedWrite(songExists = true, title = null))
    }

    @Test
    fun `download states map to stable external states`() {
        assertEquals(
            DownloadStateSnapshot(DOWNLOAD_STATE_NOT_FOUND, progress = -1, cached = false),
            downloadStateSnapshot(dbDownloaded = false, downloadState = null, percentDownloaded = -1f),
        )
        assertEquals(
            DownloadStateSnapshot(DOWNLOAD_STATE_COMPLETED, progress = 100, cached = true),
            downloadStateSnapshot(dbDownloaded = true, downloadState = null, percentDownloaded = -1f),
        )
        assertEquals(
            DownloadStateSnapshot(DOWNLOAD_STATE_QUEUED, progress = 0, cached = false),
            downloadStateSnapshot(dbDownloaded = false, downloadState = Download.STATE_QUEUED, percentDownloaded = 0f),
        )
        assertEquals(
            DownloadStateSnapshot(DOWNLOAD_STATE_DOWNLOADING, progress = 42, cached = false),
            downloadStateSnapshot(
                dbDownloaded = false,
                downloadState = Download.STATE_DOWNLOADING,
                percentDownloaded = 42.4f,
            ),
        )
        assertEquals(
            DownloadStateSnapshot(DOWNLOAD_STATE_DOWNLOADING, progress = -1, cached = false),
            downloadStateSnapshot(
                dbDownloaded = false,
                downloadState = Download.STATE_DOWNLOADING,
                percentDownloaded = -1f,
            ),
        )
        assertEquals(
            DownloadStateSnapshot(DOWNLOAD_STATE_COMPLETED, progress = 100, cached = true),
            downloadStateSnapshot(
                dbDownloaded = false,
                downloadState = Download.STATE_COMPLETED,
                percentDownloaded = 100f,
            ),
        )
        assertEquals(
            DownloadStateSnapshot(
                DOWNLOAD_STATE_FAILED,
                progress = -1,
                cached = true,
                error = "failure_reason_3",
            ),
            downloadStateSnapshot(
                dbDownloaded = true,
                downloadState = Download.STATE_FAILED,
                percentDownloaded = -1f,
                failureReason = 3,
            ),
        )
        assertEquals(
            DownloadStateSnapshot(DOWNLOAD_STATE_REMOVED, progress = -1, cached = false),
            downloadStateSnapshot(dbDownloaded = false, downloadState = Download.STATE_STOPPED, percentDownloaded = -1f),
        )
        assertEquals(
            DownloadStateSnapshot(DOWNLOAD_STATE_REMOVED, progress = -1, cached = false),
            downloadStateSnapshot(dbDownloaded = false, downloadState = Download.STATE_REMOVING, percentDownloaded = -1f),
        )
    }

    @Test
    fun `new commands reject invalid song ids`() {
        assertTrue(isValidMediaSongId("dQw4w9WgXcQ"))
        assertTrue(isValidMediaSongId("  dQw4w9WgXcQ  "))
        assertFalse(isValidMediaSongId(null))
        assertFalse(isValidMediaSongId(""))
        assertFalse(isValidMediaSongId("a/b"))
        assertFalse(isValidMediaSongId("a b"))
        assertFalse(isValidMediaSongId("x".repeat(129)))
    }
}
