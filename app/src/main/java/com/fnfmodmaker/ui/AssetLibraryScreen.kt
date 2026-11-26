package com.fnfmodmaker.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fnfmodmaker.model.AssetCategory
import com.fnfmodmaker.model.AssetPack
import com.fnfmodmaker.model.CatalogAsset
import com.fnfmodmaker.project.AssetPackCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Asset Library 1.0: importa paquetes de recursos, navega el catálogo y reutiliza los compatibles. */
@Composable
internal fun AssetLibraryScreen(vm: WizardViewModel) {
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.prepareAssetPackImport(uri) }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("TODAS") }
    var gridMode by rememberSaveable { mutableStateOf(true) }
    var detailAsset by remember { mutableStateOf<CatalogAsset?>(null) }
    var deletePack by remember { mutableStateOf<AssetPack?>(null) }
    val packs = vm.config.assetPacks
    val visible = packs.flatMap { pack -> pack.assets.map { pack to it } }.filter { (_, asset) ->
        val matchesText = search.isBlank() ||
            asset.displayName.contains(search, ignoreCase = true) ||
            asset.sourcePath.contains(search, ignoreCase = true)
        matchesText && (filter == "TODAS" || asset.type.name == filter)
    }

    vm.assetPackReview?.let { review ->
        AlertDialog(onDismissRequest = vm::cancelAssetPackImport,
            title = { Text("Revisar paquete de recursos") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${review.name} · ${review.entries} archivos · ${review.totalBytes / (1024 * 1024) + 1} MB", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                review.categories.forEach { (label, count) -> Text("• $label: $count", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(6.dp))
                review.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text("Tras confirmar, los archivos se copian al proyecto activo conservando su jerarquía original; nada cambia si cancelas.",
                    style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = vm::confirmAssetPackImport, enabled = !vm.busy) { Text("Importar al proyecto") } },
            dismissButton = { TextButton(onClick = vm::cancelAssetPackImport, enabled = !vm.busy) { Text("Cancelar") } })
    }

    detailAsset?.let { asset ->
        AlertDialog(onDismissRequest = { detailAsset = null },
            title = { Text(asset.displayName) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                AssetThumbnail(asset, Modifier.fillMaxWidth().height(160.dp).background(Color(0xFF101018)))
                Spacer(Modifier.height(8.dp))
                Text("Categoría: ${asset.type.label}", style = MaterialTheme.typography.bodySmall)
                Text("Paquete: ${packs.firstOrNull { it.id == asset.packId }?.sourceName ?: asset.packId}", style = MaterialTheme.typography.bodySmall)
                Text("Ruta original: ${asset.sourcePath}", style = MaterialTheme.typography.bodySmall)
                Text("Extensión: ${asset.extension.ifBlank { "sin extensión" }} · ${asset.sizeBytes / 1024 + 1} KB", style = MaterialTheme.typography.bodySmall)
                if (asset.width > 0) Text("Dimensiones: ${asset.width} × ${asset.height}", style = MaterialTheme.typography.bodySmall)
                Text("SHA-256: ${asset.sha256.take(16)}…", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                val pack = packs.firstOrNull { it.id == asset.packId }
                if (asset.type == AssetCategory.ATLAS) {
                    TextButton(onClick = { vm.useLibraryAssetAsCharacterAtlas(asset); detailAsset = null }, enabled = !vm.busy) {
                        Text("Usar como atlas del personaje (con su PNG emparejado)") }
                    if (asset.relatedId.isEmpty()) Text("Sin PNG emparejado inequívoco: el XML no puede usarse solo.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                if (asset.type == AssetCategory.SPRITE || asset.type == AssetCategory.ATLAS || asset.extension.equals("png", true)) {
                    TextButton(onClick = { vm.useLibraryAssetAsCharacterSheet(asset); detailAsset = null }, enabled = !vm.busy) {
                        Text("Usar como hoja de sprites del personaje (PNG)") }
                }
                if (asset.extension.equals("png", true)) {
                    TextButton(onClick = { vm.useLibraryAssetAsCharacterIcon(asset); detailAsset = null }, enabled = !vm.busy) {
                        Text("Usar como icono del personaje") }
                    TextButton(onClick = { vm.useLibraryAssetAsModIcon(asset); detailAsset = null }, enabled = !vm.busy) {
                        Text("Usar como icono del mod") }
                }
                if (asset.type in listOf(AssetCategory.STAGE, AssetCategory.PROPS, AssetCategory.OTHER, AssetCategory.UNCLASSIFIED) ||
                    asset.extension.equals("png", true) || asset.extension.equals("jpg", true) || asset.extension.equals("jpeg", true) || asset.extension.equals("webp", true)) {
                    TextButton(onClick = { vm.useLibraryAssetAsStageBackground(asset); detailAsset = null }, enabled = !vm.busy) {
                        Text("Usar como fondo del escenario") }
                }
                if (pack == null || (asset.type != AssetCategory.ATLAS && asset.type != AssetCategory.SPRITE &&
                    !asset.extension.equals("png", true) && !asset.extension.equals("jpg", true) &&
                    !asset.extension.equals("jpeg", true) && !asset.extension.equals("webp", true))) {
                    Text("Este tipo de recurso aún no tiene asignaciones compatibles; queda disponible en el catálogo.",
                        style = MaterialTheme.typography.labelSmall)
                }
            } },
            confirmButton = { TextButton(onClick = { detailAsset = null }) { Text("Cerrar") } })
    }

    deletePack?.let { pack ->
        AlertDialog(onDismissRequest = { deletePack = null },
            title = { Text("¿Eliminar el paquete \"${pack.sourceName}\"?") },
            text = { Text("Se borran ${pack.assets.size} archivos del proyecto. Si algún recurso está asignado al personaje, escenario o mod, la eliminación se rechaza y se explica.") },
            confirmButton = { TextButton(onClick = { vm.removeAssetPack(pack.id); deletePack = null }, enabled = !vm.busy) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { deletePack = null }) { Text("Cancelar") } })
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { ToolTopBar("Biblioteca de assets", "${packs.size} paquete(s) · ${visible.size} recurso(s) visible(s)", vm::goHome) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            if (vm.error != null) Text(vm.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (vm.status.isNotBlank() && vm.busy) Text(vm.status, style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream")) },
                    enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Importar ZIP de recursos") }
                FilterChip(gridMode, { gridMode = true }, label = { Text("Cuadrícula") })
                FilterChip(!gridMode, { gridMode = false }, label = { Text("Lista") })
            }
            Spacer(Modifier.height(8.dp))
            packs.forEach { pack ->
                Surface(Modifier.fillMaxWidth().padding(vertical = 3.dp), tonalElevation = 2.dp) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pack.sourceName.ifBlank { "Paquete" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${pack.assets.size} archivos · ${pack.totalBytes / (1024 * 1024) + 1} MB · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(pack.importedAt))}",
                                style = MaterialTheme.typography.labelSmall)
                            Text(pack.engineHint, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { deletePack = pack }, enabled = !vm.busy) { Text("Eliminar") }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Field("Buscar por nombre o ruta", search, { search = it }, enabled = !vm.busy)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(filter == "TODAS", { filter = "TODAS" }, label = { Text("Todas") })
                AssetCategory.entries.forEach { category ->
                    FilterChip(filter == category.name, { filter = category.name }, label = { Text(category.label) })
                }
            }
            Spacer(Modifier.height(6.dp))
            when {
                packs.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("La biblioteca está vacía", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Importa un ZIP con los recursos originales del juego (sprites, atlas, música, fuentes). No necesita pack.json ni metadata de mod; los archivos se conservan íntegros dentro del proyecto.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
                }
                visible.isEmpty() -> Text("Sin resultados para la búsqueda o el filtro actual.", style = MaterialTheme.typography.bodySmall)
                gridMode -> LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 110.dp), modifier = Modifier.weight(1f)) {
                    items(visible, key = { (_, asset) -> asset.id }) { (pack, asset) ->
                        AssetGridCard(asset, pack.sourceName) { detailAsset = asset }
                    }
                }
                else -> LazyColumn(Modifier.weight(1f)) {
                    items(visible, key = { (_, asset) -> asset.id }) { (pack, asset) ->
                        AssetListRow(asset, pack.sourceName) { detailAsset = asset }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AssetGridCard(asset: CatalogAsset, packName: String, onOpen: () -> Unit) {
    Surface(modifier = Modifier.padding(4.dp).clickable(onClick = onOpen), tonalElevation = 2.dp) {
        Column(Modifier.padding(6.dp)) {
            AssetThumbnail(asset, Modifier.fillMaxWidth().height(84.dp).background(Color(0xFF101018)))
            Spacer(Modifier.height(4.dp))
            Text(asset.displayName, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(asset.type.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${asset.sizeBytes / 1024 + 1} KB · ${packName.take(18)}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AssetListRow(asset: CatalogAsset, packName: String, onOpen: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(onClick = onOpen), tonalElevation = 1.dp) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AssetThumbnail(asset, Modifier.width(56.dp).height(56.dp).background(Color(0xFF101018)))
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(asset.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(asset.sourcePath, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${asset.type.label} · ${asset.sizeBytes / 1024 + 1} KB" + if (asset.width > 0) " · ${asset.width}×${asset.height}" else "" + " · $packName",
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Miniatura bajo demanda: decodifica en segundo plano con muestreo limitado y sin guardar cachés globales. */
@Composable
private fun AssetThumbnail(asset: CatalogAsset, modifier: Modifier = Modifier) {
    val path = asset.file?.path
    val isImage = AssetPackCatalog.isImage(asset.sourcePath)
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        if (path != null && isImage) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 128 && bounds.outHeight / (sample * 2) >= 128) sample *= 2
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image, contentDescription = asset.displayName, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Text(asset.extension.uppercase().ifBlank { "—" }, style = MaterialTheme.typography.labelMedium, color = Color(0xFFB9B3C4))
    }
}
