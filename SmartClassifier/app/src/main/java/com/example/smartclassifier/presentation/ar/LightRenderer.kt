package com.example.smartclassifier.presentation.ar

import android.opengl.GLES20
import java.nio.FloatBuffer
import kotlin.math.max

enum class LightPattern { CHASE, ALTERNATE, TWINKLE }

/**
 * Draws glowing light bulbs (camera-facing quads with a radial glow, additive blending).
 * Per frame: beginFrame() -> addBulb() for every bulb -> draw(vp) (can be called for several surfaces).
 */
class LightRenderer {

    private var program = 0
    private var posLoc = 0
    private var uvLoc = 0
    private var colorLoc = 0
    private var vpLoc = 0

    private var buffer: FloatBuffer = GlUtil.floatBuffer(INITIAL_FLOATS)
    private var floatCount = 0
    private var vertexCount = 0

    fun init() {
        program = GlUtil.createProgram(VERTEX, FRAGMENT)
        posLoc = GLES20.glGetAttribLocation(program, "a_Position")
        uvLoc = GLES20.glGetAttribLocation(program, "a_Uv")
        colorLoc = GLES20.glGetAttribLocation(program, "a_Color")
        vpLoc = GLES20.glGetUniformLocation(program, "u_VP")
    }

    fun beginFrame() {
        floatCount = 0
        vertexCount = 0
    }

    /** [half] = half size of the glow quad in metres, [intensity] 0..1 = how bright the bulb is now. */
    fun addBulb(
        x: Float, y: Float, z: Float, half: Float,
        r: Float, g: Float, b: Float, intensity: Float,
        right: FloatArray, up: FloatArray
    ) {
        ensureCapacity(floatCount + 6 * STRIDE_FLOATS)

        fun corner(sx: Float, sy: Float, out: FloatArray) {
            out[0] = x + right[0] * sx * half + up[0] * sy * half
            out[1] = y + right[1] * sx * half + up[1] * sy * half
            out[2] = z + right[2] * sx * half + up[2] * sy * half
        }
        val c0 = FloatArray(3); val c1 = FloatArray(3); val c2 = FloatArray(3); val c3 = FloatArray(3)
        corner(-1f, -1f, c0); corner(1f, -1f, c1); corner(1f, 1f, c2); corner(-1f, 1f, c3)

        put(c0, -1f, -1f, r, g, b, intensity)
        put(c1, 1f, -1f, r, g, b, intensity)
        put(c2, 1f, 1f, r, g, b, intensity)
        put(c0, -1f, -1f, r, g, b, intensity)
        put(c2, 1f, 1f, r, g, b, intensity)
        put(c3, -1f, 1f, r, g, b, intensity)
    }

    private fun put(p: FloatArray, u: Float, v: Float, r: Float, g: Float, b: Float, i: Float) {
        buffer.put(floatCount, p[0])
        buffer.put(floatCount + 1, p[1])
        buffer.put(floatCount + 2, p[2])
        buffer.put(floatCount + 3, u)
        buffer.put(floatCount + 4, v)
        buffer.put(floatCount + 5, r)
        buffer.put(floatCount + 6, g)
        buffer.put(floatCount + 7, b)
        buffer.put(floatCount + 8, i)
        floatCount += STRIDE_FLOATS
        vertexCount++
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.capacity()) return
        val bigger = GlUtil.floatBuffer(max(needed, buffer.capacity() * 2))
        for (i in 0 until floatCount) bigger.put(i, buffer.get(i))
        buffer = bigger
    }

    fun draw(vp: FloatArray) {
        if (vertexCount == 0) return

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(vpLoc, 1, false, vp, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)   // additive glow

        val stride = STRIDE_FLOATS * 4
        buffer.position(0)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, stride, buffer)
        buffer.position(3)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, stride, buffer)
        buffer.position(5)
        GLES20.glVertexAttribPointer(colorLoc, 4, GLES20.GL_FLOAT, false, stride, buffer)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glEnableVertexAttribArray(colorLoc)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

        buffer.position(0)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glDisableVertexAttribArray(colorLoc)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private companion object {
        const val STRIDE_FLOATS = 9
        const val INITIAL_FLOATS = STRIDE_FLOATS * 6 * 256

        const val VERTEX = """
            uniform mat4 u_VP;
            attribute vec3 a_Position;
            attribute vec2 a_Uv;
            attribute vec4 a_Color;
            varying vec2 v_Uv;
            varying vec4 v_Color;
            void main() {
                gl_Position = u_VP * vec4(a_Position, 1.0);
                v_Uv = a_Uv;
                v_Color = a_Color;
            }
        """
        const val FRAGMENT = """
            precision mediump float;
            varying vec2 v_Uv;
            varying vec4 v_Color;
            void main() {
                float d = clamp(length(v_Uv), 0.0, 1.0);
                float core = smoothstep(0.30, 0.0, d);
                float glow = pow(1.0 - d, 2.5);
                vec3 col = mix(v_Color.rgb, vec3(1.0), core * 0.85);
                float alpha = clamp((glow * 0.9 + core) * v_Color.a, 0.0, 1.0);
                gl_FragColor = vec4(col, alpha);
            }
        """
    }
}