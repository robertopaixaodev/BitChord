package com.music.bitchord.playback

/**
 * Single source of truth for the nine musical-identity extras carried on a
 * queue item. PlayerConnection writes and reads them, and EnrichmentQueueUpdater
 * updates them in place; both must use the same keys or enrichment silently
 * disappears between the player and persistence.
 */
internal object SongExtraKeys {
    const val ISRC = "bitchord.isrc"
    const val MBID = "bitchord.mbid"
    const val TRACK_NUMBER = "bitchord.trackNumber"
    const val DISC_NUMBER = "bitchord.discNumber"
    const val ALBUM_ARTIST = "bitchord.albumArtist"
    const val GENRE = "bitchord.genre"
    const val YEAR = "bitchord.year"
    const val LABEL = "bitchord.label"
    const val COMPOSER = "bitchord.composer"
}
