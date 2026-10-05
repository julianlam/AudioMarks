package com.example.audiomarks

import android.content.Context
import android.net.Uri
import android.util.Log
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class Annotation(
	val t: Double, // seconds
	val text: String,
	val created: Long, // epoch ms
)

data class TrackAnnotations(
	val key: String,
	val title: String?,
	val artist: String?,
	val annotations: List<Annotation>,
)

/**
 * Reads/writes one JSON file per track inside a user-chosen folder
 * (picked via SAF, permission persisted). Polls the folder once per second
 * so externally-saved annotations appear in real time.
 *
 * File format: <key>.json
 * {
 *   "key": "<md5 or fallback>",
 *   "title": "...", "artist": "...",
 *   "annotations": [ { "t": 83.0, "text": "trombone counter", "created": 123 } ]
 * }
 */
class AnnotationStore(context: Context) {

	private val appContext = context.applicationContext
	private val resolver = appContext.contentResolver
	private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	private val _tracks = MutableStateFlow<Map<String, TrackAnnotations>>(emptyMap())
	val tracks: StateFlow<Map<String, TrackAnnotations>> = _tracks.asStateFlow()

	private val _folderName = MutableStateFlow<String?>(null)
	val folderName: StateFlow<String?> = _folderName.asStateFlow()

	// file uri string -> lastModified of the content we last parsed
	private val known = mutableMapOf<String, Long>()

	init {
		prefs.getString("tree", null)?.let { treeUri = Uri.parse(it) }
		scope.launch {
			while (true) {
				poll()
				delay(POLL_MS)
			}
		}
	}

	private var treeUri: Uri? = null

	fun hasFolder(): Boolean = treeUri != null

	fun setFolder(uri: Uri) {
		try {
			resolver.takePersistableUriPermission(
				uri,
				android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
					android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
			)
		} catch (_: Exception) {
		}
		treeUri = uri
		prefs.edit().putString("tree", uri.toString()).apply()
		known.clear()
		scope.launch { poll() }
	}

	private fun poll() {
		val tree = treeUri ?: return
		val root = try {
			DocumentFile.fromTreeUri(appContext, tree)
		} catch (e: Exception) {
			null
		} ?: return

		val seen = mutableSetOf<String>()
		val updated = _tracks.value.toMutableMap()

		for (file in root.listFiles()) {
			val name = file.name ?: continue
			if (!name.endsWith(".json")) continue
			val uri = file.uri ?: continue
			val uriKey = uri.toString()
			seen.add(uriKey)
			val modified = try {
				file.lastModified()
			} catch (e: Exception) {
				0L
			}
			if (known[uriKey] == modified) continue
			val content = try {
				resolver.openInputStream(uri)?.use { it.readBytes() }?.decodeToString()
			} catch (e: Exception) {
				null
			} ?: continue
			known[uriKey] = modified
			parse(content, name)?.let { parsed ->
				updated[parsed.key] = parsed
			}
		}

		// Drop entries whose files disappeared
		known.keys.retainAll(seen)
		_tracks.value = updated
		_folderName.value = root.name
	}

	private fun parse(content: String, fileName: String): TrackAnnotations? = try {
		val obj = JSONObject(content)
		val key = obj.optString("key", fileName.removeSuffix(".json"))
		val list = obj.optJSONArray("annotations") ?: JSONArray()
		val annotations = (0 until list.length()).map { i ->
			val a = list.getJSONObject(i)
			Annotation(
				t = a.optDouble("t", 0.0),
				text = a.optString("text", ""),
				created = a.optLong("created", 0L),
			)
		}.sortedBy { it.t }
		TrackAnnotations(
			key = key,
			title = obj.optString("title", "").ifEmpty { null },
			artist = obj.optString("artist", "").ifEmpty { null },
			annotations = annotations,
		)
	} catch (e: Exception) {
		null
	}

