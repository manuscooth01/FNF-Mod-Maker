package com.fnfmodmaker.model

/** Rename an imported character and update song references in one transaction. */
internal fun renameImportedCharacter(config: ModConfig, id: String, newId: String): ModConfig {
    require(newId.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}"))) { "El ID debe contener letras minúsculas, números, guiones o guiones bajos." }
    val entry = config.characterLibrary.singleOrNull { it.id == id } ?: error("Personaje no encontrado.")
    val old = entry.draft.charFolder
    var draft = entry.draft
    // Old Psych projects predate this provenance field; capture it before renaming.
    if (draft.importedSourceFormat == "Psych" && draft.importedPsychCharacterId.isEmpty() &&
        config.importedModResources.any { it.converted && it.originalPath == "characters/$old.json" })
        draft = draft.copy(importedPsychCharacterId = old)
    draft = draft.copy(charId = newId)
    val target = draft.charFolder
    require(config.characterLibrary.none { it.id != id && it.draft.charFolder.equals(target, true) }) { "Ya existe un personaje con ese ID." }
    val songs = config.songs.map { song -> song.copy(
        player = if (song.player == old) target else song.player,
        opponent = if (song.opponent == old) target else song.opponent,
        girlfriend = if (song.girlfriend == old) target else song.girlfriend)
    }
    return config.copy(characterLibrary = config.characterLibrary.map { if (it.id == id) it.copy(draft = draft) else it }, songs = songs)
}

/** Remove one library identity and replace its direct song references with engine defaults. */
internal fun removeLibraryCharacter(config: ModConfig, id: String): ModConfig {
    val entry = config.characterLibrary.singleOrNull { it.id == id } ?: error("Personaje no encontrado.")
    val old = entry.draft.charFolder
    val original = entry.draft.importedVSliceCharacterId.ifEmpty { entry.draft.importedPsychCharacterId }.ifEmpty {
        if (entry.draft.importedSourceFormat == "Psych" && config.importedModResources.any { it.converted && it.originalPath == "characters/$old.json" }) old else ""
    }
    val a = config.resolvedAssignments()
    val remaining = config.characterLibrary.filterNot { it.id == id }.ifEmpty { listOf(CharacterEntry()) }
    val songs = config.songs.map { song -> song.copy(
        player = if (song.player == old) "bf" else song.player,
        opponent = if (song.opponent == old) "dad" else song.opponent,
        girlfriend = if (song.girlfriend == old) "gf" else song.girlfriend)
    }
    val originalShared = original.isNotEmpty() && remaining.any {
        it.draft.importedVSliceCharacterId == original || it.draft.importedPsychCharacterId == original
    }
    return config.copy(characterLibrary = remaining,
        selectedCharacterId = if (config.selectedCharacterId == id) remaining.first().id else config.selectedCharacterId,
        characterAssignments = a.copy(player = a.player.takeUnless { it == id } ?: "", opponent = a.opponent.takeUnless { it == id } ?: "", girlfriend = a.girlfriend.takeUnless { it == id } ?: ""), songs = songs,
        deletedImportedCharacterIds = if (original.isEmpty() || originalShared) config.deletedImportedCharacterIds else config.deletedImportedCharacterIds + original)
}
