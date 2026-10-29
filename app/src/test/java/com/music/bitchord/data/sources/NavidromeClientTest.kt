package com.music.bitchord.data.sources

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class NavidromeClientTest {

    private fun config(server: MockWebServer) = NavidromeConfig(
        baseUrl = server.url("").toString().trimEnd('/'),
        username = "listener",
        password = "secret",
    )

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `search3 signs the request and parses rows`() = runBlocking {
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
            val client = NavidromeClient(config(server))

            val songs = client.searchSongs("Title", 10)

            assertEquals(1, songs.size)
            assertEquals("song-1", songs.single().id)
            assertEquals("USRC17607839", songs.single().isrc)
            val request = server.takeRequest()
            assertTrue(request.path.orEmpty().startsWith("/rest/search3.view"))
            val url = request.requestUrl!!
            assertEquals("listener", url.queryParameter("u"))
            assertEquals("1.16.1", url.queryParameter("v"))
            assertEquals("BitChord", url.queryParameter("c"))
            val salt = url.queryParameter("s")
            assertNotNull(salt)
            assertEquals(md5("secret$salt"), url.queryParameter("t"))
            assertEquals("Title", url.queryParameter("query"))
            assertEquals("10", url.queryParameter("songCount"))
        }
    }

    @Test
    fun `search accepts OpenSubsonic arrays for isrc and mbid`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"subsonic-response":{"status":"ok","version":"1.16.1",
                      "searchResult3":{"song":[{
                        "id":"song-1","title":"Enter Sandman","artist":"Metallica",
                        "isrc":["USSM19901001"],
                        "musicBrainzId":["mbid-1"]
                      }]}}}
                    """.trimIndent(),
                ),
            )
            val client = NavidromeClient(config(server))

            val songs = client.searchSongs("enter sandman metallica", 10)

            assertEquals(1, songs.size)
            assertEquals("USSM19901001", songs.single().isrc)
            assertEquals("mbid-1", songs.single().musicBrainzId)
        }
    }

    @Test
    fun `album list asks for newest and parses rows`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"subsonic-response":{"status":"ok","version":"1.16.1",
                      "albumList2":{"album":[
                        {"id":"album-1","name":"Newest","artist":"Artist",
                         "coverArt":"cover-1","songCount":9,"year":2024},
                        {"id":"album-2","name":"Older","artist":"Other","year":1999}
                      ]}}}
                    """.trimIndent(),
                ),
            )
            val client = NavidromeClient(config(server))

            val albums = client.recentAlbums(10)

            assertEquals(listOf("album-1", "album-2"), albums.map { it.id })
            assertEquals("Newest", albums.first().name)
            assertEquals("Artist", albums.first().artist)
            assertEquals(9, albums.first().songCount)
            val url = server.takeRequest().requestUrl!!
            assertEquals("/rest/getAlbumList2.view", url.encodedPath)
            assertEquals("newest", url.queryParameter("type"))
            assertEquals("10", url.queryParameter("size"))
        }
    }

    @Test
    fun `genres artists and songs-by-genre parse OpenSubsonic shapes`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"subsonic-response":{"status":"ok","version":"1.16.1",
                      "genres":{"genre":[
                        {"value":"Rock","songCount":42,"albumCount":7},
                        {"name":"Jazz","songCount":9}
                      ]}}}
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"subsonic-response":{"status":"ok","version":"1.16.1",
                      "artists":{"index":[
                        {"name":"A","artist":[
                          {"id":"ar-1","name":"Artist","albumCount":2,"coverArt":"ar-1"}
                        ]},
                        {"name":"B","artist":[
                          {"id":"ar-2","name":"Another","albumCount":1}
                        ]}
                      ]}}}
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """
                    {"subsonic-response":{"status":"ok","version":"1.16.1",
                      "songsByGenre":{"song":[
                        {"id":"song-1","title":"Rock Song","artist":"Artist",
                         "suffix":"flac","bitRate":1411}
                      ]}}}
                    """.trimIndent(),
                ),
            )
            val client = NavidromeClient(config(server))

            val genres = client.genres()
            val artists = client.artists()
            val songs = client.songsByGenre("Rock", 25)

            assertEquals(listOf("Rock", "Jazz"), genres.map { it.displayName })
            assertEquals(42, genres.first().songCount)
            assertEquals(listOf("ar-1", "ar-2"), artists.map { it.id })
            assertEquals("song-1", songs.single().id)
            // The three requests are sequential; the last one is the genre call.
            server.takeRequest()
            server.takeRequest()
            val url = server.takeRequest().requestUrl!!
            assertEquals("/rest/getSongsByGenre.view", url.encodedPath)
            assertEquals("Rock", url.queryParameter("genre"))
            assertEquals("25", url.queryParameter("count"))
        }
    }

    @Test
    fun `stream URL keeps the song id and asks for raw lossless`() {
        MockWebServer().use { server ->
            server.start()
            val client = NavidromeClient(config(server))

            val url = client.streamUrl("song-1", StreamRequest.Lossless).toHttpUrl()

            assertEquals("/rest/stream.view", url.encodedPath)
            assertEquals("song-1", url.queryParameter("id"))
            assertEquals("0", url.queryParameter("maxBitRate"))
            assertEquals("raw", url.queryParameter("format"))
        }
    }

    @Test
    fun `ping maps a rejected login to Rejected`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setBody(
                    """{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}""",
                ),
            )
            val client = NavidromeClient(config(server))

            val health = client.health()

            assertTrue(health is SourceHealth.Rejected)
        }
    }
}
