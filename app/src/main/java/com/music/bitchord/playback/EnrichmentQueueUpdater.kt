package com.music.bitchord.playback

import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.music.bitchord.data.model.Song
import java.util.Locale

/**
 * One queue row, in the terms the updater decides on. Kept separate from
 * [MediaItem] so the decision table can be tested on the JVM, where Bundle is
 * a stub and extras cannot be read back.
 */
internal data class EnrichmentQueueItem(
    val index: Int,
    val mediaId: String,
    val queueEntryId: String?,
    val uri: String?,
)

/**
 * Applies one enriched [Song] to the current row and the rows just ahead of it.
 *
 * Future rows are metadata-only replacements and are never playing, so they do
 * not need playback guards. The current row is only replaced when its source is
 * a progressive file and no quality swap is in flight: Media3 reuses a
 * ProgressiveMediaSource when only the metadata changed, but that guarantee
 * does not extend to HLS/DASH or to a source being replaced by QualityUpgrade.
 *
 * Nothing here calls prepare() or seekTo(). The item's URI, media id and queue
 * entry id are unchanged; only the nine identity extras are added.
 */
class EnrichmentQueueUpdater(
    private val player: Player,
    private val isSwapping: () -> Boolean = { false },
) {

    fun updateCurrentAndNext(enriched: Song, lookahead: Int = 8): Int {
        Log.d("EnrichmentDebug", "Updating queue for: ${enriched.title}")
        if (enriched.videoId.isBlank()) return 0
        val count = player.mediaItemCount
        val currentIndex = player.currentMediaItemIndex
        val window = enrichmentWindow(currentIndex, count, lookahead)
        if (window.isEmpty()) return 0
        val items = window.map { index ->
            val item = player.getMediaItemAt(index)
            EnrichmentQueueItem(
                index = index,
                mediaId = item.mediaId,
                queueEntryId = item.queueEntryId,
                uri = item.localConfiguration?.uri?.toString(),
            )
        }
        val targets = planEnrichmentTargets(
            currentIndex = 0,
            items = items,
            enriched = enriched,
            lookahead = lookahead,
            isSwapping = isSwapping(),
        )
        var updated = 0
        for (index in targets) {
            if (tryUpdateItemAt(index, enriched)) updated++
        }
        return updated
    }

    fun tryUpdateItemAt(index: Int, enriched: Song): Boolean {
        if (enriched.videoId.isBlank()) return false
        val count = player.mediaItemCount
        if (index !in 0 until count) return false
        val item = player.getMediaItemAt(index)
        if (!matches(item.mediaId, item.queueEntryId, enriched)) {
            if (enriched.queueEntryId != null && item.queueEntryId != enriched.queueEntryId) {
                Log.d("EnrichmentDebug", "Updater: rejected queueEntryId mismatch")
            }
            return false
        }
        val updatedItem = item.withIdentity(enriched)
        if (index == player.currentMediaItemIndex &&
            !canReplaceCurrent(item, updatedItem, enriched)
        ) {
            return false
        }
        player.replaceMediaItem(index, updatedItem)
        return true
    }

    private fun matches(mediaId: String, queueEntryId: String?, enriched: Song): Boolean =
        if (enriched.queueEntryId != null) {
            queueEntryId == enriched.queueEntryId
        } else {
            mediaId == enriched.videoId
        }

    private fun canReplaceCurrent(
        item: MediaItem,
        updatedItem: MediaItem,
        enriched: Song,
    ): Boolean {
        if (isSwapping()) {
            Log.d("EnrichmentDebug", "Updater: rejected because isSwapping is true")
            return false
        }
        if (item.mediaId != enriched.videoId) return false
        if (item.queueEntryId != enriched.queueEntryId) {
            Log.d("EnrichmentDebug", "Updater: rejected queueEntryId mismatch")
            return false
        }
        if (item.localConfiguration?.uri != updatedItem.localConfiguration?.uri) return false
        val uri = item.localConfiguration?.uri?.toString() ?: return false
        Log.d("EnrichmentDebug", "Updater: checking URI ${item.localConfiguration?.uri}")
        if (!isProgressiveUri(uri)) {
            Log.d("EnrichmentDebug", "Updater: rejected non-progressive URI")
            return false
        }
        return true
    }
}

