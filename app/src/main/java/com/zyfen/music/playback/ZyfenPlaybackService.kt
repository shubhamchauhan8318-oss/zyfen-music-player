package com.zyfen.music.playback

import android.content.Context
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class ZyfenPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    /**
     * ZYFEN is a music player: audio only to prevent device-specific
     * video codec initialization crashes when streaming muxed audio/video containers.
     */
    private class AudioOnlyRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: ArrayList<Renderer>
        ) {
            // Audio only
        }
    }

    override fun onCreate() {
        super.onCreate()

        val httpData = DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            )
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept-Encoding" to "identity"
                )
            )

        // CRITICAL FIX: DefaultDataSource supports content://, file://, assets AND http(s)://
        val dataSourceFactory = DefaultDataSource.Factory(this, httpData)

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(AudioOnlyRenderersFactory(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true // Auto handle audio focus
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
