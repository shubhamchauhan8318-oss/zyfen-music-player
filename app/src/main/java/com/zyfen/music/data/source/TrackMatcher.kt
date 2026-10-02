package com.zyfen.music.data.source

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * High-Precision Track Matching & Verification Engine.
 *
 * Rules:
 * 1. Exact track/ISRC match -> 100% confidence.
 * 2. Exact Title + Artist -> 100% confidence.
 * 3. Never match a remix/cover/slowed/reverb when original is requested.
 * 4. Never match a track with wrong artist or heavily divergent duration (e.g. 10m mashup vs 3m track).
 * 5. If confidence score < MIN_CONFIDENCE_THRESHOLD, REJECT the candidate (NEVER play wrong track).
 */
object TrackMatcher {

    private const val TAG = "TrackMatcher"
    const val MIN_CONFIDENCE_THRESHOLD = 65

    data class MatchScore(
        val confidence: Int, // 0..100
        val isExactMatch: Boolean,
        val titleSimilarity: Float,
        val artistSimilarity: Float,
        val durationMatches: Boolean,
        val reason: String
    ) {
        val isVerified: Boolean get() = confidence >= MIN_CONFIDENCE_THRESHOLD
    }

    /**
     * Normalizes a song title or artist name by stripping noise artifacts,
     * bracketed video descriptions (e.g. "[Official Music Video]"), and diacritics.
     */
    fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw.lowercase().trim()
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        s = Regex("""\p{InCombiningDiacriticalMarks}+""").replace(s, "")

        // Remove video artifacts
        s = Regex("""(?i)\s*\[(official|video|audio|lyrics|hd|4k|hq|visualizer|remastered|extended|music video|full song).*?\]""").replace(s, " ")
        s = Regex("""(?i)\s*\((official|video|audio|lyrics|hd|4k|hq|visualizer|remastered|extended|music video|full song).*?\)""").replace(s, " ")
        s = Regex("""(?i)\b(official music video|official video|lyric video|official audio|full song|original audio)\b""").replace(s, " ")
        s = s.replace(Regex("""[^a-z0-9\s]"""), " ")
        return s.split(Regex("""\s+""")).filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * Splits comma / feat / with / & separated artist lists into individual normalized artists.
     */
    fun extractArtists(rawArtist: String): List<String> {
        val raw = rawArtist.lowercase()
            .replace("feat.", ",")
            .replace("ft.", ",")
            .replace("featuring", ",")
            .replace("&", ",")
            .replace(" x ", ",")
            .replace(" / ", ",")
            .replace(";", ",")
        return raw.split(",")
            .map { normalize(it) }
            .filter { it.isNotBlank() && it !in listOf("various artists", "unknown artist", "unknown", "va", "spotify") }
    }

    /**
     * Computes Levenshtein edit distance between two strings.
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prev + cost)
                prev = temp
            }
        }
        return dp[s2.length]
    }

    /**
     * Normalized similarity score between 0.0f (no match) and 1.0f (exact match).
     */
    fun similarity(s1: String, s2: String): Float {
        val n1 = normalize(s1)
        val n2 = normalize(s2)
        if (n1 == n2) return 1.0f
        if (n1.isEmpty() || n2.isEmpty()) return 0.0f
        val maxLen = max(n1.length, n2.length)
        if (maxLen == 0) return 1.0f
        val dist = levenshteinDistance(n1, n2)
        return (1.0f - (dist.toFloat() / maxLen.toFloat())).coerceIn(0.0f, 1.0f)
    }

    /**
     * Checks if any target artist matches any candidate artist with token overlap.
     */
    fun artistMatches(targetArtist: String, candidateArtist: String): Float {
        val targetList = extractArtists(targetArtist)
        val candList = extractArtists(candidateArtist)
        if (targetList.isEmpty() || candList.isEmpty()) return 0.5f // Neutral if missing

        var maxSim = 0.0f
        for (t in targetList) {
            for (c in candList) {
                if (t == c) return 1.0f
                if (t.contains(c) || c.contains(t)) {
                    val sim = min(t.length, c.length).toFloat() / max(t.length, c.length).toFloat()
                    if (sim > maxSim) maxSim = sim
                }
                val sim = similarity(t, c)
                if (sim > maxSim) maxSim = sim
            }
        }
        return maxSim
    }