/**
 * The decision half of [EnrichmentQueueUpdater]. `currentIndex` is an index
 * into [items], not an absolute player index; the returned values are
 * [EnrichmentQueueItem.index] values, ready for replaceMediaItem.
 */
internal fun planEnrichmentTargets(
    currentIndex: Int,
    items: List<EnrichmentQueueItem>,
    enriched: Song,
    lookahead: Int,
    isSwapping: Boolean,
): List<Int> {
    if (currentIndex !in items.indices) return emptyList()
    val end = minOf(currentIndex + lookahead.coerceAtLeast(0), items.lastIndex)
    val targets = mutableListOf<Int>()
    for (relative in currentIndex..end) {
        val item = items[relative]
        val matches = if (enriched.queueEntryId != null) {
            item.queueEntryId == enriched.queueEntryId
        } else {
            item.mediaId == enriched.videoId
        }
        if (!matches) continue
        if (relative == currentIndex) {
            if (isSwapping) continue
            if (item.mediaId != enriched.videoId) continue
            if (item.queueEntryId != enriched.queueEntryId) continue
            val uri = item.uri ?: continue
            if (!isProgressiveUri(uri)) continue
        }
        targets += item.index
    }
    return targets
}

internal fun isProgressiveUri(uri: String): Boolean {
    val lower = uri.lowercase(Locale.ROOT)
    if (lower.startsWith("bitchord://")) return true
    if (lower.startsWith("rtsp://")) return false
    val path = lower.substringBefore('?').substringBefore('#')
    if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return false
    if (lower.contains("googlevideo.com")) return true
    val extension = path.substringAfterLast('/', path)
        .substringAfterLast('.', "")
    return extension in PROGRESSIVE_EXTENSIONS || extension == "webm"
}

/** The current row plus at most [lookahead] ahead; empty when the queue is gone. */
internal fun enrichmentWindow(
    currentIndex: Int,
    itemCount: Int,
    lookahead: Int,
): IntRange {
    if (currentIndex !in 0 until itemCount) return IntRange.EMPTY
    val end = minOf(currentIndex + lookahead.coerceAtLeast(0), itemCount - 1)
    return currentIndex..end
}

private val PROGRESSIVE_EXTENSIONS =
    setOf("mp3", "flac", "m4a", "ogg", "opus", "wav", "aac")

private fun MediaItem.withIdentity(enriched: Song): MediaItem {
    val extras = Bundle(mediaMetadata.extras ?: Bundle())
    enriched.isrc?.let { extras.putString(SongExtraKeys.ISRC, it) }
    enriched.mbid?.let { extras.putString(SongExtraKeys.MBID, it) }
    enriched.trackNumber?.let { extras.putInt(SongExtraKeys.TRACK_NUMBER, it) }
    enriched.discNumber?.let { extras.putInt(SongExtraKeys.DISC_NUMBER, it) }
    enriched.albumArtist?.let { extras.putString(SongExtraKeys.ALBUM_ARTIST, it) }
    enriched.genre?.let { extras.putString(SongExtraKeys.GENRE, it) }
    enriched.year?.let { extras.putInt(SongExtraKeys.YEAR, it) }
    enriched.label?.let { extras.putString(SongExtraKeys.LABEL, it) }
    enriched.composer?.let { extras.putString(SongExtraKeys.COMPOSER, it) }
    val metadata = mediaMetadata.buildUpon().setExtras(extras).build()
    return buildUpon().setMediaMetadata(metadata).build()
}
