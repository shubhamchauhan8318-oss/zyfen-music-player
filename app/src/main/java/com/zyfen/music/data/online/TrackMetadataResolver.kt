package com.zyfen.music.data.online

import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class ResolvedTrackMeta(
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val album: String? = null,
    val previewUrl: String? = null
)

/**
 * Universal metadata and high-resolution artwork resolver.
 * Accurately finds the real artist, official 600x600 album artwork, and cleans up dummy/corrupted metadata
 * (such as playlist names like ZYFEN_SP accidentally used as artists).
 */
object TrackMetadataResolver {
    private const val TAG = "TrackMetaResolver"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // In-memory cache for ultra-fast instant lookups (key: "title|artist")
    private val cache = LruCache<String, ResolvedTrackMeta>(500)
    private val artworkCache = LruCache<String, String>(1000)

    fun cleanArtist(rawArtist: String?): String {
        if (rawArtist.isNullOrBlank()) return ""
        val trimmed = rawArtist.trim()
        val lower = trimmed.lowercase()
        if (lower.contains("zyfen") || lower.contains("spotify") || lower.contains("playlist") ||
            lower == "unknown artist" || lower == "various artists" || lower.startsWith("sp_")
        ) {
            return ""
        }
        return trimmed
    }

    fun cleanTitle(rawTitle: String): String {
        return rawTitle
            .replace(Regex("""\s*#\d+\b"""), "") // Remove "#1", "#2" etc.
            .replace(Regex("""(?i)\s*\[official.*?]|\(official.*?\)"""), "")
            .trim()
    }

    suspend fun resolve(title: String, rawArtist: String?): ResolvedTrackMeta? = withContext(Dispatchers.IO) {
        val sanitizedTitle = cleanTitle(title)
        val sanitizedArtist = cleanArtist(rawArtist)
        val cacheKey = "${sanitizedTitle.lowercase()}|${sanitizedArtist.lowercase()}"

        cache.get(cacheKey)?.let { return@withContext it }

        // 1. iTunes Search API (Free, high-speed, 600x600 lossless artwork, exact artist metadata)
        val query = if (sanitizedArtist.isNotBlank()) "$sanitizedTitle $sanitizedArtist" else sanitizedTitle
        try {
            val enc = URLEncoder.encode(query, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$enc&media=music&entity=song&limit=5"
            val req = Request.Builder().url(url).header("User-Agent", "ZyfenMusic/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    if (!body.isNullOrBlank()) {
                        val root = JSONObject(body)
                        val results = root.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            for (i in 0 until results.length()) {
                                val r = results.getJSONObject(i)
                                val foundArtist = r.optString("artistName")
                                val foundTitle = r.optString("trackName")
                                val trackId = r.optString("trackId")

                                // Validate against requested artist if artist was provided
                                if (sanitizedArtist.isNotBlank()) {
                                    val sim = com.zyfen.music.data.source.TrackMatcher.artistMatches(sanitizedArtist, foundArtist)
                                    if (sim < 0.60f) {
                                        Log.d(TAG, "iTunes result rejected: '$foundTitle' by '$foundArtist' doesn't match requested artist '$sanitizedArtist'")
                                        continue
                                    }
                                }

                                val art = r.optString("artworkUrl100")
                                    .replace("100x100bb", "600x600bb")
                                    .ifBlank { null }
                                val album = r.optString("collectionName").ifBlank { null }
                                val preview = r.optString("previewUrl").ifBlank { null }

                                val meta = ResolvedTrackMeta(
                                    title = foundTitle.ifBlank { sanitizedTitle },
                                    artist = foundArtist.ifBlank { sanitizedArtist },
                                    artworkUrl = art,
                                    album = album,
                                    previewUrl = preview
                                )
                                cache.put(cacheKey, meta)
                                if (art != null) {
                                    artworkCache.put(cacheKey, art)
                                    com.zyfen.music.data.source.ArtworkCache.put("itunes", trackId, art)
                                }
                                return@withContext meta
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "iTunes lookup failed for '$query': ${e.message}")
        }

        null
    }

    suspend fun getArtworkUrl(title: String, rawArtist: String?): String? {
        val sanitizedTitle = cleanTitle(title)
        val sanitizedArtist = cleanArtist(rawArtist)
        val cacheKey = "${sanitizedTitle.lowercase()}|${sanitizedArtist.lowercase()}"
        artworkCache.get(cacheKey)?.let { return it }

        val meta = resolve(title, rawArtist)
        return meta?.artworkUrl
    }
}
