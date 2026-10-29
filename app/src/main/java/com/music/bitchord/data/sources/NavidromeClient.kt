package com.music.bitchord.data.sources

import com.music.bitchord.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.UUID

/**
 * OpenSubsonic is inconsistent about these two fields: classic Subsonic sends
 * a string, OpenSubsonic sends a one-element array. Either shape means the same
 * identifier, so collapse an array to its first value before the String
 * serializer sees it.
 */
internal object StringOrListSerializer : JsonTransformingSerializer<String>(String.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) {
            element.firstOrNull() ?: JsonPrimitive("")
        } else {
            element
        }
}

@Serializable
internal data class NavidromeSong(
    val id: String = "",
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val duration: Int? = null,
    val track: Int? = null,
    @SerialName("discNumber") val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    @Serializable(with = StringOrListSerializer::class)
    val isrc: String? = null,
    @SerialName("musicBrainzId")
    @Serializable(with = StringOrListSerializer::class)
    val musicBrainzId: String? = null,
    @SerialName("coverArt") val coverArt: String? = null,
    val suffix: String? = null,
    @SerialName("bitRate") val bitRate: Int? = null,
)

@Serializable
private data class NavidromeSearchResult(
    val song: List<NavidromeSong> = emptyList(),
    val album: List<NavidromeAlbum> = emptyList(),
    val artist: List<NavidromeArtist> = emptyList(),
)

@Serializable
internal data class NavidromeGenre(
    val value: String? = null,
    val name: String? = null,
    @SerialName("songCount") val songCount: Int? = null,
    @SerialName("albumCount") val albumCount: Int? = null,
) {
    val displayName: String get() = value ?: name.orEmpty()
}

@Serializable
private data class NavidromeGenreList(
    val genre: List<NavidromeGenre> = emptyList(),
)

@Serializable
internal data class NavidromeArtist(
    val id: String = "",
    val name: String = "",
    @SerialName("albumCount") val albumCount: Int? = null,
    @SerialName("coverArt") val coverArt: String? = null,
)

@Serializable
private data class NavidromeArtistIndex(
    val artist: List<NavidromeArtist> = emptyList(),
)

@Serializable
private data class NavidromeArtistList(
    val index: List<NavidromeArtistIndex> = emptyList(),
) {
    val flat: List<NavidromeArtist> get() = index.flatMap(NavidromeArtistIndex::artist)
}

@Serializable
private data class NavidromeSongs(
    val song: List<NavidromeSong> = emptyList(),
)

@Serializable
internal data class NavidromeAlbum(
    val id: String = "",
    val name: String = "",
    val artist: String? = null,
    @SerialName("artistId") val artistId: String? = null,
    @SerialName("coverArt") val coverArt: String? = null,
    @SerialName("songCount") val songCount: Int? = null,
    val year: Int? = null,
    val created: String? = null,
)

@Serializable
private data class NavidromeAlbumList(
    val album: List<NavidromeAlbum> = emptyList(),
)

@Serializable
internal data class NavidromeAlbumDetail(
    val id: String = "",
    val name: String = "",
    val artist: String? = null,
    val song: List<NavidromeSong> = emptyList(),
)

@Serializable
private data class NavidromeError(
    val code: Int? = null,
    val message: String? = null,
)

@Serializable
private data class NavidromeBody(
    val status: String? = null,
    val version: String? = null,
    val error: NavidromeError? = null,
    @SerialName("searchResult3") val searchResult: NavidromeSearchResult? = null,
    @SerialName("albumList2") val albumList: NavidromeAlbumList? = null,
    val genres: NavidromeGenreList? = null,
    val artists: NavidromeArtistList? = null,
    @SerialName("songsByGenre") val songsByGenre: NavidromeSongs? = null,
    val album: NavidromeAlbumDetail? = null,
)

@Serializable
private data class NavidromeEnvelope(
    @SerialName("subsonic-response") val response: NavidromeBody? = null,
)

internal class NavidromeException(message: String, val statusCode: Int? = null) : Exception(message)

/**
 * Minimal Subsonic client: token auth, ping, search3 and stream URL.
 *
 * Each URL carries its own salt and MD5(password + salt), as the protocol asks.
 * The URL is also what the player fetches later, so auth is part of the URL
 * rather than a header.
 */
