package com.example.smartclassifier.presentation.ar

import android.opengl.GLES20
import java.nio.FloatBuffer

/** One 3D text label. [model] = anchor pose matrix (x right, y up, z towards the viewer). */
class LabelInstance(
    val textureId: Int,
    val width: Float,       // world metres
    val height: Float,
    val color: FloatArray,  // front face
    val dark: FloatArray    // extruded sides
) {
    val model = FloatArray(16)
    var visible = false
}

/**
 * Draws text labels as stacked textured quads: the back layers (darker) fake the extrusion,
 * so the text looks like a solid 3D object when you walk around it.
 */
class LabelRenderer {

    private var program = 0
    private var dataLoc = 0
    private var vpLoc = 0
    private var modelLoc = 0
    private var sizeLoc = 0
    private var zLoc = 0
    private var colorLoc = 0
    private var flipLoc = 0
    private var samplerLoc = 0

    // x, y, u, v
    private val quad: FloatBuffer = GlUtil.floatBuffer(16).apply {
        put(floatArrayOf(
            -0.5f, 0.5f, 0f, 0f,
            -0.5f, -0.5f, 0f, 1f,
            0.5f, 0.5f, 1f, 0f,
            0.5f, -0.5f, 1f, 1f
        ))
        position(0)
    }

    fun init() {
        program = GlUtil.createProgram(VERTEX, FRAGMENT)
        dataLoc = GLES20.glGetAttribLocation(program, "a_Data")
        vpLoc = GLES20.glGetUniformLocation(program, "u_VP")
        modelLoc = GLES20.glGetUniformLocation(program, "u_Model")
        sizeLoc = GLES20.glGetUniformLocation(program, "u_Size")
        zLoc = GLES20.glGetUniformLocation(program, "u_Z")
        colorLoc = GLES20.glGetUniformLocation(program, "u_Color")
        flipLoc = GLES20.glGetUniformLocation(program, "u_Flip")
        samplerLoc = GLES20.glGetUniformLocation(program, "u_Texture")
    }

    fun draw(labels: List<LabelInstance>, vp: FloatArray, camPos: FloatArray) {
        if (labels.none { it.visible }) return

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(vpLoc, 1, false, vp, 0)
        GLES20.glUniform1i(samplerLoc, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        quad.position(0)
        GLES20.glVertexAttribPointer(dataLoc, 4, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(dataLoc)

        for (label in labels) {
            if (!label.visible) continue
            val m = label.model

            // Is the viewer in front of the text (z axis points to the front face)?
            val toCam = (camPos[0] - m[12]) * m[8] + (camPos[1] - m[13]) * m[9] + (camPos[2] - m[14]) * m[10]
            val front = toCam > 0f

            GLES20.glUniformMatrix4fv(modelLoc, 1, false, m, 0)
            GLES20.glUniform2f(sizeLoc, label.width, label.height)
            GLES20.glUniform1f(flipLoc, if (front) 0f else 1f)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, label.textureId)

            val step = label.height * LAYER_STEP
            // far -> near so the nearest layer ends up on top
            val order = if (front) (LAYERS downTo 0) else (0..LAYERS)
            for (k in order) {
                GLES20.glUniform1f(zLoc, -k * step)
                GLES20.glUniform4fv(colorLoc, 1, if (k == 0) label.color else label.dark, 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
        }

        GLES20.glDisableVertexAttribArray(dataLoc)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private companion object {
        const val LAYERS = 8
        const val LAYER_STEP = 0.012f   // x height per layer -> total depth ~10% of text height

        const val VERTEX = """
            uniform mat4 u_VP;
            uniform mat4 u_Model;
            uniform vec2 u_Size;
            uniform float u_Z;
            attribute vec4 a_Data;
            varying vec2 v_Tex;
            void main() {
                vec4 local = vec4(a_Data.x * u_Size.x, a_Data.y * u_Size.y, u_Z, 1.0);
                gl_Position = u_VP * u_Model * local;
                v_Tex = a_Data.zw;
            }
        """
        const val FRAGMENT = """
            precision mediump float;
            uniform sampler2D u_Texture;
            uniform vec4 u_Color;
            uniform float u_Flip;
            varying vec2 v_Tex;
            void main() {
                vec2 uv = vec2(mix(v_Tex.x, 1.0 - v_Tex.x, u_Flip), v_Tex.y);
                float a = texture2D(u_Texture, uv).a;
                gl_FragColor = vec4(u_Color.rgb, u_Color.a * a);
            }
        """
    }
}