package com.example.audiomarks

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

	private val vm: MainViewModel by viewModels()

	override fun onCreate(savedInstanceState: android.os.Bundle?) {
		super.onCreate(savedInstanceState)
		setContent {
			val pickFolder = rememberLauncherForActivityResult(
				ActivityResultContracts.OpenDocumentTree(),
			) { uri: Uri? ->
				uri?.let { vm.store.setFolder(it) }
			}
			val context = LocalContext.current
			val listenerGranted = remember { mutableStateOf(notificationAccessGranted(context)) }
			LaunchedEffect(Unit) {
				while (true) {
					delay(2000)
					listenerGranted.value = notificationAccessGranted(context)
				}
			}
			val requestAudioPerm = rememberLauncherForActivityResult(
				ActivityResultContracts.RequestPermission(),
			) { /* MediaStore queries fail gracefully until granted */ }
			LaunchedEffect(Unit) {
				if (context.checkSelfPermission(
						android.Manifest.permission.READ_MEDIA_AUDIO,
					) != PackageManager.PERMISSION_GRANTED
				) {
					requestAudioPerm.launch(android.Manifest.permission.READ_MEDIA_AUDIO)
				}
			}
			MaterialTheme {
				Scaffold { padding ->
					AudioMarksScreen(
						vm = vm,
						onPickFolder = { pickFolder.launch(null) },
						listenerGranted = listenerGranted.value,
						onOpenListenerSettings = {
							context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
						},
						modifier = Modifier.padding(padding),
					)
				}
			}
		}
	}
}

@Composable
fun AudioMarksScreen(
	vm: MainViewModel,
	onPickFolder: () -> Unit,
	listenerGranted: Boolean,
	onOpenListenerSettings: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val nowPlaying by vm.playback.nowPlaying.collectAsState()
	val track by vm.currentTrack.collectAsState()
	val tracks by vm.store.tracks.collectAsState()
	val folderName by vm.store.folderName.collectAsState()
	val hasFolder = vm.store.hasFolder()

	Column(
		modifier = modifier
			.fillMaxSize()
			.padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		NowPlayingCard(nowPlaying, track, onSeekBack10 = { vm.seekBack10s() })

		if (!listenerGranted) {
			Surface(
				shape = RoundedCornerShape(12.dp),
				color = MaterialTheme.colorScheme.errorContainer,
				modifier = Modifier.fillMaxWidth(),
			) {
				Column(modifier = Modifier.padding(16.dp)) {
					Text(
						"Notification access required",
						style = MaterialTheme.typography.titleMedium,
						color = MaterialTheme.colorScheme.onErrorContainer,
					)
					Spacer(Modifier.height(8.dp))
					Text(
						"Android 16 only lets an app see other apps' playback if it is an " +
							"enabled notification listener. AudioMarks reads no notifications — " +
							"the access is only what unlocks playback observation.",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onErrorContainer,
					)
					Spacer(Modifier.height(12.dp))
					Button(onClick = onOpenListenerSettings) {
						Text("Open notification access settings")
					}
				}
			}
		}

		if (!hasFolder) {
			Surface(
				shape = RoundedCornerShape(12.dp),
				color = MaterialTheme.colorScheme.surfaceVariant,
				modifier = Modifier.fillMaxWidth(),
			) {
				Column(modifier = Modifier.padding(16.dp)) {
					Text(
						"Choose where annotations are stored",
						style = MaterialTheme.typography.titleMedium,
					)
					Spacer(Modifier.height(8.dp))
					Text(
						"Annotations are saved as one JSON file per track in this folder.",
						style = MaterialTheme.typography.bodySmall,
					)
					Spacer(Modifier.height(12.dp))
					Button(onClick = onPickFolder) {
						Icon(Icons.Outlined.FolderOpen, contentDescription = null)
						Spacer(Modifier.width(8.dp))
						Text("Choose folder")
					}
				}
			}
		} else {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Icon(
					Icons.Outlined.FolderOpen,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Spacer(Modifier.width(8.dp))
				Text(
					"Folder: ${folderName ?: "?"}",
					style = MaterialTheme.typography.bodySmall,
					modifier = Modifier.weight(1f),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					"Change",
					color = MaterialTheme.colorScheme.primary,
					style = MaterialTheme.typography.bodySmall,
				)
			}
		}

		AnnotationList(nowPlaying, track, tracks, Modifier.weight(1f))

		if (hasFolder && track != null) {
			AddAnnotationBar(vm)
		}
	}
}

