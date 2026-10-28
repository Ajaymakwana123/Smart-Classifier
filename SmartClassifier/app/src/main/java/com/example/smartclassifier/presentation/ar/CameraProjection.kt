package com.example.smartclassifier.presentation.ar


import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class CameraProjection private constructor(
    private val fx: Float,
    private val fy: Float,
    private val cx: Float,
    private val cy: Float,
    private val u: FloatArray,    // sensor +x axis in device frame
    private val v: FloatArray,    // sensor +y axis in device frame
    private val fwd: FloatArray   // viewing direction in device frame
) {

    /** Sensor pixel -> unit direction in world space. [r] = device->world matrix. */
    fun sensorToWorld(sx: Float, sy: Float, r: FloatArray): FloatArray {
        val a = (sx - cx) / fx
        val b = (sy - cy) / fy
        val dx = u[0] * a + v[0] * b + fwd[0]
        val dy = u[1] * a + v[1] * b + fwd[1]
        val dz = u[2] * a + v[2] * b + fwd[2]
        val wx = r[0] * dx + r[1] * dy + r[2] * dz
        val wy = r[3] * dx + r[4] * dy + r[5] * dz
        val wz = r[6] * dx + r[7] * dy + r[8] * dz
        val n = sqrt(wx * wx + wy * wy + wz * wz)
        return floatArrayOf(wx / n, wy / n, wz / n)
    }

    /** World direction -> sensor pixel written to out[offset], out[offset+1]. False if behind camera. */
    fun worldToSensor(
        wx: Float, wy: Float, wz: Float, r: FloatArray, out: FloatArray, offset: Int
    ): Boolean {
        // device = R^T * world
        val dx = r[0] * wx + r[3] * wy + r[6] * wz
        val dy = r[1] * wx + r[4] * wy + r[7] * wz
        val dz = r[2] * wx + r[5] * wy + r[8] * wz
        val z = dx * fwd[0] + dy * fwd[1] + dz * fwd[2]
        if (z < 0.05f) return false
        val x = dx * u[0] + dy * u[1] + dz * u[2]
        val y = dx * v[0] + dy * v[1] + dz * v[2]
        out[offset] = cx + fx * x / z
        out[offset + 1] = cy + fy * y / z
        return true
    }

    companion object {
        fun create(context: Context, front: Boolean): CameraProjection {
            val wanted = if (front) CameraCharacteristics.LENS_FACING_FRONT
            else CameraCharacteristics.LENS_FACING_BACK

            var width = 4000f
            var height = 3000f
            var cx = width / 2f
            var cy = height / 2f
            var orientation = 90
            var fx = 0f
            var fy = 0f

            try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = manager.cameraIdList.firstOrNull {
                    manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted
                }
                if (id != null) {
                    val ch = manager.getCameraCharacteristics(id)
                    orientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                    ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
                        width = it.width().toFloat()
                        height = it.height().toFloat()
                        cx = it.exactCenterX()
                        cy = it.exactCenterY()
                    }
                    val pixel = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                    if (pixel != null && phys != null && focal != null && phys.width > 0f && phys.height > 0f) {
                        fx = focal * pixel.width / phys.width
                        fy = focal * pixel.height / phys.height
                    }
                }
            } catch (e: Exception) {
                // use defaults below
            }

            if (fx <= 0f || fy <= 0f) {   // fallback: ~65 degree horizontal FOV
                fx = (width / 2f) / tan(Math.toRadians(32.5)).toFloat()
                fy = fx
            }

            val t = Math.toRadians(orientation.toDouble())
            val c = cos(t).toFloat()
            val s = sin(t).toFloat()

            // Sensor axes expressed in the device frame (x right, y up, z out of screen)
            return if (!front) {
                CameraProjection(fx, fy, cx, cy,
                    u = floatArrayOf(c, -s, 0f),
                    v = floatArrayOf(-s, -c, 0f),
                    fwd = floatArrayOf(0f, 0f, -1f))
            } else {
                CameraProjection(fx, fy, cx, cy,
                    u = floatArrayOf(-c, -s, 0f),
                    v = floatArrayOf(s, -c, 0f),
                    fwd = floatArrayOf(0f, 0f, 1f))
            }
        }
    }
}