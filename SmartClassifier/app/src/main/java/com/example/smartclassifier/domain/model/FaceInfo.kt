package com.example.smartclassifier.domain.model

import android.graphics.Rect

enum class Mood(val label: String, val emoji: String) {
    HAPPY("Happy", "😊"),
    NEUTRAL("Neutral", "😐"),
    SAD("Sad / Serious", "😞"),
    UNKNOWN("Unknown", "❓")
}

enum class BodyPose(val label: String, val emoji: String) {
    STANDING("Standing", "🚶"),
    SITTING("Sitting", "💺"),
    LYING("Lying down", "🛏"),
    UPPER_BODY_ONLY("Upper body only", "👤"),
    UNKNOWN("Not detected", "❓")
}

data class FaceInfo(
    val id: Int,
    val boundingBox: Rect,
    val rollAngle: Float,
    val smileProbability: Float?,
    val mood: Mood,
    val pose: BodyPose
)
