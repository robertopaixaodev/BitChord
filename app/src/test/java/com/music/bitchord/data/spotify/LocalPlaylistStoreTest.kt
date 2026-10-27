package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistStoreTest {

    private fun populatedSong() = Song(
        videoId = "playlist-1",
        title = "Title",
        artist = "Artist",
        thumbnailUrl = null,
        isrc = "USRC17607839",
        mbid = "11111111-2222-3333-4444-555555555555",
        trackNumber = 7,
        discNumber = 2,
        albumArtist = "Album Artist",
        genre = "Jazz",
        year = 1999,
        label = "Label Records",
        composer = "Composer Name",
    )

    private fun assertIdentityEquals(expected: Song, actual: Song) {
        assertEquals(expected.isrc, actual.isrc)
        assertEquals(expected.mbid, actual.mbid)
        assertEquals(expected.trackNumber, actual.trackNumber)
        assertEquals(expected.discNumber, actual.discNumber)
        assertEquals(expected.albumArtist, actual.albumArtist)
        assertEquals(expected.genre, actual.genre)
        assertEquals(expected.year, actual.year)
        assertEquals(expected.label, actual.label)
        assertEquals(expected.composer, actual.composer)
    }

    @Test
    fun `codec round trip preserves musical identity`() {
        val original = populatedSong()

        val restored = LocalPlaylistSongCodec.decode(LocalPlaylistSongCodec.encode(original))

        assertIdentityEquals(original, restored)
    }

    @Test
    fun `codec round trip keeps missing identity null`() {
        val original = Song(
            videoId = "playlist-empty",
            title = "Title",
            artist = "Artist",
            thumbnailUrl = null,
        )

        val encoded = LocalPlaylistSongCodec.encode(original)
        val restored = LocalPlaylistSongCodec.decode(encoded)

        assertFalse(encoded.containsKey("isrc"))
        assertFalse(encoded.containsKey("trackNumber"))
        assertNull(restored.isrc)
        assertNull(restored.mbid)
        assertNull(restored.trackNumber)
        assertNull(restored.discNumber)
        assertNull(restored.albumArtist)
        assertNull(restored.genre)
        assertNull(restored.year)
        assertNull(restored.label)
        assertNull(restored.composer)
    }

    @Test
    fun `zero is a value and survives the round trip`() {
        val original = populatedSong().copy(
            trackNumber = 0,
            discNumber = 0,
            year = 0,
        )

        val encoded = LocalPlaylistSongCodec.encode(original)
        val restored = LocalPlaylistSongCodec.decode(encoded)

        assertTrue(encoded.containsKey("trackNumber"))
        assertTrue(encoded.containsKey("discNumber"))
        assertTrue(encoded.containsKey("year"))
        assertEquals(0, restored.trackNumber)
        assertEquals(0, restored.discNumber)
        assertEquals(0, restored.year)
    }

    @Test
    fun `old playlist json without identity decodes as null`() {
        val old = mapOf(
            "videoId" to "old",
            "title" to "Title",
            "artist" to "Artist",
            "isVideo" to false,
        )

        val restored = LocalPlaylistSongCodec.decode(old)

        assertNull(restored.isrc)
        assertNull(restored.mbid)
        assertNull(restored.trackNumber)
        assertNull(restored.discNumber)
        assertNull(restored.albumArtist)
        assertNull(restored.genre)
        assertNull(restored.year)
        assertNull(restored.label)
        assertNull(restored.composer)
    }
}
