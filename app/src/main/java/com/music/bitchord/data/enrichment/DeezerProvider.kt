package com.music.bitchord.data.enrichment

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
private data class DeezerSearchResponse(
    val data: List<DeezerTrack> = emptyList(),
)

@Serializable
private data class DeezerTrack(
    val id: Long = 0,
    val title: String = "",
    val isrc: String? = null,
    @SerialName("track_position") val trackPosition: Int? = null,
    @SerialName("disk_number") val diskNumber: Int? = null,
    val composer: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val artist: DeezerArtist? = null,
    val album: DeezerAlbumRef? = null,
)

@Serializable
private data class DeezerAlbumRef(val id: Long = 0)

@Serializable
private data class DeezerAlbum(
    val id: Long = 0,
    val label: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val artist: DeezerArtist? = null,
    val genres: DeezerGenres? = null,
)

@Serializable
private data class DeezerArtist(val name: String? = null)

@Serializable
private data class DeezerGenres(val data: List<DeezerGenre> = emptyList())

@Serializable
private data class DeezerGenre(val name: String? = null)

/**
 * Deezer is the only provider that can fill track/disc, label, composer and
 * album artist. It is keyed on the ISRC MusicBrainz resolved: a search hit is
 * accepted only when its ISRC matches, so a loose title search cannot attach
 * the wrong release.
 */
class DeezerProvider(
    private val client: HttpClient = EnrichmentHttp.client,
    private val json: Json = EnrichmentHttp.json,
    private val baseUrl: String = "https://api.deezer.com",
) : EnrichmentProvider {

    override val name: String = "deezer"
    private val gate = RequestGate(250L)

    override suspend fun enrich(song: Song): PartialEnrichment? {
        val isrc = song.isrc?.takeIf { it.isNotBlank() } ?: return null
        return try {
            gate.await()
            val response = client.get("${baseUrl.trimEnd('/')}/search") {
                parameter("q", "${song.artist} ${song.title}")
            }
            if (!response.status.isSuccess()) {
                TrackLog.w(TAG, "Deezer returned ${response.status.value} for '${song.title}'", song.videoId)
                return null
            }

            val parsed = json.decodeFromString<DeezerSearchResponse>(response.bodyAsText())
            val match = parsed.data.firstOrNull {
                it.isrc.equals(isrc, ignoreCase = true)
            } ?: return null

            val album = match.album?.id
                ?.takeIf { it > 0L }
                ?.let { fetchAlbum(it, song) }

            val partial = PartialEnrichment(
                isrc = match.isrc,
                trackNumber = match.trackPosition,
                discNumber = match.diskNumber,
                albumArtist = album?.artist?.name,
                genre = album?.genres?.data?.firstOrNull()?.name,
                year = releaseYear(album?.releaseDate ?: match.releaseDate),
                label = album?.label,
                composer = match.composer,
                provider = name,
            )
            partial.takeIf { it.hasAny }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "Deezer enrichment failed for '${song.title}'", e, song.videoId)
            null
        }
    }

    private suspend fun fetchAlbum(albumId: Long, song: Song): DeezerAlbum? = try {
        gate.await()
        val response = client.get("${baseUrl.trimEnd('/')}/album/$albumId")
        if (!response.status.isSuccess()) {
            TrackLog.w(TAG, "Deezer album $albumId returned ${response.status.value}", song.videoId)
            null
        } else {
            json.decodeFromString<DeezerAlbum>(response.bodyAsText())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "Deezer album $albumId failed", e, song.videoId)
        null
    }

    private companion object {
        const val TAG = "Enrichment"
    }
}
