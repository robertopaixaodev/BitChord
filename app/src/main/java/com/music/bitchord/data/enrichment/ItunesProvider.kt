package com.music.bitchord.data.enrichment

import android.util.Log
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ItunesSearchResponse(
    val results: List<ItunesSearchItem> = emptyList(),
)

@Serializable
private data class ItunesSearchItem(
    @SerialName("trackName") val trackName: String? = null,
    @SerialName("artistName") val artistName: String? = null,
    @SerialName("trackNumber") val trackNumber: Int? = null,
    @SerialName("primaryGenreName") val genre: String? = null,
    @SerialName("releaseDate") val releaseDate: String? = null,
)

/**
 * The free iTunes Search API. It has no ISRC, so this provider is deliberately
 * limited to the fields the catalogue does expose.
 */
class ItunesProvider(
    private val client: HttpClient = EnrichmentHttp.client,
    private val json: Json = EnrichmentHttp.json,
    private val baseUrl: String = "https://itunes.apple.com",
) : EnrichmentProvider {

    override val name: String = "itunes"

    override suspend fun enrich(song: Song): PartialEnrichment? {
        if (song.title.isBlank() || song.artist.isBlank()) return null
        return try {
            val response = client.get("${baseUrl.trimEnd('/')}/search") {
                parameter("term", "${song.artist} ${song.title}")
                parameter("entity", "song")
                parameter("limit", 5)
            }
            Log.d("EnrichmentDebug", "iTunes response code: ${response.status}")
            if (!response.status.isSuccess()) {
                TrackLog.w(TAG, "iTunes returned ${response.status.value} for '${song.title}'", song.videoId)
                return null
            }

            val parsed = json.decodeFromString<ItunesSearchResponse>(response.bodyAsText())
            val match = parsed.results.firstOrNull {
                matchesTitleAndArtist(song.title, song.artist, it.trackName, it.artistName)
            } ?: return null

            val partial = PartialEnrichment(
                trackNumber = match.trackNumber,
                genre = match.genre,
                year = releaseYear(match.releaseDate),
                provider = name,
            )
            partial.takeIf { it.hasAny }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("EnrichmentDebug", "Network error in ${this::class.simpleName}: ${e.message}")
            TrackLog.w(TAG, "iTunes enrichment failed for '${song.title}'", e, song.videoId)
            null
        }
    }

    private companion object {
        const val TAG = "Enrichment"
    }
}
