package com.music.bitchord.data.enrichment

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class MusicBrainzResponse(
    val recordings: List<MusicBrainzRecording> = emptyList(),
)

@Serializable
private data class MusicBrainzRecording(
    val id: String = "",
    val title: String = "",
    val isrcs: List<String> = emptyList(),
    @SerialName("artist-credit") val artistCredit: List<MusicBrainzArtistCredit> = emptyList(),
) {
    val credit: String
        get() = artistCredit
            .joinToString(" ") { it.name ?: it.artist?.name.orEmpty() }
            .trim()
}

@Serializable
private data class MusicBrainzArtistCredit(
    val name: String? = null,
    val artist: MusicBrainzArtist? = null,
)

@Serializable
private data class MusicBrainzArtist(val name: String? = null)

/**
 * Resolves a recording's MusicBrainz id and, when the recording has one, its
 * ISRC. MusicBrainz requires a descriptive User-Agent and asks clients to stay
 * at or below one request per second; [RequestGate] enforces the latter.
 */
class MusicBrainzProvider(
    private val client: HttpClient = EnrichmentHttp.client,
    private val json: Json = EnrichmentHttp.json,
    private val baseUrl: String = "https://musicbrainz.org/ws/2",
    private val userAgent: String = EnrichmentConfig.musicBrainzUserAgent,
) : EnrichmentProvider {

    override val name: String = "musicbrainz"
    private val gate = RequestGate(1_000L)

    override suspend fun enrich(song: Song): PartialEnrichment? {
        if (song.title.isBlank()) return null
        return try {
            gate.await()
            val query = buildString {
                append("recording:")
                append(song.title)
                if (song.artist.isNotBlank()) {
                    append(" AND artist:")
                    append(song.artist)
                }
            }
            val response = client.get("${baseUrl.trimEnd('/')}/recording") {
                parameter("query", query)
                parameter("fmt", "json")
                parameter("limit", 5)
                header(HttpHeaders.UserAgent, userAgent)
            }
            if (!response.status.isSuccess()) {
                TrackLog.w(
                    TAG,
                    "MusicBrainz returned ${response.status.value} for '${song.title}'",
                    song.videoId,
                )
                return null
            }

            val parsed = json.decodeFromString<MusicBrainzResponse>(response.bodyAsText())
            val matches = parsed.recordings.filter {
                matchesTitleAndArtist(song.title, song.artist, it.title, it.credit)
            }
            val match = matches.firstOrNull { it.isrcs.isNotEmpty() } ?: matches.firstOrNull()
                ?: return null

            val partial = PartialEnrichment(
                isrc = match.isrcs.firstOrNull()?.takeIf { it.isNotBlank() },
                mbid = match.id.takeIf { it.isNotBlank() },
                provider = name,
            )
            partial.takeIf { it.hasAny }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "MusicBrainz enrichment failed for '${song.title}'", e, song.videoId)
            null
        }
    }

    private companion object {
        const val TAG = "Enrichment"
    }
}
