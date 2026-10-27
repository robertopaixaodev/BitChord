package com.music.bitchord.data.stats

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.YearMonth

class ListeningStatsTest {

    private lateinit var directory: File
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("listening-stats-test").toFile()
        ListeningStats.initForTesting(directory)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun populatedSong() = Song(
        videoId = "listening-1",
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

    private fun storedBucket(): StoredBucket {
        val file = File(directory, "${YearMonth.now()}.json")
        return json.decodeFromString(StoredBucket.serializer(), file.readText())
    }

    private fun assertIdentityEquals(expected: Song, actual: TrackEntry) {
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
    fun `record round trip preserves musical identity`() = runBlocking {
        val original = populatedSong()

        ListeningStats.record(original, playedMs = 1_000L, countsAsPlay = true)
        ListeningStats.flushForTesting()

        val stored = storedBucket()
        assertEquals(1, stored.version)
        assertIdentityEquals(original, stored.tracks.single { it.id == original.videoId })
    }

    @Test
    fun `record round trip keeps missing identity null`() = runBlocking {
        val original = Song(
            videoId = "listening-empty",
            title = "Title",
            artist = "Artist",
            thumbnailUrl = null,
        )

        ListeningStats.record(original, playedMs = 1_000L, countsAsPlay = true)
        ListeningStats.flushForTesting()

        val entry = storedBucket().tracks.single { it.id == original.videoId }
        assertNull(entry.isrc)
        assertNull(entry.mbid)
        assertNull(entry.trackNumber)
        assertNull(entry.discNumber)
        assertNull(entry.albumArtist)
        assertNull(entry.genre)
        assertNull(entry.year)
        assertNull(entry.label)
        assertNull(entry.composer)
    }

    @Test
    fun `old bucket json without identity decodes as null`() {
        val month = YearMonth.now().toString()
        val old = """
            {
              "version": 1,
              "month": "$month",
              "tracks": [
                {"id": "old", "title": "Title", "artist": "Artist"}
              ]
            }
        """.trimIndent()

        val entry = json.decodeFromString(StoredBucket.serializer(), old).tracks.single()

        assertNull(entry.isrc)
        assertNull(entry.mbid)
        assertNull(entry.trackNumber)
        assertNull(entry.discNumber)
        assertNull(entry.albumArtist)
        assertNull(entry.genre)
        assertNull(entry.year)
        assertNull(entry.label)
        assertNull(entry.composer)
    }

    @Test
    fun `absorb keeps the first non-null identity value`() {
        val identified = TrackEntry(
            id = "same",
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
        val later = TrackEntry(id = "same")

        later.absorb(identified)

        assertEquals("USRC17607839", later.isrc)
        assertEquals("11111111-2222-3333-4444-555555555555", later.mbid)
        assertEquals(7, later.trackNumber)
        assertEquals(2, later.discNumber)
        assertEquals("Album Artist", later.albumArtist)
        assertEquals("Jazz", later.genre)
        assertEquals(1999, later.year)
        assertEquals("Label Records", later.label)
        assertEquals("Composer Name", later.composer)
    }
}
