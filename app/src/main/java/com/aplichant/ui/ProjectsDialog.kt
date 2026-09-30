package com.aplichant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Liste des projets : ouvrir, créer, renommer, supprimer. */
@Composable
fun ProjectsDialog(state: UiState, vm: SingViewModel, onDismiss: () -> Unit) {
    // Projet en cours de renommage (ou "" pour un nouveau projet), et projet à supprimer.
    var naming by remember { mutableStateOf<ProjectInfo?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProjectInfo?>(null) }

    if (creating || naming != null) {
        NameDialog(
            title = if (creating) "Nouveau projet" else "Renommer le projet",
            initial = naming?.name ?: "",
            hint = if (creating) "Laissez vide pour prendre le nom de la musique" else null,
            onDismiss = { creating = false; naming = null },
            onConfirm = { name ->
                if (creating) {
                    vm.newProject(name)
                    onDismiss()
                } else {
                    naming?.let { vm.renameProject(it.id, name) }
                }
                creating = false
                naming = null
            },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer « ${p.name} » ?") },
            text = { Text("Les paroles, les réglages et toutes les prises de ce projet seront effacés. Les fichiers audio d'origine ne sont pas touchés.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProject(p.id)
                    deleting = null
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mes projets") },
        text = {
            Column {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    state.projects.forEach { p ->
                        val current = p.id == state.projectId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable {
                                    vm.openProject(p.id)
                                    onDismiss()
                                }
                                .padding(start = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                p.name,
                                Modifier.weight(1f),
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { naming = p }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Renommer")
                            }
                            IconButton(onClick = { deleting = p }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
                            }
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Nouveau projet")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    hint: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nom") }, singleLine = true)
                hint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
