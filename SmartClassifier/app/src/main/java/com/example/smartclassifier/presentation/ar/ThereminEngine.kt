package com.example.smartclassifier.presentation.ar

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ThereminEngine {

    @Volatile
    var targetFreq = 440f

    @Volatile
    var targetAmp = 0f

    /** 0 = sine, 1 = square, 2 = saw, 3 = triangle */
    @Volatile
    var wave = 0

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            val sr = 44100
            val minBuf = AudioTrack.getMinBufferSize(
                sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder().setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                ).setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                ).setBufferSizeInBytes(maxOf(minBuf, 4096)).setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()

            val buf = ShortArray(512)
            var phase = 0.0
            var f = targetFreq.toDouble()
            var a = 0.0
            var lp = 0.0
            var vib = 0.0

            while (running) {
                val tf = targetFreq.toDouble()
                val ta = targetAmp.toDouble()
                val w = wave
                for (i in buf.indices) {
                    f += (tf - f) * 0.0018          // portamento (glide between notes)
                    a += (ta - a) * 0.0025          // smooth volume
                    vib += 2.0 * PI * 5.5 / sr      // subtle 5.5 Hz vibrato = "theremin feel"
                    val fv = f * (1.0 + 0.004 * sin(vib))
                    phase += fv / sr
                    if (phase >= 1.0) phase -= 1.0

                    var s = when (w) {
                        1 -> if (phase < 0.5) 0.6 else -0.6
                        2 -> (2.0 * phase - 1.0) * 0.7
                        3 -> 4.0 * abs(phase - 0.5) - 1.0
                        else -> sin(2.0 * PI * phase)
                    }
                    if (w == 1 || w == 2) {          // soften harsh waves
                        lp += (s - lp) * 0.3
                        s = lp
                    }
                    buf[i] = (s * a * 0.6 * Short.MAX_VALUE).toInt().toShort()
                }
                track.write(buf, 0, buf.size)
            }
            try {
                track.stop()
            } catch (_: Exception) {
            }
            track.release()
        }.also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    fun stop() {
        running = false
        targetAmp = 0f
        thread = null
    }
}