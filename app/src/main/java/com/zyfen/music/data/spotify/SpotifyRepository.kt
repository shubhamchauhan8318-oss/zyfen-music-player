package com.zyfen.music.data.spotify

import android.util.Log
import com.zyfen.music.data.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import retrofit2.HttpException
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
data class ImportedSpotifyPlaylist(
    val name: String,
    val artworkUrl: String?,
    val spotifyUrl: String?,
    val tracks: List<SpotifyTrack>,
    val total: Int = tracks.size,
    val skipped: Int = 0,
    val truncated: Boolean = false
)

class SpotifyRepository(
    private val api: SpotifyService,
    private val session: SpotifySession,
    private val client: OkHttpClient,
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao
) {
    companion object {
        private const val TAG = "ZyfenImport"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        // Safety ceiling only (10_000 pages = 1M tracks) — NOT a track limit.
        // Spotify always moves `next` forward, so this never triggers in practice.
        private const val SANITY_MAX_PAGES = 10_000
        private const val BATCH = SpotifyService.PAGE_SIZE
    }

    suspend fun importPlaylist(
        linkOrId: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ImportedSpotifyPlaylist {
        val pid = SpotifyLinkParser.extractPlaylistId(linkOrId)
            ?: throw IllegalArgumentException("That doesn't look like a Spotify playlist link.")

        // Primary: spclient playlist v2 (full list, one call/page, token only —
        // no client-token needed) + per-track embed page for metadata. No track cap.
        tryImportViaSpclient(pid, onProgress)?.let { p ->
            persistIncremental(p, pid) { _, _ -> } // progress already reported during metadata fetch
            return p
        }

        tryImportViaApi(pid, onProgress)?.let { return it }

        val embed = fetchViaEmbed(pid)
            ?: throw IllegalStateException(
                "Could not fetch that playlist. Check the link and your internet."
            )
        val enriched = enrichMissingArtwork(embed)
        persistIncremental(enriched, pid, onProgress)
        return enriched
    }

    private val enrichClient: OkHttpClient by lazy {
        client.newBuilder()
            .callTimeout(8, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .connectTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Fills per-track artwork (unique thumbnail per song) using Spotify's public
     * oEmbed endpoint when the playlist fetch had no album images (embed fallback).
     * Only used on the embed path — the API path ships album images with each track.
     * Best-effort: failures leave the track with a gradient placeholder.
     */
    private suspend fun enrichMissingArtwork(p: ImportedSpotifyPlaylist): ImportedSpotifyPlaylist =
        withContext(Dispatchers.IO) {
            val need = p.tracks.filter {
                it.album?.images.isNullOrEmpty() && !it.id.isNullOrBlank()
            }
            if (need.isEmpty()) return@withContext p

            val sem = Semaphore(6)
            val results = need.map { t ->
                async {
                    sem.withPermit {
                        try {
                            val enc = URLEncoder.encode(
                                "https://open.spotify.com/track/${t.id}", "UTF-8"
                            )
                            val req = Request.Builder()
                                .url("https://open.spotify.com/oembed?url=$enc")
                                .header(
                                    "User-Agent",
                                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36"
                                )
                                .build()
                            enrichClient.newCall(req).execute().use { r ->
                                if (!r.isSuccessful) return@use null
                                val thumb = JSONObject(r.body!!.string()).optString("thumbnail_url")
                                if (thumb.isNullOrBlank()) null else thumb
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }.awaitAll()

            val byId = need.mapIndexedNotNull { i, t ->
                results[i]?.let { t.id!! to it }
            }.toMap()
            if (byId.isEmpty()) return@withContext p

            p.copy(tracks = p.tracks.map { t ->
                val url = byId[t.id]
                if (url == null) t else t.copy(
                    album = SpotifyAlbum(
                        name = t.album?.name ?: "",
                        images = listOf(SpotifyImage(url))
                    )
                )
            })
        }

    // ---------- source 0: spclient playlist v2 + per-track embed metadata ----------

    /**
     * Full playlist via spclient.wg.spotify.com/playlist/v2 (needs only the anonymous
     * access token — no client-token, which some networks reject). Returns every track
     * URI in one paginated sweep (length=500/page, no cap), then fetches each track's
     * metadata (title / artists / duration / artwork) from its public embed page
     * (__NEXT_DATA__), 6 at a time, with live progress. Duplicates are dropped from
     * the result and counted as skipped, matching Spotify's own list (unique tracks).
     */
    private suspend fun tryImportViaSpclient(
        pid: String,
        onProgress: (Int, Int) -> Unit
    ): ImportedSpotifyPlaylist? = withContext(Dispatchers.IO) {
        try {
            session.ensure()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "spclient: session failed: ${e.message}")
            return@withContext null
        }

        // 1) full URI list (paginated; page size 500 — server may return fewer)
        var total = -1
        var plName = ""
        var artUrl: String? = null
        val ids = ArrayList<String>()
        var offset = 0
        var pages = 0
        while (pages < SANITY_MAX_PAGES) {
            val body = spGet(
                "https://spclient.wg.spotify.com/playlist/v2/playlist/$pid" +
                    "?length=500&offset=$offset&markets=IN"
            ) ?: break
            val json = try { JSONObject(body) } catch (e: Exception) { break }
            if (total < 0) {
                total = json.optInt("length", -1)
                val attrs = json.optJSONObject("attributes")
                plName = attrs?.optString("name").orEmpty()
                artUrl = attrs?.optString("picture").orEmpty()
                    .takeIf { it.isNotBlank() }?.let { decodePictureGid(it) }
            }
            val items = json.optJSONObject("contents")?.optJSONArray("items") ?: break
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                if (total in 1..ids.size) break
                val uri = items.getJSONObject(i).optString("uri")
                if (uri.startsWith("spotify:track:")) ids.add(uri.substringAfterLast(':'))
            }
            offset += items.length()
            pages++
            if (total > 0 && offset >= total) break
        }
        if (ids.isEmpty()) {
            Log.w(TAG, "spclient: no track URIs (total=$total pages=$pages)")
            return@withContext null
        }

        val unique = ids.distinct()
        val dupSkips = ids.size - unique.size
        Log.i(
            TAG,
            "spclient list ok: name='$plName' total=$total uris=${ids.size} " +
                "unique=${unique.size} pages=$pages"
        )

        // 2) metadata per track from the public embed page — full pagination, no caps
        val done = AtomicInteger(0)
        onProgress(0, unique.size)
        val sem = Semaphore(6)
        val tracks = unique
            .map { id ->
                async {
                    sem.withPermit {
                        val t = fetchEmbedTrack(id)
                        onProgress(done.incrementAndGet(), unique.size)
                        t
                    }
                }
            }
            .awaitAll()
            .filterNotNull()

        if (tracks.isEmpty()) {
            Log.w(TAG, "spclient: all ${unique.size} embed metadata fetches failed")
            return@withContext null
        }
        val failed = unique.size - tracks.size
        Log.i(
            TAG,
            "spclient import done: total=$total imported=${tracks.size} " +
                "skipped=${dupSkips + failed} (dup=$dupSkips fetchFail=$failed)"
        )
        ImportedSpotifyPlaylist(
            name = plName.ifBlank { "Spotify playlist" },
            artworkUrl = artUrl,
            spotifyUrl = "https://open.spotify.com/playlist/$pid",
            tracks = tracks,
            total = total.takeIf { it > 0 } ?: tracks.size,
            skipped = dupSkips + failed,
            truncated = false
        )
    }

    /** GET with retry; returns body or null (never throws for HTTP errors). */
    private suspend fun spGet(url: String): String? {
        repeat(3) { attempt ->
            try {
                val auth = session.authHeader()
                if (auth == null) {
                    Log.w(TAG, "spclient: no access token")
                    return null
                }
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", auth)
                    .header("User-Agent", UA)
                    .header("Accept", "application/json")
                    .header("spotify-app-platform", "WebPlayer")
                    .build()
                client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) return r.body?.string()
                    Log.w(TAG, "spclient HTTP ${r.code} (attempt ${attempt + 1})")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "spclient failed (attempt ${attempt + 1}): ${e.message}")
            }
            if (attempt < 2) delay(1200L * (attempt + 1))
        }
        return null
    }

    /** Metadata for one track from its public embed page; null after 2 tries. */
    private suspend fun fetchEmbedTrack(id: String): SpotifyTrack? =
        withContext(Dispatchers.IO) {
            var lastErr: String? = null
            repeat(2) { attempt ->
                try {
                    val req = Request.Builder()
                        .url("https://open.spotify.com/embed/track/$id")
                        .header("User-Agent", UA)
                        .build()
                    val html = enrichClient.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) {
                            lastErr = "HTTP ${r.code}"
                            null
                        } else {
                            r.body?.string()
                        }
                    }
                    if (html != null) {
                        val parsed = parseEmbedTrack(id, html)
                        if (parsed != null) return@withContext parsed
                        lastErr = "no __NEXT_DATA__"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastErr = e.message
                }
                if (attempt == 0) delay(300L)
            }
            Log.w(TAG, "embed track fetch failed id=$id: $lastErr")
            null
        }

    private fun parseEmbedTrack(id: String, html: String): SpotifyTrack? {
        val m = Regex(
            "<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>",
            RegexOption.DOT_MATCHES_ALL
        ).find(html) ?: return null
        val ent = runCatching {
            JSONObject(m.groupValues[1]).getJSONObject("props").getJSONObject("pageProps")
                .getJSONObject("state").getJSONObject("data")
                .getJSONObject("entity")
        }.getOrNull() ?: return null
        val title = ent.optString("name").ifBlank { ent.optString("title") }
        if (title.isBlank()) return null
        val artists = ArrayList<SpotifyArtist>()
        ent.optJSONArray("artists")?.let { arr ->
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i).optString("name")
                if (n.isNotBlank()) artists.add(SpotifyArtist(n))
            }
        }
        if (artists.isEmpty()) artists.add(SpotifyArtist("Unknown Artist"))
        val artUrl = runCatching {
            val imgs = ent.getJSONObject("visualIdentity").getJSONArray("image")
            var best: String? = null
            var bestW = -1
            for (i in 0 until imgs.length()) {
                val o = imgs.getJSONObject(i)
                val w = o.optInt("maxWidth", 0)
                if (w >= bestW) {
                    bestW = w
                    best = o.optString("url")
                }
            }
            best?.takeIf { it.isNotBlank() }
        }.getOrNull()
        return SpotifyTrack(
            id = id,
            name = title,
            artists = artists,
            album = artUrl?.let { SpotifyAlbum(name = "", images = listOf(SpotifyImage(it))) },
            durationMs = ent.optLong("duration"),
            externalUrls = SpotifyExternalUrls("https://open.spotify.com/track/$id")
        )
    }

    /** Playlist cover: base64 GID -> hex -> i.scdn.co/image/{hex}. */
    private fun decodePictureGid(gid: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(gid)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        "https://i.scdn.co/image/$hex"
    }.getOrNull()

    // ---------- source 1: official Web API with full pagination ----------

    private suspend fun tryImportViaApi(
        pid: String,
        onProgress: (Int, Int) -> Unit
    ): ImportedSpotifyPlaylist? {
        try {
            session.ensure()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "session failed, will use embed fallback: ${e.message}")
            return null
        }

        val first = retry { api.getPlaylist(pid) }
        if (first == null) {
            Log.w(TAG, "playlist page 1 failed after retries — embed fallback")
            return null
        }

        val playlistId = "spotify_$pid"
        playlistDao.upsert(
            PlaylistEntity(
                id = playlistId,
                name = first.name,
                artworkUrl = first.images.firstOrNull()?.url,
                isSpotifyImport = true,
                spotifyId = pid,
                spotifyUrl = first.externalUrls?.spotify
            )
        )
        playlistDao.clearSongs(playlistId) // re-import refreshes; no stale/duplicate entries

        val total = first.tracks.total
        val all = ArrayList<SpotifyTrack>(total.coerceIn(0, 4096))
        val seen = HashSet<String>()
        var processed = 0
        var imported = 0
        var skipped = 0
        var position = 0

        suspend fun ingest(items: List<SpotifyPlaylistTrackItem>) {
            if (items.isEmpty()) return
            val songBatch = ArrayList<SongEntity>(items.size)
            val refBatch = ArrayList<PlaylistSongCrossRef>(items.size)
            for (item in items) {
                processed++
                val t = item.track
                if (t == null || t.name.isBlank()) { // removed / unavailable / invalid
                    skipped++
                    continue
                }
                val sid = if (t.id != null) "spotify_${t.id}" else "spotify_loc_$position"
                val entity = SongEntity(
                    id = sid,
                    title = t.name,
                    artist = t.artists.joinToString(", ") { it.name }.ifBlank { "Unknown Artist" },
                    album = t.album?.name ?: "",
                    durationMs = t.durationMs,
                    uri = "", // no local audio — stream or open on Spotify
                    artworkUri = t.album?.images?.firstOrNull()?.url,
                    isLocal = false,
                    spotifyId = t.id,
                    spotifyUrl = t.externalUrls?.spotify
                )
                songBatch += entity
                if (seen.add(sid)) refBatch += PlaylistSongCrossRef(playlistId, sid, position)
                all += t
                imported++
                position++
            }
            // incremental batch insert — page by page, never the whole list at once
            songDao.upsertAll(songBatch)
            if (refBatch.isNotEmpty()) playlistDao.addSongs(refBatch)
            onProgress(processed, total)
        }

        ingest(first.tracks.items)
        Log.i(TAG, "import '${first.name}': page 1 ok, total=$total processed=$processed")

        var next = first.tracks.next
        var pages = 1
        while (next != null && pages < SANITY_MAX_PAGES) {
            val url = next
            val page = retry { api.getPlaylistPage(url) }
            if (page == null) {
                val lost = (total - processed).coerceAtLeast(0)
                skipped += lost
                Log.w(TAG, "page ${pages + 1} failed after retries — keeping $imported tracks, $lost unscanned")
                break
            }
            ingest(page.items)
            next = page.next
            pages++
        }
        if (next == null && processed < total) skipped += total - processed

        Log.i(TAG, "import done: total=$total imported=$imported skipped=$skipped pages=$pages")
        return ImportedSpotifyPlaylist(
            name = first.name,
            artworkUrl = first.images.firstOrNull()?.url,
            spotifyUrl = first.externalUrls?.spotify,
            tracks = all,
            total = total,
            skipped = skipped,
            truncated = false
        )
    }

    /** Retries retryable failures (429 / 5xx / timeouts) with backoff; gives up -> null. */
    private suspend fun <T> retry(times: Int = 3, block: suspend () -> T): T? {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = (e as? HttpException)?.code()
                val retryable = code == null || code == 429 || code >= 500
                Log.w(TAG, "attempt $attempt failed${code?.let { " (HTTP $it)" } ?: ""}: ${e.message}")
                if (!retryable || attempt >= times) return null
                delay(1500L * attempt)
            }
        }
    }

    // ---------- source 2: Spotify embed page (fallback, ~100 tracks) ----------

    private suspend fun fetchViaEmbed(pid: String): ImportedSpotifyPlaylist? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://open.spotify.com/embed/playlist/$pid")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                    )
                    .build()
                val html = client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null
                    r.body?.string() ?: return@withContext null
                }
                val m = Regex(
                    "<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>",
                    RegexOption.DOT_MATCHES_ALL
                ).find(html) ?: return@withContext null
                val root = JSONObject(m.groupValues[1])
                val entity = root.getJSONObject("props").getJSONObject("pageProps")
                    .getJSONObject("state").getJSONObject("data")
                    .getJSONObject("entity")

                val name = entity.optString("name").ifBlank { entity.optString("title") }
                if (name.isBlank()) return@withContext null

                val art = runCatching {
                    entity.getJSONObject("coverArt").getJSONArray("sources")
                        .getJSONObject(0).getString("url")
                }.getOrNull()

                val openUrl = "https://open.spotify.com/playlist/$pid"

                val arr = entity.optJSONArray("trackList") ?: return@withContext null
                val tracks = (0 until arr.length()).mapNotNull { i ->
                    val t = arr.getJSONObject(i)
                    if (t.optBoolean("isPlayable") == false) return@mapNotNull null
                    val trackUri = t.optString("uri")
                    val trackId = trackUri.substringAfterLast(":").ifBlank { null }
                        ?: return@mapNotNull null
                    val openTrack = if (trackUri.startsWith("spotify:track:"))
                        "https://open.spotify.com/track/$trackId" else null
                    SpotifyTrack(
                        id = trackId,
                        name = t.optString("title"),
                        artists = listOf(SpotifyArtist(t.optString("subtitle").ifBlank { "Unknown Artist" })),
                        album = null,
                        durationMs = t.optLong("duration"),
                        externalUrls = SpotifyExternalUrls(openTrack)
                    )
                }
                if (tracks.isEmpty()) null else ImportedSpotifyPlaylist(
                    name = name,
                    artworkUrl = art,
                    spotifyUrl = openUrl.ifBlank { null },
                    tracks = tracks,
                    total = tracks.size,
                    skipped = 0,
                    truncated = tracks.size >= 100
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "embed fetch failed: ${e.message}")
                null
            }
        }

    // ---------- page-by-page persistence (shared by embed path) ----------

    private suspend fun persistIncremental(
        p: ImportedSpotifyPlaylist,
        pid: String,
        onProgress: (Int, Int) -> Unit
    ) {
        val playlistId = "spotify_$pid"
        playlistDao.upsert(
            PlaylistEntity(
                id = playlistId,
                name = p.name,
                artworkUrl = p.artworkUrl,
                isSpotifyImport = true,
                spotifyId = pid,
                spotifyUrl = p.spotifyUrl
            )
        )
        playlistDao.clearSongs(playlistId)

        val total = p.tracks.size
        var processed = 0
        var position = 0
        val seen = HashSet<String>()
        for (chunk in p.tracks.chunked(BATCH)) {
            val songBatch = ArrayList<SongEntity>(chunk.size)
            val refBatch = ArrayList<PlaylistSongCrossRef>(chunk.size)
            for (t in chunk) {
                if (t.name.isBlank()) { processed++; continue }
                val sid = if (t.id != null) "spotify_${t.id}" else "spotify_loc_$position"
                songBatch += SongEntity(
                    id = sid,
                    title = t.name,
                    artist = t.artists.joinToString(", ") { it.name }.ifBlank { "Unknown Artist" },
                    album = t.album?.name ?: "",
                    durationMs = t.durationMs,
                    uri = "",
                    artworkUri = t.album?.images?.firstOrNull()?.url,
                    isLocal = false,
                    spotifyId = t.id,
                    spotifyUrl = t.externalUrls?.spotify
                )
                if (seen.add(sid)) refBatch += PlaylistSongCrossRef(playlistId, sid, position)
                processed++
                position++
            }
            songDao.upsertAll(songBatch)
            if (refBatch.isNotEmpty()) playlistDao.addSongs(refBatch)
            onProgress(processed, total)
        }
        Log.i(TAG, "embed import done: total=$total processed=$processed")
    }
}
