/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.constants

import android.os.Bundle
import androidx.media3.session.SessionCommand

object MediaSessionConstants {
    const val ACTION_TOGGLE_LIBRARY = "TOGGLE_LIBRARY"
    const val ACTION_TOGGLE_START_RADIO = "TOGGLE_START_RADIO"
    const val ACTION_TOGGLE_LIKE = "TOGGLE_LIKE"
    const val ACTION_TOGGLE_SHUFFLE = "TOGGLE_SHUFFLE"
    const val ACTION_TOGGLE_REPEAT_MODE = "TOGGLE_REPEAT_MODE"
    const val ACTION_ADD_TO_TARGET_PLAYLIST = "ADD_TO_TARGET_PLAYLIST"
    const val ACTION_DOWNLOAD = "DOWNLOAD"
    const val ACTION_SET_LIKED = "SET_LIKED"
    const val ACTION_GET_LIKED_STATE = "GET_LIKED_STATE"
    const val ACTION_GET_DOWNLOAD_STATE = "GET_DOWNLOAD_STATE"
    const val ACTION_CANCEL_DOWNLOAD = "CANCEL_DOWNLOAD"
    const val ACTION_SET_AUTO_DOWNLOAD = "SET_AUTO_DOWNLOAD"
    const val ACTION_PLAY_QUEUE = "PLAY_QUEUE"

    const val KEY_SONG_ID = "songId"
    const val KEY_TITLE = "title"
    const val KEY_ARTIST = "artist"
    const val KEY_DURATION_MS = "durationMs"
    const val KEY_THUMBNAIL_URL = "thumbnailUrl"
    const val KEY_ALBUM_ID = "albumId"
    const val KEY_ALBUM_TITLE = "albumTitle"
    const val KEY_EXPLICIT = "explicit"
    const val KEY_LIKED = "liked"
    const val KEY_ENABLED = "enabled"

    const val KEY_STATE = "state"
    const val KEY_PROGRESS = "progress"
    const val KEY_CACHED = "cached"
    const val KEY_ERROR = "error"
    const val KEY_EXISTS = "exists"
    const val KEY_REMOVED = "removed"
    const val KEY_LOCAL_ONLY = "localOnly"
    const val KEY_SONG_IDS = "songIds"
    const val KEY_TITLES = "titles"
    const val KEY_ARTISTS = "artists"
    const val KEY_THUMBNAIL_URLS = "thumbnailUrls"
    const val KEY_DURATIONS_MS = "durationsMs"
    const val KEY_START_INDEX = "startIndex"
    const val KEY_QUEUE_TITLE = "queueTitle"
    const val KEY_COUNT = "count"

    val CommandToggleLibrary = SessionCommand(ACTION_TOGGLE_LIBRARY, Bundle.EMPTY)
    val CommandToggleLike = SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY)
    val CommandToggleStartRadio = SessionCommand(ACTION_TOGGLE_START_RADIO, Bundle.EMPTY)
    val CommandToggleShuffle = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
    val CommandToggleRepeatMode = SessionCommand(ACTION_TOGGLE_REPEAT_MODE, Bundle.EMPTY)
    val CommandAddToTargetPlaylist = SessionCommand(ACTION_ADD_TO_TARGET_PLAYLIST, Bundle.EMPTY)
    val CommandDownload = SessionCommand(ACTION_DOWNLOAD, Bundle.EMPTY)
    val CommandSetLiked = SessionCommand(ACTION_SET_LIKED, Bundle.EMPTY)
    val CommandGetLikedState = SessionCommand(ACTION_GET_LIKED_STATE, Bundle.EMPTY)
    val CommandGetDownloadState = SessionCommand(ACTION_GET_DOWNLOAD_STATE, Bundle.EMPTY)
    val CommandCancelDownload = SessionCommand(ACTION_CANCEL_DOWNLOAD, Bundle.EMPTY)
    val CommandSetAutoDownload = SessionCommand(ACTION_SET_AUTO_DOWNLOAD, Bundle.EMPTY)
    val CommandPlayQueue = SessionCommand(ACTION_PLAY_QUEUE, Bundle.EMPTY)

    const val TARGET_PLAYLIST_AUTO = "auto"
}
