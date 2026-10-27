package com.music.bitchord.data.enrichment

import android.content.Context

/**
 * Process-wide owner of the enrichment cache and service.
 *
 * The Application opens it next to the other stores, and PlaybackService reads
 * the same instance; there is no DI container in this app, so this follows the
 * AuthStore/singleton-object pattern already used at startup.
 */
object EnrichmentRuntime {

    private var instance: EnrichmentService? = null

    val service: EnrichmentService
        get() = instance ?: error("EnrichmentRuntime.init was not called")

    val serviceOrNull: EnrichmentService?
        get() = instance

    fun init(context: Context) {
        val cache = EnrichmentCache().apply { init(context) }
        instance = EnrichmentService(cache)
    }

    internal fun initForTesting(service: EnrichmentService) {
        instance = service
    }

    internal fun resetForTesting() {
        instance?.close()
        instance = null
    }
}
