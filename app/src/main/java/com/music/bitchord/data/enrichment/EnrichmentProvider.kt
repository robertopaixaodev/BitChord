package com.music.bitchord.data.enrichment

import com.music.bitchord.data.model.Song
import java.util.Locale

/**
 * The identity fields a provider can contribute. A provider returns only what
 * it actually knows; the service merges the non-null values without overwriting
 * anything the song already carried.
 */
data class PartialEnrichment(
    val isrc: String? = null,
    val mbid: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val label: String? = null,
    val composer: String? = null,
    val provider: String,
) {
    val hasAny: Boolean
        get() = isrc != null || mbid != null || trackNumber != null ||
            discNumber != null || albumArtist != null || genre != null ||
            year != null || label != null || composer != null
}

interface EnrichmentProvider {
    val name: String
    suspend fun enrich(song: Song): PartialEnrichment?
}

internal fun Song.mergedWith(partial: PartialEnrichment): Song = copy(
    // The song wins wherever it already has a value; the provider only fills
    // gaps. That keeps a catalogue's own metadata from being replaced by a
    // looser search hit.
    isrc = isrc ?: partial.isrc,
    mbid = mbid ?: partial.mbid,
    trackNumber = trackNumber ?: partial.trackNumber,
    discNumber = discNumber ?: partial.discNumber,
    albumArtist = albumArtist ?: partial.albumArtist,
    genre = genre ?: partial.genre,
    year = year ?: partial.year,
    label = label ?: partial.label,
    composer = composer ?: partial.composer,
)

internal fun Song.hasCompleteIdentity(): Boolean =
    isrc != null && mbid != null && trackNumber != null && discNumber != null &&
        albumArtist != null && genre != null && year != null &&
        label != null && composer != null

/** True when at least one of the nine enrichment fields is already known. */
internal fun Song.hasAnyIdentity(): Boolean =
    isrc != null || mbid != null || trackNumber != null || discNumber != null ||
        albumArtist != null || genre != null || year != null ||
        label != null || composer != null

/** The hook's content guard: a row with any identity, or no id, is left alone. */
internal fun Song.needsEnrichment(): Boolean =
    videoId.isNotBlank() && !hasAnyIdentity()

/**
 * Provider search hits are loose. Title and artist are compared after removing
 * punctuation and case, and one side may contain the other: catalogues append
 * "Remastered", "Live", or a featured credit that the queue row does not have.
 */
internal fun normalizeMatchText(value: String): String =
    value.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

internal fun matchesTitleAndArtist(
    targetTitle: String,
    targetArtist: String,
    candidateTitle: String?,
    candidateArtist: String?,
): Boolean {
    val title = normalizeMatchText(targetTitle)
    val candidate = normalizeMatchText(candidateTitle.orEmpty())
    if (title.isEmpty() || candidate.isEmpty()) return false
    if (!(candidate.contains(title) || title.contains(candidate))) return false

    val artist = normalizeMatchText(targetArtist)
    val candidateCredit = normalizeMatchText(candidateArtist.orEmpty())
    if (artist.isEmpty() || candidateCredit.isEmpty()) return true
    return candidateCredit.contains(artist) || artist.contains(candidateCredit)
}

internal fun releaseYear(releaseDate: String?): Int? =
    releaseDate?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1000..9999 }

