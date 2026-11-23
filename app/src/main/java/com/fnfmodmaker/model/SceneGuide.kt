package com.fnfmodmaker.model

/** Approximate anchors on the 1280 × 720 reference scene; not engine stage coordinates. */
data class SceneGuideAnchor(val x: Float, val y: Float)

val GUIDE_OPPONENT = SceneGuideAnchor(155f, 180f)
val GUIDE_COMPANION = SceneGuideAnchor(515f, 145f)
val GUIDE_PLAYER = SceneGuideAnchor(900f, 180f)

fun characterGuideAnchor(side: CharacterSide): SceneGuideAnchor =
    if (side == CharacterSide.PLAYER) GUIDE_PLAYER else GUIDE_OPPONENT
