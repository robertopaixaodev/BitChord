package com.music.bitchord.data.enrichment

import android.util.Log
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the nine identity fields in a fixed, cheapest-first order:
 * iTunes (basic), MusicBrainz (ISRC/MBID), then Deezer (release credits).
 *
 * Nothing here touches the player. A caller in a later wave can ask for an
 * enriched [Song] or hand one to [enrichInBackground]; the service owns its
 * scope and its cache, so it is safe to call from a screen or a service without
 * reaching for `GlobalScope`.
 */
class EnrichmentService(
    private val cache: EnrichmentCache,
    private val providers: List<EnrichmentProvider> = defaultProviders(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val inFlight = ConcurrentHashMap<String, Deferred<Song?>>()

    /**
     * Returns the song with every field the cache or a provider could fill, or
     * null when nothing was found. Network and parse failures are logged and
     * never escape to the caller.
     *
     * Concurrent calls for one video id share a single provider chain. The
     * cache alone would only deduplicate after the first call had finished,
     * which is too late for current + lookahead asking for the same row.
     */
    suspend fun enrich(song: Song): Song? {
        Log.d("EnrichmentDebug", "Starting enrichment for: ${song.videoId} - ${song.title}")
        if (song.videoId.isBlank()) return null
        inFlight[song.videoId]?.let { return it.await() }

        val started = scope.async { enrichUncached(song) }
        val existing = inFlight.putIfAbsent(song.videoId, started)
        if (existing != null) {
            started.cancel()
            return existing.await()
        }
        started.invokeOnCompletion { inFlight.remove(song.videoId, started) }
        return started.await()
    }

    private suspend fun enrichUncached(song: Song): Song? {
        return try {
            val cached = cache.get(song.videoId)
            if (cached != null) {
                if (cached.isFreshMiss()) {
                    Log.d("EnrichmentDebug", "Enrichment returned null for ${song.title}")
                    return null
                }
                if (cached.hasAny) {
                    val merged = song.mergedWith(cached.toPartial())
                    Log.d("EnrichmentDebug", "Enrichment success for ${song.title}: isrc=${merged.isrc}")
                    return merged
                }
            }

            var working = song
            var resolvedBy: String? = null
            for (provider in providers) {
                if (working.hasCompleteIdentity()) break
                val partial = try {
                    provider.enrich(working)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("EnrichmentDebug", "Provider ${provider.name} failed: ${e.message}", e)
                    TrackLog.w(TAG, "${provider.name} provider failed for '${song.title}'", e, song.videoId)
                    null
                }
                if (partial != null && partial.hasAny) {
                    working = working.mergedWith(partial)
                    resolvedBy = partial.provider
                }
            }

            val resolved = resolvedBy
            if (resolved == null) {
                cache.putMiss(song.videoId)
                Log.d("EnrichmentDebug", "Enrichment returned null for ${song.title}")
                null
            } else {
                cache.put(working, resolved)
                Log.d("EnrichmentDebug", "Enrichment success for ${song.title}: isrc=${working.isrc}")
                working
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "Enrichment failed for '${song.title}'", e, song.videoId)
            Log.d("EnrichmentDebug", "Enrichment returned null for ${song.title}")
            null
        }
    }

    /**
     * Fire-and-forget on the service's own scope; failures are already swallowed
     * by [enrich]. [onResult] runs on that scope, so a caller that needs the
     * main thread must dispatch there.
     */
    fun enrichInBackground(song: Song, onResult: (Song?) -> Unit = {}) {
        scope.launch {
            onResult(enrich(song))
        }
    }

    /** Cancels any background work; useful for tests and for a future owned lifecycle. */
    internal fun close() {
        scope.cancel()
    }

    private companion object {
        const val TAG = "Enrichment"

        fun defaultProviders(): List<EnrichmentProvider> = listOf(
            ItunesProvider(),
            MusicBrainzProvider(),
            DeezerProvider(),
        )
    }
}
