package com.music.bitchord.data.sources

import android.util.Log
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Locale

/**
 * A Navidrome/Subsonic library.
 *
 * One search row is one `/rest/stream` URL: there is no pack to enumerate and
 * no codec negotiation beyond what the server says it holds. Rows are cached by
 * their Subsonic id so [stream] can carry the duration and suffix the search
 * already returned, exactly as the addon source does.
 */
class NavidromeSource(
    override val configId: String,
    private val config: NavidromeConfig,
    private val client: NavidromeClient = NavidromeClient(config),
) : MusicSource {

    override val kind: SourceKind = SourceKind.NAVIDROME
    override val displayName: String = config.displayName

    private val rows = Collections.synchronizedMap(
        object : LinkedHashMap<String, NavidromeSong>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, NavidromeSong>) = size > MAX_ROWS
        },
    )

    override suspend fun health(): SourceHealth {
        if (!config.isComplete) {
            return SourceHealth.Rejected("A Navidrome URL and username are required")
        }
        return client.health()
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> = withContext(Dispatchers.IO) {
        Log.d("NavidromeDebug", "Iniciando busca no Navidrome para: $query")
        if (query.isBlank() || limit <= 0) return@withContext emptyList()
        try {
            client.searchSongs(query, limit).take(limit).map { row ->
                rows[row.id] = row
                row.toSong()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "Navidrome search failed for '$query': ${e.message}")
            emptyList()
        }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        if (trackId.isBlank()) return null
        return try {
            val row = rows[trackId]
            SourceStream(
                url = client.streamUrl(trackId, request),
                format = row?.format() ?: StreamFormat(),
                durationSec = row?.duration,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "Navidrome stream URL failed for $trackId: ${e.message}")
            null
        }
    }

    private fun NavidromeSong.toSong(): Song {
        val suffix = suffix?.lowercase(Locale.ROOT)
        val lossless = suffix in LOSSLESS_SUFFIXES
        return Song(
            videoId = SourceRegistry.trackKey(configId, id),
            title = title.ifBlank { "Unknown Title" },
            artist = artist?.takeIf { it.isNotBlank() } ?: "Unknown Artist",
            albumName = album?.takeIf { it.isNotBlank() },
            durationText = duration?.let(::formatDuration),
            thumbnailUrl = coverArt?.let(client::coverUrl),
            trackNumber = track,
            discNumber = discNumber,
            year = year,
            genre = genre?.takeIf { it.isNotBlank() },
            isrc = isrc?.takeIf { it.isNotBlank() },
            mbid = musicBrainzId?.takeIf { it.isNotBlank() },
            sourceQuality = if (lossless) "LOSSLESS" else "HIGH",
        )
    }

    private fun NavidromeSong.format(): StreamFormat {
        val codec = suffix?.lowercase(Locale.ROOT)?.let {
            when (it) {
                "aif", "aiff" -> "aiff"
                "m4a", "mp4" -> "alac"
                else -> it
            }
        }
        return StreamFormat(codec = codec, kbps = bitRate?.takeIf { it > 0 })
    }

    private companion object {
        const val TAG = "BitChord"
        const val MAX_ROWS = 512
        val LOSSLESS_SUFFIXES = setOf("flac", "wav", "aiff", "aif", "alac", "ape", "wv")

        fun formatDuration(seconds: Int): String =
            "${seconds / 60}:${"%02d".format(Locale.ROOT, seconds % 60)}"
    }
}
