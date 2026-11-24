package com.fnfmodmaker.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fnfmodmaker.appearance.AppearanceSlot
import com.fnfmodmaker.appearance.LocalAppearanceStore
import com.fnfmodmaker.appearance.findAppearanceImages
import com.fnfmodmaker.appearance.withAppearanceArchive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI state is app-wide, while storage is deliberately outside all project directories. */
@Stable
internal class LocalAppearanceController(context: Context) {
    private val app = context.applicationContext
    private val store = LocalAppearanceStore(app)
    private val preferences = context.applicationContext.getSharedPreferences("local_appearance", Context.MODE_PRIVATE)
    var firstHalf by mutableStateOf(preferences.getBoolean("first_half", false)); private set
    fun updateFirstHalf(value: Boolean) { firstHalf = value; preferences.edit().putBoolean("first_half", value).apply() }
    var background by mutableStateOf<ImageBitmap?>(null); private set
    var icon by mutableStateOf<ImageBitmap?>(null); private set
    var busy by mutableStateOf(false); private set
    var importingArchive by mutableStateOf(false); private set
    var archiveProgress by mutableStateOf(""); private set
    val archivePromptSeen: Boolean get() = preferences.getBoolean("archive_prompt_seen", false)
    fun markArchivePromptSeen() { preferences.edit().putBoolean("archive_prompt_seen", true).apply() }
    var message by mutableStateOf<String?>(null); private set

    suspend fun load() {
        if (busy) return
        busy = true
        val result = withContext(Dispatchers.IO) { runCatching {
            store.discardAbandonedArchives()
            store.read(AppearanceSlot.BACKGROUND)?.asImageBitmap() to store.read(AppearanceSlot.ICON)?.asImageBitmap()
        } }
        result.onSuccess { (bg, image) -> background = bg; icon = image }
            .onFailure { message = "No se pudo cargar la apariencia local: ${it.message}" }
        busy = false
    }
    suspend fun choose(slot: AppearanceSlot, uri: Uri) {
        if (busy) return
        busy = true; message = null
        val result = withContext(Dispatchers.IO) { runCatching { store.replace(slot, uri); store.read(slot)?.asImageBitmap() ?: error("No se pudo leer la imagen guardada.") } }
        result.onSuccess { image ->
            if (slot == AppearanceSlot.BACKGROUND) background = image else icon = image
            message = "Imagen guardada solo en este dispositivo."
        }.onFailure { message = "No se cambió la imagen: ${it.message ?: "archivo no compatible"}." }
        busy = false
    }
    suspend fun importArchive(uri: Uri) {
        if (busy) return
        busy = true; importingArchive = true; message = null
        archiveProgress = "Abriendo el ZIP…"
        val coroutine = currentCoroutineContext()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        try {
            val report = withContext(Dispatchers.IO) {
                withAppearanceArchive(app, uri, coroutine, onFallback = {
                    handler.post { if (importingArchive) archiveProgress = "Copiando temporalmente el ZIP: puede necesitar hasta 2 GiB de espacio y tardar varios minutos…" }
                }) { zip ->
                    val files = findAppearanceImages(zip) { coroutine.ensureActive() }
                    require(files.isNotEmpty()) { "No se encontró menuBG.png ni icon-bf.png en las rutas del repositorio de FNF." }
                    val successes = mutableListOf<AppearanceSlot>(); val errors = mutableListOf<String>()
                    files.forEach { (slot, entry) ->
                        coroutine.ensureActive()
                        try { zip.getInputStream(entry).use { store.replaceFromStream(slot, it) }; successes.add(slot) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { errors.add("${if (slot == AppearanceSlot.BACKGROUND) "fondo" else "icono"}: ${e.message}") }
                    }
                    val bg = if (AppearanceSlot.BACKGROUND in successes) store.read(AppearanceSlot.BACKGROUND)?.asImageBitmap() else null
                    val iconImage = if (AppearanceSlot.ICON in successes) store.read(AppearanceSlot.ICON)?.asImageBitmap() else null
                    Triple(successes, errors, bg to iconImage)
                }
            }
            if (AppearanceSlot.BACKGROUND in report.first) background = report.third.first
            if (AppearanceSlot.ICON in report.first) { icon = report.third.second; updateFirstHalf(true) }
            val applied = report.first.joinToString { if (it == AppearanceSlot.BACKGROUND) "fondo" else "icono" }
            message = (if (applied.isEmpty()) "No se pudo aplicar ninguna imagen." else "Importado: $applied. Solo se guardaron copias locales.") +
                if (report.second.isNotEmpty()) " Errores: ${report.second.joinToString()}" else ""
        } catch (e: CancellationException) {
            // Some images may already have been applied before cancellation; reload the actual files.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                val bg = runCatching { store.read(AppearanceSlot.BACKGROUND)?.asImageBitmap() }.getOrNull()
                val iconImage = runCatching { store.read(AppearanceSlot.ICON)?.asImageBitmap() }.getOrNull()
                withContext(Dispatchers.Main) { background = bg; icon = iconImage; message = "Importación cancelada. Las imágenes ya aplicadas se conservan." }
            }
            throw e
        } catch (e: Exception) { message = "No se pudo importar el ZIP: ${e.message ?: "archivo no válido"}." }
        finally { importingArchive = false; archiveProgress = ""; busy = false }
    }
    suspend fun remove(slot: AppearanceSlot) {
        if (busy) return
        busy = true; message = null
        val result = withContext(Dispatchers.IO) { runCatching { store.remove(slot) } }
        result.onSuccess {
            if (slot == AppearanceSlot.BACKGROUND) background = null else icon = null
            message = "Imagen local eliminada; el archivo original no se modifica."
        }.onFailure { message = "No se pudo eliminar la imagen local: ${it.message}" }
        busy = false
    }
}
internal val LocalAppearance = staticCompositionLocalOf<LocalAppearanceController?> { null }

