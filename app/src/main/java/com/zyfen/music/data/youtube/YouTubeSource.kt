package com.zyfen.music.data.youtube

import android.util.Log
import com.zyfen.music.data.media.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves a streamable audio URL for a song.
 *
 * Three-tier resilient playback architecture:
 *   1. YouTube innertube API (multiple clients: ANDROID_VR, ANDROID, IOS, WEB_REMIX)
 *   2. Multi-video search matching: if primary upload has playback restrictions, tries next search match
 *   3. Public Invidious / Piped proxy instances
 *
 * Excluded options (failed URLs / itags) are tracked per song so retries explore alternative formats.
 */
class YouTubeSource {

    data class Stream(val videoId: String, val url: String, val expiresAt: Long, val source: String = "?")

    @Volatile
    var lastError: String = "not attempted"
        private set

    private val scope = CoroutineScope(Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val videoIds = ConcurrentHashMap<String, String>()
    private val streams = ConcurrentHashMap<String, Stream>()
    private val inflight = ConcurrentHashMap<String, Deferred<Stream?>>()

    // Per-song tracking of options that failed (url, itag).
    private val failures = ConcurrentHashMap<String, MutableSet<String>>()

    fun markFailed(songId: String, url: String) {
        val set = failures.computeIfAbsent(songId) { ConcurrentHashMap.newKeySet() }
        set.add("url:$url")
        val itag = Regex("""[?&]itag=(\d+)""").find(url)?.groupValues?.get(1)
        val expired = expireOf(url)?.let { it < System.currentTimeMillis() + 60_000L } ?: false
        if (expired) {
            Log.w(TAG, "markFailed: URL expired (fresh resolve needed) song=$songId host=${hostOf(url)}")
            return
        }
        if (itag != null) set.add("itag:$itag")
        Log.w(TAG, "markFailed: itag=$itag song=$songId host=${hostOf(url)}")
    }

    fun clearFailures(songId: String) {
        failures.remove(songId)?.let { Log.i(TAG, "clearFailures: song=$songId (fresh retry allowed)") }
    }

    fun videoIdOf(song: Song): String? =
        videoIds[song.id] ?: song.contentUri
            .takeIf { it.startsWith(VT) }
            ?.removePrefix(VT)
            ?.takeIf { it.length == 11 }

    suspend fun resolve(song: Song): Stream? = withContext(Dispatchers.IO) {
        val excl = failures[song.id].orEmpty()
        videoIdOf(song)?.let { vid ->
            streams[vid]?.takeIf {
                // Must have at least 90s remaining before expiration
                it.expiresAt > (System.currentTimeMillis() + 90_000L) && !isExcludedUrl(it.url, excl)
            }?.let {
                Log.i(TAG, "resolve: cache hit vid=$vid src=${it.source} '${song.title}'")
                return@withContext it
            }
        }

        val key = song.id
        inflight[key]?.let { return@withContext it.await() }

        val job = scope.async { resolveInternal(song) }
        inflight[key] = job
        try {
            job.await()
        } finally {
            inflight.remove(key)
        }
    }

    private suspend fun resolveInternal(song: Song): Stream? {
        lastError = "start"
        Log.i(TAG, "resolve: start '${song.title}' — ${song.artist}")
        val query = "${song.title} ${song.artist}"
        val cached = videoIdOf(song)
        val excl = failures[song.id].orEmpty()
        val itagExcl = excl.filter { it.startsWith("itag:") }.toSet()

        // 1. YouTube Innertube
        lastError = "YouTube: searching video"
        val candidateIds = mutableListOf<String>()
        if (cached != null) {
            candidateIds.add(cached)
        }
        search(query)?.let { ids ->
            for (id in ids) {
                if (!candidateIds.contains(id)) candidateIds.add(id)
            }
        }

        for (vid in candidateIds) {
            lastError = "YouTube: fetching stream for $vid"
            playerUrl(vid, itagExcl)?.let {
                return store(song, vid, it, "youtube")
            }
        }

        // 2. Invidious Instances
        lastError = "Invidious: trying fallback instances"
        for (vid in candidateIds) {
            invidiousStream(vid, excl)?.let {
                return store(song, vid, it, "invidious")
            }
        }
        if (candidateIds.isEmpty()) {
            invidiousSearch(query)?.let { ids ->
                for (vid in ids) {
                    invidiousStream(vid, excl)?.let {
                        return store(song, vid, it, "invidious")
                    }
                }
            }
        }

        // 3. Piped Instances
        lastError = "Piped: trying fallback instances"
        for (vid in candidateIds) {
            pipedStream(vid, excl)?.let {
                return store(song, vid, it, "piped")
            }
        }
        if (candidateIds.isEmpty()) {
            pipedSearch(query)?.let { ids ->
                for (vid in ids) {
                    pipedStream(vid, excl)?.let {
                        return store(song, vid, it, "piped")
                    }
                }
            }
        }

        lastError = "Unable to resolve playable stream ($lastError)"
        Log.e(TAG, "resolve FAILED '${song.title}' — $lastError")
        return null
    }

    private fun store(song: Song, vid: String, url: String, source: String): Stream {
        videoIds[song.id] = vid
        val expiry = expireOf(url) ?: (System.currentTimeMillis() + 4 * 3600_000L)
        val stream = Stream(vid, url, expiry, source)
        streams[vid] = stream
        Log.i(
            TAG,
            "stream: src=$source vid=$vid host=${hostOf(url)} " +
                "expiresIn=${(expiry - System.currentTimeMillis()) / 1000}s '${song.title}'"
        )
        return stream
    }

    // ---------- source 1: YouTube innertube ----------

    private fun search(query: String): List<String>? {
        val body = JSONObject()
            .put(
                "context", JSONObject().put(
                    "client", JSONObject()
                        .put("clientName", "WEB_REMIX")
                        .put("clientVersion", "1.20241001.01.00")
                        .put("hl", "en")
                        .put("gl", gl())
                )
            )
            .put("query", query)

        for (host in HOSTS) {
            val resp = post(host + SEARCH_PATH, body.toString(), WEB_UA) ?: continue
            try {
                val ids = ArrayList<String>(4)
                findVideoIds(JSONObject(resp), ids, 4)
                if (ids.isNotEmpty()) return ids
            } catch (e: Exception) {
                lastError = "YouTube: search parsing error"
            }
        }
        return null
    }

    private fun findVideoIds(node: Any?, out: MutableList<String>, limit: Int) {
        if (out.size >= limit) return
        when (node) {
            is JSONObject -> {
                val v = node.optString("videoId")
                if (v.length == 11 && !out.contains(v)) {
                    out.add(v)
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    findVideoIds(node.opt(keys.next()), out, limit)
                    if (out.size >= limit) return
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findVideoIds(node.opt(i), out, limit)
                    if (out.size >= limit) return
                }
            }
        }
    }

    private fun playerUrl(videoId: String, itagExcl: Set<String> = emptySet()): String? {
        for (client in CLIENTS) {
            val body = JSONObject()
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)
                .put("context", JSONObject().put("client", client.json))

            for (host in HOSTS) {
                val resp = post(host + PLAYER_PATH, body.toString(), client.ua) ?: continue
                try {
                    val root = JSONObject(resp)
                    val playability = root.optJSONObject("playabilityStatus")
                    val status = playability?.optString("status") ?: "?"
                    if (status != "OK") {
                        val reason = playability?.optString("reason") ?: status
                        Log.w(TAG, "player: status=$status ($reason) vid=$videoId client=${client.json.optString("clientName")}")
                        lastError = "YouTube: $reason"
                        continue
                    }

                    val sd = root.optJSONObject("streamingData") ?: continue
                    pickAudioUrl(sd, itagExcl)?.let { return it }
                } catch (e: Exception) {
                    lastError = "YouTube: player JSON parse fail"
                }
            }
        }
        return null
    }

