package com.julianlam.audiomarks

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Identity of the currently playing track.
 *
 * [key] is the MD5 hex digest of the audio file's bytes when the file can be
 * read; otherwise a `title|artist` fallback key is used so annotations still
 * work (e.g. streamed tracks).
 */
data class TrackInfo(
	val key: String,
	val title: String?,
	val artist: String?,
	val album: String?,
	val mediaStoreUri: Uri?,
	val durationMs: Long,
	val isHashed: Boolean,
)

class TrackResolver(context: Context) {

	private val resolver = context.contentResolver
	private val cacheFile = File(context.filesDir, "hashcache.json")

	// mediaStoreId -> {size, modified, hash}
	private val cache = mutableMapOf<String, JSONObject>()

	init {
		loadCache()
	}

	/**
	 * Resolve a [NowPlaying] snapshot into a [TrackInfo].
	 * May block on I/O (MediaStore queries, full-file MD5) — call from a
	 * background dispatcher.
	 */
	fun resolve(np: NowPlaying): TrackInfo? {
		// 1) Preferred: mediaId is a MediaStore content URI
		np.mediaId?.let { id ->
			if (id.startsWith("content://media/")) {
				val uri = Uri.parse(id)
				queryRow(uri)?.let { row ->
					return row.toTrackInfo(np)
				}
			}
		}

		// 2) Fuzzy match on title + artist
		val row = fuzzyMatch(np.title, np.artist, np.durationMs)
		if (row != null) return row.toTrackInfo(np)

		// 3) No MediaStore row at all (streaming?) — title/artist identity
		val title = np.title?.trim().orEmpty()
		val artist = np.artist?.trim().orEmpty()
		if (title.isEmpty() && artist.isEmpty()) return null
		return TrackInfo(
			key = fallbackKey(title, artist),
			title = np.title,
			artist = np.artist,
			album = np.album,
			mediaStoreUri = null,
			durationMs = np.durationMs,
			isHashed = false,
		)
	}

	private fun Row.toTrackInfo(np: NowPlaying): TrackInfo {
		val hash = hashOf(this)
		return TrackInfo(
			key = hash ?: fallbackKey(
				title.orEmpty(),
				artist.orEmpty(),
			),
			title = title ?: np.title,
			artist = artist ?: np.artist,
			album = album ?: np.album,
			mediaStoreUri = uri,
			durationMs = if (durationMs == 0L) np.durationMs else durationMs,
			isHashed = hash != null,
		)
	}

	/** One MediaStore row, just the columns we care about. */
	private data class Row(
		val id: String,
		val uri: Uri,
		val title: String?,
		val artist: String?,
		val album: String?,
		val durationMs: Long,
		val size: Long,
		val modified: Long,
	)

	private val projection = arrayOf(
		MediaStore.Audio.Media._ID,
		MediaStore.Audio.Media.TITLE,
		MediaStore.Audio.Media.ARTIST,
		MediaStore.Audio.Media.ALBUM,
		MediaStore.Audio.Media.DURATION,
		MediaStore.Audio.Media.SIZE,
		MediaStore.Audio.Media.DATE_MODIFIED,
	)

	private fun queryRow(uri: Uri): Row? = try {
		resolver.query(uri, projection, null, null, null)?.use { c ->
			if (!c.moveToFirst()) return null
			Row(
				id = c.getString(0),
				uri = uri,
				title = c.getString(1),
				artist = c.getString(2),
				album = c.getString(3),
				durationMs = c.getLong(4),
				size = c.getLong(5),
				modified = c.getLong(6),
			)
		}
	} catch (e: Exception) {
		null
	}

	private fun fuzzyMatch(title: String?, artist: String?, durationMs: Long): Row? {
		val wantTitle = title?.trim()?.lowercase().orEmpty()
		val wantArtist = artist?.trim()?.lowercase().orEmpty()
		if (wantTitle.isEmpty() && wantArtist.isEmpty()) return null
		return try {
			resolver.query(
				MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
				projection,
				null,
				null,
				null,
			)?.use { c ->
				var best: Row? = null
				var bestScore = -1
				while (c.moveToNext()) {
					val row = Row(
						id = c.getString(0),
						uri = Uri.parse(
							ContentUri(c.getString(0)),
						),
						title = c.getString(1),
						artist = c.getString(2),
						album = c.getString(3),
						durationMs = c.getLong(4),
						size = c.getLong(5),
						modified = c.getLong(6),
					)
					val score = matchScore(row, wantTitle, wantArtist, durationMs)
					if (score > bestScore) {
						bestScore = score
						best = row
					}
				}
				best
			}
		} catch (e: Exception) {
			null
		}
	}

	private fun matchScore(
		row: Row,
		wantTitle: String,
		wantArtist: String,
		durationMs: Long,
	): Int {
		val t = row.title?.trim()?.lowercase().orEmpty()
		val a = row.artist?.trim()?.lowercase().orEmpty()
		var score = 0
		if (wantTitle.isNotEmpty() && t == wantTitle) score += 4
		else if (wantTitle.isNotEmpty() && (t.contains(wantTitle) || wantTitle.contains(t))) score += 2
		if (wantArtist.isNotEmpty() && a == wantArtist) score += 2
		if (durationMs > 0 && row.durationMs > 0 &&
			kotlin.math.abs(row.durationMs - durationMs) < 2000
		) score += 2
		return score
	}

	private fun ContentUri(id: String): String =
		"content://media/external/audio/media/$id"

	/** MD5 of the file bytes, cached by (id, size, modified). */
	private fun hashOf(row: Row): String? {
		val cached = cache[row.id]
		if (cached != null &&
			cached.optLong("size", -1) == row.size &&
			cached.optLong("modified", -1) == row.modified
		) {
			return cached.optString("hash", "")
		}
		val hash = md5(row.uri, row.size) ?: return null
		cache[row.id] = JSONObject()
			.put("size", row.size)
			.put("modified", row.modified)
			.put("hash", hash)
		saveCache()
		return hash
	}

	private fun md5(uri: Uri, expectedSize: Long): String? {
		return try {
			val digest = MessageDigest.getInstance("MD5")
			val input = resolver.openInputStream(uri) ?: return null
			input.use {
				val buf = ByteArray(64 * 1024)
				while (true) {
					val n = it.read(buf)
					if (n < 0) break
					digest.update(buf, 0, n)
				}
			}
			digest.digest().joinToString("") { "%02x".format(it) }
		} catch (e: Exception) {
			null
		}
	}

	fun fallbackKey(title: String, artist: String): String =
		"t|${title.lowercase()}|a|${artist.lowercase()}"

	private fun loadCache() {
		try {
			val root = JSONObject(cacheFile.readText())
			root.keys().forEach { id ->
				root.optJSONObject(id)?.let { cache[id] = it }
			}
		} catch (_: Exception) {
		}
	}

	private fun saveCache() {
		try {
			val root = JSONObject()
			cache.keys.forEach { id -> root.put(id, cache[id]) }
			cacheFile.writeText(root.toString())
		} catch (_: Exception) {
		}
	}
}
