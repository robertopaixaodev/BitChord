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
