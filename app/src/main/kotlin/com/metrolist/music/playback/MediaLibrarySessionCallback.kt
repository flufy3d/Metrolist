/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.offline.Download
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants.EXTRAS_KEY_IS_EXPLICIT
import androidx.media3.session.MediaConstants.EXTRAS_VALUE_ATTRIBUTE_PRESENT
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.music.R
import com.metrolist.music.constants.AndroidAutoSearchLocalLimitKey
import com.metrolist.music.constants.AutoDownloadOnLikeKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.MediaSessionConstants
import com.metrolist.music.constants.SongSortType
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.extensions.toggleRepeatMode
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.ArtistNameAliases
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.get
import com.metrolist.music.utils.getArtistSeparator
import com.metrolist.music.utils.joinToArtistString
import com.metrolist.music.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import timber.log.Timber
import java.time.LocalDateTime
import javax.inject.Inject
import com.metrolist.music.constants.AndroidAutoSectionsOrderKey
import com.metrolist.music.constants.AndroidAutoYouTubePlaylistsKey
import com.metrolist.music.constants.AutoRadioQueueKey
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.ui.screens.settings.AndroidAutoSection
import com.metrolist.music.ui.screens.settings.deserializeSections
import com.metrolist.music.ui.screens.settings.serializeSections
import kotlinx.coroutines.withContext

