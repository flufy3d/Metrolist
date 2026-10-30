/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayQueueCommandTest {
    @Test
    fun `keeps order and metadata`() {
        val request = parsePlayQueueRequest(
            songIds = listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"),
            titles = listOf("A", "B", "C"),
            artists = listOf("x", "", null),
            thumbnailUrls = listOf("https://t/a", null, ""),
            durationsMs = longArrayOf(1000L, -5L, 0L),
            startIndex = 1,
            title = " 流行 ",
        )!!
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), request.entries.map { it.songId })
        assertEquals(1, request.startIndex)
        assertEquals("流行", request.title)
        assertEquals("x", request.entries[0].artist)
        assertNull(request.entries[1].artist)
        assertEquals(0L, request.entries[1].durationMs)
        assertEquals("https://t/a", request.entries[0].thumbnailUrl)
        assertNull(request.entries[2].thumbnailUrl)
    }

    @Test
    fun `drops invalid and duplicate ids and keeps the start song`() {
        val request = parsePlayQueueRequest(
            songIds = listOf("bad/id", "aaaaaaaaaaa", "aaaaaaaaaaa", "bbbbbbbbbbb"),
            titles = null,
            artists = null,
            thumbnailUrls = null,
            durationsMs = null,
            startIndex = 3,
            title = null,
        )!!
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), request.entries.map { it.songId })
        assertEquals(1, request.startIndex)
        assertEquals("aaaaaaaaaaa", request.entries[0].title)
    }

    @Test
    fun `caps the queue size`() {
        val ids = List(250) { "song$it" }
        val request = parsePlayQueueRequest(ids, null, null, null, null, 0, null)!!
        assertEquals(PLAY_QUEUE_MAX_ITEMS, request.entries.size)
    }

    @Test
    fun `out of range start falls back to the first song`() {
        val request = parsePlayQueueRequest(listOf("aaaaaaaaaaa"), null, null, null, null, 9, null)!!
        assertEquals(0, request.startIndex)
    }

    @Test
    fun `empty request is rejected`() {
        assertNull(parsePlayQueueRequest(emptyList(), null, null, null, null, 0, null))
        assertNull(parsePlayQueueRequest(listOf("bad id"), null, null, null, null, 0, null))
    }
}