    /**
     * Evaluates candidate track against requested target track.
     */
    fun evaluate(
        targetTitle: String,
        targetArtist: String,
        targetDurationMs: Long,
        candTitle: String,
        candArtist: String,
        candDurationMs: Long,
        candAlbum: String = "",
        targetAlbum: String = ""
    ): MatchScore {
        val normTargetTitle = normalize(targetTitle)
        val normCandTitle = normalize(candTitle)

        // 1. Title Similarity
        var titleSim = similarity(normTargetTitle, normCandTitle)
        if (normCandTitle.contains(normTargetTitle) || normTargetTitle.contains(normCandTitle)) {
            val containsSim = min(normTargetTitle.length, normCandTitle.length).toFloat() /
                max(normTargetTitle.length, normCandTitle.length).toFloat()
            titleSim = max(titleSim, containsSim.coerceAtLeast(0.75f))
        }

        // 2. Artist Similarity
        val artistSim = artistMatches(targetArtist, candArtist)

        // 3. Junk & Non-Music Rejection
        val candLower = candTitle.lowercase()
        val isJunk = candLower.contains("podcast") || candLower.contains("interview") ||
            candLower.contains("reaction") || candLower.contains("reaction to") ||
            candLower.contains("episode") || candLower.contains("review") ||
            candLower.contains("status") || candLower.contains("shorts") ||
            candLower.contains("satsang") || candLower.contains("katha") || candLower.contains("natok")

        if (isJunk) {
            return MatchScore(
                confidence = 0,
                isExactMatch = false,
                titleSimilarity = titleSim,
                artistSimilarity = artistSim,
                durationMatches = false,
                reason = "Rejected non-music/junk candidate ($candTitle)"
            )
        }

        // 4. Remix / Cover / Slowed / Mashup Filter
        val targetIsRemix = normTargetTitle.contains("remix") || normTargetTitle.contains("slowed") ||
            normTargetTitle.contains("reverb") || normTargetTitle.contains("lofi") || normTargetTitle.contains("cover")
        val candIsRemix = normCandTitle.contains("remix") || normCandTitle.contains("slowed") ||
            normCandTitle.contains("reverb") || normCandTitle.contains("lofi") || normCandTitle.contains("cover") ||
            normCandTitle.contains("mashup") || normCandTitle.contains("speed up") || normCandTitle.contains("sped up")

        if (candIsRemix && !targetIsRemix) {
            return MatchScore(
                confidence = 10,
                isExactMatch = false,
                titleSimilarity = titleSim,
                artistSimilarity = artistSim,
                durationMatches = false,
                reason = "Rejected remix/cover when original requested"
            )
        }

        // 5. Duration Verification
        var durationOk = true
        var durationPenalty = 0
        if (targetDurationMs > 10_000L && candDurationMs > 10_000L) {
            val diff = abs(targetDurationMs - candDurationMs)
            val percentDiff = (diff.toDouble() / targetDurationMs.toDouble()) * 100.0
            if (percentDiff > 35.0 && diff > 30_000L) {
                durationOk = false
                durationPenalty = 40 // Heavy penalty for mismatching duration (e.g. 10min video vs 3min song)
            } else if (percentDiff > 20.0 && diff > 15_000L) {
                durationPenalty = 15
            }
        }

        // 6. Calculate Overall Confidence (0..100)
        var confidence = (titleSim * 50f + artistSim * 40f).toInt()
        if (durationOk && targetDurationMs > 0L) confidence += 10
        confidence -= durationPenalty

        // Album bonus
        if (targetAlbum.isNotBlank() && candAlbum.isNotBlank()) {
            val albumSim = similarity(targetAlbum, candAlbum)
            if (albumSim >= 0.8f) confidence = min(100, confidence + 10)
        }

        val isExact = normTargetTitle == normCandTitle && artistSim >= 0.9f && durationOk
        if (isExact) confidence = 100

        val finalScore = confidence.coerceIn(0, 100)
        return MatchScore(
            confidence = finalScore,
            isExactMatch = isExact,
            titleSimilarity = titleSim,
            artistSimilarity = artistSim,
            durationMatches = durationOk,
            reason = if (finalScore >= MIN_CONFIDENCE_THRESHOLD) "Verified track match ($finalScore%)"
            else "Match confidence too low ($finalScore% < $MIN_CONFIDENCE_THRESHOLD%)"
        )
    }

    fun logMatch(requested: TrackIdentity, candidate: TrackIdentity, score: MatchScore) {
        if (score.isVerified) {
            Log.i(TAG, "MATCH VERIFIED [${score.confidence}%]: '${requested.title}' by '${requested.artist}' -> '${candidate.title}' by '${candidate.artist}' (src=${candidate.sourceProvider})")
        } else {
            Log.w(TAG, "TRACK MATCH FAILURE [${score.confidence}%]: '${requested.title}' by '${requested.artist}' vs '${candidate.title}' by '${candidate.artist}' — ${score.reason}")
        }
    }
}