@Composable
internal fun LocalAppearancePanel() {
    val state = LocalAppearance.current ?: return
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var showInitialPrompt by rememberSaveable { mutableStateOf(!state.archivePromptSeen) }
    var zipJob by remember { mutableStateOf<Job?>(null) }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { expanded = true; zipJob = scope.launch { state.importArchive(uri) } }
    }
    if (showInitialPrompt) AlertDialog(
        onDismissRequest = { state.markArchivePromptSeen(); showInitialPrompt = false },
        title = { Text("¿Elegir ZIP de recursos visuales?") },
        text = { Text("Puedes elegir tu copia del ZIP de funkin.assets. Se buscarán el fondo de menú y el icono de Boyfriend para la interfaz. Las imágenes se guardan solo en este dispositivo; no se incluyen en el APK ni en los proyectos. Si el proveedor no permite lectura directa, se copiará temporalmente el ZIP (hasta 2 GiB). También puedes hacerlo después en Apariencia local.") },
        confirmButton = { TextButton(onClick = { state.markArchivePromptSeen(); showInitialPrompt = false; zipPicker.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text("Elegir ZIP") } },
        dismissButton = { TextButton(onClick = { state.markArchivePromptSeen(); showInitialPrompt = false }) { Text("Ahora no") } })
    if (state.importingArchive) AlertDialog(
        onDismissRequest = {}, title = { Text("Importando apariencia") }, text = { Text(state.archiveProgress) },
        confirmButton = { TextButton(onClick = { zipJob?.cancel() }) { Text("Cancelar") } })
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { state.choose(AppearanceSlot.BACKGROUND, uri) }
    }
    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { state.choose(AppearanceSlot.ICON, uri) }
    }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp)) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Apariencia local ↑" else "Personalizar apariencia ↓") }
            if (expanded) {
                Text("El ZIP de funkin.assets aplica el fondo de menú y el icono de Boyfriend si están presentes. No instala todos los recursos ni importa mods.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { zipPicker.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text("Elegir ZIP de recursos") }
                Text("También puedes elegir un fondo y un icono individuales: PNG, JPG o WebP, hasta 8 MiB y 8192 × 8192 px.", style = MaterialTheme.typography.bodySmall)
                Text("Imágenes estáticas: los atlas XML no se separan ni animan y el fondo se recorta para ajustarse. La copia privada no se incluye en .fnfproject, ZIP ni APK.", style = MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { backgroundPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }, enabled = !state.busy) { Text("Elegir fondo") }
                    TextButton(onClick = { scope.launch { state.remove(AppearanceSlot.BACKGROUND) } }, enabled = !state.busy) { Text("Quitar") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { iconPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }, enabled = !state.busy) { Text("Elegir icono") }
                    TextButton(onClick = { scope.launch { state.remove(AppearanceSlot.ICON) } }, enabled = !state.busy) { Text("Quitar") }
                }
                if (state.icon != null) TextButton(onClick = { state.updateFirstHalf(!state.firstHalf) }) {
                    Text(if (state.firstHalf) "Icono: mitad izquierda (cambiar)" else "Icono: imagen completa (cambiar)")
                }
                Text("Para hojas con dos caras iguales puedes mostrar solo la mitad izquierda. Las opciones son locales y no alteran la imagen original.", style = MaterialTheme.typography.labelSmall)
                state.message?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                if (state.busy) Text("Preparando imagen…", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun HomeAppearanceBanner(content: @Composable () -> Unit) {
    val image = LocalAppearance.current?.background
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Box {
            if (image != null) {
                Image(image, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .70f)))
            }
            content()
        }
    }
}

@Composable
internal fun LocalToolbarIcon() {
    val state = LocalAppearance.current ?: return
    val image = state.icon ?: return
    val modifier = Modifier.padding(end = 12.dp).size(36.dp).clip(RoundedCornerShape(8.dp))
    if (state.firstHalf && image.width >= 2) Canvas(modifier) {
        drawImage(image, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width / 2, image.height),
            dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)))
    } else Image(image, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
}