    private fun pickAudioUrl(sd: JSONObject, itagExcl: Set<String> = emptySet()): String? {
        val audioFormats = ArrayList<JSONObject>()
        listOf("formats", "adaptiveFormats").forEach { key ->
            val arr = sd.optJSONArray(key) ?: return@forEach
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val itag = f.optInt("itag", 0)
                if ("itag:$itag" in itagExcl) continue

                val url = extractFormatUrl(f) ?: continue
                if (url.isNotBlank()) {
                    f.put("extractedUrl", url)
                    audioFormats.add(f)
                }
            }
        }

        if (audioFormats.isEmpty()) return null

        // Progressive MP4 (itag 18) muxed track
        val progressive = audioFormats.firstOrNull { it.optInt("itag", 0) == 18 }
        // Audio M4A (itag 140 / 141)
        val audioM4a = audioFormats.firstOrNull { it.optInt("itag", 0) in listOf(140, 141) }
        // Opus (itag 251, 250, 249)
        val opus = audioFormats.firstOrNull { it.optInt("itag", 0) in listOf(251, 250, 249) }
        // Any audio mime type
        val anyAudio = audioFormats.firstOrNull { it.optString("mimeType").startsWith("audio/") }

        val chosen = progressive ?: audioM4a ?: opus ?: anyAudio ?: audioFormats.first()
        val chosenUrl = chosen.optString("extractedUrl")
        Log.i(TAG, "pickAudioUrl: selected itag=${chosen.optInt("itag", 0)} mime=${chosen.optString("mimeType").substringBefore(';')}")
        return chosenUrl.takeIf { it.isNotBlank() }
    }

    private fun extractFormatUrl(f: JSONObject): String? {
        if (f.has("url")) {
            val u = f.optString("url")
            if (u.isNotBlank()) return u
        }
        val cipher = f.optString("signatureCipher").ifBlank { f.optString("cipher") }
        if (cipher.isNotBlank()) {
            return try {
                val params = cipher.split("&").associate {
                    val parts = it.split("=", limit = 2)
                    parts[0] to (if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else "")
                }
                val u = params["url"]
                val sig = params["s"] ?: params["sig"]
                val sp = params["sp"] ?: "sig"
                if (!u.isNullOrBlank()) {
                    if (!sig.isNullOrBlank()) "$u&$sp=$sig" else u
                } else null
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    // ---------- source 2: Invidious (server-side YouTube fetch) ----------

    private fun invidiousSearch(query: String): List<String>? {
        val url = "/api/v1/search?q=" + URLEncoder.encode(query, "UTF-8")
        for (host in INVIDIOUS) {
            val resp = get(host + url) ?: continue
            try {
                val arr = JSONArray(resp)
                val ids = ArrayList<String>(3)
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i)?.optString("videoId") ?: continue
                    if (v.length == 11 && !ids.contains(v)) ids.add(v)
                    if (ids.size >= 3) break
                }
                if (ids.isNotEmpty()) return ids
            } catch (_: Exception) {
                lastError = "Invidious: search parse error ($host)"
            }
        }
        return null
    }

    private fun invidiousStream(vid: String, excl: Set<String> = emptySet()): String? {
        for (host in INVIDIOUS) {
            val resp = get("$host/api/v1/videos/$vid") ?: continue
            try {
                val root = JSONObject(resp)
                val arr = root.optJSONArray("adaptiveFormats") ?: continue
                var best: String? = null
                var bestRate = -1L
                for (i in 0 until arr.length()) {
                    val f = arr.optJSONObject(i) ?: continue
                    if (!f.optString("type").startsWith("audio")) continue
                    val u = f.optString("url").takeIf { it.isNotBlank() } ?: continue
                    if (isExcludedUrl(u, excl)) continue
                    val rate = f.optLong("bitrate", 0L)
                    if (rate >= bestRate) {
                        bestRate = rate
                        best = u
                    }
                }
                if (best != null) return best
            } catch (_: Exception) {
                lastError = "Invidious: video parse error ($host)"
            }
        }
        return null
    }

    // ---------- source 3: Piped (proxied audio) ----------

    private fun pipedSearch(query: String): List<String>? {
        val url = "/search?q=" + URLEncoder.encode(query, "UTF-8") + "&filter=all"
        for (host in PIPED) {
            val resp = get(host + url) ?: continue
            try {
                val items = JSONObject(resp).optJSONArray("items") ?: continue
                val ids = ArrayList<String>(3)
                for (i in 0 until items.length()) {
                    val w = items.optJSONObject(i)?.optString("url") ?: continue
                    val v = w.substringAfter("v=", "").takeIf { it.length == 11 } ?: continue
                    if (!ids.contains(v)) ids.add(v)
                    if (ids.size >= 3) break
                }
                if (ids.isNotEmpty()) return ids
            } catch (_: Exception) {
                lastError = "Piped: search parse error ($host)"
            }
        }
        return null
    }

    private fun pipedStream(vid: String, excl: Set<String> = emptySet()): String? {
        for (host in PIPED) {
            val resp = get("$host/streams/$vid") ?: continue
            try {
                val arr = JSONObject(resp).optJSONArray("audioStreams") ?: continue
                var best: String? = null
                var bestRate = -1L
                for (i in 0 until arr.length()) {
                    val f = arr.optJSONObject(i) ?: continue
                    val u = f.optString("url").takeIf { it.isNotBlank() } ?: continue
                    if (isExcludedUrl(u, excl)) continue
                    val rate = f.optLong("bitrate", 0L)
                    if (rate >= bestRate) {
                        bestRate = rate
                        best = u
                    }
                }
                if (best != null) return best
            } catch (_: Exception) {
                lastError = "Piped: stream parse error ($host)"
            }
        }
        return null
    }

    // ---------- shared http helpers ----------

    private fun expireOf(url: String): Long? =
        Regex("""[?&]expire=(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)

    private fun isExcludedUrl(url: String, excl: Set<String>): Boolean {
        if (excl.isEmpty()) return false
        if ("url:$url" in excl) return true
        val itag = Regex("""[?&]itag=(\d+)""").find(url)?.groupValues?.get(1)
        return itag != null && "itag:$itag" in excl
    }

    private fun get(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .addHeader("User-Agent", WEB_UA)
            .addHeader("Cookie", "SOCS=CAI")
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun post(url: String, json: String, ua: String): String? {
        val req = Request.Builder()
            .url(url)
            .addHeader("User-Agent", ua)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Goog-Api-Format-Version", "2")
            .addHeader("Cookie", "SOCS=CAI")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun hostOf(url: String): String =
        try { java.net.URI(url).host ?: url.take(40) } catch (_: Exception) { url.take(40) }

    companion object {
        const val VT = "yt:"
        private const val TAG = "ZyfenYT"
        private fun gl(): String = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
        private val HOSTS = listOf("https://music.youtube.com", "https://www.youtube.com")

        private val INVIDIOUS = listOf(
            "https://invidious.nerdvpn.de",
            "https://invidious.f5.si",
            "https://inv.nadeko.net",
            "https://yewtu.be",
            "https://iv.melmac.space",
            "https://invidious.private.coffee",
            "https://invidious.drgns.space"
        )
        private val PIPED = listOf(
            "https://api.piped.private.coffee",
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.drgns.space",
            "https://pipedapi.tokhmi.xyz"
        )

        private const val SEARCH_PATH = "/youtubei/v1/search?prettyPrint=false"
        private const val PLAYER_PATH = "/youtubei/v1/player?prettyPrint=false"
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        private class Client(val json: JSONObject, val ua: String)

        private val CLIENTS = listOf(
            Client(
                JSONObject()
                    .put("clientName", "ANDROID_VR")
                    .put("clientVersion", "1.60.19")
                    .put("deviceMake", "Oculus")
                    .put("deviceModel", "Quest 2")
                    .put("hl", "en").put("gl", gl()),
                "Mozilla/5.0 (Linux; Android 12; Quest 2) AppleWebKit/537.36"
            ),
            Client(
                JSONObject()
                    .put("clientName", "ANDROID")
                    .put("clientVersion", "19.34.42")
                    .put("androidSdkVersion", 34)
                    .put("hl", "en").put("gl", gl()),
                "com.google.android.youtube/19.34.42 (Linux; U; Android 14) gzip"
            ),
            Client(
                JSONObject()
                    .put("clientName", "IOS")
                    .put("clientVersion", "19.34.2")
                    .put("deviceModel", "iPhone16,2")
                    .put("hl", "en").put("gl", gl()),
                "com.google.youtube/19.34.2 (iPhone16,2; U; CPU iOS 17_5 like Mac OS X) gzip"
            ),
            Client(
                JSONObject()
                    .put("clientName", "WEB_REMIX")
                    .put("clientVersion", "1.20241001.01.00")
                    .put("hl", "en").put("gl", gl()),
                WEB_UA
            )
        )
    }
}
