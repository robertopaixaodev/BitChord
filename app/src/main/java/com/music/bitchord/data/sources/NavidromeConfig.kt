package com.music.bitchord.data.sources

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The endpoint and credentials for one Navidrome/Subsonic library.
 *
 * The password is kept here only in memory; [com.music.bitchord.data.settings.AppSettings]
 * mirrors it from the encrypted [com.music.bitchord.auth.AuthStore].
 */
data class NavidromeConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
) {
    val rootUrl: String
        get() = baseUrl.trim().trimEnd('/').removeSuffix("/rest").trimEnd('/')

    private val parsedHost: String
        get() = rootUrl.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" } ?: rootUrl

    /** Stable across restarts, and unique per server + user. */
    val configId: String
        get() = "navidrome:$parsedHost:${username.trim()}"

    val displayName: String
        get() = rootUrl.toHttpUrlOrNull()?.host ?: "Navidrome"

    val isComplete: Boolean
        get() = rootUrl.isNotBlank() && username.isNotBlank()
}
