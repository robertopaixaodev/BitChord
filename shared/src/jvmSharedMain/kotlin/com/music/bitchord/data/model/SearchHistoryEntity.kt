package com.music.bitchord.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An entity the user tapped in search results — a track, album, artist, or playlist.
 *
 * Unlike raw query strings, these carry the exact identity (id, artwork, type) of what was
 * clicked, so recents render instantly with real cover art and tapping one navigates directly
 * to that entity instead of re-running a text search.
 */
@Serializable
data class SearchHistoryEntity(
    /** Unique id: videoId for tracks, browseId for albums/artists/playlists. */
    val id: String,
    /** Display title — song name, album name, artist name, etc. */
    val title: String,
    /** Subtitle — e.g. "Song • Drake", "Album • Artist", or just the type label. */
    val subtitle: String,
    /** Artwork URL for the cover/thumbnail. */
    val artworkUrl: String?,
    /** The kind of entity this represents. */
    val entityType: EntityType,
    /** Timestamp (epoch millis) — updated on each re-tap to bubble it to the top. */
    val timestamp: Long = System.currentTimeMillis(),
    val isrc: String? = null,
    val mbid: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val label: String? = null,
    val composer: String? = null,
)

enum class EntityType {
    @SerialName("TRACK") TRACK,
    @SerialName("ALBUM") ALBUM,
    @SerialName("ARTIST") ARTIST,
    @SerialName("PLAYLIST") PLAYLIST,
}

/**
 * The track this entry plays, or null when it isn't one. A raw search term is
 * also stored as a TRACK (id "q:<term>") for backwards compatibility, and has
 * no video behind it.
 */
fun SearchHistoryEntity.toSong(): Song? = if (entityType == EntityType.TRACK && !id.startsWith("q:")) {
    Song(
        videoId = id,
        title = title,
        artist = subtitle,
        thumbnailUrl = artworkUrl,
        isrc = isrc,
        mbid = mbid,
        trackNumber = trackNumber,
        discNumber = discNumber,
        albumArtist = albumArtist,
        genre = genre,
        year = year,
        label = label,
        composer = composer,
    )
} else null