class MediaLibrarySessionCallback
@Inject
constructor(
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
    val downloadUtil: DownloadUtil,
    val syncUtils: SyncUtils,
) : MediaLibrarySession.Callback {
    private val scope = CoroutineScope(Dispatchers.Main) + Job()
    lateinit var service: MusicService
    var toggleLike: () -> Unit = {}
    var toggleStartRadio: () -> Unit = {}
    var toggleLibrary: () -> Unit = {}
    var addToTargetPlaylist: () -> Unit = {}
    var download: (Bundle) -> Boolean = { false }

    fun release() {
        scope.cancel()
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val connectionResult = super.onConnect(session, controller)
        return MediaSession.ConnectionResult.accept(
            connectionResult.availableSessionCommands
                .buildUpon()
                .add(MediaSessionConstants.CommandToggleLike)
                .add(MediaSessionConstants.CommandToggleStartRadio)
                .add(MediaSessionConstants.CommandToggleLibrary)
                .add(MediaSessionConstants.CommandToggleShuffle)
                .add(MediaSessionConstants.CommandToggleRepeatMode)
                .add(MediaSessionConstants.CommandAddToTargetPlaylist)
                .add(MediaSessionConstants.CommandDownload)
                .add(MediaSessionConstants.CommandSetLiked)
                .add(MediaSessionConstants.CommandGetLikedState)
                .add(MediaSessionConstants.CommandGetDownloadState)
                .add(MediaSessionConstants.CommandCancelDownload)
                .add(MediaSessionConstants.CommandSetAutoDownload)
                .build(),
            connectionResult.availablePlayerCommands,
        )
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        when (customCommand.customAction) {
            MediaSessionConstants.ACTION_TOGGLE_LIKE -> toggleLike()
            MediaSessionConstants.ACTION_TOGGLE_START_RADIO -> toggleStartRadio()
            MediaSessionConstants.ACTION_TOGGLE_LIBRARY -> toggleLibrary()
            MediaSessionConstants.ACTION_TOGGLE_SHUFFLE -> session.player.shuffleModeEnabled =
                !session.player.shuffleModeEnabled

            MediaSessionConstants.ACTION_TOGGLE_REPEAT_MODE -> session.player.toggleRepeatMode()
            MediaSessionConstants.ACTION_ADD_TO_TARGET_PLAYLIST -> addToTargetPlaylist()
            MediaSessionConstants.ACTION_DOWNLOAD -> {
                if (!download(args)) {
                    return Futures.immediateFuture(
                        SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE),
                    )
                }
            }

            MediaSessionConstants.ACTION_SET_LIKED ->
                return scope.future(Dispatchers.IO) { handleSetLiked(args) }

            MediaSessionConstants.ACTION_GET_LIKED_STATE ->
                return scope.future(Dispatchers.IO) { handleGetLikedState(args) }

            MediaSessionConstants.ACTION_GET_DOWNLOAD_STATE ->
                return scope.future(Dispatchers.IO) { handleGetDownloadState(args) }

            MediaSessionConstants.ACTION_CANCEL_DOWNLOAD ->
                return scope.future(Dispatchers.IO) { handleCancelDownload(args) }

            MediaSessionConstants.ACTION_SET_AUTO_DOWNLOAD ->
                return scope.future(Dispatchers.IO) { handleSetAutoDownload(args) }
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    /**
     * Explicitly set the Metrolist liked flag for any song, without requiring it
     * to be the current player item and without changing the queue.
     */
    private suspend fun handleSetLiked(args: Bundle): SessionResult {
        val request = parseSetLikedRequest(
            songId = args.getString(MediaSessionConstants.KEY_SONG_ID),
            likedProvided = args.containsKey(MediaSessionConstants.KEY_LIKED),
            liked = args.getBoolean(MediaSessionConstants.KEY_LIKED, false),
            title = args.getString(MediaSessionConstants.KEY_TITLE),
            artist = args.getString(MediaSessionConstants.KEY_ARTIST),
            durationMs = args.getLong(MediaSessionConstants.KEY_DURATION_MS, 0L),
            thumbnailUrl = args.getString(MediaSessionConstants.KEY_THUMBNAIL_URL),
            albumId = args.getString(MediaSessionConstants.KEY_ALBUM_ID),
            albumTitle = args.getString(MediaSessionConstants.KEY_ALBUM_TITLE),
            explicit = args.getBoolean(MediaSessionConstants.KEY_EXPLICIT, false),
        ) ?: return SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)

        return try {
            val existing = database.getSongByIdBlocking(request.songId)
            if (requiresTitleForLikedWrite(existing != null, request.title)) {
                return SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
            }

            val now = LocalDateTime.now()
            val stored: SongEntity = if (existing == null) {
                database.insert(request.toMediaMetadata(now))
                database.songEntity(request.songId)
                    ?: return SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
            } else {
                existing.song
            }
            val updated = stored.applyLikedState(request.liked, now)
            if (updated != stored) {
                database.update(updated)
            }

            // YouTube writes need feedback tokens from a real YouTube song. If
            // they are absent this is a local-only change and the result says so.
            val syncQueued = updated.libraryAddToken != null || updated.libraryRemoveToken != null
            if (syncQueued) {
                syncUtils.likeSong(updated)
            }

            withContext(Dispatchers.Main) {
                if (::service.isInitialized) {
                    service.refreshLikedStateForCurrentSong(request.songId)
                }
            }

            SessionResult(
                SessionResult.RESULT_SUCCESS,
                Bundle().apply {
                    putString(MediaSessionConstants.KEY_SONG_ID, request.songId)
                    putBoolean(MediaSessionConstants.KEY_LIKED, updated.liked)
                    putBoolean(MediaSessionConstants.KEY_LOCAL_ONLY, !syncQueued)
                },
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to set liked state for ${request.songId}")
            SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
        }
    }

    private suspend fun handleGetLikedState(args: Bundle): SessionResult {
        val songId = args.getString(MediaSessionConstants.KEY_SONG_ID)?.trim().orEmpty()
        if (!isValidMediaSongId(songId)) {
            return SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
        }

        return try {
            val song = database.songEntity(songId)
            SessionResult(
                SessionResult.RESULT_SUCCESS,
                Bundle().apply {
                    putString(MediaSessionConstants.KEY_SONG_ID, songId)
                    putBoolean(MediaSessionConstants.KEY_EXISTS, song != null)
                    putBoolean(MediaSessionConstants.KEY_LIKED, song?.liked == true)
                },
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to read liked state for $songId")
            SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
        }
    }

    /**
     * Single-song download lookup: reads one DownloadIndex row and one song row
     * instead of scanning the whole download list.
     */
    private suspend fun handleGetDownloadState(args: Bundle): SessionResult {
        val songId = args.getString(MediaSessionConstants.KEY_SONG_ID)?.trim().orEmpty()
        if (!isValidMediaSongId(songId)) {
            return SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
        }

        return try {
            val song = database.songEntity(songId)
            val download = downloadUtil.downloadManager.downloadIndex.getDownload(songId)
            val snapshot = downloadStateSnapshot(
                dbDownloaded = song?.isDownloaded == true,
                downloadState = download?.state,
                percentDownloaded = download?.percentDownloaded ?: -1f,
                failureReason = download?.failureReason,
            )

            SessionResult(
                SessionResult.RESULT_SUCCESS,
                Bundle().apply {
                    putString(MediaSessionConstants.KEY_SONG_ID, songId)
                    putString(MediaSessionConstants.KEY_STATE, snapshot.state)
                    putInt(MediaSessionConstants.KEY_PROGRESS, snapshot.progress)
                    putBoolean(MediaSessionConstants.KEY_CACHED, snapshot.cached)
                    snapshot.error?.let { putString(MediaSessionConstants.KEY_ERROR, it) }
                },
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to read download state for $songId")
            SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
        }
    }

    private fun handleCancelDownload(args: Bundle): SessionResult {
        val songId = args.getString(MediaSessionConstants.KEY_SONG_ID)?.trim().orEmpty()
        if (!isValidMediaSongId(songId)) {
            return SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
        }

        return try {
            val exists = downloadUtil.downloadManager.downloadIndex.getDownload(songId) != null
            if (exists) {
                downloadUtil.downloadManager.removeDownload(songId)
            }
            SessionResult(
                SessionResult.RESULT_SUCCESS,
                Bundle().apply {
                    putString(MediaSessionConstants.KEY_SONG_ID, songId)
                    putBoolean(MediaSessionConstants.KEY_REMOVED, exists)
                },
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to cancel download for $songId")
            SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
        }
    }

    private suspend fun handleSetAutoDownload(args: Bundle): SessionResult {
        if (!args.containsKey(MediaSessionConstants.KEY_ENABLED)) {
            return SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
        }
        val enabled = args.getBoolean(MediaSessionConstants.KEY_ENABLED)

        return try {
            context.dataStore.edit { it[AutoDownloadOnLikeKey] = enabled }
            SessionResult(
                SessionResult.RESULT_SUCCESS,
                Bundle().apply {
                    putBoolean(MediaSessionConstants.KEY_ENABLED, enabled)
                },
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to update auto-download setting")
            SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
        }
    }

    private fun SetLikedRequest.toMediaMetadata(
        now: LocalDateTime,
    ) = com.metrolist.music.models.MediaMetadata(
        id = songId,
        title = requireNotNull(title),
        artists = listOf(
            com.metrolist.music.models.MediaMetadata.Artist(
                id = null,
                name = artist ?: "Unknown",
            ),
        ),
        duration = (durationMs / 1000L).toInt().coerceAtLeast(0),
        thumbnailUrl = thumbnailUrl,
        album = albumId?.let {
            com.metrolist.music.models.MediaMetadata.Album(
                id = it,
                title = albumTitle.orEmpty(),
            )
        },
        explicit = explicit,
        liked = liked,
        likedDate = if (liked) now else null,
        inLibrary = if (liked) now else null,
    )

    /**
     * Mirrors the stock toggle: liking also puts the song in the library,
     * unliking leaves library membership alone. Re-applying the same liked value
     * is idempotent and keeps the original liked date.
     */
    private fun SongEntity.applyLikedState(
        liked: Boolean,
        now: LocalDateTime,
    ) = copy(
        liked = liked,
        likedDate = if (liked) likedDate ?: now else null,
        inLibrary = if (liked) inLibrary ?: now else inLibrary,
    )

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaItemsWithStartPosition> =
        Futures.immediateFuture(
            MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET),
        )

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(
            LibraryResult.ofItem(
                MediaItem
                    .Builder()
                    .setMediaId(MusicService.ROOT)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setIsPlayable(false)
                            .setIsBrowsable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build(),
                    ).build(),
                params,
            ),
        )

    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> =
        Futures.immediateFuture(
            if (isBrowsableMediaId(parentId)) {
                LibraryResult.ofVoid(params)
            } else {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
            },
        )

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
        scope.future(Dispatchers.IO) {
            try {
                loadLocalChildren(parentId, page, pageSize)?.let { children ->
                    return@future LibraryResult.ofItemList(children, params)
                }

                val children = when (parentId) {
                    MusicService.ROOT -> {
                        val sectionsRaw = context.dataStore.get(
                            AndroidAutoSectionsOrderKey,
                            serializeSections(AndroidAutoSection.values().map { it to true })
                        )
                        val sections = deserializeSections(sectionsRaw)
                        val showYoutubePlaylists = context.dataStore.get(AndroidAutoYouTubePlaylistsKey, false)
                        val rootItems = sections
                            .filter { (_, enabled) -> enabled }
                            .ifEmpty { listOf(AndroidAutoSection.LIKED to true) }
                            .map { (section, _) ->
                                when (section) {
                                    AndroidAutoSection.LIKED -> browsableMediaItem(
                                        "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}",
                                        context.getString(R.string.liked_songs),
                                        null,
                                        drawableUri(R.drawable.favorite),
                                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    )
                                   AndroidAutoSection.SONGS -> browsableMediaItem(
                                        MusicService.SONG,
                                        context.getString(R.string.songs),
                                        null,
                                        drawableUri(R.drawable.music_note),
                                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    )
                                    AndroidAutoSection.ARTISTS -> browsableMediaItem(
                                        MusicService.ARTIST,
                                        context.getString(R.string.artists),
                                        null,
                                        drawableUri(R.drawable.artist),
                                        MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
                                    )
                                    AndroidAutoSection.ALBUMS -> browsableMediaItem(
                                        MusicService.ALBUM,
                                        context.getString(R.string.albums),
                                        null,
                                        drawableUri(R.drawable.album),
                                        MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
                                    )
                                    AndroidAutoSection.PLAYLISTS -> browsableMediaItem(
                                        MusicService.PLAYLIST,
                                        context.getString(R.string.playlists),
                                        null,
                                        drawableUri(R.drawable.queue_music),
                                        MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                                    )
                                }
                            }
                        if (showYoutubePlaylists) {
                            rootItems + browsableMediaItem(
                                MusicService.YOUTUBE_PLAYLIST,
                                context.getString(R.string.mixes),
                                null,
                                drawableUri(R.drawable.explore_outlined),
                                MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                            )
                        } else {
                            rootItems
                        }
                    }
                    MusicService.YOUTUBE_PLAYLIST -> {
                        if (!context.dataStore.get(AndroidAutoYouTubePlaylistsKey, false)) {
                            emptyList()
                        } else {
                            try {
                                val allSections = mutableListOf<com.metrolist.innertube.pages.HomePage.Section>()
                                var continuation: String? = null
                                val maxPages = 4

                                for (page in 0 until maxPages) {
                                    val result = YouTube.home(continuation)
                                        .onFailure { reportException(it) }
                                        .getOrNull() ?: break
                                    allSections.addAll(result.sections)
                                    continuation = result.continuation
                                    if (continuation == null) break
                                }

                                // Drop playlists already saved to the local library,
                                // which are exposed under MusicService.PLAYLIST.
                                val savedBrowseIds = database.bookmarkedPlaylistBrowseIds().toSet()

                                val playlists = allSections
                                    .flatMap { it.items }
                                    .filterIsInstance<PlaylistItem>()
                                    .filterNot { it.id in savedBrowseIds }
                                    .distinctBy { it.id }

                                playlists.map { playlist ->
                                    browsableMediaItem(
                                        "${MusicService.YOUTUBE_PLAYLIST}/${playlist.id}",
                                        playlist.title,
                                        playlist.author?.name,
                                        playlist.thumbnail?.toUri(),
                                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    )
                                }
                            } catch (e: Exception) {
                                reportException(e)
                                emptyList()
                            }
                        }
                    }

                    else ->
                        when {
                            parentId.startsWith("${MusicService.YOUTUBE_PLAYLIST}/") -> {
                                val playlistId = parentId.removePrefix("${MusicService.YOUTUBE_PLAYLIST}/")
                                try {
                                    val songs = YouTube.playlist(playlistId).getOrNull()?.songs
                                        ?.take(100)
                                        ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                        ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                                        ?: emptyList()

                                    // Add shuffle item at the top
                                    listOf(
                                        MediaItem.Builder()
                                            .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                                            .setMediaMetadata(
                                                MediaMetadata.Builder()
                                                    .setTitle(context.getString(R.string.shuffle))
                                                    .setArtworkUri(drawableUri(R.drawable.shuffle))
                                                    .setIsPlayable(true)
                                                    .setIsBrowsable(false)
                                                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                                    .build()
                                            ).build()
                                    ) + songs.map { songItem ->
                                        MediaItem.Builder()
                                            .setMediaId("$parentId/${songItem.id}")
                                            .setMediaMetadata(
                                                MediaMetadata.Builder()
                                                    .setTitle(songItem.title)
                                                    .setExtras(Bundle().apply {
                                                        if (songItem.explicit) putLong(EXTRAS_KEY_IS_EXPLICIT, EXTRAS_VALUE_ATTRIBUTE_PRESENT)
                                                    })
                                                    .setSubtitle(songItem.artists.joinToArtistString(getArtistSeparator(context)) {
                                                        ArtistNameAliases.resolve(it.id, it.name)
                                                    })
                                                    .setArtist(songItem.artists.joinToArtistString(getArtistSeparator(context)) {
                                                        ArtistNameAliases.resolve(it.id, it.name)
                                                    })
                                                    .setArtworkUri(songItem.thumbnail.toUri())
                                                    .setIsPlayable(true)
                                                    .setIsBrowsable(false)
                                                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                                    .build()
                                            )
                                            .build()
                                    }
                                } catch (e: Exception) {
                                    reportException(e)
                                    emptyList()
                                }
                            }

                            else -> emptyList()
                        }
                }
                LibraryResult.ofItemList(children.paginate(page, pageSize), params)
            } catch (e: Exception) {
                reportException(e)
                LibraryResult.ofItemList(emptyList(), params)
            }
        }

    private suspend fun loadLocalChildren(
        parentId: String,
        page: Int,
        pageSize: Int,
    ): List<MediaItem>? {
        val request = androidAutoPageRequest(page, pageSize)
        return when {
            parentId == MusicService.ARTIST ->
                database.artistsByCreateDateAsc(request.limit, request.offset).map { artist ->
                    browsableMediaItem(
                        "${MusicService.ARTIST}/${artist.id}",
                        ArtistNameAliases.resolve(artist.id, artist.artist.name),
                        context.resources.getQuantityString(
                            R.plurals.n_song,
                            artist.songCount,
                            artist.songCount,
                        ),
                        artist.artist.thumbnailUrl?.toUri(),
                        MediaMetadata.MEDIA_TYPE_ARTIST,
                    )
                }

            parentId == MusicService.ALBUM ->
                database.albumsByCreateDateAsc(request.limit, request.offset).map { album ->
                    browsableMediaItem(
                        "${MusicService.ALBUM}/${album.id}",
                        album.album.title,
                        album.artists.joinToString {
                            ArtistNameAliases.resolve(it.id, it.name)
                        },
                        album.album.thumbnailUrl?.toUri(),
                        MediaMetadata.MEDIA_TYPE_ALBUM,
                    )
                }

            parentId == MusicService.PLAYLIST ->
                loadPlaylistContainers(request)

            parentId == MusicService.SONG ->
                database.songsByCreateDateAsc(request.limit, request.offset)
                    .map { it.toMediaItem(parentId) }

            parentId.startsWith("${MusicService.ARTIST}/") ->
                database.artistSongsByCreateDateAsc(
                    parentId.removePrefix("${MusicService.ARTIST}/"),
                    request.limit,
                    request.offset,
                ).map { it.toMediaItem(parentId) }

            parentId.startsWith("${MusicService.ALBUM}/") ->
                database.albumSongs(
                    parentId.removePrefix("${MusicService.ALBUM}/"),
                    request.limit,
                    request.offset,
                ).map { it.toMediaItem(parentId) }

            parentId.startsWith("${MusicService.PLAYLIST}/") ->
                loadPlaylistChildren(parentId, request)

            else -> null
        }
    }

    private suspend fun loadPlaylistContainers(request: AndroidAutoPageRequest): List<MediaItem> {
        val builtInItems =
            if (request.offset < 2 && request.limit > 0) {
                val likedSongCount = database.likedSongsCount().first()
                val downloadedSongCount = downloadUtil.downloads.value.size
                listOf(
                    browsableMediaItem(
                        "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}",
                        context.getString(R.string.liked_songs),
                        context.resources.getQuantityString(R.plurals.n_song, likedSongCount, likedSongCount),
                        drawableUri(R.drawable.favorite),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    ),
                    browsableMediaItem(
                        "${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}",
                        context.getString(R.string.downloaded_songs),
                        context.resources.getQuantityString(
                            R.plurals.n_song,
                            downloadedSongCount,
                            downloadedSongCount,
                        ),
                        drawableUri(R.drawable.download),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST,
                    ),
                ).drop(request.offset).take(request.limit)
            } else {
                emptyList()
            }
        val playlistRequest = request.afterLeadingItems(2)
        val playlists =
            if (playlistRequest.limit == 0) {
                emptyList()
            } else {
                database.playlistsByCreateDateAsc(playlistRequest.limit, playlistRequest.offset)
            }
        return builtInItems + playlists.map { playlist ->
            browsableMediaItem(
                "${MusicService.PLAYLIST}/${playlist.id}",
                playlist.playlist.name,
                context.resources.getQuantityString(R.plurals.n_song, playlist.songCount, playlist.songCount),
                playlist.thumbnails.firstOrNull()?.toUri(),
                MediaMetadata.MEDIA_TYPE_PLAYLIST,
            )
        }
    }

    private suspend fun loadPlaylistChildren(
        parentId: String,
        request: AndroidAutoPageRequest,
    ): List<MediaItem> {
        val playlistId = parentId.removePrefix("${MusicService.PLAYLIST}/")
        val includeShuffle = request.offset == 0 && request.limit > 0
        val songRequest = request.afterLeadingItems(1)
        val songs = when {
            songRequest.limit == 0 -> emptyList()
            playlistId == PlaylistEntity.LIKED_PLAYLIST_ID ->
                database.likedSongsByCreateDateDesc(songRequest.limit, songRequest.offset)

            playlistId == PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> {
                val downloads = downloadUtil.downloads.value
                val completedSongIds = downloads.entries
                    .asSequence()
                    .filter { it.value.state == Download.STATE_COMPLETED }
                    .sortedBy { it.value.updateTimeMs }
                    .map { it.key }
                    .toList()
                val existingSongIds = completedSongIds
                    .chunked(MAX_ANDROID_AUTO_PAGE_SIZE)
                    .flatMapTo(mutableSetOf()) { database.existingSongIds(it) }
                val songIds = completedSongIds.asSequence()
                    .filter(existingSongIds::contains)
                    .drop(songRequest.offset)
                    .take(songRequest.limit)
                    .toList()
                val songsById = database.getSongsByIds(songIds).associateBy { it.id }
                songIds.mapNotNull(songsById::get)
            }

            else ->
                database.playlistSongs(playlistId, songRequest.limit, songRequest.offset)
                    .map { it.song }
        }

        return buildList {
            if (includeShuffle) {
                add(
                    MediaItem.Builder()
                        .setMediaId("$parentId/${MusicService.SHUFFLE_ACTION}")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(context.getString(R.string.shuffle))
                                .setArtworkUri(drawableUri(R.drawable.shuffle))
                                .setIsPlayable(true)
                                .setIsBrowsable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .build()
                        ).build()
                )
            }
            addAll(songs.map { it.toMediaItem(parentId) })
        }
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future(Dispatchers.IO) {
            try {
                database.song(mediaId).first()?.toMediaItem()?.let {
                    LibraryResult.ofItem(it, null)
                } ?: LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
            } catch (e: Exception) {
                reportException(e)
                LibraryResult.ofError(SessionError.ERROR_UNKNOWN)
            }
        }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        session.notifySearchResultChanged(browser, query, 1, params)
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return scope.future(Dispatchers.IO) {
            if (query.isEmpty()) {
                return@future LibraryResult.ofItemList(emptyList(), params)
            }

            try {
                val searchResults = mutableListOf<MediaItem>()
                val limit = context.dataStore.get(AndroidAutoSearchLocalLimitKey, 75)

                val allLocalSongs = database.searchSongsExtended(query, limit).first()
                allLocalSongs.forEach { song ->
                    searchResults.add(song.toMediaItem(
                        path = "${MusicService.SEARCH}/$query",
                        isPlayable = true,
                        isBrowsable = false,
                    ))
                }

                try {
                    val onlineResults = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG)
                        .getOrNull()
                        ?.items
                        ?.filterIsInstance<SongItem>()
                        ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                        ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                        ?.filter { onlineSong ->
                            !allLocalSongs.any { localSong ->
                                localSong.id == onlineSong.id ||
                                (localSong.song.title.equals(onlineSong.title, ignoreCase = true) &&
                                 localSong.artists.any { artist ->
                                     onlineSong.artists.any {
                                         it.name.equals(artist.name, ignoreCase = true)
                                     }
                                 })
                            }
                        } ?: emptyList()

                    onlineResults.forEach { songItem ->
                        try {
                            database.query { insert(songItem.toMediaMetadata()) }
                        } catch (e: Exception) {
                        }
                        
                        searchResults.add(
                            MediaItem.Builder()
                                .setMediaId("${MusicService.SEARCH}/$query/${songItem.id}")
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(songItem.title)
                                        .setExtras(Bundle().apply {
                                            if (songItem.explicit) putLong(EXTRAS_KEY_IS_EXPLICIT, EXTRAS_VALUE_ATTRIBUTE_PRESENT)
                                        })
                                        .setSubtitle(songItem.artists.joinToArtistString(getArtistSeparator(context)) {
                                            ArtistNameAliases.resolve(it.id, it.name)
                                        })
                                        .setArtist(songItem.artists.joinToArtistString(getArtistSeparator(context)) {
                                            ArtistNameAliases.resolve(it.id, it.name)
                                        })
                                        .setArtworkUri(songItem.thumbnail.toUri())
                                        .setIsPlayable(true)
                                        .setIsBrowsable(false)
                                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                        .build()
                                )
                                .build()
                        )
                    }
                } catch (e: Exception) {
                    reportException(e)
                }
                
                // Media3 rejects any page whose item count exceeds pageSize, so
                // hand back only the slice the browser asked for. Search results
                // combine local songs and YouTube hits in one ranked list.
                LibraryResult.ofItemList(searchResults.paginate(page, pageSize), params)

            } catch (e: Exception) {
                reportException(e)
                LibraryResult.ofItemList(emptyList(), params)
            }
        }
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> =
        scope.future(Dispatchers.IO) {
            val defaultResult = MediaItemsWithStartPosition(emptyList(), startIndex, startPositionMs)
            val voiceQuery = mediaItems.firstOrNull()?.requestMetadata?.searchQuery

            val path = if (!voiceQuery.isNullOrBlank()) {
                listOf(MusicService.SEARCH, voiceQuery, "")
            } else {
                mediaItems.firstOrNull()?.mediaId?.split("/")
            } ?: return@future defaultResult

            when (path.firstOrNull()) {
                MusicService.SONG -> {
                    val songId = path.getOrNull(1) ?: return@future defaultResult
                    val allSongs = database.songsByCreateDateAsc().first()
                    MediaItemsWithStartPosition(
                        allSongs.map { it.toMediaItem() },
                        allSongs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0,
                        startPositionMs
                    )
                }

                MusicService.ARTIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val artistId = path.getOrNull(1) ?: return@future defaultResult
                    val songs = database.artistSongsByCreateDateAsc(artistId).first()
                    MediaItemsWithStartPosition(
                        songs.map { it.toMediaItem() },
                        songs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0,
                        startPositionMs
                    )
                }

                MusicService.ALBUM -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val albumId = path.getOrNull(1) ?: return@future defaultResult
                    val albumWithSongs = database.albumWithSongs(albumId).first() ?: return@future defaultResult
                    MediaItemsWithStartPosition(
                        albumWithSongs.songs.map { it.toMediaItem() },
                        albumWithSongs.songs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0,
                        startPositionMs
                    )
                }

                MusicService.PLAYLIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val playlistId = path.getOrNull(1) ?: return@future defaultResult
                    val songs = when (playlistId) {
                        PlaylistEntity.LIKED_PLAYLIST_ID -> database.likedSongs(SongSortType.CREATE_DATE, descending = true)
                        PlaylistEntity.DOWNLOADED_PLAYLIST_ID -> {
                            val downloads = downloadUtil.downloads.value
                            database
                                .allSongs()
                                .flowOn(Dispatchers.IO)
                                .map { songs ->
                                    songs.filter {
                                        downloads[it.id]?.state == Download.STATE_COMPLETED
                                    }
                                }.map { songs ->
                                    songs
                                        .map { it to downloads[it.id] }
                                        .sortedBy { it.second?.updateTimeMs ?: 0L }
                                        .map { it.first }
                                }
                        }
                        else -> database.playlistSongs(playlistId).map { list ->
                            list.map { it.song }
                        }
                    }.first()

                    // Check if this is a shuffle action
                    if (songId == MusicService.SHUFFLE_ACTION) {
                        MediaItemsWithStartPosition(
                            songs.shuffled().map { it.toMediaItem() },
                            0,
                            C.TIME_UNSET
                        )
                    } else {
                        MediaItemsWithStartPosition(
                            songs.map { it.toMediaItem() },
                            songs.indexOfFirst { it.id == songId }.takeIf { it != -1 } ?: 0,
                            startPositionMs
                        )
                    }
                }

                MusicService.YOUTUBE_PLAYLIST -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val playlistId = path.getOrNull(1) ?: return@future defaultResult

                    val songs = try {
                        YouTube.playlist(playlistId).getOrNull()?.songs?.map {
                            it.toMediaItem()
                        } ?: emptyList()
                    } catch (e: Exception) {
                        reportException(e)
                        return@future defaultResult
                    }

                    // Check if this is a shuffle action
                    if (songId == MusicService.SHUFFLE_ACTION) {
                        MediaItemsWithStartPosition(
                            songs.shuffled(),
                            0,
                            C.TIME_UNSET
                        )
                    } else {
                        MediaItemsWithStartPosition(
                            songs,
                            songs.indexOfFirst { it.mediaId.endsWith(songId) }.takeIf { it != -1 } ?: 0,
                            C.TIME_UNSET
                        )
                    }
                }

                MusicService.SEARCH -> {
                    val songId = path.getOrNull(2) ?: return@future defaultResult
                    val searchQuery = path.getOrNull(1) ?: return@future defaultResult

                    val isVoiceSearch = songId.isBlank() && searchQuery.isNotBlank()

                    if (isVoiceSearch) {
                        //Search if the voiceQuery is about a local playlist and play only the songs in that playlist
                        val localPlaylists = database.searchPlaylists(searchQuery).first()
                        val exactLocalPlaylist = localPlaylists.firstOrNull {
                            it.playlist.name.equals(searchQuery, ignoreCase = true)
                        }
                        if (exactLocalPlaylist != null) {
                            val playlistSongs = database.playlistSongs(exactLocalPlaylist.playlist.id).first()
                            if (playlistSongs.isNotEmpty()) {
                                return@future MediaItemsWithStartPosition(
                                    playlistSongs.map { it.song.toMediaItem() },
                                    0,
                                    C.TIME_UNSET
                                )
                            }
                        }
                    }

                    val searchResults = mutableListOf<Song>()
                    val limit = context.dataStore.get(AndroidAutoSearchLocalLimitKey, 75)

                    val allLocalSongs = database.searchSongsExtended(searchQuery, limit).first()
                    searchResults.addAll(allLocalSongs)
                    if (!isVoiceSearch && songId.isNotBlank() && searchResults.indexOfFirst { it.id == songId } == -1) {
                        database.song(songId).first()?.let { searchResults.add(it) }
                    }
                    
                    try {
                        val onlineResults = YouTube.search(searchQuery, YouTube.SearchFilter.FILTER_SONG)
                            .getOrNull()
                            ?.items
                            ?.filterIsInstance<SongItem>()
                            ?.filterExplicit(context.dataStore.get(HideExplicitKey, false))
                            ?.filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                            ?.filter { onlineSong ->
                                !allLocalSongs.any { localSong ->
                                    localSong.id == onlineSong.id ||
                                    (localSong.song.title.equals(onlineSong.title, ignoreCase = true) &&
                                     localSong.artists.any { artist ->
                                         onlineSong.artists.any {
                                             it.name.equals(artist.name, ignoreCase = true)
                                         }
                                     })
                                }
                            } ?: emptyList()

                        onlineResults.forEach { songItem ->
                            try {
                                database.query { insert(songItem.toMediaMetadata()) }
                                database.song(songItem.id).first()?.let { newSong ->
                                    searchResults.add(newSong)
                                }
                            } catch (e: Exception) {
                            }
                        }
                    } catch (e: Exception) {
                        reportException(e)
                    }
                    
                    if (searchResults.isEmpty()) {
                        return@future defaultResult
                    }

                    val selectedSong =
                        if (isVoiceSearch) {    //Check if the voiceQuery is about a specific song
                            val snapshot: List<Song> =
                                synchronized(searchResults) { searchResults.toList() }
                            VoiceSearchMatcher.findBest(searchQuery, snapshot)
                        } else {
                            searchResults.firstOrNull { it.id == songId }
                        }

                    if(context.dataStore.get(AutoRadioQueueKey, true)) {
                        val radioQueue = YouTubeQueue.radio(selectedSong?.toMediaMetadata() ?: return@future defaultResult)
                        val radioStatus = runCatching {
                            withContext(Dispatchers.IO) {
                                radioQueue
                                    .getInitialStatus()
                                    .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                                    .filterVideoSongs(context.dataStore.get(HideVideoSongsKey, false))
                            }
                        }.getOrNull()

                        if (radioStatus != null && radioStatus.items.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                service.adoptQueue(radioQueue, radioStatus.title, radioStatus.items.size) //Used to make the radio queue load more songs when near the end
                            }
                            return@future MediaItemsWithStartPosition(
                                radioStatus.items,
                                radioStatus.items.indexOfFirst { it.mediaId == selectedSong.id }.coerceAtLeast(0),
                                C.TIME_UNSET,
                            )
                        }
                    }

                    val items = listOf(selectedSong?.toMediaItem() ?: return@future defaultResult)
                    withContext(Dispatchers.Main) {
                        service.adoptQueue(
                            ListQueue(
                                title = selectedSong.song.title,
                                items = items,
                            ),
                            title = selectedSong.song.title,
                        )
                    }
                    MediaItemsWithStartPosition(
                        items,
                        0,
                        C.TIME_UNSET
                    )
                }

                else -> defaultResult
            }
        }

    private fun drawableUri(
        @DrawableRes id: Int,
    ) = Uri
        .Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.resources.getResourcePackageName(id))
        .appendPath(context.resources.getResourceTypeName(id))
        .appendPath(context.resources.getResourceEntryName(id))
        .build()

    private fun browsableMediaItem(
        id: String,
        title: String,
        subtitle: String?,
        iconUri: Uri?,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_MUSIC,
    ) = MediaItem
        .Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtist(subtitle)
                .setArtworkUri(iconUri)
                .setIsPlayable(false)
                .setIsBrowsable(true)
                .setMediaType(mediaType)
                .build(),
        ).build()

    private fun Song.toMediaItem(path: String, isPlayable: Boolean = true, isBrowsable: Boolean = false): MediaItem {
        return MediaItem
            .Builder()
            .setMediaId("$path/$id")
            .setMediaMetadata(
                 MediaMetadata
                     .Builder()
                     .setTitle(song.title)
                     .setExtras(Bundle().apply {
                         if (song.explicit) putLong(EXTRAS_KEY_IS_EXPLICIT, EXTRAS_VALUE_ATTRIBUTE_PRESENT)
                     })
                     .setSubtitle(artists.joinToArtistString(getArtistSeparator(context)) {
                         ArtistNameAliases.resolve(it.id, it.name)
                     })
                     .setArtist(artists.joinToArtistString(getArtistSeparator(context)) {
                         ArtistNameAliases.resolve(it.id, it.name)
                     })
                     .setArtworkUri(song.thumbnailUrl?.toUri())
                    .setIsPlayable(isPlayable)
                    .setIsBrowsable(isBrowsable)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            ).build()
    }
}

