package com.music.bitchord.playback

import com.music.bitchord.data.enrichment.needsEnrichment
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrichmentHookTest {

    private fun song(
        videoId: String = "id",
        isrc: String? = null,
        trackNumber: Int? = null,
        genre: String? = null,
    ) = Song(
        videoId = videoId,
        title = "Title",
        artist = "Artist",
        thumbnailUrl = null,
        isrc = isrc,
        trackNumber = trackNumber,
        genre = genre,
    )

    @Test
    fun `a song with any identity field is not enriched again`() {
        assertFalse(song(isrc = "USRC17607839").needsEnrichment())
        assertFalse(song(trackNumber = 3).needsEnrichment())
        assertFalse(song(genre = "Rock").needsEnrichment())
    }

    @Test
    fun `a blank id is never enriched`() {
        assertFalse(song(videoId = "").needsEnrichment())
    }

    @Test
    fun `a plain song needs enrichment`() {
        assertTrue(song().needsEnrichment())
    }

    @Test
    fun `the window is current plus lookahead, bounded by the queue`() {
        assertEquals(0..8, enrichmentWindow(currentIndex = 0, itemCount = 20, lookahead = 8))
        assertEquals(15..19, enrichmentWindow(currentIndex = 15, itemCount = 20, lookahead = 8))
        assertEquals(3..3, enrichmentWindow(currentIndex = 3, itemCount = 20, lookahead = 0))
    }

    @Test
    fun `an empty or invalid window has no rows`() {
        assertTrue(enrichmentWindow(currentIndex = -1, itemCount = 5, lookahead = 8).isEmpty())
        assertTrue(enrichmentWindow(currentIndex = 5, itemCount = 5, lookahead = 8).isEmpty())
        assertTrue(enrichmentWindow(currentIndex = 0, itemCount = 0, lookahead = 8).isEmpty())
    }
}
