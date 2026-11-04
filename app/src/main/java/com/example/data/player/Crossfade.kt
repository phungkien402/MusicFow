package com.example.data.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Crossfade {
    fun equalPowerGains(progress: Float): Pair<Float, Float> {
        val p = progress.coerceIn(0f, 1f)
        val angle = p * (PI.toFloat() / 2f)
        val outGain = cos(angle)
        val inGain = sin(angle)
        return Pair(outGain, inGain)
    }

    fun shouldStart(
        positionMs: Long,
        durationMs: Long,
        crossfadeMs: Long,
        hasNext: Boolean,
        repeatOne: Boolean
    ): Boolean {
        return crossfadeMs > 0 &&
                durationMs >= 2 * crossfadeMs &&
                hasNext &&
                !repeatOne &&
                (durationMs - positionMs <= crossfadeMs)
    }
}