internal fun isBrowsableMediaId(mediaId: String): Boolean =
    mediaId == MusicService.ROOT ||
        mediaId == MusicService.SONG ||
        mediaId == MusicService.ARTIST ||
        mediaId == MusicService.ALBUM ||
        mediaId == MusicService.PLAYLIST ||
        mediaId == MusicService.YOUTUBE_PLAYLIST ||
        mediaId.startsWith("${MusicService.ARTIST}/") ||
        mediaId.startsWith("${MusicService.ALBUM}/") ||
        mediaId.startsWith("${MusicService.PLAYLIST}/") ||
        mediaId.startsWith("${MusicService.YOUTUBE_PLAYLIST}/")

internal fun <T> List<T>.paginate(
    page: Int,
    pageSize: Int,
): List<T> {
    val safePage = page.coerceAtLeast(0)
    val safePageSize = pageSize.coerceAtLeast(0)
    val fromIndex = (safePage.toLong() * safePageSize).coerceAtMost(size.toLong()).toInt()
    val toIndex = (fromIndex.toLong() + safePageSize).coerceAtMost(size.toLong()).toInt()
    return subList(fromIndex, toIndex)
}

internal const val MAX_ANDROID_AUTO_PAGE_SIZE = 500

private const val TAG = "MediaLibrarySession"

internal data class AndroidAutoPageRequest(
    val offset: Int,
    val limit: Int,
) {
    fun afterLeadingItems(count: Int): AndroidAutoPageRequest {
        val leadingItemsOnPage = (count - offset).coerceIn(0, limit)
        return AndroidAutoPageRequest(
            offset = (offset - count).coerceAtLeast(0),
            limit = limit - leadingItemsOnPage,
        )
    }
}

internal fun androidAutoPageRequest(
    page: Int,
    pageSize: Int,
): AndroidAutoPageRequest {
    val safePage = page.coerceAtLeast(0)
    val safePageSize = pageSize.coerceAtLeast(0)
    val effectivePageSize = safePageSize.coerceAtMost(MAX_ANDROID_AUTO_PAGE_SIZE)
    return AndroidAutoPageRequest(
        offset = (safePage.toLong() * effectivePageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        limit = effectivePageSize,
    )
}