class NavidromeClient(
    private val config: NavidromeConfig,
    private val client: OkHttpClient = Http.client,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        try {
            val body = get("ping.view")
            if (body.status != "ok") {
                SourceHealth.Rejected(body.error?.message ?: "Navidrome rejected the credentials")
            } else {
                SourceHealth.Ok(body.version?.let { "Navidrome $it" })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NavidromeException) {
            if (e.statusCode == 401 || e.statusCode == 403) {
                SourceHealth.Rejected(e.message ?: "Navidrome rejected the credentials")
            } else {
                SourceHealth.Unreachable(e.message ?: "Could not reach Navidrome")
            }
        } catch (e: Exception) {
            SourceHealth.Unreachable(e.message ?: "Could not reach Navidrome")
        }
    }

    internal suspend fun searchSongs(query: String, limit: Int): List<NavidromeSong> =
        withContext(Dispatchers.IO) {
            if (query.isBlank() || limit <= 0) return@withContext emptyList()
            val body = get(
                "search3.view",
                listOf(
                    "query" to query,
                    // The server's own cap is cheap; the UI's page was the
                    // thing that stopped at twelve. Ask for a full page and
                    // let the caller take what it needs.
                    "songCount" to maxOf(limit, NAVIDROME_SONG_COUNT).toString(),
                    "albumCount" to NAVIDROME_ALBUM_COUNT.toString(),
                    "artistCount" to "0",
                ),
            )
            body.searchResult?.song.orEmpty().take(limit)
        }

    internal suspend fun searchAlbums(query: String, limit: Int): List<NavidromeAlbum> =
        withContext(Dispatchers.IO) {
            if (query.isBlank() || limit <= 0) return@withContext emptyList()
            val body = get(
                "search3.view",
                listOf(
                    "query" to query,
                    "songCount" to "0",
                    "albumCount" to maxOf(limit, NAVIDROME_ALBUM_COUNT).toString(),
                    "artistCount" to "0",
                ),
            )
            body.searchResult?.album.orEmpty().take(limit)
        }

    internal suspend fun searchArtists(query: String, limit: Int): List<NavidromeArtist> =
        withContext(Dispatchers.IO) {
            if (query.isBlank() || limit <= 0) return@withContext emptyList()
            val body = get(
                "search3.view",
                listOf(
                    "query" to query,
                    "songCount" to "0",
                    "albumCount" to NAVIDROME_ALBUM_COUNT.toString(),
                    "artistCount" to maxOf(limit, NAVIDROME_ARTIST_COUNT).toString(),
                ),
            )
            body.searchResult?.artist.orEmpty()
                .distinctBy { it.name.lowercase().ifBlank { it.id } }
                .take(limit)
        }

    internal suspend fun albumSongs(albumId: String): List<NavidromeSong> =
        withContext(Dispatchers.IO) {
            if (albumId.isBlank()) return@withContext emptyList()
            get("getAlbum.view", listOf("id" to albumId)).album?.song.orEmpty()
        }

    internal suspend fun recentAlbums(limit: Int): List<NavidromeAlbum> =
        albumsByType("newest", limit)

    internal suspend fun frequentAlbums(limit: Int): List<NavidromeAlbum> =
        albumsByType("frequent", limit)

    internal suspend fun randomAlbums(limit: Int): List<NavidromeAlbum> =
        albumsByType("random", limit)

    internal suspend fun albumsByType(type: String, limit: Int): List<NavidromeAlbum> =
        withContext(Dispatchers.IO) {
            if (limit <= 0) return@withContext emptyList()
            val body = get(
                "getAlbumList2.view",
                listOf(
                    "type" to type,
                    "size" to limit.toString(),
                ),
            )
            body.albumList?.album.orEmpty().take(limit)
        }

    internal suspend fun genres(): List<NavidromeGenre> = withContext(Dispatchers.IO) {
        get("getGenres.view").genres?.genre.orEmpty()
    }

    internal suspend fun artists(): List<NavidromeArtist> = withContext(Dispatchers.IO) {
        get("getArtists.view").artists?.flat.orEmpty()
    }

    internal suspend fun songsByGenre(genre: String, limit: Int): List<NavidromeSong> =
        withContext(Dispatchers.IO) {
            if (genre.isBlank() || limit <= 0) return@withContext emptyList()
            val body = get(
                "getSongsByGenre.view",
                listOf(
                    "genre" to genre,
                    "count" to limit.toString(),
                    "offset" to "0",
                ),
            )
            body.songsByGenre?.song.orEmpty().take(limit)
        }

    fun streamUrl(songId: String, request: StreamRequest): String {
        val params = mutableListOf(
            "id" to songId,
            "maxBitRate" to when (request) {
                is StreamRequest.Capped -> request.maxKbps.toString()
                StreamRequest.Lossless, StreamRequest.Best -> "0"
            },
        )
        if (request is StreamRequest.Lossless) params += "format" to "raw"
        return endpoint("stream.view", params).toString()
    }

    fun coverUrl(coverArtId: String): String =
        endpoint(
            "getCoverArt.view",
            listOf("id" to coverArtId, "size" to "600"),
        ).toString()

    private suspend fun get(file: String, params: List<Pair<String, String>> = emptyList()): NavidromeBody {
        val url = endpoint(file, params)
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) {
                throw NavidromeException(
                    "Navidrome HTTP ${response.code}",
                    statusCode = response.code,
                )
            }
            val parsed = body?.let { json.decodeFromString<NavidromeEnvelope>(it) }?.response
                ?: throw NavidromeException("Navidrome returned an empty response")
            if (parsed.status != "ok") {
                val apiCode = parsed.error?.code
                throw NavidromeException(
                    parsed.error?.message ?: "Navidrome returned ${parsed.status ?: "an error"}",
                    statusCode = if (apiCode in setOf(40, 41, 50)) 401 else response.code,
                )
            }
            parsed
        }
    }

    private fun endpoint(file: String, params: List<Pair<String, String>>): HttpUrl {
        val base = config.rootUrl.toHttpUrlOrNull()
            ?: throw NavidromeException("Invalid Navidrome URL", statusCode = null)
        val salt = UUID.randomUUID().toString().replace("-", "").take(16)
        val token = md5(config.password + salt)
        val builder = base.newBuilder()
            .addPathSegment("rest")
            .addPathSegment(file)
            .addQueryParameter("u", config.username)
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", CLIENT_NAME)
            .addQueryParameter("f", "json")
            .addQueryParameter("s", salt)
            .addQueryParameter("t", token)
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "BitChord"
        const val NAVIDROME_SONG_COUNT = 100
        const val NAVIDROME_ALBUM_COUNT = 50
        const val NAVIDROME_ARTIST_COUNT = 20
    }
}
