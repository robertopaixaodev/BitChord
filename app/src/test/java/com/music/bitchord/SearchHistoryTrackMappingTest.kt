package com.music.bitchord

import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.toSong
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SearchHistoryTrackMappingTest {

    @Test
    fun `track entity converts to Song with matching fields`() {
        val entity = SearchHistoryEntity(
            id = "vid_123",
            title = "Test Song",
            subtitle = "Test Artist",
            artworkUrl = "https://example.com/art.jpg",
            entityType = EntityType.TRACK,
        )

        val song = entity.toSong()

        assertNotNull(song)
        assertEquals("vid_123", song!!.videoId)
        assertEquals("Test Song", song.title)
        assertEquals("Test Artist", song.artist)
        assertEquals("https://example.com/art.jpg", song.thumbnailUrl)
    }

    @Test
    fun `non-track entities return null song`() {
        val album = SearchHistoryEntity(
            id = "album_123",
            title = "Test Album",
            subtitle = "Test Artist",
            artworkUrl = "https://example.com/album.jpg",
            entityType = EntityType.ALBUM,
        )
        val artist = album.copy(entityType = EntityType.ARTIST)
        val playlist = album.copy(entityType = EntityType.PLAYLIST)

        assertNull(album.toSong())
        assertNull(artist.toSong())
        assertNull(playlist.toSong())
    }

    @Test
    fun `identity survives json round trip`() {
        val json = Json { ignoreUnknownKeys = true }
        val entity = SearchHistoryEntity(
            id = "vid_identity",
            title = "Title",
            subtitle = "Artist",
            artworkUrl = null,
            entityType = EntityType.TRACK,
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

        val decoded = json.decodeFromString<SearchHistoryEntity>(json.encodeToString(entity))

        assertEquals(entity.isrc, decoded.isrc)
        assertEquals(entity.mbid, decoded.mbid)
        assertEquals(entity.trackNumber, decoded.trackNumber)
        assertEquals(entity.discNumber, decoded.discNumber)
        assertEquals(entity.albumArtist, decoded.albumArtist)
        assertEquals(entity.genre, decoded.genre)
        assertEquals(entity.year, decoded.year)
        assertEquals(entity.label, decoded.label)
        assertEquals(entity.composer, decoded.composer)
    }

    @Test
    fun `toSong copies identity from the entity`() {
        val entity = SearchHistoryEntity(
            id = "vid_identity",
            title = "Title",
            subtitle = "Artist",
            artworkUrl = null,
            entityType = EntityType.TRACK,
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

        val song = entity.toSong()!!

        assertEquals(entity.isrc, song.isrc)
        assertEquals(entity.mbid, song.mbid)
        assertEquals(entity.trackNumber, song.trackNumber)
        assertEquals(entity.discNumber, song.discNumber)
        assertEquals(entity.albumArtist, song.albumArtist)
        assertEquals(entity.genre, song.genre)
        assertEquals(entity.year, song.year)
        assertEquals(entity.label, song.label)
        assertEquals(entity.composer, song.composer)
    }

    @Test
    fun `missing identity survives as null`() {
        val json = Json { ignoreUnknownKeys = true }
        val entity = SearchHistoryEntity(
            id = "vid_empty",
            title = "Title",
            subtitle = "Artist",
            artworkUrl = null,
            entityType = EntityType.TRACK,
        )

        val decoded = json.decodeFromString<SearchHistoryEntity>(json.encodeToString(entity))

        assertNull(decoded.isrc)
        assertNull(decoded.mbid)
        assertNull(decoded.trackNumber)
        assertNull(decoded.discNumber)
        assertNull(decoded.albumArtist)
        assertNull(decoded.genre)
        assertNull(decoded.year)
        assertNull(decoded.label)
        assertNull(decoded.composer)
    }

    @Test
    fun `old json without identity decodes as null`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """
            {
              "id": "vid_old",
              "title": "Title",
              "subtitle": "Artist",
              "artworkUrl": null,
              "entityType": "TRACK"
            }
        """.trimIndent()

        val decoded = json.decodeFromString<SearchHistoryEntity>(old)

        assertNull(decoded.isrc)
        assertNull(decoded.mbid)
        assertNull(decoded.trackNumber)
        assertNull(decoded.discNumber)
        assertNull(decoded.albumArtist)
        assertNull(decoded.genre)
        assertNull(decoded.year)
        assertNull(decoded.label)
        assertNull(decoded.composer)
    }

    @Test
    fun `unknown future field does not break decoding`() {
        val json = Json { ignoreUnknownKeys = true }
        val future = """
            {
              "id": "vid_future",
              "title": "Title",
              "subtitle": "Artist",
              "artworkUrl": null,
              "entityType": "TRACK",
              "futureField": "value"
            }
        """.trimIndent()

        val decoded = json.decodeFromString<SearchHistoryEntity>(future)

        assertEquals("vid_future", decoded.id)
        assertNull(decoded.isrc)
    }

    @Test
    fun `raw query entries return null song`() {
        val query = SearchHistoryEntity(
            id = "q:some search",
            title = "some search",
            subtitle = "",
            artworkUrl = null,
            entityType = EntityType.TRACK,
        )

        assertNull(query.toSong())
    }
}
