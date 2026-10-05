package com.example.audiomarks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

	val playback = PlaybackObserver(app)
	val store = AnnotationStore(app)
	private val resolver = TrackResolver(app)

	private val _currentTrack = MutableStateFlow<TrackInfo?>(null)
	val currentTrack: StateFlow<TrackInfo?> = _currentTrack.asStateFlow()

	private var resolvingFor: String? = null

	init {
		playback.start()
		viewModelScope.launch {
			playback.nowPlaying.collect { np ->
				if (np == null) {
					if (resolvingFor != null) {
						resolvingFor = null
						_currentTrack.value = null
					}
					return@collect
				}
				// Re-resolve identity when the track (or its metadata) changes
				val fingerprint = "${np.mediaId}|${np.title}|${np.artist}"
				if (fingerprint == resolvingFor) return@collect
				resolvingFor = fingerprint
				viewModelScope.launch(Dispatchers.IO) {
					val info = resolver.resolve(np)
					if (resolvingFor == fingerprint) {
						_currentTrack.value = info
					}
				}
			}
		}
	}

	fun seekBack10s() {
		playback.seekBy(-10_000)
	}

	fun togglePlayPause() {
		val np = playback.nowPlaying.value ?: return
		if (np.isPlaying) playback.pause() else playback.play()
	}

	fun seekTo(ms: Long) {
		playback.seekTo(ms)
	}

	fun addAnnotation(text: String) {
		val np = playback.nowPlaying.value ?: return
		val track = _currentTrack.value ?: return
		val t = np.positionMs / 1000.0
		viewModelScope.launch(Dispatchers.IO) {
			store.addAnnotation(track, t, text.trim())
		}
	}

	fun updateAnnotation(a: Annotation, text: String) {
		val track = _currentTrack.value ?: return
		// known.remove() inside the store forces a re-read on the next poll
		store.updateAnnotationText(track.key, a.t, a.created, text.trim())
	}

	override fun onCleared() {
		playback.stop()
		super.onCleared()
	}
}
