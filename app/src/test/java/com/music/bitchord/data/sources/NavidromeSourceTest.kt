package com.music.bitchord.data.sources

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import com.music.bitchord.data.model.SongOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NavidromeSourceTest {

    private fun config(server: MockWebServer) = NavidromeConfig(
        baseUrl = server.url("").toString().trimEnd('/'),
        username = "listener",
        password = "secret",
    )

    @Test
    fun `search maps Subsonic rows into Song identity`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"subsonic-response":{"status":"ok","version":"1.16.1",
                      "searchResult3":{"song":[{
                        "id":"song-1","title":"Title","artist":"Artist",
                        "album":"Album","duration":185,"track":3,
                        "discNumber":1,"year":2001,"genre":"Rock",
                        "isrc":"USRC17607839","musicBrainzId":"mbid-1",
                        "coverArt":"cover-1","suffix":"flac","bitRate":1411
                      }]}}}
                    """.trimIndent(),
                ),
            )
            val config = config(server)
            val source = NavidromeSource(config.configId, config)

            val songs = source.search("Title", limit = 10)
            val song = songs.single()

            assertEquals(SourceRegistry.trackKey(config.configId, "song-1"), song.videoId)
            assertEquals("Title", song.title)
            assertEquals("Artist", song.artist)
            assertEquals("Album", song.albumName)
            assertEquals("3:05", song.durationText)
            assertEquals(3, song.trackNumber)
            assertEquals(1, song.discNumber)
            assertEquals(2001, song.year)
            assertEquals("Rock", song.genre)
            assertEquals("USRC17607839", song.isrc)
            assertEquals("mbid-1", song.mbid)
            assertEquals("LOSSLESS", song.sourceQuality)
            assertEquals(SongOrigin.NAVIDROME, song.sourceOrigin)
            assertNotNull(song.thumbnailUrl)
        }
    }

    @Test
    fun `recent albums map to Album rows with cover urls`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"subsonic-response":{"status":"ok","version":"1.16.1",
                      "albumList2":{"album":[
                        {"id":"album-1","name":"Newest","artist":"Artist",
                         "coverArt":"cover-1","songCount":9,"year":2024}
                      ]}}}
                    """.trimIndent(),
                ),
            )
            val config = config(server)
            val source = NavidromeSource(config.configId, config)

            val albums = source.getRecentAlbums(10)

            val album = albums.single()
            assertEquals("album-1", album.id)
            assertEquals("Newest", album.title)
            assertEquals("Artist", album.artist)
            assertEquals(2024, album.year)
            assertEquals(9, album.songCount)
            assertNotNull(album.thumbnailUrl)
        }
    }

    @Test
    fun `album and artist results map to browse-ready models`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"subsonic-response":{"status":"ok",
                      "searchResult3":{
                        "album":[{"id":"album-1","name":"Enter Sandman","artist":"Metallica","coverArt":"cover-1","year":1991}],
                        "artist":[{"id":"ar-1","name":"Metallica","albumCount":10,"coverArt":"ar-1"}]
                      }}}
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"subsonic-response":{"status":"ok",
                      "searchResult3":{
                        "album":[{"id":"album-1","name":"Enter Sandman","artist":"Metallica","coverArt":"cover-1","year":1991}],
                        "artist":[{"id":"ar-1","name":"Metallica","albumCount":10,"coverArt":"ar-1"}]
                      }}}
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"subsonic-response":{"status":"ok",
                      "album":{"id":"album-1","name":"Enter Sandman","artist":"Metallica",
                        "song":[{"id":"song-1","title":"Enter Sandman","artist":"Metallica",
                          "suffix":"flac","bitRate":1411}]}}}
                    """.trimIndent(),
                ),
            )
            val config = config(server)
            val source = NavidromeSource(config.configId, config)

            val albums = source.searchAlbums("Metallica", 5)
            val artists = source.searchArtists("Metallica", 5)
            val songs = source.albumSongs("album-1")

            assertEquals("album-1", albums.single().id)
            assertEquals(1991, albums.single().year)
            assertNotNull(albums.single().thumbnailUrl)
            assertEquals("ar-1", artists.single().id)
            assertNotNull(artists.single().thumbnailUrl)
            assertEquals("Enter Sandman", songs.single().title)
            assertEquals(SongOrigin.NAVIDROME, songs.single().sourceOrigin)
        }
    }

    @Test
    fun `artist search dedupes ids and falls back to the album credit`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"subsonic-response":{"status":"ok",
                      "searchResult3":{"artist":[
                        {"id":"ar-1","name":"Eminem"},
                        {"id":"ar-1","name":"Eminem"},
                        {"id":"ar-2","name":"Eminem"}
                      ]}}}
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"subsonic-response":{"status":"ok","searchResult3":{"artist":[]}}}""",
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"subsonic-response":{"status":"ok",
                      "searchResult3":{"album":[
                        {"id":"album-1","name":"The Eminem Show","artist":"Eminem","coverArt":"cover-1"}
                      ]}}}
                    """.trimIndent(),
                ),
            )
            val config = config(server)
            val source = NavidromeSource(config.configId, config)

            val direct = source.searchArtists("Eminem", 10)
            val fromAlbum = source.searchArtists("the Eminem show", 10)

            assertEquals(1, direct.size)
            assertEquals("ar-1", direct.single().id)
            assertEquals("Eminem", fromAlbum.single().name)
        }
    }

    @Test
    fun `stream reuses the cached row and builds a Subsonic URL`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"subsonic-response":{"status":"ok",
                      "searchResult3":{"song":[{
                        "id":"song-1","title":"Title","artist":"Artist",
                        "duration":185,"suffix":"flac","bitRate":1411
                      }]}}}
                    """.trimIndent(),
                ),
            )
            val config = config(server)
            val source = NavidromeSource(config.configId, config)
            source.search("Title", limit = 10)

            val stream = source.stream("song-1", StreamRequest.Lossless)

            assertNotNull(stream)
            assertEquals(185, stream!!.durationSec)
            assertEquals("flac", stream.format.codec)
            val url = stream.url.toHttpUrl()
            assertEquals("/rest/stream.view", url.encodedPath)
            assertEquals("song-1", url.queryParameter("id"))
            assertEquals("0", url.queryParameter("maxBitRate"))
            assertEquals("raw", url.queryParameter("format"))
        }
    }
}
