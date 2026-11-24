package com.fnfmodmaker.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fnfmodmaker.model.CharacterRole

@Composable
internal fun CharacterLibraryPanel(vm: WizardViewModel) {
    val config = vm.config
    var removeId by remember { mutableStateOf<String?>(null) }
    Text("Personajes (${config.characterLibrary.size})", fontWeight = FontWeight.Bold)
    config.characterLibrary.forEach { entry ->
        Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), tonalElevation = 2.dp) {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val image = entry.draft.atlasSheet ?: entry.draft.sheet
                ImageAssetPreview(image?.path, "Personaje ${entry.draft.displayName}")
                Column(Modifier.weight(1f)) {
                    Text(entry.draft.displayName.ifBlank { "Sin nombre" }, fontWeight = FontWeight.SemiBold)
                    Text("Archivo: ${entry.draft.charFolder}", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { vm.selectLibraryCharacter(entry.id) }, enabled = !vm.busy && entry.id != config.selectedCharacterId) { Text(if (entry.id == config.selectedCharacterId) "Seleccionado" else "Editar") }
                    if (config.characterLibrary.size > 1) TextButton(onClick = { removeId = entry.id }, enabled = !vm.busy) { Text("Eliminar") }
                }
            }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        OutlinedButton(onClick = { vm.createLibraryCharacter() }, enabled = !vm.busy && config.characterLibrary.size < 64) { Text("Crear personaje") }
        TextButton(onClick = { vm.createLibraryCharacter(duplicate = true) }, enabled = !vm.busy && config.characterLibrary.size < 64) { Text("Duplicar seleccionado") }
    }
    Text("Asigna por ID estable; una selección puede ocupar varios roles. El personaje sin asignar queda guardado, pero no se exporta.", style = MaterialTheme.typography.bodySmall)
    if (config.songs.any { it.vSliceOrigin != null }) Text("En V-Slice importado, las asignaciones explícitas cambian solo esos personajes en la metadata del ZIP; el original del proyecto se conserva.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    val assignment = config.resolvedAssignments()
    CharacterRole.entries.forEach { role ->
        val id = when (role) { CharacterRole.PLAYER -> assignment.player; CharacterRole.OPPONENT -> assignment.opponent; CharacterRole.GIRLFRIEND -> assignment.girlfriend }
        Text("${role.label} · ${config.characterLibrary.firstOrNull { it.id == id }?.draft?.charFolder ?: "ID de la canción"}", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(id.isEmpty(), { vm.assignLibraryCharacter(role, "") }, enabled = !vm.busy, label = { Text("Usar canción") }, modifier = Modifier.padding(end = 4.dp))
            config.characterLibrary.forEach { entry ->
                FilterChip(id == entry.id, { vm.assignLibraryCharacter(role, entry.id) }, enabled = !vm.busy, label = { Text(entry.draft.charFolder) }, modifier = Modifier.padding(end = 4.dp))
            }
        }
    }
    removeId?.let { id -> AlertDialog(onDismissRequest = { removeId = null }, title = { Text("¿Eliminar personaje?") },
        text = { Text("Se quitará de la biblioteca y de sus asignaciones. Los recursos originales del mod importado se conservan.") },
        confirmButton = { TextButton(onClick = { vm.removeLibraryCharacter(id); removeId = null }) { Text("Eliminar") } },
        dismissButton = { TextButton(onClick = { removeId = null }) { Text("Cancelar") } }) }
}
