package com.music.bitchord.playback

import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class LastPlayedTest {

    private lateinit var prefs: FakeSharedPreferences

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        LastPlayed.initForTesting(prefs)
    }

    private fun populatedSong() = Song(
        videoId = "last-played-1",
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

    private fun assertIdentityEquals(expected: Song, actual: Song) {
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
    fun `queue round trip preserves musical identity`() {
        val original = populatedSong()

        LastPlayed.saveQueue(listOf(original), index = 0)
        val restored = LastPlayed.load()!!.songs.single()

        assertIdentityEquals(original, restored)
    }

    @Test
    fun `queue round trip keeps missing identity null`() {
        val original = Song(
            videoId = "last-played-empty",
            title = "Title",
            artist = "Artist",
            thumbnailUrl = null,
        )

        LastPlayed.saveQueue(listOf(original), index = 0)
        val restored = LastPlayed.load()!!.songs.single()

        assertNull(restored.isrc)
        assertNull(restored.mbid)
        assertNull(restored.trackNumber)
        assertNull(restored.discNumber)
        assertNull(restored.albumArtist)
        assertNull(restored.genre)
        assertNull(restored.year)
        assertNull(restored.label)
        assertNull(restored.composer)
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? =
            data[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            data[key] as? Set<String> ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            (data[key] as? Number)?.toInt() ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (data[key] as? Number)?.toLong() ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (data[key] as? Number)?.toFloat() ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            data[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor(this)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {}

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {}

        private class Editor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putStringSet(
                key: String?,
                values: Set<String>?,
            ): SharedPreferences.Editor {
                if (key != null) pending[key] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removed += key
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) {
                    prefs.data.clear()
                } else {
                    prefs.data.keys.removeAll(removed)
                    prefs.data.putAll(pending)
                }
                pending.clear()
                removed.clear()
                clearAll = false
            }
        }
    }
}
