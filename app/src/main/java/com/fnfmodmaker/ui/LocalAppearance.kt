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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI state is app-wide, while storage is deliberately outside all project directories. */
@Stable
internal class LocalAppearanceController(context: Context) {
    private val store = LocalAppearanceStore(context.applicationContext)
    private val preferences = context.applicationContext.getSharedPreferences("local_appearance", Context.MODE_PRIVATE)
    var firstHalf by mutableStateOf(preferences.getBoolean("first_half", false)); private set
    fun setFirstHalf(value: Boolean) { firstHalf = value; preferences.edit().putBoolean("first_half", value).apply() }
    var background by mutableStateOf<ImageBitmap?>(null); private set
    var icon by mutableStateOf<ImageBitmap?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set

    suspend fun load() {
        if (busy) return
        busy = true
        val result = withContext(Dispatchers.IO) { runCatching {
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
                Text("Elige un fondo para Inicio y un icono para las barras de herramientas. PNG, JPG o WebP, hasta 8 MiB y 8192 × 8192 px.", style = MaterialTheme.typography.bodySmall)
                Text("Imágenes estáticas: los atlas XML no se separan ni animan y el fondo se recorta para ajustarse. La copia privada no se incluye en .fnfproject, ZIP ni APK.", style = MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { backgroundPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }, enabled = !state.busy) { Text("Elegir fondo") }
                    TextButton(onClick = { scope.launch { state.remove(AppearanceSlot.BACKGROUND) } }, enabled = !state.busy) { Text("Quitar") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { iconPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }, enabled = !state.busy) { Text("Elegir icono") }
                    TextButton(onClick = { scope.launch { state.remove(AppearanceSlot.ICON) } }, enabled = !state.busy) { Text("Quitar") }
                }
                if (state.icon != null) TextButton(onClick = { state.setFirstHalf(!state.firstHalf) }) {
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
