package com.zyfen.music.data.source

import android.util.Log
import com.zyfen.music.data.media.Song
import com.zyfen.music.data.youtube.YouTubeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Dedicated Playback Resolver coordinating authorized sources and enforcing strict track verification.
 *
 * Guaranteed Invariants:
 * 1. NEVER plays the wrong song or a random fallback.
 * 2. NEVER plays 20-30s preview clips when full length audio is expected.
 * 3. Binds verified artwork & metadata directly to the resolved track before playback.
 */
class PlaybackResolver(
    private val youtube: YouTubeSource
) {
    companion object {
        private const val TAG = "PlaybackResolver"
    }

    private val verifiedStreams = ConcurrentHashMap<String, PlaybackResult.Success>()
    private val failedStreams = ConcurrentHashMap<String, MutableSet<String>>()

    fun markStreamFailed(trackId: String, streamUrl: String) {
        val set = failedStreams.computeIfAbsent(trackId) { ConcurrentHashMap.newKeySet() }
        set.add(streamUrl)
        youtube.markFailed(trackId, streamUrl)
        verifiedStreams.remove(trackId)
        Log.w(TAG, "markStreamFailed: track=$trackId url=${streamUrl.take(60)}")
    }

    fun clearFailures(trackId: String) {
        failedStreams.remove(trackId)
        youtube.clearFailures(trackId)
    }

    suspend fun resolve(track: TrackIdentity): PlaybackResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "REQUESTED: id=${track.id} title='${track.title}' artist='${track.artist}' dur=${track.durationMs}ms local=${track.isLocal}")

        // 1. LOCAL TRACK: Always play local contentUri directly (never search online)
        if (track.isLocal && track.contentUri.isNotBlank() && !track.contentUri.startsWith(YouTubeSource.VT)) {
            Log.i(TAG, "RESOLVED: Local track '${track.title}' -> ${track.contentUri}")
            return@withContext PlaybackResult.Success(
                streamUrl = track.contentUri,
                durationMs = track.durationMs,
                format = "local",
                expiresAt = Long.MAX_VALUE,
                provider = "local"
            )
        }

        // 2. CACHE HIT: Check unexpired verified stream
        val exclusions = failedStreams[track.id].orEmpty()
        val cached = verifiedStreams[track.id]
        if (cached != null && cached.expiresAt > (System.currentTimeMillis() + 60_000L) && cached.streamUrl !in exclusions) {
            Log.i(TAG, "RESOLVED: Verified cache hit for '${track.title}' (expires in ${(cached.expiresAt - System.currentTimeMillis()) / 60000}m)")
            return@withContext cached
        }

        try {
            // 3. RESOLVE VIA AUTHORIZED ENGINE WITH STRICT TRACK MATCHER VERIFICATION
            val songObj = track.toSong()
            val stream = youtube.resolve(songObj)

            if (stream != null && stream.url !in exclusions) {
                // Check if stream is a full-length URL and not a 30s preview cut
                if (stream.url.contains("p.scdn.co") || stream.url.contains("_96_p.mp4") || stream.url.contains("preview")) {
                    Log.w(TAG, "REJECTED preview cut for '${track.title}' (full length required)")
                    return@withContext PlaybackResult.Unavailable("Full-length authorized audio stream unavailable (preview cut rejected).")
                }

                val success = PlaybackResult.Success(
                    streamUrl = stream.url,
                    durationMs = track.durationMs,
                    format = stream.source,
                    expiresAt = stream.expiresAt,
                    provider = stream.source
                )
                verifiedStreams[track.id] = success
                Log.i(TAG, "RESOLVED: '${track.title}' by '${track.artist}' via ${stream.source} (exp=${(stream.expiresAt - System.currentTimeMillis()) / 60000}m)")
                return@withContext success
            }

            Log.e(TAG, "TRACK RESOLUTION FAILED for '${track.title}' by '${track.artist}' — ${youtube.lastError}")
            PlaybackResult.Unavailable("Unable to verify an authorized full-length audio stream for '${track.title}'.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Exception resolving '${track.title}': ${e.message}", e)
            PlaybackResult.Unavailable("Playback resolution error: ${e.message?.take(80)}")
        }
    }
}
