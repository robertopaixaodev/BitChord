package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SongOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Locale

/**
 * A genre the server's library reports, for the Explore page.
 *
 * [name] is already display-ready; counts are shown as the secondary line.
 */
data class SourceGenre(
    val name: String,
    val songCount: Int? = null,
    val albumCount: Int? = null,
)

/** An artist the server's library reports, for Explore's fallback list. */
data class SourceArtist(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val thumbnailUrl: String? = null,
)

/** The full artist profile a library page needs. */
data class SourceArtistDetail(
    val name: String,
    val biography: String? = null,
    val imageUrl: String? = null,
    val albums: List<Album> = emptyList(),
    val songs: List<Song> = emptyList(),
)

/**
 * A Navidrome/Subsonic library.
 *
 * One search row is one `/rest/stream` URL: there is no pack to enumerate and
 * no codec negotiation beyond what the server says it holds. Rows are cached by
 * their Subsonic id so [stream] can carry the duration and suffix the search
 * already returned, exactly as the addon source does.
 */
class NavidromeSource(
    override val configId: String,
    private val config: NavidromeConfig,
    private val client: NavidromeClient = NavidromeClient(config),
) : MusicSource {

    override val kind: SourceKind = SourceKind.NAVIDROME
    override val displayName: String = config.displayName

    private val rows = Collections.synchronizedMap(
        object : LinkedHashMap<String, NavidromeSong>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, NavidromeSong>) = size > MAX_ROWS
        },
    )

    override suspend fun health(): SourceHealth {
        if (!config.isComplete) {
            return SourceHealth.Rejected("A Navidrome URL and username are required")
        }
        return client.health()
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank() || limit <= 0) return@withContext emptyList()
        try {
            client.searchSongs(query, limit).take(limit).map { row ->
                rows[row.id] = row
                row.toSong()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "Navidrome search failed for '$query': ${e.message}")
            emptyList()
        }
    }

    /** Genres the library reports, for Explore. Empty when the server has none. */
    suspend fun genres(): List<SourceGenre> = withContext(Dispatchers.IO) {
        try {
            client.genres().map { genre ->
                SourceGenre(
                    name = genre.displayName,
                    songCount = genre.songCount,
                    albumCount = genre.albumCount,
                )
            }.filter { it.name.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "Navidrome genre list failed: ${e.message}")
            emptyList()
        }
    }

    /** Artists the library reports, used when it has no genres to show. */
    suspend fun artists(limit: Int = 60): List<SourceArtist> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        try {
            client.artists().take(limit).map { artist ->
                SourceArtist(
                    id = artist.id,
                    name = artist.name,
                    albumCount = artist.albumCount,
                    thumbnailUrl = artist.coverArt?.let(client::coverUrl),
                )
            }.filter { it.name.isNotBlank() }
                .groupBy { it.name.lowercase() }
                .map { (_, sameName) ->
                    val first = sameName.first()
                    first.copy(
                        albumCount = sameName.mapNotNull { it.albumCount }
                            .takeIf { it.isNotEmpty() }
                            ?.sum(),
                    )
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "Navidrome artist list failed: ${e.message}")
            emptyList()
        }
    }

    /** Tracks tagged with [genre], in the server's own order. */
    suspend fun songsByGenre(genre: String, limit: Int = 100): List<Song> =
        withContext(Dispatchers.IO) {
            if (genre.isBlank() || limit <= 0) return@withContext emptyList()
            try {
                client.songsByGenre(genre, limit).take(limit).map(::remember)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackLog.w(TAG, "Navidrome songs by genre failed for '$genre': ${e.message}")
                emptyList()
            }
        }

    /**
     * The cover for an Explore genre: the artwork of the artist with the most
     * tracks in it. A library with one dominant artist therefore shows that
     * artist's sleeve rather than an arbitrary first track.
     */
    suspend fun genreCover(genre: String): String? = withContext(Dispatchers.IO) {
        if (genre.isBlank()) return@withContext null
        val songs = runCatching { client.songsByGenre(genre, 100) }.getOrDefault(emptyList())
        val dominant = songs
            .filter { !it.artist.isNullOrBlank() }
            .groupBy { it.artist.orEmpty().lowercase() }
            .maxByOrNull { it.value.size }
            ?.value
            ?.firstOrNull()
        dominant?.coverArt?.let(client::coverUrl)
    }

    /**
     * Tracks credited to [artist].
     *
     * Subsonic's search3 is the one endpoint that answers this in a single
     * request; the library is then filtered to rows whose artist credit matches
     * exactly, because a title match would otherwise leak unrelated tracks into
     * the artist's page. When the server credits the artist differently from
     * what getArtists reported, the unfiltered result is a better answer than an
     * empty page.
     */
    suspend fun songsByArtist(artist: String, limit: Int = 100): List<Song> =
        withContext(Dispatchers.IO) {
            if (artist.isBlank() || limit <= 0) return@withContext emptyList()
            try {
                val found = client.searchSongs(artist, limit * 2)
                val credited = found.filter { it.artist.equals(artist, ignoreCase = true) }
                (credited.ifEmpty { found }).take(limit).map(::remember)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackLog.w(TAG, "Navidrome songs by artist failed for '$artist': ${e.message}")
                emptyList()
            }
        }

    private fun remember(row: NavidromeSong): Song {
        rows[row.id] = row
        return row.toSong()
    }

    override suspend fun getRecentAlbums(limit: Int): List<Album> = albums("newest", limit)

    /**
     * Albums of a Subsonic album-list type (`newest`, `frequent`, `random`).
     * Used by Home's library shelves.
     */
    suspend fun albums(type: String, limit: Int): List<Album> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        try {
            client.albumsByType(type, limit).take(limit).map { row ->
                Album(
                    id = row.id,
                    title = row.name.ifBlank { "Unknown Album" },
                    artist = row.artist?.takeIf { it.isNotBlank() },
                    thumbnailUrl = row.coverArt?.let(client::coverUrl),
                    year = row.year,
                    songCount = row.songCount,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "Navidrome $type album list failed: ${e.message}")
            emptyList()
        }
    }

    /** Albums matching [query], for the Albums search tab. */
    suspend fun searchAlbums(query: String, limit: Int = 12): List<Album> =
        withContext(Dispatchers.IO) {
            if (query.isBlank() || limit <= 0) return@withContext emptyList()
            try {
                client.searchAlbums(query, limit).take(limit).map { row ->
                    Album(
                        id = row.id,
                        title = row.name.ifBlank { "Unknown Album" },
                        artist = row.artist?.takeIf { it.isNotBlank() },
                        thumbnailUrl = row.coverArt?.let(client::coverUrl),
                        year = row.year,
                        songCount = row.songCount,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackLog.w(TAG, "Navidrome album search failed for '$query': ${e.message}")
                emptyList()
            }
        }

    /** Artists matching [query], for the Artists search tab. */
    suspend fun searchArtists(query: String, limit: Int = 12): List<SourceArtist> =
        withContext(Dispatchers.IO) {
            if (query.isBlank() || limit <= 0) return@withContext emptyList()
            try {
                val direct = client.searchArtists(query, limit).map { artist ->
                    SourceArtist(
                        id = artist.id,
                        name = artist.name,
                        albumCount = artist.albumCount,
                        thumbnailUrl = artist.coverArt?.let(client::coverUrl),
                    )
                }.filter { it.name.isNotBlank() }
                    .groupBy { it.name.lowercase() }
                    .map { (_, sameName) ->
                        val first = sameName.first()
                        first.copy(
                            albumCount = sameName.mapNotNull { it.albumCount }
                                .takeIf { it.isNotEmpty() }
                                ?.sum(),
                        )
                    }
                if (direct.isNotEmpty()) return@withContext direct.take(limit)
                // `search3` often reports no artists for an album title (the
                // canonical "the Eminem show" case). The album it did return
                // carries the credit, so derive the artist from that instead of
                // showing an empty tab.
                client.searchAlbums(query, limit).mapNotNull { album ->
                    val name = album.artist?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    SourceArtist(
                        id = "",
                        name = name,
                        thumbnailUrl = album.coverArt?.let(client::coverUrl),
                    )
                }.distinctBy { it.name.lowercase() }.take(limit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackLog.w(TAG, "Navidrome artist search failed for '$query': ${e.message}")
                emptyList()
            }
        }

    /** Every track on [albumId], in the server's own order. */
    suspend fun albumSongs(albumId: String): List<Song> = withContext(Dispatchers.IO) {
        if (albumId.isBlank()) return@withContext emptyList()
        try {
            client.albumSongs(albumId).map(::remember)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "Navidrome album tracks failed for '$albumId': ${e.message}")
            emptyList()
        }
    }

    /**
     * The artist page's data: biography/image from `getArtistInfo2`, albums
     * from `getArtist`, and a popular list from the same catalogue search the
     * artist tab uses. Missing pieces are simply absent rather than failing the
     * whole page.
     */
    suspend fun artistDetail(
        artistId: String,
        name: String,
        songLimit: Int = 20,
    ): SourceArtistDetail = withContext(Dispatchers.IO) {
        val artist = runCatching { client.artistDetail(artistId) }.getOrNull()
        val info = runCatching { client.artistInfo(artistId) }.getOrNull()
        val albums = artist?.album.orEmpty().map { row ->
            Album(
                id = row.id,
                title = row.name.ifBlank { "Unknown Album" },
                artist = row.artist?.takeIf { it.isNotBlank() } ?: name,
                thumbnailUrl = row.coverArt?.let(client::coverUrl),
                year = row.year,
                songCount = row.songCount,
            )
        }.ifEmpty {
            searchAlbums(name, 50).filter { it.artist.equals(name, ignoreCase = true) }
        }
        val imageUrl = info?.largeImageUrl?.takeIf { it.isNotBlank() }
            ?: info?.mediumImageUrl?.takeIf { it.isNotBlank() }
            ?: info?.smallImageUrl?.takeIf { it.isNotBlank() }
            ?: artist?.coverArt?.let(client::coverUrl)
        SourceArtistDetail(
            name = artist?.name?.takeIf { it.isNotBlank() } ?: name,
            biography = info?.biography?.takeIf { it.isNotBlank() },
            imageUrl = imageUrl,
            albums = albums,
            songs = songsByArtist(name, songLimit),
        )
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        if (trackId.isBlank()) return null
        return try {
            val row = rows[trackId]
            SourceStream(
                url = client.streamUrl(trackId, request),
                format = row?.format() ?: StreamFormat(),
                durationSec = row?.duration,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "Navidrome stream URL failed for $trackId: ${e.message}")
            null
        }
    }

    private fun NavidromeSong.toSong(): Song {
        val suffix = suffix?.lowercase(Locale.ROOT)
        val lossless = suffix in LOSSLESS_SUFFIXES
        return Song(
            videoId = SourceRegistry.trackKey(configId, id),
            title = title.ifBlank { "Unknown Title" },
            artist = artist?.takeIf { it.isNotBlank() } ?: "Unknown Artist",
            albumName = album?.takeIf { it.isNotBlank() },
            durationText = duration?.let(::formatDuration),
            thumbnailUrl = coverArt?.let(client::coverUrl),
            trackNumber = track,
            discNumber = discNumber,
            year = year,
            genre = genre?.takeIf { it.isNotBlank() },
            isrc = isrc?.takeIf { it.isNotBlank() },
            mbid = musicBrainzId?.takeIf { it.isNotBlank() },
            sourceQuality = if (lossless) "LOSSLESS" else "HIGH",
            sourceOrigin = SongOrigin.NAVIDROME,
        )
    }

    private fun NavidromeSong.format(): StreamFormat {
        val codec = suffix?.lowercase(Locale.ROOT)?.let {
            when (it) {
                "aif", "aiff" -> "aiff"
                "m4a", "mp4" -> "alac"
                else -> it
            }
        }
        return StreamFormat(codec = codec, kbps = bitRate?.takeIf { it > 0 })
    }

    private companion object {
        const val TAG = "BitChord"
        const val MAX_ROWS = 512
        val LOSSLESS_SUFFIXES = setOf("flac", "wav", "aiff", "aif", "alac", "ape", "wv")

        fun formatDuration(seconds: Int): String =
            "${seconds / 60}:${"%02d".format(Locale.ROOT, seconds % 60)}"
    }
}
