package com.example.smartclassifier.presentation.ar

import android.opengl.GLES20
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws strokes as camera-facing ribbons (triangle strips) in world space.
 * Per frame: beginFrame() -> addStroke() for each stroke -> draw(vp) (can be called for several surfaces).
 */
class StrokeRenderer {

    private class Range(val first: Int, val count: Int, val color: FloatArray)

    private var program = 0
    private var posLoc = 0
    private var edgeLoc = 0
    private var vpLoc = 0
    private var colorLoc = 0

    private var buffer: FloatBuffer = GlUtil.floatBuffer(INITIAL_FLOATS)
    private var floatCount = 0
    private val ranges = ArrayList<Range>()

    fun init() {
        program = GlUtil.createProgram(VERTEX, FRAGMENT)
        posLoc = GLES20.glGetAttribLocation(program, "a_Position")
        edgeLoc = GLES20.glGetAttribLocation(program, "a_Edge")
        vpLoc = GLES20.glGetUniformLocation(program, "u_VP")
        colorLoc = GLES20.glGetUniformLocation(program, "u_Color")
    }

    fun beginFrame() {
        floatCount = 0
        ranges.clear()
    }

    /**
     * @param world  n points (x,y,z) in world space
     * @param camPos camera position (ribbon faces the camera)
     * @param right  camera right vector (fallback when ribbon direction is degenerate)
     */
    fun addStroke(
        world: FloatArray, n: Int, halfWidth: Float, color: FloatArray,
        camPos: FloatArray, right: FloatArray
    ) {
        if (n < 2) return
        ensureCapacity(floatCount + n * 2 * 4)
        val firstVertex = floatCount / 4

        for (i in 0 until n) {
            val a = max(i - 1, 0)
            val b = min(i + 1, n - 1)
            val tx = world[3 * b] - world[3 * a]
            val ty = world[3 * b + 1] - world[3 * a + 1]
            val tz = world[3 * b + 2] - world[3 * a + 2]

            val px = world[3 * i]
            val py = world[3 * i + 1]
            val pz = world[3 * i + 2]
            val vx = camPos[0] - px
            val vy = camPos[1] - py
            val vz = camPos[2] - pz

            // side = tangent x viewDir
            var sx = ty * vz - tz * vy
            var sy = tz * vx - tx * vz
            var sz = tx * vy - ty * vx
            var len = sqrt(sx * sx + sy * sy + sz * sz)
            if (len < 1e-8f) {
                sx = right[0]; sy = right[1]; sz = right[2]; len = 1f
            }
            val k = halfWidth / len
            sx *= k; sy *= k; sz *= k

            put(px + sx, py + sy, pz + sz, 1f)
            put(px - sx, py - sy, pz - sz, -1f)
        }
        ranges.add(Range(firstVertex, n * 2, color))
    }

    private fun put(x: Float, y: Float, z: Float, edge: Float) {
        buffer.put(floatCount, x)
        buffer.put(floatCount + 1, y)
        buffer.put(floatCount + 2, z)
        buffer.put(floatCount + 3, edge)
        floatCount += 4
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.capacity()) return
        val bigger = GlUtil.floatBuffer(max(needed, buffer.capacity() * 2))
        for (i in 0 until floatCount) bigger.put(i, buffer.get(i))
        buffer = bigger
    }

    fun draw(vp: FloatArray) {
        if (ranges.isEmpty()) return

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(vpLoc, 1, false, vp, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        buffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, STRIDE, buffer)
        buffer.position(3)
        GLES20.glVertexAttribPointer(edgeLoc, 1, GLES20.GL_FLOAT, false, STRIDE, buffer)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(edgeLoc)

        for (r in ranges) {
            GLES20.glUniform4fv(colorLoc, 1, r.color, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, r.first, r.count)
        }

        buffer.position(0)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(edgeLoc)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private companion object {
        const val STRIDE = 16
        const val INITIAL_FLOATS = 4 * 4096

        const val VERTEX = """
            uniform mat4 u_VP;
            attribute vec3 a_Position;
            attribute float a_Edge;
            varying float v_Edge;
            void main() {
                gl_Position = u_VP * vec4(a_Position, 1.0);
                v_Edge = a_Edge;
            }
        """
        const val FRAGMENT = """
            precision mediump float;
            uniform vec4 u_Color;
            varying float v_Edge;
            void main() {
                float a = 1.0 - smoothstep(0.6, 1.0, abs(v_Edge));
                gl_FragColor = vec4(u_Color.rgb, u_Color.a * a);
            }
        """
    }
}