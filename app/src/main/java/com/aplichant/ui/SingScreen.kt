package com.aplichant.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

private val AUDIO_TYPES = arrayOf("audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/wave", "audio/*")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingScreen(vm: SingViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val pickSong = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::onSongPicked)
    }
    val pickBacking = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::onBackingPicked)
    }
    // Enregistrement demandé en attendant l'autorisation du micro (true = sans backing).
    var pendingSolo by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            !granted -> vm.showMessage("L'accès au micro est nécessaire pour enregistrer")
            pendingSolo -> vm.recordSolo()
            else -> vm.record()
        }
    }
    fun startRecording(solo: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            if (solo) vm.recordSolo() else vm.record()
        } else {
            pendingSolo = solo
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Export de la voix seule vers le stockage du téléphone.
    var exporting by remember { mutableStateOf<Take?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val take = exporting
        if (uri != null && take != null) vm.exportTake(take, uri)
        exporting = null
    }
    var naming by remember { mutableStateOf<Take?>(null) }
    var deleting by remember { mutableStateOf<Take?>(null) }

    naming?.let { take ->
        SaveDialog(
            initial = take.name ?: "",
            onDismiss = { naming = null },
            onSave = { name ->
                vm.saveTakeAs(take, name)
                naming = null
            },
        )
    }
    deleting?.let { take ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer cette voix ?") },
            text = { Text(take.name ?: "Prise du ${take.label}") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteTake(take)
                    deleting = null
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } },
        )
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }

    val busy = state.mode != Mode.IDLE

    Scaffold(
        topBar = { TopAppBar(title = { Text("Apli Chant") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ------------------------------------------------------------ 1. Fichiers
            Section("1. Fichiers") {
                FileRow(
                    icon = Icons.Filled.MusicNote,
                    label = "Musique",
                    name = state.songName,
                    enabled = !busy,
                ) { pickSong.launch(AUDIO_TYPES) }
                Spacer(Modifier.height(8.dp))
                FileRow(
                    icon = Icons.Filled.LibraryMusic,
                    label = "Backing track",
                    name = state.backingName,
                    enabled = !busy,
                ) { pickBacking.launch(AUDIO_TYPES) }
            }

            // ------------------------------------------------------------ 2. Extrait
            Section("2. Extrait") {
                if (state.loadingWaveform) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Analyse de la musique…")
                    }
                }
                val total = state.timelineMs
                if (total <= 0) {
                    Text("Choisissez une musique pour sélectionner un extrait.",
                        style = MaterialTheme.typography.bodyMedium)
                } else {
                    Waveform(
                        peaks = state.waveform,
                        totalMs = total,
                        startMs = state.startMs,
                        endMs = state.endMs,
                        positionMs = state.positionMs,
                    )
                    RangeSlider(
                        value = state.startMs.toFloat()..state.endMs.toFloat(),
                        onValueChange = { r -> vm.setSelection(r.start.toLong(), r.endInclusive.toLong()) },
                        valueRange = 0f..total.toFloat(),
                        enabled = !busy,
                    )
                    TimeAdjuster("Début", state.startMs, !busy, vm::nudgeStart)
                    TimeAdjuster("Fin", state.endMs, !busy, vm::nudgeEnd)
                    Text(
                        "Durée de l'extrait : ${formatTime(state.endMs - state.startMs)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlayButton("Musique", state.mode == Mode.PLAYING_SONG, busy, Modifier.weight(1f),
                            onPlay = vm::playSong, onStop = vm::stop)
                        PlayButton("Backing", state.mode == Mode.PLAYING_BACKING, busy, Modifier.weight(1f),
                            onPlay = vm::playBacking, onStop = vm::stop)
                    }
                }
            }

            // ------------------------------------------------------------ 3. Enregistrement
            Section("3. Enregistrement") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Branchez des écouteurs (filaires de préférence) pour que le micro ne capte pas le backing track.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(12.dp))
                val recording = state.mode == Mode.RECORDING
                val recordingSolo = state.mode == Mode.RECORDING_SOLO
                Button(
                    onClick = { if (recording) vm.stop() else startRecording(solo = false) },
                    enabled = recording || (!busy && state.backingUri != null && total(state) > 0),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(if (recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (recording) "Arrêter l'enregistrement" else "Enregistrer ma voix sur le backing")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { if (recordingSolo) vm.stop() else startRecording(solo = true) },
                    enabled = recordingSolo || !busy,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(
                        if (recordingSolo) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = null,
                        tint = Color(0xFFD32F2F),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (recordingSolo) "Arrêter (${formatTime(state.recordElapsedMs)})" else "Enregistrer ma voix seule (sans backing)")
                }
                if (recording || recordingSolo) {
                    Spacer(Modifier.height(8.dp))
                    Text("Niveau du micro", style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(
                        progress = { state.inputLevel.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = if (state.inputLevel > 0.95f) Color.Red else Color(0xFF43A047),
                    )
                }
                if (state.mode == Mode.PREPARING) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Préparation…")
                    }
                }
            }

            // ------------------------------------------------------------ 4. Réécoute
            Section("4. Réécoute et sauvegarde") {
                if (state.takes.isEmpty()) {
                    Text("Aucune prise pour l'instant.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    state.takes.forEach { take ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) { vm.selectTake(take) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.selectedTake?.file == take.file,
                                onClick = { vm.selectTake(take) },
                                enabled = !busy,
                            )
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (take.saved) {
                                        Icon(
                                            Icons.Filled.Bookmark, contentDescription = "Sauvegardée",
                                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(
                                        take.name ?: "Prise du ${take.label}",
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    if (take.withBacking) {
                                        "Sur le backing · ${formatTime(take.startMs)} → ${formatTime(take.endMs)}"
                                    } else {
                                        "Sans backing · ${formatTime(take.endMs - take.startMs)}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(onClick = { naming = take }, enabled = !busy) {
                                Icon(
                                    if (take.saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                    contentDescription = "Sauvegarder",
                                )
                            }
                            IconButton(
                                onClick = {
                                    exporting = take
                                    exportLauncher.launch(vm.exportFileName(take))
                                },
                                enabled = !busy,
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = "Exporter la voix seule")
                            }
                            IconButton(onClick = { deleting = take }, enabled = !busy) {
                                Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlayButton("Voix seule", state.mode == Mode.PLAYING_VOICE, busy, Modifier.weight(1f),
                            icon = Icons.Filled.RecordVoiceOver, onPlay = vm::playVoice, onStop = vm::stop)
                        PlayButton("Voix + backing", state.mode == Mode.PLAYING_MIX, busy, Modifier.weight(1f),
                            enabled = state.selectedTake?.withBacking != false,
                            onPlay = vm::playMix, onStop = vm::stop)
                    }
                    Spacer(Modifier.height(12.dp))
                    LabeledSlider("Volume voix", state.voiceVolume, 0f..2f, "${(state.voiceVolume * 100).toInt()} %",
                        vm::setVoiceVolume)
                    LabeledSlider("Volume backing", state.backingVolume, 0f..1.5f,
                        "${(state.backingVolume * 100).toInt()} %", vm::setBackingVolume)
                    LabeledSlider("Compensation de latence", state.latencyMs.toFloat(), 0f..500f,
                        "${state.latencyMs} ms", { vm.setLatency(it.toInt()) })
                    Text(
                        "Si votre voix semble en retard sur le backing, augmentez la compensation " +
                            "(les écouteurs Bluetooth demandent souvent 200 à 300 ms).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun total(state: UiState) = state.timelineMs

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun FileRow(icon: ImageVector, label: String, name: String?, enabled: Boolean, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                name ?: "Aucun fichier (MP3 ou WAV)",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FilledTonalButton(onClick = onPick, enabled = enabled) {
            Text(if (name == null) "Choisir" else "Changer")
        }
    }
}

@Composable
private fun TimeAdjuster(label: String, valueMs: Long, enabled: Boolean, onNudge: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label : ${formatTime(valueMs)}", Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium)
        listOf(-1000L to "-1s", -100L to "-0,1", 100L to "+0,1", 1000L to "+1s").forEach { (d, t) ->
            TextButton(onClick = { onNudge(d) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(t, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PlayButton(
    label: String,
    playing: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.PlayArrow,
    enabled: Boolean = true,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    OutlinedButton(
        onClick = if (playing) onStop else onPlay,
        enabled = playing || (!busy && enabled),
        modifier = modifier,
    ) {
        Icon(if (playing) Icons.Filled.Stop else icon, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SaveDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sauvegarder la voix") },
        text = {
            Column {
                Text(
                    "La voix est gardée dans l'application sous ce nom. " +
                        "Utilisez le bouton de téléchargement pour l'exporter en WAV sur le téléphone.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Sauvegarder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(valueText, style = MaterialTheme.typography.bodyMedium)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

@Composable
private fun Waveform(peaks: FloatArray?, totalMs: Long, startMs: Long, endMs: Long, positionMs: Long?) {
    val waveColor = MaterialTheme.colorScheme.primary
    val dimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    val selColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val cursorColor = MaterialTheme.colorScheme.secondary
    Box(
        Modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
    ) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp)) {
            val w = size.width
            val h = size.height
            val x0 = w * startMs / totalMs
            val x1 = w * endMs / totalMs
            drawRect(selColor, topLeft = Offset(x0, 0f), size = Size(x1 - x0, h))
            if (peaks != null && peaks.isNotEmpty()) {
                val barW = w / peaks.size
                for (i in peaks.indices) {
                    val x = i * barW + barW / 2
                    val amp = (peaks[i] * h / 2).coerceAtLeast(1f)
                    val inside = x in x0..x1
                    drawLine(
                        if (inside) waveColor else dimColor,
                        Offset(x, h / 2 - amp), Offset(x, h / 2 + amp),
                        strokeWidth = (barW * 0.7f).coerceAtLeast(1f),
                    )
                }
            }
            drawLine(waveColor, Offset(x0, 0f), Offset(x0, h), strokeWidth = 3f)
            drawLine(waveColor, Offset(x1, 0f), Offset(x1, h), strokeWidth = 3f)
            positionMs?.let {
                val x = w * it / totalMs
                drawLine(cursorColor, Offset(x, 0f), Offset(x, h), strokeWidth = 4f)
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val clamped = ms.coerceAtLeast(0)
    val min = clamped / 60_000
    val sec = (clamped % 60_000) / 1000.0
    return String.format(Locale.FRANCE, "%d:%04.1f", min, sec)
}
