package com.music.bitchord.data.enrichment

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrichmentCacheTest {

    private fun song(videoId: String = "cache-song") = Song(
        videoId = videoId,
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

    @Test
    fun `cache survives a new instance`() {
        val prefs = EnrichmentTestPrefs()
        EnrichmentCache().apply { initForTesting(prefs) }.put(song(), "itunes", now = 10L)

        val reopened = EnrichmentCache().apply { initForTesting(prefs) }
        val cached = reopened.get("cache-song")

        assertNotNull(cached)
        assertEquals("USRC17607839", cached!!.isrc)
        assertEquals("11111111-2222-3333-4444-555555555555", cached.mbid)
        assertEquals(7, cached.trackNumber)
        assertEquals(2, cached.discNumber)
        assertEquals("Album Artist", cached.albumArtist)
        assertEquals("Jazz", cached.genre)
        assertEquals(1999, cached.year)
        assertEquals("Label Records", cached.label)
        assertEquals("Composer Name", cached.composer)
        assertEquals("itunes", cached.provider)
        assertFalse(cached.missed)
    }

    @Test
    fun `a miss is fresh until its ttl`() {
        val prefs = EnrichmentTestPrefs()
        val cache = EnrichmentCache().apply { initForTesting(prefs) }
        cache.putMiss("missing", now = 1_000L)

        val cached = cache.get("missing")

        assertNotNull(cached)
        assertTrue(cached!!.isFreshMiss(now = 1_000L + 60_000L))
        assertFalse(cached.isFreshMiss(now = 1_000L + EnrichmentCache.MISS_TTL_MS))
    }

    @Test
    fun `an unknown video id has no entry`() {
        val cache = EnrichmentCache().apply { initForTesting(EnrichmentTestPrefs()) }
        assertNull(cache.get("nobody"))
    }
}
