package com.music.bitchord.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class LocalPlaylist(
    val id: String,
    val title: String,
    val songs: List<Song>,
) {
    val browseId: String get() = "local:playlist:$id"
}

object LocalPlaylistStore {
    private const val PREF_NAME = "bitchord_local_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"

    private lateinit var prefs: SharedPreferences

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        if (!this::prefs.isInitialized) return
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return
        runCatching {
            val list = mutableListOf<LocalPlaylist>()
            val jsonArray = JSONArray(raw)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getString("id")
                val title = obj.getString("title")
                val songsArray = obj.getJSONArray("songs")
                val songs = mutableListOf<Song>()
                for (j in 0 until songsArray.length()) {
                    val sObj = songsArray.getJSONObject(j)
                    val song = LocalPlaylistSongCodec.decode(sObj.toValueMap())
                    songs.add(song)
                }
                list.add(LocalPlaylist(id, title, songs))
            }
            _playlists.value = list
        }
    }

    fun savePlaylist(title: String, songs: List<Song>): LocalPlaylist {
        val id = "sp_local_" + System.currentTimeMillis()
        val playlist = LocalPlaylist(id = id, title = title, songs = songs)
        val updated = listOf(playlist) + _playlists.value.filterNot { it.id == id }
        _playlists.value = updated
        persist(updated)
        return playlist
    }

    fun deletePlaylist(id: String) {
        val updated = _playlists.value.filterNot { it.id == id || it.browseId == id || it.id == id.removePrefix("local:playlist:") }
        _playlists.value = updated
        persist(updated)
    }

    fun renamePlaylist(id: String, newTitle: String) {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        val updated = _playlists.value.map {
            if (it.id == cleanId || it.id == id || it.browseId == id) {
                it.copy(title = newTitle)
            } else it
        }
        _playlists.value = updated
        persist(updated)
    }

    fun getPlaylist(id: String): LocalPlaylist? {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        return _playlists.value.firstOrNull { it.id == cleanId || it.id == id || it.browseId == id }
    }

    private fun persist(list: List<LocalPlaylist>) {
        if (!this::prefs.isInitialized) return
        runCatching {
            val jsonArray = JSONArray()
            for (playlist in list) {
                val pObj = JSONObject()
                pObj.put("id", playlist.id)
                pObj.put("title", playlist.title)
                val songsArray = JSONArray()
                for (song in playlist.songs) {
                    val sObj = JSONObject()
                    for ((key, value) in LocalPlaylistSongCodec.encode(song)) {
                        if (value != null) sObj.put(key, value)
                    }
                    songsArray.put(sObj)
                }
                pObj.put("songs", songsArray)
                jsonArray.put(pObj)
            }
            prefs.edit().putString(KEY_PLAYLISTS, jsonArray.toString()).apply()
        }
    }
}

/** Keeps the manual JSON field list in one place, so persist and load cannot drift. */
internal object LocalPlaylistSongCodec {

    fun encode(song: Song): Map<String, Any?> = buildMap {
        put("videoId", song.videoId)
        put("title", song.title)
        put("artist", song.artist)
        song.thumbnailUrl?.let { put("thumbnailUrl", it) }
        song.durationText?.let { put("durationText", it) }
        song.artistId?.let { put("artistId", it) }
        song.albumId?.let { put("albumId", it) }
        song.albumName?.let { put("albumName", it) }
        put("isVideo", song.isVideo)
        song.isrc?.let { put("isrc", it) }
        song.mbid?.let { put("mbid", it) }
        song.trackNumber?.let { put("trackNumber", it) }
        song.discNumber?.let { put("discNumber", it) }
        song.albumArtist?.let { put("albumArtist", it) }
        song.genre?.let { put("genre", it) }
        song.year?.let { put("year", it) }
        song.label?.let { put("label", it) }
        song.composer?.let { put("composer", it) }
    }

    fun decode(values: Map<String, Any?>): Song = Song(
        videoId = values.string("videoId") ?: "",
        title = values.string("title") ?: "",
        artist = values.string("artist") ?: "",
        thumbnailUrl = values.string("thumbnailUrl"),
        durationText = values.string("durationText"),
        artistId = values.string("artistId"),
        albumId = values.string("albumId"),
        albumName = values.string("albumName"),
        isVideo = values["isVideo"] as? Boolean ?: false,
        isrc = values.string("isrc"),
        mbid = values.string("mbid"),
        trackNumber = values["trackNumber"] as? Int,
        discNumber = values["discNumber"] as? Int,
        albumArtist = values.string("albumArtist"),
        genre = values.string("genre"),
        year = values["year"] as? Int,
        label = values.string("label"),
        composer = values.string("composer"),
    )

    private fun Map<String, Any?>.string(key: String): String? =
        (this[key] as? String)?.takeIf { it.isNotBlank() && it != "null" }
}

private fun JSONObject.toValueMap(): Map<String, Any?> {
    val values = mutableMapOf<String, Any?>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        // optInt answers 0 for a missing key, and 0 is a real disc/track/year.
        // Presence is the only thing that distinguishes it from "not recorded".
        val value = when (key) {
            "trackNumber", "discNumber", "year" -> if (has(key)) optInt(key) else null
            else -> opt(key).takeUnless { it == JSONObject.NULL }
        }
        values[key] = value
    }
    return values
}
