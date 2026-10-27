package com.music.bitchord.data.enrichment

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrichmentProvidersTest {

    private fun song(isrc: String? = null) = Song(
        videoId = "provider-song",
        title = "Title",
        artist = "Artist",
        thumbnailUrl = null,
        isrc = isrc,
    )

    @Test
    fun `itunes maps track genre and year`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"results":[{
                      "trackName":"Title",
                      "artistName":"Artist",
                      "trackNumber":3,
                      "primaryGenreName":"Rock",
                      "releaseDate":"2001-06-01T00:00:00Z"
                    }]}
                    """.trimIndent(),
                ),
            )
            val provider = ItunesProvider(baseUrl = server.url("").toString().trimEnd('/'))

            val result = provider.enrich(song())

            assertNotNull(result)
            assertEquals(3, result!!.trackNumber)
            assertEquals("Rock", result.genre)
            assertEquals(2001, result.year)
            assertNull(result.isrc)
            assertTrue(server.takeRequest().path.orEmpty().startsWith("/search"))
        }
    }

    @Test
    fun `musicbrainz returns isrc and mbid with a descriptive user agent`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"recordings":[{
                      "id":"mbid-1",
                      "title":"Title",
                      "isrcs":["USRC17607839"],
                      "artist-credit":[{"name":"Artist"}]
                    }]}
                    """.trimIndent(),
                ),
            )
            val provider = MusicBrainzProvider(
                baseUrl = server.url("").toString().trimEnd('/'),
                userAgent = "BitChord/test (https://example.test)",
            )

            val result = provider.enrich(song())

            assertNotNull(result)
            assertEquals("USRC17607839", result!!.isrc)
            assertEquals("mbid-1", result.mbid)
            val request = server.takeRequest()
            assertTrue(request.path.orEmpty().startsWith("/recording"))
            assertTrue(request.getHeader("User-Agent").orEmpty().contains("BitChord/test"))
        }
    }

    @Test
    fun `musicbrainz 429 returns null without throwing`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(429))
            val provider = MusicBrainzProvider(baseUrl = server.url("").toString().trimEnd('/'))

            val result = provider.enrich(song())

            assertNull(result)
        }
    }

    @Test
    fun `deezer uses the isrc and fetches release credits`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"data":[{
                      "id":10,
                      "title":"Title",
                      "isrc":"USRC17607839",
                      "track_position":3,
                      "disk_number":2,
                      "composer":"Composer Name",
                      "release_date":"1999-05-01",
                      "album":{"id":42}
                    }]}
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"id":42,
                     "label":"Label Records",
                     "release_date":"1999-05-01",
                     "artist":{"name":"Album Artist"},
                     "genres":{"data":[{"name":"Jazz"}]}}
                    """.trimIndent(),
                ),
            )
            val provider = DeezerProvider(baseUrl = server.url("").toString().trimEnd('/'))

            val result = provider.enrich(song(isrc = "USRC17607839"))

            assertNotNull(result)
            assertEquals(3, result!!.trackNumber)
            assertEquals(2, result.discNumber)
            assertEquals("Composer Name", result.composer)
            assertEquals("Label Records", result.label)
            assertEquals("Album Artist", result.albumArtist)
            assertEquals("Jazz", result.genre)
            assertEquals(1999, result.year)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `musicbrainz rate gate spaces requests`() = runBlocking {
        val gate = RequestGate(100L)
        gate.await()
        val started = System.currentTimeMillis()

        gate.await()

        assertTrue(System.currentTimeMillis() - started >= 90L)
    }

    @Test
    fun `deezer does nothing without an isrc`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val provider = DeezerProvider(baseUrl = server.url("").toString().trimEnd('/'))

            val result = provider.enrich(song(isrc = null))

            assertNull(result)
            assertEquals(0, server.requestCount)
        }
    }
}
