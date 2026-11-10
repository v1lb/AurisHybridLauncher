package com.aurishybrid.launcher.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NowPlaying(
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val artist: String?,
    val playing: Boolean,
    val art: ImageBitmap?,
)

/** hasAccess = false — пользователь ещё не разрешил доступ к уведомлениям. */
data class MediaState(val hasAccess: Boolean = false, val now: NowPlaying? = null)

/**
 * «Сейчас играет»: читает активную медиасессию Android (MediaSessionManager) и управляет ею.
 * Работает только с плеерами, которые публикуют медиасессию; всё выполняется в главном потоке.
 */
class MediaRepository(private val context: Context) {

    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MediaListenerService::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(MediaState())
    val state: StateFlow<MediaState> = _state

    private var listening = false
    private var controller: MediaController? = null
    private var artSource: Bitmap? = null
    private var artCache: ImageBitmap? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pick(list ?: emptyList())
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = refresh()
    }

    fun hasAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Перечитать сессии; вызывается при старте и при каждом возврате в лаунчер. */
    fun refresh() {
        if (msm == null || !hasAccess()) {
            detach()
            _state.value = MediaState(hasAccess = false)
            return
        }
        try {
            if (!listening) {
                msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
                listening = true
            }
            pick(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            detach()
            _state.value = MediaState(hasAccess = false)
        }
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    private fun detach() {
        controller?.unregisterCallback(callback)
        controller = null
        if (listening) {
            runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
            listening = false
        }
    }

    /** Берётся играющая сессия, иначе — первая в списке (Android ставит последнюю активную первой). */
    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, handler)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            _state.value = MediaState(hasAccess = true)
            return
        }
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        _state.value = MediaState(
            hasAccess = true,
            now = NowPlaying(
                packageName = c.packageName,
                appLabel = appLabel(c.packageName),
                title = title?.takeIf { it.isNotBlank() },
                artist = artist?.takeIf { it.isNotBlank() },
                playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
                art = art(bmp),
            )
        )
    }

    private fun art(src: Bitmap?): ImageBitmap? {
        if (src == null || src.isRecycled) {
            artSource = null; artCache = null
            return null
        }
        if (src === artSource) return artCache
        artSource = src
        artCache = try {
            Bitmap.createScaledBitmap(src, ART_PX, ART_PX, true).asImageBitmap()
        } catch (e: Exception) {
            null
        }
        return artCache
    }

    private fun appLabel(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private companion object {
        const val ART_PX = 192
    }
}
