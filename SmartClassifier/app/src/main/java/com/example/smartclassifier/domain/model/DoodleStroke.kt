package com.example.smartclassifier.domain.model

enum class AnchorMode { WORLD, FACE }

/**
 * WORLD: (x, y, z) = unit direction in world space.
 * FACE : (x, y) = face-relative (u, v), z unused.
 */
class DoodlePoint(val x: Float, val y: Float, val z: Float = 0f)

class DoodleStroke(
    val color: Int,
    val widthRatio: Float,
    val mode: AnchorMode
) {
    val points = ArrayList<DoodlePoint>()
}

/** Tracked face in sensor coordinates. [angleDeg] = head roll (ML Kit: positive = counter-clockwise). */
data class FaceAnchor(
    val cx: Float,
    val cy: Float,
    val size: Float,
    val angleDeg: Float
)