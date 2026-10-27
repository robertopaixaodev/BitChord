package com.music.bitchord.data.enrichment

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrichmentServiceTest {

    private fun song(videoId: String = "song-1") = Song(
        videoId = videoId,
        title = "Title",
        artist = "Artist",
        thumbnailUrl = null,
    )

    private fun cache(): EnrichmentCache =
        EnrichmentCache().apply { initForTesting(EnrichmentTestPrefs()) }

    private class FakeProvider(
        override val name: String,
        private val result: PartialEnrichment?,
        private val inspect: ((Song) -> Unit)? = null,
    ) : EnrichmentProvider {
        var calls = 0

        override suspend fun enrich(song: Song): PartialEnrichment? {
            calls++
            inspect?.invoke(song)
            return result
        }
    }

    @Test
    fun `cache hit merges without calling a provider`() = runBlocking {
        val cache = cache()
        val original = song()
        cache.put(
            original.copy(isrc = "cached-isrc", genre = "Cached Genre"),
            provider = "cache",
        )
        val provider = FakeProvider(
            name = "itunes",
            result = PartialEnrichment(isrc = "remote-isrc", provider = "itunes"),
        )
        val service = EnrichmentService(cache, listOf(provider))

        val result = service.enrich(original)

        assertEquals("cached-isrc", result?.isrc)
        assertEquals("Cached Genre", result?.genre)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `itunes fills only the fields it knows`() = runBlocking {
        val cache = cache()
        val provider = FakeProvider(
            name = "itunes",
            result = PartialEnrichment(trackNumber = 3, genre = "Rock", year = 2001, provider = "itunes"),
        )
        val service = EnrichmentService(cache, listOf(provider))

        val result = service.enrich(song())

        assertEquals(3, result?.trackNumber)
        assertEquals("Rock", result?.genre)
        assertEquals(2001, result?.year)
        assertNull(result?.isrc)
        assertEquals("itunes", cache.get("song-1")?.provider)
        assertEquals("Rock", cache.get("song-1")?.genre)
    }

    @Test
    fun `the chain passes each result to the next provider`() = runBlocking {
        val cache = cache()
        var deezerSawIsrc: String? = null
        val itunes = FakeProvider(
            name = "itunes",
            result = PartialEnrichment(trackNumber = 3, genre = "Rock", year = 2001, provider = "itunes"),
        )
        val musicBrainz = FakeProvider(
            name = "musicbrainz",
            result = PartialEnrichment(
                isrc = "USRC17607839",
                mbid = "11111111-2222-3333-4444-555555555555",
                provider = "musicbrainz",
            ),
        )
        val deezer = FakeProvider(
            name = "deezer",
            result = PartialEnrichment(
                discNumber = 1,
                albumArtist = "Album Artist",
                label = "Label Records",
                composer = "Composer Name",
                provider = "deezer",
            ),
            inspect = { deezerSawIsrc = it.isrc },
        )
        val service = EnrichmentService(cache, listOf(itunes, musicBrainz, deezer))

        val result = service.enrich(song())

        assertEquals("USRC17607839", deezerSawIsrc)
        assertEquals("USRC17607839", result?.isrc)
        assertEquals("11111111-2222-3333-4444-555555555555", result?.mbid)
        assertEquals(3, result?.trackNumber)
        assertEquals(1, result?.discNumber)
        assertEquals("Album Artist", result?.albumArtist)
        assertEquals("Label Records", result?.label)
        assertEquals("Composer Name", result?.composer)
        assertEquals(2001, result?.year)
    }

    @Test
    fun `all providers failing returns null and records a miss`() = runBlocking {
        val cache = cache()
        val service = EnrichmentService(
            cache,
            listOf(
                FakeProvider("itunes", null),
                FakeProvider("musicbrainz", null),
                FakeProvider("deezer", null),
            ),
        )

        val result = service.enrich(song())

        assertNull(result)
        assertTrue(cache.get("song-1")?.isFreshMiss() == true)
    }

    @Test
    fun `a failed MusicBrainz still keeps the iTunes partial`() = runBlocking {
        val cache = cache()
        val itunes = FakeProvider(
            name = "itunes",
            result = PartialEnrichment(genre = "Rock", year = 2001, provider = "itunes"),
        )
        val service = EnrichmentService(
            cache,
            listOf(
                itunes,
                FakeProvider("musicbrainz", null),
                FakeProvider("deezer", null),
            ),
        )

        val result = service.enrich(song())

        assertEquals("Rock", result?.genre)
        assertEquals(2001, result?.year)
        assertNull(result?.isrc)
    }

    @Test
    fun `a provider cannot overwrite a value already on the song`() = runBlocking {
        val cache = cache()
        val original = song().copy(genre = "Existing", label = "Existing Label")
        val provider = FakeProvider(
            name = "itunes",
            result = PartialEnrichment(
                genre = "Provider Genre",
                label = "Provider Label",
                composer = "Composer",
                provider = "itunes",
            ),
        )
        val service = EnrichmentService(cache, listOf(provider))

        val result = service.enrich(original)

        assertEquals("Existing", result?.genre)
        assertEquals("Existing Label", result?.label)
        assertEquals("Composer", result?.composer)
    }

    @Test
    fun `a fresh miss skips the providers`() = runBlocking {
        val cache = cache()
        cache.putMiss("song-1")
        val provider = FakeProvider(
            name = "itunes",
            result = PartialEnrichment(genre = "Rock", provider = "itunes"),
        )
        val service = EnrichmentService(cache, listOf(provider))

        val result = service.enrich(song())

        assertNull(result)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `concurrent calls share one provider chain`() = runBlocking {
        val cache = cache()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val provider = object : EnrichmentProvider {
            override val name: String = "fake"

            override suspend fun enrich(song: Song): PartialEnrichment? {
                calls++
                started.complete(Unit)
                gate.await()
                return PartialEnrichment(isrc = "USRC17607839", provider = name)
            }
        }
        val service = EnrichmentService(cache, listOf(provider))

        val first = async { service.enrich(song()) }
        started.await()
        val second = async { service.enrich(song()) }
        delay(50)
        gate.complete(Unit)
        val results = awaitAll(first, second)

        assertEquals(1, calls)
        assertTrue(results.all { it?.isrc == "USRC17607839" })
    }

    @Test
    fun `a second sequential call uses the cache`() = runBlocking {
        val cache = cache()
        val provider = FakeProvider(
            name = "itunes",
            result = PartialEnrichment(genre = "Rock", provider = "itunes"),
        )
        val service = EnrichmentService(cache, listOf(provider))

        assertEquals("Rock", service.enrich(song())?.genre)
        assertEquals("Rock", service.enrich(song())?.genre)

        assertEquals(1, provider.calls)
    }
}
