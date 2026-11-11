package com.treyz.clipforge

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileInputStream

class MainActivity : ComponentActivity() {
    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = darkColorScheme(
                primary = Color(0xFF63D8FF),
                secondary = Color(0xFFB38CFF),
                background = Color(0xFF090D16),
                surface = Color(0xFF151C2B)
            )
            MaterialTheme(colorScheme = colors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ClipForgeScreen(
                        onPickVideo = { launcherCallback ->
                            // The picker is launched by the Compose activity-result launcher below.
                            launcherCallback()
                        },
                        getDuration = { uri -> readDurationMs(uri) },
                        exportClip = { uri, startMs, endMs, onStatus ->
                            exportTrimmedVideo(uri, startMs, endMs, onStatus)
                        }
                    )
                }
            }
        }
    }

    private fun readDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    @OptIn(UnstableApi::class)
    private fun exportTrimmedVideo(
        sourceUri: Uri,
        startMs: Long,
        endMs: Long,
        onStatus: (String) -> Unit
    ) {
        val outputFile = File(cacheDir, "clipforge_${System.currentTimeMillis()}.mp4")
        onStatus("Exporting video… keep the app open until export finishes.")

        val clippedItem = MediaItem.Builder()
            .setUri(sourceUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
            .build()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "ClipForge_${System.currentTimeMillis()}.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipForge")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val destination = contentResolver.insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        values
                    ) ?: throw IllegalStateException("Could not create a video in Movies/ClipForge.")

                    contentResolver.openOutputStream(destination)?.use { output ->
                        FileInputStream(outputFile).use { input -> input.copyTo(output) }
                    } ?: throw IllegalStateException("Could not open the output video.")

                    val completedValues = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(destination, completedValues, null, null)
                    outputFile.delete()
                    runOnUiThread { onStatus("Export complete. Saved in Gallery → Movies → ClipForge.") }
                } catch (error: Exception) {
                    runOnUiThread { onStatus("Exported, but saving failed: ${error.message}") }
                }
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                outputFile.delete()
                runOnUiThread { onStatus("Export failed: ${exportException.message ?: "Unknown error"}") }
            }
        }

        try {
            val transformer = Transformer.Builder(this)
                .addListener(listener)
                .build()
            transformer.start(EditedMediaItem.Builder(clippedItem).build(), outputFile.absolutePath)
        } catch (error: Exception) {
            outputFile.delete()
            onStatus("Could not start export: ${error.message}")
        }
    }
}

@Composable
private fun ClipForgeScreen(
    onPickVideo: (() -> Unit) -> Unit,
    getDuration: (Uri) -> Long,
    exportClip: (Uri, Long, Long, (String) -> Unit) -> Unit
) {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var startMs by remember { mutableFloatStateOf(0f) }
    var endMs by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("Choose a gameplay recording to begin.") }
    var exporting by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedUri = uri
            val duration = getDuration(uri)
            durationMs = duration
            startMs = 0f
            endMs = duration.toFloat()
            status = if (duration > 0) "Video loaded. Set your start and end points." else "Could not read this video's duration."
        }
    }

    val canExport = selectedUri != null && durationMs > 1000 &&
        endMs.toLong() - startMs.toLong() >= 1000 && !exporting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090D16))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("CLIPFORGE", style = MaterialTheme.typography.headlineMedium, color = Color(0xFF63D8FF))
        Text("Gaming clips, cut your way.", color = Color(0xFFB7C2D6))
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151C2B)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("01  IMPORT GAMEPLAY", color = Color(0xFFB38CFF), style = MaterialTheme.typography.labelLarge)
                Text(
                    if (selectedUri == null) "No video selected" else "Video selected • ${formatTime(durationMs)}",
                    color = Color.White
                )
                Button(
                    onClick = { picker.launch("video/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Choose video")
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151C2B)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("02  TRIM CLIP", color = Color(0xFFB38CFF), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Start", color = Color.White)
                    Text(formatTime(startMs.toLong()), color = Color(0xFF63D8FF))
                }
                Slider(
                    value = startMs,
                    onValueChange = { value ->
                        startMs = value.coerceAtMost((endMs - 1000f).coerceAtLeast(0f))
                    },
                    valueRange = 0f..(durationMs.toFloat().coerceAtLeast(1f)),
                    enabled = selectedUri != null && durationMs > 1000
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("End", color = Color.White)
                    Text(formatTime(endMs.toLong()), color = Color(0xFF63D8FF))
                }
                Slider(
                    value = endMs,
                    onValueChange = { value ->
                        endMs = value.coerceAtLeast(startMs + 1000f).coerceAtMost(durationMs.toFloat())
                    },
                    valueRange = 0f..(durationMs.toFloat().coerceAtLeast(1f)),
                    enabled = selectedUri != null && durationMs > 1000
                )
                Text(
                    "Selected length: ${formatTime((endMs - startMs).toLong().coerceAtLeast(0L))}",
                    color = Color(0xFFB7C2D6)
                )
            }
        }

        Button(
            onClick = {
                val uri = selectedUri ?: return@Button
                exporting = true
                exportClip(uri, startMs.toLong(), endMs.toLong()) { message ->
                    status = message
                    if (message.startsWith("Export complete") || message.startsWith("Export failed") ||
                        message.startsWith("Could not") || message.startsWith("Exported, but")) {
                        exporting = false
                    }
                }
            },
            enabled = canExport,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (exporting) "Exporting…" else "Export trimmed clip")
        }

        Text(status, color = Color(0xFFB7C2D6), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            "CLIPFORGE • Early development build",
            color = Color(0xFF65728A),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
