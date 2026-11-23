package com.fnfmodmaker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.fnfmodmaker.model.hasUnsupportedFeatures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SimpleStagePanel(vm: WizardViewModel) {
    val stage = vm.config.stageDraft
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.pickStageBackground(uri) }
    SectionTitle("Escenario sencillo · opcional")
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Usar mi fondo", fontWeight = FontWeight.Bold)
                    Text("Se usará en todas las canciones del mod.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(stage.enabled, vm::setSimpleStageEnabled, enabled = !vm.busy)
            }
            if (stage.enabled) {
                Text("Crea una base estática para los motores elegidos. No crea animaciones, cámaras dinámicas ni cinemáticas.", style = MaterialTheme.typography.bodySmall)
                Field("Nombre corto del escenario", stage.stageId, vm::setStageId, hint = "La app usará el ID ${stage.stageFolder}.", enabled = !vm.busy)
                Button(onClick = { picker.launch(arrayOf("image/png", "image/jpeg")) }, enabled = !vm.busy) { Text("Elegir imagen de fondo") }
                Text(stage.backgroundImage?.displayName ?: "Aún no has elegido un fondo.", style = MaterialTheme.typography.bodySmall)
                StageScenePreview(stage.backgroundImage?.path, stage.cameraPosition.zoom)
                Text("Encuadre inicial: ${stage.cameraPosition.zoom}", fontWeight = FontWeight.SemiBold)
                Row {
                    listOf("Amplio" to 0.7f, "Normal" to 1f, "Cerca" to 1.3f).forEach { (label, zoom) ->
                        FilterChip(stage.cameraPosition.zoom == zoom, onClick = { vm.setSimpleStageZoom(zoom) }, enabled = !vm.busy, label = { Text(label) }, modifier = Modifier.padding(end = 4.dp))
                    }
                }
                Text("La imagen comienza en −600, −200; las posiciones de personajes son orientativas. Prueba el resultado en cada motor. Para cambios de cámara o cinemáticas pide al asistente un plan.", style = MaterialTheme.typography.labelSmall)
                if (stage.hasUnsupportedFeatures()) Text("Este borrador contiene objetos avanzados no compatibles: desactiva este escenario para conservarlos.", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
