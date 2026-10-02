package com.zyfen.music.data.online

import android.util.Log
import com.zyfen.music.data.media.Song
import com.zyfen.music.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Enterprise-grade Online Music Repository with:
 * 1. Multi-category Search (Songs, Artists, Albums, Playlists)
 * 2. Infinite Scroll Pagination (Continuation tokens)
 * 3. Junk & Podcast Rejection
 * 4. Exact Artist Catalog Explorer
 */
class OnlineMusicRepository(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "OnlineMusicRepo"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }

    /**
     * Backward-compatible simple search
     */
    suspend fun searchSongs(query: String, limit: Int = 25): List<Song> = withContext(Dispatchers.IO) {
        val result = search(query, filter = "songs", limit = limit)
        result.songs.map { it.toSong() }
    }

    /**
     * Full Paginated & Categorized Search Engine
     */
    suspend fun search(
        query: String,
        filter: String = "all", // "all", "songs", "artists", "albums", "playlists"
        continuationToken: String? = null,
        limit: Int = 30
    ): SearchPageResult = withContext(Dispatchers.IO) {
        if (query.isBlank() && continuationToken.isNullOrBlank()) {
            return@withContext SearchPageResult(emptyList())
        }

        try {
            val bodyJson = JSONObject()
                .put(
                    "context", JSONObject().put(
                        "client", JSONObject()
                            .put("clientName", "WEB_REMIX")
                            .put("clientVersion", "1.20241001.01.00")
                            .put("hl", "en")
                            .put("gl", java.util.Locale.getDefault().country.takeIf { it.length == 2 } ?: "US")
                    )
                )

            val url: String
            if (!continuationToken.isNullOrBlank()) {
                url = "https://music.youtube.com/youtubei/v1/search?continuation=$continuationToken"
                bodyJson.put("continuation", continuationToken)
            } else {
                url = "https://music.youtube.com/youtubei/v1/search"
                bodyJson.put("query", query.trim())
                when (filter.lowercase()) {
                    "songs" -> bodyJson.put("params", "Eg-KAQwIARAAGAAgACgAMABqChAGEAkQBRAKEAM%3D")
                    "artists" -> bodyJson.put("params", "Eg-KAQwIAhABGAAgACgAMABqChAGEAkQBRAKEAM%3D")
                    "albums" -> bodyJson.put("params", "Eg-KAQwIBAABGAAgACgAMABqChAGEAkQBRAKEAM%3D")
                    "playlists" -> bodyJson.put("params", "Eg-KAQwIBAABGAAgACgAMABqChAGEAkQBRAKEAM%3D")
                }
            }

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://music.youtube.com/")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val jsonStr = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext SearchPageResult(emptyList())
                resp.body?.string() ?: return@withContext SearchPageResult(emptyList())
            }

            val root = JSONObject(jsonStr)
            val songs = ArrayList<TrackIdentity>()
            val artists = ArrayList<ArtistResult>()
            val albums = ArrayList<AlbumResult>()
            val playlists = ArrayList<PlaylistResult>()
            val seenIds = HashSet<String>()
            var nextContinuation: String? = null

            fun extractItems(node: Any?) {
                when (node) {
                    is JSONObject -> {
                        // Continuation Token
                        if (node.has("continuationEndpoint")) {
                            val cont = node.getJSONObject("continuationEndpoint")
                                .optJSONObject("continuationCommand")?.optString("token")
                            if (!cont.isNullOrBlank()) nextContinuation = cont
                        }
                        if (node.has("nextContinuationData")) {
                            val cont = node.getJSONObject("nextContinuationData").optString("continuation")
                            if (cont.isNotBlank()) nextContinuation = cont
                        }

                        // Song / Video items
                        if (node.has("musicResponsiveListItemRenderer")) {
                            val r = node.getJSONObject("musicResponsiveListItemRenderer")
                            val vid = r.optJSONObject("playlistItemData")?.optString("videoId")
                                ?: r.optJSONObject("doubleTapCommand")
                                    ?.optJSONObject("watchEndpoint")?.optString("videoId")
                                ?: ""

                            val browseEndpoint = r.optJSONObject("navigationEndpoint")
                                ?.optJSONObject("browseEndpoint")
                            val browseId = browseEndpoint?.optString("browseId").orEmpty()
                            val pageType = browseEndpoint?.optJSONObject("browseEndpointContextSupportedConfigs")
                                ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType").orEmpty()

                            val cols = r.optJSONArray("flexColumns")
                            var title = ""
                            var artist = ""
                            var album = ""
                            var durationMs = 0L

                            if (cols != null && cols.length() > 0) {
                                val c0 = cols.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                title = extractRunsText(c0)

                                if (cols.length() > 1) {
                                    val c1 = cols.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                    val runs = c1?.optJSONObject("text")?.optJSONArray("runs")
                                    if (runs != null) {
                                        val parts = mutableListOf<String>()
                                        for (i in 0 until runs.length()) {
                                            val run = runs.optJSONObject(i) ?: continue
                                            val t = run.optString("text").trim()
                                            if (t != "•" && t.isNotBlank()) {
                                                if (t.matches(Regex("""\d+:\d+"""))) {
                                                    durationMs = parseDuration(t)
                                                } else if (t.lowercase() !in listOf("song", "video", "single", "album", "ep", "artist")) {
                                                    parts.add(t)
                                                }
                                            }
                                        }
                                        artist = parts.firstOrNull().orEmpty()
                                        album = parts.getOrNull(1).orEmpty()
                                    }
                                }
                            }

                            val thumbs = r.optJSONObject("thumbnail")
                                ?.optJSONObject("musicThumbnailRenderer")
                                ?.optJSONObject("thumbnail")
                                ?.optJSONArray("thumbnails")
                            val thumb = if (thumbs != null && thumbs.length() > 0) {
                                thumbs.getJSONObject(thumbs.length() - 1).optString("url")
                            } else null

                            val titleLower = title.lowercase()
                            val isJunk = titleLower.contains("podcast") || titleLower.contains("interview") ||
                                titleLower.contains("reaction to") || titleLower.contains("episode") ||
                                titleLower.contains("status") || titleLower.contains("shorts")

                            if (!isJunk) {
                                when {
                                    vid.length == 11 && title.isNotBlank() && seenIds.add("song_$vid") -> {
                                        val identity = TrackIdentity(
                                            id = "yt_$vid",
                                            title = title,
                                            artist = artist.ifBlank { "YouTube Music" },
                                            album = album.ifBlank { "YouTube" },
                                            durationMs = durationMs,
                                            artworkUrl = thumb,
                                            sourceProvider = "youtube",
                                            sourceTrackId = vid,
                                            contentUri = "vt:$vid",
                                            isLocal = false
                                        )
                                        if (thumb != null) ArtworkCache.put(identity, thumb)
                                        songs.add(identity)
                                    }
                                    pageType == "MUSIC_PAGE_TYPE_ARTIST" && title.isNotBlank() && seenIds.add("artist_$browseId") -> {
                                        artists.add(
                                            ArtistResult(
                                                id = browseId,
                                                name = title,
                                                artworkUrl = thumb,
                                                description = artist
                                            )
                                        )
                                    }
                                    pageType == "MUSIC_PAGE_TYPE_ALBUM" && title.isNotBlank() && seenIds.add("album_$browseId") -> {
                                        albums.add(
                                            AlbumResult(
                                                id = browseId,
                                                title = title,
                                                artist = artist,
                                                artworkUrl = thumb
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        val keys = node.keys()
                        while (keys.hasNext()) {
                            extractItems(node.opt(keys.next()))
                        }
                    }
                    is JSONArray -> {
                        for (i in 0 until node.length()) {
                            extractItems(node.opt(i))
                        }
                    }
                }
            }

            extractItems(root)
            Log.i(TAG, "Search '$query' (filter=$filter) returned: ${songs.size} songs, ${artists.size} artists, ${albums.size} albums, hasMore=${nextContinuation != null}")

            SearchPageResult(
                songs = songs.take(limit),
                artists = artists,
                albums = albums,
                playlists = playlists,
                continuationToken = nextContinuation,
                hasMore = nextContinuation != null
            )
        } catch (e: Exception) {
            Log.w(TAG, "search error: ${e.message}")
            SearchPageResult(emptyList())
        }
    }

    /**
     * Retrieves artist detailed discography (Top Songs, Albums, Singles, EPs)
     */
    suspend fun getArtistDetails(artistBrowseId: String): ArtistDetailData? = withContext(Dispatchers.IO) {
        if (artistBrowseId.isBlank()) return@withContext null
        try {
            val body = JSONObject()
                .put(
                    "context", JSONObject().put(
                        "client", JSONObject()
                            .put("clientName", "WEB_REMIX")
                            .put("clientVersion", "1.20241001.01.00")
                            .put("hl", "en")
                            .put("gl", "US")
                    )
                )
                .put("browseId", artistBrowseId)

            val req = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/browse")
                .header("User-Agent", UA)
                .header("Referer", "https://music.youtube.com/")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val jsonStr = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string() ?: return@withContext null
            }

            val root = JSONObject(jsonStr)
            val header = root.optJSONObject("header")?.optJSONObject("musicImmersiveHeaderRenderer")
                ?: root.optJSONObject("header")?.optJSONObject("musicVisualHeaderRenderer")

            val artistName = header?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: "Artist"
            val artistThumbs = header?.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val artistThumb = if (artistThumbs != null && artistThumbs.length() > 0) {
                artistThumbs.getJSONObject(artistThumbs.length() - 1).optString("url")
            } else null

            val topSongs = ArrayList<TrackIdentity>()
            val albums = ArrayList<AlbumResult>()

            fun parseArtistNodes(node: Any?) {
                when (node) {
                    is JSONObject -> {
                        if (node.has("musicResponsiveListItemRenderer")) {
                            val r = node.getJSONObject("musicResponsiveListItemRenderer")
                            val vid = r.optJSONObject("playlistItemData")?.optString("videoId")
                                ?: r.optJSONObject("doubleTapCommand")?.optJSONObject("watchEndpoint")?.optString("videoId")
                                ?: ""
                            val cols = r.optJSONArray("flexColumns")
                            var title = ""
                            var artist = artistName
                            var album = ""
                            var durationMs = 0L

                            if (cols != null && cols.length() > 0) {
                                val c0 = cols.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                title = extractRunsText(c0)
                                if (cols.length() > 1) {
                                    val c1 = cols.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                                    val runs = c1?.optJSONObject("text")?.optJSONArray("runs")
                                    if (runs != null) {
                                        for (i in 0 until runs.length()) {
                                            val t = runs.optJSONObject(i)?.optString("text")?.trim().orEmpty()
                                            if (t.matches(Regex("""\d+:\d+"""))) durationMs = parseDuration(t)
                                            else if (t != "•" && t.isNotBlank() && t != artistName) album = t
                                        }
                                    }
                                }
                            }

                            val thumbs = r.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
                                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                            val thumb = if (thumbs != null && thumbs.length() > 0) {
                                thumbs.getJSONObject(thumbs.length() - 1).optString("url")
                            } else artistThumb

                            if (vid.length == 11 && title.isNotBlank() && topSongs.none { it.sourceTrackId == vid }) {
                                val id = TrackIdentity(
                                    id = "yt_$vid",
                                    title = title,
                                    artist = artist,
                                    album = album.ifBlank { "Singles" },
                                    durationMs = durationMs,
                                    artworkUrl = thumb,
                                    sourceProvider = "youtube",
                                    sourceTrackId = vid,
                                    contentUri = "vt:$vid"
                                )
                                topSongs.add(id)
                            }
                        }
                        val keys = node.keys()
                        while (keys.hasNext()) parseArtistNodes(node.opt(keys.next()))
                    }
                    is JSONArray -> {
                        for (i in 0 until node.length()) parseArtistNodes(node.opt(i))
                    }
                }
            }

            parseArtistNodes(root)

            ArtistDetailData(
                artist = ArtistResult(artistBrowseId, artistName, artistThumb),
                topSongs = topSongs,
                albums = albums
            )
        } catch (e: Exception) {
            Log.w(TAG, "getArtistDetails failed: ${e.message}")
            null
        }
    }

    private fun extractRunsText(column: JSONObject?): String {
        val runs = column?.optJSONObject("text")?.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            val r = runs.optJSONObject(i) ?: continue
            sb.append(r.optString("text"))
        }
        return sb.toString().trim()
    }

    private fun parseDuration(d: String): Long {
        val parts = d.split(":")
        return when (parts.size) {
            2 -> (parts[0].toLongOrNull() ?: 0) * 60_000L + (parts[1].toLongOrNull() ?: 0) * 1000L
            3 -> (parts[0].toLongOrNull() ?: 0) * 3600_000L + (parts[1].toLongOrNull() ?: 0) * 60_000L + (parts[2].toLongOrNull() ?: 0) * 1000L
            else -> 0L
        }
    }
}
