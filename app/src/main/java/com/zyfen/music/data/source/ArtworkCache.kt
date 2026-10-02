package com.zyfen.music.data.source

import android.util.LruCache

/**
 * Thread-safe, provider-qualified artwork cache.
 * Uses `provider:uniqueTrackId` keys to prevent cross-song artwork collision or stale reuse.
 */
object ArtworkCache {

    private val cache = LruCache<String, String>(1000)

    fun get(identity: TrackIdentity): String? {
        val key = identity.cacheKey
        return cache.get(key) ?: identity.artworkUrl
    }

    fun put(identity: TrackIdentity, url: String) {
        if (url.isNotBlank()) {
            cache.put(identity.cacheKey, url)
        }
    }

    fun put(provider: String, trackId: String, url: String) {
        if (url.isNotBlank() && trackId.isNotBlank()) {
            cache.put("${provider.lowercase()}:$trackId", url)
        }
    }

    fun remove(identity: TrackIdentity) {
        cache.remove(identity.cacheKey)
    }

    fun clear() {
        cache.evictAll()
    }
}
