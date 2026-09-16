package net.sgransoft.portalsnap

import kotlin.math.PI
import kotlin.math.sin

/** A voice effect that goes beyond a pitch ratio, applied to the recorded voice after the shifter. */
enum class VoiceFx { NONE, ROBOT }

/**
 * The robot: a ring modulator, then a short feedback comb. Multiplying by a low sine splits every
 * harmonic into two a fixed distance apart, which is the metallic, Dalek part. The comb rings at
 * one fixed pitch whatever the voice does, which is the flat, buzzy part. Cheap, no FFT, and it
 * keeps the words clear because the dry voice is still in the mix.
 */
class RobotVoice {
    private val comb = FloatArray(COMB)
    private var w = 0
    private var phase = 0.0

    fun process(buf: FloatArray, n: Int) {
        for (i in 0 until n) {
            val x = buf[i]
            val ring = x * sin(phase).toFloat()
            phase += STEP
            if (phase > TAU_D) phase -= TAU_D
            val y = x * DRY + ring * WET + comb[w] * FEEDBACK
            comb[w] = y
            w = (w + 1) % COMB
            buf[i] = y * OUT
        }
    }

    private companion object {
        const val TAU_D = PI * 2
        /** The ring modulator's frequency. Around 30-60Hz is the classic Dalek range. */
        const val RING_HZ = 50.0
        val STEP = TAU_D * RING_HZ / MicHub.RATE
        /** The comb's delay: 48000 / 436 is a buzz at 110Hz. */
        const val COMB = 436
        const val FEEDBACK = 0.55f
        const val DRY = 0.35f
        const val WET = 0.9f
        /** Brings the comb's gain at resonance, about 2.2x, back to the voice's level. */
        const val OUT = 0.5f
    }
}
