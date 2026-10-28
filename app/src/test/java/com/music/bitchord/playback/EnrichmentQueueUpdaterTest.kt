package com.music.bitchord.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class EnrichmentQueueUpdaterTest {

    private fun item(mediaId: String, uri: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .build()

    private fun song(
        videoId: String,
        queueEntryId: String? = null,
        isrc: String? = "USRC17607839",
    ) = Song(
        videoId = videoId,
        title = "Title",
        artist = "Artist",
        thumbnailUrl = null,
        isrc = isrc,
        queueEntryId = queueEntryId,
    )

    private class FakePlayer(
        val items: MutableList<MediaItem>,
        var currentIndex: Int = 0,
    ) {
        val replacements = mutableListOf<Pair<Int, MediaItem>>()

        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getMediaItemCount" -> items.size
                "getCurrentMediaItemIndex" -> currentIndex
                "getMediaItemAt" -> items[args[0] as Int]
                "replaceMediaItem" -> {
                    val index = args[0] as Int
                    val replacement = args[1] as MediaItem
                    replacements += index to replacement
                    items[index] = replacement
                    null
                }
                else -> null
            }
        } as Player
    }

    @Test
    fun `updates a future item directly`() {
        val fake = FakePlayer(
            mutableListOf(
                item("current", "https://music.test/current.flac"),
                item("next", "https://music.test/next.flac"),
            ),
        )
        val updater = EnrichmentQueueUpdater(fake.player)

        val count = updater.updateCurrentAndNext(song("next"), lookahead = 8)

        assertEquals(1, count)
        assertEquals(1, fake.replacements.single().first)
        assertEquals("next", fake.replacements.single().second.mediaId)
    }

    @Test
    fun `current progressive row is selected by the plan`() {
        val items = listOf(
            EnrichmentQueueItem(
                index = 0,
                mediaId = "current",
                queueEntryId = null,
                uri = "https://music.test/current.flac",
            ),
        )

        val targets = planEnrichmentTargets(
            currentIndex = 0,
            items = items,
            enriched = song("current"),
            lookahead = 8,
            isSwapping = false,
        )

        assertEquals(listOf(0), targets)
    }

    @Test
    fun `does not select the current item when its source is a manifest`() {
        listOf(
            "https://music.test/current.m3u8",
            "https://music.test/current.mpd",
        ).forEach { uri ->
            val items = listOf(EnrichmentQueueItem(0, "current", null, uri))

            val targets = planEnrichmentTargets(
                currentIndex = 0,
                items = items,
                enriched = song("current"),
                lookahead = 8,
                isSwapping = false,
            )

            assertTrue(targets.isEmpty())
        }
    }

    @Test
    fun `does not select the current item while a quality swap is in flight`() {
        val items = listOf(
            EnrichmentQueueItem(0, "current", null, "https://music.test/current.flac"),
        )

        val targets = planEnrichmentTargets(
            currentIndex = 0,
            items = items,
            enriched = song("current"),
            lookahead = 8,
            isSwapping = true,
        )

        assertTrue(targets.isEmpty())
    }

    @Test
    fun `does not touch items outside the lookahead window`() {
        val fake = FakePlayer(
            mutableListOf(
                item("current", "https://music.test/current.flac"),
                item("one", "https://music.test/one.flac"),
                item("two", "https://music.test/two.flac"),
                item("three", "https://music.test/three.flac"),
            ),
        )
        val updater = EnrichmentQueueUpdater(fake.player)

        val count = updater.updateCurrentAndNext(song("three"), lookahead = 1)

        assertEquals(0, count)
        assertTrue(fake.replacements.isEmpty())
    }

    @Test
    fun `plan counts every matching row in the window`() {
        val items = listOf(
            EnrichmentQueueItem(0, "same", null, "https://music.test/current.flac"),
            EnrichmentQueueItem(1, "same", null, "https://music.test/one.flac"),
            EnrichmentQueueItem(2, "same", null, "https://music.test/two.flac"),
        )

        val targets = planEnrichmentTargets(
            currentIndex = 0,
            items = items,
            enriched = song("same"),
            lookahead = 8,
            isSwapping = false,
        )

        assertEquals(listOf(0, 1, 2), targets)
    }

    @Test
    fun `queue entry id wins over a duplicate media id`() {
        val items = listOf(
            EnrichmentQueueItem(0, "same", "entry-1", "https://music.test/a.flac"),
            EnrichmentQueueItem(1, "same", "entry-2", "https://music.test/b.flac"),
        )
        val enriched = song("same", queueEntryId = "entry-2")

        val targets = planEnrichmentTargets(
            currentIndex = 0,
            items = items,
            enriched = enriched,
            lookahead = 8,
            isSwapping = false,
        )

        assertEquals(listOf(1), targets)
    }

    @Test
    fun `manifest current row is skipped by the plan`() {
        val items = listOf(
            EnrichmentQueueItem(0, "current", null, "https://music.test/a.m3u8"),
        )

        val targets = planEnrichmentTargets(
            currentIndex = 0,
            items = items,
            enriched = song("current"),
            lookahead = 8,
            isSwapping = false,
        )

        assertTrue(targets.isEmpty())
    }

    @Test
    fun `plan skips the current row during a swap but keeps future rows`() {
        val items = listOf(
            EnrichmentQueueItem(0, "current", "entry-1", "https://music.test/a.flac"),
            EnrichmentQueueItem(1, "next", "entry-2", "https://music.test/b.flac"),
        )

        val currentTargets = planEnrichmentTargets(
            currentIndex = 0,
            items = items,
            enriched = song("current", queueEntryId = "entry-1"),
            lookahead = 8,
            isSwapping = true,
        )
        val futureTargets = planEnrichmentTargets(
            currentIndex = 0,
            items = items,
            enriched = song("next", queueEntryId = "entry-2"),
            lookahead = 8,
            isSwapping = true,
        )

        assertTrue(currentTargets.isEmpty())
        assertEquals(listOf(1), futureTargets)
    }

    @Test
    fun `tryUpdateItemAt rejects a mismatched queue entry`() {
        val fake = FakePlayer(
            mutableListOf(item("same", "https://music.test/a.flac")),
        )
        val updater = EnrichmentQueueUpdater(fake.player)

        assertTrue(!updater.tryUpdateItemAt(0, song("same", queueEntryId = "missing")))
        assertTrue(fake.replacements.isEmpty())
    }

    @Test
    fun `progressive detection covers direct files and internal uris`() {
        listOf("mp3", "flac", "m4a", "ogg", "opus", "wav", "aac", "webm").forEach {
            assertTrue(isProgressiveUri("https://music.test/song.$it"))
            assertTrue(isProgressiveUri("https://music.test/song.$it?token=1"))
        }
        assertTrue(isProgressiveUri("https://rr1.googlevideo.com/videoplayback?itag=251"))
        assertTrue(isProgressiveUri("bitchord://watch?v=abc"))
        assertTrue(!isProgressiveUri("https://music.test/song.m3u8"))
        assertTrue(!isProgressiveUri("https://music.test/song.mpd"))
        assertTrue(!isProgressiveUri("rtsp://music.test/song"))
    }

    @Test
    fun `empty media id does not match`() {
        val fake = FakePlayer(
            mutableListOf(item("", "https://music.test/a.flac")),
        )
        val updater = EnrichmentQueueUpdater(fake.player)

        assertEquals(0, updater.updateCurrentAndNext(song(""), lookahead = 0))
        assertNull(fake.replacements.firstOrNull())
    }
}
