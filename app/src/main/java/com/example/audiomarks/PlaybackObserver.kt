package com.example.audiomarks

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Snapshot of whatever media session is currently active in any app
 * (Gramophone, VLC, ...). Position is refreshed every [TICK_MS].
 */
data class NowPlaying(
	val title: String?,
	val artist: String?,
	val album: String?,
	val mediaId: String?,
	val art: Bitmap?,
	val durationMs: Long,
	val positionMs: Long,
	val isPlaying: Boolean,
)

class PlaybackObserver(context: Context) {

	private val appContext = context.applicationContext
	private val msm =
		context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
	private val handler = Handler(Looper.getMainLooper())

	private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
	val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

	private var watched: MediaController? = null
	private var tick = 0L

	private val callback = object : MediaController.Callback() {
		override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
		override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
		override fun onSessionDestroyed() {
			unwatch()
			rescan()
		}
	}

	private val ticker = object : Runnable {
		override fun run() {
			tick++
			// Rescan for new/changed sessions every ~2s (players may start later)
			if (watched == null || tick % 4 == 0L) rescan()
			refresh()
			handler.postDelayed(this, TICK_MS)
		}
	}

	fun start() {
		handler.post(ticker)
	}

	fun stop() {
		handler.removeCallbacks(ticker)
		unwatch()
	}

	private fun rescan() {
		val controllers = discoverControllers()
		if (controllers.isEmpty()) {
			if (watched != null) {
				unwatch()
				_nowPlaying.value = null
			}
			return
		}
		val current = watched
		if (current != null && controllers.contains(current)) return
		unwatch()
		val best = controllers.firstOrNull {
			it.playbackState?.state == PlaybackState.STATE_PLAYING
		} ?: controllers.first()
		watched = best
		try {
			best.registerCallback(callback)
		} catch (e: Exception) {
			watched = null
		}
		refresh()
	}

	/**
	 * Find active media session controllers from any app.
	 *
	 * API 36 removed the old cross-package session listing; the modern path is
	 * the media-key session (the system's "current" player) plus enumeration
	 * of [android.media.Session2Token]s, each resolved back to controllers via
	 * its exact component name.
	 */
	private fun discoverControllers(): List<MediaController> {
		val result = mutableListOf<MediaController>()
		val seen = mutableSetOf<MediaController>()

		// Primary: the session that receives media key events = current player
		try {
			val token = msm.getMediaKeyEventSession()
			Log.w(TAG, "media key session token: $token")
			token?.let {
				val c = MediaController(appContext, it)
				Log.w(TAG, "media key controller: ${c.packageName} meta=${c.metadata?.description}")
				if (seen.add(c)) result.add(c)
			}
		} catch (e: Exception) {
			Log.w(TAG, "media key session failed", e)
		}

		// Secondary: enumerate session tokens from all packages
		try {
			val tokens = msm.getSession2Tokens()
			Log.w(TAG, "session2 tokens: $tokens")
			for (t in tokens) {
				val pkg = t.packageName ?: continue
				val cn = ComponentName(pkg, t.serviceName ?: pkg)
				try {
					val cs = msm.getActiveSessions(cn)
					Log.w(TAG, "getActiveSessions($cn) -> $cs")
					cs.forEach { c ->
						if (seen.add(c)) result.add(c)
					}
				} catch (e: Exception) {
					Log.w(TAG, "getActiveSessions($cn) failed", e)
				}
			}
		} catch (e: Exception) {
			Log.w(TAG, "getSession2Tokens failed", e)
		}

		Log.w(TAG, "discovered ${result.size} controller(s)")
		return result
	}

	private fun unwatch() {
		watched?.let {
			try {
				it.unregisterCallback(callback)
			} catch (_: Exception) {
			}
		}
		watched = null
	}

	/** Seek the watched session by [deltaMs] (clamped at 0). */
	fun seekBy(deltaMs: Long) {
		val c = watched ?: return
		val pos = c.playbackState?.position ?: return
		try {
			c.transportControls.seekTo((pos + deltaMs).coerceAtLeast(0L))
		} catch (_: Exception) {
		}
	}

	/** Seek the watched session to an absolute position. */
	fun seekTo(positionMs: Long) {
		val c = watched ?: return
		try {
			c.transportControls.seekTo(positionMs.coerceAtLeast(0L))
		} catch (_: Exception) {
		}
	}

	fun play() {
		watched?.let { c ->
			try {
				c.transportControls.play()
			} catch (_: Exception) {
			}
		}
	}

	fun pause() {
		watched?.let { c ->
			try {
				c.transportControls.pause()
			} catch (_: Exception) {
			}
		}
	}

	private fun refresh() {
		val c = watched ?: run {
			_nowPlaying.value = null
			return
		}
		val m = c.metadata
		val state = c.playbackState
		_nowPlaying.value = NowPlaying(
			title = m?.getString(MediaMetadata.METADATA_KEY_TITLE),
			artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST),
			album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM),
			mediaId = m?.description?.mediaId,
			art = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
			durationMs = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
			positionMs = state?.position ?: 0L,
			isPlaying = state?.state == PlaybackState.STATE_PLAYING,
		)
	}

	companion object {
		const val TICK_MS = 500L
		private const val TAG = "PlaybackObserver"
	}
}
