package com.aplichant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Section « Paroles » : coller les paroles, caler leur timing, choisir l'extrait par lignes. */
@Composable
fun LyricsSection(state: UiState, busy: Boolean, vm: SingViewModel) {
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        LyricsDialog(
            initial = vm.lyricsText(),
            onDismiss = { editing = false },
            onSave = {
                vm.setLyricsText(it)
                editing = false
            },
        )
    }

    if (state.songUri == null) {
        Text("Choisissez une musique pour ajouter ses paroles.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    if (state.lyrics.isEmpty()) {
        Text(
            "Collez les paroles : l'application repère quand chaque ligne est chantée, " +
                "puis vous choisissez les phrases à travailler.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { editing = true }, enabled = !busy) {
            Icon(Icons.Filled.ContentPaste, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Coller les paroles")
        }
        return
    }

    // ------------------------------------------------------------ Actions
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = vm::detectLyricsTiming, enabled = !busy, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.AutoFixHigh, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Détecter", maxLines = 1)
        }
        OutlinedButton(
            onClick = { vm.startManualSync(state.selectedLines.minOrNull() ?: 0) },
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Filled.TouchApp, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Caler à la main", maxLines = 1)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { editing = true }, enabled = !busy) {
            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Modifier les paroles")
        }
        if (state.selectedLines.isNotEmpty()) {
            TextButton(onClick = vm::clearLineSelection, enabled = !busy) { Text("Tout décocher") }
        }
    }

    if (state.mode == Mode.ANALYZING) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Analyse du morceau…", Modifier.weight(1f))
            TextButton(onClick = vm::stop) { Text("Arrêter") }
        }
    }

    // ------------------------------------------------------------ Calage manuel
    if (state.mode == Mode.SYNCING) {
        val next = state.lyrics.getOrNull(state.syncIndex)
        Text(
            "Appuyez sur le gros bouton au moment où chaque ligne commence.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = vm::syncTap,
            modifier = Modifier.fillMaxWidth().height(96.dp),
        ) {
            Text(
                next?.text ?: "Fin de la dernière ligne",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
        TextButton(onClick = vm::stop) { Text("Arrêter le calage") }
    } else {
        Text(
            "Cochez les lignes à travailler : l'extrait va de la première à la dernière ligne cochée.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Spacer(Modifier.height(4.dp))

    // ------------------------------------------------------------ Lignes
    val current = if (state.mode == Mode.SYNCING) state.syncIndex - 1 else state.currentLine()
    state.lyrics.forEachIndexed { i, line ->
        val highlighted = i == current
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (highlighted) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                    RoundedCornerShape(6.dp),
                )
                .clickable(enabled = !busy) { vm.toggleLine(i) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = i in state.selectedLines, onCheckedChange = { vm.toggleLine(i) }, enabled = !busy)
            Text(
                if (line.startMs >= 0) formatTime(line.startMs) else "–:––",
                Modifier.width(56.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                line.text,
                Modifier.weight(1f).padding(vertical = 6.dp),
                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

/** Ligne de paroles chantée en ce moment, affichée en gros pendant la lecture et l'enregistrement. */
@Composable
fun CurrentLyric(state: UiState) {
    val i = state.currentLine()
    if (i < 0) return
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            state.lyrics[i].text,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary,
        )
        state.lyrics.getOrNull(i + 1)?.let {
            Text(
                it.text,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LyricsDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paroles") },
        text = {
            Column {
                Text(
                    "Une ligne par phrase chantée. Mettez les refrains autant de fois qu'ils sont chantés. " +
                        "Les lignes vides et les repères comme [Refrain] sont ignorés.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { clipboard.getText()?.text?.let { text = it } }) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Coller depuis le presse-papiers")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 12,
                    placeholder = { Text("Collez ou tapez les paroles ici") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
