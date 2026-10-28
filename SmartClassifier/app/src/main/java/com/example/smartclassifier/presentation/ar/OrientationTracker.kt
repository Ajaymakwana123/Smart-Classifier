package com.example.smartclassifier.presentation.ar

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper

class OrientationTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val matrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    val isAvailable: Boolean get() = sensor != null

    fun start() {
        sensor?.let {
            sensorManager.registerListener(
                this, it, SensorManager.SENSOR_DELAY_GAME, Handler(Looper.getMainLooper())
            )
        }
    }

    fun stop() = sensorManager.unregisterListener(this)

    /** Identity if no sensor. Read on the main thread only. */
    fun rotation(): FloatArray = matrix

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}