@Composable
private fun NowPlayingCard(
	np: NowPlaying?,
	track: TrackInfo?,
	onSeekBack10: () -> Unit,
) {
	Surface(
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.surfaceVariant,
		modifier = Modifier.fillMaxWidth(),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(16.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (np?.art != null) {
				Box(
					modifier = Modifier
						.size(72.dp)
						.clip(RoundedCornerShape(10.dp))
						.background(MaterialTheme.colorScheme.surface),
				) {
					androidx.compose.foundation.Image(
						bitmap = np.art.asImageBitmap(),
						contentDescription = null,
						contentScale = ContentScale.Crop,
						modifier = Modifier.fillMaxSize(),
					)
				}
				Spacer(Modifier.width(12.dp))
			} else {
				Icon(
					Icons.Outlined.MusicNote,
					contentDescription = null,
					modifier = Modifier.size(40.dp),
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Spacer(Modifier.width(12.dp))
			}
			Column(modifier = Modifier.weight(1f)) {
				if (np == null) {
					Text(
						"No active playback",
						style = MaterialTheme.typography.titleMedium,
					)
					Text(
						"Start a song in Gramophone or VLC",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				} else {
					Text(
						np.title ?: "Unknown title",
						style = MaterialTheme.typography.titleMedium,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					Text(
						np.artist ?: "Unknown artist",
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					Spacer(Modifier.height(4.dp))
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text(
							"${formatTime(np.positionMs)} / ${formatTime(np.durationMs)}" +
								if (np.isPlaying) "  ▶" else "  ❚❚",
							style = MaterialTheme.typography.bodyMedium,
							fontFamily = FontFamily.Monospace,
						)
						Spacer(Modifier.width(8.dp))
						FilledTonalButton(
							onClick = onSeekBack10,
							contentPadding = androidx.compose.foundation.layout.PaddingValues(
								horizontal = 12.dp,
								vertical = 4.dp,
							),
						) {
							Icon(
								Icons.Filled.Replay10,
								contentDescription = "Back 10 seconds",
								modifier = Modifier.size(18.dp),
							)
							Spacer(Modifier.width(4.dp))
							Text("10s", style = MaterialTheme.typography.labelLarge)
						}
					}
					track?.let {
						Spacer(Modifier.height(4.dp))
						Text(
							it.mediaStoreUri?.toString()
								?: "no MediaStore entry (streaming?)",
							style = MaterialTheme.typography.bodySmall,
							fontFamily = FontFamily.Monospace,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						Text(
							if (it.isHashed) "identity: file hash" else "identity: title/artist",
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			}
		}
	}
}

@Composable
private fun AnnotationList(
	np: NowPlaying?,
	track: TrackInfo?,
	tracks: Map<String, TrackAnnotations>,
	modifier: Modifier = Modifier,
) {
	val annotations = track?.let { tracks[it.key]?.annotations } ?: emptyList()
	val listState = rememberLazyListState()

	// Anchor = last annotation at or before the playhead; scroll to it as the song plays
	val positionMs = np?.positionMs ?: 0L
	val anchor = annotations.indexOfLast { it.t * 1000.0 <= positionMs.toDouble() }
	LaunchedEffect(anchor, annotations.size) {
		if (anchor >= 0 && anchor != listState.firstVisibleItemIndex) {
			listState.animateScrollToItem(anchor)
		}
	}

	Surface(
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.surfaceVariant,
		modifier = modifier.fillMaxWidth(),
	) {
		if (annotations.isEmpty()) {
			Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text(
					if (track == null) "Annotations will appear here"
					else "No annotations for this track yet",
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					style = MaterialTheme.typography.bodyMedium,
					textAlign = TextAlign.Center,
				)
			}
		} else {
			LazyColumn(
				state = listState,
				modifier = Modifier.fillMaxSize(),
				contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				items(annotations) { a ->
					val active = np != null &&
						positionMs >= a.t * 1000.0 &&
						positionMs < a.t * 1000.0 + HIGHLIGHT_MS
					AnnotationBubble(a, active)
				}
			}
		}
	}
}

@Composable
private fun AnnotationBubble(a: Annotation, active: Boolean) {
	val bg = if (active) {
		MaterialTheme.colorScheme.primaryContainer
	} else {
		MaterialTheme.colorScheme.surface
	}
	Surface(
		color = bg,
		shape = RoundedCornerShape(14.dp),
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
			Text(
				formatTime((a.t * 1000).toLong()),
				style = MaterialTheme.typography.labelMedium,
				fontFamily = FontFamily.Monospace,
				fontWeight = FontWeight.Bold,
				color = if (active) {
					MaterialTheme.colorScheme.onPrimaryContainer
				} else {
					MaterialTheme.colorScheme.primary
				},
			)
			Text(
				a.text,
				style = MaterialTheme.typography.bodyLarge,
				color = if (active) {
					MaterialTheme.colorScheme.onPrimaryContainer
				} else {
					MaterialTheme.colorScheme.onSurface
				},
			)
		}
	}
}

@Composable
private fun AddAnnotationBar(vm: MainViewModel) {
	var text by remember { mutableStateOf("") }
	val np by vm.playback.nowPlaying.collectAsState()
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
	) {
		OutlinedTextField(
			value = text,
			onValueChange = { text = it },
			placeholder = { Text("Annotation text…") },
			singleLine = true,
			keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
			modifier = Modifier.weight(1f),
		)
		Spacer(Modifier.width(8.dp))
		Button(
			onClick = {
				if (text.isNotBlank()) {
					vm.addAnnotation(text)
					text = ""
				}
			},
			enabled = text.isNotBlank() && np != null,
		) {
			Icon(Icons.Outlined.AddComment, contentDescription = null)
			Spacer(Modifier.width(6.dp))
			Text("Add @ ${formatTime(np?.positionMs ?: 0L)}")
		}
	}
}

fun formatTime(ms: Long): String {
	val totalSec = ms / 1000
	val h = totalSec / 3600
	val m = (totalSec % 3600) / 60
	val s = totalSec % 60
	return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private const val HIGHLIGHT_MS = 10_000L

private fun notificationAccessGranted(context: Context): Boolean {
	val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
	return nm.isNotificationListenerAccessGranted(
		ComponentName(context, MarksNotificationListener::class.java),
	)
}
