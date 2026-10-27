package com.music.bitchord

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.songFromBundle
import com.music.bitchord.playback.toMediaItem
import com.music.bitchord.playback.toSong
import com.music.bitchord.playback.toSongBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The queue is rebuilt from a [androidx.media3.common.MediaItem] and from the
 * service's swap [android.os.Bundle], so anything these mappings drop is gone
 * before any persistence layer sees it.
 *
 * Instrumented because local unit tests run against a stub `android.os.Bundle`
 * (`unitTests.isReturnDefaultValues = true`), which discards every put and
 * returns null for every get.
 */
@RunWith(AndroidJUnit4::class)
class PlayerConnectionIdentityTest {

    private fun fullyPopulatedSong() = Song(
        videoId = "video-identity-1",
        title = "Title",
        artist = "Artist",
        thumbnailUrl = null,
        isrc = "USRC17607839",
        mbid = "11111111-2222-3333-4444-555555555555",
        discNumber = 2,
        albumArtist = "Album Artist",
        genre = "Jazz",
        year = 1999,
        label = "Label Records",
        composer = "Composer Name",
        trackNumber = 7,
    )

    private fun assertIdentityEquals(expected: Song, actual: Song) {
        assertEquals(expected.isrc, actual.isrc)
        assertEquals(expected.mbid, actual.mbid)
        assertEquals(expected.discNumber, actual.discNumber)
        assertEquals(expected.albumArtist, actual.albumArtist)
        assertEquals(expected.genre, actual.genre)
        assertEquals(expected.year, actual.year)
        assertEquals(expected.label, actual.label)
        assertEquals(expected.composer, actual.composer)
        assertEquals(expected.trackNumber, actual.trackNumber)
    }

    @Test
    fun mediaItemRoundTripPreservesMusicalIdentity() {
        val original = fullyPopulatedSong()

        val restored = original.toMediaItem().toSong()

        assertIdentityEquals(original, restored)
    }

    @Test
    fun mediaItemRoundTripKeepsMissingIdentityNull() {
        val original = Song(
            videoId = "video-identity-empty",
            title = "Title",
            artist = "Artist",
            thumbnailUrl = null,
        )

        val restored = original.toMediaItem().toSong()

        assertNull(restored.isrc)
        assertNull(restored.mbid)
        assertNull(restored.discNumber)
        assertNull(restored.albumArtist)
        assertNull(restored.genre)
        assertNull(restored.year)
        assertNull(restored.label)
        assertNull(restored.composer)
        assertNull(restored.trackNumber)
    }

    @Test
    fun bundleRoundTripPreservesMusicalIdentity() {
        val original = fullyPopulatedSong()

        val restored = songFromBundle(original.toSongBundle())

        assertIdentityEquals(original, restored)
    }

    @Test
    fun bundleRoundTripKeepsMissingIdentityNull() {
        val original = Song(
            videoId = "video-identity-empty",
            title = "Title",
            artist = "Artist",
            thumbnailUrl = null,
        )

        val restored = songFromBundle(original.toSongBundle())

        assertNull(restored.isrc)
        assertNull(restored.mbid)
        assertNull(restored.discNumber)
        assertNull(restored.albumArtist)
        assertNull(restored.genre)
        assertNull(restored.year)
        assertNull(restored.label)
        assertNull(restored.composer)
        assertNull(restored.trackNumber)
    }
}
