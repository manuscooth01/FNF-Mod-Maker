package com.fnfmodmaker.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CharacterAiPanel(vm: WizardViewModel) {
    val chooseImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickAiAnalysisImage(uri)
    }
    val saveImage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) vm.saveAiGeneratedImage(uri)
    }
    var settingsOpen by rememberSaveable { mutableStateOf(true) }
    var confirmAction by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Text("Asistente de IA", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text("Opcional: usa un proveedor HTTPS compatible con la API de OpenAI. Cada proveedor y modelo admite funciones distintas y puede cobrar por uso.", style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { settingsOpen = !settingsOpen }) { Text(if (settingsOpen) "Ocultar configuración ↑" else "Configurar proveedor ↓") }
    if (settingsOpen) {
        Field("URL base del proveedor", vm.aiBaseUrl, { vm.aiBaseUrl = it },
            hint = "Ejemplo: https://api.openai.com/v1 · usa solo proveedores de confianza.", enabled = !vm.aiBusy)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = vm.aiKey, onValueChange = { vm.aiKey = it },
            label = { Text("Clave API") }, visualTransformation = PasswordVisualTransformation(),
            singleLine = true, enabled = !vm.aiBusy, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
        Text("La clave solo se mantiene en memoria mientras la app está abierta; no se guarda en el proyecto.", style = MaterialTheme.typography.labelSmall)
        Row {
            TextButton(onClick = vm::clearAiKey, enabled = !vm.aiBusy && vm.aiKey.isNotEmpty()) { Text("Borrar clave") }
            TextButton(onClick = { confirmClear = true }, enabled = !vm.aiBusy) { Text("Limpiar sesión IA") }
        }
        if (confirmClear) androidx.compose.material3.AlertDialog(onDismissRequest = { confirmClear = false },
            title = { Text("¿Limpiar la sesión de IA?") },
            text = { Text("Se borrarán la clave, la consulta, la respuesta y las imágenes temporales. Las imágenes que guardaste no se modificarán.") },
            confirmButton = { TextButton(onClick = { vm.clearAiSession(); confirmClear = false }) { Text("Limpiar") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } })
        Field("Modelo de texto / visión", vm.aiChatModel, { vm.aiChatModel = it }, hint = "Debe admitir chat/completions; para imágenes, también visión.", enabled = !vm.aiBusy)
        Spacer(Modifier.height(8.dp))
        Field("Modelo de imágenes", vm.aiImageModel, { vm.aiImageModel = it }, hint = "Debe admitir images/generations y devolver b64_json o URL HTTPS.", enabled = !vm.aiBusy)
    }
    SectionTitle("¿En qué te ayudo?")
    Field("Consulta o descripción", vm.aiPrompt, { vm.aiPrompt = it.take(4000) },
        hint = "Ejemplo: ¿cómo puedo mejorar la posición del rival?", singleLine = false, enabled = !vm.aiBusy)
    Spacer(Modifier.height(8.dp))
    Text("Para analizar, elige una imagen. Se reduce y se eliminan sus metadatos; no se envía hasta que confirmes el análisis.", style = MaterialTheme.typography.bodySmall)
    Text(vm.aiAnalysisName ?: "Ninguna imagen elegida", style = MaterialTheme.typography.labelSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { chooseImage.launch(arrayOf("image/*")) }, enabled = !vm.aiBusy) { Text("Elegir imagen") }
        if (vm.aiAnalysisFile != null) TextButton(onClick = vm::clearAiAnalysisImage, enabled = !vm.aiBusy) { Text("Quitar") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = { confirmAction = "advice" }, enabled = !vm.aiBusy && vm.aiPrompt.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Pedir consejos") }
        OutlinedButton(onClick = { confirmAction = "analyze" }, enabled = !vm.aiBusy && vm.aiPrompt.isNotBlank() && vm.aiAnalysisFile != null,
            modifier = Modifier.fillMaxWidth()) { Text("Analizar imagen elegida") }
        OutlinedButton(onClick = { confirmAction = "generate" }, enabled = !vm.aiBusy && vm.aiPrompt.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) { Text("Generar ilustración") }
    }
    confirmAction?.let { action ->
        val description = when (action) {
            "analyze" -> "Se enviarán la consulta, los ajustes del personaje y una copia reducida de la imagen elegida al proveedor configurado."
            "generate" -> "Se enviará tu descripción al proveedor configurado para generar una imagen."
            else -> "Se enviarán la consulta y los ajustes del personaje al proveedor configurado; no se enviará ninguna imagen."
        }
        androidx.compose.material3.AlertDialog(onDismissRequest = { confirmAction = null },
            title = { Text("¿Enviar al proveedor de IA?") },
            text = { Text("$description Puede haber cargos y se aplican sus políticas de privacidad.") },
            confirmButton = { TextButton(onClick = {
                when (action) { "analyze" -> vm.askCharacterAi(true); "generate" -> vm.generateCharacterAiImage(); else -> vm.askCharacterAi(false) }
                confirmAction = null
            }) { Text("Enviar") } },
            dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("Cancelar") } })
    }
    if (vm.aiBusy) Text(vm.aiStatus, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    vm.aiError?.let { Spacer(Modifier.height(8.dp)); MessageCard(it, error = true) }
    vm.aiResult?.let { SectionTitle("Respuesta"); Text(it, style = MaterialTheme.typography.bodyMedium) }
    vm.aiGeneratedFile?.let { path ->
        SectionTitle("Ilustración generada")
        val image by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
            value = withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }
        }
        image?.let { Image(it, contentDescription = "Ilustración generada por IA", modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) }
        Text("Es una ilustración, no un personaje animado ni un atlas listo para jugar. Puedes guardarla y usarla como referencia o seleccionarla después como frame PNG.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { saveImage.launch("personaje-ia.png") }, enabled = !vm.aiBusy) { Text("Guardar imagen PNG") }
        vm.aiSavedImage?.let { Text("Imagen guardada: $it", style = MaterialTheme.typography.bodySmall) }
    }
}
