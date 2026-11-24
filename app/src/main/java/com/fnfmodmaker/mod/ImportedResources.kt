package com.fnfmodmaker.mod

import com.fnfmodmaker.model.ImportedModResource
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** Nonconverted files never overwrite generated data. Converted originals remain in the project. */
internal fun mergeImportedResources(root: File, resources: List<ImportedModResource>, allowedAssetRoot: File, replaceExisting: Boolean = false) {
    fun key(path: String) = Normalizer.normalize(path, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    fun valid(path: String): Boolean = path.length <= 1024 && !path.startsWith('/') && !path.contains('\\') && path.split('/').let { parts ->
        parts.size in 1..32 && parts.all { p -> p.isNotBlank() && p != "." && p != ".." && !p.endsWith('.') && !p.endsWith(' ') && p.length <= 255 &&
            p.none { it.code < 32 || it.code == 127 || it in ":*?\"<>|" } && Normalizer.normalize(p, Normalizer.Form.NFKC).let { it != "." && it != ".." && !it.contains('/') && !it.contains('\\') && !it.contains(':') } }
    }
    val generated = root.walkTopDown().filter { it.isFile }.associateBy { key(it.relativeTo(root).invariantSeparatorsPath) }.toMutableMap()
    val directories = root.walkTopDown().filter { it.isDirectory && it != root }.associate { key(it.relativeTo(root).invariantSeparatorsPath) to it.relativeTo(root).invariantSeparatorsPath }.toMutableMap()
    val seen = mutableSetOf<String>()
    resources.forEach { resource ->
        val path = resource.originalPath
        require(valid(path) && seen.add(key(path))) { "Ruta original inválida o duplicada: $path" }
        val source = File(resource.asset?.path ?: error("Falta el recurso original: $path"))
        require(source.canonicalPath.startsWith(allowedAssetRoot.canonicalPath + File.separator) && source.isFile && source.canonicalFile == source.absoluteFile) { "Falta o no es seguro el recurso original: $path" }
        val parts = path.split('/')
        for (i in 1 until parts.size) {
            val parent = parts.take(i).joinToString("/")
            require(key(parent) !in generated) { "Conflicto de archivo y carpeta original: $parent" }
            val known = directories.putIfAbsent(key(parent), parent)
            require(known == null || known == parent) { "Carpeta original ambigua: $parent" }
        }
        require(key(path) !in directories) { "Conflicto de archivo y carpeta original: $path" }
        val existing = generated[key(path)]
        if (existing != null) {
            require((resource.converted || replaceExisting) && existing.relativeTo(root).invariantSeparatorsPath == path) { "Colisión entre archivo original y generado: $path. No se exportó el ZIP." }
        } else {
            val target = File(root, path)
            require(target.canonicalPath.startsWith(root.canonicalPath + File.separator) && !target.exists()) { "Ruta original insegura: $path" }
            check(target.parentFile.isDirectory || target.parentFile.mkdirs())
            source.copyTo(target, overwrite = false)
            generated[key(path)] = target
        }
    }
}
