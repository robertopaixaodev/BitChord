package com.music.bitchord.data.enrichment

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The nine identity fields as they were last resolved for one video id.
 *
 * A miss is stored too: without it, a track no provider knows would be asked
 * about again on every queue rebuild. [updatedAt] is the retry clock for that
 * miss.
 */
@Serializable
internal data class CachedEnrichment(
    val isrc: String? = null,
    val mbid: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val label: String? = null,
    val composer: String? = null,
    val missed: Boolean = false,
    val updatedAt: Long = 0L,
    val provider: String? = null,
) {
    val hasAny: Boolean
        get() = isrc != null || mbid != null || trackNumber != null ||
            discNumber != null || albumArtist != null || genre != null ||
            year != null || label != null || composer != null

    fun isFreshMiss(now: Long = System.currentTimeMillis()): Boolean =
        missed && now - updatedAt < EnrichmentCache.MISS_TTL_MS

    fun toPartial(): PartialEnrichment = PartialEnrichment(
        isrc = isrc,
        mbid = mbid,
        trackNumber = trackNumber,
        discNumber = discNumber,
        albumArtist = albumArtist,
        genre = genre,
        year = year,
        label = label,
        composer = composer,
        provider = provider ?: "cache",
    )
}

/**
 * Enrichment results keyed by videoId, in their own preference file so the
 * queue/download stores cannot be disturbed by a cache write.
 */
class EnrichmentCache {

    private lateinit var prefs: SharedPreferences
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val serializer = MapSerializer(
        String.serializer(),
        CachedEnrichment.serializer(),
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Test-only dependency injection; production uses [init]. */
    internal fun initForTesting(testPrefs: SharedPreferences) {
        prefs = testPrefs
    }

    internal fun get(videoId: String): CachedEnrichment? = read()[videoId]

    internal fun put(song: Song, provider: String, now: Long = System.currentTimeMillis()) {
        if (song.videoId.isBlank()) return
        val entries = read().toMutableMap()
        entries[song.videoId] = CachedEnrichment(
            isrc = song.isrc,
            mbid = song.mbid,
            trackNumber = song.trackNumber,
            discNumber = song.discNumber,
            albumArtist = song.albumArtist,
            genre = song.genre,
            year = song.year,
            label = song.label,
            composer = song.composer,
            missed = false,
            updatedAt = now,
            provider = provider,
        )
        write(entries)
    }

    internal fun putMiss(videoId: String, now: Long = System.currentTimeMillis()) {
        if (videoId.isBlank()) return
        val entries = read().toMutableMap()
        entries[videoId] = CachedEnrichment(missed = true, updatedAt = now)
        write(entries)
    }

    internal fun clear() {
        if (this::prefs.isInitialized) prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private fun read(): Map<String, CachedEnrichment> {
        if (!this::prefs.isInitialized) return emptyMap()
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString(serializer, raw)
        }.getOrDefault(emptyMap())
    }

    private fun write(entries: Map<String, CachedEnrichment>) {
        if (!this::prefs.isInitialized) return
        val encoded = runCatching {
            json.encodeToString(serializer, entries)
        }.getOrNull() ?: return
        prefs.edit().putString(KEY_ENTRIES, encoded).apply()
    }

    companion object {
        internal const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val PREFS_NAME = "bitchord_enrichment"
        private const val KEY_ENTRIES = "entries"
    }
}
