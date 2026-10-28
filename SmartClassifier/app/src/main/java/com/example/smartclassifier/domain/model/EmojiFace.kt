package com.example.smartclassifier.domain.model

import android.graphics.Matrix
import android.graphics.PointF

enum class EmojiStyle(val label: String, val icon: String) {
    CLASSIC("Classic", "😀"),
    CAT("Cat", "🐱"),
    PANDA("Panda", "🐼"),
    ROBOT("Robot", "🤖")
}

class EmojiFace(
    val uprightToSensor: Matrix,
    val cx: Float, val cy: Float,
    val width: Float, val height: Float,
    val rollDeg: Float,                 // head tilt (ML Kit: positive = counter-clockwise)
    val eyeLeft: PointF, val eyeRight: PointF,
    val openLeft: Float, val openRight: Float,   // 0 = closed, 1 = open
    val browLeft: PointF, val browRight: PointF,
    val nose: PointF,
    val mouth: PointF, val mouthWidth: Float,
    val mouthOpen: Float,               // 0..1
    val smile: Float,                   // 0..1
    val timeMs: Long
)