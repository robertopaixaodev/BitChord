package com.music.bitchord.data.enrichment

import com.music.bitchord.data.Http
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Shared HTTP and JSON plumbing for the enrichment providers.
 *
 * Ktor is already the app's HTTP stack (with the OkHttp engine), so this reuses
 * it rather than adding a second client. The client is deliberately not a
 * singleton owned by a screen: it lives for the process and is closed by the
 * process.
 */
internal object EnrichmentHttp {
    val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            // Reuse the app's one OkHttp client (Http.client) so DNS, address
            // family and connection pooling stay shared with playback.
            engine { preconfigured = Http.client }
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 10_000
            }
            expectSuccess = false
        }
    }
}

/**
 * A simple serial gate. MusicBrainz asks for at most one request per second;
 * Deezer has no published limit but the same gate keeps a burst from looking
 * like abuse.
 */
internal class RequestGate(private val minIntervalMs: Long) {
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    suspend fun await() {
        mutex.withLock {
            val elapsed = System.currentTimeMillis() - lastRequestAt
            val waitMs = minIntervalMs - elapsed
            if (lastRequestAt > 0L && waitMs > 0L) delay(waitMs)
            lastRequestAt = System.currentTimeMillis()
        }
    }
}

/**
 * MusicBrainz rejects a generic User-Agent. The default names the app and its
 * public project page; callers can replace it (there is no personal address in
 * the code).
 */
object EnrichmentConfig {
    @Volatile
    var musicBrainzUserAgent: String =
        "BitChord/${com.music.bitchord.BuildConfig.VERSION_NAME} " +
            "(https://github.com/robertopaixaodev/BitChord)"
}