	/** Append an annotation to the track's file. Call from a background thread. */
	fun addAnnotation(track: TrackInfo, t: Double, text: String) {
		val tree = treeUri ?: return
		val root = DocumentFile.fromTreeUri(appContext, tree) ?: return
		val name = "${track.key}.json"

		val existing = _tracks.value[track.key]
		val obj = JSONObject()
			.put("key", track.key)
			.put("title", track.title ?: "")
			.put("artist", track.artist ?: "")
		val arr = JSONArray()
		existing?.annotations?.forEach { a ->
			arr.put(
				JSONObject()
					.put("t", a.t)
					.put("text", a.text)
					.put("created", a.created),
			)
		}
		arr.put(
			JSONObject()
				.put("t", t)
				.put("text", text)
				.put("created", System.currentTimeMillis()),
		)
		obj.put("annotations", arr)

		try {
			val doc = root.findFile(name) ?: root.createFile("application/json", name)
			if (doc == null) return
			resolver.openOutputStream(doc.uri)?.use { it.write(obj.toString(2).toByteArray()) }
			// Force re-read on next poll
			known.remove(doc.uri.toString())
		} catch (_: Exception) {
		}
	}

	/** Update the text of the annotation at (t, created) in [trackKey]'s file. */
	fun updateAnnotationText(trackKey: String, t: Double, created: Long, text: String): Boolean {
		val tree = treeUri ?: return false
		val root = DocumentFile.fromTreeUri(appContext, tree) ?: return false
		val doc = root.findFile("${trackKey}.json") ?: run {
			Log.w(TAG, "update: file not found for key $trackKey")
			return false
		}
		var found = false
		try {
			val content = resolver.openInputStream(doc.uri)
				?.use { it.readBytes() }
				?.decodeToString() ?: return false
			val obj = JSONObject(content)
			val arr = obj.getJSONArray("annotations")
			for (i in 0 until arr.length()) {
				val a = arr.getJSONObject(i)
				if (a.optDouble("t", -1.0) == t && a.optLong("created", -1L) == created) {
					a.put("text", text)
					found = true
					break
				}
			}
			if (found) {
				// Same "w" mode as addAnnotation (truncates); "wt" is not
				// guaranteed to be honored by every DocumentProvider
				val out = resolver.openOutputStream(doc.uri) ?: run {
					Log.w(TAG, "update: openOutputStream returned null")
					return false
				}
				out.use { it.write(obj.toString(2).toByteArray()) }
				// Force re-read on next poll
				known.remove(doc.uri.toString())
				Log.i(TAG, "update: wrote ${obj.length()} bytes for key $trackKey")
			} else {
				Log.w(TAG, "update: no annotation matched t=$t created=$created")
			}
		} catch (e: Exception) {
			Log.w(TAG, "update failed", e)
		}
		return found
	}

	/** Delete the annotation at (t, created) from [trackKey]'s file. */
	fun deleteAnnotation(trackKey: String, t: Double, created: Long): Boolean {
		val tree = treeUri ?: return false
		val root = DocumentFile.fromTreeUri(appContext, tree) ?: return false
		val doc = root.findFile("${trackKey}.json") ?: run {
			Log.w(TAG, "delete: file not found for key $trackKey")
			return false
		}
		var found = false
		try {
			val content = resolver.openInputStream(doc.uri)
				?.use { it.readBytes() }
				?.decodeToString() ?: return false
			val obj = JSONObject(content)
			val arr = obj.getJSONArray("annotations")
			val kept = JSONArray()
			for (i in 0 until arr.length()) {
				val a = arr.getJSONObject(i)
				if (a.optDouble("t", -1.0) == t && a.optLong("created", -1L) == created) {
					found = true
				} else {
					kept.put(a)
				}
			}
			if (found) {
				obj.put("annotations", kept)
				val out = resolver.openOutputStream(doc.uri) ?: run {
					Log.w(TAG, "delete: openOutputStream returned null")
					return false
				}
				out.use { it.write(obj.toString(2).toByteArray()) }
				// Force re-read on next poll
				known.remove(doc.uri.toString())
				Log.i(TAG, "delete: wrote ${obj.length()} bytes, ${kept.length()} remaining")
			} else {
				Log.w(TAG, "delete: no annotation matched t=$t created=$created")
			}
		} catch (e: Exception) {
			Log.w(TAG, "delete failed", e)
		}
		return found
	}

	companion object {
		const val POLL_MS = 1000L
		private const val TAG = "AnnotationStore"
	}
}
